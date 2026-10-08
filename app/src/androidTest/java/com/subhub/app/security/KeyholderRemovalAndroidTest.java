package com.subhub.app.security;

import android.content.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.MainActivity;
import com.subhub.app.R;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.*;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class KeyholderRemovalAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    @Before public void setup() { context.getSharedPreferences("subhub_controller_auth",0).edit().clear().commit(); assertTrue(ControllerPinManager.setPin(context,"2468")); }
    @After public void restoreFixture() { context.getSharedPreferences("subhub_controller_auth",0).edit().clear().commit(); ControllerPinManager.setPin(context,"2468"); ControllerPinManager.enterSubMode(); }
    @Test public void bothRemovalButtonsWorkWithoutAdditionalConfirmation() throws Exception {
        String secret=Totp.newSecret(); assertEquals(ControllerAuthenticator.Result.SUCCESS,new ControllerAuthenticator(context).pair(secret,Totp.code(secret,System.currentTimeMillis()/30000)));
        try(ActivityScenario<AuthenticatorActivity> page=ActivityScenario.launch(AuthenticatorActivity.class)) {
            onView(withId(R.id.keyholder_remove_pin)).perform(scrollTo(),click());
            assertFalse(ControllerPinManager.isConfigured(context)); assertTrue(new ControllerAuthenticator(context).isPaired());
            onView(withId(R.id.keyholder_remote_header)).perform(scrollTo(),click());
            onView(withId(R.id.keyholder_remove_authenticator)).perform(scrollTo(),click());
            assertFalse(ControllerPinManager.hasCredentials(context)); assertTrue(ControllerPinManager.allowsUnkeyedAccess(context));
        }
        ControllerPinManager.enterSubMode();
        try(ActivityScenario<MainActivity> home=ActivityScenario.launch(new Intent(context,MainActivity.class).setAction(Intent.ACTION_MAIN))) {
            home.recreate(); AtomicBoolean opened=new AtomicBoolean();
            home.onActivity(a->ControllerPinGate.require(a,()->opened.set(true),false));
            assertTrue(opened.get()); assertFalse(ControllerPinManager.isConfigured(context));
        }
    }
    @Test public void remainingAuthenticatorStillProtectsAccessWhenPinIsRemoved() throws Exception {
        String secret=Totp.newSecret(); long step=System.currentTimeMillis()/30000;
        assertEquals(ControllerAuthenticator.Result.SUCCESS,new ControllerAuthenticator(context).pair(secret,Totp.code(secret,step-1)));
        assertTrue(ControllerPinManager.removePin(context)); ControllerPinManager.enterSubMode();
        assertFalse(ControllerPinManager.allowsUnkeyedAccess(context));
        AtomicBoolean opened=new AtomicBoolean();
        try(ActivityScenario<MainActivity> home=ActivityScenario.launch(new Intent(context,MainActivity.class).setAction(Intent.ACTION_MAIN))) {
            home.onActivity(a->ControllerPinGate.require(a,()->opened.set(true),false));
            assertFalse(opened.get());
            onView(withHint(R.string.authenticator_code)).perform(replaceText(Totp.code(secret,step)),closeSoftKeyboard());
            onView(withText(R.string.controller_pin_unlock)).perform(click());
            assertTrue(opened.get()); assertFalse(ControllerPinManager.isConfigured(context));
        }
    }
    @Test public void meetDismissesHomeNotePersistently() {
        context.getSharedPreferences("subhub_home",0).edit().remove("keyholder_intro_dismissed").commit();
        try(ActivityScenario<MainActivity> home=ActivityScenario.launch(new Intent(context,MainActivity.class).setAction(Intent.ACTION_MAIN))) {
            onView(withId(R.id.keyholder_intro_open)).perform(scrollTo(),click());
            assertTrue(context.getSharedPreferences("subhub_home",0).getBoolean("keyholder_intro_dismissed",false));
            androidx.test.espresso.Espresso.pressBack(); home.recreate();
            home.onActivity(a->assertEquals(android.view.View.GONE,a.findViewById(R.id.keyholder_intro).getVisibility()));
        } finally { context.getSharedPreferences("subhub_home",0).edit().remove("keyholder_intro_dismissed").commit(); }
    }
}
