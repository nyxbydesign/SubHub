package com.subhub.app.service;

import android.content.Context;
import android.os.Build;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

public final class CaptureGapSetupAndroidTest {
    @Test public void setExplicitEmulatorProbeFlag() {
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        String value = InstrumentationRegistry.getArguments().getString("enableCaptureGap", "");
        assumeTrue("Explicit true/false required", "true".equals(value) || "false".equals(value));
        Context context = ApplicationProvider.getApplicationContext();
        java.io.File marker = new java.io.File(context.getCacheDir(), "capture-gap-arm");
        assertTrue(!marker.exists() || marker.delete());
        assertTrue(context.getSharedPreferences("capture_gap_experiment", Context.MODE_PRIVATE)
                .edit().putBoolean("enabled", Boolean.parseBoolean(value)).commit());
    }
}
