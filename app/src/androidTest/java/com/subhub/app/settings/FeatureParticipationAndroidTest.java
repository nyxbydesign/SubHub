package com.subhub.app.settings;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.detection.DetectionPreset;
import com.subhub.app.appmode.AppModeActivity;
import com.subhub.app.appmode.AppTimerManager;
import com.subhub.app.penance.*;
import com.subhub.app.security.ControllerPinManager;
import java.util.Map;
import org.junit.*;

/** Feature pages remain available; their actual controls determine runtime participation. */
public class FeatureParticipationAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private SharedPreferences preferences, walletPreferences;
    private Map<String, ?> original, originalWallet;

    @Before public void fixture() {
        preferences = context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0);
        walletPreferences = context.getSharedPreferences(PenanceManager.PREFS_NAME, 0);
        original = preferences.getAll(); originalWallet = walletPreferences.getAll();
        ControllerPinManager.setPin(context, "2468");
        ControllerPinManager.enterDomMode();
        new FeatureModuleManager(context).save(true, true, true);
        new PenanceManager(context).configure(true, Map.of(PenanceInfraction.NEW_DETECTION, 125), 1000, 5000, 10, 15);
        new PaidPauseManager(context).configure(false, 500, 15);
    }

    @After public void restore() {
        SharedPreferenceTestRestore.restore(preferences, original);
        SharedPreferenceTestRestore.restore(walletPreferences, originalWallet);
        ControllerPinManager.enterSubMode();
    }

    @Test public void offDetectionCardReopensAndRestoresTheSavedPreset() {
        DetectionPreset saved = new SettingsRepository(context).loadDetectionPreset();
        try (ActivityScenario<SettingsActivity> page = ActivityScenario.launch(SettingsActivity.class)) {
            page.onActivity(a -> {
                a.findViewById(R.id.radio_preset_off).performClick();
                assertFalse(new FeatureModuleManager(context).isCensorEnabled());
                assertEquals(saved, new SettingsRepository(context).loadDetectionPreset());
                assertTabs(a);
            });
            page.recreate();
            page.onActivity(a -> {
                assertTrue(((CompoundButton) a.findViewById(R.id.radio_preset_off)).isChecked());
                a.findViewById(R.id.radio_preset_high).performClick();
                assertTrue(new FeatureModuleManager(context).isCensorEnabled());
                assertEquals(DetectionPreset.HIGH, new SettingsRepository(context).loadDetectionPreset());
                ControllerPinManager.enterSubMode();
                a.findViewById(R.id.radio_preset_off).performClick();
                assertTrue(new FeatureModuleManager(context).isCensorEnabled());
            });
        }
    }

    @Test public void limitsUseOnlyTheirTwoBudgetControls() {
        new com.subhub.app.appmode.AppModeManager(context).saveIncludedPackages(java.util.Set.of("com.android.settings"));
        new AppTimerManager(context).saveSettings(false, 30, false, 120);
        androidx.test.espresso.intent.Intents.init();
        androidx.test.espresso.intent.Intents.intending(
                androidx.test.espresso.intent.matcher.IntentMatchers.hasAction(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                .respondWith(new android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null));
        try (ActivityScenario<AppModeActivity> page = ActivityScenario.launch(AppModeActivity.class)) {
            page.onActivity(a -> {
                assertTabs(a);
                ((CompoundButton) a.findViewById(R.id.total_limit_enabled)).setChecked(true);
            });
            android.os.SystemClock.sleep(650);
            assertTrue(new AppTimerManager(context).loadSettings().totalEnabled);
            page.recreate();
            page.onActivity(a -> {
                assertTrue(((CompoundButton) a.findViewById(R.id.total_limit_enabled)).isChecked());
                ((CompoundButton) a.findViewById(R.id.total_limit_enabled)).setChecked(false);
                assertFalse(new AppTimerManager(context).loadSettings().anyEnabled());
                assertFalse(a.isFinishing());
                assertTabs(a);
            });
        } finally { androidx.test.espresso.intent.Intents.release(); }
    }

    @Test public void ruleOffPersistsDespiteInvalidDraftAndCannotBeChangedFromAStaleDomView() {
        try (ActivityScenario<PenanceActivity> page = ActivityScenario.launch(PenanceActivity.class)) {
            page.onActivity(a -> a.findViewById(android.R.id.content).findViewWithTag("wallet:rules").performClick());
            page.onActivity(a -> {
                ((EditText) a.findViewById(R.id.daily_cap)).setText("");
                ((CompoundButton) a.findViewById(R.id.rule_detection_enabled)).setChecked(false);
                PenanceManager rules = new PenanceManager(context);
                assertFalse(rules.isEnabled());
                assertEquals(125, rules.getInfractionCents(PenanceInfraction.NEW_DETECTION));
                assertTabs(a);
            });
            page.recreate();
            page.onActivity(a -> {
                CompoundButton rule = a.findViewById(R.id.rule_detection_enabled);
                assertFalse(rule.isChecked());
                ControllerPinManager.enterSubMode();
                rule.setChecked(true);
                assertFalse(new PenanceManager(context).isEnabled());
                assertFalse(rule.isChecked());
            });
        }
    }

    @Test public void retiredDisabledFlagsMigrateOnceWithoutDiscardingAmounts() {
        preferences.edit().putBoolean(FeatureModuleManager.KEY_LIMITS_ENABLED, false)
                .putBoolean(AppTimerManager.KEY_PER_APP_ENABLED, true)
                .putBoolean(AppTimerManager.KEY_TOTAL_ENABLED, true)
                .putInt(AppTimerManager.KEY_TOTAL_MINUTES, 47)
                .putBoolean(FeatureModuleManager.KEY_WALLET_ENABLED, false).commit();
        walletPreferences.edit().remove(PenanceManager.KEY_RULE_PARTICIPATION_MIGRATED)
                .putBoolean("enabled", true).putBoolean(PaidPauseManager.KEY_ENABLED, true).commit();
        FeatureModuleManager modules = new FeatureModuleManager(context);
        PenanceManager rules = new PenanceManager(context);
        assertTrue(modules.isLimitsEnabled()); assertTrue(modules.isWalletEnabled());
        assertFalse(new AppTimerManager(context).loadSettings().anyEnabled());
        assertEquals(47, new AppTimerManager(context).loadSettings().totalMinutes);
        assertFalse(rules.isEnabled());
        assertEquals(125, rules.getInfractionCents(PenanceInfraction.NEW_DETECTION));
        rules.configure(true, Map.of(PenanceInfraction.NEW_DETECTION, 125), 1000, 5000, 10, 15);
        assertTrue(new PenanceManager(context).isEnabled());
    }

    private static void assertTabs(android.app.Activity a) {
        for (int id : new int[] {R.id.nav_censor, R.id.nav_limits, R.id.nav_money})
            assertEquals(View.VISIBLE, a.findViewById(id).getVisibility());
    }
}
