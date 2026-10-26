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
    @org.junit.Rule public final com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture();
    @org.junit.Rule public final UiAutomationStateRule automation = new UiAutomationStateRule();

    private static android.content.Intent homeIntent() {
        return new android.content.Intent(ApplicationProvider.getApplicationContext(), MainActivity.class)
                .setAction(android.content.Intent.ACTION_MAIN);
    }

    @Test public void helpAndDiagnosticsAreAbsentFromHome() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(homeIntent())) {
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
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(homeIntent())) {
            scenario.onActivity(activity -> {
                com.subhub.app.people.PersonSnapshot person = new com.subhub.app.people.LocalPersonRepository(activity)
                        .load(com.subhub.app.people.PersonId.LOCAL);
                assertEquals("3 active filters", person.sections.stream().filter(value -> "censor".equals(value.key)).findFirst().get().summary);
                assertFalse(activity.findViewById(R.id.sub_dashboard).isShown());
                assertEquals(View.GONE, activity.findViewById(R.id.primary_header_subtitle).getVisibility());
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
        Map<String, ?> original = repository.preferences().getAll();
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

        try {
            String details = new com.subhub.app.people.LocalContractDetails(context).forSection("censor");
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
        } finally {
            com.subhub.app.settings.SharedPreferenceTestRestore.restore(repository.preferences(), original);
        }
    }

    @Test public void helpAndDiagnosticsStayInSettingsWhilePaymentsBelongToWallet() {
        boolean originallyDom = com.subhub.app.security.ControllerPinManager.isDomModeActive();
        com.subhub.app.security.ControllerPinManager.enterDomMode();
        try {
            try (ActivityScenario<GlobalSettingsActivity> scenario =
                         ActivityScenario.launch(GlobalSettingsActivity.class)) {
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withTagValue(
                        org.hamcrest.Matchers.is("settings:help")))
                        .perform(com.subhub.app.NativeUiActions.revealAboveNavigation(),
                                androidx.test.espresso.action.ViewActions.click());
                scenario.onActivity(activity -> {
                    ViewGroup sections = activity.findViewById(R.id.settings_sections);
                    View helpHeader = sections.findViewWithTag("settings:help");
                    assertNotNull(helpHeader);
                    ViewGroup help = (ViewGroup) helpHeader.getParent();
                    assertTrue(help.findViewById(R.id.button_help).isShown());
                    assertTrue(help.findViewById(R.id.button_diagnostics).isShown());
                    assertNull(activity.findViewById(R.id.paypal_card));
                    assertNull(activity.findViewById(R.id.apps_card));
                    assertNull(sections.findViewWithTag("settings:apps"));
                });
            }
            Context context = ApplicationProvider.getApplicationContext();
            try (ActivityScenario<com.subhub.app.penance.PenanceActivity> wallet = ActivityScenario.launch(
                    new android.content.Intent(context, com.subhub.app.penance.PenanceActivity.class)
                            .putExtra(com.subhub.app.people.PersonRoutes.EXTRA_PERSON_ID,
                                    com.subhub.app.people.PersonId.LOCAL.value()))) {
                wallet.onActivity(activity -> {
                    View payment = activity.findViewById(android.R.id.content).findViewWithTag("wallet:paypal");
                    assertNotNull(payment);
                    assertTrue(payment.isClickable());
                    assertFalse(activity.findViewById(R.id.paypal_card).isShown());
                    assertNull(activity.findViewById(R.id.button_help));
                    assertNull(activity.findViewById(R.id.button_diagnostics));
                });
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withTagValue(
                        org.hamcrest.Matchers.is("wallet:paypal")))
                        .perform(androidx.test.espresso.action.ViewActions.scrollTo(),
                                androidx.test.espresso.action.ViewActions.click());
                wallet.onActivity(activity -> assertTrue(activity.findViewById(R.id.paypal_card).isShown()));
                wallet.recreate();
                wallet.onActivity(activity -> assertTrue(activity.findViewById(R.id.paypal_card).isShown()));
            }
        } finally {
            if (originallyDom) com.subhub.app.security.ControllerPinManager.enterDomMode();
            else com.subhub.app.security.ControllerPinManager.enterSubMode();
        }
    }
}
