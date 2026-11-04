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
public final class RemoteLifecycleNotificationsAndroidTest {
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
        prefix = "lifecycle-notification-" + UUID.randomUUID() + "-";
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
        rememberApplied(request);
    }
    private SharedPreferences main() { return context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0); }
    private void rememberApplied(RemoteSessionCommands.Request request) throws Exception {
        RemoteSessionCommands.Result result = new RemoteSessionCommands.Result(request, RemoteSessionCommands.State.APPLIED,
                RemoteSessionCommands.Reason.NONE, prepared.statistics.startedAt,
                new RemoteSessionCommands.Proof(true, RuntimeSessionIdentity.current(context), prepared.statistics.startedAt,
                        prepared.endsAt, prepared.selectedDuration));
        RemoteAppliedNotifications.session(context, dom, dom.peer(SUB, generation), new RemoteSessionRuntime.Record(
                RemoteSessionRuntime.Direction.OUTGOING, request, result, new RemoteSessionClock.Bounds(1, 1, 1, 2)));
    }
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
    private JSONObject expire() throws Exception {
        RemoteSessionClock.Sample now = RemoteSessionClock.observe(context);
        assertTrue(main().edit().putLong("commitment_ends_at", now.wall - 1)
                .putLong("commitment_ends_elapsed", now.elapsed - 1).putInt("commitment_boot", now.boot).commit());
        assertTrue(CommitmentManager.expireIfDue(context, prepared.pactIdentity));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertFalse(RuntimeSessionIdentity.active(context));
        assertTrue(RemoteLifecycleNotifications.ready(context, main().getLong("notification_pact_expiry_sequence", 0)));
        RemoteLifecycleNotifications.publish(context, sub, sub.peer(DOM, generation));
        for (RemoteProtocol.Change change : sub.published(DOM, generation, RemoteProtocol.Stream.RUNTIME))
            if (RemoteLifecycleNotifications.KEY.equals(change.key) && !change.deleted) return new JSONObject(change.body);
        throw new AssertionError("Missing exact-owned expiry publication");
    }
    @Test public void committedOwnedExpiryIsAcceptedOnceAndDisabledEventsHaveNoReplayBacklog() throws Exception {
        JSONObject receipt = expire();
        assertTrue(NotificationPreferences.set(context, NotificationPreferences.Role.DOM, NotificationPreferences.Event.SESSION_STOPPED, false));
        assertTrue(RemoteLifecycleNotifications.accept(context, dom, dom.peer(SUB, generation), receipt));
        String key = "DOM:SESSION_STOPPED:" + SUB + ":" + generation + ":pact-expiry";
        SharedPreferences ledger = context.getSharedPreferences("local_notification_receipts", 0);
        assertEquals(receipt.getLong("sequence"), ledger.getLong(key, 0));
        int size = ledger.getAll().size();
        assertTrue(NotificationPreferences.set(context, NotificationPreferences.Role.DOM, NotificationPreferences.Event.SESSION_STOPPED, true));
        assertTrue(RemoteLifecycleNotifications.accept(context, dom, dom.peer(SUB, generation), receipt));
        assertEquals(size, ledger.getAll().size());
        assertEquals(receipt.getLong("sequence"), ledger.getLong(key, 0));
    }
    @Test public void tamperedWrongRelationshipAndUnappliedStartCannotGenerateAnExpiryNotification() throws Exception {
        JSONObject receipt = expire();
        assertFalse(RemoteLifecycleNotifications.accept(context, dom, dom.peer(SUB, generation),
                new JSONObject(receipt.toString()).put("digest", String.join("", Collections.nCopies(64, "c")))));
        assertFalse(RemoteLifecycleNotifications.accept(context, dom, dom.peer(SUB, generation),
                new JSONObject(receipt.toString()).put("generation", "other-generation")));
        assertFalse(RemoteLifecycleNotifications.accept(context, dom, dom.peer(SUB, generation),
                new JSONObject(receipt.toString()).put("sub", DOM)));
        assertFalse(RemoteLifecycleNotifications.accept(context, dom, dom.peer(SUB, generation),
                new JSONObject(receipt.toString()).put("request", "stale-request")));
        dom.putMetadata(SUB, generation, "notification_applied_session", "{}");
        assertFalse(RemoteLifecycleNotifications.accept(context, dom, dom.peer(SUB, generation), receipt));
    }
    @Test public void revokedRelationshipCannotReceiveHistoricalExpiry() throws Exception {
        JSONObject receipt = expire();
        RemoteStore.Peer before = dom.peer(SUB, generation);
        dom.revoke(SUB, generation);
        assertFalse(RemoteLifecycleNotifications.accept(context, dom, before, receipt));
    }
    @Test public void delayedNativeExpiryClosureRejectsReplacementStartAndStop() throws Exception {
        delayedExpiryRejectsReplacement(true);
    }
    @Test public void delayedLocalOnlyExpiryClosureRejectsReplacementStartAndStop() throws Exception {
        delayedExpiryRejectsReplacement(false);
    }
    @Test public void localOnlyExpiryWithoutReplacementConsumesItsOwnPersistedIdentity() throws Exception {
        java.util.concurrent.CompletableFuture<Void> done = deferredExpiry(false, () -> true);
        done.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(1, context.getSharedPreferences("local_notification_receipts", 0)
                .getLong("SUB:SESSION_STOPPED:local-pact-expiry", 0));
        assertEquals(0, main().getLong(RemoteLifecycleNotifications.READY, 0));
    }
    private void delayedExpiryRejectsReplacement(boolean remoteOwned) throws Exception {
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.CountDownLatch observedPending = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CompletableFuture<Void> done = deferredExpiry(remoteOwned, () -> {
            observedPending.countDown(); return closed.get();
        });
        try {
            assertTrue(observedPending.await(5, java.util.concurrent.TimeUnit.SECONDS));
            new AppModeManager(context).setArmed(true);
            assertTrue(RuntimeSessionIdentity.observe(context, RemoteSessionClock.local()).active);
            new AppModeManager(context).setArmed(false);
            assertFalse(RuntimeSessionIdentity.observe(context, RemoteSessionClock.local()).active);
        } finally { closed.set(true); done.get(5, java.util.concurrent.TimeUnit.SECONDS); }
        assertFalse(context.getSharedPreferences("local_notification_receipts", 0)
                .contains("SUB:SESSION_STOPPED:local-pact-expiry"));
        assertEquals(0, main().getLong(RemoteLifecycleNotifications.READY, 0));
        RemoteLifecycleNotifications.publish(context, sub, sub.peer(DOM, generation));
        for (RemoteProtocol.Change change : sub.published(DOM, generation, RemoteProtocol.Stream.RUNTIME))
            if (RemoteLifecycleNotifications.KEY.equals(change.key)) assertTrue(change.deleted);
    }
    @SuppressWarnings("unchecked")
    private java.util.concurrent.CompletableFuture<Void> deferredExpiry(boolean remoteOwned,
            java.util.function.BooleanSupplier closed) throws Exception {
        if (!remoteOwned) {
            CommitmentManager.emergencyRelease(context);
            boolean wasDom = com.subhub.app.security.ControllerPinManager.isDomModeActive();
            try {
                com.subhub.app.security.ControllerPinManager.enterDomMode();
                assertTrue(CommitmentManager.start(context, 3600000));
            } finally { if (!wasDom) com.subhub.app.security.ControllerPinManager.enterSubMode(); }
        }
        synchronized (RuntimeSessionIdentity.LOCK) {
            String pact = main().getString("commitment_session_identity", "");
            String statisticsIdentity = statistics.activeSessionIdentity();
            SharedPreferences.Editor edit = main().edit().putLong("notification_pact_expiry_sequence", 1);
            com.subhub.app.notifications.LocalPactExpiryNotifications.append(edit, pact, statisticsIdentity, 1);
            boolean captured = RemoteLifecycleNotifications.appendExpiry(context, edit, pact, statisticsIdentity, 1);
            assertEquals(remoteOwned, captured);
            assertTrue(edit.commit());
            CommitmentManager.emergencyRelease(context);
        }
        java.lang.reflect.Method deferred = com.subhub.app.notifications.LocalPactExpiryNotifications.class.getDeclaredMethod(
                "afterCaptureClosure", Context.class, java.util.function.BooleanSupplier.class, long.class, boolean.class);
        deferred.setAccessible(true);
        return (java.util.concurrent.CompletableFuture<Void>) deferred.invoke(null, context, closed, 1L, remoteOwned);
    }
    @Test public void failedPostCommitPeerLookupCannotEscapeAppliedResultObserver() throws Exception {
        RemoteSessionCommands.Request request = prepared.request;
        RemoteSessionCommands.Result applied = new RemoteSessionCommands.Result(request, RemoteSessionCommands.State.APPLIED,
                RemoteSessionCommands.Reason.NONE, prepared.statistics.startedAt,
                new RemoteSessionCommands.Proof(true, RuntimeSessionIdentity.current(context), prepared.statistics.startedAt,
                        prepared.endsAt, prepared.selectedDuration));
        RemoteSessionRuntime.Record record = new RemoteSessionRuntime.Record(RemoteSessionRuntime.Direction.INCOMING,
                request, applied, new RemoteSessionClock.Bounds(1, 1, 1, 2));
        dom.getWritableDatabase().execSQL("ALTER TABLE peers RENAME TO notification_fixture_peers");
        try {
            assertThrows(android.database.sqlite.SQLiteException.class, () -> dom.peer(SUB, generation));
            RemoteAppliedNotifications.nativeSession(context, dom, SUB, generation, record);
            assertEquals(RemoteSessionCommands.State.APPLIED, record.result.state);
            assertTrue(RuntimeSessionIdentity.observe(context, RemoteSessionClock.local()).active);
        } finally {
            dom.getWritableDatabase().execSQL("ALTER TABLE notification_fixture_peers RENAME TO peers");
        }
    }
    @Test public void activeReplacementCannotCompleteOrRepublishHistoricalExpiry() throws Exception {
        JSONObject receipt = expire();
        new AppModeManager(context).setArmed(true);
        assertTrue(RuntimeSessionIdentity.active(context));
        assertFalse(RemoteLifecycleNotifications.ready(context, receipt.getLong("sequence")));
        RemoteLifecycleNotifications.publish(context, sub, sub.peer(DOM, generation));
        for (RemoteProtocol.Change change : sub.published(DOM, generation, RemoteProtocol.Stream.RUNTIME))
            if (RemoteLifecycleNotifications.KEY.equals(change.key)) assertTrue(change.deleted);
    }
}
