package com.subhub.app.onboarding;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import com.subhub.app.MainActivity;
import com.subhub.app.R;
import com.subhub.app.UiRefreshWitness;
import com.subhub.app.UiAutomationStateRule;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.security.*;
import com.subhub.app.settings.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;

public class OnboardingAndroidTest {
    @Rule public final KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new KeyholderPresentationAndroidTest.IntegrationFixture();
    @Rule public final UiAutomationStateRule automation = new UiAutomationStateRule();
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private Map<String, ?> penance;

    @Before public void newFixture() {
        assertFalse(com.subhub.app.people.LocalSessionControls.isActive(context));
        assertFalse(new AppModeManager(context).isArmed());
        penance = new LinkedHashMap<>(context.getSharedPreferences(com.subhub.app.penance.PenanceManager.PREFS_NAME, 0).getAll());
        context.getSharedPreferences("subhub_onboarding", 0).edit().clear().commit();
        context.getSharedPreferences("subhub_controller_auth", 0).edit().clear().commit();
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                .remove("controller_pin_salt").remove("controller_pin_hash")
                .remove("controller_keyholder_optional").remove("has_seen_onboarding")
                .putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.BOTH.name()).commit();
        ControllerPinManager.enterSubMode();
    }
    @After public void restore() {
        SharedPreferenceTestRestore.restore(context.getSharedPreferences(com.subhub.app.penance.PenanceManager.PREFS_NAME, 0), penance);
    }

    @Test public void firstLaunchStartsTourAndSkipIsPersistentWithoutForcingAKey() {
        assertTrue(OnboardingState.shouldStart(context));
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        context.startActivity(new Intent(context, MainActivity.class).setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        assertTrue(device.wait(Until.hasObject(By.res("com.subhub.app", "onboarding_role_choice")), 8000));
        onView(withId(R.id.tour_next)).check(matches(org.hamcrest.Matchers.not(isEnabled())));
        onView(withTagValue(org.hamcrest.Matchers.is("device_role:SUB_ONLY"))).perform(scrollTo(), click());
        onView(withId(R.id.tour_next)).perform(click());
        onView(withId(R.id.tour_skip)).perform(click());
        assertTrue(device.wait(Until.hasObject(By.res("com.subhub.app", "nav_home")), 8000));
        assertTrue(OnboardingState.completed(context)); assertFalse(OnboardingState.shouldStart(context));
        assertFalse(ControllerPinManager.hasCredentials(context)); assertTrue(ControllerPinManager.allowsUnkeyedAccess(context));
        assertFalse(new AppModeManager(context).isArmed());
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void selectedAppsAndSavedStyleSurviveRecreationAndFinishWithoutStartingService() throws Exception {
        new FeatureModuleManager(context).save(true, true, true, false);
        new SettingsRepository(context).saveAppearance(CensorAppearance.Type.BLUR, 71, false, false);
        try (ActivityScenario<OnboardingActivity> tour = ActivityScenario.launch(OnboardingActivity.class)) {
            OnboardingTestSupport.goTo(tour, "sub_apps", DeviceRolePreferences.Mode.SUB_ONLY);
            tour.recreate(); assertEquals("sub_apps", OnboardingTestSupport.pageId(tour));
            capture(tour, "tour-apps");
            OnboardingTestSupport.goTo(tour, "device_permissions", DeviceRolePreferences.Mode.SUB_ONLY);
            onView(withId(R.id.tour_next)).check(matches(withText(R.string.tour_finish)));
            onView(withId(R.id.tour_next)).perform(click());
        }
        assertTrue(new FeatureModuleManager(context).isWalletEnabled());
        assertEquals(CensorAppearance.Type.BLUR, new SettingsRepository(context).loadAppearance().getType());
        assertFalse(new AppModeManager(context).isArmed()); assertFalse(ControllerPinManager.hasCredentials(context));
    }

    @Test public void replayDoesNotResetSavedChoices() {
        new FeatureModuleManager(context).save(false, true, false, true);
        ControllerPinManager.useWithoutKeyholder(context); OnboardingState.complete(context);
        new SettingsRepository(context).saveAppearance(CensorAppearance.Type.GLITCH, 71, false, false);
        Map<String, ?> onboarding = new LinkedHashMap<>(context.getSharedPreferences("subhub_onboarding", 0).getAll());
        DeviceRolePreferences.Mode role = DeviceRolePreferences.get(context);
        try (ActivityScenario<OnboardingActivity> tour = ActivityScenario.launch(
                new Intent(context, OnboardingActivity.class).putExtra(OnboardingActivity.REPLAY, true))) {
            OnboardingTestSupport.goTo(tour, "dom_censor", DeviceRolePreferences.Mode.DOM_ONLY);
            onView(withId(R.id.tour_skip)).perform(click());
        }
        FeatureModuleManager modules = new FeatureModuleManager(context);
        assertFalse(modules.isCensorEnabled()); assertTrue(modules.isLimitsEnabled());
        assertTrue(modules.isWalletEnabled()); assertTrue(modules.isSubliminalEnabled());
        assertEquals(CensorAppearance.Type.GLITCH, new SettingsRepository(context).loadAppearance().getType());
        assertEquals(role, DeviceRolePreferences.get(context));
        assertEquals(onboarding, context.getSharedPreferences("subhub_onboarding", 0).getAll());
    }

    @Test public void incompleteSetupResumesAfterCredentialsAreConfigured() {
        new FeatureModuleManager(context).save(true, true, true, false);
        new SettingsRepository(context).saveAppearance(CensorAppearance.Type.BLUR, 71, false, false);
        try (ActivityScenario<OnboardingActivity> tour = ActivityScenario.launch(OnboardingActivity.class)) {
            OnboardingTestSupport.goTo(tour, "sub_apps", DeviceRolePreferences.Mode.SUB_ONLY);
        }
        assertTrue(ControllerPinManager.setPin(context, "2468")); assertTrue(OnboardingState.shouldStart(context));
        try (ActivityScenario<OnboardingActivity> resumed = ActivityScenario.launch(OnboardingActivity.class)) {
            assertEquals("sub_apps", OnboardingTestSupport.pageId(resumed));
            List<OnboardingPage> flow = OnboardingFlow.pages(DeviceRolePreferences.Mode.SUB_ONLY);
            onView(withId(R.id.tour_progress)).check(matches(withText(context.getString(R.string.tour_progress,
                    OnboardingFlow.index(flow, "sub_apps"), flow.size()))));
            onView(withId(R.id.tour_skip)).perform(click());
        }
        assertTrue(new FeatureModuleManager(context).isWalletEnabled());
        assertEquals(CensorAppearance.Type.BLUR, new SettingsRepository(context).loadAppearance().getType());
        assertFalse(OnboardingState.inProgress(context));
        for (String key : context.getSharedPreferences("subhub_onboarding", 0).getAll().keySet())
            assertFalse("Completed setup clears its draft", key.startsWith("draft_"));
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void rolePagesKeepTheFooterOutsideTheScrollAndNeverEnableProtection() throws Exception {
        try (ActivityScenario<OnboardingActivity> tour = ActivityScenario.launch(OnboardingActivity.class)) {
            OnboardingTestSupport.choose(tour, DeviceRolePreferences.Mode.SUB_ONLY);
            List<OnboardingPage> flow = OnboardingFlow.pages(DeviceRolePreferences.Mode.SUB_ONLY);
            for (int i = 0; i < flow.size(); i++) {
                OnboardingPage spec = flow.get(i); assertEquals(spec.id, OnboardingTestSupport.pageId(tour));
                final int index = i;
                tour.onActivity(activity -> {
                    Rect footer = new Rect(), scroll = new Rect();
                    assertTrue(activity.findViewById(R.id.tour_next).getGlobalVisibleRect(footer));
                    assertTrue(activity.findViewById(R.id.tour_scroll).getGlobalVisibleRect(scroll));
                    assertTrue("Footer must not overlap content", footer.top >= scroll.bottom);
                    assertEquals(activity.getString(R.string.tour_progress, index + 1, flow.size()),
                            ((TextView) activity.findViewById(R.id.tour_progress)).getText().toString());
                });
                capture(tour, "sub-page-" + spec.id);
                assertFalse(ControllerPinManager.isDomModeActive());
                assertFalse(new HardcoreModeManager(context).isRequested());
                if (i + 1 < flow.size()) onView(withId(R.id.tour_next)).perform(click());
            }
            onView(withId(R.id.tour_skip)).perform(click());
        }
    }

    private void capture(ActivityScenario<OnboardingActivity> page, String name) throws Exception {
        AtomicReference<OnboardingActivity> current = new AtomicReference<>();
        page.onActivity(current::set); UiRefreshWitness.capture(current.get(), name);
    }
}
