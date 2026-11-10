package com.subhub.app.service;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Explicit debug-only experiment toggle; does not grant permissions or arm protection. */
@RunWith(AndroidJUnit4.class)
public final class AnchorExperimentSetupAndroidTest {
    @Test public void setRequestedAnchorExperiment() {
        String requested = InstrumentationRegistry.getArguments()
                .getString("enableViewportAnchorExperiment", "");
        Assume.assumeTrue("Explicit true/false experiment argument required",
                "true".equals(requested) || "false".equals(requested));
        Context context = ApplicationProvider.getApplicationContext();
        boolean enabled = Boolean.parseBoolean(requested);
        assertTrue(context.getSharedPreferences("anchor_motion_experiment", Context.MODE_PRIVATE)
                .edit().putBoolean("enabled", enabled).commit());
        assertEquals(enabled, context.getSharedPreferences(
                "anchor_motion_experiment", Context.MODE_PRIVATE).getBoolean("enabled", !enabled));
    }
}
