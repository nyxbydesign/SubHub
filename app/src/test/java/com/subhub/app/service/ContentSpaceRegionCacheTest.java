package com.subhub.app.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.RenderSourceReference;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ContentSpaceRegionCacheTest {
    @Test public void confirmedRegionLeavesAndReturnsWithoutAnotherObservation() {
        ContentSpaceRegionCache cache = new ContentSpaceRegionCache();
        cache.observeCommittedScene(1L, "chrome|7|webview", 1_000L,
                0L, 0L, 1_000, 2_000, 1_000, 2_000,
                false, List.of(observation(7, 100, 300, 200, 240, 2, false)));

        assertEquals(1, cache.size());
        assertTrue(cache.queryNearAsScreenDetections(1L, "chrome|7|webview", 1_100L,
                0L, 7_000L, 1_000, 2_000, 1_000, 2_000).isEmpty());
        List<Detection> returned = cache.queryNearAsScreenDetections(
                1L, "chrome|7|webview", 1_200L,
                0L, 0L, 1_000, 2_000, 1_000, 2_000);

        assertEquals(1, returned.size());
        assertEquals(new BBox(100, 300, 200, 240), returned.get(0).getBox());
        assertTrue(returned.get(0).getAnchorKey().startsWith("world-cache:"));
    }

    @Test public void oneFrameFastCandidateNeverEntersMemory() {
        ContentSpaceRegionCache cache = new ContentSpaceRegionCache();
        cache.observeCommittedScene(1L, "surface", 100L,
                0L, 0L, 1_000, 2_000, 1_000, 2_000,
                false, List.of(observation(1, 20, 30, 80, 90, 1, false)));

        assertEquals(0, cache.size());
    }

    @Test public void atomicQualityCanConfirmFirstObservation() {
        ContentSpaceRegionCache cache = new ContentSpaceRegionCache();
        cache.observeCommittedScene(1L, "surface", 100L,
                0L, 0L, 1_000, 2_000, 1_000, 2_000,
                true, List.of(observation(1, 20, 30, 80, 90, 1, true)));

        assertEquals(1, cache.size());
    }

    @Test public void backfillStaysHiddenUntilItsSourceViewportHasDeparted() {
        ContentSpaceRegionCache cache = new ContentSpaceRegionCache();
        ContentSpaceRegionCache.Observation backfill =
                new ContentSpaceRegionCache.Observation(
                        -1, "FACE_FEMALE", "face_female", 0.9f,
                        new BBox(100, 300, 200, 240), true, false,
                        0, 0, true, null, true);
        cache.observeCommittedScene(1L, "surface", 100L,
                0L, 0L, 1_000, 2_000, 1_000, 2_000,
                false, List.of(backfill));

        assertTrue(cache.queryNearAsScreenDetections(1L, "surface", 150L,
                0L, 0L, 1_000, 2_000, 1_000, 2_000).isEmpty());
        cache.observeCommittedScene(1L, "surface", 175L,
                0L, 0L, 1_000, 2_000, 1_000, 2_000,
                true, Collections.emptyList());
        assertEquals(1, cache.size());

        // The offscreen near-query arms the memory; returning renders it immediately.
        cache.queryNearAsScreenDetections(1L, "surface", 200L,
                0L, 7_000L, 1_000, 2_000, 1_000, 2_000);
        assertEquals(1, cache.queryNearAsScreenDetections(1L, "surface", 250L,
                0L, 0L, 1_000, 2_000, 1_000, 2_000).size());
    }

    @Test public void reusedBackfillSlotCannotInheritAnOldLiveTrackIdentity() {
        ContentSpaceRegionCache cache = new ContentSpaceRegionCache();
        cache.observeCommittedScene(1L, "surface", 100L,
                0L, 0L, 1_000, 2_000, 1_000, 2_000,
                false, List.of(observation(42, 20, 30, 80, 90, 2, false)));
        cache.clear();
        cache.observeCommittedScene(1L, "surface", 200L,
                0L, 0L, 1_000, 2_000, 1_000, 2_000,
                false, List.of(new ContentSpaceRegionCache.Observation(
                        -1, "FACE_FEMALE", "face_female", 0.9f,
                        new BBox(20, 30, 80, 90), true, false,
                        0, 0, true, null, true)));
        cache.observeCommittedScene(1L, "surface", 300L,
                0L, 0L, 1_000, 2_000, 1_000, 2_000,
                false, List.of(observation(42, 600, 700, 80, 90, 2, false)));

        List<Detection> visible = cache.queryNearAsScreenDetections(
                1L, "surface", 350L, 0L, 0L,
                1_000, 2_000, 1_000, 2_000);
        assertTrue(visible.stream().anyMatch(value -> value.getBox().getX() == 600));
    }

    @Test public void reusedSlotCannotInheritAnOldContradiction() {
        ContentSpaceRegionCache cache = seeded();
        cache.observeCommittedScene(1L, "surface", 150L,
                0L, 0L, 1_000, 2_000, 1_000, 2_000,
                true, Collections.emptyList());
        assertEquals(1, cache.size());
        cache.clear();
        cache.observeCommittedScene(1L, "surface", 200L,
                0L, 0L, 1_000, 2_000, 1_000, 2_000,
                false, List.of(observation(2, 600, 700, 80, 90, 2, false)));

        cache.observeCommittedScene(1L, "surface", 250L,
                0L, 0L, 1_000, 2_000, 1_000, 2_000,
                true, Collections.emptyList());

        assertEquals(1, cache.size());
    }

    @Test public void reusedSlotCannotInheritOldConfidence() {
        ContentSpaceRegionCache cache = new ContentSpaceRegionCache();
        cache.observeCommittedScene(1L, "surface", 100L,
                0L, 0L, 1_000, 2_000, 1_000, 2_000,
                false, List.of(new ContentSpaceRegionCache.Observation(
                        1, "FACE_FEMALE", "face_female", .99f,
                        new BBox(20, 30, 80, 90), true, false,
                        2, 0, false)));
        cache.clear();
        cache.observeCommittedScene(1L, "surface", 200L,
                0L, 0L, 1_000, 2_000, 1_000, 2_000,
                false, List.of(new ContentSpaceRegionCache.Observation(
                        2, "FACE_FEMALE", "face_female", .10f,
                        new BBox(600, 700, 80, 90), true, false,
                        2, 0, false)));

        List<Detection> result = cache.queryNearAsScreenDetections(
                1L, "surface", 250L, 0L, 0L,
                1_000, 2_000, 1_000, 2_000);
        assertEquals(.10f, result.get(0).getConfidence(), .001f);
    }

    @Test public void offscreenOmissionDoesNotAgeButTwoInViewUnifiedContradictionsDo() {
        ContentSpaceRegionCache cache = seeded();
        cache.observeCommittedScene(1L, "surface", 200L,
                0L, 3_000L, 1_000, 2_000, 1_000, 2_000,
                true, Collections.emptyList());
        assertEquals(1, cache.size());

        cache.observeCommittedScene(1L, "surface", 300L,
                0L, 0L, 1_000, 2_000, 1_000, 2_000,
                true, Collections.emptyList());
        assertEquals(1, cache.size());
        cache.observeCommittedScene(1L, "surface", 400L,
                0L, 0L, 1_000, 2_000, 1_000, 2_000,
                true, Collections.emptyList());
        assertEquals(0, cache.size());
    }

    @Test public void documentSurfaceViewportAndTtlFenceOldGeometry() {
        ContentSpaceRegionCache cache = seeded();
        assertTrue(cache.queryNearAsScreenDetections(2L, "surface", 200L,
                0L, 0L, 1_000, 2_000, 1_000, 2_000).isEmpty());
        assertEquals(0, cache.size());

        cache = seeded();
        assertTrue(cache.queryNearAsScreenDetections(1L, "other", 200L,
                0L, 0L, 1_000, 2_000, 1_000, 2_000).isEmpty());
        assertEquals(0, cache.size());

        cache = seeded();
        assertTrue(cache.queryNearAsScreenDetections(1L, "surface", 200L,
                0L, 0L, 2_000, 1_000, 2_000, 1_000).isEmpty());

        cache = seeded();
        assertTrue(cache.queryNearAsScreenDetections(1L, "surface",
                100L + ContentSpaceRegionCache.VISUAL_TTL_MS + 1L,
                0L, 0L, 1_000, 2_000, 1_000, 2_000).isEmpty());
        assertEquals(0, cache.size());
    }

    @Test public void cacheIsBoundedAndQueryIsCapped() {
        ContentSpaceRegionCache cache = new ContentSpaceRegionCache();
        List<ContentSpaceRegionCache.Observation> observations = new ArrayList<>();
        for (int index = 0; index < ContentSpaceRegionCache.MAX_ENTRIES + 50; index++) {
            observations.add(observation(index + 1,
                    index % 20 * 45, index / 20 * 45, 30, 30, 2, false));
        }
        cache.observeCommittedScene(1L, "surface", 100L,
                0L, 0L, 1_000, 2_000, 1_000, 2_000,
                false, observations);

        assertEquals(ContentSpaceRegionCache.MAX_ENTRIES, cache.size());
        assertTrue(cache.queryNearAsScreenDetections(1L, "surface", 200L,
                0L, 0L, 1_000, 2_000, 1_000, 2_000).size()
                <= ContentSpaceRegionCache.MAX_QUERY_RESULTS);
    }

    @Test public void visibleEntryWinsQueryCapOverOffscreenPrefetch() {
        ContentSpaceRegionCache cache = new ContentSpaceRegionCache();
        List<ContentSpaceRegionCache.Observation> observations = new ArrayList<>();
        for (int index = 0; index < ContentSpaceRegionCache.MAX_QUERY_RESULTS; index++) {
            observations.add(observation(index + 1,
                    100, 1_200 + index * 100, 40, 40, 2, false));
        }
        observations.add(observation(999, 100, 5_100, 80, 90, 2, false));
        cache.observeCommittedScene(1L, "surface", 100L,
                0L, 0L, 1_000, 2_000, 1_000, 2_000,
                false, observations);

        List<Detection> result = cache.queryNearAsScreenDetections(
                1L, "surface", 200L, 0L, 5_000L,
                1_000, 2_000, 1_000, 2_000);

        assertEquals(ContentSpaceRegionCache.MAX_QUERY_RESULTS, result.size());
        assertTrue(result.stream().anyMatch(value -> value.getBox().getY() == 100
                && value.getBox().getWidth() == 80));
    }

    @Test public void metadataAndEntryBoundsRemainHardWithLargeLabels() {
        ContentSpaceRegionCache cache = new ContentSpaceRegionCache();
        String label = "x".repeat(128);
        List<ContentSpaceRegionCache.Observation> observations = new ArrayList<>();
        for (int index = 0; index < ContentSpaceRegionCache.MAX_ENTRIES; index++) {
            observations.add(new ContentSpaceRegionCache.Observation(
                    index + 1, label, label, 0.9f,
                    new BBox(10, index * 600, 30, 30), true, false,
                    2, 0, false));
        }

        cache.observeCommittedScene(1L, "surface", 100L,
                0L, 0L, 1_000, 2_000, 1_000, 2_000,
                false, observations);

        assertTrue(cache.size() <= ContentSpaceRegionCache.MAX_ENTRIES);
        assertTrue(cache.metadataBytes() <= ContentSpaceRegionCache.MAX_METADATA_BYTES);
    }

    @Test public void oversizedMetadataIsRejectedWithoutRetainingCallerString() {
        ContentSpaceRegionCache cache = new ContentSpaceRegionCache();
        String oversized = "x".repeat(129);
        cache.observeCommittedScene(1L, "surface", 100L,
                0L, 0L, 1_000, 2_000, 1_000, 2_000,
                false, List.of(new ContentSpaceRegionCache.Observation(
                        1, oversized, "face_female", 0.9f,
                        new BBox(10, 20, 30, 30), true, false,
                        2, 0, false)));

        assertEquals(0, cache.size());
    }

    @Test public void queryTouchesEntryForLruEviction() {
        ContentSpaceRegionCache cache = new ContentSpaceRegionCache();
        List<ContentSpaceRegionCache.Observation> observations = new ArrayList<>();
        for (int index = 0; index < ContentSpaceRegionCache.MAX_ENTRIES; index++) {
            observations.add(observation(index + 1, 100, index * 600, 30, 30, 2, false));
        }
        cache.observeCommittedScene(1L, "surface", 100L,
                0L, 0L, 1_000, 2_000, 1_000, 2_000,
                false, observations);

        List<Detection> first = cache.queryNearAsScreenDetections(
                1L, "surface", 200L, 0L, 0L,
                1_000, 2_000, 1_000, 2_000);
        assertTrue(first.stream().anyMatch(value -> value.getBox().getY() == 0));

        cache.observeCommittedScene(1L, "surface", 300L,
                0L, 100_000L, 1_000, 2_000, 1_000, 2_000,
                false, List.of(observation(999, 100, 10, 30, 30, 2, false)));

        List<Detection> retained = cache.queryNearAsScreenDetections(
                1L, "surface", 400L, 0L, 0L,
                1_000, 2_000, 1_000, 2_000);
        assertTrue(retained.stream().anyMatch(value -> value.getBox().getY() == 0));
    }

    @Test public void anchoredTextUsesLongerTtl() {
        ContentSpaceRegionCache cache = new ContentSpaceRegionCache();
        ContentSpaceRegionCache.Observation text = new ContentSpaceRegionCache.Observation(
                1, "TEXT_SMUT_ACCESSIBILITY_1", "text_smut", 0.9f,
                new BBox(20, 30, 100, 30), true, false,
                2, 0, false, "node:stable-1");
        cache.observeCommittedScene(1L, "surface", 100L,
                0L, 0L, 1_000, 2_000, 1_000, 2_000,
                false, List.of(text));

        assertEquals(1, cache.queryNearAsScreenDetections(
                1L, "surface", 100L + ContentSpaceRegionCache.VISUAL_TTL_MS + 1L,
                0L, 0L, 1_000, 2_000, 1_000, 2_000).size());
        assertTrue(cache.queryNearAsScreenDetections(
                1L, "surface", 100L + ContentSpaceRegionCache.ANCHORED_TEXT_TTL_MS + 1L,
                0L, 0L, 1_000, 2_000, 1_000, 2_000).isEmpty());
    }

    @Test public void sourceGeometryChangeHardClearsBeforeAcceptingNewScene() {
        ContentSpaceRegionCache cache = seeded();

        ContentSpaceRegionCache.Update update = cache.observeCommittedScene(
                1L, "surface", 200L, 0L, 0L,
                2_000, 2_000, 1_000, 2_000,
                false, Collections.emptyList());

        assertTrue(update.viewportReset);
        assertEquals(0, cache.size());
    }

    @Test public void updatingAcrossBucketsRemovesOldIndexNode() {
        ContentSpaceRegionCache cache = seeded();
        cache.observeCommittedScene(1L, "surface", 200L,
                0L, 100_000L, 1_000, 2_000, 1_000, 2_000,
                false, List.of(observation(1, 100, 300, 200, 240, 2, false)));

        assertTrue(cache.queryNearAsScreenDetections(
                1L, "surface", 300L, 0L, 0L,
                1_000, 2_000, 1_000, 2_000).isEmpty());
        assertEquals(1, cache.queryNearAsScreenDetections(
                1L, "surface", 300L, 0L, 100_000L,
                1_000, 2_000, 1_000, 2_000).size());
    }

    @Test public void veryTallRegionUsesBoundedBroadPhaseFallback() {
        ContentSpaceRegionCache cache = new ContentSpaceRegionCache();
        cache.observeCommittedScene(1L, "surface", 100L,
                0L, 0L, 1_000, 20_000, 1_000, 20_000,
                false, List.of(observation(1, 100, 100, 200, 10_000, 2, false)));

        assertEquals(1, cache.queryNearAsScreenDetections(
                1L, "surface", 200L, 0L, 5_000L,
                1_000, 20_000, 1_000, 20_000).size());
    }

    @Test public void coordinateRoundTripHandlesDifferentCaptureAndViewportSizes() {
        BBox screen = new BBox(200, 400, 100, 120);
        BBox world = ContentSpaceRegionCache.screenToWorld(
                screen, 0L, 1_000L, 1_000, 2_000, 500, 1_000);
        BBox returned = ContentSpaceRegionCache.worldToScreen(
                world, 0L, 1_000L, 1_000, 2_000, 500, 1_000);

        assertEquals(screen, returned);
    }

    private static ContentSpaceRegionCache seeded() {
        ContentSpaceRegionCache cache = new ContentSpaceRegionCache();
        cache.observeCommittedScene(1L, "surface", 100L,
                0L, 0L, 1_000, 2_000, 1_000, 2_000,
                false, List.of(observation(1, 100, 300, 200, 240, 2, false)));
        return cache;
    }

    private static ContentSpaceRegionCache.Observation observation(
            int id, int x, int y, int width, int height,
            int framesTracked, boolean qualityConfirmed) {
        return new ContentSpaceRegionCache.Observation(
                id, "FACE_FEMALE", "face_female", 0.9f,
                new BBox(x, y, width, height), true, false,
                framesTracked, 0, qualityConfirmed);
    }
    @Test public void insertedRenderReferenceSurvivesCacheQuery() {
        ContentSpaceRegionCache cache = new ContentSpaceRegionCache();
        RenderSourceReference reference = renderReference(100L, 12.5, -4.0);
        cache.observeCommittedScene(1L, "surface", 100L,
                0L, 0L, 1_000, 2_000, 1_000, 2_000,
                false, List.of(new ContentSpaceRegionCache.Observation(
                        7, "FACE_FEMALE", "face_female", 0.9f,
                        new BBox(100, 300, 200, 240), true, false,
                        2, 0, false, null, false, reference)));

        List<Detection> returned = cache.queryNearAsScreenDetections(
                1L, "surface", 200L, 0L, 0L,
                1_000, 2_000, 1_000, 2_000);

        assertEquals(1, returned.size());
        assertSame(reference, returned.get(0).getRenderSourceReference());
    }

    @Test public void geometryRevisionUpdatesRenderReferenceWithWorldBox() {
        ContentSpaceRegionCache cache = new ContentSpaceRegionCache();
        RenderSourceReference first = renderReference(100L, 12.5, -4.0);
        RenderSourceReference next = renderReference(200L, 18.0, -1.0);
        cache.observeCommittedScene(1L, "surface", 100L,
                0L, 0L, 1_000, 2_000, 1_000, 2_000,
                false, List.of(new ContentSpaceRegionCache.Observation(
                        7, "FACE_FEMALE", "face_female", 0.9f,
                        new BBox(100, 300, 200, 240), true, false,
                        2, 0, false, null, false, first)));
        cache.observeCommittedScene(1L, "surface", 200L,
                0L, 0L, 1_000, 2_000, 1_000, 2_000,
                false, List.of(new ContentSpaceRegionCache.Observation(
                        7, "FACE_FEMALE", "face_female", 0.9f,
                        new BBox(130, 320, 200, 240), true, false,
                        3, 0, false, null, false, next)));

        List<Detection> returned = cache.queryNearAsScreenDetections(
                1L, "surface", 300L, 0L, 0L,
                1_000, 2_000, 1_000, 2_000);

        assertEquals(1, returned.size());
        assertEquals(new BBox(130, 320, 200, 240), returned.get(0).getBox());
        assertSame(next, returned.get(0).getRenderSourceReference());
    }

    @Test public void clearedAndReusedSlotDoesNotInheritRenderReference() {
        ContentSpaceRegionCache cache = new ContentSpaceRegionCache();
        RenderSourceReference reference = renderReference(100L, 12.5, -4.0);
        cache.observeCommittedScene(1L, "surface", 100L,
                0L, 0L, 1_000, 2_000, 1_000, 2_000,
                false, List.of(new ContentSpaceRegionCache.Observation(
                        7, "FACE_FEMALE", "face_female", 0.9f,
                        new BBox(100, 300, 200, 240), true, false,
                        2, 0, false, null, false, reference)));
        cache.clear();
        cache.observeCommittedScene(1L, "surface", 200L,
                0L, 0L, 1_000, 2_000, 1_000, 2_000,
                false, List.of(observation(7, 100, 300, 200, 240, 2, false)));

        List<Detection> returned = cache.queryNearAsScreenDetections(
                1L, "surface", 300L, 0L, 0L,
                1_000, 2_000, 1_000, 2_000);

        assertEquals(1, returned.size());
        assertSame(RenderSourceReference.UNKNOWN, returned.get(0).getRenderSourceReference());
    }

    private static RenderSourceReference renderReference(
            long sourceUptimeMillis, double biasX, double biasY) {
        RenderSourceReference.Origin origin = new RenderSourceReference.Origin(
                1L, 1L, 7, 1_000, 2_000, 1L);
        return RenderSourceReference.known(origin, sourceUptimeMillis, biasX, biasY);
    }
}
