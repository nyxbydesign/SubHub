package com.subhub.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.subhub.app.detection.text.TextSmutConfig;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.FeatureModuleManager;
import com.subhub.app.settings.GlobalSettingsActivity;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class HomeSettingsStructureContractTest {
    @Test public void helpAndDiagnosticsAreAbsentFromHome() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                assertNull(activity.findViewById(R.id.button_help));
                assertNull(activity.findViewById(R.id.button_diagnostics));
            });
        }
    }

    @Test public void homeShowsConfiguredCountsWithoutSecondaryCaptions() {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences preferences = new SettingsRepository(context).preferences();
        Map<String, ?> original = preferences.getAll();
        String[] changedKeys = {FeatureModuleManager.KEY_CENSOR_ENABLED,
                SettingsRepository.KEY_ENABLED_CATEGORIES,
                SettingsRepository.KEY_TEXT_SMUT_ENABLED,
                SettingsRepository.KEY_TEXT_SMUT_CATEGORIES};
        preferences.edit()
                .putBoolean(FeatureModuleManager.KEY_CENSOR_ENABLED, true)
                .putStringSet(SettingsRepository.KEY_ENABLED_CATEGORIES,
                        new LinkedHashSet<>(Arrays.asList("breasts", "buttocks")))
                .putBoolean(SettingsRepository.KEY_TEXT_SMUT_ENABLED, true)
                .putStringSet(SettingsRepository.KEY_TEXT_SMUT_CATEGORIES,
                        new LinkedHashSet<>(Arrays.asList(TextSmutConfig.CATEGORY_EXPLICIT)))
                .commit();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                assertEquals("3 Censors active",
                        ((TextView) activity.findViewById(R.id.sub_censor_voice))
                                .getText().toString());
                for (int id : new int[] {R.id.primary_header_subtitle,
                        R.id.sub_censor_summary, R.id.sub_limits_detail,
                        R.id.sub_wallet_summary, R.id.sub_atmosphere_summary}) {
                    assertEquals(View.GONE, activity.findViewById(id).getVisibility());
                }
                assertTrue(activity.findViewById(R.id.sub_censor_card).isClickable());
            });
        } finally {
            SharedPreferences.Editor restore = preferences.edit();
            for (String key : changedKeys) {
                Object value = original.get(key);
                if (value instanceof Boolean) restore.putBoolean(key, (Boolean) value);
                else if (value instanceof Set) {
                    @SuppressWarnings("unchecked") Set<String> values = (Set<String>) value;
                    restore.putStringSet(key, values);
                } else restore.remove(key);
            }
            restore.commit();
        }
    }

    @Test public void filterArrangementUsesCanonicalDomLabels() {
        Context context = ApplicationProvider.getApplicationContext();
        SettingsRepository repository = new SettingsRepository(context);
        repository.preferences().edit()
                .putString(SettingsRepository.KEY_CENSOR_TYPE, "box")
                .putBoolean(SettingsRepository.KEY_SHOW_BORDER, true)
                .putString(SettingsRepository.KEY_BORDER_EFFECT, "classic")
                .putString(SettingsRepository.KEY_DETECTION_PRESET, "high")
                .putStringSet(SettingsRepository.KEY_ENABLED_CATEGORIES,
                        new LinkedHashSet<>(Arrays.asList(
                                "genitals_female", "genitals_male", "breasts", "buttocks",
                                "anus", "male_chest", "belly")))
                .putBoolean(SettingsRepository.KEY_SHOW_TEXT, true)
                .putStringSet(SettingsRepository.KEY_ENABLED_PHRASE_CATEGORIES,
                        new LinkedHashSet<>(Arrays.asList(
                                "short", "denial", "humiliation", "findom")))
                .putBoolean(SettingsRepository.KEY_TEXT_SMUT_ENABLED, true)
                .putInt(SettingsRepository.KEY_TEXT_SMUT_SENSITIVITY,
                        TextSmutConfig.SENSITIVITY_BALANCED)
                .putStringSet(SettingsRepository.KEY_TEXT_SMUT_CATEGORIES,
                        new LinkedHashSet<>(Arrays.asList(
                                TextSmutConfig.CATEGORY_EXPLICIT,
                                TextSmutConfig.CATEGORY_FETISH,
                                TextSmutConfig.CATEGORY_SOLICITATION)))
                .commit();

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                String details = activity.censorArrangementDetails();
                assertTrue(details.contains("Blackout · Classic Border"));
                assertTrue(details.contains("High · Maximum Coverage"));
                assertTrue(details.contains(
                        "Vagina, Dick & Balls, Tits, Ass, Male Chest, Abs / Tummy"));
                assertTrue(details.contains(
                        "Context · Sexual Words, Kink / Fetish Talk, Sexual Invitations"));
                assertTrue(details.contains("Beta / Cuck, Denial, Findom, Plain"));
                assertFalse(details.contains("Buttocks"));
                assertFalse(details.contains("Anus"));
                assertFalse(details.contains(", Anal,"));
                assertFalse(details.contains("BLACKOUT"));
                assertFalse(details.contains("ULTRA · MAXIMUM COVERAGE"));
                assertFalse(details.contains("Balanced"));
                assertFalse(details.contains("Explicit language"));
                assertFalse(details.contains("Humiliation"));
                assertFalse(details.contains("Short"));
            });
        }
    }

    @Test public void toolsPrecedePaymentsAndHelpDiagnosticsLiveInSettings() {
        try (ActivityScenario<GlobalSettingsActivity> scenario =
                     ActivityScenario.launch(GlobalSettingsActivity.class)) {
            scenario.onActivity(activity -> {
                ViewGroup sections = activity.findViewById(R.id.settings_sections);
                View paypal = activity.findViewById(R.id.paypal_card);
                View appSettings = activity.findViewById(R.id.app_settings_card);
                assertNotNull(paypal);
                assertEquals(sections.getChildCount() - 1, sections.indexOfChild(paypal));
                assertEquals(sections.getChildCount() - 2, sections.indexOfChild(appSettings));
                assertNotNull(activity.findViewById(R.id.button_help));
                assertNotNull(activity.findViewById(R.id.button_diagnostics));
            });
        }
    }
}
