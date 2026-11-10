package com.subhub.app.service;

import android.content.Context;
import android.os.Build;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

public final class SpatialTrackingSetupAndroidTest {
    @Test public void setExplicitEmulatorExperimentFlags() {
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        String enabled = InstrumentationRegistry.getArguments().getString("enableSpatialTracking", "");
        String correction = InstrumentationRegistry.getArguments().getString("correctSpatialTracks", "");
        assumeTrue("Explicit flags required", ("true".equals(enabled) || "false".equals(enabled))
                && ("true".equals(correction) || "false".equals(correction)));
        Context context = ApplicationProvider.getApplicationContext();
        assertTrue(context.getSharedPreferences("spatial_tracking_experiment", Context.MODE_PRIVATE)
                .edit().putBoolean("enabled", Boolean.parseBoolean(enabled))
                .putBoolean("correctTracks", Boolean.parseBoolean(correction)).commit());
    }
}
