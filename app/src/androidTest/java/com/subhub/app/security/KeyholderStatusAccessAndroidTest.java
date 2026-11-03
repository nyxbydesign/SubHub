package com.subhub.app.security;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static com.subhub.app.NativeUiActions.revealAboveNavigation;
import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.people.ConnectionUi;
import com.subhub.app.people.People;
import com.subhub.app.people.PersonId;
import com.subhub.app.remote.RemoteUnpairedUiFixture;
import com.subhub.app.settings.GlobalSettingsActivity;
import com.subhub.app.settings.SettingsRepository;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExternalResource;
import org.junit.rules.RuleChain;
import org.junit.runner.RunWith;

/** UI routing/status only. Provider responses do not prove remote authentication or live Sub enforcement. */
@RunWith(AndroidJUnit4.class)
public final class KeyholderStatusAccessAndroidTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private final AtomicInteger connections = new AtomicInteger();

    @Rule public final RuleChain ownership = RuleChain.outerRule(new ExternalResource() {
        @Override protected void before() {
            assertEquals("Status tests must not prepare or authenticate controller credentials", "false",
                    InstrumentationRegistry.getArguments().getString("controller_fixture"));
            assertFalse("Use an owned UI guest without an active runtime", RuntimeSessionIdentity.active(context));
            assertFalse("The UI fixture must begin with the workspace locked", ControllerPinManager.isDomWorkspaceUnlocked());
        }
    }).around(new com.subhub.app.UiAutomationStateRule())
            .around(new KeyholderPresentationAndroidTest.IntegrationFixture());

    @Before public void configureUi() {
        assertTrue(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                .putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.BOTH.name()).commit());
        People.installConnectionUi(new ConnectionUi() {
            @Override public void openPairing(Activity activity) { fail("Inspection must not begin pairing"); }
            @Override public void openConnection(Activity activity, PersonId person) {
                assertEquals(PersonId.LOCAL, person); connections.incrementAndGet();
            }
        });
    }

    @Test public void settingsOpensKeyholderBeforeBlockedStatusFinishes() throws Exception {
        ObservedProvider provider = new ObservedProvider(KeyholderProvider.State.UNAVAILABLE, "", true, false);
        People.installKeyholder(provider);
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(AuthenticatorActivity.class.getName(), null, false);
        AuthenticatorActivity opened = null;
        try (RemoteUnpairedUiFixture backend = new RemoteUnpairedUiFixture(context);
                ActivityScenario<GlobalSettingsActivity> settings = ActivityScenario.launch(GlobalSettingsActivity.class)) {
            try {
                assertTrue(provider.entered.await(5, TimeUnit.SECONDS));
                settings.onActivity(activity -> assertTrue(activity.findViewById(R.id.settings_connections_security).isEnabled()));
                onView(withId(R.id.settings_connections_security)).perform(revealAboveNavigation(), click());
                opened = (AuthenticatorActivity) instrumentation.waitForMonitorWithTimeout(monitor, 5000);
                assertNotNull("Status inspection must not wait for the provider", opened);
                AuthenticatorActivity page = opened;
                instrumentation.runOnMainSync(() -> assertReadonly(page, R.string.keyholder_unavailable));
                provider.release.countDown();
                awaitStatus(page, R.string.keyholder_unavailable);
                assertEquals(0, provider.mainReads.get());
                assertEquals(0, connections.get());
                assertFalse(ControllerPinManager.isDomModeActive());
                assertFalse(ControllerPinManager.isDomWorkspaceUnlocked());
                assertFalse(RuntimeSessionIdentity.active(context));
            } finally {
                provider.release.countDown();
                if (opened != null) {
                    AuthenticatorActivity page = opened;
                    instrumentation.runOnMainSync(page::finish);
                    instrumentation.waitForIdleSync();
                }
            }
        } finally {
            provider.release.countDown();
            instrumentation.removeMonitor(monitor);
        }
    }

    @Test public void pendingUnavailableAndFailedStatusStayReadonlyAfterRecreation() throws Exception {
        try (RemoteUnpairedUiFixture backend = new RemoteUnpairedUiFixture(context)) {
            for (int attempt = 0; attempt < 3; attempt++) {
                boolean failed = attempt == 2;
                KeyholderProvider.State state = attempt == 0 ? KeyholderProvider.State.PENDING : KeyholderProvider.State.UNAVAILABLE;
                ObservedProvider provider = new ObservedProvider(state, "Fixture Dom", false, failed);
                People.installKeyholder(provider);
                int expected = state == KeyholderProvider.State.PENDING ? R.string.keyholder_pairing_in_progress : R.string.keyholder_unavailable;
                try (ActivityScenario<AuthenticatorActivity> page = ActivityScenario.launch(AuthenticatorActivity.class)) {
                    assertTrue(provider.entered.await(5, TimeUnit.SECONDS));
                    awaitStatus(page, expected, failed ? "" : "Fixture Dom");
                    page.onActivity(activity -> assertReadonly(activity, expected));
                    page.recreate();
                    awaitStatus(page, expected, failed ? "" : "Fixture Dom");
                    page.onActivity(activity -> assertReadonly(activity, expected));
                    if (!failed) {
                        int before = connections.get();
                        onView(withId(R.id.settings_connection)).perform(revealAboveNavigation(), click());
                        assertEquals(before + 1, connections.get());
                    }
                    assertEquals(0, provider.mainReads.get());
                    assertFalse(ControllerPinManager.isDomModeActive());
                    assertFalse(ControllerPinManager.isDomWorkspaceUnlocked());
                } finally { provider.release.countDown(); }
            }
        }
    }

    @Test public void knownSubConnectionKeepsSettingsShortcutWithoutAuthenticating() throws Exception {
        try (RemoteUnpairedUiFixture backend = new RemoteUnpairedUiFixture(context)) {
            for (KeyholderProvider.State state : new KeyholderProvider.State[] {KeyholderProvider.State.PENDING, KeyholderProvider.State.READY}) {
                ObservedProvider provider = new ObservedProvider(state, "Fixture Dom", false, false);
                People.installKeyholder(provider);
                try (ActivityScenario<GlobalSettingsActivity> settings = ActivityScenario.launch(GlobalSettingsActivity.class)) {
                    long deadline = SystemClock.uptimeMillis() + 5000;
                    AtomicBoolean ready = new AtomicBoolean();
                    while (!ready.get() && SystemClock.uptimeMillis() < deadline) {
                        settings.onActivity(activity -> {
                            TextView link = activity.findViewById(R.id.settings_connections_security);
                            ready.set(link.isEnabled() && activity.getString(R.string.role_connection).contentEquals(link.getText()));
                        });
                        if (!ready.get()) Thread.sleep(10);
                    }
                    assertTrue("Known Sub connection shortcut must remain available", ready.get());
                    int before = connections.get();
                    onView(withId(R.id.settings_connections_security)).perform(revealAboveNavigation(), click());
                    assertEquals(before + 1, connections.get());
                    assertEquals(0, provider.mainReads.get());
                    assertFalse(ControllerPinManager.isDomModeActive());
                    assertFalse(ControllerPinManager.isDomWorkspaceUnlocked());
                } finally { provider.release.countDown(); }
            }
        }
    }

    private void awaitStatus(ActivityScenario<AuthenticatorActivity> page, int expected, String dom) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 5000;
        AtomicBoolean ready = new AtomicBoolean();
        while (!ready.get() && SystemClock.uptimeMillis() < deadline) {
            page.onActivity(activity -> {
                TextView name = activity.findViewById(R.id.keyholder_assigned_dom);
                ready.set(hasStatus(activity, expected) && (dom.isEmpty() ? name == null : name != null && dom.contentEquals(name.getText())));
            });
            if (!ready.get()) Thread.sleep(10);
        }
        assertTrue("Expected Keyholder status was not rendered", ready.get());
    }

    private void awaitStatus(AuthenticatorActivity page, int expected) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 5000;
        AtomicBoolean ready = new AtomicBoolean();
        while (!ready.get() && SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync(() -> ready.set(hasStatus(page, expected)));
            if (!ready.get()) Thread.sleep(10);
        }
        assertTrue("Expected Keyholder status was not rendered", ready.get());
    }

    private static boolean hasStatus(Activity page, int expected) {
        TextView status = page.findViewById(R.id.keyholder_setup_status);
        return status != null && page.getString(expected).contentEquals(status.getText());
    }

    private static void assertReadonly(AuthenticatorActivity page, int expected) {
        assertTrue(hasStatus(page, expected));
        assertNull(page.findViewById(R.id.keyholder_pin_change_button));
        assertNull(page.findViewById(R.id.keyholder_remove_pin));
        assertNull(page.findViewById(R.id.keyholder_remove_authenticator));
        assertNull(page.findViewById(R.id.keyholder_pair_device));
        assertFalse(page.findViewById(R.id.button_edit_lock).isShown());
    }

    private static final class ObservedProvider implements KeyholderProvider {
        final State state;
        final String name;
        final boolean blocked, failed;
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger mainReads = new AtomicInteger();
        ObservedProvider(State state, String name, boolean blocked, boolean failed) {
            this.state = state; this.name = name; this.blocked = blocked; this.failed = failed;
        }
        @Override public State state() {
            assertWorker(); entered.countDown();
            if (blocked) try {
                assertTrue("Status observer was not released", release.await(15, TimeUnit.SECONDS));
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt(); throw new IllegalStateException("Status observation cancelled", stopped);
            }
            if (failed) throw new IllegalStateException("Controlled unavailable status");
            return state;
        }
        @Override public String assignedDomName() { assertWorker(); return name; }
        @Override public ControllerAuthenticator.Result verifyCode(String code) {
            throw new AssertionError("Status inspection must not authenticate");
        }
        @Override public boolean withLocalAuthority(java.util.function.BooleanSupplier action) { return false; }
        private void assertWorker() {
            if (Looper.myLooper() == Looper.getMainLooper()) mainReads.incrementAndGet();
            assertNotEquals("Credential observations must stay off Main", Looper.getMainLooper(), Looper.myLooper());
        }
    }
}
