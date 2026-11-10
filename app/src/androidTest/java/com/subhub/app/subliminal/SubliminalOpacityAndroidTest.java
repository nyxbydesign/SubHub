package com.subhub.app.subliminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.subhub.app.R;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Offscreen renderer and isolated real-preference fixtures; no service or overlay grants. */
@RunWith(AndroidJUnit4.class)
public final class SubliminalOpacityAndroidTest {
    private Context context;
    private SharedPreferences preferences;
    private Map<String, ?> before;
    private SubliminalSettingsRepository repository;

    @Before public void setUp() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        preferences = target.getSharedPreferences("subliminal_opacity_android_test", Context.MODE_PRIVATE);
        before = new HashMap<>(preferences.getAll());
        assertTrue(preferences.edit().clear().commit());
        context = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                assertEquals(SettingsRepository.PREFERENCES_NAME, name);
                return preferences;
            }
        };
        repository = new SubliminalSettingsRepository(context);
    }

    @After public void restore() {
        if (before == null) return;
        SharedPreferenceTestRestore.restore(preferences, before);
        assertEquals(before, preferences.getAll());
    }

    @Test public void thirtyFivePercentPersistsAndRendersAtSelectedAlpha() {
        saveOpacity(35);
        // Commit waits for the repository's preceding apply to reach the real preference file.
        assertTrue(preferences.edit().commit());
        assertEquals(35, preferences.getInt(SubliminalSettingsRepository.KEY_OPACITY, -1));
        SubliminalSettings loaded = new SubliminalSettingsRepository(context).load();
        assertEquals(35, loaded.getOpacityPercent());
        assertEquals(2_400L, loaded.getVisibleMillis());
        assertEquals(12_000L, loaded.getMinimumIntervalMillis());
        assertEquals(25_000L, loaded.getMaximumIntervalMillis());
        assertEquals(22, loaded.getTextSizeSp());
        assertRenderedOpacity(loaded, 0.35f);
    }

    @Test public void invalidOpacityRetainsSaveAndLoadClampingWithoutRewritingStoredInput() {
        int[] inputs = {Integer.MIN_VALUE, 0, 36, Integer.MAX_VALUE};
        int[] expected = {1, 1, 35, 35};
        for (int index = 0; index < inputs.length; index++) {
            saveOpacity(inputs[index]);
            assertEquals(expected[index], preferences.getInt(
                    SubliminalSettingsRepository.KEY_OPACITY, -1));
            assertEquals(expected[index], repository.load().getOpacityPercent());
            assertTrue(preferences.edit().putInt(
                    SubliminalSettingsRepository.KEY_OPACITY, inputs[index]).commit());
            assertEquals(expected[index], repository.load().getOpacityPercent());
            assertEquals(inputs[index], preferences.getInt(
                    SubliminalSettingsRepository.KEY_OPACITY, -1));
        }
        assertRenderedOpacity(repository.load(), 0.35f);
    }

    @Test public void existingSelectionsAndPresetDefaultsRemainUnchanged() {
        assertEquals(5, repository.load().getOpacityPercent());
        for (int opacity = 1; opacity <= 35; opacity++) {
            saveOpacity(opacity);
            Map<String, ?> saved = new HashMap<>(preferences.getAll());
            SubliminalSettings loaded = new SubliminalSettingsRepository(context).load();
            assertEquals(opacity, loaded.getOpacityPercent());
            assertEquals(saved, preferences.getAll());
            if (opacity <= 15) assertRenderedOpacity(loaded, opacity / 100f);
        }
        SubliminalSettings.Preset[] presets = {SubliminalSettings.Preset.GENTLE,
                SubliminalSettings.Preset.NORMAL, SubliminalSettings.Preset.STRICT,
                SubliminalSettings.Preset.ULTRA};
        int[] defaults = {3, 5, 7, 10};
        for (int index = 0; index < presets.length; index++) {
            repository.savePreset(presets[index]);
            repository.saveAdvanced(false, 35, 2_400L, 12_000L, 25_000L, 22);
            assertEquals(defaults[index], repository.load().getOpacityPercent());
        }
    }

    @Test public void rendererClampsMalformedSettingsAtBothBounds() {
        int[] inputs = {36, Integer.MAX_VALUE, 0, Integer.MIN_VALUE};
        float[] expected = {0.35f, 0.35f, 0.01f, 0.01f};
        for (int index = 0; index < inputs.length; index++) {
            SubliminalSettings malformed = new SubliminalSettings(SubliminalSettings.Preset.NORMAL,
                    true, inputs[index], 2_000L, 25_000L, 60_000L, 19,
                    Collections.emptySet(), "");
            assertRenderedOpacity(malformed, expected[index]);
        }
    }

    @Test public void opacityControlReachesThirtyFiveWithoutChangingOtherRanges() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context themed = new ContextThemeWrapper(context, R.style.Theme_SubHub);
            View layout = LayoutInflater.from(themed).inflate(
                    R.layout.activity_subliminal_settings, new FrameLayout(themed), false);
            SeekBar opacity = layout.findViewById(R.id.opacity);
            assertEquals(34, opacity.getMax());
            opacity.setProgress(34);
            saveOpacity(opacity.getProgress() + 1);
            assertEquals(35, repository.load().getOpacityPercent());
            opacity.setProgress(35);
            assertEquals(34, opacity.getProgress());
            assertEquals(14, ((SeekBar) layout.findViewById(R.id.text_size)).getMax());
            assertEquals(32, ((SeekBar) layout.findViewById(R.id.duration)).getMax());
            assertEquals(295, ((SeekBar) layout.findViewById(R.id.min_interval)).getMax());
            assertEquals(295, ((SeekBar) layout.findViewById(R.id.max_interval)).getMax());
        });
    }

    private void saveOpacity(int opacity) {
        repository.saveAdvanced(true, opacity, 2_400L, 12_000L, 25_000L, 22);
    }

    private void assertRenderedOpacity(SubliminalSettings settings, float expectedAlpha) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            FrameLayout root = new FrameLayout(context);
            TextView message = new TextView(context);
            message.setText("Whisper");
            message.setTextColor(Color.WHITE);
            message.setTextSize(settings.getTextSizeSp());
            // The uniform background makes View alpha independent of glyph antialiasing.
            message.setBackgroundColor(Color.WHITE);
            message.setAlpha(SubliminalOverlayController.opacityAlpha(settings));
            root.addView(message, new FrameLayout.LayoutParams(128, 64));
            root.measure(View.MeasureSpec.makeMeasureSpec(128, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(64, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 128, 64);
            Bitmap pixels = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888);
            try {
                root.draw(new Canvas(pixels));
                assertEquals(expectedAlpha, message.getAlpha(), 0.0001f);
                int expected = Math.round(expectedAlpha * 255f);
                int actual = Color.alpha(pixels.getPixel(64, 32));
                assertTrue("Expected alpha byte " + expected + ", observed " + actual,
                        Math.abs(expected - actual) <= 1);
            } catch (AssertionError failure) {
                try {
                    File evidence = File.createTempFile("subliminal-opacity-", ".png", context.getCacheDir());
                    try (FileOutputStream stream = new FileOutputStream(evidence)) {
                        if (!pixels.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                            throw new IOException("Could not retain opacity pixels");
                        }
                    }
                    failure.addSuppressed(new AssertionError("Opacity pixels: " + evidence.getAbsolutePath()));
                } catch (IOException retainFailure) {
                    failure.addSuppressed(retainFailure);
                }
                throw failure;
            } finally {
                pixels.recycle();
            }
        });
    }
}
