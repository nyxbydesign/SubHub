package com.subhub.app.remote;

import static org.junit.Assert.*;
import static com.subhub.app.remote.RemoteSessionCommands.*;
import static com.subhub.app.remote.RemoteSessionRuntime.Direction.INCOMING;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.appmode.SessionExpiryAlarms;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.security.SubFeaturePolicy;
import com.subhub.app.service.ScreenshotAccessibilityService;
import com.subhub.app.settings.CaptureMethod;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.stats.RemoteSessionStatsFixture;
import com.subhub.app.stats.StatsRepository;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real private preferences/SQLite; admin and binding are synthetic, never OS permission acceptance. */
@RunWith(AndroidJUnit4.class)
public final class RemoteSessionNativeControllerAndroidTest {
    private static final String DOM = String.join("", Collections.nCopies(64, "a"));
    private static final String SUB = String.join("", Collections.nCopies(64, "b")), GEN = "controller_generation";
    private Context target, context;
    private String prefix, database;
    private RemoteStore store, independent;
    private RemoteSessionNative nativeSession;
    private StatsRepository stats;
    private Field running;
    private BindingFixture binding;
    private RemoteSessionNativeController controller;
    private final AtomicBoolean refuseStats = new AtomicBoolean();
    private Runnable beforeNativeCommit;
    private boolean crashFixture;
    private int armingWrites;
    private final RemoteSessionReadiness.Source source = new RemoteSessionReadiness.Source() {
        public RemoteSessionClock.Sample clock() { return RemoteSessionClock.observe(context); }
        public int sdk() { return 35; }
        public boolean adminActive() { return true; }
        public boolean accessibilityEnabled() { return true; }
        public boolean accessibilityBound() { return true; }
        public CaptureMethod captureMethod() { return CaptureMethod.APP_MODE; }
    };
    @Before public void setup() throws Exception {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String storage = InstrumentationRegistry.getArguments().getString("storage_id", "");
        crashFixture = storage.matches("controllercrash_[a-z0-9]{32}");
        assertTrue("Invalid crash storage identity", storage.isEmpty() || crashFixture);
        prefix = (crashFixture ? storage : "native-controller-" + UUID.randomUUID()) + "-"; database = prefix + "db";
        context = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                SharedPreferences saved = target.getSharedPreferences(prefix + name, mode);
                return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                        new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (method.getName().equals("edit")) return editor(saved.edit(), name);
                    try { return method.invoke(saved, args); } catch (InvocationTargetException failed) { throw failed.getCause(); }
                });
            }
        };
        binding = new BindingFixture(); running = binding.field; binding.install();
        store = new RemoteStore(context, database); independent = new RemoteStore(context, database);
        boolean initialize = store.peer(DOM, GEN) == null;
        if (initialize) store.getWritableDatabase().execSQL("INSERT INTO peers(peer,generation,name,local_role,state) VALUES(?,?,'Synthetic','SUB','ACTIVE')", new Object[]{DOM, GEN});
        independent.getReadableDatabase();
        if (initialize) {
            store.putMetadata(DOM, GEN, "session_control_version", "1");
            store.putMetadata(DOM, GEN, "applied_revision", "7");
            main().edit().putBoolean(HardcoreModeManager.KEY_REQUESTED, true)
                    .putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.SUB_ONLY.name()).commit();
            RemoteSessionClock.Sample now = RemoteSessionClock.observe(context);
            context.getSharedPreferences(SubFeaturePolicy.STORE, 0).edit().putBoolean("hardcore_locally_consented", true)
                    .putString("hardcore_start_accepted_review", "synthetic_review")
                    .putString("hardcore_start_proof_v1", RemoteProtocol.canonical(new RemoteSessionRuntime.HardcoreConsent(DOM, SUB, GEN, now.wall).json())).commit();
            synchronized (SubFeaturePolicy.LOCK) {
                store.getWritableDatabase().beginTransaction();
                try {
                    store.acknowledgeHardcoreStartReview(DOM, GEN, "synthetic_review");
                    store.getWritableDatabase().setTransactionSuccessful();
                } finally { store.getWritableDatabase().endTransaction(); }
            }
        }
        stats = RemoteSessionStatsFixture.create(context);
        nativeSession = RemoteSessionNative.installFixture(context, () -> stats);
        controller = new RemoteSessionNativeController(context, store, SUB, source);
    }
    private SharedPreferences.Editor editor(SharedPreferences.Editor saved, String name) {
        AtomicBoolean nativeWrite = new AtomicBoolean();
        return (SharedPreferences.Editor) Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
            if (SettingsRepository.PREFERENCES_NAME.equals(name) && method.getName().equals("putBoolean")
                    && args[0].equals(AppModeManager.KEY_ARMED) && Boolean.TRUE.equals(args[1])) armingWrites++;
            if (method.getName().equals("putString") && args[0].equals(RemoteSessionNative.MAIN_KEY)) nativeWrite.set(true);
            if (method.getName().equals("commit")) {
                if (StatsRepository.PREFS_NAME.equals(name) && refuseStats.get()) return false;
                if (SettingsRepository.PREFERENCES_NAME.equals(name) && nativeWrite.get() && beforeNativeCommit != null) {
                    Runnable action = beforeNativeCommit; beforeNativeCommit = null; action.run();
                }
            }
            Object value;
            try { value = method.invoke(saved, args); } catch (InvocationTargetException failed) { throw failed.getCause(); }
            return value == saved ? proxy : value;
        });
    }
    @After public void cleanup() throws Exception {
        try (BindingFixture ignored = binding) { cleanupOwnedState(); }
    }
    private void cleanupOwnedState() throws Exception {
        beforeNativeCommit = null; refuseStats.set(false);
        synchronized (RuntimeSessionIdentity.LOCK) {
            if (context != null) {
                String pact = main().getString("commitment_session_identity", "");
                main().edit().remove("commitment_session_identity").commit();
                SessionExpiryAlarms.update(context, SessionExpiryAlarms.Kind.PACT, pact, "", 0);
            }
            RuntimeSessionIdentity.changed();
        }
        SessionExpiryAlarms.whenIdle().get(15, TimeUnit.SECONDS);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        if (nativeSession != null) nativeSession.close();
        if (store != null) store.close(); if (independent != null) independent.close();
        if (database != null) target.deleteDatabase(database);
        if (prefix != null) for (String name : new String[]{SettingsRepository.PREFERENCES_NAME, StatsRepository.PREFS_NAME,
                SubFeaturePolicy.STORE, RemoteConfiguration.DURATION_PREFS, com.subhub.app.penance.PenanceManager.PREFS_NAME,
                "subhub_hardcore_auto_pay", "controller_crash_fixture"}) target.getSharedPreferences(prefix + name, 0).edit().clear().commit();
        if (stats != null) stats.sessionStamp();
    }
    private static final class BindingFixture implements AutoCloseable {
        final Field field;
        final boolean original;
        boolean owned;
        BindingFixture() throws Exception {
            field = ScreenshotAccessibilityService.class.getDeclaredField("running"); field.setAccessible(true);
            original = field.getBoolean(null);
        }
        void install() throws Exception {
            assertFalse("A pre-existing Accessibility binding is not owned by this fixture", original);
            field.setBoolean(null, true); owned = true;
        }
        @Override public void close() throws Exception {
            if (owned) { field.setBoolean(null, original); owned = false; }
        }
    }
    private SharedPreferences main() { return context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0); }
    private Request start(boolean random) throws Exception {
        RemoteSessionClock.Sample now = RemoteSessionClock.observe(context);
        if (random) context.getSharedPreferences(RemoteConfiguration.DURATION_PREFS, 0).edit()
                .putLong("duration", -2).putLong("minimum", 1800000).putLong("maximum", 3600000).commit();
        Request request = Request.start(UUID.randomUUID().toString(), DOM, SUB, GEN, 1, 7, now.wall, now.wall + 120000,
                RuntimeSessionIdentity.observe(context, now).idleFence, new Duration(random ? -2 : -1, random ? 1800000 : 3600000,
                        random ? 3600000 : 86400000, false));
        store.rememberSessionControl(DOM, GEN, INCOMING, request, now); return request;
    }
    private RemoteSessionRuntime.Record advance(Request request) throws Exception { return controller.advance(DOM, GEN, request.id); }
    private int count() { return context.getSharedPreferences(StatsRepository.PREFS_NAME, 0).getInt("sessions_count", 0); }

    @Test public void durablePreparationIsVisibleToIndependentHandleBeforeNativeCommitAndReplayIsImmutable() throws Exception {
        Request request = start(true); AtomicBoolean witnessed = new AtomicBoolean();
        beforeNativeCommit = () -> {
            try {
                assertEquals(State.APPLYING, independent.sessionControl(DOM, GEN, INCOMING, request.id).result.state);
                JSONObject prepared = independent.nativeSessionPreparation(DOM, GEN, request.id);
                assertNotNull(prepared); assertNull(nativeSession.marker()); witnessed.set(true);
            } catch (Exception failure) { throw new AssertionError(failure); }
        };
        RemoteSessionRuntime.Record first = advance(request); assertTrue(witnessed.get());
        assertEquals(State.APPLIED, first.result.state); assertNotNull(first.result.proof); assertEquals(1, count());
        long chosen = first.result.proof.selectedDuration; assertTrue(chosen >= 1800000 && chosen <= 3600000);
        assertEquals(chosen, main().getLong("commitment_duration", 0));
        assertEquals(RemoteProtocol.canonical(first.result.json()), RemoteProtocol.canonical(advance(request).result.json()));
        assertEquals(1, count()); assertFalse(RemoteSessionRuntime.applicationEnabled());
    }
    @Test public void preparationFailureCannotApplyNativeState() throws Exception {
        Request request = start(false);
        store.getWritableDatabase().execSQL("CREATE TRIGGER refuse_prepare BEFORE UPDATE OF state ON session_control_journal "
                + "WHEN NEW.state='APPLYING' BEGIN SELECT RAISE(ABORT,'synthetic preparation failure'); END");
        assertThrows(SQLiteException.class, () -> advance(request));
        assertNull(nativeSession.marker()); assertFalse(main().getBoolean(AppModeManager.KEY_ARMED, false)); assertEquals(0, count());
        assertEquals(State.ACKNOWLEDGED, store.sessionControl(DOM, GEN, INCOMING, request.id).result.state);
        assertNull(store.nativeSessionPreparation(DOM, GEN, request.id));
    }
    @Test public void terminalDatabaseFailureRecoversExistingMarkerWithoutChoosingOrCountingAgain() throws Exception {
        Request request = start(true);
        store.getWritableDatabase().execSQL("CREATE TRIGGER refuse_terminal BEFORE UPDATE OF state ON session_control_journal "
                + "WHEN NEW.state='APPLIED' BEGIN SELECT RAISE(ABORT,'synthetic terminal failure'); END");
        assertThrows(SQLiteException.class, () -> advance(request));
        assertEquals(State.APPLYING, store.sessionControl(DOM, GEN, INCOMING, request.id).result.state);
        assertNotNull(nativeSession.marker()); assertEquals(1, count()); long chosen = nativeSession.marker().prepared.selectedDuration;
        store.getWritableDatabase().execSQL("DROP TRIGGER refuse_terminal");
        assertEquals(chosen, advance(request).result.proof.selectedDuration); assertEquals(1, count());
    }
    @Test public void failedStatisticsCannotSupplySuccessAndRetryUsesSamePreparation() throws Exception {
        Request request = start(true); refuseStats.set(true);
        assertEquals(State.APPLYING, advance(request).result.state); assertEquals(0, count());
        assertTrue(main().getBoolean(AppModeManager.KEY_ARMED, false));
        String saved = RemoteProtocol.canonical(store.nativeSessionPreparation(DOM, GEN, request.id));
        refuseStats.set(false); assertEquals(State.APPLIED, advance(request).result.state); assertEquals(1, count());
        assertEquals(saved, RemoteProtocol.canonical(store.nativeSessionPreparation(DOM, GEN, request.id)));
    }
    @Test public void priorProcessPreparationWithoutMarkerCannotArm() throws Exception {
        Request request = start(false); RemoteSessionClock.Sample now = RemoteSessionClock.local();
        JSONObject prepared = RemoteSessionNative.prepare(request, now).json().put("process", UUID.randomUUID().toString());
        store.prepareNativeSessionControl(DOM, GEN, request.id, prepared, now);
        assertEquals(Reason.PROCESS_RESTARTED, advance(request).result.reason);
        assertNull(nativeSession.marker()); assertFalse(main().getBoolean(AppModeManager.KEY_ARMED, false)); assertEquals(0, count());
    }
    @Test public void existingEffectRecoveryCannotResetAReplacementSession() throws Exception {
        Request request = start(false); refuseStats.set(true); assertEquals(State.APPLYING, advance(request).result.state);
        new AppModeManager(context).setArmed(false); refuseStats.set(false); assertTrue(RemoteSessionNative.reconcile(context));
        new AppModeManager(context).setArmed(true); String replacement = stats.activeSessionIdentity();
        assertFalse(replacement.isEmpty()); assertEquals(Reason.SESSION_CHANGED, advance(request).result.reason);
        assertEquals(replacement, stats.activeSessionIdentity()); assertTrue(main().getBoolean(AppModeManager.KEY_ARMED, false));
    }
    @Test public void consentConfigurationAndCapabilityAreReadFromCurrentReceiverStores() throws Exception {
        Request request = start(false); store.putMetadata(DOM, GEN, "session_control_version", "0");
        assertEquals(Reason.UNSUPPORTED, advance(request).result.reason); assertEquals(0, count());
        store.putMetadata(DOM, GEN, "session_control_version", "1");
        Request next = Request.start(UUID.randomUUID().toString(), DOM, SUB, GEN, 2, 7, request.issuedAt, request.expiresAt,
                request.idleFence, request.service);
        store.rememberSessionControl(DOM, GEN, INCOMING, next, RemoteSessionClock.local());
        context.getSharedPreferences(SubFeaturePolicy.STORE, 0).edit().remove("hardcore_start_proof_v1").commit();
        assertEquals(Reason.CONSENT_REQUIRED, advance(next).result.reason); assertEquals(0, count());
    }
    @Test public void preparedStartRechecksAppliedRevisionAndIdleFenceBeforeAnyEffect() throws Exception {
        Request request = start(false); RemoteSessionClock.Sample now = RemoteSessionClock.local();
        store.prepareNativeSessionControl(DOM, GEN, request.id, RemoteSessionNative.prepare(request, now).json(), now);
        store.putMetadata(DOM, GEN, "applied_revision", "8");
        assertEquals(Reason.REVISION_CHANGED, advance(request).result.reason); assertNull(nativeSession.marker()); assertEquals(0, count());
    }
    @Test public void stopDoesNotRequireHardcoreOrReadinessAndCannotStopALaterSessionOnReplay() throws Exception {
        new AppModeManager(context).setArmed(true); String original = stats.activeSessionIdentity(); assertFalse(original.isEmpty());
        RemoteSessionClock.Sample now = RemoteSessionClock.local();
        Request request = Request.stop(UUID.randomUUID().toString(), DOM, SUB, GEN, 1, 7, now.wall, now.wall + 120000,
                RuntimeSessionIdentity.observe(context, now).identity);
        store.rememberSessionControl(DOM, GEN, INCOMING, request, now);
        main().edit().putBoolean(HardcoreModeManager.KEY_REQUESTED, false).commit(); running.setBoolean(null, false);
        assertEquals(State.APPLIED, advance(request).result.state); assertFalse(main().getBoolean(AppModeManager.KEY_ARMED, true));
        assertEquals("", stats.activeSessionIdentity()); assertEquals(1, stats.getSessionHistory().size());
        new AppModeManager(context).setArmed(true); String replacement = stats.activeSessionIdentity();
        assertNotEquals(original, replacement); assertEquals(State.APPLIED, advance(request).result.state);
        assertEquals(replacement, stats.activeSessionIdentity()); assertTrue(main().getBoolean(AppModeManager.KEY_ARMED, false));
    }
    @Test public void independentRevokeCannotCrossNativeApplicationTransaction() throws Exception {
        Request request = start(false); CountDownLatch atNative = new CountDownLatch(1), release = new CountDownLatch(1), revoking = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        beforeNativeCommit = () -> {
            atNative.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
        };
        try {
            Future<RemoteSessionRuntime.Record> applied = workers.submit(() -> advance(request));
            assertTrue(atNative.await(5, TimeUnit.SECONDS));
            Future<?> revoked = workers.submit(() -> { revoking.countDown(); independent.revoke(DOM, GEN); });
            assertTrue(revoking.await(5, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class, () -> revoked.get(100, TimeUnit.MILLISECONDS));
            release.countDown(); assertEquals(State.APPLIED, applied.get(10, TimeUnit.SECONDS).result.state); revoked.get(10, TimeUnit.SECONDS);
            assertEquals(RemoteStore.State.REVOKED, store.peer(DOM, GEN).state); assertEquals(1, count());
            assertThrows(SecurityException.class, () -> advance(request));
        } finally { release.countDown(); workers.shutdownNow(); }
    }
    @Test public void outerTransactionIsRefusedBeforeNativePreparation() throws Exception {
        Request request = start(false); store.getWritableDatabase().beginTransaction();
        try { assertThrows(IllegalStateException.class, () -> advance(request)); }
        finally { store.getWritableDatabase().endTransaction(); }
        assertNull(nativeSession.marker()); assertNull(store.nativeSessionPreparation(DOM, GEN, request.id));
    }

    @Test public void refusedBindingFixtureDoesNotClearTheBindingItDidNotOwn() throws Exception {
        assertTrue(running.getBoolean(null));
        try (BindingFixture refused = new BindingFixture()) {
            assertThrows(AssertionError.class, refused::install);
        }
        assertTrue(running.getBoolean(null));
    }
    @Test public void ownedBindingRestoresOriginalFlagDespiteAnIntentionalCleanupFailure() throws Exception {
        assertTrue(running.getBoolean(null));
        assertThrows(IllegalStateException.class, () -> {
            try (BindingFixture ignored = binding) { throw new IllegalStateException("Synthetic cleanup failure"); }
        });
        assertFalse(running.getBoolean(null));
        binding.close(); assertFalse(running.getBoolean(null));
    }

    private Request stopLocalSession() throws Exception {
        new AppModeManager(context).setArmed(true);
        RemoteSessionClock.Sample now = RemoteSessionClock.observe(context);
        Request request = Request.stop(UUID.randomUUID().toString(), DOM, SUB, GEN, 1, 7, now.wall, now.wall + 120000,
                RuntimeSessionIdentity.observe(context, now).identity);
        store.rememberSessionControl(DOM, GEN, INCOMING, request, now);
        main().edit().putBoolean(HardcoreModeManager.KEY_REQUESTED, false).commit(); return request;
    }
    @Test public void stopUsesOnlyClockWhenUnrelatedStartProvidersThrow() throws Exception {
        Request request = stopLocalSession();
        RemoteSessionReadiness.Source unavailableStart = new RemoteSessionReadiness.Source() {
            public RemoteSessionClock.Sample clock() { return RemoteSessionClock.observe(context); }
            public int sdk() { throw new SecurityException("SDK provider must not be queried for Stop"); }
            public boolean adminActive() { throw new SecurityException("Admin unavailable"); }
            public boolean accessibilityEnabled() { throw new SecurityException("Accessibility unavailable"); }
            public boolean accessibilityBound() { throw new SecurityException("Binding unavailable"); }
            public CaptureMethod captureMethod() { throw new SecurityException("Mode unavailable"); }
        };
        RemoteSessionRuntime.Record result = new RemoteSessionNativeController(context, store, SUB, unavailableStart).advance(DOM, GEN, request.id);
        assertEquals(State.APPLIED, result.result.state); assertNotNull(result.result.proof); assertFalse(result.result.proof.active);
        assertFalse(main().getBoolean(AppModeManager.KEY_ARMED, true)); assertEquals("", stats.activeSessionIdentity());
    }
    @Test public void stopCannotApplyOrClaimSuccessWhenItsClockProviderFails() throws Exception {
        Request request = stopLocalSession();
        RemoteSessionReadiness.Source unavailableClock = new RemoteSessionReadiness.Source() {
            public RemoteSessionClock.Sample clock() { throw new SecurityException("Clock unavailable"); }
            public int sdk() { return 35; }
            public boolean adminActive() { return false; }
            public boolean accessibilityEnabled() { return false; }
            public boolean accessibilityBound() { return false; }
            public CaptureMethod captureMethod() { return CaptureMethod.APP_MODE; }
        };
        RemoteSessionRuntime.Record result = new RemoteSessionNativeController(context, store, SUB, unavailableClock).advance(DOM, GEN, request.id);
        assertEquals(State.REJECTED, result.result.state); assertEquals(Reason.CLOCK_CHANGED, result.result.reason); assertNull(result.result.proof);
        assertTrue(main().getBoolean(AppModeManager.KEY_ARMED, false)); assertFalse(stats.activeSessionIdentity().isEmpty());
        assertNull(nativeSession.stopMarker());
    }

    @Test public void seedControllerAtDurableCrashBoundary() throws Exception {
        org.junit.Assume.assumeTrue("Explicit host crash selection required", crashFixture);
        String phase = InstrumentationRegistry.getArguments().getString("controller_boundary", "");
        assertTrue(phase.equals("prepared") || phase.equals("main") || phase.equals("terminal"));
        Request request = start(true);
        if (phase.equals("prepared")) {
            RemoteSessionClock.Sample now = RemoteSessionClock.local();
            store.prepareNativeSessionControl(DOM, GEN, request.id, RemoteSessionNative.prepare(request, now).json(), now);
            assertNull(nativeSession.marker()); assertEquals(0, count());
        } else if (phase.equals("main")) {
            refuseStats.set(true); assertEquals(State.APPLYING, advance(request).result.state);
            assertNotNull(nativeSession.marker()); assertFalse(nativeSession.marker().statisticsReady); assertEquals(0, count());
        } else {
            store.getWritableDatabase().execSQL("CREATE TRIGGER refuse_terminal BEFORE UPDATE OF state ON session_control_journal "
                    + "WHEN NEW.state='APPLIED' BEGIN SELECT RAISE(ABORT,'synthetic terminal failure'); END");
            assertThrows(SQLiteException.class, () -> advance(request));
            assertTrue(nativeSession.marker().statisticsReady); assertEquals(1, count());
        }
        JSONObject prepared = store.nativeSessionPreparation(DOM, GEN, request.id);
        assertNotNull(prepared); assertEquals(State.APPLYING, independent.sessionControl(DOM, GEN, INCOMING, request.id).result.state);
        assertTrue(context.getSharedPreferences("controller_crash_fixture", 0).edit()
                .putString("request", RemoteProtocol.canonical(request.json())).putString("prepared", RemoteProtocol.canonical(prepared))
                .putString("phase", phase).putInt("pid", android.os.Process.myPid()).commit());
        android.os.Bundle boundary = new android.os.Bundle(); boundary.putString("native_boundary", "ready");
        boundary.putInt("native_pid", android.os.Process.myPid()); boundary.putString("controller_boundary", phase);
        InstrumentationRegistry.getInstrumentation().sendStatus(42, boundary);
        if (!new CountDownLatch(1).await(2, TimeUnit.MINUTES)) fail("Host did not terminate the live committed fixture");
    }

    @Test public void recoverControllerInNewProcessWithoutFreshArmingOrRedrawingDuration() throws Exception {
        org.junit.Assume.assumeTrue("Explicit host crash selection required", crashFixture);
        SharedPreferences saved = context.getSharedPreferences("controller_crash_fixture", 0);
        int oldPid = saved.getInt("pid", -1); assertTrue(oldPid > 0); assertNotEquals(oldPid, android.os.Process.myPid());
        String phase = saved.getString("phase", "");
        Request request = Request.parse(new JSONObject(saved.getString("request", "")));
        RemoteSessionNative.PreparedStart prepared = RemoteSessionNative.PreparedStart.parse(new JSONObject(saved.getString("prepared", "")));
        assertFalse(RemoteSessionNative.currentProcess(prepared.process));
        assertEquals(RemoteProtocol.canonical(prepared.json()), RemoteProtocol.canonical(store.nativeSessionPreparation(DOM, GEN, request.id)));
        if (phase.equals("terminal")) store.getWritableDatabase().execSQL("DROP TRIGGER refuse_terminal");
        RemoteSessionRuntime.Record result = advance(request);
        if (phase.equals("prepared")) {
            assertEquals(State.REJECTED, result.result.state); assertEquals(Reason.PROCESS_RESTARTED, result.result.reason);
            assertNull(nativeSession.marker()); assertEquals(0, count()); assertFalse(main().getBoolean(AppModeManager.KEY_ARMED, false));
        } else {
            assertEquals(State.APPLIED, result.result.state); assertEquals(1, count());
            assertEquals(prepared.selectedDuration, result.result.proof.selectedDuration);
            assertEquals(prepared.statistics.identity, stats.activeSessionIdentity());
            assertEquals(prepared.pactIdentity, main().getString("commitment_session_identity", ""));
            assertEquals(prepared.endsElapsed, main().getLong("commitment_ends_elapsed", 0));
            assertEquals(prepared.endsAt, main().getLong("commitment_ends_at", 0));
            assertEquals(RemoteProtocol.canonical(result.result.json()), RemoteProtocol.canonical(advance(request).result.json()));
            assertEquals(1, count());
        }
        assertEquals("Recovery must never write a fresh armed=true intent", 0, armingWrites);
        android.os.Bundle recovered = new android.os.Bundle(); recovered.putInt("recovered_pid", android.os.Process.myPid());
        InstrumentationRegistry.getInstrumentation().sendStatus(43, recovered);
    }

    @Test public void seedStoppedNativeStateWithAnUnacknowledgedHistoricalCaptureReference() throws Exception {
        org.junit.Assume.assumeTrue("Explicit host crash selection required", crashFixture);
        Request request = stopLocalSession(); RemoteSessionClock.Sample now = RemoteSessionClock.local();
        RemoteSessionNative.PreparedStop original = nativeSession.prepareStop(request, now);
        // Fault injection: a persisted historical capture reference without any closure acknowledgment.
        // The native preferences/statistics Stop itself is real; this fixture never acquired projection.
        RemoteSessionNative.PreparedStop missing = RemoteSessionNative.PreparedStop.parse(original.json().put("capture", UUID.randomUUID().toString()));
        store.prepareNativeSessionControl(DOM, GEN, request.id, missing.json(), now);
        assertTrue(nativeSession.commitPreparedStop(original, now));
        assertTrue(main().edit().putString(RemoteSessionNative.STOP_MAIN_KEY,
                RemoteProtocol.canonical(new RemoteSessionNative.StopMarker(missing, true).json())).commit());
        assertNull(nativeSession.stopCapture(missing)); assertFalse(RuntimeSessionIdentity.observe(context, now).active);
        assertTrue(context.getSharedPreferences("controller_crash_fixture", 0).edit()
                .putString("request", RemoteProtocol.canonical(request.json())).putString("prepared", RemoteProtocol.canonical(missing.json()))
                .putString("phase", "stop_missing_capture").putInt("pid", android.os.Process.myPid()).commit());
        android.os.Bundle boundary = new android.os.Bundle(); boundary.putString("native_boundary", "ready");
        boundary.putInt("native_pid", android.os.Process.myPid()); boundary.putString("controller_boundary", "stop_missing_capture");
        InstrumentationRegistry.getInstrumentation().sendStatus(42, boundary);
        if (!new CountDownLatch(1).await(2, TimeUnit.MINUTES)) fail("Host did not terminate the committed Stop fixture");
    }
    @Test public void restartedStopCannotInventHistoricalCaptureClosureAcknowledgment() throws Exception {
        org.junit.Assume.assumeTrue("Explicit host crash selection required", crashFixture);
        SharedPreferences saved = context.getSharedPreferences("controller_crash_fixture", 0);
        assertNotEquals(saved.getInt("pid", -1), android.os.Process.myPid()); assertTrue(saved.getInt("pid", -1) > 0);
        Request request = Request.parse(new JSONObject(saved.getString("request", "")));
        RemoteSessionNative.PreparedStop prepared = RemoteSessionNative.PreparedStop.parse(new JSONObject(saved.getString("prepared", "")));
        assertFalse(RemoteSessionNative.currentProcess(prepared.process)); assertFalse(prepared.capture.isEmpty());
        assertNull(nativeSession.stopCapture(prepared)); assertTrue(nativeSession.stopMarker().ready);
        RemoteSessionRuntime.Record result = advance(request);
        assertEquals(State.FAILED, result.result.state); assertEquals(Reason.APPLICATION_FAILED, result.result.reason); assertNull(result.result.proof);
        assertEquals(State.FAILED, advance(request).result.state); assertEquals(0, armingWrites);
        assertFalse(RuntimeSessionIdentity.observe(context, RemoteSessionClock.local()).active);
        assertEquals("", stats.activeSessionIdentity()); assertEquals(1, stats.getSessionHistory().size());
        android.os.Bundle recovered = new android.os.Bundle(); recovered.putInt("recovered_pid", android.os.Process.myPid());
        InstrumentationRegistry.getInstrumentation().sendStatus(43, recovered);
    }
}
