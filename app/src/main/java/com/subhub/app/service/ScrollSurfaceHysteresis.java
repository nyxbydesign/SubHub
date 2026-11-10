package com.subhub.app.service;

/** Keeps a proven scroll owner through transient low-confidence companion events. */
final class ScrollSurfaceHysteresis {
    /*
     * Chromium can alternate a proven scroll owner with low-confidence companion callbacks for
     * a little over one second. Invalidating the world cache at that boundary visibly erases
     * scroll-back coverage even though neither the window nor the producer changed. Keep the
     * grace bounded by both time and event count so a dead owner still expires promptly.
     */
    static final int MAX_LOW_REUSE_EVENTS = 16;
    static final long MAX_LOW_REUSE_MS = 2_500L;

    enum Decision { USE_OBSERVED, REUSE_ACTIVE, DISABLE }

    private ScrollSurfaceHysteresis() {}

    static Decision decide(
            boolean observedCacheable,
            int observedWindowId,
            boolean activePresent,
            int activeWindowId,
            boolean activeProvisional,
            long nowUptime,
            long lastTrustedUptime,
            int lowReuseCount) {
        if (observedCacheable) return Decision.USE_OBSERVED;
        // Some browser/accessibility providers never expose a stable scroll-node identity. The
        // provisional window fallback is reusable while the application window is unchanged.
        // ScreenshotAccessibilityService fences it on same-window state transitions and explicit
        // content-invalid events; without this branch, Chrome's first ordinary scroll disables
        // every later quality frame before inference can run.
        if (activeProvisional && activePresent && observedWindowId >= 0
                && observedWindowId == activeWindowId) {
            return Decision.REUSE_ACTIVE;
        }
        boolean withinTime = lastTrustedUptime > 0L && nowUptime >= lastTrustedUptime
                && nowUptime - lastTrustedUptime <= MAX_LOW_REUSE_MS;
        if (activePresent && observedWindowId >= 0 && observedWindowId == activeWindowId
                && withinTime && lowReuseCount < MAX_LOW_REUSE_EVENTS) {
            return Decision.REUSE_ACTIVE;
        }
        return Decision.DISABLE;
    }
}
