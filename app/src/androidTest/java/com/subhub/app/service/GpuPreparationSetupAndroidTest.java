package com.subhub.app.service;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assume.assumeTrue;
import static org.junit.Assert.assertTrue;

public final class GpuPreparationSetupAndroidTest {
    @Test public void setExplicitExperimentFlag() {
        String value = InstrumentationRegistry.getArguments().getString("enableGpuPreparation", "");
        assumeTrue("Explicit true/false required", "true".equals(value) || "false".equals(value));
        Context context = ApplicationProvider.getApplicationContext();
        assertTrue(context.getSharedPreferences("gpu_preparation_experiment", Context.MODE_PRIVATE)
                .edit().putBoolean("enabled", Boolean.parseBoolean(value)).commit());
    }
}
