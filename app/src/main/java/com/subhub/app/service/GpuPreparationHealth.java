package com.subhub.app.service;

/** Worker-confined experimental admission policy, not a substitute for the live latency gate. */
final class GpuPreparationHealth {
    private int consecutiveSlow;
    private boolean disabled;

    boolean record(boolean succeeded, long elapsedMs) {
        if (disabled) return false;
        if (!succeeded || elapsedMs < 0 || elapsedMs > 96) disabled = true;
        else {
            consecutiveSlow = elapsedMs > 48 ? consecutiveSlow + 1 : 0;
            disabled = consecutiveSlow >= 3;
        }
        return !disabled;
    }
}
