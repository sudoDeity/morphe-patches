/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import android.util.Log;

/**
 * Diagnostic-only probe for the known RedGIFs cold-start race fixture.
 *
 * <p>This class does not register identities, start resolution, or modify Reddit state. It only
 * records when the persisted LinkDataModel for the exact fixture post is hydrated.</p>
 */
@SuppressWarnings("unused")
public final class RedgifsHydrationProbe {
    private static final String TAG = "MorpheRedgifs";
    private static final String TARGET_LINK_ID = "1w49d8q";
    private static final String TARGET_MEDIA_ID = "ztoihw8sawmh1";
    private static final String TARGET_SLUG = "thatautomaticelephantseal";

    private RedgifsHydrationProbe() {
    }

    public static void observeLinkDataModel(
            String linkId,
            int listingPosition,
            String linkJson,
            long listingId) {
        if (!TARGET_LINK_ID.equals(linkId)) return;

        boolean hasMediaId = linkJson != null && linkJson.contains(TARGET_MEDIA_ID);
        boolean hasSlug = linkJson != null && linkJson.contains(TARGET_SLUG);

        Log.d(TAG, "DB_HYDRATE" +
                " thread=" + Thread.currentThread().getName() +
                " linkId=" + linkId +
                " listingId=" + listingId +
                " position=" + listingPosition +
                " hasMediaId=" + hasMediaId +
                " hasSlug=" + hasSlug +
                " jsonLength=" + (linkJson == null ? -1 : linkJson.length()));
    }
}
