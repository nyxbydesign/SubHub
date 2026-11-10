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
    @Test public void localAndRemoteChoicesShareOneSetupAndSurviveRecreation() throws Exception {
        try (ActivityScenario<AuthenticatorActivity> scenario = ActivityScenario.launch(AuthenticatorActivity.class)) {
            awaitRendered(scenario, KeyholderPresentationAndroidTest::localRendered);
            scenario.onActivity(a -> {
                assertNotNull(a.findViewById(R.id.keyholder_setup_local));
                assertNotNull(a.findViewById(R.id.keyholder_pair_device));
                assertNull(a.findViewById(R.id.authenticator_pairing_qr));
                assertEquals(0, a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE);
            });
            scenario.recreate();
            awaitRendered(scenario, KeyholderPresentationAndroidTest::localRendered);
            scenario.onActivity(a -> {
                assertTrue(a.findViewById(R.id.keyholder_pin_change_button).isShown());
                assertTrue(a.findViewById(R.id.keyholder_pair_device).isShown());
            });
        }
    }

    @Test public void pairedPendingAndUnavailableStatesNeverOfferPinOrManualEnrollment() throws Exception {
        for (KeyholderProvider.State state : new KeyholderProvider.State[] {
                KeyholderProvider.State.READY, KeyholderProvider.State.PENDING, KeyholderProvider.State.UNAVAILABLE}) {
            com.subhub.app.people.People.installKeyholder(provider(state, "Synthetic Dom"));
            try (ActivityScenario<AuthenticatorActivity> scenario = ActivityScenario.launch(AuthenticatorActivity.class)) {
                awaitRendered(scenario, a -> remoteRendered(a, state));
                scenario.onActivity(a -> assertRemote(a, state));
                scenario.recreate();
                awaitRendered(scenario, a -> remoteRendered(a, state));
                scenario.onActivity(a -> assertRemote(a, state));
            }
        }
    }

    private static boolean localRendered(AuthenticatorActivity activity) {
        android.view.View local = activity.findViewById(R.id.keyholder_setup_local);
        android.view.View pin = activity.findViewById(R.id.keyholder_pin_change_button);
        android.view.View pairing = activity.findViewById(R.id.keyholder_pair_device);
        return local != null && local.isShown() && pin != null && pin.isShown() && pairing != null && pairing.isShown();
    }

    private static boolean remoteRendered(AuthenticatorActivity activity, KeyholderProvider.State state) {
        android.widget.TextView name = activity.findViewById(R.id.keyholder_assigned_dom);
        android.widget.TextView status = activity.findViewById(R.id.keyholder_setup_status);
        int expected = state == KeyholderProvider.State.READY ? R.string.role_connection_paired
                : state == KeyholderProvider.State.PENDING ? R.string.keyholder_pairing_in_progress : R.string.keyholder_unavailable;
        return name != null && name.isShown() && "Synthetic Dom".contentEquals(name.getText())
                && status != null && status.isShown() && activity.getString(expected).contentEquals(status.getText());
    }

    private static void awaitRendered(ActivityScenario<AuthenticatorActivity> scenario,
            java.util.function.Predicate<AuthenticatorActivity> expected) throws InterruptedException {
        java.util.concurrent.CountDownLatch rendered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Runnable> remove = new java.util.concurrent.atomic.AtomicReference<>(() -> {});
        try {
            scenario.onActivity(activity -> {
                if (expected.test(activity)) { rendered.countDown(); return; }
                android.view.View root = activity.findViewById(android.R.id.content);
                android.view.ViewTreeObserver.OnPreDrawListener observer = () -> {
                    if (expected.test(activity)) rendered.countDown();
                    return true;
                };
                root.getViewTreeObserver().addOnPreDrawListener(observer);
                remove.set(() -> {
                    if (root.getViewTreeObserver().isAlive()) root.getViewTreeObserver().removeOnPreDrawListener(observer);
                });
                root.invalidate();
            });
            assertTrue("Expected Keyholder cards/status were not rendered",
                    rendered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        } finally { InstrumentationRegistry.getInstrumentation().runOnMainSync(remove.get()); }
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
