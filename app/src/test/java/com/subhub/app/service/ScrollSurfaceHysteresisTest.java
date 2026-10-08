package com.subhub.app.service;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ScrollSurfaceHysteresisTest {
    @Test public void stableObservationAlwaysWins() {
        assertEquals(ScrollSurfaceHysteresis.Decision.USE_OBSERVED,
                ScrollSurfaceHysteresis.decide(true, 7, true, 7,
                        false, 2_000L, 1_500L, 0));
    }

    @Test public void lowConfidenceCompanionReusesProvenSameWindowSurface() {
        assertEquals(ScrollSurfaceHysteresis.Decision.REUSE_ACTIVE,
                ScrollSurfaceHysteresis.decide(false, 7, true, 7,
                        false, 2_000L, 1_500L, 3));
    }

    @Test public void lowConfidenceSameWindowReusesDocumentScopedProvisionalSurface() {
        assertEquals(ScrollSurfaceHysteresis.Decision.REUSE_ACTIVE,
                ScrollSurfaceHysteresis.decide(false, 7, true, 7,
                        true, 90_000L, 0L, 500));
    }

    @Test public void provisionalSurfaceCannotCrossApplicationWindows() {
        assertEquals(ScrollSurfaceHysteresis.Decision.DISABLE,
                ScrollSurfaceHysteresis.decide(false, 8, true, 7,
                        true, 2_000L, 0L, 0));
    }

    @Test public void lowConfidenceDifferentWindowCannotReuseHistory() {
        assertEquals(ScrollSurfaceHysteresis.Decision.DISABLE,
                ScrollSurfaceHysteresis.decide(false, 8, true, 7,
                        false, 2_000L, 1_500L, 0));
    }

    @Test public void lowConfidenceCannotCreateFirstSurface() {
        assertEquals(ScrollSurfaceHysteresis.Decision.DISABLE,
                ScrollSurfaceHysteresis.decide(false, 7, false, -1,
                        false, 2_000L, 1_500L, 0));
    }

    @Test public void lowConfidenceGraceExpiresByTime() {
        assertEquals(ScrollSurfaceHysteresis.Decision.DISABLE,
                ScrollSurfaceHysteresis.decide(false, 7, true, 7,
                        false, 4_001L, 1_500L, 0));
    }

    @Test public void chromiumCompanionBurstPastOneSecondKeepsProvenSurface() {
        assertEquals(ScrollSurfaceHysteresis.Decision.REUSE_ACTIVE,
                ScrollSurfaceHysteresis.decide(false, 7, true, 7,
                        false, 3_650L, 1_500L, 9));
    }

    @Test public void lowConfidenceGraceIncludesLastBoundedMoment() {
        assertEquals(ScrollSurfaceHysteresis.Decision.REUSE_ACTIVE,
                ScrollSurfaceHysteresis.decide(false, 7, true, 7,
                        false, 3_999L, 1_500L,
                        ScrollSurfaceHysteresis.MAX_LOW_REUSE_EVENTS - 1));
    }

    @Test public void backwardsClockCannotReuseHistory() {
        assertEquals(ScrollSurfaceHysteresis.Decision.DISABLE,
                ScrollSurfaceHysteresis.decide(false, 7, true, 7,
                        false, 1_499L, 1_500L, 0));
    }

    @Test public void lowConfidenceGraceExpiresByEventCount() {
        assertEquals(ScrollSurfaceHysteresis.Decision.DISABLE,
                ScrollSurfaceHysteresis.decide(false, 7, true, 7,
                        false, 2_000L, 1_500L,
                        ScrollSurfaceHysteresis.MAX_LOW_REUSE_EVENTS));
    }
}
