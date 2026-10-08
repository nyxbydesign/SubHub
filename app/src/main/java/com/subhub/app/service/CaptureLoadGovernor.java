package com.subhub.app.service;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;

/** Keeps normal capture unconstrained, backing off only for explicit system thermal/power signals. */
final class CaptureLoadGovernor implements AutoCloseable {
    private final PowerManager powerManager;
    private final PowerManager.OnThermalStatusChangedListener thermalListener;
    private volatile int thermalStatus = PowerManager.THERMAL_STATUS_NONE;
    private long lastCaptureUptimeMillis = Long.MIN_VALUE;

    CaptureLoadGovernor(Context context) {
        powerManager = context.getSystemService(PowerManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            thermalListener = status -> thermalStatus = status;
            powerManager.addThermalStatusListener(context.getMainExecutor(), thermalListener);
        } else {
            thermalListener = null;
        }
    }

    synchronized boolean shouldCapture(long nowUptimeMillis, long requestedIntervalMillis) {
        boolean powerSave = powerManager != null && powerManager.isPowerSaveMode();
        long interval = intervalFor(requestedIntervalMillis, thermalStatus, powerSave);
        if (lastCaptureUptimeMillis != Long.MIN_VALUE
                && nowUptimeMillis - lastCaptureUptimeMillis < interval) return false;
        lastCaptureUptimeMillis = nowUptimeMillis;
        return true;
    }

    static long intervalFor(long requested, int thermalStatus, boolean powerSave) {
        long interval = Math.max(16L, requested);
        if (powerSave) interval = Math.max(interval, 50L);
        if (thermalStatus >= PowerManager.THERMAL_STATUS_EMERGENCY) {
            interval = Math.max(interval, 250L);
        } else if (thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL) {
            interval = Math.max(interval, 125L);
        } else if (thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            interval = Math.max(interval, 66L);
        } else if (thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
            interval = Math.max(interval, 33L);
        }
        return interval;
    }

    @Override
    public void close() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && powerManager != null && thermalListener != null) {
            powerManager.removeThermalStatusListener(thermalListener);
        }
    }
}
