package com.subhub.app.appmode;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withId;

import static com.subhub.app.NativeUiActions.revealAboveNavigation;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.CompoundButton;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.subhub.app.MainActivity;
import com.subhub.app.R;
import com.subhub.app.commitment.CommitmentManager;
import com.subhub.app.popup.PopupStormSettings;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.CaptureMethod;
import com.subhub.app.settings.FeatureModuleManager;
import com.subhub.app.settings.GlobalSettingsActivity;
import com.subhub.app.settings.SettingsRepository;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Map;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public final class MasterParticipationAndroidTest {
    private Context context;
    private SharedPreferences preferences;
    private Map<String, ?> before;
    private Map<String, ?> achievementsBefore;

    @Before public void setup() {
        context = ApplicationProvider.getApplicationContext();
        preferences = new SettingsRepository(context).preferences();
        before = preferences.getAll();
        SharedPreferences achievements = context.getSharedPreferences("betablocker_achievements", Context.MODE_PRIVATE);
        achievementsBefore = achievements.getAll();
        Set<String> unlocked = new java.util.LinkedHashSet<>();
        for (com.subhub.app.stats.AchievementManager.Achievement value :
                new com.subhub.app.stats.AchievementManager(context).all()) unlocked.add(value.getId());
        // This policy fixture starts an already-configured session; milestone popovers are
        // tested separately and must not obscure the genuine Leave Service touch target.
        achievements.edit().putStringSet("unlocked", unlocked).remove("pending_notifications").commit();
        ControllerPinManager.enterDomMode();
        CommitmentManager.emergencyRelease(context);
        preferences.edit().putBoolean(com.subhub.app.security.HardcoreModeManager.KEY_REQUESTED, false).commit();
    }
    @After public void cleanup() {
        restore(preferences, before);
        restore(context.getSharedPreferences("betablocker_achievements", Context.MODE_PRIVATE), achievementsBefore);
        ControllerPinManager.enterSubMode();
    }

    private static void restore(SharedPreferences preferences, Map<String, ?> values) {
        SharedPreferences.Editor edit = preferences.edit().clear();
        for (Map.Entry<String, ?> item : values.entrySet()) {
            String key = item.getKey(); Object value = item.getValue();
            if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) edit.putInt(key, (Integer) value);
            else if (value instanceof Long) edit.putLong(key, (Long) value);
            else if (value instanceof Float) edit.putFloat(key, (Float) value);
            else if (value instanceof String) edit.putString(key, (String) value);
            else if (value instanceof Set<?>) edit.putStringSet(key, new java.util.LinkedHashSet<>((Set<String>) value));
        }
        assertTrue(edit.commit());
    }

    @Test public void featureConfigurationNeverEntersOrLeavesServiceAndZeroFeaturesCanStillLeave() {
        FeatureModuleManager modules = new FeatureModuleManager(context);
        modules.save(true, true, true, false);
        AppModeManager mode = new AppModeManager(context);
        mode.setArmed(true);
        preferences.edit().putBoolean(PopupStormSettings.K_ENABLED, false).commit();
        try (ActivityScenario<GlobalSettingsActivity> scenario = ActivityScenario.launch(GlobalSettingsActivity.class)) {
            scenario.onActivity(activity -> {
                ((CompoundButton) activity.findViewById(R.id.switch_module_censor)).setChecked(false);
                ((CompoundButton) activity.findViewById(R.id.switch_module_limits)).setChecked(false);
                ((CompoundButton) activity.findViewById(R.id.switch_module_wallet)).setChecked(false);
            });
        }
        assertTrue(mode.isArmed());
        assertFalse(modules.hasRuntimeFeature());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                assertTrue(activity.findViewById(R.id.button_protection).isShown());
            });
            onView(withId(R.id.button_protection)).perform(revealAboveNavigation(), click());
        }
        assertFalse(mode.isArmed());
        modules.save(true, false, false, false);
        assertFalse(mode.isArmed());
    }

    @Test public void popupOnlyParticipationUsesTheSharedIncludedScopeWithoutCensor() {
        FeatureModuleManager modules = new FeatureModuleManager(context);
        modules.save(false, false, false, false);
        preferences.edit().putBoolean(PopupStormSettings.K_ENABLED, true)
                .putString(SettingsRepository.KEY_CAPTURE_METHOD, CaptureMethod.SCREEN_RECORDING.preferenceValue()).commit();
        AppModeManager mode = new AppModeManager(context);
        mode.save(true, Set.of("synthetic.app"));
        assertTrue(modules.hasRuntimeFeature());
        assertFalse(mode.shouldRecognize("synthetic.app"));
        assertTrue(mode.shouldShowPopups("synthetic.app"));
        assertFalse(mode.shouldShowPopups("excluded.app"));
        assertFalse(mode.shouldShowPopups(context.getPackageName()));
        mode.setArmed(false);
        assertFalse(mode.shouldShowPopups("synthetic.app"));
        preferences.edit().putBoolean(PopupStormSettings.K_ENABLED, false).commit();
        assertFalse(modules.hasRuntimeFeature());
    }
}
