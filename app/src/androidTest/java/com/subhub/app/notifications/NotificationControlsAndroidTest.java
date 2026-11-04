package com.subhub.app.notifications;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static com.subhub.app.NativeUiActions.revealAboveNavigation;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

import android.content.Context;
import android.widget.CompoundButton;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.UiAutomationStateRule;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.KeyholderPresentationAndroidTest;
import com.subhub.app.settings.GlobalSettingsActivity;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import java.util.Map;
import org.junit.*;
import org.junit.rules.RuleChain;
import static com.subhub.app.notifications.NotificationPreferences.*;

public final class NotificationControlsAndroidTest {
    @Rule public final RuleChain rules = RuleChain.outerRule(new KeyholderPresentationAndroidTest.IntegrationFixture())
            .around(new UiAutomationStateRule());
    private Context context;
    private Map<String, ?> original;
    @Before public void prepare() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        original = context.getSharedPreferences(STORE, 0).getAll();
        assertTrue(context.getSharedPreferences(STORE, 0).edit().clear().commit());
        assertTrue(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                .putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.BOTH.name()).commit());
    }
    @After public void restore() { SharedPreferenceTestRestore.restore(context.getSharedPreferences(STORE, 0), original); }
    @Test public void nativeTogglePersistsAndRecreationUsesPreferencesInBothDirections() {
        String tag = "notification:" + key(Role.DOM, Event.ACTIVITY);
        try (ActivityScenario<GlobalSettingsActivity> scenario = ActivityScenario.launch(GlobalSettingsActivity.class)) {
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(android.R.id.content).findViewWithTag("settings:notifications"));
                assertNotNull(activity.findViewById(android.R.id.content).findViewWithTag("notification:" + key(Role.SUB, Event.CONTRACT_CHANGED)));
            });
            onView(withTagValue(is((Object) tag))).perform(revealAboveNavigation(), click());
            assertFalse(enabled(context, Role.DOM, Event.ACTIVITY));
            assertFalse(com.subhub.app.activity.ActivityNotifications.enabled(context));
            assertTrue(enabled(context, Role.SUB, Event.CONTRACT_CHANGED));
            scenario.recreate();
            scenario.onActivity(activity -> assertFalse(((CompoundButton) activity.findViewById(android.R.id.content).findViewWithTag(tag)).isChecked()));
            assertTrue(set(context, Role.DOM, Event.ACTIVITY, true));
            scenario.recreate();
            scenario.onActivity(activity -> assertTrue(((CompoundButton) activity.findViewById(android.R.id.content).findViewWithTag(tag)).isChecked()));
            assertTrue(enabled(context, Role.DOM, Event.ACTIVITY));
            assertTrue(set(context, Role.DOM, Event.ACTIVITY, false));
            scenario.recreate();
            scenario.onActivity(activity -> assertFalse(((CompoundButton) activity.findViewById(android.R.id.content).findViewWithTag(tag)).isChecked()));
            assertFalse(enabled(context, Role.DOM, Event.ACTIVITY));
        }
    }
}
