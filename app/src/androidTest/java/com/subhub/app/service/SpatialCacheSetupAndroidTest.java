package com.subhub.app.service;

import android.content.Context;
import android.os.Build;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

public final class SpatialCacheSetupAndroidTest {
    @Test public void setExplicitEmulatorExperimentFlag() {
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        String value = InstrumentationRegistry.getArguments().getString("enableSpatialCache", "");
        assumeTrue("Explicit true/false required", "true".equals(value) || "false".equals(value));
        Context context = ApplicationProvider.getApplicationContext();
        assertTrue(context.getSharedPreferences("spatial_cache_experiment", Context.MODE_PRIVATE)
                .edit().putBoolean("enabled", Boolean.parseBoolean(value)).commit());
    }
}
