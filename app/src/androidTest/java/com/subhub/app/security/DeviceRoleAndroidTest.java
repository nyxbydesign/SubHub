package com.subhub.app.security;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.UiAutomationStateRule;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.onboarding.OnboardingActivity;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.people.ConnectionUi;
import com.subhub.app.people.People;
import com.subhub.app.people.PersonId;
import com.subhub.app.settings.CensorAppearance;
import com.subhub.app.settings.GlobalSettingsActivity;
import com.subhub.app.settings.SettingsRepository;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class DeviceRoleAndroidTest {
    private final KeyholderPresentationAndroidTest.IntegrationFixture integration = new KeyholderPresentationAndroidTest.IntegrationFixture();
    private final org.junit.rules.ExternalResource notifications = new org.junit.rules.ExternalResource() {
        private boolean channelExisted;
        @Override protected void before() {
            android.app.NotificationManager manager = context.getSystemService(android.app.NotificationManager.class);
            channelExisted = manager != null && manager.getNotificationChannel("subhub_hardcore_readiness") != null;
        }
        @Override protected void after() {
            // This runs after the saved protection preferences have been restored by integration.
            HardcoreReadinessNotificationManager.refresh(context);
            android.app.NotificationManager manager = context.getSystemService(android.app.NotificationManager.class);
            if (manager != null && !channelExisted) manager.deleteNotificationChannel("subhub_hardcore_readiness");
        }
    };
    @Rule public final RuleChain rules = RuleChain.outerRule(notifications).around(integration).around(new UiAutomationStateRule());
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Before public void localController() {
        preferences().edit().remove(DeviceRolePreferences.KEY_MODE).putBoolean(AppModeManager.KEY_ARMED, false)
                .remove("commitment_ends_at").putBoolean(HardcoreModeManager.KEY_REQUESTED, false).commit();
        OnboardingState.complete(context);
        assertTrue(ControllerPinManager.setPin(context, "5729"));
    }
    private android.content.SharedPreferences preferences() { return new SettingsRepository(context).preferences(); }

    @Test public void existingDevicesDefaultToBothAndDomWorkspaceDoesNotUnlockCredentials() {
        assertEquals(DeviceRolePreferences.Mode.BOTH, DeviceRolePreferences.get(context));
        assertTrue(DeviceRolePreferences.supportsDom(context)); assertTrue(DeviceRolePreferences.supportsSub(context));
        ControllerPinManager.enterSubMode();
        assertFalse(DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.DOM_ONLY));
        assertTrue(ControllerPinManager.verify(context, "5729"));
        assertTrue(DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.DOM_ONLY));
        ControllerPinManager.lockNow();
        assertTrue(DeviceRolePreferences.isDomWorkspace(context));
        assertFalse(ControllerPinManager.isDomModeActive());
        assertTrue(DeviceRolePreferences.supportsDom(context)); assertFalse(DeviceRolePreferences.supportsSub(context));
        assertFalse(DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.SUB_ONLY));
        assertTrue(ControllerPinManager.verify(context, "5729"));
        assertTrue(DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.SUB_ONLY));
        assertFalse(DeviceRolePreferences.supportsDom(context)); assertTrue(DeviceRolePreferences.supportsSub(context));
        ControllerPinManager.enterSubMode();
        assertFalse(DeviceRolePreferences.isDomWorkspace(context));
    }

    @Test public void assignedOrUnavailableKeyholdersCannotBeEscapedThroughDeviceRoles() {
        for (KeyholderProvider.State state : new KeyholderProvider.State[] {
                KeyholderProvider.State.READY, KeyholderProvider.State.PENDING, KeyholderProvider.State.UNAVAILABLE}) {
            People.installKeyholder(KeyholderPresentationAndroidTest.provider(state, "Synthetic Dom"));
            assertFalse(DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.DOM_ONLY));
            assertFalse(DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.SUB_ONLY));
            assertEquals(DeviceRolePreferences.Mode.BOTH, DeviceRolePreferences.get(context));
        }
    }

    @Test public void localSessionAndRetainedHardcoreProtectionBlockDomOnly() {
        preferences().edit().putBoolean(AppModeManager.KEY_ARMED, true).commit();
        assertFalse(DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.DOM_ONLY));
        assertTrue(new AppModeManager(context).isArmed());
        preferences().edit().putBoolean(AppModeManager.KEY_ARMED, false).putBoolean(HardcoreModeManager.KEY_REQUESTED, true).commit();
        assertFalse(DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.DOM_ONLY));
        assertTrue(preferences().getBoolean(HardcoreModeManager.KEY_REQUESTED, false));
        assertEquals(DeviceRolePreferences.Mode.BOTH, DeviceRolePreferences.get(context));
    }

    @Test public void inlineRoleSelectionRequiresFreshPinEvenForAnUnlockedController() {
        try (ActivityScenario<GlobalSettingsActivity> page = ActivityScenario.launch(GlobalSettingsActivity.class)) {
            onView(withTagValue(is("device_role:DOM_ONLY"))).perform(scrollTo(), click());
            onView(withHint(R.string.controller_pin_label)).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText(android.R.string.cancel)).inRoot(isDialog()).perform(click());
            assertEquals(DeviceRolePreferences.Mode.BOTH, DeviceRolePreferences.get(context));
            assertTrue(ControllerPinManager.isDomModeActive());
            onView(withTagValue(is("device_role:DOM_ONLY"))).perform(scrollTo(), click());
            unlockPin();
            assertEquals(DeviceRolePreferences.Mode.DOM_ONLY, DeviceRolePreferences.get(context));
            page.onActivity(a -> {
                assertNull(a.findViewById(R.id.switch_hardcore_mode));
                assertNull(a.findViewById(R.id.hardcore_card));
                assertFalse(a.findViewById(R.id.android_access_card).isShown());
                assertTrue(a.findViewById(R.id.privacy_discreet_toggle).isShown());
                assertNotNull(a.findViewById(android.R.id.content).findViewWithTag("settings:help"));
            });
        }
    }

    @Test public void domOnlyKeyholderOffersLocalPinAndAuthenticatedAddSub() {
        assertTrue(DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.DOM_ONLY));
        AtomicInteger pairs = new AtomicInteger();
        People.installConnectionUi(new ConnectionUi() {
            public void openPairing(Activity activity) { fail("The explicit Dom route is required"); }
            public void openPairing(Activity activity, boolean asDom) { assertTrue(asDom); pairs.incrementAndGet(); }
            public void openConnection(Activity activity, PersonId id) { fail("Dom setup does not select an assigned Dom"); }
        });
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<AuthenticatorActivity> page = ActivityScenario.launch(AuthenticatorActivity.class)) {
            page.onActivity(a -> {
                assertNotNull(a.findViewById(R.id.keyholder_pin_change_button));
                assertNotNull(a.findViewById(R.id.keyholder_add_sub));
                assertNull(a.findViewById(R.id.keyholder_pair_device));
                assertNull(a.findViewById(R.id.keyholder_setup_remote));
            });
            onView(withId(R.id.keyholder_add_sub)).perform(scrollTo(), click());
            assertEquals(0, pairs.get());
            unlockPin();
            assertEquals(1, pairs.get());
            page.recreate();
            page.onActivity(a -> assertNotNull(a.findViewById(R.id.keyholder_add_sub)));
        }
    }

    @Test public void pristineOnboardingSelectsDomOnlyAndSkipsLocalFeatureSetup() {
        pristine();
        try (ActivityScenario<OnboardingActivity> page = ActivityScenario.launch(OnboardingActivity.class)) {
            onView(withTagValue(is("device_role:DOM_ONLY"))).perform(scrollTo(), click());
            assertEquals(DeviceRolePreferences.Mode.DOM_ONLY, DeviceRolePreferences.get(context));
            assertFalse(ControllerPinManager.isDomModeActive());
            onView(withId(R.id.tour_next)).perform(click());
            page.onActivity(a -> {
                assertEquals(a.getString(R.string.dom_onboarding_security_title), ((TextView) a.findViewById(R.id.tour_step_title)).getText().toString());
                assertNotNull(a.findViewById(R.id.keyholder_pin_change_button));
                assertNull(a.findViewById(android.R.id.content).findViewWithTag("setup-app-card"));
                assertNull(a.findViewById(android.R.id.content).findViewWithTag("setup-feature-overview"));
            });
            assertFalse(ControllerPinManager.isConfigured(context));
        }
    }

    @Test public void domOnlyResumesStaleDraftAtPinAndNeverAppliesLocalConfiguration() {
        pristine();
        assertTrue(DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.DOM_ONLY));
        SettingsRepository settings = new SettingsRepository(context);
        settings.saveAppearance(CensorAppearance.Type.GLITCH, 71, false, false);
        context.getSharedPreferences("subhub_onboarding", 0).edit().putBoolean("in_progress", true)
                .putInt("draft_step", 2).putInt("draft_flow_version", 2)
                .putBoolean("draft_appearance_changed", true).putString("draft_style", CensorAppearance.Type.BLUR.getPreferenceValue()).commit();
        try (ActivityScenario<OnboardingActivity> page = ActivityScenario.launch(OnboardingActivity.class)) {
            page.onActivity(a -> assertEquals(a.getString(R.string.dom_onboarding_security_title),
                    ((TextView) a.findViewById(R.id.tour_step_title)).getText().toString()));
            onView(withId(R.id.keyholder_pin_change_button)).perform(scrollTo(), click());
            onView(withHint(R.string.controller_pin_label)).inRoot(isDialog()).perform(replaceText("5729"), closeSoftKeyboard());
            onView(withHint(R.string.controller_pin_confirm_label)).inRoot(isDialog()).perform(replaceText("5729"), closeSoftKeyboard());
            onView(withText(R.string.controller_pin_set)).inRoot(isDialog()).perform(click());
            com.subhub.app.onboarding.OnboardingTestSupport.goTo(page, "device_permissions", DeviceRolePreferences.Mode.DOM_ONLY);
            onView(withText(R.string.tour_accessibility_title)).check(doesNotExist());
            onView(withText(R.string.tour_overlay_title)).check(doesNotExist());
            onView(withId(R.id.tour_next)).perform(click());
            assertEquals(CensorAppearance.Type.GLITCH, settings.loadAppearance().getType());
            assertFalse(new AppModeManager(context).isArmed());
            assertTrue(OnboardingState.completed(context));
        }
    }

    @Test public void subHardcoreReleaseKeepsItsCredentialAndConfirmationGuards() {
        preferences().edit().putBoolean(HardcoreModeManager.KEY_REQUESTED, true).commit();
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<GlobalSettingsActivity> page = ActivityScenario.launch(GlobalSettingsActivity.class)) {
            page.onActivity(a -> assertNotNull(a.findViewById(R.id.switch_hardcore_mode)));
            onView(withId(R.id.switch_hardcore_mode)).perform(scrollTo(), click());
            onView(withHint(R.string.controller_pin_label)).inRoot(isDialog()).check(matches(isDisplayed()));
            assertTrue(preferences().getBoolean(HardcoreModeManager.KEY_REQUESTED, false));
            onView(withText(android.R.string.cancel)).inRoot(isDialog()).perform(click());
            assertTrue(preferences().getBoolean(HardcoreModeManager.KEY_REQUESTED, false));
            assertFalse(ControllerPinManager.isDomModeActive());
            onView(withId(R.id.switch_hardcore_mode)).perform(scrollTo(), click());
            unlockPin();
            onView(withText(R.string.hardcore_release_title)).inRoot(isDialog()).check(matches(isDisplayed()));
            page.onActivity(a -> assertNull(a.findViewById(R.id.switch_hardcore_mode)));
            assertTrue(preferences().getBoolean(HardcoreModeManager.KEY_REQUESTED, false));
            onView(withText(android.R.string.cancel)).inRoot(isDialog()).perform(click());
            assertFalse(ControllerPinManager.isDomModeActive());
            assertTrue(preferences().getBoolean(HardcoreModeManager.KEY_REQUESTED, false));
            page.onActivity(a -> assertNotNull(a.findViewById(R.id.switch_hardcore_mode)));
        }
    }

    private void pristine() {
        context.getSharedPreferences("subhub_onboarding", 0).edit().clear().commit();
        context.getSharedPreferences("subhub_controller_auth", 0).edit().clear().commit();
        preferences().edit().remove("controller_pin_salt").remove("controller_pin_hash")
                .remove("controller_keyholder_optional").remove("has_seen_onboarding").remove(DeviceRolePreferences.KEY_MODE).commit();
        ControllerPinManager.enterSubMode();
        assertTrue(OnboardingState.isPristine(context));
    }
    private static void unlockPin() {
        onView(withHint(R.string.controller_pin_label)).inRoot(isDialog()).perform(replaceText("5729"), closeSoftKeyboard());
        onView(withText(R.string.controller_pin_unlock)).inRoot(isDialog()).perform(click());
    }
}
