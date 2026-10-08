package com.subhub.app.settings;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static com.subhub.app.NativeUiActions.revealAboveNavigation;
import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.subhub.app.R;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.subliminal.SubliminalSettingsActivity;
import com.subhub.app.subliminal.SubliminalSettingsRepository;
import com.subhub.app.util.SelectionGrid;
import com.subhub.app.util.StateToggle;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public final class CensorUxRedesignAndroidTest {
    private Context context;
    private SharedPreferences preferences;
    private Map<String, ?> saved;
    private boolean wasDom;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        preferences = new SettingsRepository(context).preferences();
        saved = new LinkedHashMap<>(preferences.getAll());
        wasDom = ControllerPinManager.isDomModeActive();
        ControllerPinManager.enterDomMode();
        new FeatureModuleManager(context).save(true, true, true);
    }

    @After public void restore() {
        SharedPreferences.Editor edit = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : saved.entrySet()) {
            Object value = entry.getValue();
            String key = entry.getKey();
            if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) edit.putInt(key, (Integer) value);
            else if (value instanceof Long) edit.putLong(key, (Long) value);
            else if (value instanceof Float) edit.putFloat(key, (Float) value);
            else if (value instanceof String) edit.putString(key, (String) value);
            else if (value instanceof Set) {
                @SuppressWarnings("unchecked") Set<String> set = (Set<String>) value;
                edit.putStringSet(key, new LinkedHashSet<>(set));
            }
        }
        assertTrue(edit.commit());
        if (wasDom) ControllerPinManager.enterDomMode(); else ControllerPinManager.enterSubMode();
    }

    @Test public void coreChoicesPrecedeCollapsedAppearanceAndCompatibilityDetails() {
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(SettingsActivity.class)) {
            scenario.onActivity(activity -> {
                View rules = activity.findViewById(R.id.censor_filter_rules);
                View appearance = activity.findViewById(R.id.censor_appearance);
                ViewGroup page = (ViewGroup) rules.getParent();
                assertTrue(page.indexOfChild(rules) < page.indexOfChild(appearance));
                assertEquals(View.GONE, activity.findViewById(R.id.appearance_content).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.capture_options_content).getVisibility());
                ViewGroup filters = (ViewGroup) rules;
                assertEquals(filters.getChildAt(filters.getChildCount() - 1), activity.findViewById(R.id.censor_capture_section));
                assertTrue(activity.findViewById(R.id.switch_buttocks) instanceof android.widget.CheckBox);
                assertEquals(4, ((ViewGroup) activity.findViewById(R.id.intimate_area_grid)).getChildCount());
                assertEquals("Vagina", ((TextView) activity.findViewById(R.id.switch_genitals_female)).getText().toString());
                assertEquals("Dick & Balls", ((TextView) activity.findViewById(R.id.switch_genitals_male)).getText().toString());
                assertEquals("Tits", ((TextView) activity.findViewById(R.id.switch_breasts)).getText().toString());
                assertEquals("Ass", ((TextView) activity.findViewById(R.id.switch_buttocks)).getText().toString());
                assertEquals("Abs / Tummy", ((TextView) activity.findViewById(R.id.switch_belly)).getText().toString());
                assertEquals("Dressed matches", ((TextView) activity.findViewById(R.id.switch_covered)).getText().toString());
                assertFalse(containsText(activity.findViewById(android.R.id.content),
                        activity.getString(R.string.text_smut_description)));
            });
            onView(withId(R.id.button_appearance_details)).perform(revealAboveNavigation(), click());
            scenario.onActivity(activity -> {
                assertTrue(activity.findViewById(R.id.appearance_content).isShown());
                assertEquals(1, countText(activity.findViewById(R.id.appearance_content), activity.getString(R.string.style_box)));
                assertFalse(containsText(activity.findViewById(R.id.appearance_content),
                        activity.getString(R.string.changes_saved_automatically)));
            });
            onView(withId(R.id.button_appearance_details)).perform(revealAboveNavigation(), click());
            scenario.onActivity(activity -> assertEquals(View.GONE,
                    activity.findViewById(R.id.appearance_content).getVisibility()));
        }
    }

    @Test public void categoryTilesKeepIndependentSavedChoicesAndCollapsedSelections() {
        SettingsRepository repository = new SettingsRepository(context);
        repository.saveDetection(25, new LinkedHashSet<>(java.util.Arrays.asList("buttocks", "face")));
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(SettingsActivity.class)) {
            scenario.onActivity(activity -> {
                assertTrue(((CompoundButton) activity.findViewById(R.id.switch_faces)).isChecked());
                assertEquals(View.GONE, activity.findViewById(R.id.other_area_grid).getVisibility());
                assertTrue(((TextView) activity.findViewById(R.id.button_other_areas)).getText().toString().contains("1 selected"));
                assertTrue(((CompoundButton) activity.findViewById(R.id.switch_buttocks)).isChecked());
            });
            onView(withId(R.id.switch_buttocks)).perform(revealAboveNavigation(), click());
            assertFalse(repository.loadDetectorConfig().getEnabledCategories().contains("anus"));
            assertFalse(repository.loadDetectorConfig().getEnabledCategories().contains("buttocks"));
            assertTrue(repository.loadDetectorConfig().getEnabledCategories().contains("face"));
            onView(withId(R.id.switch_buttocks)).perform(revealAboveNavigation(), click());
            assertTrue(repository.loadDetectorConfig().getEnabledCategories().contains("anus"));
            assertTrue(repository.loadDetectorConfig().getEnabledCategories().contains("buttocks"));
            assertTrue(repository.loadDetectorConfig().getEnabledCategories().contains("face"));
            onView(withId(R.id.button_other_areas)).perform(revealAboveNavigation(), click());
            scenario.onActivity(activity -> assertTrue(activity.findViewById(R.id.switch_faces).isShown()));
        }
    }

    @Test public void textMatchingOnlyAppearsWhenEnabledWithoutChangingItsSavedChoices() {
        preferences.edit().putBoolean(SettingsRepository.KEY_TEXT_SMUT_ENABLED, false).commit();
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(SettingsActivity.class)) {
            scenario.onActivity(activity -> assertEquals(View.GONE, activity.findViewById(R.id.text_matching_details).getVisibility()));
            onView(withId(R.id.switch_smut_text)).perform(revealAboveNavigation(), click());
            scenario.onActivity(activity -> assertEquals(View.VISIBLE, activity.findViewById(R.id.text_matching_details).getVisibility()));
            assertTrue(new SettingsRepository(context).loadTextSmutConfig().isEnabled());
            onView(withId(R.id.switch_smut_text)).perform(revealAboveNavigation(), click());
            scenario.onActivity(activity -> assertEquals(View.GONE, activity.findViewById(R.id.text_matching_details).getVisibility()));
            assertFalse(new SettingsRepository(context).loadTextSmutConfig().isEnabled());
        }
    }

    @Test public void legacyAnusOnlyUsesTheCombinedAssTileAndSavesBothClassesOnEnable() {
        SettingsRepository repository = new SettingsRepository(context);
        repository.saveDetection(25, new LinkedHashSet<>(java.util.Arrays.asList("anus", "face")));
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(SettingsActivity.class)) {
            scenario.onActivity(activity -> assertTrue(((CompoundButton) activity.findViewById(R.id.switch_buttocks)).isChecked()));
            onView(withId(R.id.switch_buttocks)).perform(revealAboveNavigation(), click());
            assertEquals(Collections.singleton("face"), repository.loadDetectorConfig().getEnabledCategories());
            onView(withId(R.id.switch_buttocks)).perform(revealAboveNavigation(), click());
            assertEquals(new LinkedHashSet<>(java.util.Arrays.asList("face", "buttocks", "anus")),
                    repository.loadDetectorConfig().getEnabledCategories());
            scenario.recreate();
            scenario.onActivity(activity -> assertTrue(((CompoundButton) activity.findViewById(R.id.switch_buttocks)).isChecked()));
        }
    }

    @Test public void customPhrasesPersistBeforeRecreateAndLockWithoutAnExtraSaveAction() {
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(SettingsActivity.class)) {
            onView(withId(R.id.button_appearance_details)).perform(revealAboveNavigation(), click());
            scenario.onActivity(activity -> ((EditText) activity.findViewById(R.id.custom_phrases)).setText("NICE TRY\nSTILL NO"));
            assertEquals(new LinkedHashSet<>(java.util.Arrays.asList("NICE TRY", "STILL NO")),
                    preferences.getStringSet(SettingsRepository.KEY_CUSTOM_PHRASES, Collections.emptySet()));
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertTrue(((EditText) activity.findViewById(R.id.custom_phrases)).getText().toString().contains("STILL NO"));
                activity.findViewById(R.id.button_edit_lock).performClick();
            });
            assertTrue(preferences.getStringSet(SettingsRepository.KEY_CUSTOM_PHRASES, Collections.emptySet()).contains("NICE TRY"));
            assertFalse(ControllerPinManager.isDomModeActive());
        }
    }

    @Test public void nativeStatusPillsAndIndependentTilesReflowAtLargeFonts() {
        for (int width : new int[] {320, 360, 411}) for (float scale : new float[] {1f, 1.3f, 2f}) {
            Configuration configuration = new Configuration(context.getResources().getConfiguration());
            configuration.screenWidthDp = width;
            configuration.fontScale = scale;
            Context themed = new ContextThemeWrapper(context.createConfigurationContext(configuration), R.style.Theme_SubHub);
            View page = LayoutInflater.from(themed).inflate(R.layout.activity_settings, null, false);
            int pixels = Math.round(width * themed.getResources().getDisplayMetrics().density);
            page.measure(View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            page.layout(0, 0, pixels, page.getMeasuredHeight());
            SelectionGrid tiles = page.findViewById(R.id.intimate_area_grid);
            for (int i = 0; i < tiles.getChildCount(); i++) {
                TextView tile = (TextView) tiles.getChildAt(i);
                assertTrue(tile.getWidth() > 0);
                assertTrue(tile.getRight() <= tiles.getWidth());
                assertTextFits(tile);
            }
            StateToggle toggle = page.findViewById(R.id.switch_smut_text);
            assertTextFits(toggle);
            assertTrue(toggle.getWidth() >= toggle.getSwitchMinWidth());
            AccessibilityNodeInfo node = toggle.createAccessibilityNodeInfo();
            assertTrue(node.isCheckable());
            toggle.setChecked(true);
            assertTrue(toggle.createAccessibilityNodeInfo().isChecked());
            toggle.setChecked(false);
        }
    }

    @Test public void messagePacksComeFirstAndUnusedPreviewDoesNotTakeSpace() {
        SubliminalSettingsRepository repository = new SubliminalSettingsRepository(context);
        repository.savePacks(Collections.singleton(SubliminalSettingsRepository.PACK_OBEDIENCE));
        try (ActivityScenario<SubliminalSettingsActivity> scenario = ActivityScenario.launch(SubliminalSettingsActivity.class)) {
            scenario.onActivity(activity -> {
                View packs = activity.findViewById(R.id.message_packs_card);
                View intensity = activity.findViewById(R.id.intensity_card);
                ViewGroup page = (ViewGroup) packs.getParent();
                assertTrue(page.indexOfChild(packs) < page.indexOfChild(intensity));
                assertEquals(View.GONE, activity.findViewById(R.id.preview_stage).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.custom_phrases_section).getVisibility());
            });
            onView(withId(R.id.pack_custom)).perform(click());
            scenario.onActivity(activity -> assertEquals(View.VISIBLE, activity.findViewById(R.id.custom_phrases_section).getVisibility()));
        }
    }

    private static boolean containsText(View root, String expected) {
        if (root instanceof TextView && expected.equals(((TextView) root).getText().toString())) return true;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                if (containsText(group.getChildAt(index), expected)) return true;
            }
        }
        return false;
    }

    private static int countText(View root, String expected) {
        int count = root instanceof TextView && expected.equals(((TextView) root).getText().toString()) ? 1 : 0;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) count += countText(group.getChildAt(index), expected);
        }
        return count;
    }

    private static void assertTextFits(TextView text) {
        assertNotNull(text.getLayout());
        int available = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
        for (int line = 0; line < text.getLineCount(); line++) {
            assertEquals(0, text.getLayout().getEllipsisCount(line));
            assertTrue(text.getLayout().getLineMax(line) <= available + 1);
        }
        assertTrue(text.getLayout().getHeight() <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
    }
}
