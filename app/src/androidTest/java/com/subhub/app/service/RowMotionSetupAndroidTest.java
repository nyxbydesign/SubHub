package com.subhub.app.service;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assume.assumeTrue;
import static org.junit.Assert.assertTrue;
public final class RowMotionSetupAndroidTest {
    @Test public void setExplicitShadowFlag() {
        String value=InstrumentationRegistry.getArguments().getString("enableRowMotionShadow", "");
        assumeTrue("Explicit argument required", "true".equals(value)||"false".equals(value));
        Context context=ApplicationProvider.getApplicationContext();
        assertTrue(context.getSharedPreferences("row_motion_experiment",Context.MODE_PRIVATE)
                .edit().putBoolean("enabled",Boolean.parseBoolean(value))
                .putBoolean("save_frames", Boolean.parseBoolean(value) && "true".equals(
                        InstrumentationRegistry.getArguments().getString("saveRowFrames", "false")))
                .commit());
    }
}
