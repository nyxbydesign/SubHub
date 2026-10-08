package com.subhub.app.service;
import android.content.Context;
import android.os.Build;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assume.assumeTrue;
import static org.junit.Assert.assertTrue;
public final class ChromeGeometrySetupAndroidTest {
    @Test public void armOneShotNativeGeometryProbe() {
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("probeChromeGeometry")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue(context.getSharedPreferences("chrome_geometry_probe", Context.MODE_PRIVATE)
                .edit().putBoolean("enabled", true).commit());
    }
}
