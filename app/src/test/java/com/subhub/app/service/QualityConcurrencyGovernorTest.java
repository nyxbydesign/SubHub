package com.subhub.app.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class QualityConcurrencyGovernorTest {
    @Test public void ordinaryOverlapNeverPausesQuality() {
        QualityConcurrencyGovernor governor = new QualityConcurrencyGovernor(1_000L);
        governor.recordFast(40L, false, 100L);
        governor.recordFast(42L, false, 200L);
        governor.recordFast(38L, false, 300L);

        for (int index = 0; index < 10; index++) {
            assertEquals(QualityConcurrencyGovernor.Decision.NONE,
                    governor.recordFast(58L, true, 400L + index));
        }
        assertTrue(governor.allows(500L));
    }

    @Test public void twoMaterialRegressionsInWindowPauseThenRecover() {
        QualityConcurrencyGovernor governor = new QualityConcurrencyGovernor(1_000L);
        governor.recordFast(40L, false, 100L);
        governor.recordFast(40L, false, 200L);
        governor.recordFast(40L, false, 300L);

        assertEquals(QualityConcurrencyGovernor.Decision.NONE,
                governor.recordFast(90L, true, 400L));
        assertEquals(QualityConcurrencyGovernor.Decision.PAUSE_CONCURRENT_QUALITY,
                governor.recordFast(100L, true, 600L));
        assertFalse(governor.allows(1_599L));
        assertTrue(governor.allows(1_600L));
    }

    @Test public void slowSamplesOutsideWindowDoNotTripCircuit() {
        QualityConcurrencyGovernor governor = new QualityConcurrencyGovernor(1_000L);
        governor.recordFast(45L, false, 100L);
        governor.recordFast(45L, false, 200L);
        governor.recordFast(45L, false, 300L);
        governor.recordFast(120L, true, 400L);
        governor.recordFast(55L, true, 500L);
        governor.recordFast(120L, true,
                400L + QualityConcurrencyGovernor.SLOW_SAMPLE_WINDOW_MS + 1L);
        assertTrue(governor.allows(16_000L));
    }

    @Test public void normalFrameBetweenTailRegressionsDoesNotHideTheTail() {
        QualityConcurrencyGovernor governor = new QualityConcurrencyGovernor(1_000L);
        governor.recordFast(40L, false, 100L);
        governor.recordFast(40L, false, 200L);
        governor.recordFast(40L, false, 300L);

        assertEquals(QualityConcurrencyGovernor.Decision.NONE,
                governor.recordFast(100L, true, 400L));
        assertEquals(QualityConcurrencyGovernor.Decision.NONE,
                governor.recordFast(45L, true, 500L));
        assertEquals(QualityConcurrencyGovernor.Decision.PAUSE_CONCURRENT_QUALITY,
                governor.recordFast(95L, true, 600L));
    }

    @Test public void rollingIdleBaselineAdaptsToARealWorkloadStep() {
        QualityConcurrencyGovernor governor = new QualityConcurrencyGovernor(1_000L);
        governor.recordFast(40L, false, 100L);
        governor.recordFast(42L, false, 200L);
        governor.recordFast(38L, false, 300L);
        for (int index = 0; index < 9; index++) {
            governor.recordFast(100L + index, false, 400L + index);
        }

        assertTrue(governor.idleRuntimeEmaMs() >= 100f);
        assertEquals(QualityConcurrencyGovernor.Decision.NONE,
                governor.recordFast(110L, true, 600L));
        assertEquals(QualityConcurrencyGovernor.Decision.NONE,
                governor.recordFast(115L, true, 700L));
    }

    @Test public void resetDropsOldTopologyBaselineAndPause() {
        QualityConcurrencyGovernor governor = new QualityConcurrencyGovernor(1_000L);
        governor.recordFast(40L, false, 100L);
        governor.recordFast(40L, false, 200L);
        governor.recordFast(40L, false, 300L);
        governor.recordFast(100L, true, 400L);
        governor.recordFast(100L, true, 500L);
        assertFalse(governor.allows(1_000L));

        governor.reset();
        assertTrue(governor.allows(1_000L));
        assertEquals(0f, governor.idleRuntimeEmaMs(), .01f);
    }
}
