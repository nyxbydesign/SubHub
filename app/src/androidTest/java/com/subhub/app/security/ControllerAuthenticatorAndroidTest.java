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
    @Test public void existingControllerGateAcceptsALegacyCodeInItsSingleInput() throws Exception {
        String secret = Totp.newSecret(); long step = System.currentTimeMillis() / 30000;
        assertEquals(ControllerAuthenticator.Result.SUCCESS, new ControllerAuthenticator(context).pair(secret, Totp.code(secret, step - 1)));
        java.util.concurrent.atomic.AtomicBoolean authorized = new java.util.concurrent.atomic.AtomicBoolean();
        try (androidx.test.core.app.ActivityScenario<com.subhub.app.MainActivity> scenario = androidx.test.core.app.ActivityScenario.launch(
                new android.content.Intent(context, com.subhub.app.MainActivity.class)
                        .setAction(android.content.Intent.ACTION_MAIN)
                        .putExtra(com.subhub.app.MainActivity.EXTRA_SUPPRESS_PERMISSION_READINESS, true))) {
            scenario.onActivity(activity -> {
                ControllerPinManager.enterSubMode();
                ControllerPinGate.unlock(activity, () -> authorized.set(true), false);
            });
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withHint(com.subhub.app.R.string.keyholder_pin_or_code))
                    .perform(androidx.test.espresso.action.ViewActions.replaceText(Totp.code(secret, step)), androidx.test.espresso.action.ViewActions.closeSoftKeyboard());
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(com.subhub.app.R.string.controller_pin_unlock))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(androidx.test.espresso.action.ViewActions.click());
            assertTrue(authorized.get()); assertTrue(ControllerPinManager.isDomModeActive());
        }
    }
    @Test public void oneInputAcceptsASixDigitPinAndLegacyCodeWithoutConsumingTheOtherCredential() throws Exception {
        android.content.SharedPreferences preferences=context.getSharedPreferences(com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME,0);
        String oldHash=preferences.getString("controller_pin_hash",null),oldSalt=preferences.getString("controller_pin_salt",null);boolean optional=preferences.getBoolean("controller_keyholder_optional",false),hadOptional=preferences.contains("controller_keyholder_optional");
        String secret=Totp.newSecret();long step=System.currentTimeMillis()/Totp.STEP_MILLIS;String code=Totp.code(secret,step);String pin="123456".equals(code)?"654321":"123456";
        try {
            assertTrue(ControllerPinManager.setPin(context,pin));assertEquals(ControllerAuthenticator.Result.SUCCESS,new ControllerAuthenticator(context).pair(secret,Totp.code(secret,step-1)));
            java.util.concurrent.atomic.AtomicBoolean accepted=new java.util.concurrent.atomic.AtomicBoolean();
            try(androidx.test.core.app.ActivityScenario<com.subhub.app.MainActivity> page=androidx.test.core.app.ActivityScenario.launch(new android.content.Intent(context,com.subhub.app.MainActivity.class).setAction(android.content.Intent.ACTION_MAIN).putExtra(com.subhub.app.MainActivity.EXTRA_SUPPRESS_PERMISSION_READINESS,true))){
                page.onActivity(a->ControllerPinGate.authenticate(a,()->accepted.set(true),false));
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(android.widget.RadioGroup.class)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist());
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withHint(com.subhub.app.R.string.keyholder_pin_or_code)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(androidx.test.espresso.action.ViewActions.replaceText(pin),androidx.test.espresso.action.ViewActions.closeSoftKeyboard());
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(com.subhub.app.R.string.controller_pin_unlock)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(androidx.test.espresso.action.ViewActions.click());
                assertTrue(accepted.get());assertEquals(step-1,context.getSharedPreferences("subhub_controller_auth",0).getLong("last_step",-1));
                accepted.set(false);page.onActivity(a->ControllerPinGate.authenticate(a,()->accepted.set(true),false));
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withHint(com.subhub.app.R.string.keyholder_pin_or_code)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(androidx.test.espresso.action.ViewActions.replaceText(code),androidx.test.espresso.action.ViewActions.closeSoftKeyboard());
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(com.subhub.app.R.string.controller_pin_unlock)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(androidx.test.espresso.action.ViewActions.click());
                assertTrue(accepted.get());assertEquals(step,context.getSharedPreferences("subhub_controller_auth",0).getLong("last_step",-1));
            }
        } finally {
            android.content.SharedPreferences.Editor restore=preferences.edit();if(oldHash==null)restore.remove("controller_pin_hash");else restore.putString("controller_pin_hash",oldHash);if(oldSalt==null)restore.remove("controller_pin_salt");else restore.putString("controller_pin_salt",oldSalt);if(hadOptional)restore.putBoolean("controller_keyholder_optional",optional);else restore.remove("controller_keyholder_optional");assertTrue(restore.commit());
        }
    }

}
