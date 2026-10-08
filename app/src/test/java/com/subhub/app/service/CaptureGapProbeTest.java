package com.subhub.app.service;

import org.junit.Test;
import static org.junit.Assert.*;

public final class CaptureGapProbeTest {
    @Test public void unarmedProbeDoesNotAffectAdmission() {
        CaptureGapProbe probe = new CaptureGapProbe();
        assertEquals(CaptureGapProbe.Action.PASS, probe.poll(100, false));
        assertTrue(probe.awaitingArm());
        assertEquals(CaptureGapProbe.Action.PASS, probe.poll(-1, true));
        assertEquals(CaptureGapProbe.Action.PASS, probe.poll(Long.MAX_VALUE, true));
        assertTrue(probe.awaitingArm());
    }

    @Test public void pauseHasAnExplicitDeadlineAndCannotBeExtendedOrRearmed() {
        CaptureGapProbe probe = new CaptureGapProbe();
        assertEquals(CaptureGapProbe.Action.START, probe.poll(100, true));
        assertEquals(5100, probe.deadline());
        assertFalse(probe.awaitingArm());
        assertEquals(CaptureGapProbe.Action.WAIT, probe.poll(5099, true));
        assertEquals(5100, probe.deadline());
        assertEquals(CaptureGapProbe.Action.END, probe.poll(5100, true));
        assertEquals(CaptureGapProbe.Action.PASS, probe.poll(5200, true));
    }

    @Test public void latePollingResumesWithoutSleepingOrStartingAnotherGap() {
        CaptureGapProbe probe = new CaptureGapProbe();
        probe.poll(100, true);
        assertEquals(CaptureGapProbe.Action.END, probe.poll(6000, false));
        assertEquals(CaptureGapProbe.Action.PASS, probe.poll(6001, false));
    }
}
