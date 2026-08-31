/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RedgifsPlaybackPatchTest {
    @Test
    public void mediaHeadersOverrideUnsafeDefaultsAndPreserveOtherHeaders() {
        Map<String, String> existing = new LinkedHashMap<>();
        existing.put("user-agent", "platform-default");
        existing.put("Range", "bytes=10-");

        Map<String, String> headers =
                RedgifsPlaybackPatch.redgifsRequestHeaders(existing, "TestSlug");

        assertEquals("bytes=10-", headers.get("Range"));
        assertEquals("https://www.redgifs.com", headers.get("Origin"));
        assertEquals("https://www.redgifs.com/", headers.get("Referer"));
        assertTrue(headers.get("User-Agent").contains("Android"));
        assertFalse(headers.containsKey("user-agent"));
        assertEquals(
                "https://www.redgifs.com/watch/TestSlug",
                headers.get("x-customheader")
        );
        assertThrows(
                UnsupportedOperationException.class,
                () -> headers.put("Another", "header")
        );
    }

    @Test
    public void mediaHeadersDoNotInventWatchUrlWithoutSlug() {
        Map<String, String> headers =
                RedgifsPlaybackPatch.redgifsRequestHeaders(Collections.emptyMap(), null);

        assertFalse(headers.containsKey("x-customheader"));
    }

    @Test
    public void feedQualityPrefersSdAndFallsBackToHd() {
        String jsonWithBoth = "{\"urls\":{\"hd\":\"https:\\/\\/media.redgifs.com\\/hd.mp4\"," +
                "\"sd\":\"https:\\/\\/media.redgifs.com\\/sd.mp4\"}}";
        String hdOnly = "{\"urls\":{\"hd\":\"https:\\/\\/media.redgifs.com\\/hd.mp4\"}}";

        assertEquals(
                "https://media.redgifs.com/sd.mp4",
                RedgifsPlaybackPatch.extractDirectMediaUrl(jsonWithBoth)
        );
        assertEquals(
                "https://media.redgifs.com/hd.mp4",
                RedgifsPlaybackPatch.extractDirectMediaUrl(hdOnly)
        );
    }

    @Test
    public void strongEtagDefinesVersionButVariantRemainsPartOfIdentity() {
        String first = RedgifsPlaybackPatch.cacheKeyForDirectMedia(
                "sd",
                "https://media.redgifs.com/first.mp4?token=old",
                "https://files.redgifs.com/first.mp4?token=old",
                "\"content-v7\""
        );
        String renewedSignature = RedgifsPlaybackPatch.cacheKeyForDirectMedia(
                "sd",
                "https://media.redgifs.com/first.mp4?token=new",
                "https://files.redgifs.com/first.mp4?token=new",
                "\"content-v7\""
        );
        String reencoded = RedgifsPlaybackPatch.cacheKeyForDirectMedia(
                "sd",
                "https://media.redgifs.com/first.mp4?token=new",
                "https://files.redgifs.com/first.mp4?token=new",
                "\"content-v8\""
        );
        String differentResourceSameEtag = RedgifsPlaybackPatch.cacheKeyForDirectMedia(
                "sd",
                "https://media.redgifs.com/second.mp4?token=new",
                "https://files.redgifs.com/second.mp4?token=new",
                "\"content-v7\""
        );
        String hd = RedgifsPlaybackPatch.cacheKeyForDirectMedia(
                "hd",
                "https://media.redgifs.com/first.mp4?token=new",
                "https://files.redgifs.com/first.mp4?token=new",
                "\"content-v7\""
        );

        assertEquals(first, renewedSignature);
        assertNotEquals(first, reencoded);
        assertNotEquals(first, differentResourceSameEtag);
        assertNotEquals(first, hd);
        assertTrue(first.startsWith("redgifs:v1:sd:etag:"));
    }

    @Test
    public void missingOrWeakEtagUsesFinalCdnOriginAndPathWithoutQuery() {
        String first = RedgifsPlaybackPatch.cacheKeyForDirectMedia(
                "sd",
                "https://api.redgifs.com/signed.mp4?token=one",
                "https://FILES.redgifs.com/video/example.mp4?token=one",
                null
        );
        String renewedSignature = RedgifsPlaybackPatch.cacheKeyForDirectMedia(
                "sd",
                "https://api.redgifs.com/signed.mp4?token=two",
                "https://files.redgifs.com/video/example.mp4?token=two",
                "W/\"weak-validator\""
        );
        String differentPath = RedgifsPlaybackPatch.cacheKeyForDirectMedia(
                "sd",
                "https://api.redgifs.com/signed.mp4?token=two",
                "https://files.redgifs.com/video/reencoded.mp4?token=two",
                null
        );
        String hd = RedgifsPlaybackPatch.cacheKeyForDirectMedia(
                "hd",
                "https://api.redgifs.com/signed.mp4?token=two",
                "https://files.redgifs.com/video/example.mp4?token=two",
                null
        );

        assertEquals(first, renewedSignature);
        assertNotEquals(first, differentPath);
        assertNotEquals(first, hd);
        assertTrue(first.startsWith("redgifs:v1:sd:path:"));
    }

    @Test
    public void malformedCdnUrlFallsBackToFullDirectUrlHash() {
        String first = RedgifsPlaybackPatch.cacheKeyForDirectMedia(
                "sd", "not a URL?token=one", null, null
        );
        String second = RedgifsPlaybackPatch.cacheKeyForDirectMedia(
                "sd", "not a URL?token=two", null, null
        );

        assertNotEquals(first, second);
        assertTrue(first.startsWith("redgifs:v1:sd:url:"));
    }

    @Test
    public void signedUrlExpiryIsDerivedFromSecondsEpoch() {
        long nowNanos = TimeUnit.SECONDS.toNanos(10);
        long nowMillis = 1_800_000_000_000L;
        long expiresSeconds = TimeUnit.MILLISECONDS.toSeconds(nowMillis) + 65;

        long deadline = RedgifsPlaybackPatch.directUrlExpiryDeadlineNanos(
                "https://media.redgifs.com/video.mp4?expires=" + expiresSeconds,
                nowNanos,
                nowMillis
        );

        assertEquals(nowNanos + TimeUnit.SECONDS.toNanos(60), deadline);
    }

    @Test
    public void unsignedUrlOnlyGetsShortHandoffLifetime() {
        long nowNanos = TimeUnit.SECONDS.toNanos(10);

        long deadline = RedgifsPlaybackPatch.directUrlExpiryDeadlineNanos(
                "https://media.redgifs.com/video.mp4",
                nowNanos,
                1_800_000_000_000L
        );

        assertEquals(nowNanos + TimeUnit.SECONDS.toNanos(30), deadline);
    }
}
