package com.subhub.app.security;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.MainActivity;
import com.subhub.app.R;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class KeyholderDiscoveryAndroidTest {
    @org.junit.Rule public final com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture();
    @Test public void remoteChoiceOpensSubPairingAndPreservesTheLocalRole() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        com.subhub.app.people.People.installConnectionUi(new com.subhub.app.people.ConnectionUi() {
            public void openPairing(android.app.Activity activity) { fail("The explicit Sub pairing route is required"); }
            public void openPairing(android.app.Activity activity, boolean asDom) { assertFalse(asDom); calls.incrementAndGet(); }
            public void openConnection(android.app.Activity activity, com.subhub.app.people.PersonId id) { fail("No relationship exists yet"); }
        });
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<AuthenticatorActivity> page = ActivityScenario.launch(AuthenticatorActivity.class)) {
            onView(withId(R.id.keyholder_pair_device)).perform(scrollTo(), click());
            assertEquals(1, calls.get());
            assertFalse(ControllerPinManager.isDomModeActive());
            page.recreate();
            onView(withId(R.id.keyholder_pair_device)).perform(scrollTo(), click());
            assertEquals(2, calls.get());
            assertFalse(ControllerPinManager.isDomModeActive());
        }
    }
    @Test public void dismissedIntroductionStaysDismissedAndRitualsStaysDomOnly() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences("subhub_home", 0).edit().remove("keyholder_intro_dismissed").commit();
        new com.subhub.app.settings.SettingsRepository(context).preferences().edit().remove("controller_pin_salt").remove("controller_pin_hash").commit();
        context.getSharedPreferences("subhub_controller_auth", 0).edit().clear().commit();
        assertTrue(ControllerPinManager.useWithoutKeyholder(context));
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<MainActivity> home = ActivityScenario.launch(new Intent(context, MainActivity.class).setAction(Intent.ACTION_MAIN))) {
            home.onActivity(a->{
                if("PERMISSIONS".equals(a.findViewById(R.id.global_notice_host).getTag()))a.findViewById(R.id.global_notice_dismiss).performClick();
                assertEquals("KEYHOLDER",a.findViewById(R.id.global_notice_host).getTag());
            });
            onView(withId(R.id.global_notice_dismiss)).perform(scrollTo(), click());
            home.recreate();
            home.onActivity(activity -> {
                assertEquals(View.GONE, activity.findViewById(R.id.global_notice_host).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.nav_contract).getVisibility());
            });
        } finally { context.getSharedPreferences("subhub_home", 0).edit().remove("keyholder_intro_dismissed").commit(); }
    }
    @Test public void directKeyholderEntryOffersSetupWithoutChangingRole() {
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<AuthenticatorActivity> page = ActivityScenario.launch(AuthenticatorActivity.class)) {
            page.onActivity(a -> {
                assertNotNull(a.findViewById(R.id.keyholder_pin_change_button));
                assertNotNull(a.findViewById(R.id.keyholder_pair_device));
                assertNull(a.findViewById(R.id.authenticator_pairing_qr));
                assertFalse(a.findViewById(R.id.button_edit_lock).isShown());
            });
            assertFalse(ControllerPinManager.isDomModeActive());
            page.recreate();
            assertFalse(ControllerPinManager.isDomModeActive());
        }
    }
}
