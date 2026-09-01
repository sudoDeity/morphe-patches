/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import android.util.Log;

import org.json.JSONObject;

import java.net.URL;

/**
 * Diagnostic-only probe for RedGIFs LinkDataModel hydration timing.
 *
 * <p>This class does not register identities, start resolution, or modify Reddit state. It only
 * records RedGIFs LinkDataModel construction so cache/model timing can be compared with VideoProps
 * rewrites and the later GraphQL mapper.</p>
 */
@SuppressWarnings("unused")
public final class RedgifsHydrationProbe {
    private static final String TAG = "MorpheRedgifs";

    private RedgifsHydrationProbe() {
    }

    public static void observeLinkDataModel(
            String linkId,
            int listingPosition,
            String linkJson,
            long listingId) {
        if (linkJson == null || linkJson.isEmpty()) return;
        // Cheap preflight only; structured parsing below establishes the top-level RedGIFs URL.
        if (!linkJson.toLowerCase().contains("redgifs.com")) return;

        try {
            JSONObject link = new JSONObject(linkJson);
            String slug = redgifsSlug(link.optString("url", null));
            if (slug == null) return;

            String mediaId = null;
            JSONObject media = link.optJSONObject("media");
            if (media != null) {
                mediaId = redditMediaId(media.optJSONObject("reddit_video"));
            }
            if (mediaId == null) {
                JSONObject preview = link.optJSONObject("preview");
                if (preview != null) {
                    mediaId = redditMediaId(preview.optJSONObject("reddit_video_preview"));
                }
            }

            Log.d(TAG, "LINK_MODEL" +
                    " thread=" + Thread.currentThread().getName() +
                    " linkId=" + linkId +
                    " listingId=" + listingId +
                    " position=" + listingPosition +
                    " persistedListing=" + (listingId >= 0) +
                    " slug=" + slug +
                    " mediaId=" + (mediaId == null ? "null" : mediaId) +
                    " jsonLength=" + linkJson.length());
        } catch (Throwable throwable) {
            Log.e(TAG, "LINK_MODEL_PARSE_FAILED linkId=" + linkId +
                    " listingId=" + listingId +
                    " position=" + listingPosition, throwable);
        }
    }

    private static String redditMediaId(JSONObject redditVideo) {
        if (redditVideo == null) return null;
        String[] fields = {
                "packaged_mp4_url",
                "dash_url",
                "fallback_url",
                "hls_url",
                "scrubber_media_url",
                "downloadUrl"
        };
        for (String field : fields) {
            String mediaId = redditMediaIdFromUrl(redditVideo.optString(field, null));
            if (mediaId != null) return mediaId;
        }
        return null;
    }

    private static String redditMediaIdFromUrl(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            URL url = new URL(value);
            String host = url.getHost();
            if (host == null) return null;
            host = host.toLowerCase();
            if (!host.equals("v.redd.it") && !host.equals("packaged-media.redd.it")) return null;

            String path = url.getPath();
            if (path == null || path.length() < 2) return null;
            int nextSlash = path.indexOf('/', 1);
            String mediaId = nextSlash < 0 ? path.substring(1) : path.substring(1, nextSlash);
            return mediaId.isEmpty() ? null : mediaId;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String redgifsSlug(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            URL url = new URL(value);
            String host = url.getHost();
            if (host == null) return null;
            host = host.toLowerCase();
            if (!host.equals("redgifs.com") && !host.endsWith(".redgifs.com")) return null;

            String path = url.getPath();
            if (path == null) return null;
            String prefix;
            if (path.startsWith("/watch/")) {
                prefix = "/watch/";
            } else if (path.startsWith("/ifr/")) {
                prefix = "/ifr/";
            } else {
                return null;
            }

            String slug = path.substring(prefix.length());
            int slash = slug.indexOf('/');
            if (slash >= 0) slug = slug.substring(0, slash);
            return slug.isEmpty() ? null : slug;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
