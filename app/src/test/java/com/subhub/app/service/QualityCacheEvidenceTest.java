package com.subhub.app.service;

import com.subhub.app.detection.BBox;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class QualityCacheEvidenceTest {
    @Test public void serviceClassifiesFastOnlyAndDeadlineAsFastEvidenceNotQuality() {
        for (SceneTransactionCoordinator.CommitKind kind : SceneTransactionCoordinator.CommitKind.values()) {
            ContentSpaceRegionCache.Evidence evidence = ScreenshotAccessibilityService.worldCacheEvidence(kind, true, 123);
            int expected = kind == SceneTransactionCoordinator.CommitKind.ACTIVE_FAST
                    ? ContentSpaceRegionCache.Evidence.NONE : kind == SceneTransactionCoordinator.CommitKind.SETTLED_FUSED
                    ? ContentSpaceRegionCache.Evidence.COMPLETE : ContentSpaceRegionCache.Evidence.FAST;
            assertEquals(expected, evidence.lane);
            assertEquals(123, evidence.sourceUptime);
            assertEquals(ContentSpaceRegionCache.Evidence.NONE,
                    ScreenshotAccessibilityService.worldCacheEvidence(kind, false, 123).lane);
        }
        assertEquals(ContentSpaceRegionCache.Evidence.NONE,
                ScreenshotAccessibilityService.worldCacheEvidence(null, true, 123).lane);
    }
    private static final BBox FULL = new BBox(0, 0, 1000, 2000);
    private static ContentSpaceRegionCache.Observation avatar(boolean confirmed) {
        return new ContentSpaceRegionCache.Observation(-1, "FACE_FEMALE", "face", .9f,
                new BBox(50, 100, 100, 90), true, false, 0, 0, confirmed);
    }
    private static void observe(ContentSpaceRegionCache cache, long now,
            ContentSpaceRegionCache.Evidence evidence, ContentSpaceRegionCache.Observation... hits) {
        cache.observeCommittedScene(1, "surface", now, 0, 0, 1000, 2000, 1000, 2000,
                evidence, List.of(hits));
    }
    private static ContentSpaceRegionCache confirmed() {
        ContentSpaceRegionCache cache = new ContentSpaceRegionCache();
        observe(cache, 100, ContentSpaceRegionCache.Evidence.quality(100, FULL), avatar(true));
        assertEquals(1, cache.size());
        return cache;
    }
    @Test public void fastMissesNeverEraseConfirmedQualityAvatar() {
        ContentSpaceRegionCache cache = confirmed();
        for (long time = 200; time < 3000; time += 100) {
            observe(cache, time, ContentSpaceRegionCache.Evidence.full(ContentSpaceRegionCache.Evidence.FAST, time));
            assertEquals(1, cache.size());
        }
    }
    @Test public void twoFreshInspectedQualityMissesRetireActuallyMissingAvatar() {
        ContentSpaceRegionCache cache = confirmed();
        observe(cache, 200, ContentSpaceRegionCache.Evidence.quality(200, FULL));
        assertEquals(1, cache.size());
        observe(cache, 300, ContentSpaceRegionCache.Evidence.quality(300, FULL));
        assertEquals(0, cache.size());
    }
    @Test public void otherTileAndClippedEdgeAreNotNegativeEvidence() {
        ContentSpaceRegionCache cache = confirmed();
        for (long time = 200; time < 1000; time += 100) {
            observe(cache, time, ContentSpaceRegionCache.Evidence.quality(time,
                    new BBox(0, time % 200 == 0 ? 1000 : 150, 1000, 1000)));
            assertEquals(1, cache.size());
        }
    }
    @Test public void staleAndRepeatedCaptureCannotAccumulateMisses() {
        ContentSpaceRegionCache cache = confirmed();
        observe(cache, 200, ContentSpaceRegionCache.Evidence.quality(90, FULL));
        observe(cache, 210, ContentSpaceRegionCache.Evidence.quality(90, FULL));
        observe(cache, 220, ContentSpaceRegionCache.Evidence.quality(200, FULL));
        observe(cache, 230, ContentSpaceRegionCache.Evidence.quality(200, FULL));
        assertEquals(1, cache.size());
        observe(cache, 240, ContentSpaceRegionCache.Evidence.quality(240, FULL));
        assertEquals(0, cache.size());
    }
    @Test public void unpromotedHitPreventsMissButDoesNotCreateDurableRegion() {
        ContentSpaceRegionCache cache = new ContentSpaceRegionCache();
        observe(cache, 100, ContentSpaceRegionCache.Evidence.quality(100, FULL), avatar(false));
        assertEquals(0, cache.size());
        observe(cache, 200, ContentSpaceRegionCache.Evidence.quality(200, FULL), avatar(true));
        observe(cache, 300, ContentSpaceRegionCache.Evidence.quality(300, FULL));
        observe(cache, 400, ContentSpaceRegionCache.Evidence.quality(400, FULL), avatar(false));
        observe(cache, 500, ContentSpaceRegionCache.Evidence.quality(500, FULL));
        assertEquals(1, cache.size());
        observe(cache, 600, ContentSpaceRegionCache.Evidence.quality(600, FULL));
        assertEquals(0, cache.size());
    }
    @Test public void fastOnlyRegionsStillRetireAfterFastMisses() {
        ContentSpaceRegionCache cache = new ContentSpaceRegionCache();
        ContentSpaceRegionCache.Observation fast = new ContentSpaceRegionCache.Observation(1,
                "FACE_FEMALE", "face", .9f, new BBox(50, 100, 100, 90), true, false, 2, 0, false);
        observe(cache, 100, ContentSpaceRegionCache.Evidence.full(ContentSpaceRegionCache.Evidence.FAST, 100), fast);
        observe(cache, 200, ContentSpaceRegionCache.Evidence.quality(200, FULL));
        observe(cache, 300, ContentSpaceRegionCache.Evidence.quality(300, FULL));
        assertEquals(1, cache.size());
        observe(cache, 400, ContentSpaceRegionCache.Evidence.full(ContentSpaceRegionCache.Evidence.FAST, 400));
        observe(cache, 500, ContentSpaceRegionCache.Evidence.full(ContentSpaceRegionCache.Evidence.FAST, 500));
        assertEquals(0, cache.size());
    }
    @Test public void malformedCoverageAndFutureTimestampFailClosed() {
        ContentSpaceRegionCache cache = confirmed();
        observe(cache, 200, ContentSpaceRegionCache.Evidence.quality(200, new BBox(-1, 0, 1000, 2000)));
        observe(cache, 210, ContentSpaceRegionCache.Evidence.quality(211, FULL));
        observe(cache, 220, ContentSpaceRegionCache.Evidence.quality(220, FULL));
        assertEquals(1, cache.size());
    }

    @Test public void replayedPositiveDoesNotRenewLifetimeOrEraseNewerNegativeEvidence() {
        ContentSpaceRegionCache cache = confirmed();
        observe(cache, 200, ContentSpaceRegionCache.Evidence.quality(200, FULL));
        observe(cache, 300, ContentSpaceRegionCache.Evidence.quality(100, FULL), avatar(true));
        observe(cache, 400, ContentSpaceRegionCache.Evidence.quality(400, FULL));
        assertEquals(0, cache.size());
        cache = confirmed();
        observe(cache, 200, ContentSpaceRegionCache.Evidence.quality(100, FULL), avatar(true));
        assertEquals(0, cache.queryNearAsScreenDetections(1, "surface",
                ContentSpaceRegionCache.VISUAL_TTL_MS + 101, 0, 0, 1000, 2000, 1000, 2000).size());
    }

    @Test public void unpromotedSightingsDoNotExtendDurableLifetimeAndDocumentChangeClearsIt() {
        ContentSpaceRegionCache cache = confirmed();
        observe(cache, 200, ContentSpaceRegionCache.Evidence.quality(200, FULL), avatar(false));
        assertEquals(0, cache.queryNearAsScreenDetections(1, "surface",
                ContentSpaceRegionCache.VISUAL_TTL_MS + 101, 0, 0, 1000, 2000, 1000, 2000).size());
        cache = confirmed();
        assertEquals(0, cache.queryNearAsScreenDetections(2, "surface", 200,
                0, 0, 1000, 2000, 1000, 2000).size());
    }
}
