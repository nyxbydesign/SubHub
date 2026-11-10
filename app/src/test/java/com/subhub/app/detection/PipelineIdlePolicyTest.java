package com.subhub.app.detection;

import static org.junit.Assert.*;
import org.junit.Test;

public final class PipelineIdlePolicyTest {
    @Test public void highExpiresWithoutAnotherForegroundEvent() {
        PipelineIdlePolicy policy = new PipelineIdlePolicy();
        assertEquals(-1, policy.delayMillis(true, true, false, 0));
        assertEquals(180000, policy.delayMillis(false, true, false, 10));
        assertEquals(1, policy.delayMillis(false, true, false, 180009));
        assertEquals(0, policy.delayMillis(false, true, false, 180010));
    }
    @Test public void irrelevantActivityDoesNotExtendDeadline() {
        PipelineIdlePolicy policy = new PipelineIdlePolicy();
        policy.delayMillis(false, true, false, 100);
        assertEquals(90000, policy.delayMillis(false, true, false, 90100));
    }
    @Test public void trackedReturnStartsANewGraceOnlyAfterNextDeparture() {
        PipelineIdlePolicy policy = new PipelineIdlePolicy();
        policy.delayMillis(false, true, false, 0);
        assertEquals(-1, policy.delayMillis(true, true, false, 100000));
        assertEquals(180000, policy.delayMillis(false, true, false, 200000));
    }
    @Test public void saverStartsIdleAndNeverRenewsElapsedGrace() {
        PipelineIdlePolicy policy = new PipelineIdlePolicy();
        assertEquals(0, policy.delayMillis(false, true, true, 0));
        assertEquals(0, policy.delayMillis(false, true, false, 180000));
        assertEquals(-1, policy.delayMillis(true, true, true, 180001));
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
