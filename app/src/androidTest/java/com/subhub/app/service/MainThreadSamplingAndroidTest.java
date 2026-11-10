package com.subhub.app.service;

import android.content.Context;
import android.os.Build;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

/** Arms a one-shot probe; reinstall the target afterward to reconnect Accessibility. */
public final class MainThreadSamplingAndroidTest {
    @Test public void armNextServiceConnection() {
        Assume.assumeTrue("Explicit sampling opt-in required", "true".equals(
                InstrumentationRegistry.getArguments().getString("sampleMainThread")));
        Assume.assumeTrue("Emulator only", Build.HARDWARE.contains("ranchu")
                || Build.HARDWARE.contains("goldfish"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue(context.getSharedPreferences("main_thread_probe", Context.MODE_PRIVATE)
                .edit().putBoolean("armed", true).commit());
    }
}
