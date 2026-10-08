package com.subhub.app.popup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.View;
import android.view.ViewGroup;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.subhub.app.R;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.security.ControllerPinManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class PopupStormContractTest {
    @Test public void intensitySliderPersistsAllFourPresetsFromKeyboardInput() {
        ControllerPinManager.enterDomMode();
        android.content.Context context = androidx.test.core.app.ApplicationProvider.getApplicationContext();
        IntensityPresets.GENTLE.apply(context);
        try (ActivityScenario<PopupStormActivity> scenario = ActivityScenario.launch(PopupStormActivity.class)) {
            scenario.onActivity(activity -> {
                com.google.android.material.slider.Slider slider = activity.findViewById(R.id.preset_slider);
                assertEquals(0f, slider.getValueFrom(), 0f);
                assertEquals(3f, slider.getValueTo(), 0f);
                assertEquals(1f, slider.getStepSize(), 0f);
                assertTrue(slider.requestFocus());
            });
            for (int step = 1; step < 4; step++) {
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.preset_slider))
                        .perform(androidx.test.espresso.action.ViewActions.pressKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
                assertEquals(IntensityPresets.values()[step].name(),
                        PopupStormSettings.preferences(context).getString(PopupStormSettings.K_PRESET, ""));
            }
        }
    }
    private Context context;
    private SharedPreferences preferences;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        ControllerPinManager.enterDomMode();
        preferences = PopupStormSettings.preferences(context);
        preferences.edit().clear().commit();
        PopupStormManager.get().stop();
    }

    @After public void tearDown() {
        PopupStormManager.get().stop();
        preferences.edit().clear().commit();
    }

    @Test public void settingsClampUntrustedPreferenceValues() {
        preferences.edit()
                .putFloat(PopupStormSettings.K_SPAWN_RATE, 900f)
                .putInt(PopupStormSettings.K_MAX_SIMULTANEOUS, 900)
                .putInt(PopupStormSettings.K_MIN_SIZE, -5)
                .putString(PopupStormSettings.K_DENIAL_CAPTION_TEXT,
                        "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")
                .putString(PopupStormSettings.K_DETECTION_MODE, "invalid")
                .commit();
        PopupStormSettings settings = PopupStormSettings.load(context);
        assertEquals(8f, settings.getSpawnRate(), 0f);
        assertEquals(15, settings.getMaxSimultaneous());
        assertEquals(40, settings.getMinSize());
        assertEquals(32, settings.getDenialCaptionText().length());
        assertEquals("off", settings.getDetectionMode());
        assertFalse(settings.isEnabled());
    }

    @Test public void recoveredIntensityPresetsWriteExpectedBounds() {
        IntensityPresets.OVERLOAD.apply(context);
        PopupStormSettings overload = PopupStormSettings.load(context);
        assertEquals(7f, overload.getSpawnRate(), 0f);
        assertEquals(.5f, overload.getDisplayDuration(), 0f);
        assertEquals(15, overload.getMaxSimultaneous());
        assertTrue(overload.isBurstEnabled());

        IntensityPresets.GENTLE.apply(context);
        PopupStormSettings gentle = PopupStormSettings.load(context);
        assertEquals(.5f, gentle.getSpawnRate(), 0f);
        assertEquals(3, gentle.getMaxSimultaneous());
        assertFalse(gentle.isBurstEnabled());
    }

    @Test public void generatedSampleAndDenialFiltersDecode() throws Exception {
        Bitmap sample = BitmapFactory.decodeStream(
                context.getAssets().open("popup_storm/guardian_shield.webp"));
        assertNotNull(sample);
        Bitmap pixelated = DenialFilter.pixelate(sample, 75);
        Bitmap blurred = DenialFilter.blur(sample, 75);
        assertEquals(sample.getWidth(), pixelated.getWidth());
        assertEquals(sample.getHeight(), blurred.getHeight());
        pixelated.recycle();
        blurred.recycle();
        sample.recycle();
    }

    @Test public void configurationScreenUsesGeneratedGuardianStyle() {
        try (ActivityScenario<PopupStormActivity> scenario =
                     ActivityScenario.launch(PopupStormActivity.class)) {
            scenario.onActivity(activity -> {
                assertEquals(activity.getString(R.string.popup_title),
                        ((TextView) activity.findViewById(R.id.primary_header_title))
                                .getText().toString());
                assertNotNull(activity.findViewById(R.id.button_preview));
                assertNotNull(activity.findViewById(R.id.button_add_folder));
                assertNotNull(activity.findViewById(R.id.dynamic_settings));
                View configuration = activity.findViewById(R.id.popup_configuration_card);
                View images = activity.findViewById(R.id.popup_images_card);
                View advanced = activity.findViewById(R.id.popup_advanced_card);
                ViewGroup page = (ViewGroup) configuration.getParent();
                assertEquals(page, images.getParent());
                assertEquals(page, advanced.getParent());
                assertTrue(page.indexOfChild(configuration) < page.indexOfChild(images));
                assertTrue(page.indexOfChild(images) < page.indexOfChild(advanced));
                assertInside(configuration, activity.findViewById(R.id.preset_slider));
                assertInside(configuration, activity.findViewById(R.id.button_preview));
                assertInside(images, activity.findViewById(R.id.button_add_folder));
                assertInside(advanced, activity.findViewById(R.id.dynamic_settings));
            });
        }
    }

    @Test public void subModeKeepsSafetyStopAndCannotRequestPreviewOrEnableParticipation() {
        ControllerPinManager.enterSubMode();
        boolean armed = new AppModeManager(context).isArmed();
        try (ActivityScenario<PopupStormActivity> scenario =
                     ActivityScenario.launch(PopupStormActivity.class)) {
            scenario.onActivity(activity -> {
                assertTrue(activity.findViewById(R.id.button_stop).isEnabled());
                assertFalse(activity.findViewById(R.id.button_preview).isEnabled());
                activity.findViewById(R.id.button_preview).performClick();
                assertEquals(0, activity.getResources().getIdentifier("switch_enabled", "id", activity.getPackageName()));
                assertFalse(PopupStormSettings.load(context).isEnabled());
                assertFalse(PopupStormManager.get().isPreviewing());
                assertEquals(armed, new AppModeManager(context).isArmed());
            });
        }
    }

    @Test public void popupActionsWrapAtNarrowWidthsAndLargeFonts() {
        for (int width : new int[] {320, 360, 411, 600}) {
            for (float fontScale : new float[] {1f, 1.3f, 2f}) {
                Configuration configuration = new Configuration(
                        context.getResources().getConfiguration());
                configuration.screenWidthDp = width;
                configuration.fontScale = fontScale;
                Context themed = new ContextThemeWrapper(
                        context.createConfigurationContext(configuration), R.style.Theme_SubHub);
                View page = LayoutInflater.from(themed)
                        .inflate(R.layout.activity_popup_storm, null, false);
                int pixels = Math.round(width * themed.getResources().getDisplayMetrics().density);
                page.measure(View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                page.layout(0, 0, pixels, page.getMeasuredHeight());
                for (int id : new int[] {R.id.button_preview, R.id.button_stop,
                        R.id.button_add_folder}) {
                    TextView action = page.findViewById(id);
                    assertTrue(action.getHeight() >= themed.getResources()
                            .getDimensionPixelSize(R.dimen.control_min_height));
                    assertTextFits(action);
                }
            }
        }
    }

    @Test public void advancedChoicesAndCaptionKeepReadableGeometryAndLabels() {
        ControllerPinManager.enterDomMode();
        try (ActivityScenario<PopupStormActivity> scenario =
                     ActivityScenario.launch(PopupStormActivity.class)) {
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.button_advanced_details))
                    .perform(com.subhub.app.NativeUiActions.revealAboveNavigation(), androidx.test.espresso.action.ViewActions.click());
            scenario.onActivity(activity -> {
                ViewGroup settings = activity.findViewById(R.id.dynamic_settings);
                int groups = 0;
                int fields = 0;
                for (int section = 0; section < settings.getChildCount(); section++) {
                    ViewGroup card = (ViewGroup) settings.getChildAt(section);
                    for (int index = 0; index < card.getChildCount(); index++) {
                        View child = card.getChildAt(index);
                        if (child instanceof GridLayout) {
                            GridLayout group = (GridLayout) child;
                            assertEquals(Math.min(3, group.getChildCount()), group.getColumnCount());
                            for (int option = 0; option < group.getChildCount(); option++) {
                                TextView choice = (TextView) group.getChildAt(option);
                                assertTrue(choice.getHeight() >= activity.getResources()
                                        .getDimensionPixelSize(R.dimen.control_min_height));
                                assertTextFits(choice);
                            }
                            groups++;
                        } else if (child instanceof EditText) {
                            assertTrue(index > 0);
                            assertEquals(child.getId(), card.getChildAt(index - 1).getLabelFor());
                            fields++;
                        }
                    }
                }
                assertEquals(4, groups);
                assertEquals(1, fields);
            });
        }
    }

    private static void assertTextFits(TextView text) {
        assertNotNull(text.getLayout());
        int availableWidth = text.getWidth() - text.getCompoundPaddingLeft()
                - text.getCompoundPaddingRight();
        for (int line = 0; line < text.getLineCount(); line++) {
            assertEquals(0, text.getLayout().getEllipsisCount(line));
            assertTrue(text.getLayout().getLineMax(line) <= availableWidth + 1);
        }
        assertTrue(text.getLayout().getHeight() <= text.getHeight()
                - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
    }

    private static void assertInside(View expected, View child) {
        assertNotNull(child);
        View current = child;
        while (current != expected && current.getParent() instanceof View) {
            current = (View) current.getParent();
        }
        assertEquals(expected, current);
    }
}
