package com.subhub.app.settings;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static com.subhub.app.NativeUiActions.revealAboveNavigation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import com.subhub.app.R;
import com.subhub.app.security.ControllerPinManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class CensorEditorStructureContractTest {
    @Test public void detectionLabelsHaveSmallerItalicSubtextOnANewLine() {
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(SettingsActivity.class)) {
            scenario.onActivity(activity -> {
                int[] ids = {R.id.radio_preset_low, R.id.radio_preset_medium, R.id.radio_preset_high};
                String[] labels = {"Low", "Medium", "High"};
                for (int i = 0; i < ids.length; i++) {
                    android.text.Spanned text = (android.text.Spanned) ((android.widget.TextView) activity.findViewById(ids[i])).getText();
                    int subtext = labels[i].length() + 1;
                    assertTrue(text.toString().startsWith(labels[i] + "\n"));
                    android.text.style.StyleSpan[] style = text.getSpans(subtext, text.length(), android.text.style.StyleSpan.class);
                    assertEquals(1, style.length);
                    assertEquals(android.graphics.Typeface.ITALIC, style[0].getStyle());
                    android.text.style.RelativeSizeSpan[] size = text.getSpans(subtext, text.length(), android.text.style.RelativeSizeSpan.class);
                    assertEquals(1, size.length);
                    assertTrue(size[0].getSizeChange() < 1f);
                }
                assertEquals("Medium\nMore Coverage",
                        ((android.widget.TextView) activity.findViewById(R.id.radio_preset_medium)).getText().toString());
                assertEquals("Low\nBalanced Coverage",
                        ((android.widget.TextView) activity.findViewById(R.id.radio_preset_low)).getText().toString());
                assertEquals("High\nMaximum Coverage",
                        ((android.widget.TextView) activity.findViewById(R.id.radio_preset_high)).getText().toString());
                float density = activity.getResources().getDisplayMetrics().density;
                for (int id : ids) {
                    android.widget.TextView card = activity.findViewById(id);
                    android.widget.RadioGroup.LayoutParams params =
                            (android.widget.RadioGroup.LayoutParams) card.getLayoutParams();
                    assertTrue(params.leftMargin >= Math.round(4 * density));
                    assertTrue(params.rightMargin >= Math.round(4 * density));
                    assertTrue(card.getPaddingTop() >= Math.round(10 * density));
                    assertTrue(card.getPaddingBottom() >= Math.round(10 * density));
                    assertNotNull(card.getCompoundDrawables()[1]);
                    assertTrue(card.getCompoundDrawablesRelative()[0] == null);
                }
            });
        }
    }
    private android.content.SharedPreferences preferences;
    private java.util.Map<String, ?> originalPreferences;
    @Before public void enterDomMode() {
        android.content.Context context = androidx.test.core.app.ApplicationProvider.getApplicationContext();
        preferences = new SettingsRepository(context).preferences();
        originalPreferences = new java.util.LinkedHashMap<>(preferences.getAll());
        preferences.edit().putBoolean(FeatureModuleManager.KEY_CENSOR_ENABLED, true).commit();
        ControllerPinManager.enterDomMode();
    }

    @After public void leaveDomMode() {
        SharedPreferenceTestRestore.restore(preferences, originalPreferences);
        ControllerPinManager.enterSubMode();
    }

    @Test public void imageManagementIsInsideAppearanceWithoutASeparateToolsCard() {
        preferences.edit().putString(SettingsRepository.KEY_CENSOR_TYPE, "custom")
                .putBoolean(SettingsRepository.KEY_REVERSE_MODE, false).commit();
        try (ActivityScenario<SettingsActivity> scenario =
                     ActivityScenario.launch(SettingsActivity.class)) {
            onView(withId(R.id.button_appearance_details)).perform(revealAboveNavigation(), click());
            scenario.onActivity(activity -> {
                View rules = activity.findViewById(R.id.censor_filter_rules);
                View appearance = activity.findViewById(R.id.censor_appearance);
                View photos = activity.findViewById(R.id.button_export);
                ViewGroup page = (ViewGroup) rules.getParent();
                assertEquals(page, appearance.getParent());
                        org.junit.Assert.assertNull(photos);
                assertTrue(page.indexOfChild(rules) < page.indexOfChild(appearance));
                assertTrue(activity.findViewById(R.id.button_packs) == null);
                for (int id : new int[] {R.id.capture_method_group, R.id.preset_group,
                        R.id.coverage_group, R.id.switch_breasts, R.id.switch_smut_text}) {
                    assertInside(rules, activity.findViewById(id));
                }
                for (int id : new int[] {R.id.style_group, R.id.effect_palette_group,
                        R.id.border_effect_group, R.id.switch_text, R.id.custom_phrases,
                        R.id.censor_images_section, R.id.button_add_censor_images, R.id.censor_images_list}) {
                    assertInside(appearance, activity.findViewById(id));
                }
                int minimum = activity.getResources()
                        .getDimensionPixelSize(R.dimen.control_min_height);
                for (int id : new int[] {R.id.button_add_censor_images}) {
                    View action = activity.findViewById(id);
                    assertTrue(action.isShown());
                    assertTrue(action.getWidth() >= minimum);
                    assertTrue(action.getHeight() >= minimum);
                }
            });
        }
    }

    @Test public void ritualsGalleryActionOpensWorkspaceWithSeparateDeletionChoice() {
        try (ActivityScenario<com.subhub.app.atmosphere.AtmosphereActivity> scenario =
                     ActivityScenario.launch(com.subhub.app.atmosphere.AtmosphereActivity.class)) {
            onView(withId(R.id.rituals_gallery_card)).perform(revealAboveNavigation(), click());
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                Activity export = null;
                for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED)) {
                    if (activity instanceof
                                            com.subhub.app.capture.export.ExportWorkspaceActivity) export = activity;
                }
                assertNotNull("Gallery action must open the photo export screen", export);
                assertNotNull(export.findViewById(R.id.switch_delete_originals));
                export.finish();
            });
        }
    }

    @Test public void subModeCannotUseThePhotoToolOrChangeCensorConfiguration() {
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<SettingsActivity> scenario =
                     ActivityScenario.launch(SettingsActivity.class)) {
            scenario.onActivity(activity -> {
                        org.junit.Assert.assertNull(activity.findViewById(R.id.button_export));
                assertFalse(activity.findViewById(R.id.button_add_censor_images).isEnabled());
                assertFalse(activity.findViewById(R.id.radio_coverage_person).isEnabled());
                assertFalse(activity.findViewById(R.id.switch_smut_text).isEnabled());
            });
        }
    }

    private static void assertInside(View expectedGroup, View child) {
        assertNotNull(child);
        View current = child;
        while (current != expectedGroup && current.getParent() instanceof View) {
            current = (View) current.getParent();
        }
        assertEquals(expectedGroup, current);
    }
}
