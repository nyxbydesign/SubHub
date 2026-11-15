package com.subhub.app.detection;

import static org.junit.Assert.*;
import org.junit.Test;

public final class PipelineIdlePolicyTest {
    @Test public void highExpiresWithoutAnotherForegroundEvent() {
        PipelineIdlePolicy policy = new PipelineIdlePolicy();
        assertEquals(-1, policy.delayMillis(true, true, false, 0));
        assertEquals(30_000, policy.delayMillis(false, true, false, 10));
        assertEquals(1, policy.delayMillis(false, true, false, 30_009));
        assertEquals(0, policy.delayMillis(false, true, false, 30_010));
    }
    @Test public void irrelevantActivityDoesNotExtendDeadline() {
        PipelineIdlePolicy policy = new PipelineIdlePolicy();
        policy.delayMillis(false, true, false, 100);
        assertEquals(15_000, policy.delayMillis(false, true, false, 15_100));
        assertEquals(0, policy.delayMillis(false, true, false, 30_100));
    }
    @Test public void trackedReturnBeforeIdleClearsTheOldDeadline() {
        PipelineIdlePolicy policy = new PipelineIdlePolicy();
        assertEquals(30_000, policy.delayMillis(false, true, false, 0));
        assertEquals(-1, policy.delayMillis(true, true, false, 29_999));
        assertEquals(-1, policy.delayMillis(true, true, false, 30_000));
        assertEquals(30_000, policy.delayMillis(false, true, false, 40_000));
    }
    @Test public void trackedReturnAfterIdleDoesNotWaitForAnotherGracePeriod() {
        PipelineIdlePolicy policy = new PipelineIdlePolicy();
        assertEquals(30_000, policy.delayMillis(false, true, false, 0));
        assertEquals(0, policy.delayMillis(false, true, false, 35_000));
        assertEquals(-1, policy.delayMillis(true, true, false, 35_001));
        assertEquals(30_000, policy.delayMillis(false, true, false, 40_000));
    }
    @Test public void saverStartsIdleAndNeverRenewsElapsedGrace() {
        PipelineIdlePolicy policy = new PipelineIdlePolicy();
        assertEquals(0, policy.delayMillis(false, true, true, 0));
        assertEquals(0, policy.delayMillis(false, true, false, 30_000));
        assertEquals(-1, policy.delayMillis(true, true, true, 30_001));
    }
    @Test public void mediumAndLowDoNotRetainWarmSessions() {
        assertEquals(0, new PipelineIdlePolicy().delayMillis(false, false, false, 0));
    }
    @Test public void onlyHighRealtimeMaySpinOutsideSaver() {
        for (int threads = 1; threads <= 4; threads++) {
            assertEquals(threads == 4, PipelineIdlePolicy.allowSpinning(true, threads, false));
            assertFalse(PipelineIdlePolicy.allowSpinning(true, threads, true));
            assertFalse(PipelineIdlePolicy.allowSpinning(false, threads, false));
        }
    }
}
