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
import java.util.List;
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
    @Rule public final org.junit.rules.TestRule integration = isolatedIntegration();
    private boolean readersDrained;
    private android.app.Application observedApplication;
    private final java.util.Set<com.subhub.app.util.AsyncUiScope> ownedReaders =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final android.app.Application.ActivityLifecycleCallbacks readerObservation = new android.app.Application.ActivityLifecycleCallbacks() {
        public void onActivityStarted(android.app.Activity activity) {
            if (activity instanceof androidx.appcompat.app.AppCompatActivity) synchronized (ownedReaders) {
                ownedReaders.add(com.subhub.app.util.AsyncUiScope.forPage((androidx.appcompat.app.AppCompatActivity) activity));
            }
        }
        public void onActivityCreated(android.app.Activity activity, android.os.Bundle state) { }
        public void onActivityResumed(android.app.Activity activity) { }
        public void onActivityPaused(android.app.Activity activity) { }
        public void onActivityStopped(android.app.Activity activity) { }
        public void onActivitySaveInstanceState(android.app.Activity activity, android.os.Bundle state) { }
        public void onActivityDestroyed(android.app.Activity activity) { }
    };
    private org.junit.rules.TestRule isolatedIntegration() {
        return (base, description) -> new org.junit.runners.model.Statement() {
            @Override public void evaluate() throws Throwable {
                KeyholderPresentationAndroidTest.IntegrationFixture original = new KeyholderPresentationAndroidTest.IntegrationFixture();
                invokeIntegration(original, "before");
                Throwable failure = null;
                try { base.evaluate(); } catch (Throwable problem) { failure = problem; }
                if (readersDrained) {
                    try { invokeIntegration(original, "after"); }
                    catch (Throwable problem) { if (failure == null) failure = problem; else failure.addSuppressed(problem); }
                } else if (failure == null) failure = new AssertionError("Keep fixture hooks isolated until every owned reader drains");
                if (failure != null) throw failure;
            }
        };
    }
    private static void invokeIntegration(KeyholderPresentationAndroidTest.IntegrationFixture original, String phase) throws Throwable {
        java.lang.reflect.Method method = KeyholderPresentationAndroidTest.IntegrationFixture.class.getDeclaredMethod(phase);
        method.setAccessible(true);
        try { method.invoke(original); }
        catch (java.lang.reflect.InvocationTargetException failed) { throw failed.getCause(); }
    }
    private void observeOwnedPages() {
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        observedApplication = (android.app.Application) instrumentation.getTargetContext().getApplicationContext();
        instrumentation.runOnMainSync(() -> observedApplication.registerActivityLifecycleCallbacks(readerObservation));
    }
    private void awaitOwnedReaders() throws Exception {
        assertNotEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper());
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> {
            if (observedApplication != null) observedApplication.unregisterActivityLifecycleCallbacks(readerObservation);
        });
        instrumentation.waitForIdleSync();
        java.lang.reflect.Field worker = com.subhub.app.util.AsyncUiScope.class.getDeclaredField("worker");
        java.lang.reflect.Field serial = com.subhub.app.util.AsyncUiScope.class.getDeclaredField("serialWorker");
        worker.setAccessible(true); serial.setAccessible(true);
        java.util.List<com.subhub.app.util.AsyncUiScope> readers;
        synchronized (ownedReaders) { readers = new java.util.ArrayList<>(ownedReaders); }
        for (com.subhub.app.util.AsyncUiScope reader : readers) {
            for (java.util.concurrent.ExecutorService executor : new java.util.concurrent.ExecutorService[]{
                    (java.util.concurrent.ExecutorService) worker.get(reader),
                    (java.util.concurrent.ExecutorService) serial.get(reader)}) if (executor != null) {
                assertTrue("Closed page must stop its owned executor", executor.isShutdown());
                assertTrue("Page readers must drain before restoring shared hooks",
                        executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
            }
        }
        instrumentation.waitForIdleSync();
    }

    @Rule public final UiAutomationStateRule automation = new UiAutomationStateRule();
    private static final String DOM = repeat('a'), SUB = repeat('b'), SESSION = repeat('c');
    private Context target, authority;
    private com.subhub.app.activity.ActivityRepository beforeActivity;
    private String prefix, database, generation;
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
        observeOwnedPages();
        // The rule connects UiAutomation before any Activity or target View is created.
        drainMain();
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        beforeActivity = com.subhub.app.activity.ActivitySources.repository();
        com.subhub.app.activity.ActivitySources.install(new SessionCoordinatorActivityIsolation());
        pinApplication = ControllerPinManager.class.getDeclaredField("application");
        pinApplication.setAccessible(true); originalPinApplication = pinApplication.get(null);
        assertTrue(RemoteSessionRuntime.applicationEnabled());
        if (!ControllerPinManager.hasCredentials(target)) {
            // IntegrationFixture restores an initially unkeyed guest; never replace existing credentials.
            assertTrue(ControllerPinManager.setPin(target,
                    InstrumentationRegistry.getArguments().getString("controller_pin", "2468")));
        }
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
        People.installKeyholder(feature.keyholder);
        assertTrue(authority.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                .putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.DOM_ONLY.name()).commit());
        assertTrue(ControllerPinManager.setPin(authority, "7351"));
        assertTrue(ControllerPinManager.verify(authority, "7351"));
        // IntegrationFixture restores this exact typed global preference snapshot and People hooks.
        assertTrue(target.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                .putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.DOM_ONLY.name()).commit());
        long now = System.currentTimeMillis();
        PairingInvite invite = PairingInvite.create(DOM, "fixture", now);
        generation = invite.id;
        store.saveInvite(invite);
        store.bindInvite(generation, SUB, invite.tokenHash(), now);
        store.approveInvite(generation, SUB, "Synthetic Sub", now);
        store.activate(SUB, generation);
        peer = store.peer(SUB, generation);
        store.putMetadata(SUB, generation, "session_control_version", "1");
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
        awaitOwnedReaders();
        // Scenarios close before @After; drain only the fixture endpoint, never the application one.
        if (feature != null) shutdownOwnedFeature(); else if (store != null) store.close();
        if (selected) synchronized (RemoteFeature.class) {
            assertSame("Selected endpoint changed before restoration", feature, singleton.get(null));
            singleton.set(null, previous); selected = false;
        }
        if (previous != null) {
            assertSame(previous, RemoteFeature.get(target)); assertAccepting(previous);
        }
        if (beforeActivity != null) com.subhub.app.activity.ActivitySources.install(beforeActivity);
        if (pinApplication != null) pinApplication.set(null, originalPinApplication);
        readersDrained = true;
        if (database != null && target != null) assertTrue(target.deleteDatabase(database));
        if (prefix != null) for (String name : privatePreferences)
            assertTrue(target.getSharedPreferences(prefix + name, 0).edit().clear().commit());
        // IntegrationFixture subsequently restores the original provider, controller mode and preferences.
    }

    private void shutdownOwnedFeature() throws Exception {
        feature.shutdownForTest();
        for (String name : new String[]{"timer", "pairingDeadlineTimer", "acceptor", "operations", "incomingSessions", "sessions"}) {
            java.lang.reflect.Field field = RemoteFeature.class.getDeclaredField(name); field.setAccessible(true);
            java.util.concurrent.ExecutorService executor = (java.util.concurrent.ExecutorService) field.get(feature);
            assertTrue("Fixture endpoint must stop its owned " + name, executor.isShutdown());
            assertTrue("Fixture endpoint must drain before restoring the application endpoint",
                    executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        java.lang.reflect.Field worker = RemoteActivityRepository.class.getDeclaredField("worker"); worker.setAccessible(true);
        java.util.concurrent.ExecutorService activityWorker = (java.util.concurrent.ExecutorService) worker.get(feature.activity);
        assertTrue(activityWorker.isShutdown());
        assertTrue(activityWorker.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    /** Isolate unrelated feed reads while retaining the actual session coordinator and journal. */
    private static final class SessionCoordinatorActivityIsolation implements com.subhub.app.activity.ActivityRepository {
        public boolean supports(com.subhub.app.people.PersonId person) { return false; }
        public Page load(String before, int limit) { throw new AssertionError("Unexpected activity read"); }
        public Page locate(String key, int limit) { throw new AssertionError("Unexpected activity read"); }
        public List<com.subhub.app.activity.ActivityEntry> recent(com.subhub.app.people.PersonId person, int limit) { throw new AssertionError("Unexpected activity read"); }
        public int unreadCount() { return 0; }
        public void markRead(List<String> keys) { throw new AssertionError("Unexpected activity write"); }
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
            assertTrue(RemoteSessionRuntime.applicationEnabled());
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
            await(() -> durationIs(page, 1440));
            assertNotEquals(changedView.minted.sourceWitness, coordinator.snapshot(peer).sourceWitness);
            changedView.release(); awaitReviewDrained(firstScope);
            assertEquals(0, gateway.confirms.get()); assertNoIntent();
            // The real coordinator independently refuses the same stale minted review.
            assertThrows(SecurityException.class, () -> coordinator.confirm(changedView.minted));
            assertNoIntent();

            // Change the durable target after all View comparisons, at the actual confirm boundary.
            await(() -> enabled(page, "remote_session_stop"));
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

            await(() -> enabled(page, "remote_session_stop"));
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
            assertTrue(RemoteSessionRuntime.applicationEnabled());
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
        RemoteSessionControlState state = new RemoteSessionControlState(SUB, DOM, generation, now.wall,
                revision, new RemoteSessionCommands.Duration(duration, 1_800_000, 86_400_000, false),
                true, "", session, readiness, reason, false, true);
        store.putMetadata(SUB, generation, RemoteSessionControlState.METADATA,
                RemoteProtocol.canonical(new RemoteSessionControlState.Received(state, now).json()));
    }
    private void assertQueued(RemoteSessionRuntime.Review review) throws Exception {
        assertEquals(1, journalCount());
        RemoteSessionRuntime.Record row = store.sessionControl(SUB, generation,
                RemoteSessionRuntime.Direction.OUTGOING, review.request.id);
        assertNotNull(row); assertEquals(review.request.id, row.request.id);
        assertEquals(review.requestDigest, row.request.digest());
        assertEquals(DOM, row.request.sender); assertEquals(SUB, row.request.recipient);
        assertEquals(generation, row.request.generation); assertEquals(SESSION, row.request.sessionIdentity);
        assertEquals(RemoteSessionCommands.Action.STOP, row.request.action); assertNull(row.request.service);
        assertEquals(RemoteSessionCommands.State.QUEUED, row.result.state); assertNull(row.result.proof);
        assertEquals(Long.toString(review.request.sequence), store.metadata(SUB, generation, "session_control_OUTGOING_floor", "0"));
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
        assertEquals("0", store.metadata(SUB, generation, "session_control_OUTGOING_floor", "0"));
        assertTrue(store.pendingSessionControls(SUB, generation, RemoteSessionRuntime.Direction.OUTGOING, 10).isEmpty());
        assertNull(store.latestCommand(SUB, generation));
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
