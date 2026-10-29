package com.subhub.app.security;

import android.content.Context;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.onboarding.OnboardingActivity;
import com.subhub.app.people.People;
import com.subhub.app.settings.SettingsRepository;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class KeyholderFlowAndroidTest {
    @Rule public final KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new KeyholderPresentationAndroidTest.IntegrationFixture();
    @Rule public final com.subhub.app.UiAutomationStateRule automation = new com.subhub.app.UiAutomationStateRule();
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private MutableProvider provider;

    @Before public void freshLocalAuthority() {
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                .remove("controller_pin_salt").remove("controller_pin_hash")
                .remove("controller_keyholder_optional").commit();
        context.getSharedPreferences("subhub_controller_auth", 0).edit().clear().commit();
        provider = new MutableProvider();
        People.installKeyholder(provider);
        ControllerPinManager.enterSubMode();
    }

    @Test public void onboardingSetsFirstLocalPinWithoutAnUnlockDetour() {
        context.getSharedPreferences("subhub_onboarding", 0).edit().clear()
                .putBoolean("in_progress", true).putInt("draft_flow_version", 2)
                .putInt("draft_step", 4).commit();
        try (ActivityScenario<OnboardingActivity> page = ActivityScenario.launch(OnboardingActivity.class)) {
            page.onActivity(a -> assertNotNull(a.findViewById(R.id.keyholder_pin_change_button)));
            onView(withId(R.id.keyholder_pin_change_button)).perform(scrollTo(), click());
            enterPin("5729");
            assertTrue(ControllerPinManager.isConfigured(context));
            assertFalse(ControllerPinManager.isDomModeActive());
            assertTrue(ControllerPinManager.verify(context, "5729"));
            ControllerPinManager.enterSubMode();
            page.recreate();
            page.onActivity(a -> assertNotNull(a.findViewById(R.id.keyholder_setup_local)));
        }
    }

    @Test public void finishingSetupAfterPairingPreservesTheRemoteConfiguration() {
        SettingsRepository settings = new SettingsRepository(context);
        settings.saveAppearance(com.subhub.app.settings.CensorAppearance.Type.GLITCH, 71, false, false);
        context.getSharedPreferences("subhub_onboarding", 0).edit().clear()
                .putBoolean("in_progress", true).putInt("draft_flow_version", 2)
                .putInt("draft_step", 4).putBoolean("draft_appearance_changed", true)
                .putString("draft_style", com.subhub.app.settings.CensorAppearance.Type.BLUR.getPreferenceValue()).commit();
        provider.state = KeyholderProvider.State.READY;
        try (ActivityScenario<OnboardingActivity> page = ActivityScenario.launch(OnboardingActivity.class)) {
            page.onActivity(a -> assertNull(a.findViewById(R.id.keyholder_pin_change_button)));
            com.subhub.app.onboarding.OnboardingTestSupport.goTo(page, "device_permissions");
            onView(withId(R.id.tour_next)).perform(click());
            assertEquals(com.subhub.app.settings.CensorAppearance.Type.GLITCH, settings.loadAppearance().getType());
            assertFalse(ControllerPinManager.isConfigured(context));
        }
    }

    @Test public void cancelingFirstPinSetupDoesNotCreateCredentialsOrUnkeyedAccess() {
        try (ActivityScenario<AuthenticatorActivity> page = ActivityScenario.launch(AuthenticatorActivity.class)) {
            onView(withId(R.id.keyholder_pin_change_button)).perform(scrollTo(), click());
            onView(withText(android.R.string.cancel)).inRoot(isDialog()).perform(click());
            assertFalse(ControllerPinManager.hasCredentials(context));
            assertFalse(ControllerPinManager.allowsUnkeyedAccess(context));
            assertFalse(ControllerPinManager.isDomModeActive());
        }
    }

    @Test public void assigningRemoteAuthorityClosesAnAlreadyOpenPinSetup() {
        try (ActivityScenario<AuthenticatorActivity> page = ActivityScenario.launch(AuthenticatorActivity.class)) {
            onView(withId(R.id.keyholder_pin_change_button)).perform(scrollTo(), click());
            page.onActivity(a -> {
                assertSame(provider, People.keyholder());
                provider.state = KeyholderProvider.State.READY;
                assertEquals(KeyholderProvider.State.READY, People.keyholder().state());
            });
            try { awaitAbsent(R.string.controller_pin_label); }
            catch (AssertionError failure) {
                page.onActivity(a -> {
                    android.view.View local = a.findViewById(R.id.keyholder_setup_local);
                    android.util.Log.e("KeyholderFlowWitness", "sameProvider=" + (provider == People.keyholder())
                            + ";state=" + People.keyholder().state() + ";localCard=" + (local != null)
                            + ";localShown=" + (local != null && local.isShown()));
                });
                throw failure;
            }
            assertFalse(ControllerPinManager.isConfigured(context));
            page.recreate();
            page.onActivity(a -> {
                assertNull(a.findViewById(R.id.keyholder_pin_change_button));
                assertNull(a.findViewById(R.id.keyholder_remove_pin));
                assertNotNull(a.findViewById(R.id.keyholder_assigned_dom));
            });
        }
    }

    @Test public void assignedSubUsesOnlyRemoteCodeEvenWithRetainedLocalPin() {
        assertTrue(ControllerPinManager.setPin(context, "5729"));
        provider.state = KeyholderProvider.State.READY;
        ControllerPinManager.enterSubMode();
        AtomicBoolean approved = new AtomicBoolean();
        try (ActivityScenario<AuthenticatorActivity> page = ActivityScenario.launch(AuthenticatorActivity.class)) {
            page.onActivity(a -> ControllerPinGate.unlock(a, () -> approved.set(true), false));
            onView(withHint(R.string.controller_pin_label)).check(doesNotExist());
            onView(withText(R.string.authenticator_method_pin)).check(doesNotExist());
            onView(withHint(R.string.authenticator_code)).inRoot(isDialog())
                    .perform(replaceText("654321"), closeSoftKeyboard());
            onView(withText(R.string.controller_pin_unlock)).inRoot(isDialog()).perform(click());
            assertTrue(approved.get());
            assertEquals(1, provider.verifications);
            assertTrue(ControllerPinManager.isDomModeActive());
            assertTrue(ControllerPinManager.isConfigured(context));
        }
    }

    @Test public void sensitiveActionRequiresFreshPinWithoutLockingTheExistingSessionOnCancel() {
        assertTrue(ControllerPinManager.setPin(context, "5729"));
        AtomicBoolean approved = new AtomicBoolean();
        try (ActivityScenario<AuthenticatorActivity> page = ActivityScenario.launch(AuthenticatorActivity.class)) {
            page.onActivity(a -> ControllerPinGate.authenticate(a, () -> approved.set(true), false));
            assertFalse(approved.get());
            onView(withText(android.R.string.cancel)).inRoot(isDialog()).perform(click());
            assertTrue(ControllerPinManager.isDomModeActive());
            assertFalse(approved.get());
            page.onActivity(a -> ControllerPinGate.authenticate(a, () -> approved.set(true), false));
            onView(withHint(R.string.controller_pin_label)).inRoot(isDialog())
                    .perform(replaceText("5729"), closeSoftKeyboard());
            onView(withText(R.string.controller_pin_unlock)).inRoot(isDialog()).perform(click());
            assertTrue(approved.get());
        }
    }

    @Test public void existingStandaloneAuthenticatorCanBeRemovedOnlyAfterFreshPin() throws Exception {
        assertTrue(ControllerPinManager.setPin(context, "5729"));
        ControllerAuthenticator legacy = new ControllerAuthenticator(context);
        String secret = Totp.newSecret();
        assertEquals(ControllerAuthenticator.Result.SUCCESS,
                legacy.pair(secret, Totp.code(secret, System.currentTimeMillis() / Totp.STEP_MILLIS)));
        assertTrue(legacy.isPaired());
        try (ActivityScenario<AuthenticatorActivity> page = ActivityScenario.launch(AuthenticatorActivity.class)) {
            onView(withId(R.id.keyholder_remove_authenticator)).perform(scrollTo(), click());
            onView(withText(android.R.string.cancel)).inRoot(isDialog()).perform(click());
            assertTrue(legacy.isPaired());
            assertTrue(ControllerPinManager.isDomModeActive());
            onView(withId(R.id.keyholder_remove_authenticator)).perform(scrollTo(), click());
            onView(withHint(R.string.keyholder_pin_or_code)).inRoot(isDialog())
                    .perform(replaceText("5729"), closeSoftKeyboard());
            onView(withText(R.string.controller_pin_unlock)).inRoot(isDialog()).perform(click());
            assertFalse(legacy.isPaired());
            assertTrue(ControllerPinManager.isConfigured(context));
            assertTrue(ControllerPinManager.verify(context, "5729"));
            onView(withId(R.id.keyholder_remove_authenticator)).check(doesNotExist());
        }
    }

    private static void enterPin(String value) {
        onView(withHint(R.string.controller_pin_label)).inRoot(isDialog())
                .perform(replaceText(value), closeSoftKeyboard());
        onView(withHint(R.string.controller_pin_confirm_label)).inRoot(isDialog())
                .perform(replaceText(value), closeSoftKeyboard());
        onView(withText(R.string.controller_pin_set)).inRoot(isDialog()).perform(click());
    }

    private static void awaitAbsent(int hint) {
        long deadline = android.os.SystemClock.elapsedRealtime() + 4000;
        AssertionError last = null;
        do {
            try { onView(withHint(hint)).check(doesNotExist()); return; }
            catch (AssertionError transientView) { last = transientView; }
            android.os.SystemClock.sleep(20);
        } while (android.os.SystemClock.elapsedRealtime() < deadline);
        throw last == null ? new AssertionError("Stale PIN field remains") : last;
    }

    private static final class MutableProvider implements KeyholderProvider {
        volatile State state = State.NONE;
        int verifications;
        public State state() { return state; }
        public String assignedDomName() { return "Synthetic Dom"; }
        public ControllerAuthenticator.Result verifyCode(String code) {
            verifications++;
            if (state != State.READY || !"654321".equals(code)) return ControllerAuthenticator.Result.INVALID;
            ControllerPinManager.enterDomMode();
            return ControllerAuthenticator.Result.SUCCESS;
        }
    }
}
