package com.subhub.app.security;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ControllerAuthenticatorAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    @Before public void reset() { context.getSharedPreferences("subhub_controller_auth", 0).edit().clear().commit(); ControllerPinManager.enterDomMode(); }
    @After public void cleanup() { context.getSharedPreferences("subhub_controller_auth", 0).edit().clear().commit(); ControllerPinManager.enterSubMode(); }
    @Test public void pairingRequiresControllerAndConfirmedCodeAndStoresOnlyCiphertext() throws Exception {
        ControllerAuthenticator auth = new ControllerAuthenticator(context); String secret = Totp.newSecret();
        ControllerPinManager.enterSubMode();
        assertEquals(ControllerAuthenticator.Result.UNAVAILABLE, auth.pair(secret, Totp.code(secret, System.currentTimeMillis() / 30000)));
        assertFalse(auth.isPaired()); ControllerPinManager.enterDomMode();
        assertEquals(ControllerAuthenticator.Result.INVALID, auth.pair(secret, "invalid"));
        assertFalse(auth.isPaired());
        assertEquals(ControllerAuthenticator.Result.SUCCESS, auth.pair(secret, Totp.code(secret, System.currentTimeMillis() / 30000)));
        String stored = context.getSharedPreferences("subhub_controller_auth", 0).getString("encrypted_secret", "");
        assertFalse(stored.contains(secret)); assertTrue(stored.contains("."));
        assertTrue(new ControllerAuthenticator(context).isPaired());
        ControllerPinManager.enterSubMode(); assertFalse(auth.remove());
        ControllerPinManager.enterDomMode(); assertTrue(auth.remove()); assertFalse(auth.isPaired());
    }
    @Test public void consumesCodesOnceAndPersistsReplayStateAcrossInstances() throws Exception {
        ControllerAuthenticator auth = new ControllerAuthenticator(context); String secret = Totp.newSecret();
        long step = System.currentTimeMillis() / 30000;
        assertEquals(ControllerAuthenticator.Result.SUCCESS, auth.pair(secret, Totp.code(secret, step - 1)));
        ControllerPinManager.enterSubMode();
        String code = Totp.code(secret, step);
        assertEquals(ControllerAuthenticator.Result.SUCCESS, new ControllerAuthenticator(context).verify(code));
        assertTrue(ControllerPinManager.isDomModeActive()); ControllerPinManager.enterSubMode();
        assertEquals(ControllerAuthenticator.Result.INVALID, new ControllerAuthenticator(context).verify(code));
        assertFalse(ControllerPinManager.isDomModeActive());
    }
    @Test public void invalidAttemptsThrottlePersistentlyWithoutGrantingDom() throws Exception {
        ControllerAuthenticator auth = new ControllerAuthenticator(context); String secret = Totp.newSecret();
        assertEquals(ControllerAuthenticator.Result.SUCCESS, auth.pair(secret, Totp.code(secret, System.currentTimeMillis() / 30000)));
        ControllerPinManager.enterSubMode();
        for (int i = 0; i < 5; i++) assertEquals(ControllerAuthenticator.Result.INVALID, auth.verify("bad"));
        assertTrue(new ControllerAuthenticator(context).remainingCooldownMillis() > 0);
        assertEquals(ControllerAuthenticator.Result.THROTTLED, new ControllerAuthenticator(context).verify("bad"));
        assertFalse(ControllerPinManager.isDomModeActive());
    }
    @Test public void existingControllerGateOffersExplicitAuthenticatorAndAuthorizesTheSameCallback() throws Exception {
        String secret = Totp.newSecret(); long step = System.currentTimeMillis() / 30000;
        assertEquals(ControllerAuthenticator.Result.SUCCESS, new ControllerAuthenticator(context).pair(secret, Totp.code(secret, step - 1)));
        java.util.concurrent.atomic.AtomicBoolean authorized = new java.util.concurrent.atomic.AtomicBoolean();
        try (androidx.test.core.app.ActivityScenario<com.subhub.app.MainActivity> scenario = androidx.test.core.app.ActivityScenario.launch(
                new android.content.Intent(context, com.subhub.app.MainActivity.class)
                        .setAction(android.content.Intent.ACTION_MAIN)
                        .putExtra(com.subhub.app.MainActivity.EXTRA_SUPPRESS_PERMISSION_READINESS, true))) {
            scenario.onActivity(activity -> {
                ControllerPinManager.enterSubMode();
                ControllerPinGate.require(activity, () -> authorized.set(true), false);
            });
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(com.subhub.app.R.string.authenticator_method_code))
                    .perform(androidx.test.espresso.action.ViewActions.click());
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withHint(com.subhub.app.R.string.authenticator_code))
                    .perform(androidx.test.espresso.action.ViewActions.replaceText(Totp.code(secret, step)), androidx.test.espresso.action.ViewActions.closeSoftKeyboard());
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(com.subhub.app.R.string.controller_pin_unlock))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(androidx.test.espresso.action.ViewActions.click());
            assertTrue(authorized.get()); assertTrue(ControllerPinManager.isDomModeActive());
        }
    }
}
