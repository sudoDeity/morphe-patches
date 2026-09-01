/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Utils;

/**
 * Resolves RedGIFs media before Reddit creates its MediaSource, so Reddit/Media3 own one
 * consistent URI from MediaItem through MediaPeriod and track publication.
 */
@SuppressWarnings("unused")
public final class RedgifsPlaybackPatch {
    private static final String AUTH_URL = "https://api.redgifs.com/v2/auth/temporary";
    private static final String GIF_INFO_PREFIX = "https://api.redgifs.com/v2/gifs/";
    private static final String ORIGIN = "https://www.redgifs.com";
    private static final String REFERER = ORIGIN + "/";
    private static final String MEDIA_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

    private static final int MAX_PENDING_RESOLUTIONS = 32;
    private static final int MAX_RETRY_EXPONENT = 6;
    private static final long DIRECT_URL_FALLBACK_LIFETIME_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final long DIRECT_URL_EXPIRY_MARGIN_MILLIS = TimeUnit.SECONDS.toMillis(5);
    private static final long INITIAL_RETRY_DELAY_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final long MAX_RETRY_DELAY_NANOS = TimeUnit.MINUTES.toNanos(1);
    private static final long PLAYBACK_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(800);

    private static final Pattern REDGIFS_SLUG = Pattern.compile(
            "https?://(?:[A-Za-z0-9-]+\\.)*redgifs\\.com/(?:watch|ifr)/([A-Za-z0-9_-]+)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern REDDIT_MEDIA_ID = Pattern.compile(
            "https?://(?:v\\.redd\\.it|packaged-media\\.redd\\.it)/([A-Za-z0-9]+)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TOKEN_JSON = Pattern.compile(
            "\\\"token\\\"\\s*:\\s*\\\"([^\\\"]+)\\\""
    );
    private static final Pattern SD_URL_JSON = Pattern.compile(
            "\\\"sd\\\"\\s*:\\s*\\\"([^\\\"]+)\\\""
    );
    private static final Pattern HD_URL_JSON = Pattern.compile(
            "\\\"hd\\\"\\s*:\\s*\\\"([^\\\"]+)\\\""
    );
    private static final Pattern GIF_DELETED_JSON = Pattern.compile(
            "\\\"code\\\"\\s*:\\s*\\\"GifDeleted\\\""
    );

    private static final ThreadLocal<String> PENDING_REDGIFS_SLUG = new ThreadLocal<>();

    private static final ConcurrentHashMap<String, String> MEDIA_TO_SLUG =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ResolutionEntry> RESOLUTIONS_BY_SLUG =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ResolvedRoute> ROUTES_BY_URL =
            new ConcurrentHashMap<>();

    private static final ThreadPoolExecutor RESOLVER = new ThreadPoolExecutor(
            2,
            2,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_PENDING_RESOLUTIONS),
            new ThreadFactory() {
                private int nextId = 1;

                @Override
                public synchronized Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "morphe-redgifs-" + nextId++);
                    thread.setDaemon(true);
                    return thread;
                }
            },
            new ThreadPoolExecutor.AbortPolicy()
    );

    private static final Object TOKEN_LOCK = new Object();
    private static volatile String temporaryToken;
    private static volatile DataSpecAccess dataSpecAccess;
    private static volatile Method redditVideoGetDashUrlMethod;

    private RedgifsPlaybackPatch() {
    }

    public static void prewarmRedgifs(String embedHtml, String url) {
        String slug = findRedgifsSlug(embedHtml, url);
        if (slug != null) startResolution(entryForSlug(slug));
    }

    public static void prewarmCachedLinkJson(String linkJson) {
        if (linkJson == null || linkJson.isEmpty()) return;
        try {
            JSONObject link = new JSONObject(linkJson);
            String slug = findFirst(REDGIFS_SLUG, link.optString("url", null));
            if (slug != null) startResolution(entryForSlug(slug));
        } catch (JSONException | RuntimeException ignored) {
        }
    }

    public static void captureMediaFragment(Object mediaFragment) {
        String slug = extractConfirmedRedgifsSlug(mediaFragment);
        if (slug == null) PENDING_REDGIFS_SLUG.remove();
        else PENDING_REDGIFS_SLUG.set(slug);
    }

    public static void registerRedditVideo(Object redditVideo) {
        String slug = PENDING_REDGIFS_SLUG.get();
        PENDING_REDGIFS_SLUG.remove();
        if (slug == null) return;

        String mediaId = redditVideoMediaId(redditVideo);
        if (mediaId != null) registerIdentity(mediaId, slug);
    }

    public static void registerCellGroup(Object cellGroupFragment) {
        if (cellGroupFragment == null || !"xb7".equals(cellGroupFragment.getClass().getName())) {
            return;
        }

        try {
            Object cellsValue = accessibleField(cellGroupFragment.getClass(), "c")
                    .get(cellGroupFragment);
            if (!(cellsValue instanceof Iterable<?>)) return;

            String slug = null;
            String mediaId = null;
            Field metadataField = null;
            Field legacyVideoField = null;

            for (Object cell : (Iterable<?>) cellsValue) {
                if (cell == null || !"vb7".equals(cell.getClass().getName())) continue;

                if (metadataField == null) {
                    metadataField = accessibleField(cell.getClass(), "C");
                    legacyVideoField = accessibleField(cell.getClass(), "z");
                }

                Object metadata = metadataField.get(cell);
                if (metadata != null && "h2u".equals(metadata.getClass().getName())) {
                    Object mediaPathValue = accessibleField(metadata.getClass(), "l").get(metadata);
                    Object mediaDomainValue = accessibleField(metadata.getClass(), "m").get(metadata);

                    if (mediaPathValue instanceof String &&
                            mediaDomainValue instanceof String &&
                            "redgifs".equalsIgnoreCase((String) mediaDomainValue)) {
                        String candidate = findFirst(REDGIFS_SLUG, (String) mediaPathValue);
                        if (candidate != null) {
                            if (slug != null && !slug.equals(candidate)) return;
                            slug = candidate;
                        }
                    }
                }

                Object legacyVideo = legacyVideoField.get(cell);
                if (legacyVideo == null || !"ygr".equals(legacyVideo.getClass().getName())) {
                    continue;
                }
                Object media = accessibleField(legacyVideo.getClass(), "b").get(legacyVideo);
                if (media == null || !"vgr".equals(media.getClass().getName())) continue;
                Object mediaSource = accessibleField(media.getClass(), "b").get(media);
                if (mediaSource == null || !"nc7".equals(mediaSource.getClass().getName())) {
                    continue;
                }

                Object redditPathValue = accessibleField(mediaSource.getClass(), "a").get(mediaSource);
                String candidate = redditPathValue instanceof String
                        ? findFirst(REDDIT_MEDIA_ID, (String) redditPathValue)
                        : null;
                if (candidate != null) {
                    if (mediaId != null && !mediaId.equals(candidate)) return;
                    mediaId = candidate;
                }
            }

            if (slug != null && mediaId != null) registerIdentity(mediaId, slug);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    public static void registerCachedRedgifs(String linkUrl, String dashUrl) {
        String slug = findRedgifsSlug(null, linkUrl);
        String mediaId = findFirst(REDDIT_MEDIA_ID, dashUrl);
        if (slug != null && mediaId != null) registerIdentity(mediaId, slug);
    }

    private static void registerIdentity(String mediaId, String slug) {
        MEDIA_TO_SLUG.put(mediaId, slug);
        startResolution(entryForSlug(slug));
    }

    public static boolean overrideIsGif(boolean originalIsGif, Object redditVideo) {
        if (!originalIsGif) return false;
        String mediaId = redditVideoMediaId(redditVideo);
        if (mediaId == null) return true;

        String slug = MEDIA_TO_SLUG.get(mediaId);
        if (slug == null) return true;

        ResolutionEntry entry = RESOLUTIONS_BY_SLUG.get(slug);
        if (entry == null) return true;
        synchronized (entry) {
            return entry.terminalFailure == TerminalFailure.GIF_DELETED;
        }
    }

    public static String rewritePlaybackUrl(String currentUrl) {
        if (currentUrl == null || currentUrl.isEmpty()) return currentUrl;

        Uri currentUri;
        try {
            currentUri = Uri.parse(currentUrl);
        } catch (RuntimeException ignored) {
            return currentUrl;
        }
        if (isRedgifsMedia(currentUri)) return currentUrl;

        String mediaId = findFirst(REDDIT_MEDIA_ID, currentUrl);
        if (mediaId == null) return currentUrl;
        String slug = MEDIA_TO_SLUG.get(mediaId);
        if (slug == null) return currentUrl;

        ResolutionEntry entry = entryForSlug(slug);
        ResolvedRoute route = validRoute(entry, System.nanoTime());
        if (route != null) return route.directUrl;

        startResolution(entry);
        if (Looper.myLooper() != Looper.getMainLooper()) {
            route = awaitRoute(entry);
            if (route != null) return route.directUrl;
        }
        return currentUrl;
    }

    public static boolean forceCacheUriValidation(boolean original, Object uriObject) {
        return original || (uriObject instanceof Uri && isRedgifsMedia((Uri) uriObject));
    }

    public static Object prepareDataSpec(Object dataSpec) {
        if (dataSpec == null) return null;
        try {
            DataSpecAccess access = getDataSpecAccess(dataSpec);
            Uri uri = (Uri) access.uriField.get(dataSpec);
            if (!isRedgifsMedia(uri)) return dataSpec;

            ResolvedRoute route = ROUTES_BY_URL.get(uri.toString());
            Object builder = access.buildUpon.invoke(dataSpec);
            if (route != null && route.cacheKey != null) {
                access.builderKeyField.set(builder, route.cacheKey);
            }

            @SuppressWarnings("unchecked")
            Map<String, String> existingHeaders =
                    (Map<String, String>) access.builderRequestHeadersField.get(builder);
            access.builderRequestHeadersField.set(
                    builder,
                    redgifsRequestHeaders(existingHeaders, route == null ? null : route.slug)
            );
            return access.build.invoke(builder);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return dataSpec;
        }
    }

    private static String findRedgifsSlug(String embedHtml, String url) {
        String slug = findFirst(REDGIFS_SLUG, url);
        return slug != null ? slug : findFirst(REDGIFS_SLUG, embedHtml);
    }

    private static String extractConfirmedRedgifsSlug(Object mediaFragment) {
        if (mediaFragment == null || !"sgt".equals(mediaFragment.getClass().getName())) {
            return null;
        }
        try {
            Object redditVideoMedia = accessibleField(mediaFragment.getClass(), "e")
                    .get(mediaFragment);
            if (redditVideoMedia == null || !"rgt".equals(redditVideoMedia.getClass().getName())) {
                return null;
            }
            Object videoMedia = accessibleField(redditVideoMedia.getClass(), "b")
                    .get(redditVideoMedia);
            if (videoMedia == null || !"gim0".equals(videoMedia.getClass().getName())) return null;

            Object embed = accessibleField(videoMedia.getClass(), "a").get(videoMedia);
            Object url = accessibleField(videoMedia.getClass(), "b").get(videoMedia);
            return findRedgifsSlug(
                    embed instanceof String ? (String) embed : null,
                    url instanceof String ? (String) url : null
            );
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static String redditVideoMediaId(Object redditVideo) {
        if (redditVideo == null ||
                !"com.reddit.domain.model.RedditVideo".equals(redditVideo.getClass().getName())) {
            return null;
        }
        try {
            Method method = redditVideoGetDashUrlMethod;
            if (method == null || method.getDeclaringClass() != redditVideo.getClass()) {
                method = redditVideo.getClass().getMethod("getDashUrl");
                redditVideoGetDashUrlMethod = method;
            }
            Object value = method.invoke(redditVideo);
            return value instanceof String ? findFirst(REDDIT_MEDIA_ID, (String) value) : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static ResolutionEntry entryForSlug(String slug) {
        return RESOLUTIONS_BY_SLUG.computeIfAbsent(slug, ResolutionEntry::new);
    }

    private static ResolvedRoute validRoute(ResolutionEntry entry, long nowNanos) {
        ResolvedRoute route = entry.route;
        if (route == null) return null;

        String networkIdentity = currentNetworkIdentity();
        boolean sameNetwork = route.networkIdentity == null || networkIdentity == null ||
                route.networkIdentity.equals(networkIdentity);
        if (sameNetwork && nowNanos < route.expiresAtNanos) return route;

        synchronized (entry) {
            if (entry.route == route) entry.route = null;
        }
        return null;
    }

    private static ResolvedRoute awaitRoute(ResolutionEntry entry) {
        long deadline = System.nanoTime() + PLAYBACK_WAIT_NANOS;
        synchronized (entry) {
            while (true) {
                ResolvedRoute route = validRoute(entry, System.nanoTime());
                if (route != null) return route;
                if (entry.terminalFailure == TerminalFailure.GIF_DELETED || !entry.resolving) {
                    return null;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return null;
                try {
                    TimeUnit.NANOSECONDS.timedWait(entry, remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
    }

    private static void startResolution(ResolutionEntry entry) {
        synchronized (entry) {
            long nowNanos = System.nanoTime();
            if (entry.resolving ||
                    entry.terminalFailure == TerminalFailure.GIF_DELETED ||
                    validRoute(entry, nowNanos) != null ||
                    nowNanos < entry.nextRetryAtNanos) {
                return;
            }
            entry.resolving = true;
        }

        try {
            RESOLVER.execute(() -> {
                ResolutionResult result = ResolutionResult.TRANSIENT_FAILURE;
                try {
                    result = resolveDirectMediaUrl(entry.slug);
                } catch (RuntimeException ignored) {
                }
                completeResolution(entry, result);
            });
        } catch (RuntimeException exception) {
            completeResolution(entry, ResolutionResult.TRANSIENT_FAILURE);
        }
    }

    private static void completeResolution(ResolutionEntry entry, ResolutionResult result) {
        ResolvedRoute published = null;
        if (result.directUrl != null) {
            published = new ResolvedRoute(
                    entry.slug,
                    result.directUrl,
                    result.cacheKey,
                    directUrlExpiryDeadlineNanos(
                            result.directUrl,
                            System.nanoTime(),
                            System.currentTimeMillis()
                    ),
                    currentNetworkIdentity()
            );
            ROUTES_BY_URL.put(published.directUrl, published);
        }

        synchronized (entry) {
            entry.resolving = false;
            if (published != null) {
                entry.route = published;
                entry.failureCount = 0;
                entry.nextRetryAtNanos = 0;
                entry.terminalFailure = TerminalFailure.NONE;
            } else if (result.terminalFailure == TerminalFailure.GIF_DELETED) {
                entry.route = null;
                entry.failureCount = 0;
                entry.nextRetryAtNanos = 0;
                entry.terminalFailure = TerminalFailure.GIF_DELETED;
            } else {
                entry.failureCount = Math.min(entry.failureCount + 1, MAX_RETRY_EXPONENT + 1);
                long delay = INITIAL_RETRY_DELAY_NANOS << (entry.failureCount - 1);
                entry.nextRetryAtNanos = System.nanoTime() + Math.min(delay, MAX_RETRY_DELAY_NANOS);
            }
            entry.notifyAll();
        }
    }

    private static ResolutionResult resolveDirectMediaUrl(String slug) {
        try {
            String token = getTemporaryToken();
            if (token == null) return ResolutionResult.TRANSIENT_FAILURE;

            Response response = requestGifInfo(slug, token);
            if (response.code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                clearTemporaryToken(token);
                token = getTemporaryToken();
                if (token == null) return ResolutionResult.TRANSIENT_FAILURE;
                response = requestGifInfo(slug, token);
            }

            if (response.code == HttpURLConnection.HTTP_GONE &&
                    GIF_DELETED_JSON.matcher(response.body).find()) {
                return ResolutionResult.GIF_DELETED;
            }
            if (response.code < 200 || response.code >= 300) {
                return ResolutionResult.TRANSIENT_FAILURE;
            }

            DirectMedia directMedia = extractDirectMedia(response.body);
            if (directMedia == null) return ResolutionResult.TRANSIENT_FAILURE;
            ProbeResult probe = probeDirectMediaUrl(directMedia.url, slug);
            if (probe.code >= 200 && probe.code < 300) {
                return ResolutionResult.success(
                        directMedia.url,
                        cacheKeyForDirectMedia(
                                directMedia.variant,
                                directMedia.url,
                                probe.finalUrl,
                                probe.etag
                        )
                );
            }

            if (probe.code == HttpURLConnection.HTTP_UNAUTHORIZED ||
                    probe.code == HttpURLConnection.HTTP_FORBIDDEN) {
                clearTemporaryToken(token);
                token = getTemporaryToken();
                if (token == null) return ResolutionResult.TRANSIENT_FAILURE;
                response = requestGifInfo(slug, token);
                if (response.code < 200 || response.code >= 300) {
                    return ResolutionResult.TRANSIENT_FAILURE;
                }
                directMedia = extractDirectMedia(response.body);
                if (directMedia == null) return ResolutionResult.TRANSIENT_FAILURE;
                probe = probeDirectMediaUrl(directMedia.url, slug);
                if (probe.code >= 200 && probe.code < 300) {
                    return ResolutionResult.success(
                            directMedia.url,
                            cacheKeyForDirectMedia(
                                    directMedia.variant,
                                    directMedia.url,
                                    probe.finalUrl,
                                    probe.etag
                            )
                    );
                }
            }
            return ResolutionResult.TRANSIENT_FAILURE;
        } catch (IOException ignored) {
            return ResolutionResult.TRANSIENT_FAILURE;
        }
    }

    private static String getTemporaryToken() throws IOException {
        String token = temporaryToken;
        if (token != null && !token.isEmpty()) return token;
        synchronized (TOKEN_LOCK) {
            token = temporaryToken;
            if (token != null && !token.isEmpty()) return token;

            Response response = request(AUTH_URL, null, null);
            if (response.code < 200 || response.code >= 300) return null;
            token = findFirst(TOKEN_JSON, response.body);
            if (token == null) return null;
            temporaryToken = unescapeJsonString(token);
            return temporaryToken;
        }
    }

    private static void clearTemporaryToken(String rejectedToken) {
        synchronized (TOKEN_LOCK) {
            if (rejectedToken != null && rejectedToken.equals(temporaryToken)) {
                temporaryToken = null;
            }
        }
    }

    private static Response requestGifInfo(String slug, String token) throws IOException {
        return request(
                GIF_INFO_PREFIX + slug + "?views=yes",
                "Bearer " + token,
                ORIGIN + "/watch/" + slug
        );
    }

    private static Response request(String url, String authorization, String customHeader)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(4_000);
            connection.setReadTimeout(6_000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Origin", ORIGIN);
            connection.setRequestProperty("Referer", REFERER);
            connection.setRequestProperty("User-Agent", MEDIA_USER_AGENT);
            if (authorization != null) connection.setRequestProperty("Authorization", authorization);
            if (customHeader != null) connection.setRequestProperty("x-customheader", customHeader);

            int code = connection.getResponseCode();
            InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Response(code, input == null ? "" : readFully(input));
        } finally {
            connection.disconnect();
        }
    }

    private static ProbeResult probeDirectMediaUrl(String directUrl, String slug)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(directUrl).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(4_000);
            connection.setReadTimeout(6_000);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Range", "bytes=0-0");
            for (Map.Entry<String, String> header : redgifsRequestHeaders(null, slug).entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            int code = connection.getResponseCode();
            return new ProbeResult(
                    code,
                    connection.getURL().toString(),
                    connection.getHeaderField("ETag")
            );
        } finally {
            connection.disconnect();
        }
    }

    private static String currentNetworkIdentity() {
        try {
            if (!Utils.isContextSet()) return null;
            Context context = Utils.getContext();
            ConnectivityManager connectivityManager =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivityManager == null) return null;
            Network network = connectivityManager.getActiveNetwork();
            return network == null ? null : network.toString();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static DataSpecAccess getDataSpecAccess(Object dataSpec)
            throws ReflectiveOperationException {
        DataSpecAccess access = dataSpecAccess;
        if (access != null && access.dataSpecClass == dataSpec.getClass()) return access;
        synchronized (RedgifsPlaybackPatch.class) {
            access = dataSpecAccess;
            if (access == null || access.dataSpecClass != dataSpec.getClass()) {
                access = new DataSpecAccess(dataSpec.getClass());
                dataSpecAccess = access;
            }
            return access;
        }
    }

    static Map<String, String> redgifsRequestHeaders(Map<String, String> existing, String slug) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (existing != null) headers.putAll(existing);
        putHeader(headers, "Origin", ORIGIN);
        putHeader(headers, "Referer", REFERER);
        putHeader(headers, "User-Agent", MEDIA_USER_AGENT);
        if (slug != null && !slug.isEmpty()) {
            putHeader(headers, "x-customheader", ORIGIN + "/watch/" + slug);
        }
        return Collections.unmodifiableMap(headers);
    }

    private static void putHeader(Map<String, String> headers, String name, String value) {
        String existingName = null;
        for (String candidate : headers.keySet()) {
            if (name.equalsIgnoreCase(candidate)) {
                existingName = candidate;
                break;
            }
        }
        if (existingName != null) headers.remove(existingName);
        headers.put(name, value);
    }

    private static boolean isRedgifsMedia(Uri uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) return false;
        String host = uri.getHost();
        if (host == null) return false;
        String normalizedHost = host.toLowerCase(Locale.US);
        boolean redgifsHost = "redgifs.com".equals(normalizedHost) ||
                normalizedHost.endsWith(".redgifs.com");
        if (!redgifsHost || "api.redgifs.com".equals(normalizedHost) ||
                "www.redgifs.com".equals(normalizedHost)) {
            return false;
        }
        String path = uri.getPath();
        return path != null && path.toLowerCase(Locale.US).endsWith(".mp4");
    }

    private static Field accessibleField(Class<?> owner, String name)
            throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Method accessibleMethod(Class<?> owner, String name)
            throws NoSuchMethodException {
        Method method = owner.getDeclaredMethod(name);
        method.setAccessible(true);
        return method;
    }

    private static String readFully(InputStream input) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder();
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) != -1) result.append(buffer, 0, count);
            return result.toString();
        }
    }

    static String extractDirectMediaUrl(String json) {
        DirectMedia directMedia = extractDirectMedia(json);
        return directMedia == null ? null : directMedia.url;
    }

    private static DirectMedia extractDirectMedia(String json) {
        String directUrl = findFirst(SD_URL_JSON, json);
        if (directUrl != null) return new DirectMedia(unescapeJsonString(directUrl), "sd");
        directUrl = findFirst(HD_URL_JSON, json);
        return directUrl == null ? null : new DirectMedia(unescapeJsonString(directUrl), "hd");
    }

    static String cacheKeyForDirectMedia(String variant, String directUrl, String finalUrl,
                                         String etag) {
        String normalizedVariant = "hd".equalsIgnoreCase(variant) ? "hd" : "sd";
        String normalizedPath = normalizedOriginAndPath(finalUrl);
        if (normalizedPath == null) normalizedPath = normalizedOriginAndPath(directUrl);

        String strongEtag = strongEtag(etag);
        if (strongEtag != null) {
            String resource = normalizedPath == null ? String.valueOf(directUrl) : normalizedPath;
            return "redgifs:v1:" + normalizedVariant + ":etag:" +
                    sha256(resource + '\n' + strongEtag);
        }
        if (normalizedPath != null) {
            return "redgifs:v1:" + normalizedVariant + ":path:" + sha256(normalizedPath);
        }
        return "redgifs:v1:" + normalizedVariant + ":url:" + sha256(directUrl);
    }

    private static String strongEtag(String etag) {
        if (etag == null) return null;
        String trimmed = etag.trim();
        if (trimmed.isEmpty() || trimmed.regionMatches(true, 0, "W/", 0, 2)) return null;
        return trimmed;
    }

    private static String normalizedOriginAndPath(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            URL url = new URL(value);
            String protocol = url.getProtocol();
            String host = url.getHost();
            if (protocol.isEmpty() || host.isEmpty()) return null;
            int port = url.getPort();
            String path = url.getPath();
            if (path == null || path.isEmpty()) path = "/";
            return protocol.toLowerCase(Locale.US) + "://" + host.toLowerCase(Locale.US) +
                    (port >= 0 && port != url.getDefaultPort() ? ":" + port : "") + path;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    String.valueOf(value).getBytes(StandardCharsets.UTF_8)
            );
            char[] hex = new char[digest.length * 2];
            final char[] digits = "0123456789abcdef".toCharArray();
            for (int i = 0; i < digest.length; i++) {
                int unsigned = digest[i] & 0xff;
                hex[i * 2] = digits[unsigned >>> 4];
                hex[i * 2 + 1] = digits[unsigned & 0x0f];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static long directUrlExpiryDeadlineNanos(String directUrl, long nowNanos,
                                             long nowEpochMillis) {
        long fallbackDeadline = nowNanos + DIRECT_URL_FALLBACK_LIFETIME_NANOS;
        if (directUrl == null) return fallbackDeadline;
        try {
            String query = new URL(directUrl).getQuery();
            if (query == null || query.isEmpty()) return fallbackDeadline;
            for (String parameter : query.split("&")) {
                int separator = parameter.indexOf('=');
                if (separator <= 0) continue;
                String name = URLDecoder.decode(
                        parameter.substring(0, separator), StandardCharsets.UTF_8.name());
                if (!"expires".equalsIgnoreCase(name) && !"exp".equalsIgnoreCase(name)) continue;
                String rawValue = URLDecoder.decode(
                        parameter.substring(separator + 1), StandardCharsets.UTF_8.name());
                long epochValue = Long.parseLong(rawValue);
                long expiryMillis = epochValue < 100_000_000_000L
                        ? TimeUnit.SECONDS.toMillis(epochValue)
                        : epochValue;
                long remainingMillis = expiryMillis - nowEpochMillis -
                        DIRECT_URL_EXPIRY_MARGIN_MILLIS;
                if (remainingMillis <= 0) return nowNanos;
                return nowNanos + TimeUnit.MILLISECONDS.toNanos(remainingMillis);
            }
        } catch (IOException | NumberFormatException ignored) {
        }
        return fallbackDeadline;
    }

    private static String findFirst(Pattern pattern, String value) {
        if (value == null || value.isEmpty()) return null;
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String unescapeJsonString(String value) {
        if (value == null || value.indexOf('\\') < 0) return value;
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character != '\\' || i + 1 >= value.length()) {
                out.append(character);
                continue;
            }
            char escaped = value.charAt(++i);
            switch (escaped) {
                case '"': out.append('"'); break;
                case '\\': out.append('\\'); break;
                case '/': out.append('/'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'u':
                    if (i + 4 < value.length()) {
                        try {
                            out.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                            i += 4;
                            break;
                        } catch (NumberFormatException ignored) {
                            out.append("\\u");
                            break;
                        }
                    }
                    out.append("\\u");
                    break;
                default: out.append(escaped); break;
            }
        }
        return out.toString();
    }

    private enum TerminalFailure {
        NONE,
        GIF_DELETED
    }

    private static final class ResolutionEntry {
        final String slug;
        volatile ResolvedRoute route;
        boolean resolving;
        int failureCount;
        long nextRetryAtNanos;
        TerminalFailure terminalFailure = TerminalFailure.NONE;

        ResolutionEntry(String slug) {
            this.slug = slug;
        }
    }

    private static final class ResolvedRoute {
        final String slug;
        final String directUrl;
        final String cacheKey;
        final long expiresAtNanos;
        final String networkIdentity;

        ResolvedRoute(String slug, String directUrl, String cacheKey, long expiresAtNanos,
                      String networkIdentity) {
            this.slug = slug;
            this.directUrl = directUrl;
            this.cacheKey = cacheKey;
            this.expiresAtNanos = expiresAtNanos;
            this.networkIdentity = networkIdentity;
        }
    }

    private static final class DirectMedia {
        final String url;
        final String variant;

        DirectMedia(String url, String variant) {
            this.url = url;
            this.variant = variant;
        }
    }

    private static final class ProbeResult {
        final int code;
        final String finalUrl;
        final String etag;

        ProbeResult(int code, String finalUrl, String etag) {
            this.code = code;
            this.finalUrl = finalUrl;
            this.etag = etag;
        }
    }

    private static final class ResolutionResult {
        static final ResolutionResult TRANSIENT_FAILURE =
                new ResolutionResult(null, null, TerminalFailure.NONE);
        static final ResolutionResult GIF_DELETED =
                new ResolutionResult(null, null, TerminalFailure.GIF_DELETED);

        final String directUrl;
        final String cacheKey;
        final TerminalFailure terminalFailure;

        private ResolutionResult(String directUrl, String cacheKey, TerminalFailure terminalFailure) {
            this.directUrl = directUrl;
            this.cacheKey = cacheKey;
            this.terminalFailure = terminalFailure;
        }

        static ResolutionResult success(String directUrl, String cacheKey) {
            return new ResolutionResult(directUrl, cacheKey, TerminalFailure.NONE);
        }
    }

    private static final class DataSpecAccess {
        final Class<?> dataSpecClass;
        final Field uriField;
        final Method buildUpon;
        final Field builderRequestHeadersField;
        final Field builderKeyField;
        final Method build;

        DataSpecAccess(Class<?> dataSpecClass) throws ReflectiveOperationException {
            this.dataSpecClass = dataSpecClass;
            uriField = accessibleField(dataSpecClass, "a");
            buildUpon = accessibleMethod(dataSpecClass, "a");
            Class<?> builderClass = buildUpon.getReturnType();
            builderRequestHeadersField = accessibleField(builderClass, "e");
            if (!Map.class.isAssignableFrom(builderRequestHeadersField.getType())) {
                throw new NoSuchFieldException("DataSpec.Builder request headers field");
            }
            builderKeyField = accessibleField(builderClass, "h");
            build = accessibleMethod(builderClass, "a");
        }
    }

    private static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }
}
