/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Diagnostic-only facade for the aggressive minimal RedGIFs A/B build.
 *
 * <p>This class never implements playback decisions. Every public entry point delegates to
 * {@link RedgifsPlaybackPatch} first and observes state afterwards. Expensive state dumps are
 * deliberately kept off Reddit's calling thread and coalesced so diagnostics cannot stall the
 * UI/feed mapper.</p>
 */
@SuppressWarnings({"unused", "unchecked"})
public final class RedgifsDebug {
    private static final String TAG = "MorpheRedgifs";
    private static final AtomicLong EVENT_SEQUENCE = new AtomicLong();
    private static final AtomicLong WATCH_GENERATION = new AtomicLong();
    private static final int MAX_DUMP_ENTRIES = 6;

    private static final ScheduledThreadPoolExecutor WATCHER =
            new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "morphe-redgifs-debug");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    static {
        WATCHER.setRemoveOnCancelPolicy(true);
        log("BOOT debugger=enabled minimalCore=true mode=nonblocking");
        scheduleWatch("BOOT");
    }

    private RedgifsDebug() {
    }

    public static void captureMediaFragment(Object mediaFragment) {
        long event = EVENT_SEQUENCE.incrementAndGet();
        log("CAPTURE begin id=" + event +
                " thread=" + threadLabel() +
                " fragment=" + className(mediaFragment));

        RedgifsPlaybackPatch.captureMediaFragment(mediaFragment);

        Object pending = threadLocalValue("PENDING_REDGIFS_SLUG");
        log("CAPTURE end id=" + event +
                " pendingSlug=" + value(pending));
        scheduleWatch("CAPTURE#" + event);
    }

    public static void registerRedditVideo(Object redditVideo) {
        long event = EVENT_SEQUENCE.incrementAndGet();
        String mediaIdBefore = redditVideoMediaId(redditVideo);
        Object pendingBefore = threadLocalValue("PENDING_REDGIFS_SLUG");
        log("REGISTER begin id=" + event +
                " thread=" + threadLabel() +
                " video=" + className(redditVideo) +
                " mediaId=" + value(mediaIdBefore) +
                " pendingSlug=" + value(pendingBefore));

        RedgifsPlaybackPatch.registerRedditVideo(redditVideo);

        String slug = mediaIdBefore == null ? null : mediaToSlug().get(mediaIdBefore);
        boolean conflicted = mediaIdBefore != null && conflictedMediaIds().contains(mediaIdBefore);
        Object pendingAfter = threadLocalValue("PENDING_REDGIFS_SLUG");
        log("IDENTITY id=" + event +
                " mediaId=" + value(mediaIdBefore) +
                " slug=" + value(slug) +
                " conflicted=" + conflicted +
                " pendingAfter=" + value(pendingAfter));
        scheduleWatch("REGISTER#" + event);
    }

    public static String rewritePlaybackUrl(String currentUrl) {
        long event = EVENT_SEQUENCE.incrementAndGet();
        String mediaId = mediaIdFromUrl(currentUrl);
        String slugBefore = mediaId == null ? null : mediaToSlug().get(mediaId);
        log("REWRITE begin id=" + event +
                " thread=" + threadLabel() +
                " mediaId=" + value(mediaId) +
                " slug=" + value(slugBefore) +
                " in=" + safeUrl(currentUrl));

        long started = System.nanoTime();
        String result = RedgifsPlaybackPatch.rewritePlaybackUrl(currentUrl);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        boolean changed = currentUrl == null ? result != null : !currentUrl.equals(result);
        String slugAfter = mediaId == null ? null : mediaToSlug().get(mediaId);
        log("REWRITE end id=" + event +
                " changed=" + changed +
                " elapsedMs=" + elapsedMs +
                " mediaId=" + value(mediaId) +
                " slug=" + value(slugAfter) +
                " out=" + safeUrl(result));

        if (!changed) {
            logRewriteFallbackReason(event, currentUrl, mediaId, slugAfter);
        }
        scheduleWatch("REWRITE#" + event);
        return result;
    }

    /** Expensive diagnostic snapshot. Call only from the dedicated watcher thread. */
    public static void dumpState(String reason) {
        try {
            Map<String, String> identities = mediaToSlug();
            Set<String> conflicts = conflictedMediaIds();
            Map<String, Object> resolutions = resolutionsBySlug();
            Map<String, Object> routes = routesByUrl();
            ThreadPoolExecutor resolver = resolver();
            Object token = staticField("temporaryToken");

            log("STATE reason=" + reason +
                    " thread=" + threadLabel() +
                    " events=" + EVENT_SEQUENCE.get() +
                    " identities=" + identities.size() +
                    " conflicts=" + conflicts.size() +
                    " resolutions=" + resolutions.size() +
                    " routes=" + routes.size() +
                    " tokenPresent=" + (token != null && !String.valueOf(token).isEmpty()) +
                    " resolverActive=" + resolver.getActiveCount() +
                    " resolverQueued=" + resolver.getQueue().size() +
                    " resolverCompleted=" + resolver.getCompletedTaskCount());

            int count = 0;
            for (Map.Entry<String, String> entry : identities.entrySet()) {
                if (count++ >= MAX_DUMP_ENTRIES) {
                    log("IDENTITY_MAP truncated remaining=" +
                            Math.max(0, identities.size() - MAX_DUMP_ENTRIES));
                    break;
                }
                log("IDENTITY_MAP mediaId=" + entry.getKey() +
                        " slug=" + entry.getValue() +
                        " conflicted=" + conflicts.contains(entry.getKey()));
            }

            count = 0;
            for (Map.Entry<String, Object> entry : resolutions.entrySet()) {
                if (count++ >= MAX_DUMP_ENTRIES) {
                    log("RESOLUTION truncated remaining=" +
                            Math.max(0, resolutions.size() - MAX_DUMP_ENTRIES));
                    break;
                }
                dumpResolution(entry.getKey(), entry.getValue());
            }
        } catch (Throwable throwable) {
            Log.e(TAG, "DEBUG_STATE_FAILED reason=" + reason, throwable);
        }
    }

    private static void dumpResolution(String slug, Object entry) {
        try {
            boolean resolving = booleanField(entry, "resolving");
            int failureCount = intField(entry, "failureCount");
            long nextRetry = longField(entry, "nextRetryAtNanos");
            long nextPlaybackRetry = longField(entry, "nextPlaybackRetryAtNanos");
            Object terminalFailure = field(entry, "terminalFailure");
            Object pendingTask = field(entry, "pendingTask");
            Object route = field(entry, "route");

            String pending = "none";
            if (pendingTask != null) {
                pending = "priority=" + booleanField(pendingTask, "playbackPriority");
            }

            long now = System.nanoTime();
            log("RESOLUTION slug=" + slug +
                    " resolving=" + resolving +
                    " pending=" + pending +
                    " failures=" + failureCount +
                    " retryInMs=" + nanosUntil(now, nextRetry) +
                    " playbackRetryInMs=" + nanosUntil(now, nextPlaybackRetry) +
                    " terminal=" + value(terminalFailure) +
                    " route=" + (route == null ? "none" : "present"));

            if (route != null) {
                String directUrl = String.valueOf(field(route, "directUrl"));
                Object cacheKey = field(route, "cacheKey");
                long expiresAt = longField(route, "expiresAtNanos");
                Object networkIdentity = field(route, "networkIdentity");
                log("ROUTE slug=" + slug +
                        " direct=" + safeUrl(directUrl) +
                        " cacheKey=" + value(cacheKey) +
                        " expiresInMs=" + nanosUntil(now, expiresAt) +
                        " network=" + value(networkIdentity));
            }
        } catch (Throwable throwable) {
            Log.e(TAG, "DEBUG_RESOLUTION_FAILED slug=" + slug, throwable);
        }
    }

    private static void logRewriteFallbackReason(long event, String url, String mediaId,
                                                  String slug) {
        if (url == null || url.isEmpty()) {
            log("REWRITE miss id=" + event + " reason=empty_url");
            return;
        }
        if (isDirectRedgifsUrl(url)) {
            log("REWRITE miss id=" + event + " reason=already_direct_redgifs");
            return;
        }
        if (mediaId == null) {
            log("REWRITE miss id=" + event + " reason=no_reddit_media_id");
            return;
        }
        if (conflictedMediaIds().contains(mediaId)) {
            log("REWRITE miss id=" + event +
                    " reason=conflicted_identity mediaId=" + mediaId);
            return;
        }
        if (slug == null) {
            log("REWRITE miss id=" + event + " reason=no_identity mediaId=" + mediaId);
            return;
        }
        Object resolution = resolutionsBySlug().get(slug);
        if (resolution == null) {
            log("REWRITE miss id=" + event + " reason=no_resolution_entry slug=" + slug);
            return;
        }
        try {
            Object terminal = field(resolution, "terminalFailure");
            boolean resolving = booleanField(resolution, "resolving");
            Object route = field(resolution, "route");
            log("REWRITE miss id=" + event +
                    " reason=route_unavailable" +
                    " slug=" + slug +
                    " resolving=" + resolving +
                    " terminal=" + value(terminal) +
                    " staleRoutePresent=" + (route != null));
        } catch (Throwable throwable) {
            Log.e(TAG, "DEBUG_REWRITE_REASON_FAILED id=" + event, throwable);
        }
    }

    /**
     * Coalesce bursts of GraphQL mapper events. Every event advances the generation; stale tasks
     * exit without reflection or log output. Only the final quiet point produces full snapshots.
     */
    private static void scheduleWatch(final String reason) {
        final long generation = WATCH_GENERATION.incrementAndGet();
        final long[] delaysMs = {200L, 800L, 2000L};
        for (final long delay : delaysMs) {
            WATCHER.schedule(new Runnable() {
                @Override
                public void run() {
                    if (WATCH_GENERATION.get() != generation) return;
                    dumpState(reason + "+" + delay + "ms");
                }
            }, delay, TimeUnit.MILLISECONDS);
        }
    }

    private static String redditVideoMediaId(Object redditVideo) {
        try {
            Method method = RedgifsPlaybackPatch.class.getDeclaredMethod(
                    "redditVideoMediaId", Object.class);
            method.setAccessible(true);
            Object result = method.invoke(null, redditVideo);
            return result == null ? null : String.valueOf(result);
        } catch (Throwable throwable) {
            Log.e(TAG, "DEBUG_MEDIA_ID_FAILED video=" + className(redditVideo), throwable);
            return null;
        }
    }

    private static String mediaIdFromUrl(String url) {
        if (url == null) return null;
        try {
            Pattern pattern = (Pattern) staticField("REDDIT_MEDIA_ID");
            Matcher matcher = pattern.matcher(url);
            return matcher.find() ? matcher.group(1) : null;
        } catch (Throwable throwable) {
            Log.e(TAG, "DEBUG_URL_MEDIA_ID_FAILED url=" + safeUrl(url), throwable);
            return null;
        }
    }

    private static boolean isDirectRedgifsUrl(String url) {
        if (url == null) return false;
        try {
            URL parsed = new URL(url);
            String host = parsed.getHost();
            String path = parsed.getPath();
            if (host == null || path == null) return false;
            host = host.toLowerCase();
            return (host.equals("redgifs.com") || host.endsWith(".redgifs.com")) &&
                    !host.equals("api.redgifs.com") &&
                    !host.equals("www.redgifs.com") &&
                    path.toLowerCase().endsWith(".mp4");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String safeUrl(String value) {
        if (value == null) return "null";
        try {
            URL url = new URL(value);
            int port = url.getPort();
            return url.getProtocol() + "://" + url.getHost() +
                    (port >= 0 ? ":" + port : "") + url.getPath() +
                    (url.getQuery() == null ? "" : "?<redacted>");
        } catch (Throwable ignored) {
            return value.length() <= 220 ? value : value.substring(0, 220) + "...";
        }
    }

    private static long nanosUntil(long now, long deadline) {
        if (deadline <= 0) return 0;
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, deadline - now));
    }

    private static String threadLabel() {
        return Thread.currentThread().getName() +
                (Looper.myLooper() == Looper.getMainLooper() ? ":main" : ":bg");
    }

    private static String value(Object value) {
        return value == null ? "null" : String.valueOf(value);
    }

    private static String className(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private static void log(String message) {
        Log.d(TAG, message);
    }

    private static Object staticField(String name) {
        try {
            Field field = RedgifsPlaybackPatch.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not read RedgifsPlaybackPatch." + name, exception);
        }
    }

    private static Object field(Object owner, String name) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "Could not read " + owner.getClass().getName() + "." + name, exception);
        }
    }

    private static boolean booleanField(Object owner, String name) {
        return Boolean.TRUE.equals(field(owner, name));
    }

    private static int intField(Object owner, String name) {
        Object value = field(owner, name);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static long longField(Object owner, String name) {
        Object value = field(owner, name);
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static Object threadLocalValue(String name) {
        Object value = staticField(name);
        return value instanceof ThreadLocal<?> ? ((ThreadLocal<?>) value).get() : null;
    }

    private static Map<String, String> mediaToSlug() {
        return (ConcurrentHashMap<String, String>) staticField("MEDIA_TO_SLUG");
    }

    private static Set<String> conflictedMediaIds() {
        return (Set<String>) staticField("CONFLICTED_MEDIA_IDS");
    }

    private static Map<String, Object> resolutionsBySlug() {
        return (ConcurrentHashMap<String, Object>) staticField("RESOLUTIONS_BY_SLUG");
    }

    private static Map<String, Object> routesByUrl() {
        return (Map<String, Object>) staticField("ROUTES_BY_URL");
    }

    private static ThreadPoolExecutor resolver() {
        return (ThreadPoolExecutor) staticField("RESOLVER");
    }
}
