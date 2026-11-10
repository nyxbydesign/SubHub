package com.subhub.app.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.EditText;
import android.widget.LinearLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.MainActivity;
import com.subhub.app.R;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.people.LocalSessionControls;
import com.subhub.app.people.People;
import com.subhub.app.people.PersonId;
import com.subhub.app.people.ProfileStore;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.stats.AchievementRole;
import com.subhub.app.stats.StatsRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExternalResource;
import org.junit.rules.RuleChain;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.*;

/** BOTH workspace access is not the authority that can alter its still-running Sub session. */
@RunWith(AndroidJUnit4.class)
public final class SwitchWorkspaceAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new KeyholderPresentationAndroidTest.IntegrationFixture();
    private final ExternalResource runtime = new ExternalResource() {
        private final Map<String, Map<String, ?>> saved = new LinkedHashMap<>();
        @Override protected void before() {
            assertFalse("Use an idle owned guest", RuntimeSessionIdentity.active(context));
            for (String name : new String[] { StatsRepository.PREFS_NAME, "subhub_penance" })
                saved.put(name, new LinkedHashMap<>(context.getSharedPreferences(name, 0).getAll()));
        }
        @Override protected void after() {
            for (Map.Entry<String, Map<String, ?>> entry : saved.entrySet())
                com.subhub.app.settings.SharedPreferenceTestRestore.restore(
                        context.getSharedPreferences(entry.getKey(), 0), entry.getValue());
        }
    };
    @Rule public final RuleChain rules = RuleChain.outerRule(runtime).around(integration)
            .around(new com.subhub.app.UiAutomationStateRule());

    private SharedPreferences preferences() { return new SettingsRepository(context).preferences(); }
    private void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    @Before public void localPin() {
        main(() -> {
            preferences().edit().putString(DeviceRolePreferences.KEY_MODE, "BOTH")
                    .putBoolean(com.subhub.app.appmode.AppModeManager.KEY_ARMED, false)
                    .remove("commitment_ends_at").remove("commitment_ends_elapsed")
                    .putBoolean(HardcoreModeManager.KEY_REQUESTED, false).commit();
            com.subhub.app.onboarding.OnboardingState.complete(context);
            assertTrue(ControllerPinManager.setPin(context, "5729"));
            ControllerPinManager.enterSubMode();
        });
    }

    @Test public void assignedOfflinePinOpensOnlyOwnWorkspaceAndPreservesActiveSub() {
        main(() -> {
            AtomicInteger stateReads = new AtomicInteger();
            People.installKeyholder(new KeyholderProvider() {
                public State state() { stateReads.incrementAndGet(); return State.UNAVAILABLE; }
                public String assignedDomName() { return "Synthetic assigned Dom"; }
                public ControllerAuthenticator.Result verifyCode(String code) { return ControllerAuthenticator.Result.UNAVAILABLE; }
            });
            People.installRemoteAuthority(() -> true);
            preferences().edit().putLong("commitment_ends_at", System.currentTimeMillis() + 3_600_000L)
                    .remove("commitment_ends_elapsed")
                    .putString("commitment_session_identity", "12345678-1234-1234-1234-123456789abc").commit();
            SharedPreferences stats = context.getSharedPreferences(StatsRepository.PREFS_NAME, 0);
            stats.edit().putString("active_session_identity", "22345678-1234-1234-1234-123456789abc").commit();
            Map<String, ?> before = new LinkedHashMap<>(preferences().getAll());
            Map<String, ?> statsBefore = new LinkedHashMap<>(stats.getAll());
            String identity = RuntimeSessionIdentity.current(context);
            RuntimeSessionIdentity.ComponentObservation components = RuntimeSessionIdentity.componentObservation(context);
            assertFalse(identity.isEmpty());
            assertFalse(ControllerPinManager.verify(context, "5729"));
            assertFalse(ControllerPinManager.verifyLocalDomWorkspace(context, "0000"));
            int reads = stateReads.get();
            assertTrue(ControllerPinManager.verifyLocalDomWorkspace(context, "5729"));
            assertTrue(ControllerPinManager.isLocalDomWorkspaceUnlocked());
            assertTrue(ControllerPinManager.isDomWorkspaceUnlocked());
            ControllerPinManager.domWorkspaceRevision();
            assertEquals("Workspace proof must not ask remote readiness", reads, stateReads.get());
            assertTrue(DeviceRolePreferences.isDomWorkspace(context));
            assertTrue(ProfileStore.mayEdit(context, true));
            assertTrue(AchievementRole.DOM.accessible(context));
            assertFalse(ProfileStore.mayEdit(context, false));
            assertFalse(ControllerPinManager.isSessionUnlocked());
            assertFalse(ControllerPinManager.isDomModeActive());
            assertFalse(ControllerPinManager.canEditConfiguration());
            assertFalse(LocalSessionControls.end(context, PersonId.LOCAL));
            assertEquals(ProtectionStopPolicy.Decision.TIMER_LOCKED, ProtectionStopPolicy.decision(context));
            assertFalse(ControllerPinManager.setPin(context, "9371"));
            assertFalse(ControllerPinManager.removePin(context));
            assertFalse(DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.DOM_ONLY));
            assertTrue(RuntimeSessionIdentity.active(context));
            assertEquals(identity, RuntimeSessionIdentity.current(context));
            assertTrue(components.isCurrent());
            assertEquals(components.signature, RuntimeSessionIdentity.componentSignature(context));
            assertEquals(before, preferences().getAll());
            assertEquals(statsBefore, stats.getAll());
        });
    }

    @Test public void proofRetiresOnLockRoleCredentialOrProviderReplacement() {
        main(() -> {
            KeyholderProvider provider = KeyholderPresentationAndroidTest.provider(KeyholderProvider.State.READY, "Synthetic Dom");
            People.installKeyholder(provider);
            assertTrue(ControllerPinManager.verifyLocalDomWorkspace(context, "5729"));
            long first = ControllerPinManager.domWorkspaceRevision();
            ControllerPinManager.lockNow();
            assertFalse(ControllerPinManager.isLocalDomWorkspaceUnlocked());
            assertTrue(ControllerPinManager.verifyLocalDomWorkspace(context, "5729"));
            assertTrue(ControllerPinManager.domWorkspaceRevision() > first);
            preferences().edit().putString(DeviceRolePreferences.KEY_MODE, "SUB_ONLY").commit();
            assertFalse(ControllerPinManager.isLocalDomWorkspaceUnlocked());
            assertFalse(ControllerPinManager.verifyLocalDomWorkspace(context, "5729"));
            preferences().edit().putString(DeviceRolePreferences.KEY_MODE, "BOTH").commit();
            assertFalse(ControllerPinManager.isLocalDomWorkspaceUnlocked());
            assertTrue(ControllerPinManager.verifyLocalDomWorkspace(context, "5729"));
            String oldHash = preferences().getString("controller_pin_hash", "");
            preferences().edit().putString("controller_pin_hash", "replacement").commit();
            assertFalse(ControllerPinManager.isLocalDomWorkspaceUnlocked());
            preferences().edit().putString("controller_pin_hash", oldHash).commit();
            assertFalse(ControllerPinManager.isLocalDomWorkspaceUnlocked());
            assertTrue(ControllerPinManager.verifyLocalDomWorkspace(context, "5729"));
            People.installKeyholder(KeyholderPresentationAndroidTest.provider(KeyholderProvider.State.UNAVAILABLE, "Same name"));
            assertFalse(ControllerPinManager.isLocalDomWorkspaceUnlocked());
            assertTrue(ControllerPinManager.verifyLocalDomWorkspace(context, "5729"));
            assertFalse(ControllerPinManager.isSessionUnlocked());
        });
    }

    @Test public void freshWorkspacePromptDoesNotReuseUnlockedProof() {
        People.installKeyholder(KeyholderPresentationAndroidTest.provider(KeyholderProvider.State.UNAVAILABLE, "Synthetic Dom"));
        main(() -> assertTrue(ControllerPinManager.verifyLocalDomWorkspace(context, "5729")));
        AtomicInteger accepted = new AtomicInteger();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> ControllerPinGate.authenticateDomWorkspace(activity, accepted::incrementAndGet, false));
            assertEquals(0, accepted.get());
            onView(withHint(R.string.controller_pin_label)).inRoot(isDialog()).perform(replaceText("5729"), closeSoftKeyboard());
            onView(withText(R.string.controller_pin_unlock)).inRoot(isDialog()).perform(click());
            assertEquals(1, accepted.get());
            assertFalse(ControllerPinManager.isSessionUnlocked());
            assertTrue(ControllerPinManager.isLocalDomWorkspaceUnlocked());
            onView(withId(R.id.nav_stats)).perform(click());
            onView(withId(R.id.primary_header_title)).check((view, missing) -> {
                if (missing != null) throw missing;
                boolean actualProfileHost = false;
                for (android.app.Activity activity : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                        .getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)) {
                    if (activity instanceof com.subhub.app.people.ProfileActivity
                            && activity instanceof com.subhub.app.stats.StatsActivity
                            && view == activity.findViewById(R.id.primary_header_title)) actualProfileHost = true;
                }
                assertTrue("Profile tab must open the actual own-profile Stats host", actualProfileHost);
                assertEquals(context.getString(R.string.role_profile), ((android.widget.TextView) view).getText().toString());
            });
            onView(withId(R.id.nav_stats)).check(androidx.test.espresso.assertion.ViewAssertions.matches(isSelected()));
            onView(withId(R.id.button_edit_lock)).check(androidx.test.espresso.assertion.ViewAssertions.matches(withText(R.string.role_dom)));
            onView(withTagValue(org.hamcrest.Matchers.is("self_profile:dom")))
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()));
            onView(withTagValue(org.hamcrest.Matchers.is("self_profile_name")))
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(withText(ProfileStore.load(context, true).name)));
            onView(withTagValue(org.hamcrest.Matchers.is("self_profile:sub")))
                    .check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist());
            assertFalse(ControllerPinManager.isSessionUnlocked());
            onView(withId(R.id.button_edit_lock)).perform(click());
            onView(withId(R.id.button_edit_lock)).check(androidx.test.espresso.assertion.ViewAssertions.matches(withText(R.string.role_sub)));
            onView(withTagValue(org.hamcrest.Matchers.is("self_profile:sub")))
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()));
            onView(withTagValue(org.hamcrest.Matchers.is("self_profile:dom")))
                    .check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist());
            assertFalse(ControllerPinManager.isLocalDomWorkspaceUnlocked());
            assertFalse(ControllerPinManager.isDomWorkspaceUnlocked());
            assertFalse(ControllerPinManager.isSessionUnlocked());
            androidx.test.espresso.Espresso.pressBack();
            scenario.onActivity(activity -> {
                assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, activity.getLifecycle().getCurrentState());
                assertFalse(DeviceRolePreferences.isDomWorkspace(activity));
            });
        }
    }

    @Test public void protectedCredentialChoiceStillRequiresAssignedRemoteCode() {
        People.installKeyholder(new KeyholderProvider() {
            public State state() { return State.READY; }
            public String assignedDomName() { return "Synthetic Dom"; }
            public ControllerAuthenticator.Result verifyCode(String code) {
                if (!"684219".equals(code)) return ControllerAuthenticator.Result.INVALID;
                ControllerPinManager.enterDomMode();
                return ControllerAuthenticator.Result.SUCCESS;
            }
        });
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                EditText input = new EditText(activity);
                ControllerCredentialChoice choice = new ControllerCredentialChoice(activity, new LinearLayout(activity), input);
                input.setText("5729"); assertFalse(choice.verify());
                assertFalse(ControllerPinManager.isDomModeActive());
                input.setText("684219"); assertTrue(choice.verify());
                assertTrue(ControllerPinManager.isDomModeActive());
                assertTrue(ControllerPinManager.isSessionUnlocked());
                assertTrue(ControllerPinManager.isDomWorkspaceUnlocked());
                assertFalse(ControllerPinManager.isLocalDomWorkspaceUnlocked());
            });
        }
    }
}
