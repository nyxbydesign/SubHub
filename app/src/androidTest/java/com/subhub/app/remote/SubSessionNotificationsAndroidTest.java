package com.subhub.app.remote;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.appmode.SessionExpiryAlarms;
import com.subhub.app.commitment.CommitmentManager;
import com.subhub.app.notifications.NotificationPreferences;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.stats.RemoteSessionStatsFixture;
import com.subhub.app.stats.StatsRepository;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;

/** Real native Start/expiry persistence and receiving ledger; deliberately no OS notification permission claim. */
@RunWith(AndroidJUnit4.class)
public final class SubSessionNotificationsAndroidTest {
    private static final String DOM = String.join("", Collections.nCopies(64, "a"));
    private static final String SUB = String.join("", Collections.nCopies(64, "b"));
    private Context context, target;
    private String prefix, generation;
    private final Set<String> preferences = new HashSet<>();
    private RemoteStore dom, sub;
    private RemoteSessionNative nativeSession;
    private StatsRepository statistics;
    private RemoteSessionNative.PreparedStart prepared;
    @Before public void prepare() throws Exception {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefix = "sub-session-notification-" + UUID.randomUUID() + "-";
        context = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                preferences.add(prefix + name); return target.getSharedPreferences(prefix + name, mode);
            }
            @Override public Object getSystemService(String name) {
                return Context.NOTIFICATION_SERVICE.equals(name) ? null : super.getSystemService(name);
            }
        };
        statistics = RemoteSessionStatsFixture.create(context);
        nativeSession = RemoteSessionNative.installFixture(context, () -> statistics);
        dom = new RemoteStore(context, prefix + "dom.db"); sub = new RemoteStore(context, prefix + "sub.db");
        PairingInvite invite = PairingInvite.create(DOM, "ticket", 1000); generation = invite.id;
        dom.saveInvite(invite); dom.bindInvite(generation, SUB, invite.tokenHash(), 1001);
        dom.approveInvite(generation, SUB, "Sub", 1001); dom.activate(SUB, generation);
        sub.acceptInvite(invite, "Dom"); sub.activate(DOM, generation);
        RemoteSessionClock.Sample now = RemoteSessionClock.observe(context);
        RemoteSessionCommands.Request request = RemoteSessionCommands.Request.start("owned-start", DOM, SUB, generation,
                1, 0, now.wall, now.wall + 120000, RuntimeSessionIdentity.observe(context, now).idleFence,
                new RemoteSessionCommands.Duration(3600000, 1800000, 3600000, false));
        prepared = RemoteSessionNative.prepare(request, now);
        assertTrue(nativeSession.commitPreparedStart(prepared, RemoteSessionClock.observe(context)));
    }
    private SharedPreferences main() { return context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0); }
    @After public void clean() throws Throwable {
        Throwable failure = null;
        try {
            failure = cleanupStep(failure, () -> InstrumentationRegistry.getInstrumentation().waitForIdleSync());
            Set<String> retired = new java.util.LinkedHashSet<>();
            failure = cleanupStep(failure, () -> {
                synchronized (RuntimeSessionIdentity.LOCK) {
                    if (context != null) {
                        String currentPact = main().getString("commitment_session_identity", "");
                        if (SessionExpiryAlarms.validIdentity(currentPact)) retired.add(currentPact);
                    }
                    if (prepared != null && SessionExpiryAlarms.validIdentity(prepared.pactIdentity)) retired.add(prepared.pactIdentity);
                }
            });
            for (String identity : retired) {
                failure = cleanupStep(failure, () -> {
                    synchronized (RuntimeSessionIdentity.LOCK) {
                        SessionExpiryAlarms.update(context, SessionExpiryAlarms.Kind.PACT, identity, "", 0);
                    }
                    SessionExpiryAlarms.whenIdle().get(5, java.util.concurrent.TimeUnit.SECONDS);
                });
            }
        } finally {
            failure = cleanupStep(failure, () -> { if (nativeSession != null) nativeSession.close(); });
            failure = cleanupStep(failure, () -> { if (dom != null) dom.close(); });
            failure = cleanupStep(failure, () -> { if (sub != null) sub.close(); });
            if (target != null && prefix != null) {
                failure = cleanupStep(failure, () -> target.deleteDatabase(prefix + "dom.db"));
                failure = cleanupStep(failure, () -> target.deleteDatabase(prefix + "sub.db"));
                for (String name : new HashSet<>(preferences))
                    failure = cleanupStep(failure, () -> target.deleteSharedPreferences(name));
            }
        }
        if (failure != null) throw failure;
    }
    private interface CleanupStep { void run() throws Exception; }
    private static Throwable cleanupStep(Throwable first, CleanupStep step) {
        try { step.run(); }
        catch (Throwable failure) {
            if (first == null) return failure;
            if (first != failure) first.addSuppressed(failure);
        }
        return first;
    }
    private long expire() throws Exception {
        RemoteSessionClock.Sample now = RemoteSessionClock.observe(context);
        assertTrue(main().edit().putLong("commitment_ends_at", now.wall - 1)
                .putLong("commitment_ends_elapsed", now.elapsed - 1).putInt("commitment_boot", now.boot).commit());
        assertTrue(CommitmentManager.expireIfDue(context, prepared.pactIdentity));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertFalse(RuntimeSessionIdentity.observe(context, RemoteSessionClock.local()).active);
        return main().getLong("notification_pact_expiry_sequence", 0);
    }
    private RemoteSessionRuntime.Record appliedRecord() throws Exception {
        RemoteSessionCommands.Request request = prepared.request;
        RemoteSessionCommands.Result result = new RemoteSessionCommands.Result(request, RemoteSessionCommands.State.APPLIED,
                RemoteSessionCommands.Reason.NONE, prepared.statistics.startedAt,
                new RemoteSessionCommands.Proof(true, RuntimeSessionIdentity.current(context), prepared.statistics.startedAt,
                        prepared.endsAt, prepared.selectedDuration));
        return new RemoteSessionRuntime.Record(RemoteSessionRuntime.Direction.INCOMING, request, result,
                new RemoteSessionClock.Bounds(1, 1, 1, 2));
    }
    @Test public void appliedSessionReceiptNotifiesOnlySubAndRejectsReplay() throws Exception {
        RemoteSessionRuntime.Record record = appliedRecord();
        RemoteAppliedNotifications.nativeSession(context, sub, DOM, generation, record);
        SharedPreferences receipts = context.getSharedPreferences("local_notification_receipts", 0);
        String key = "SUB:SESSION_STARTED:" + DOM + ":" + generation;
        assertEquals(1, receipts.getLong(key, 0));
        java.util.Map<String, ?> first = new java.util.HashMap<>(receipts.getAll());
        RemoteAppliedNotifications.nativeSession(context, sub, DOM, generation, record);
        RemoteAppliedNotifications.session(context, dom.peer(SUB, generation), record);
        assertEquals(first, receipts.getAll());
        assertEquals("", dom.metadata(SUB, generation, "notification_applied_session", ""));
    }
    @Test public void naturalExpiryConsumesSubStopWithoutCreatingDomLifecycleMetadata() throws Exception {
        long sequence = expire();
        assertEquals(sequence, context.getSharedPreferences("local_notification_receipts", 0)
                .getLong("SUB:SESSION_STOPPED:local-pact-expiry", 0));
        assertFalse(main().contains("notification_pact_expiry_receipt"));
        assertFalse(main().contains("notification_pact_expiry_ready"));
        assertTrue(sub.published(DOM, generation, RemoteProtocol.Stream.RUNTIME).isEmpty());
    }
    @Test public void delayedNativeExpiryClosureRejectsReplacementStartAndStop() throws Exception { delayedExpiryRejectsReplacement(true); }
    @Test public void delayedLocalOnlyExpiryClosureRejectsReplacementStartAndStop() throws Exception { delayedExpiryRejectsReplacement(false); }
    @Test public void localOnlyExpiryWithoutReplacementConsumesItsOwnPersistedIdentity() throws Exception {
        java.util.concurrent.CompletableFuture<Void> done = deferredExpiry(false, () -> true);
        done.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(1, context.getSharedPreferences("local_notification_receipts", 0)
                .getLong("SUB:SESSION_STOPPED:local-pact-expiry", 0));
    }
    private void delayedExpiryRejectsReplacement(boolean nativeStart) throws Exception {
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.CountDownLatch observedPending = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CompletableFuture<Void> done = deferredExpiry(nativeStart, () -> {
            observedPending.countDown(); return closed.get();
        });
        try {
            assertTrue(observedPending.await(5, java.util.concurrent.TimeUnit.SECONDS));
            new AppModeManager(context).setArmed(true);
            assertTrue(RuntimeSessionIdentity.observe(context, RemoteSessionClock.local()).active);
            new AppModeManager(context).setArmed(false);
            assertFalse(RuntimeSessionIdentity.observe(context, RemoteSessionClock.local()).active);
        } finally { closed.set(true); done.get(5, java.util.concurrent.TimeUnit.SECONDS); }
        assertFalse(context.getSharedPreferences("local_notification_receipts", 0).contains("SUB:SESSION_STOPPED:local-pact-expiry"));
        assertFalse(main().contains("notification_pact_expiry_receipt"));
    }
    @SuppressWarnings("unchecked")
    private java.util.concurrent.CompletableFuture<Void> deferredExpiry(boolean nativeStart,
            java.util.function.BooleanSupplier closed) throws Exception {
        if (!nativeStart) {
            CommitmentManager.emergencyRelease(context);
            boolean wasDom = com.subhub.app.security.ControllerPinManager.isDomModeActive();
            try {
                com.subhub.app.security.ControllerPinManager.enterDomMode();
                assertTrue(CommitmentManager.start(context, 3600000));
            } finally { if (!wasDom) com.subhub.app.security.ControllerPinManager.enterSubMode(); }
        }
        synchronized (RuntimeSessionIdentity.LOCK) {
            SharedPreferences.Editor edit = main().edit().putLong("notification_pact_expiry_sequence", 1);
            com.subhub.app.notifications.LocalPactExpiryNotifications.append(edit,
                    main().getString("commitment_session_identity", ""), statistics.activeSessionIdentity(), 1);
            assertTrue(edit.commit());
            CommitmentManager.emergencyRelease(context);
        }
        java.lang.reflect.Method deferred = com.subhub.app.notifications.LocalPactExpiryNotifications.class.getDeclaredMethod(
                "afterCaptureClosure", Context.class, java.util.function.BooleanSupplier.class, long.class);
        deferred.setAccessible(true);
        return (java.util.concurrent.CompletableFuture<Void>) deferred.invoke(null, context, closed, 1L);
    }
    @Test public void failedPostCommitPeerLookupCannotEscapeAppliedResultObserver() throws Exception {
        RemoteSessionRuntime.Record record = appliedRecord();
        sub.getWritableDatabase().execSQL("ALTER TABLE peers RENAME TO notification_fixture_peers");
        try {
            assertThrows(android.database.sqlite.SQLiteException.class, () -> sub.peer(DOM, generation));
            RemoteAppliedNotifications.nativeSession(context, sub, DOM, generation, record);
            assertEquals(RemoteSessionCommands.State.APPLIED, record.result.state);
            assertTrue(RuntimeSessionIdentity.observe(context, RemoteSessionClock.local()).active);
        } finally { sub.getWritableDatabase().execSQL("ALTER TABLE notification_fixture_peers RENAME TO peers"); }
    }
}
