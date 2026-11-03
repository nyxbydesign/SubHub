package com.subhub.app.remote;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.NativeUiActions;
import com.subhub.app.R;
import com.subhub.app.UiAutomationStateRule;
import com.subhub.app.configuration.LegacyPackMigration;
import com.subhub.app.people.ConnectionUi;
import com.subhub.app.people.People;
import com.subhub.app.people.PersonActivity;
import com.subhub.app.people.PersonId;
import com.subhub.app.people.PersonRoutes;
import com.subhub.app.people.PersonSnapshot;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.KeyholderPresentationAndroidTest;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.util.AsyncUiScope;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Actual Views, coordinator-minted Reviews and SQLite. Receiver advice alone is synthetic;
 * this does not qualify a bound APP_MODE source, OS grants, activation or two-device behavior. */
@RunWith(AndroidJUnit4.class)
public final class RemoteSessionCoordinatorControlsAndroidTest {
    @Rule public final KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new KeyholderPresentationAndroidTest.IntegrationFixture();
    @Rule public final UiAutomationStateRule automation = new UiAutomationStateRule();
    private static final String DOM = repeat('a'), SUB = repeat('b'), SESSION = repeat('c');
    private static final String GENERATION = "controls_coordinator_generation";
    private Context target, authority;
    private String prefix, database;
    private final Set<String> privatePreferences = ConcurrentHashMap.newKeySet();
    private RemoteFeature feature, previous;
    private RemoteStore store;
    private RemoteStore.Peer peer;
    private RemoteSessionControlCoordinator coordinator;
    private Gateway gateway;
    private Field singleton, pinApplication;
    private Object originalPinApplication;
    private boolean selected;
    private final AtomicBoolean dirtyDuration = new AtomicBoolean();

    @Before public void setup() throws Exception {
        // The rule connects UiAutomation before any Activity or target View is created.
        drainMain();
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        pinApplication = ControllerPinManager.class.getDeclaredField("application");
        pinApplication.setAccessible(true); originalPinApplication = pinApplication.get(null);
        assertFalse(RemoteSessionRuntime.applicationEnabled());
        assertTrue("Keep the runner's ordinary controller fixture enabled",
                ControllerPinManager.hasCredentials(target));
        assertTrue("Application startup must have completed recovery", LegacyPackMigration.isReady(target));
        previous = RemoteFeature.get(target); assertAccepting(previous);
        prefix = "session-controls-coordinator-" + UUID.randomUUID() + "-";
        database = prefix + "db";
        authority = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                privatePreferences.add(name);
                return target.getSharedPreferences(prefix + name, mode);
            }
            @Override public File getNoBackupFilesDir() {
                return new File(target.getNoBackupFilesDir(), prefix);
            }
        };
        // All migration/PIN writes are private; the UI uses the runner's existing credentials.
        assertTrue(LegacyPackMigration.recoverAndMigrate(authority));
        store = new RemoteStore(authority, database);
        feature = new RemoteFeature(authority, store); assertTrue(feature.recoveryReady());
        store.getWritableDatabase().execSQL(
                "INSERT INTO peers(peer,generation,name,local_role,state) VALUES(?,?,'Synthetic Sub','DOM','ACTIVE')",
                new Object[]{SUB, GENERATION});
        peer = store.peer(SUB, GENERATION);
        store.putMetadata(SUB, GENERATION, "session_control_version", "1");
        People.installKeyholder(feature.keyholder);
        assertTrue(authority.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                .putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.DOM_ONLY.name()).commit());
        assertTrue(ControllerPinManager.setPin(authority, "7351"));
        assertTrue(ControllerPinManager.verify(authority, "7351"));
        // IntegrationFixture restores this exact typed global preference snapshot and People hooks.
        assertTrue(target.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                .putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.DOM_ONLY.name()).commit());
        coordinator = new RemoteSessionControlCoordinator(authority, store, DOM);
        gateway = new Gateway();
        syntheticAdvice(SESSION, 7, 3_600_000, RemoteSessionRuntime.Readiness.NEEDS_SUB_ACTION,
                RemoteSessionCommands.Reason.READINESS_REQUIRED);
        singleton = RemoteFeature.class.getDeclaredField("instance"); singleton.setAccessible(true);
        synchronized (RemoteFeature.class) {
            assertSame("Application endpoint changed during setup", previous, singleton.get(null));
            singleton.set(null, feature); selected = true;
        }
        People.installRemoteRepository(new RemotePersonRepository(target, feature));
        People.installRemoteAuthority(feature::controlsLocal);
        People.installConnectionUi(new ConnectionUi() {
            public void openPairing(Activity activity) { throw new AssertionError("Pairing is outside this fixture"); }
            public void openConnection(Activity activity, PersonId id) { throw new AssertionError("Connection is outside this fixture"); }
            @Override public boolean bindSessionControls(Activity activity, LinearLayout parent, PersonSnapshot person) {
                assertEquals(RemotePersonRepository.id(peer), person.id);
                new RemoteSessionControls(activity, parent, person, dirtyDuration::get, gateway).bind();
                return true;
            }
        });
    }

    @After public void cleanup() throws Exception {
        // Scenarios close before @After; retire only the fixture endpoint, never the application one.
        try {
            if (selected) synchronized (RemoteFeature.class) {
                assertSame("Selected endpoint changed before restoration", feature, singleton.get(null));
                singleton.set(null, previous); selected = false;
            }
        } finally {
            try {
                if (feature != null) feature.shutdownForTest(); else if (store != null) store.close();
            } finally {
                try {
                    if (database != null && target != null) assertTrue(target.deleteDatabase(database));
                    if (prefix != null) for (String name : privatePreferences)
                        assertTrue(target.getSharedPreferences(prefix + name, 0).edit().clear().commit());
                    if (previous != null) {
                        assertSame(previous, RemoteFeature.get(target)); assertAccepting(previous);
                    }
                } finally {
                    if (pinApplication != null) pinApplication.set(null, originalPinApplication);
                }
            }
        }
        // IntegrationFixture subsequently restores the original provider, controller mode and preferences.
    }

    @Test public void nativeStopQueuesExactReviewAcrossSyntheticStartAdviceChangesAndRecreation() throws Exception {
        dirtyDuration.set(true);
        Attempt attempt = gateway.holdReview();
        try (ActivityScenario<PersonActivity> page = open()) {
            await(() -> enabled(page, "remote_session_stop"));
            RemoteSessionRuntime.Snapshot clicked = coordinator.snapshot(peer);
            page.onActivity(a -> assertFalse(tag(a, "remote_session_start").isEnabled()));
            assertEquals(0, gateway.reviews.get()); assertEquals(0, journalCount());
            tap("remote_session_stop");
            await(() -> attempt.minted != null);
            assertEquals(0, gateway.confirms.get()); assertEquals(0, journalCount());
            assertEquals(clicked.sourceWitness, attempt.minted.sourceWitness);

            // Synthetic outcomes of failing Start providers/configuration; authority itself is unchanged.
            syntheticAdvice(SESSION, 99, 86_400_000, RemoteSessionRuntime.Readiness.UNAVAILABLE,
                    RemoteSessionCommands.Reason.HARDCORE_REQUIRED);
            tap("remote_session_refresh");
            await(() -> durationIs(page, 1440));
            RemoteSessionRuntime.Snapshot current = coordinator.snapshot(peer);
            assertTrue(current.canStop); assertFalse(current.canStart);
            assertEquals(clicked.sourceWitness, current.sourceWitness);
            assertEquals(current.sourceWitness, attempt.minted.sourceWitness);
            attempt.release();
            await(() -> hasText(page, "remote_session_status", target.getString(R.string.remote_session_queued)));
            assertEquals(1, gateway.reviews.get()); assertEquals(1, gateway.confirms.get());
            assertSame(attempt.minted, gateway.confirmed);
            assertQueued(attempt.minted);

            page.recreate();
            await(() -> hasText(page, "remote_session_status", target.getString(R.string.remote_session_queued)));
            drainMain();
            assertEquals(1, gateway.reviews.get()); assertEquals(1, gateway.confirms.get());
            assertQueued(attempt.minted);
            assertFalse(RemoteSessionRuntime.applicationEnabled());
        }
    }

    @Test public void nativeStaleSessionRefusesAtViewAndCoordinatorAndLifecycleCannotImplicitlyQueue() throws Exception {
        Attempt changedView = gateway.holdReview();
        try (ActivityScenario<PersonActivity> page = open()) {
            await(() -> enabled(page, "remote_session_stop"));
            AsyncUiScope firstScope = scope(page);
            tap("remote_session_stop"); await(() -> changedView.minted != null);
            syntheticAdvice(repeat('d'), 7, 86_400_000, RemoteSessionRuntime.Readiness.NEEDS_SUB_ACTION,
                    RemoteSessionCommands.Reason.READINESS_REQUIRED);
            tap("remote_session_refresh"); await(() -> durationIs(page, 1440));
            assertNotEquals(changedView.minted.sourceWitness, coordinator.snapshot(peer).sourceWitness);
            changedView.release(); awaitReviewDrained(firstScope);
            assertEquals(0, gateway.confirms.get()); assertNoIntent();
            // The real coordinator independently refuses the same stale minted review.
            assertThrows(SecurityException.class, () -> coordinator.confirm(changedView.minted));
            assertNoIntent();

            // Change the durable target after all View comparisons, at the actual confirm boundary.
            tap("remote_session_refresh"); await(() -> enabled(page, "remote_session_stop"));
            Attempt changedBackend = gateway.holdReview();
            gateway.beforeConfirm = () -> syntheticAdvice(repeat('e'), 7, 86_400_000,
                    RemoteSessionRuntime.Readiness.NEEDS_SUB_ACTION, RemoteSessionCommands.Reason.READINESS_REQUIRED);
            tap("remote_session_stop"); await(() -> changedBackend.minted != null);
            changedBackend.release();
            await(() -> gateway.confirmFailure != null);
            await(() -> hasText(page, "remote_session_status", target.getString(R.string.remote_unknown)));
            assertTrue(gateway.confirmFailure instanceof SecurityException);
            assertSame(changedBackend.minted, gateway.confirmed);
            assertEquals(1, gateway.confirms.get()); assertNoIntent();

            tap("remote_session_refresh"); await(() -> enabled(page, "remote_session_stop"));
            Attempt recreated = gateway.holdReview();
            AsyncUiScope oldScope = scope(page);
            tap("remote_session_stop"); await(() -> recreated.minted != null);
            page.recreate(); recreated.release();
            awaitReviewDrained(oldScope);
            await(() -> enabled(page, "remote_session_stop"));
            assertEquals(3, gateway.reviews.get()); assertEquals(1, gateway.confirms.get()); assertNoIntent();

            Attempt locked = gateway.holdReview();
            AsyncUiScope newScope = scope(page);
            tap("remote_session_stop"); await(() -> locked.minted != null);
            page.onActivity(a -> ControllerPinManager.lockNow());
            locked.release(); awaitReviewDrained(newScope);
            assertFalse(ControllerPinManager.isSessionUnlocked());
            assertEquals(4, gateway.reviews.get()); assertEquals(1, gateway.confirms.get()); assertNoIntent();
            assertFalse(RemoteSessionRuntime.applicationEnabled());
        }
    }

    private final class Gateway implements RemoteSessionControls.Gateway {
        final AtomicInteger reviews = new AtomicInteger(), confirms = new AtomicInteger();
        volatile Attempt attempt;
        volatile RemoteSessionRuntime.Review confirmed;
        volatile Exception confirmFailure;
        volatile CheckedAction beforeConfirm;
        Attempt holdReview() { Attempt value = new Attempt(); attempt = value; return value; }
        public CompletableFuture<RemoteSessionRuntime.Snapshot> snapshot() {
            try { return CompletableFuture.completedFuture(coordinator.snapshot(peer)); }
            catch (Exception error) { return failed(error); }
        }
        public CompletableFuture<RemoteSessionRuntime.Review> review(RemoteSessionCommands.Action action) {
            try {
                Attempt value = attempt; assertNotNull("Each native click owns one held delivery", value);
                RemoteSessionRuntime.Review minted = coordinator.review(peer, action);
                reviews.incrementAndGet(); value.minted = minted;
                return value.delivery;
            } catch (Exception error) { return failed(error); }
        }
        public CompletableFuture<String> confirm(RemoteSessionRuntime.Review review) {
            confirmed = review; confirms.incrementAndGet();
            return feature.submit(() -> {
                try {
                    CheckedAction change = beforeConfirm; beforeConfirm = null;
                    if (change != null) change.run();
                    return coordinator.confirm(review);
                } catch (Exception error) { confirmFailure = error; throw error; }
            });
        }
    }

    private static final class Attempt {
        final CompletableFuture<RemoteSessionRuntime.Review> delivery = new CompletableFuture<>();
        volatile RemoteSessionRuntime.Review minted;
        void release() { assertNotNull(minted); assertTrue(delivery.complete(minted)); }
    }
    private interface CheckedAction { void run() throws Exception; }
    private static <T> CompletableFuture<T> failed(Exception error) {
        CompletableFuture<T> result = new CompletableFuture<>(); result.completeExceptionally(error); return result;
    }
    private void syntheticAdvice(String session, long revision, long duration,
            RemoteSessionRuntime.Readiness readiness, RemoteSessionCommands.Reason reason) throws Exception {
        RemoteSessionClock.Sample now = RemoteSessionClock.observe(authority);
        RemoteSessionControlState state = new RemoteSessionControlState(SUB, DOM, GENERATION, now.wall,
                revision, new RemoteSessionCommands.Duration(duration, 1_800_000, 86_400_000, false),
                true, "", session, readiness, reason, false, true);
        store.putMetadata(SUB, GENERATION, RemoteSessionControlState.METADATA,
                RemoteProtocol.canonical(new RemoteSessionControlState.Received(state, now).json()));
    }
    private void assertQueued(RemoteSessionRuntime.Review review) throws Exception {
        assertEquals(1, journalCount());
        RemoteSessionRuntime.Record row = store.sessionControl(SUB, GENERATION,
                RemoteSessionRuntime.Direction.OUTGOING, review.request.id);
        assertNotNull(row); assertEquals(review.request.id, row.request.id);
        assertEquals(review.requestDigest, row.request.digest());
        assertEquals(DOM, row.request.sender); assertEquals(SUB, row.request.recipient);
        assertEquals(GENERATION, row.request.generation); assertEquals(SESSION, row.request.sessionIdentity);
        assertEquals(RemoteSessionCommands.Action.STOP, row.request.action); assertNull(row.request.service);
        assertEquals(RemoteSessionCommands.State.QUEUED, row.result.state); assertNull(row.result.proof);
        assertEquals(Long.toString(review.request.sequence), store.metadata(SUB, GENERATION, "session_control_OUTGOING_floor", "0"));
        assertEquals(review.request.id, coordinator.snapshot(peer).latestResult.id);
    }
    private int journalCount() {
        synchronized (store) {
            try (Cursor rows = store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM session_control_journal", null)) {
                assertTrue(rows.moveToFirst()); return rows.getInt(0);
            }
        }
    }
    private void assertNoIntent() throws Exception {
        assertEquals(0, journalCount());
        assertEquals("0", store.metadata(SUB, GENERATION, "session_control_OUTGOING_floor", "0"));
        assertTrue(store.pendingSessionControls(SUB, GENERATION, RemoteSessionRuntime.Direction.OUTGOING, 10).isEmpty());
        assertNull(store.latestCommand(SUB, GENERATION));
    }
    private ActivityScenario<PersonActivity> open() {
        return ActivityScenario.launch(new Intent(target, PersonActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, RemotePersonRepository.id(peer).value()));
    }
    private static AsyncUiScope scope(ActivityScenario<PersonActivity> page) {
        AtomicReference<AsyncUiScope> result = new AtomicReference<>();
        page.onActivity(a -> result.set(AsyncUiScope.forPage(a))); return result.get();
    }
    private void awaitReviewDrained(AsyncUiScope scope) throws Exception {
        String key = "session-control:" + RemotePersonRepository.id(peer).value() + ":review";
        await(() -> {
            AtomicBoolean pending = new AtomicBoolean();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> pending.set(
                    scope.isPending(key)));
            return !pending.get();
        });
        drainMain();
    }
    private static View tag(PersonActivity activity, String value) {
        return activity.findViewById(android.R.id.content).findViewWithTag(value);
    }
    private static boolean enabled(ActivityScenario<PersonActivity> page, String value) {
        AtomicBoolean result = new AtomicBoolean();
        page.onActivity(a -> { View view = tag(a, value); result.set(view != null && view.isEnabled()); }); return result.get();
    }
    private static boolean hasText(ActivityScenario<PersonActivity> page, String value, String text) {
        AtomicBoolean result = new AtomicBoolean();
        page.onActivity(a -> {
            View view = tag(a, value); result.set(view instanceof TextView && text.contentEquals(((TextView) view).getText()));
        }); return result.get();
    }
    private boolean durationIs(ActivityScenario<PersonActivity> page, int minutes) {
        return hasText(page, "remote_session_applied_duration", target.getString(R.string.remote_session_applied_duration,
                minutes + " " + target.getString(R.string.remote_minutes)));
    }
    private static void tap(String value) {
        drainMain();
        onView(withTagValue(is(value))).perform(NativeUiActions.revealAboveNavigation(), click());
    }
    private static void drainMain() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    private interface Check { boolean ready() throws Exception; }
    private static void await(Check check) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 10_000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (check.ready()) return;
            Thread.sleep(25);
        }
        assertTrue("Native coordinator composition did not settle", check.ready());
    }
    private static void assertAccepting(RemoteFeature endpoint) throws Exception {
        Field field = RemoteFeature.class.getDeclaredField("sessions"); field.setAccessible(true);
        assertFalse("Application executor must survive the fixture", ((ThreadPoolExecutor) field.get(endpoint)).isShutdown());
    }
    private static String repeat(char character) {
        char[] value = new char[64]; Arrays.fill(value, character); return new String(value);
    }
}
