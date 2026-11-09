package com.subhub.app.people;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.OwnedUiIntegrationFixture;
import com.subhub.app.R;
import com.subhub.app.remote.RemoteUnpairedUiFixture;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.util.PageScrollView;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/** Real PersonActivity refresh/resume/recreation and target guard; no production peer is queried. */
public final class PersonRefreshAndroidTest {
    @Rule public final OwnedUiIntegrationFixture integration = new OwnedUiIntegrationFixture();
    @Rule public final com.subhub.app.UiAutomationStateRule automation = new com.subhub.app.UiAutomationStateRule();
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final PersonId id = new PersonId("remote_" + "e".repeat(64) + "_refresh");
    private final AtomicReference<PersonSnapshot> current = new AtomicReference<>();
    private final AtomicBoolean blockNext = new AtomicBoolean();
    private final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    private RemoteUnpairedUiFixture remote;

    @Before public void setup() throws Exception {
        InstrumentationRegistry.getInstrumentation().getUiAutomation();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        remote = new RemoteUnpairedUiFixture(context);
        assertTrue(new SettingsRepository(context).preferences().edit().putString(DeviceRolePreferences.KEY_MODE,
                DeviceRolePreferences.Mode.DOM_ONLY.name()).commit());
        assertTrue(ControllerPinManager.setPin(context, "2468")); ControllerPinManager.enterDomMode();
        current.set(snapshot(1, "Sub"));
        People.installRemoteRepository(new PersonRepository() {
            public List<PersonId> people() { return List.of(id); }
            public PersonSnapshot load(PersonId requested) {
                assertEquals(id, requested);
                if (blockNext.compareAndSet(true, false)) {
                    entered.countDown();
                    try { assertTrue(release.await(8, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                }
                return current.get();
            }
        });
        People.installConnectionUi(new ConnectionUi() {
            public void openPairing(Activity activity) { fail("Unexpected pairing"); }
            public void openConnection(Activity activity, PersonId target) { }
            public boolean bindSessionControls(Activity activity, LinearLayout parent, PersonSnapshot person) {
                EditText draft = new EditText(activity); draft.setTag("retained_duration_draft"); parent.addView(draft);
                parent.setFocusableInTouchMode(true); parent.requestFocus();
                TextView longContent = new TextView(activity); longContent.setText("Synthetic session detail\n".repeat(35));
                parent.addView(longContent); return true;
            }
        });
    }

    @After public void cleanup() throws Exception {
        release.countDown();
        integration.drainReaders();
        if (remote != null) remote.close();
    }

    @Test public void telemetryAndDeferredRefreshKeepTheProfileDraftAndDeliveryTimeScroll() throws Exception {
        try (ActivityScenario<PersonActivity> scenario = launch()) {
            AtomicReference<PersonActivity> host = host(scenario); awaitLoaded(host);
            AtomicReference<View> avatar = new AtomicReference<>(), activity = new AtomicReference<>();
            AtomicReference<EditText> draft = new AtomicReference<>();
            scenario.onActivity(a -> {
                avatar.set(tag(a, "person_avatar")); activity.set(tag(a, "activity_preview:" + id.value()));
                draft.set((EditText) tag(a, "retained_duration_draft")); draft.get().setText("73");
                scroll(a).scrollTo(0, 320); assertEquals(320, scroll(a).getScrollY());
            });
            current.set(snapshot(999, "Sub")); blockNext.set(true);
            scenario.onActivity(PersonActivity::refresh);
            assertTrue(entered.await(8, TimeUnit.SECONDS));
            scenario.onActivity(a -> {
                assertSame(avatar.get(), tag(a, "person_avatar"));
                assertSame(draft.get(), tag(a, "retained_duration_draft"));
            });
            int afterGesture = swipeAndSettle(host.get());
            assertTrue("Native swipe must move the populated page while its read is pending", afterGesture > 320);
            release.countDown(); awaitLoaded(host);
            scenario.onActivity(a -> {
                assertSame(avatar.get(), tag(a, "person_avatar")); assertSame(activity.get(), tag(a, "activity_preview:" + id.value()));
                assertSame(draft.get(), tag(a, "retained_duration_draft")); assertEquals("73", draft.get().getText().toString());
                assertEquals(afterGesture, scroll(a).getScrollY());
            });
            for (int repeat = 0; repeat < 3; repeat++) {
                scenario.onActivity(PersonActivity::refresh); awaitLoaded(host);
                scenario.onActivity(a -> { assertSame(draft.get(), tag(a, "retained_duration_draft")); assertEquals(afterGesture, scroll(a).getScrollY()); });
            }
            scenario.moveToState(Lifecycle.State.CREATED); scenario.moveToState(Lifecycle.State.RESUMED); awaitLoaded(host);
            scenario.onActivity(a -> { assertSame(draft.get(), tag(a, "retained_duration_draft")); assertEquals(afterGesture, scroll(a).getScrollY()); });
        }
    }

    @Test public void changedIdentityRecreationAndBackRetainPositionButRoleRevocationClosesPage() throws Exception {
        try (ActivityScenario<PersonActivity> scenario = launch()) {
            AtomicReference<PersonActivity> host = host(scenario); awaitLoaded(host);
            scenario.onActivity(a -> scroll(a).scrollTo(0, 400));
            current.set(snapshot(2, "Updated Sub")); scenario.onActivity(PersonActivity::refresh); awaitLoaded(host);
            scenario.onActivity(a -> {
                assertEquals("Updated Sub", ((TextView) tag(a, "person_name")).getText().toString());
                assertEquals(400, scroll(a).getScrollY());
                tag(a, "person_tab:connection").performClick();
            });
            awaitLoaded(host);
            scenario.onActivity(a -> a.getOnBackPressedDispatcher().onBackPressed()); awaitLoaded(host);
            scenario.onActivity(a -> assertEquals(400, scroll(a).getScrollY()));
            scenario.recreate(); scenario.onActivity(host::set); awaitLoaded(host);
            scenario.onActivity(a -> assertEquals(400, scroll(a).getScrollY()));
            scenario.onActivity(a -> { ControllerPinManager.enterSubMode(); a.refresh(); assertTrue(a.isFinishing()); });
        }
    }

    @Test public void retainedLocalDurationRefreshesOnReturnWithoutLosingAnIncompleteDraft() throws Exception {
        android.content.SharedPreferences prefs = context.getSharedPreferences("subhub_service_duration_ui", 0);
        java.util.Map<String, ?> before = new java.util.LinkedHashMap<>(prefs.getAll());
        assertTrue(new SettingsRepository(context).preferences().edit().putString(DeviceRolePreferences.KEY_MODE,
                DeviceRolePreferences.Mode.BOTH.name()).commit());
        assertTrue(prefs.edit().putLong("duration", -2).putLong("minimum", 3_600_000L)
                .putLong("maximum", 86_400_000L).putBoolean("hidden", false).commit());
        try (ActivityScenario<PersonActivity> scenario = ActivityScenario.launch(new Intent(context, PersonActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value()))) {
            AtomicReference<PersonActivity> host = host(scenario); awaitLoaded(host);
            AtomicReference<com.subhub.app.commitment.ServiceDurationView> retained = new AtomicReference<>();
            scenario.onActivity(a -> {
                retained.set((com.subhub.app.commitment.ServiceDurationView) tag(a, "person_duration:local"));
                assertNotNull(retained.get());
                ((EditText) retained.get().findViewById(R.id.service_duration_min_hours)).setText("");
                a.refresh();
            });
            awaitLoaded(host);
            scenario.onActivity(a -> assertEquals("", ((EditText) retained.get()
                    .findViewById(R.id.service_duration_min_hours)).getText().toString()));
            // Finish the draft, then change the source while this exact Activity is stopped.
            scenario.onActivity(a -> ((EditText) retained.get().findViewById(R.id.service_duration_min_hours)).setText("1"));
            scenario.moveToState(Lifecycle.State.CREATED);
            assertTrue(prefs.edit().putLong("duration", 86_400_000L).putLong("minimum", 7_200_000L)
                    .putLong("maximum", 18_000_000L).putBoolean("hidden", true).commit());
            scenario.moveToState(Lifecycle.State.RESUMED); awaitLoaded(host);
            scenario.onActivity(a -> {
                assertSame(retained.get(), tag(a, "person_duration:local"));
                assertTrue(retained.get().findViewById(R.id.commitment_timer_24h).isSelected());
                assertEquals("2", ((EditText) retained.get().findViewById(R.id.service_duration_min_hours)).getText().toString());
                assertEquals("5", ((EditText) retained.get().findViewById(R.id.service_duration_max_hours)).getText().toString());
                ((com.subhub.app.util.StateToggle) retained.get().findViewById(R.id.service_duration_hide)).setChecked(false);
                assertEquals(86_400_000L, prefs.getLong("duration", 0));
                assertEquals(7_200_000L, prefs.getLong("minimum", 0));
                assertEquals(18_000_000L, prefs.getLong("maximum", 0));
            });
        } finally {
            integration.drainReaders();
            com.subhub.app.settings.SharedPreferenceTestRestore.restore(prefs, before);
        }
    }

    private static int swipeAndSettle(PersonActivity activity) throws Exception {
        android.graphics.Rect bounds = new android.graphics.Rect();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertTrue(scroll(activity).getGlobalVisibleRect(bounds));
            android.graphics.Rect nav = new android.graphics.Rect();
            if (activity.findViewById(R.id.bottom_navigation).getGlobalVisibleRect(nav)) bounds.bottom = nav.top;
        });
        assertTrue(androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).swipe(
                bounds.centerX(), bounds.top + bounds.height() * 3 / 4,
                bounds.centerX(), bounds.top + bounds.height() / 4, 30));
        java.util.concurrent.atomic.AtomicInteger last = new java.util.concurrent.atomic.AtomicInteger(-1);
        java.util.concurrent.atomic.AtomicInteger stable = new java.util.concurrent.atomic.AtomicInteger();
        await(() -> {
            int position = scroll(activity).getScrollY();
            if (last.getAndSet(position) == position) return stable.incrementAndGet() >= 5;
            stable.set(0); return false;
        });
        return last.get();
    }

    private ActivityScenario<PersonActivity> launch() {
        return ActivityScenario.launch(new Intent(context, PersonActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID, id.value()));
    }
    private static AtomicReference<PersonActivity> host(ActivityScenario<PersonActivity> scenario) {
        AtomicReference<PersonActivity> host = new AtomicReference<>(); scenario.onActivity(host::set); return host;
    }
    private void awaitLoaded(AtomicReference<PersonActivity> host) throws Exception {
        await(() -> host.get() != null && !host.get().data.isPending("person") && !host.get().data.isPending("person-observe")
                && host.get().body.getChildCount() > 0 && !scroll(host.get()).isLayoutRequested());
    }
    private static View tag(Activity activity, String tag) { return activity.findViewById(android.R.id.content).findViewWithTag(tag); }
    private static PageScrollView scroll(Activity activity) { return activity.findViewById(R.id.role_page_scroll); }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = SystemClock.uptimeMillis() + 8000; AtomicBoolean result = new AtomicBoolean();
        do {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> result.set(condition.getAsBoolean()));
            if (result.get()) return;
            SystemClock.sleep(20);
        } while (SystemClock.uptimeMillis() < end);
        fail("Person refresh did not settle");
    }
    private PersonSnapshot snapshot(long telemetry, String name) {
        return new PersonSnapshot(id, name, "Fixture phone", "Contract", "Until released",
                PersonSnapshot.Connection.CONNECTED, PersonSnapshot.Freshness.CURRENT, telemetry,
                EnumSet.of(PersonSnapshot.Capability.VIEW_CONTRACT, PersonSnapshot.Capability.VIEW_STATS,
                        PersonSnapshot.Capability.MANAGE_CONNECTION), Collections.emptyList(), Collections.emptyList(),
                "EUR", telemetry, telemetry, 0, 1, true)
                .withProfile(new ProfileData(name, "Synthetic biography. ".repeat(20), ""), Collections.emptyMap());
    }
}
