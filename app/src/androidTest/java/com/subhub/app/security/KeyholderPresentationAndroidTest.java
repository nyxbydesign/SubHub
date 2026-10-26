package com.subhub.app.security;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.WindowManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class KeyholderPresentationAndroidTest {
    @org.junit.Rule public final IntegrationFixture integration = new IntegrationFixture();
    @org.junit.Rule public final com.subhub.app.UiAutomationStateRule automationState = new com.subhub.app.UiAutomationStateRule();
    @Test public void androidScreenshotContainsTheKeyholderHeader() {
        ControllerPinManager.enterDomMode();
        try (ActivityScenario<AuthenticatorActivity> scenario=ActivityScenario.launch(AuthenticatorActivity.class)) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            android.os.SystemClock.sleep(200);
            android.graphics.Rect bounds=new android.graphics.Rect();
            scenario.onActivity(activity -> {
                android.view.View title=activity.findViewById(R.id.primary_header_title);
                int[] origin=new int[2]; title.getLocationOnScreen(origin);
                bounds.set(origin[0],origin[1],origin[0]+title.getWidth(),origin[1]+title.getHeight());
            });
            assertTrue("Header must be laid out before capture: "+bounds,bounds.width()>0 && bounds.height()>0);
            Bitmap screenshot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            assertNotNull(screenshot);
            try {
                int visibleTextPixels=0;
                for(int y=bounds.top;y<bounds.bottom;y++) for(int x=bounds.left;x<bounds.right;x++) {
                    int pixel=screenshot.getPixel(x,y);
                    if(android.graphics.Color.red(pixel)>180 && android.graphics.Color.green(pixel)>180 && android.graphics.Color.blue(pixel)>180) visibleTextPixels++;
                }
                try (FileOutputStream out=new FileOutputStream(new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir(),"keyholder-android-screenshot.png"))) {
                    screenshot.compress(Bitmap.CompressFormat.PNG,100,out);
                } catch(Exception error) { throw new AssertionError(error); }
                assertTrue("Android screenshot must contain the visible header text: "+bounds+" count="+visibleTextPixels,visibleTextPixels>20);
            } finally { screenshot.recycle(); }
        } finally { ControllerPinManager.enterSubMode(); }
    }
    @Test public void localAndRemoteChoicesShareOneSetupAndSurviveRecreation() {
        try (ActivityScenario<AuthenticatorActivity> scenario = ActivityScenario.launch(AuthenticatorActivity.class)) {
            scenario.onActivity(a -> {
                assertNotNull(a.findViewById(R.id.keyholder_setup_local));
                assertNotNull(a.findViewById(R.id.keyholder_pair_device));
                assertNull(a.findViewById(R.id.authenticator_pairing_qr));
                assertEquals(0, a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE);
            });
            scenario.recreate();
            scenario.onActivity(a -> {
                assertTrue(a.findViewById(R.id.keyholder_pin_change_button).isShown());
                assertTrue(a.findViewById(R.id.keyholder_pair_device).isShown());
            });
        }
    }

    @Test public void pairedPendingAndUnavailableStatesNeverOfferPinOrManualEnrollment() {
        for (KeyholderProvider.State state : new KeyholderProvider.State[] {
                KeyholderProvider.State.READY, KeyholderProvider.State.PENDING, KeyholderProvider.State.UNAVAILABLE}) {
            com.subhub.app.people.People.installKeyholder(provider(state, "Synthetic Dom"));
            try (ActivityScenario<AuthenticatorActivity> scenario = ActivityScenario.launch(AuthenticatorActivity.class)) {
                scenario.onActivity(a -> assertRemote(a, state));
                scenario.recreate();
                scenario.onActivity(a -> assertRemote(a, state));
            }
        }
    }

    private static void assertRemote(AuthenticatorActivity activity, KeyholderProvider.State state) {
        assertEquals("Synthetic Dom", ((android.widget.TextView) activity.findViewById(R.id.keyholder_assigned_dom)).getText().toString());
        int expected = state == KeyholderProvider.State.READY ? R.string.role_connection_paired
                : state == KeyholderProvider.State.PENDING ? R.string.keyholder_pairing_in_progress : R.string.keyholder_unavailable;
        assertEquals(activity.getString(expected), ((android.widget.TextView) activity.findViewById(R.id.keyholder_setup_status)).getText().toString());
        assertNull(activity.findViewById(R.id.keyholder_setup_local));
        assertNull(activity.findViewById(R.id.keyholder_pin_change_button));
        assertNull(activity.findViewById(R.id.keyholder_remove_pin));
        assertNull(activity.findViewById(R.id.keyholder_remove_authenticator));
        assertNull(activity.findViewById(R.id.keyholder_pair_device));
        assertNull(activity.findViewById(R.id.authenticator_pairing_qr));
        assertFalse(activity.findViewById(R.id.button_edit_lock).isShown());
        assertTrue(activity.findViewById(R.id.settings_connection).isShown());
    }

    public static KeyholderProvider provider(KeyholderProvider.State state, String name) {
        return new KeyholderProvider() {
            public State state() { return state; }
            public String assignedDomName() { return name; }
            public ControllerAuthenticator.Result verifyCode(String code) { return ControllerAuthenticator.Result.UNAVAILABLE; }
        };
    }

    /** Restores runtime integration identities, not replacement defaults, after isolated UI fixtures. */
    public static final class IntegrationFixture extends org.junit.rules.ExternalResource {
        private final java.util.Map<java.lang.reflect.Field, Object> hooks = new java.util.LinkedHashMap<>();
        private final java.util.Map<String, java.util.Map<String, ?>> prefs = new java.util.LinkedHashMap<>();
        private boolean dom;
        @Override protected void before() throws Throwable {
            dom = ControllerPinManager.isDomModeActive();
            for (java.lang.reflect.Field field : com.subhub.app.people.People.class.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) && !java.lang.reflect.Modifier.isFinal(field.getModifiers())) {
                    field.setAccessible(true); hooks.put(field, field.get(null));
                }
            }
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            for (String name : new String[] {com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME,
                    "subhub_controller_auth", "subhub_home", "subhub_onboarding"})
                prefs.put(name, new java.util.LinkedHashMap<>(context.getSharedPreferences(name, 0).getAll()));
            com.subhub.app.people.People.installKeyholder(provider(KeyholderProvider.State.NONE, ""));
            com.subhub.app.people.People.installRemoteAuthority(() -> false);
            com.subhub.app.people.People.installRemoteRepository(null);
            com.subhub.app.people.People.installLocalProjection(java.util.function.UnaryOperator.identity());
            com.subhub.app.people.People.installConnectionUi(null);
        }
        @Override protected void after() {
            try {
                Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
                for (java.util.Map.Entry<String, java.util.Map<String, ?>> entry : prefs.entrySet())
                    com.subhub.app.settings.SharedPreferenceTestRestore.restore(context.getSharedPreferences(entry.getKey(), 0), entry.getValue());
            } finally {
                try {
                    for (java.util.Map.Entry<java.lang.reflect.Field, Object> entry : hooks.entrySet()) entry.getKey().set(null, entry.getValue());
                } catch (IllegalAccessException error) { throw new AssertionError(error); }
                finally { if (dom) ControllerPinManager.enterDomMode(); else ControllerPinManager.enterSubMode(); }
            }
        }
    }
}
