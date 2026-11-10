package com.subhub.app.service;

import static org.junit.Assert.assertEquals;

import android.os.PowerManager;

import org.junit.Test;

public final class CaptureLoadGovernorTest {
    @Test
    public void normalModeKeepsDisplayRateCapture() {
        assertEquals(16L, CaptureLoadGovernor.intervalFor(
                0L, PowerManager.THERMAL_STATUS_NONE, false));
    }

    @Test
    public void explicitSystemPressureBacksOffProgressively() {
        assertEquals(50L, CaptureLoadGovernor.intervalFor(
                0L, PowerManager.THERMAL_STATUS_NONE, true));
        assertEquals(66L, CaptureLoadGovernor.intervalFor(
                0L, PowerManager.THERMAL_STATUS_SEVERE, false));
        assertEquals(250L, CaptureLoadGovernor.intervalFor(
                0L, PowerManager.THERMAL_STATUS_EMERGENCY, false));
    }

    @Test
    public void userRequestedCadenceRemainsALowerBound() {
        assertEquals(150L, CaptureLoadGovernor.intervalFor(
                150L, PowerManager.THERMAL_STATUS_SEVERE, false));
    }
}
