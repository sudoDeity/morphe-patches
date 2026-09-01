/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import android.util.Log;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Diagnostic-only probe for the first Reddit media URL that reaches VideoProps without a known
 * RedGIFs identity. It returns the input unchanged and never registers identity or starts work.
 */
@SuppressWarnings({"unused", "unchecked"})
public final class RedgifsRewriteSourceProbe {
    private static final String TAG = "MorpheRedgifs";
    private static final int MAX_STACK_FRAMES = 24;
    private static final Pattern REDDIT_MEDIA_ID = Pattern.compile(
            "https?://(?:packaged-media\\.redd\\.it|v\\.redd\\.it)/([A-Za-z0-9]+)(?:/|$)");
    private static final Set<String> SEEN_MEDIA_IDS = ConcurrentHashMap.newKeySet();

    private RedgifsRewriteSourceProbe() {
    }

    public static String observe(String currentUrl) {
        String mediaId = mediaIdFromUrl(currentUrl);
        if (mediaId == null || !SEEN_MEDIA_IDS.add(mediaId)) return currentUrl;

        try {
            String slug = mediaToSlug().get(mediaId);
            if (slug != null) return currentUrl;

            Log.d(TAG, "REWRITE_SOURCE mediaId=" + mediaId +
                    " thread=" + Thread.currentThread().getName());

            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            int emitted = 0;
            for (StackTraceElement frame : stack) {
                String className = frame.getClassName();
                if (className.equals(Thread.class.getName()) ||
                        className.equals(RedgifsRewriteSourceProbe.class.getName())) {
                    continue;
                }
                Log.d(TAG, "REWRITE_STACK mediaId=" + mediaId +
                        " frame=" + emitted +
                        " at=" + frame.toString());
                emitted++;
                if (emitted >= MAX_STACK_FRAMES) break;
            }
        } catch (Throwable throwable) {
            Log.e(TAG, "REWRITE_STACK_FAILED mediaId=" + mediaId, throwable);
        }

        return currentUrl;
    }

    private static String mediaIdFromUrl(String url) {
        if (url == null) return null;
        Matcher matcher = REDDIT_MEDIA_ID.matcher(url);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static Map<String, String> mediaToSlug() throws ReflectiveOperationException {
        Field field = RedgifsPlaybackPatch.class.getDeclaredField("MEDIA_TO_SLUG");
        field.setAccessible(true);
        return (Map<String, String>) field.get(null);
    }
}
