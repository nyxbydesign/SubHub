package com.subhub.app.detection;

/** Test-only package bridge; does not widen the application's provider-selection API. */
public final class DetectorTestAccess {
    private DetectorTestAccess() {}

    public static void initializeCpu(DetectionEngine engine) throws Exception {
        engine.initializeForProvider("CPU");
    }
}
