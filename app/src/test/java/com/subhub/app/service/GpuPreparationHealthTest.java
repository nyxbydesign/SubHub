package com.subhub.app.service;

import org.junit.Test;
import static org.junit.Assert.*;

public final class GpuPreparationHealthTest {
    @Test public void isolatedSlowCallsRecoverButThreeConsecutiveDisable() {
        GpuPreparationHealth h = new GpuPreparationHealth();
        assertTrue(h.record(true, 53)); assertTrue(h.record(true, 48));
        assertTrue(h.record(true, 56)); assertTrue(h.record(true, 60));
        assertTrue(h.record(true, 7));
        assertTrue(h.record(true, 49)); assertTrue(h.record(true, 96));
        assertFalse(h.record(true, 49)); assertFalse(h.record(true, 1));
    }
    @Test public void hardStallFailureAndInvalidTimeAreImmediatelyTerminal() {
        for (long time : new long[]{97, 1000, -1}) {
            GpuPreparationHealth h = new GpuPreparationHealth();
            assertFalse(h.record(true, time)); assertFalse(h.record(true, 1));
        }
        assertFalse(new GpuPreparationHealth().record(false, 0));
    }
}
