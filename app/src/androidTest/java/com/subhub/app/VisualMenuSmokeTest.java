package com.subhub.app;

import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.subhub.app.appmode.AppModeActivity;
import com.subhub.app.atmosphere.AtmosphereActivity;
import com.subhub.app.capture.CustomImagesActivity;
import com.subhub.app.capture.ExportActivity;
import com.subhub.app.commitment.CommitmentActivity;
import com.subhub.app.diagnostics.DiagnosticsActivity;
import com.subhub.app.help.HelpActivity;
import com.subhub.app.penance.PenanceActivity;
import com.subhub.app.popup.PopupStormActivity;
import com.subhub.app.settings.SettingsActivity;
import com.subhub.app.settings.GlobalSettingsActivity;
import com.subhub.app.settings.FeatureModuleManager;
import com.subhub.app.stats.AchievementsActivity;
import com.subhub.app.stats.StatsActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** Launches every user-facing surface and leaves screenshot evidence for visual review. */
@RunWith(AndroidJUnit4.class)
public final class VisualMenuSmokeTest {
    @Test public void everyMenuInflatesAndRenders() throws Exception {
        new FeatureModuleManager(ApplicationProvider.getApplicationContext()).save(true, true, true);
        Map<String, Class<? extends Activity>> screens = new LinkedHashMap<>();
        screens.put("home", MainActivity.class);
        screens.put("settings", SettingsActivity.class);
        screens.put("global-settings", GlobalSettingsActivity.class);
        screens.put("help", HelpActivity.class);
        screens.put("export", ExportActivity.class);
        screens.put("app-mode", AppModeActivity.class);
        screens.put("atmosphere", AtmosphereActivity.class);
        screens.put("commitment", CommitmentActivity.class);
        screens.put("penance", PenanceActivity.class);
        screens.put("popup-storm", PopupStormActivity.class);
        screens.put("custom-images", CustomImagesActivity.class);
        screens.put("diagnostics", DiagnosticsActivity.class);
        screens.put("stats", StatsActivity.class);
        screens.put("achievements", AchievementsActivity.class);

        Bundle arguments = InstrumentationRegistry.getArguments();
        String additionalOutput = arguments.getString("additionalTestOutputDir", "");
        File output = additionalOutput.isEmpty()
                ? new File(ApplicationProvider.getApplicationContext()
                        .getExternalFilesDir(null), "visual-audit")
                : new File(additionalOutput, "visual-audit");
        assertTrue(output.exists() || output.mkdirs());
        for (Map.Entry<String, Class<? extends Activity>> screen : screens.entrySet()) {
            try (ActivityScenario<? extends Activity> ignored =
                         ActivityScenario.launch(screen.getValue())) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                SystemClock.sleep(250L);
                Bitmap screenshot = InstrumentationRegistry.getInstrumentation()
                        .getUiAutomation().takeScreenshot();
                File target = new File(output, screen.getKey() + ".png");
                try (FileOutputStream stream = new FileOutputStream(target)) {
                    assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream));
                } finally {
                    screenshot.recycle();
                }
                assertTrue(target.length() > 1000);
            }
        }
    }
}
