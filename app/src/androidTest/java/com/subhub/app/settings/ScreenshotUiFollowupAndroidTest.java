package com.subhub.app.settings;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.pressKey;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static com.subhub.app.NativeUiActions.revealAboveNavigation;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

import android.content.Context;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.GridLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.material.slider.Slider;
import com.subhub.app.R;
import com.subhub.app.detection.DetectionPreset;
import com.subhub.app.popup.PopupStormActivity;
import com.subhub.app.popup.PopupStormSettings;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.subliminal.SubliminalSettings;
import com.subhub.app.subliminal.SubliminalSettingsActivity;
import com.subhub.app.subliminal.SubliminalSettingsRepository;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class ScreenshotUiFollowupAndroidTest {
    private final Context context = ApplicationProvider.getApplicationContext();
    @Before public void enterDom() { ControllerPinManager.enterDomMode(); }

    @Test public void fourStopSliderPersistsEveryStepFromRealKeyboardInput() {
        SubliminalSettingsRepository repository = new SubliminalSettingsRepository(context);
        repository.savePreset(SubliminalSettings.Preset.GENTLE);
        try (ActivityScenario<SubliminalSettingsActivity> scenario = ActivityScenario.launch(SubliminalSettingsActivity.class)) {
            scenario.onActivity(activity -> {
                Slider slider = activity.findViewById(R.id.preset_slider);
                assertEquals(0f, slider.getValueFrom(), 0f);
                assertEquals(3f, slider.getValueTo(), 0f);
                assertEquals(1f, slider.getStepSize(), 0f);
                assertTrue(slider.requestFocus());
                GridLayout packs = activity.findViewById(R.id.message_packs_grid);
                assertEquals(2, packs.getColumnCount());
                assertEquals(5, packs.getChildCount());
                assertEquals(packs.getChildAt(0).getTop(), packs.getChildAt(1).getTop());
                assertTrue(packs.getChildAt(2).getTop() > packs.getChildAt(0).getTop());
            });
            for (int step = 1; step < 4; step++) {
                onView(withId(R.id.preset_slider)).perform(pressKey(KeyEvent.KEYCODE_DPAD_RIGHT));
                assertEquals(SubliminalSettings.Preset.values()[step], repository.load().getPreset());
            }
        }
    }

    @Test public void imageThreeSelectorsAreThreeAcrossAndExclusive() {
        try (ActivityScenario<PopupStormActivity> scenario = ActivityScenario.launch(PopupStormActivity.class)) {
            onView(withId(R.id.button_advanced_details)).perform(revealAboveNavigation(), click());
            scenario.onActivity(activity -> {
                for (String key : new String[] {PopupStormSettings.K_DENIAL_STYLE, PopupStormSettings.K_DETECTION_MODE}) {
                    GridLayout choices = activity.findViewById(R.id.dynamic_settings).findViewWithTag("popup-choice:" + key);
                    assertEquals(3, choices.getColumnCount());
                    assertEquals(3, choices.getChildCount());
                    assertEquals(choices.getChildAt(0).getTop(), choices.getChildAt(2).getTop());
                    assertTrue(choices.getChildAt(0).getRight() <= choices.getChildAt(1).getLeft());
                    assertTrue(choices.getChildAt(1).getRight() <= choices.getChildAt(2).getLeft());
                }
            });
            onView(withTagValue(is("pixelate"))).perform(revealAboveNavigation(), click());
            assertEquals("pixelate", PopupStormSettings.preferences(context).getString(PopupStormSettings.K_DENIAL_STYLE, ""));
            onView(withTagValue(is("mixed"))).perform(revealAboveNavigation(), click());
            scenario.onActivity(activity -> {
                GridLayout choices = activity.findViewById(R.id.dynamic_settings).findViewWithTag("popup-choice:" + PopupStormSettings.K_DENIAL_STYLE);
                for (int i = 0; i < 3; i++) assertEquals(i == 2, choices.getChildAt(i).isSelected());
            });
        }
    }

    @Test public void oldDetectionPreferencesAreIgnoredAndThreeNewLevelsPersist() {
        SettingsRepository settings = new SettingsRepository(context);
        settings.preferences().edit().remove(SettingsRepository.KEY_DETECTION_PRESET).remove(SettingsRepository.KEY_CONFIDENCE)
                .putString("detection_preset", "ultra").putInt("confidence_threshold_percent", 38)
                .putBoolean(FeatureModuleManager.KEY_CENSOR_ENABLED, true).commit();
        assertEquals(DetectionPreset.MEDIUM, settings.loadDetectionPreset());
        assertEquals(.25f, settings.loadDetectorConfig().getConfidenceThreshold(), .0001f);
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(SettingsActivity.class)) {
            scenario.onActivity(activity -> assertEquals(3, ((ViewGroup) activity.findViewById(R.id.preset_group)).getChildCount()));
            int[] ids = {R.id.radio_preset_low, R.id.radio_preset_medium, R.id.radio_preset_high};
            for (int i = 0; i < ids.length; i++) {
                onView(withId(ids[i])).perform(revealAboveNavigation(), click());
                assertEquals(DetectionPreset.values()[i], settings.loadDetectionPreset());
                assertEquals(DetectionPreset.values()[i].getConfidence(), settings.loadDetectorConfig().getConfidenceThreshold(), .0001f);
            }
        }
    }

    @Test public void inlineImageCanBeDisabledAndDeletedWithoutLeavingSettings() throws Exception {
        new SettingsRepository(context).preferences().edit()
                .putBoolean(FeatureModuleManager.KEY_CENSOR_ENABLED, true).commit();
        com.subhub.app.capture.CustomImageManager manager = new com.subhub.app.capture.CustomImageManager(context);
        java.io.File image = new java.io.File(context.getCacheDir(), "synthetic-inline-censor.png");
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xff9860be);
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(image)) {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
        } finally { bitmap.recycle(); }
        java.util.Set<String> before = new java.util.HashSet<>();
        for (com.subhub.app.capture.CustomImageManager.Entry entry : manager.listEntries()) before.add(entry.getId());
        assertEquals(1, manager.addImages(java.util.List.of(android.net.Uri.fromFile(image))));
        String id = manager.listEntries().stream().filter(entry -> !before.contains(entry.getId()))
                .findFirst().orElseThrow().getId();
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(SettingsActivity.class)) {
            onView(withId(R.id.button_appearance_details)).perform(revealAboveNavigation(), click());
            scenario.onActivity(activity -> {
                ViewGroup row = activity.findViewById(R.id.censor_images_list).findViewWithTag("censor-image:" + id);
                row.getChildAt(1).setTag("inline-toggle");
                row.getChildAt(2).setTag("inline-delete");
            });
            onView(withTagValue(is("inline-toggle"))).perform(revealAboveNavigation(), click());
            assertFalse(manager.listEntries().stream().filter(entry -> entry.getId().equals(id))
                    .findFirst().orElseThrow().isEnabled());
            onView(withTagValue(is("inline-delete"))).perform(revealAboveNavigation(), click());
            assertFalse(manager.listEntries().stream().anyMatch(entry -> entry.getId().equals(id)));
        } finally {
            manager.delete(id);
            assertTrue(image.delete());
        }
    }
}
