package com.subhub.app.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.BuildConfig;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.DetectionPreset;
import com.subhub.app.detection.NudeNetClassCatalog;
import com.subhub.app.detection.text.TextSmutConfig;
import com.subhub.app.popup.PopupStormSettings;
import com.subhub.app.settings.CaptureMethod;
import com.subhub.app.settings.FeatureModuleManager;
import com.subhub.app.settings.SettingsRepository;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Persistent, explicit fixture for an owned emulator; not a live-pipeline readiness test. */
@RunWith(AndroidJUnit4.class)
public final class CorpusAccessibilitySetupAndroidTest {
    @Test public void configureMaximumImageOnlyPlayback() throws Exception {
        assertTrue("Explicit emulator fixture opt-in required", "true".equals(
                InstrumentationRegistry.getArguments().getString("corpusAccessibilitySetup")));
        assertTrue("Debug emulator only", BuildConfig.DEBUG
                && (Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")));
        String session = InstrumentationRegistry.getArguments().getString("corpusSetupSession");
        assertTrue("New bounded fixture session required", session != null
                && session.matches("[a-z0-9][a-z0-9-]{0,63}"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String foregroundPackage = InstrumentationRegistry.getArguments().getString(
                "corpusForegroundPackage", "com.subhub.app.test");
        assertTrue("Only owned playback or explicitly selected Chromium fixtures are supported",
                "com.subhub.app.test".equals(foregroundPackage)
                        || "com.android.chrome".equals(foregroundPackage)
                        || "org.chromium.chrome".equals(foregroundPackage)
                        || "org.chromium.chrome.stable".equals(foregroundPackage));
        File directory = new File(context.getFilesDir(), "corpus-accessibility-setup");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File receipt = new File(directory, session + ".json");
        assertFalse("Preserve existing setup receipt", receipt.exists());

        Set<String> categories = new LinkedHashSet<>();
        for (int index = 0; index < NudeNetClassCatalog.CLASS_COUNT; index++) {
            categories.addAll(NudeNetClassCatalog.byIndex(index).getCategories());
        }
        // The shared face category selects both model classes; aliases add no extra class.
        categories.remove("face_female");
        categories.remove("face_male");
        assertEquals(17, categories.size());
        SettingsRepository settings = new SettingsRepository(context);
        SharedPreferences preferences = settings.preferences();
        assertTrue(preferences.edit()
                .putBoolean(FeatureModuleManager.KEY_CENSOR_ENABLED, true)
                .putBoolean(FeatureModuleManager.KEY_LIMITS_ENABLED, false)
                .putBoolean(FeatureModuleManager.KEY_WALLET_ENABLED, false)
                .putBoolean(FeatureModuleManager.KEY_SUBLIMINAL_ENABLED, false)
                .putBoolean(PopupStormSettings.K_ENABLED, false)
                .putString(SettingsRepository.KEY_CAPTURE_METHOD, CaptureMethod.APP_MODE.preferenceValue())
                .putString(SettingsRepository.KEY_DETECTION_PRESET, DetectionPreset.HIGH.preferenceValue())
                .putInt(SettingsRepository.KEY_CONFIDENCE, 18)
                .putStringSet(SettingsRepository.KEY_ENABLED_CATEGORIES, categories)
                .putString(SettingsRepository.KEY_CENSOR_COVERAGE, "detected_areas")
                .putString(SettingsRepository.KEY_CENSOR_TYPE, "box")
                .putFloat(SettingsRepository.KEY_CENSOR_SIZE_PADDING, .14f)
                .putInt(SettingsRepository.KEY_CENSOR_INTENSITY, 50)
                .putBoolean(SettingsRepository.KEY_SHOW_TEXT, false)
                .putBoolean(SettingsRepository.KEY_SHOW_BORDER, false)
                .putBoolean(SettingsRepository.KEY_ANIMATE_BORDER, false)
                .putBoolean(SettingsRepository.KEY_REVERSE_MODE, false)
                .putBoolean(SettingsRepository.KEY_TEXT_SMUT_ENABLED, true)
                .putInt(SettingsRepository.KEY_TEXT_SMUT_SENSITIVITY, TextSmutConfig.SENSITIVITY_BALANCED)
                .putStringSet(SettingsRepository.KEY_TEXT_SMUT_CATEGORIES, TextSmutConfig.DEFAULT_CATEGORIES)
                .putStringSet(SettingsRepository.KEY_CUSTOM_PHRASES, Collections.emptySet()).commit());
        for (String experiment : new String[]{"row_motion_experiment", "spatial_cache_experiment",
                "spatial_tracking_experiment", "capture_gap_experiment", "gpu_preparation_experiment",
                "capture_readback_profile"}) {
            assertTrue(context.getSharedPreferences(experiment, Context.MODE_PRIVATE).edit()
                    .putBoolean("enabled", false).commit());
        }
        assertTrue(context.getSharedPreferences("main_thread_probe", Context.MODE_PRIVATE)
                .edit().putBoolean("armed", false).commit());
        AppModeManager appMode = new AppModeManager(context);
        Set<String> scope = Collections.singleton(foregroundPackage);
        appMode.save(true, scope);
        DetectorConfig config = settings.loadDetectorConfig();
        assertEquals(DetectionPreset.HIGH, settings.loadDetectionPreset());
        assertEquals(CaptureMethod.APP_MODE, settings.loadCaptureMethod());
        assertEquals(.18f, config.getConfidenceThreshold(), .000001f);
        assertEquals(6, config.getMinDetectionSize());
        assertEquals(4, config.getInferenceThreads());
        assertEquals(512, config.getInferenceResolution());
        assertEquals(categories, config.getEnabledCategories());
        assertEquals(.14f, settings.loadAppearance().getSizePadding(), .000001f);
        assertTrue(settings.loadTextSmutConfig().isEnabled());
        assertTrue(appMode.shouldRecognize(foregroundPackage));
        assertFalse(appMode.shouldRecognize("com.subhub.app"));
        assertEquals("com.android.chrome".equals(foregroundPackage),
                appMode.shouldRecognize("com.android.chrome"));
        assertEquals(scope, appMode.getSelectedPackages());
        assertEquals(scope, appMode.getIncludedPackages());
        assertFalse(appMode.isAllApps());

        JSONObject result = new JSONObject().put("schemaVersion", 1).put("sessionId", session)
                .put("status", "configured-not-live-verified").put("uptimeMs", SystemClock.uptimeMillis())
                .put("targetApkSha256", hash(new FileInputStream(context.getApplicationInfo().sourceDir)))
                .put("modelSha256", hash(context.getAssets().open(config.getModelFilename())))
                .put("preset", "high").put("presetDescription", DetectionPreset.HIGH.getDescription())
                .put("captureMethod", "app_mode").put("selectedPackages", new JSONArray(scope))
                .put("categories", new JSONArray(categories)).put("confidence", config.getConfidenceThreshold())
                .put("minimumSize", config.getMinDetectionSize()).put("inferenceThreads", config.getInferenceThreads())
                .put("configuredInferenceResolution", config.getInferenceResolution())
                .put("detectorBoxPadding", config.getBoxPadding()).put("detectCoveredFlag", config.isDetectCovered())
                .put("appearancePadding", settings.loadAppearance().getSizePadding())
                .put("textClassifierEnabled", true).put("originalBrowserAccessibilityEventsKnown", false)
                .put("liveServiceVerified", false).put("requiresMatchingTargetReinstallAfterInstrumentation", true);
        File partial = new File(directory, session + ".json.partial");
        assertFalse("Preserve partial receipt", partial.exists());
        Files.write(partial.toPath(), result.toString().getBytes(StandardCharsets.UTF_8));
        assertTrue("Atomic receipt publication", partial.renameTo(receipt));
    }

    private static String hash(InputStream source) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = source) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = stream.read(buffer)) != -1) sha.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : sha.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
