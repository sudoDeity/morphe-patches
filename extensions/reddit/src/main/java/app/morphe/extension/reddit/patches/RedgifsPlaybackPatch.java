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
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Utils;

/** Runtime bridge that keeps RedGIFs playback in Reddit's native media player. */
@SuppressWarnings("unused")
public final class RedgifsPlaybackPatch {
    private static final String AUTH_URL = "https://api.redgifs.com/v2/auth/temporary";
    private static final String GIF_INFO_PREFIX = "https://api.redgifs.com/v2/gifs/";
    private static final String ORIGIN = "https://www.redgifs.com";
    private static final String REFERER = ORIGIN + "/";
    private static final String MEDIA_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    private static final String PACKAGED_MEDIA_HOST = "packaged-media.redd.it";

    private static final int MAX_CACHE_ENTRIES = 256;
    private static final int MAX_PENDING_RESOLUTIONS = 32;
    private static final int MAX_RETRY_EXPONENT = 6;
    private static final long DIRECT_URL_FALLBACK_LIFETIME_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final long DIRECT_URL_EXPIRY_MARGIN_MILLIS = TimeUnit.SECONDS.toMillis(5);
    private static final long INITIAL_RETRY_DELAY_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final long MAX_RETRY_DELAY_NANOS = TimeUnit.MINUTES.toNanos(1);
    private static final long CONFIRMED_REDGIFS_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(800);

    private static final Pattern REDGIFS_SLUG = Pattern.compile(
            "https?://(?:www\\.)?redgifs\\.com/(?:watch|ifr)/([A-Za-z0-9]+)",
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

    /** Entries are keyed by Reddit media id so late Media3 requests can resolve them directly. */
    private static final Map<String, ResolutionEntry> RESOLUTIONS = Collections.synchronizedMap(
            new LinkedHashMap<String, ResolutionEntry>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ResolutionEntry> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            }
    );

    /** Route and transport metadata live exactly as long as their ProgressiveMediaPeriod. */
    private static final Map<Object, PeriodRoute> PERIOD_ROUTES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Boolean> VIDEO_CLASSIFICATIONS =
            Collections.synchronizedMap(new WeakHashMap<>());

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

    /**
     * Injection point called immediately before the GraphQL mapper returns a RedditVideo.
     *
     * <p>RedGIFs is confirmed only from Reddit 2026.34's exact GraphQL structure:
     * {@code sgt.e -> rgt.b -> gim0}, where {@code gim0.a} is embedHtml and
     * {@code gim0.b} is url. Merely looking like a GIF or using packaged-media is
     * never enough to classify a medium as RedGIFs.</p>
     */
    public static void captureMediaFragment(Object mediaFragment) {
        String slug = extractConfirmedRedgifsSlug(mediaFragment);
        if (slug == null) {
            PENDING_REDGIFS_SLUG.remove();
        } else {
            PENDING_REDGIFS_SLUG.set(slug);
        }
    }

    /**
     * Injection point for Reddit's Room LinkDataModel cache path.
     *
     * <p>Only the serialized Link's top-level URL may establish RedGIFs identity.
     * The Reddit media id is taken from that same Link's reddit_video object.
     * Nested crosspost/media URLs are deliberately ignored.</p>
     */
    public static void registerCachedLinkJson(String linkJson) {
        if (linkJson == null || linkJson.isEmpty()) return;

        // Cheap preflight only. This is not classification: a nested RedGIFs URL may pass
        // this check, but only the structured top-level Link.url below can establish identity.
        if (!REDGIFS_SLUG.matcher(linkJson).find()) return;

        final JSONObject link;
        try {
            link = new JSONObject(linkJson);
        } catch (JSONException ignored) {
            return;
        }

        String slug = findFirst(REDGIFS_SLUG, link.optString("url", null));
        if (slug == null) return;

        String mediaId = null;

        JSONObject media = link.optJSONObject("media");
        if (media != null) {
            mediaId = cachedRedditVideoMediaId(
                    media.optJSONObject("reddit_video")
            );
        }

        if (mediaId == null) {
            JSONObject preview = link.optJSONObject("preview");
            if (preview != null) {
                mediaId = cachedRedditVideoMediaId(
                        preview.optJSONObject("reddit_video_preview")
                );
            }
        }

        if (mediaId == null) return;

        ResolutionEntry entry = getOrCreateEntry(mediaId);
        synchronized (entry) {
            if (!slug.equals(entry.slug)) {
                entry.slug = slug;
                entry.directUrl = null;
                entry.directUrlCacheKey = null;
                entry.directUrlExpiresAtNanos = 0;
                entry.resolvedNetworkIdentity = null;
                entry.failureCount = 0;
                entry.nextRetryAtNanos = 0;
                entry.terminalFailure = TerminalFailure.NONE;
                entry.notifyAll();
            }
        }

    }


    private static String cachedRedditVideoMediaId(JSONObject redditVideo) {
        if (redditVideo == null) return null;

        String mediaId = findFirst(
                REDDIT_MEDIA_ID,
                redditVideo.optString("packaged_mp4_url", null)
        );
        if (mediaId != null) return mediaId;

        mediaId = findFirst(REDDIT_MEDIA_ID, redditVideo.optString("dash_url", null));
        if (mediaId != null) return mediaId;

        mediaId = findFirst(REDDIT_MEDIA_ID, redditVideo.optString("fallback_url", null));
        if (mediaId != null) return mediaId;

        return findFirst(REDDIT_MEDIA_ID, redditVideo.optString("hls_url", null));
    }


    /** Injection point paired with {@link #captureMediaFragment(Object)} on the same thread. */
    public static void registerRedditVideo(Object redditVideo) {
        String slug = PENDING_REDGIFS_SLUG.get();
        PENDING_REDGIFS_SLUG.remove();

        if (slug == null || redditVideo == null) return;

        String mediaId = redditVideoMediaId(redditVideo);
        if (mediaId == null) return;

        ResolutionEntry entry = getOrCreateEntry(mediaId);
        synchronized (entry) {
            if (!slug.equals(entry.slug)) {
                entry.slug = slug;
                entry.directUrl = null;
                entry.directUrlCacheKey = null;
                entry.directUrlExpiresAtNanos = 0;
                entry.resolvedNetworkIdentity = null;
                entry.failureCount = 0;
                entry.nextRetryAtNanos = 0;
                entry.terminalFailure = TerminalFailure.NONE;
                entry.notifyAll();
            }
        }

        VIDEO_CLASSIFICATIONS.put(redditVideo, true);
    }

    private static Field accessibleField(Class<?> owner, String name)
            throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static String extractConfirmedRedgifsSlug(Object mediaFragment) {
        if (mediaFragment == null || !"sgt".equals(mediaFragment.getClass().getName())) {
            return null;
        }

        try {
            Object redditVideoMedia = accessibleField(mediaFragment.getClass(), "e")
                    .get(mediaFragment);
            if (redditVideoMedia == null ||
                    !"rgt".equals(redditVideoMedia.getClass().getName())) {
                return null;
            }

            Object videoMedia = accessibleField(redditVideoMedia.getClass(), "b")
                    .get(redditVideoMedia);
            if (videoMedia == null || !"gim0".equals(videoMedia.getClass().getName())) {
                return null;
            }

            Object embedHtmlValue = accessibleField(videoMedia.getClass(), "a").get(videoMedia);
            Object urlValue = accessibleField(videoMedia.getClass(), "b").get(videoMedia);

            String slug = embedHtmlValue instanceof String
                    ? findFirst(REDGIFS_SLUG, (String) embedHtmlValue)
                    : null;
            if (slug != null) return slug;

            return urlValue instanceof String
                    ? findFirst(REDGIFS_SLUG, (String) urlValue)
                    : null;
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
            Method getDashUrl = redditVideoGetDashUrlMethod;
            if (getDashUrl == null || getDashUrl.getDeclaringClass() != redditVideo.getClass()) {
                getDashUrl = redditVideo.getClass().getMethod("getDashUrl");
                redditVideoGetDashUrlMethod = getDashUrl;
            }
            Object value = getDashUrl.invoke(redditVideo);
            return value instanceof String
                    ? findFirst(REDDIT_MEDIA_ID, (String) value)
                    : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static boolean shouldTreatAsVideo(Object redditVideo) {
        if (redditVideo == null) return false;

        Boolean cached = VIDEO_CLASSIFICATIONS.get(redditVideo);
        if (cached != null) return cached;

        String mediaId = redditVideoMediaId(redditVideo);
        if (mediaId == null) {
            VIDEO_CLASSIFICATIONS.put(redditVideo, false);
            return false;
        }

        ResolutionEntry entry = RESOLUTIONS.get(mediaId);
        if (entry == null) {
            VIDEO_CLASSIFICATIONS.put(redditVideo, false);
            return false;
        }

        boolean treatAsVideo;
        synchronized (entry) {
            treatAsVideo = entry.slug != null
                    && entry.terminalFailure != TerminalFailure.GIF_DELETED;
        }
        VIDEO_CLASSIFICATIONS.put(redditVideo, treatAsVideo);
        return treatAsVideo;
    }

    /**
     * Preserve Reddit's original classification for every ordinary medium and only force
     * synchronously correlated RedGIFs out of the GIF path.
     */
    public static boolean overrideIsGif(boolean originalIsGif, Object redditVideo) {
        return originalIsGif && !shouldTreatAsVideo(redditVideo);
    }

    /** Injection point called after Reddit has selected its final playback URL. */
    public static String rewritePlaybackUrl(String currentUrl) {
        if (currentUrl == null || currentUrl.isEmpty()) return currentUrl;

        String mediaId = findFirst(REDDIT_MEDIA_ID, currentUrl);
        if (mediaId == null) return currentUrl;

        ResolutionEntry entry = RESOLUTIONS.get(mediaId);
        if (entry == null) return currentUrl;

        // This is the first playback-specific signal. Retain Reddit's URL so Media3
        // can always use its mirror if RedGIFs is slow, unavailable, or rejects CDN access.
        startResolution(entry);
        return currentUrl;
    }

    /**
     * Selects and commits the final route once for the real ProgressiveMediaPeriod.
     * Unknown media ids return immediately, while confirmed RedGIFs may wait briefly for
     * the warm resolver. The route and all transport metadata are retained by period identity.
     */
    public static Object resolvePeriodUri(Object mediaPeriod, Object uriObject) {
        if (mediaPeriod == null || !(uriObject instanceof Uri)) return uriObject;

        Uri uri = (Uri) uriObject;
        PeriodRoute periodRoute = PeriodRoute.original(uri);

        if (isLegacyPackagedMedia(uri)) {
            String currentUrl = uri.toString();
            String mediaId = findFirst(REDDIT_MEDIA_ID, currentUrl);
            if (mediaId != null) {
                DataSourceRoute route = selectDataSourceRoute(mediaId, currentUrl);
                if (route.directUrl != null) {
                    periodRoute = PeriodRoute.redgifs(
                            Uri.parse(route.directUrl),
                            route.cacheKey,
                            redgifsRequestHeaders(Collections.emptyMap(), route.slug)
                    );
                }
            }
        }

        PERIOD_ROUTES.put(mediaPeriod, periodRoute);
        return periodRoute.uri;
    }

    /**
     * Applies the transport metadata committed for the owning ProgressiveMediaPeriod.
     * This method is wait-free and never consults global resolver state.
     */
    public static Object prepareDataSpec(Object mediaPeriod, Object dataSpec) {
        if (dataSpec == null || mediaPeriod == null) return dataSpec;

        PeriodRoute route = PERIOD_ROUTES.get(mediaPeriod);
        if (route == null || !route.redgifs) return dataSpec;

        try {
            DataSpecAccess access = getDataSpecAccess(dataSpec);
            Uri uri = (Uri) access.uriField.get(dataSpec);
            if (!route.uri.equals(uri) || !isRedgifsMedia(uri)) return dataSpec;
            return buildRedgifsDataSpec(access, dataSpec, route);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return dataSpec;
        }
    }

    private static Object buildRedgifsDataSpec(DataSpecAccess access, Object dataSpec,
                                                PeriodRoute route)
            throws ReflectiveOperationException {
        Object builder = access.buildUpon.invoke(dataSpec);
        access.builderUriField.set(builder, route.uri);
        if (route.cacheKey != null) {
            access.builderKeyField.set(builder, route.cacheKey);
        }

        @SuppressWarnings("unchecked")
        Map<String, String> existingHeaders =
                (Map<String, String>) access.builderRequestHeadersField.get(builder);
        access.builderRequestHeadersField.set(
                builder,
                mergeRequestHeaders(existingHeaders, route.headers)
        );
        return access.build.invoke(builder);
    }

    private static Map<String, String> mergeRequestHeaders(Map<String, String> existing,
                                                            Map<String, String> additions) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (existing != null) headers.putAll(existing);
        for (Map.Entry<String, String> addition : additions.entrySet()) {
            putHeader(headers, addition.getKey(), addition.getValue());
        }
        return Collections.unmodifiableMap(headers);
    }

    static Map<String, String> redgifsRequestHeaders(Map<String, String> existing,
                                                      String slug) {
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

    private static boolean isLegacyPackagedMedia(Uri uri) {
        if (uri == null || !PACKAGED_MEDIA_HOST.equalsIgnoreCase(uri.getHost())) return false;

        String path = uri.getPath();
        if (path == null) return false;

        int fileNameStart = path.lastIndexOf('/') + 1;
        String fileName = path.substring(fileNameStart);
        return fileName.startsWith("muxed-") && fileName.endsWith(".mp4");
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

    private static DataSourceRoute selectDataSourceRoute(String mediaId, String currentUrl) {
        ResolutionEntry entry = RESOLUTIONS.get(mediaId);
        // Never stall ordinary packaged-media on ExoPlayer's playback thread.
        if (entry == null) return DataSourceRoute.ORIGINAL;

        String slug;
        String directUrl;
        String cacheKey;
        TerminalFailure terminalFailure;
        synchronized (entry) {
            slug = entry.slug;
            directUrl = validDirectUrl(entry, System.nanoTime());
            cacheKey = directUrl == null ? null : entry.directUrlCacheKey;
            terminalFailure = entry.terminalFailure;
        }

        // Unknown entries are not delayed: this avoids stalling ordinary Reddit media.
        if (slug == null) return DataSourceRoute.ORIGINAL;

        // RedGIFs explicitly says this GIF no longer exists. Reddit may still retain its
        // packaged-media copy, so preserve that copy instead of retrying forever.
        if (terminalFailure == TerminalFailure.GIF_DELETED) {
            return DataSourceRoute.ORIGINAL;
        }

        if (directUrl == null) {
            startResolution(entry);
            awaitDirectUrl(entry, slug);

            synchronized (entry) {
                directUrl = validDirectUrl(entry, System.nanoTime());
                cacheKey = directUrl == null ? null : entry.directUrlCacheKey;
                terminalFailure = entry.terminalFailure;
            }
            if (terminalFailure == TerminalFailure.GIF_DELETED) {
                return DataSourceRoute.ORIGINAL;
            }
        }

        // Resolver failures must never turn a playable Reddit mirror into a hard failure.
        if (directUrl == null || cacheKey == null || directUrl.equals(currentUrl)) {
            return DataSourceRoute.ORIGINAL;
        }

        return new DataSourceRoute(
                directUrl,
                cacheKey,
                slug
        );
    }

    private static String awaitDirectUrl(ResolutionEntry entry, String expectedSlug) {
        if (Looper.myLooper() == Looper.getMainLooper()) return null;

        long deadline = System.nanoTime() + CONFIRMED_REDGIFS_WAIT_NANOS;
        synchronized (entry) {
            while (expectedSlug.equals(entry.slug)) {
                String directUrl = validDirectUrl(entry, System.nanoTime());
                if (directUrl != null) return directUrl;
                if (!entry.resolving) return null;

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
        return null;
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

    private static ResolutionEntry getOrCreateEntry(String mediaId) {
        synchronized (RESOLUTIONS) {
            ResolutionEntry entry = RESOLUTIONS.get(mediaId);
            if (entry == null) {
                entry = new ResolutionEntry(mediaId);
                RESOLUTIONS.put(mediaId, entry);
            }
            return entry;
        }
    }

    private static String validDirectUrl(ResolutionEntry entry, long nowNanos) {
        if (entry.directUrl == null) return null;
        String networkIdentity = currentNetworkIdentity();
        boolean sameNetwork = entry.resolvedNetworkIdentity == null ||
                networkIdentity == null ||
                entry.resolvedNetworkIdentity.equals(networkIdentity);
        if (sameNetwork && nowNanos < entry.directUrlExpiresAtNanos) {
            return entry.directUrl;
        }

        entry.directUrl = null;
        entry.directUrlCacheKey = null;
        entry.directUrlExpiresAtNanos = 0;
        entry.resolvedNetworkIdentity = null;
        return null;
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

    private static void startResolution(ResolutionEntry entry) {
        final String slug;
        synchronized (entry) {
            long nowNanos = System.nanoTime();
            if (entry.resolving
                    || entry.terminalFailure == TerminalFailure.GIF_DELETED
                    || validDirectUrl(entry, nowNanos) != null
                    || nowNanos < entry.nextRetryAtNanos) {
                return;
            }

            slug = entry.slug;
            if (slug == null) return;
            entry.resolving = true;
        }

        try {
            RESOLVER.execute(() -> {
                if (!isResolutionCurrent(entry, slug)) {
                    completeResolution(entry, slug, ResolutionResult.TRANSIENT_FAILURE);
                    return;
                }

                ResolutionResult result = ResolutionResult.TRANSIENT_FAILURE;
                try {
                    result = resolveDirectMediaUrl(slug);
                } catch (RuntimeException ignored) {
                    // Treat unexpected resolver failures like transient network failures.
                }
                completeResolution(entry, slug, result);
            });
        } catch (RuntimeException exception) {
            completeResolution(entry, slug, ResolutionResult.TRANSIENT_FAILURE);
        }
    }

    private static void completeResolution(ResolutionEntry entry, String requestedSlug,
                                           ResolutionResult result) {
        boolean restart;

        synchronized (entry) {
            entry.resolving = false;
            restart = !requestedSlug.equals(entry.slug);

            if (!restart && result.directUrl != null) {
                entry.directUrl = result.directUrl;
                entry.directUrlCacheKey = result.cacheKey;
                entry.directUrlExpiresAtNanos = directUrlExpiryDeadlineNanos(
                        result.directUrl,
                        System.nanoTime(),
                        System.currentTimeMillis()
                );
                entry.resolvedNetworkIdentity = currentNetworkIdentity();
                entry.failureCount = 0;
                entry.nextRetryAtNanos = 0;
                entry.terminalFailure = TerminalFailure.NONE;
            } else if (!restart && result.terminalFailure == TerminalFailure.GIF_DELETED) {
                entry.directUrl = null;
                entry.directUrlCacheKey = null;
                entry.directUrlExpiresAtNanos = 0;
                entry.resolvedNetworkIdentity = null;
                entry.failureCount = 0;
                entry.nextRetryAtNanos = 0;
                entry.terminalFailure = TerminalFailure.GIF_DELETED;
            } else if (!restart) {
                entry.failureCount = Math.min(entry.failureCount + 1, MAX_RETRY_EXPONENT + 1);
                long delay = INITIAL_RETRY_DELAY_NANOS << (entry.failureCount - 1);
                entry.nextRetryAtNanos = System.nanoTime()
                        + Math.min(delay, MAX_RETRY_DELAY_NANOS);
            }

            entry.notifyAll();
        }

        if (!isCurrentEntry(entry)) return;
        if (restart) startResolution(entry);
    }

    private static boolean isResolutionCurrent(ResolutionEntry entry, String requestedSlug) {
        if (!isCurrentEntry(entry)) return false;

        synchronized (entry) {
            return entry.resolving && requestedSlug.equals(entry.slug);
        }
    }

    private static boolean isCurrentEntry(ResolutionEntry entry) {
        synchronized (RESOLUTIONS) {
            return RESOLUTIONS.get(entry.mediaId) == entry;
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

            if (response.code == HttpURLConnection.HTTP_GONE
                    && GIF_DELETED_JSON.matcher(response.body).find()) {
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

            // Never cache a newly issued URL that the CDN rejects. Refresh the API
            // token and direct URL once, then preserve Reddit's playable mirror.
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

            if (authorization != null) {
                connection.setRequestProperty("Authorization", authorization);
            }
            if (customHeader != null) {
                connection.setRequestProperty("x-customheader", customHeader);
            }

            int code = connection.getResponseCode();
            InputStream input = code >= 400
                    ? connection.getErrorStream()
                    : connection.getInputStream();
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
            for (Map.Entry<String, String> header :
                    redgifsRequestHeaders(null, slug).entrySet()) {
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

    private static String readFully(InputStream input) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder();
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                result.append(buffer, 0, count);
            }
            return result.toString();
        }
    }

    static String extractDirectMediaUrl(String json) {
        DirectMedia directMedia = extractDirectMedia(json);
        return directMedia == null ? null : directMedia.url;
    }

    private static DirectMedia extractDirectMedia(String json) {
        // Feed autoplay defaults to SD; HD is only used when the API exposes no SD URL.
        String directUrl = findFirst(SD_URL_JSON, json);
        if (directUrl != null) return new DirectMedia(unescapeJsonString(directUrl), "sd");

        directUrl = findFirst(HD_URL_JSON, json);
        return directUrl == null
                ? null
                : new DirectMedia(unescapeJsonString(directUrl), "hd");
    }

    static String cacheKeyForDirectMedia(String variant, String directUrl, String finalUrl,
                                         String etag) {
        String normalizedVariant = "hd".equalsIgnoreCase(variant) ? "hd" : "sd";
        String normalizedPath = normalizedOriginAndPath(finalUrl);
        if (normalizedPath == null) normalizedPath = normalizedOriginAndPath(directUrl);

        String strongEtag = strongEtag(etag);
        if (strongEtag != null) {
            // HTTP validators are scoped to a resource, not globally unique across a CDN.
            String resource = normalizedPath == null ? String.valueOf(directUrl) : normalizedPath;
            return "redgifs:v1:" + normalizedVariant + ":etag:"
                    + sha256(resource + '\n' + strongEtag);
        }

        if (normalizedPath != null) {
            return "redgifs:v1:" + normalizedVariant + ":path:" + sha256(normalizedPath);
        }

        // This only applies to malformed/non-HTTP URLs. Keep every signed URL isolated.
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
            return protocol.toLowerCase(Locale.US) + "://" + host.toLowerCase(Locale.US)
                    + (port >= 0 && port != url.getDefaultPort() ? ":" + port : "")
                    + path;
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
                if (!"expires".equalsIgnoreCase(name) && !"exp".equalsIgnoreCase(name)) {
                    continue;
                }

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
            // Unsigned/unparseable URLs are only handed off briefly.
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
                default:
                    out.append(escaped);
                    break;
            }
        }
        return out.toString();
    }

    private enum TerminalFailure {
        NONE,
        GIF_DELETED
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

        private ResolutionResult(String directUrl, String cacheKey,
                                 TerminalFailure terminalFailure) {
            this.directUrl = directUrl;
            this.cacheKey = cacheKey;
            this.terminalFailure = terminalFailure;
        }

        static ResolutionResult success(String directUrl, String cacheKey) {
            return new ResolutionResult(directUrl, cacheKey, TerminalFailure.NONE);
        }
    }

    private static final class ResolutionEntry {
        final String mediaId;
        String slug;
        String directUrl;
        String directUrlCacheKey;
        long directUrlExpiresAtNanos;
        String resolvedNetworkIdentity;
        boolean resolving;
        int failureCount;
        long nextRetryAtNanos;
        TerminalFailure terminalFailure = TerminalFailure.NONE;

        ResolutionEntry(String mediaId) {
            this.mediaId = mediaId;
        }
    }

    private static final class DataSourceRoute {
        static final DataSourceRoute ORIGINAL = new DataSourceRoute(null, null, null);

        final String directUrl;
        final String cacheKey;
        final String slug;

        DataSourceRoute(String directUrl, String cacheKey, String slug) {
            this.directUrl = directUrl;
            this.cacheKey = cacheKey;
            this.slug = slug;
        }
    }

    private static final class PeriodRoute {
        final Uri uri;
        final boolean redgifs;
        final String cacheKey;
        final Map<String, String> headers;

        private PeriodRoute(Uri uri, boolean redgifs, String cacheKey,
                            Map<String, String> headers) {
            this.uri = uri;
            this.redgifs = redgifs;
            this.cacheKey = cacheKey;
            this.headers = headers;
        }

        static PeriodRoute original(Uri uri) {
            return new PeriodRoute(uri, false, null, Collections.emptyMap());
        }

        static PeriodRoute redgifs(Uri uri, String cacheKey, Map<String, String> headers) {
            return new PeriodRoute(uri, true, cacheKey, headers);
        }
    }

    private static final class DataSpecAccess {
        final Class<?> dataSpecClass;
        final Field uriField;
        final Method buildUpon;
        final Field builderUriField;
        final Field builderRequestHeadersField;
        final Field builderKeyField;
        final Method build;

        DataSpecAccess(Class<?> dataSpecClass) throws ReflectiveOperationException {
            this.dataSpecClass = dataSpecClass;
            uriField = accessibleField(dataSpecClass, "a");
            buildUpon = accessibleMethod(dataSpecClass, "a");

            Class<?> builderClass = buildUpon.getReturnType();
            builderUriField = accessibleField(builderClass, "a");
            builderRequestHeadersField = accessibleField(builderClass, "e");
            if (!Map.class.isAssignableFrom(builderRequestHeadersField.getType())) {
                throw new NoSuchFieldException("DataSpec.Builder request headers field");
            }
            builderKeyField = accessibleField(builderClass, "h");
            build = accessibleMethod(builderClass, "a");
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
