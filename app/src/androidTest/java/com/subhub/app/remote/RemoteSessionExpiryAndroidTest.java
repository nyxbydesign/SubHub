package com.subhub.app.remote;

import static org.junit.Assert.*;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.appmode.SessionExpiryAlarms;
import com.subhub.app.commitment.CommitmentExpiryReceiver;
import com.subhub.app.commitment.CommitmentManager;
import com.subhub.app.penance.PaidPauseExpiryReceiver;
import com.subhub.app.penance.PaidPauseManager;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.stats.RemoteSessionStatsFixture;
import com.subhub.app.stats.StatsRepository;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Actual receiver calls and Android alarm tokens, with private preferences and no runtime grants. */
@RunWith(AndroidJUnit4.class)
public final class RemoteSessionExpiryAndroidTest {
    private Context target, context;
    private String prefix;
    private RemoteSessionNative nativeSession;
    private StatsRepository stats;
    private final List<String> lockViolations = Collections.synchronizedList(new ArrayList<>());
    private final List<String> serviceCalls = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean blockAlarm = new AtomicBoolean();
    private CountDownLatch alarmEntered, releaseAlarm;

    @Before public void setup() {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefix = "native-session-expiry-" + UUID.randomUUID() + "-";
        context = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return target.getSharedPreferences(prefix + name, mode);
            }
            @Override public Object getSystemService(String name) {
                serviceCalls.add(name);
                if (Thread.holdsLock(RuntimeSessionIdentity.LOCK)) lockViolations.add(name);
                if (Context.ALARM_SERVICE.equals(name) && blockAlarm.compareAndSet(true, false)) {
                    alarmEntered.countDown();
                    try { assertTrue("Release alarm observer", releaseAlarm.await(15, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                }
                return super.getSystemService(name);
            }
            @Override public android.content.ContentResolver getContentResolver() {
                if (Thread.holdsLock(RuntimeSessionIdentity.LOCK)) lockViolations.add("resolver");
                return super.getContentResolver();
            }
        };
        stats = RemoteSessionStatsFixture.create(context);
        nativeSession = RemoteSessionNative.installFixture(context, () -> stats);
        RemoteSessionClock.observe(context);
    }
    @After public void cleanup() throws Exception {
        if (releaseAlarm != null) releaseAlarm.countDown();
        synchronized (RuntimeSessionIdentity.LOCK) {
            for (SessionExpiryAlarms.Kind kind : SessionExpiryAlarms.Kind.values()) {
                SharedPreferences saved = kind == SessionExpiryAlarms.Kind.PACT ? main() : pause();
                String key = kind == SessionExpiryAlarms.Kind.PACT ? "commitment_session_identity" : "paid_pause_session_identity";
                String identity = saved.getString(key, "");
                saved.edit().remove(key).commit();
                SessionExpiryAlarms.update(context, kind, identity, "", 0L);
            }
            RuntimeSessionIdentity.changed();
        }
        drain(); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        nativeSession.close();
        for (String name : new String[]{SettingsRepository.PREFERENCES_NAME, StatsRepository.PREFS_NAME,
                PenanceManager.PREFS_NAME, "subhub_hardcore_auto_pay", com.subhub.app.security.SubFeaturePolicy.STORE})
            target.getSharedPreferences(prefix + name, 0).edit().clear().commit();
        assertTrue("Android services/providers queried under Runtime: " + lockViolations, lockViolations.isEmpty());
    }
    private SharedPreferences main() { return context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0); }
    private SharedPreferences pause() { return context.getSharedPreferences(PenanceManager.PREFS_NAME, 0); }
    private static String id() { return UUID.randomUUID().toString(); }
    private void pact(String identity, long remaining) {
        synchronized (RuntimeSessionIdentity.LOCK) {
            RemoteSessionClock.Sample clock = RemoteSessionClock.local();
            assertTrue(main().edit().putString("commitment_session_identity", identity)
                    .putLong("commitment_started_at", clock.wall - 60000)
                    .putLong("commitment_ends_at", clock.wall + remaining)
                    .putLong("commitment_ends_elapsed", clock.elapsed + remaining)
                    .putInt("commitment_boot", clock.boot).putLong("commitment_duration", 1800000).commit());
            RuntimeSessionIdentity.changed();
        }
    }
    private void pause(String identity, long remaining, boolean rearm) {
        synchronized (RuntimeSessionIdentity.LOCK) {
            assertTrue(pause().edit().putString("paid_pause_session_identity", identity)
                    .putLong("paid_pause_active_until", System.currentTimeMillis() + remaining)
                    .putBoolean("paid_pause_rearm", rearm).commit());
            RuntimeSessionIdentity.changed();
        }
    }
    private void pactDelivery(String identity) {
        new CommitmentExpiryReceiver().onReceive(context, identity == null
                ? new Intent("com.subhub.app.action.PACT_EXPIRED")
                : SessionExpiryAlarms.intent(context, SessionExpiryAlarms.Kind.PACT, identity));
    }
    private void pauseDelivery(String identity) {
        new PaidPauseExpiryReceiver().onReceive(context, identity == null
                ? new Intent("com.subhub.app.action.PAID_PAUSE_EXPIRED")
                : SessionExpiryAlarms.intent(context, SessionExpiryAlarms.Kind.PAUSE, identity));
    }
    private static void drain() throws Exception {
        Field worker = SessionExpiryAlarms.class.getDeclaredField("WORKER"); worker.setAccessible(true);
        ((ExecutorService) worker.get(null)).submit(() -> { }).get(15, TimeUnit.SECONDS);
    }
    private PendingIntent pending(SessionExpiryAlarms.Kind kind, String identity) {
        return PendingIntent.getBroadcast(context, kind == SessionExpiryAlarms.Kind.PACT ? 8317 : 8349,
                SessionExpiryAlarms.intent(context, kind, identity), PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
    }
    private void schedule(String retired, String identity) {
        synchronized (RuntimeSessionIdentity.LOCK) {
            SessionExpiryAlarms.update(context, SessionExpiryAlarms.Kind.PACT, retired, identity,
                    android.os.SystemClock.elapsedRealtime() + 3600000L);
        }
    }

    @Test public void pactReceiverRequiresCurrentDueGenerationAndLegacyNeverEndsAFuturePact() throws Exception {
        new AppModeManager(context).setArmed(true);
        String statistics = stats.activeSessionIdentity(), old = id(), current = id();
        pact(old, -1000); pact(current, 60000);
        Map<String, ?> future = main().getAll();
        pactDelivery(old); pactDelivery(current); pactDelivery(null);
        assertEquals(future, main().getAll()); assertEquals(statistics, stats.activeSessionIdentity());
        pact(current, -1000);
        Map<String, ?> due = main().getAll(); pactDelivery(old);
        assertEquals(due, main().getAll());
        pactDelivery(current);
        assertFalse(main().getBoolean(AppModeManager.KEY_ARMED, true));
        assertEquals("", main().getString("commitment_session_identity", ""));
        assertEquals("", stats.activeSessionIdentity()); assertEquals(1, stats.getSessionHistory().size());
        pactDelivery(current); pactDelivery(null); assertEquals(1, stats.getSessionHistory().size());
        new AppModeManager(context).setArmed(true); String laterStatistics = stats.activeSessionIdentity();
        pact(id(), 60000); Map<String, ?> later = main().getAll(); pactDelivery(current);
        assertEquals(later, main().getAll()); assertEquals(laterStatistics, stats.activeSessionIdentity());
        drain();
    }
    @Test public void pauseReceiverRequiresCurrentDueGenerationAndRearmsOnlyItsCommittedPact() {
        String old = id(), current = id(), pact = id();
        pact(pact, 60000); pause(old, -1000, true); pause(current, 60000, true);
        Map<String, ?> future = pause().getAll(); pauseDelivery(old); pauseDelivery(current); pauseDelivery(null);
        assertEquals(future, pause().getAll()); assertFalse(new AppModeManager(context).isArmed());
        pause(current, -1000, true); Map<String, ?> due = pause().getAll(); pauseDelivery(old);
        assertEquals(due, pause().getAll()); pauseDelivery(current);
        assertEquals(0L, pause().getLong("paid_pause_active_until", 0)); assertTrue(new AppModeManager(context).isArmed());
        String statistics = stats.activeSessionIdentity(); assertFalse(statistics.isEmpty());
        pauseDelivery(current); assertEquals(statistics, stats.activeSessionIdentity());
        assertEquals(pact, main().getString("commitment_session_identity", ""));
        pause(id(), 60000, false); Map<String, ?> later = pause().getAll(); pauseDelivery(current);
        assertEquals(later, pause().getAll()); assertEquals(statistics, stats.activeSessionIdentity());
    }
    @Test public void legacyDueTimersStillExpireAndExplicitRecoveryCanEndFutureTimers() {
        pact("", 60000); pactDelivery(null); assertTrue(CommitmentManager.observedActive(context));
        pact("", -1000); pactDelivery(null); assertEquals(0L, main().getLong("commitment_ends_at", 0));
        pause("", 60000, false); pauseDelivery(null); assertTrue(pause().contains("paid_pause_active_until"));
        pause("", -1000, false); pauseDelivery(null); assertFalse(pause().contains("paid_pause_active_until"));
        pact(id(), 60000); CommitmentManager.emergencyRelease(context);
        assertFalse(CommitmentManager.observedActive(context));
        pause(id(), 60000, false); new PaidPauseManager(context).finish(); assertFalse(pause().contains("paid_pause_active_until"));
    }
    @Test public void malformedAlarmTargetsAndWrongKindCannotFallBackToLegacy() {
        String identity = id(); pact(identity, -1000); pause(identity, -1000, false);
        Map<String, ?> originalPact = main().getAll(), originalPause = pause().getAll();
        for (String uri : new String[]{"subhub-expiry://pact/nope", "subhub-expiry://pause/" + identity,
                "subhub-expiry://pact/" + identity + "?legacy=true", "subhub-expiry://pact/x/" + identity})
            new CommitmentExpiryReceiver().onReceive(context, new Intent("com.subhub.app.action.PACT_EXPIRED").setData(Uri.parse(uri)));
        new PaidPauseExpiryReceiver().onReceive(context, new Intent("com.subhub.app.action.PAID_PAUSE_EXPIRED")
                .setData(Uri.parse("subhub-expiry://pact/" + identity)));
        new CommitmentExpiryReceiver().onReceive(context, new Intent("wrong.action"));
        assertEquals(originalPact, main().getAll()); assertEquals(originalPause, pause().getAll());
    }
    @Test public void pactDueCheckUsesMatchingBootElapsedClockAndWallFallbackAfterBootMismatch() {
        String identity = id(); pact(identity, 60000);
        main().edit().putLong("commitment_ends_at", System.currentTimeMillis() - 1000).commit();
        pactDelivery(identity); assertEquals(identity, main().getString("commitment_session_identity", ""));
        pact(identity, -1000);
        main().edit().putInt("commitment_boot", RemoteSessionClock.local().boot + 1)
                .putLong("commitment_ends_at", System.currentTimeMillis() + 60000).commit();
        pactDelivery(identity); assertEquals(identity, main().getString("commitment_session_identity", ""));
        main().edit().putLong("commitment_ends_at", System.currentTimeMillis() - 1000).commit();
        pactDelivery(identity); assertFalse(main().contains("commitment_ends_at"));
    }
    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 31)
    public void immutableAlarmTokensAreGenerationSpecificAndOldCancellationCannotReplaceNewIntent() throws Exception {
        String old = id(), current = id();
        pact(old, 60000); schedule("", old); drain();
        PendingIntent oldToken = pending(SessionExpiryAlarms.Kind.PACT, old); assertNotNull(oldToken);
        assertTrue(oldToken.isImmutable());
        pact(current, 60000); schedule(old, current); drain();
        PendingIntent currentToken = pending(SessionExpiryAlarms.Kind.PACT, current);
        assertNotNull(currentToken); assertNotEquals(oldToken, currentToken);
        assertNull(pending(SessionExpiryAlarms.Kind.PACT, old));
        synchronized (RuntimeSessionIdentity.LOCK) {
            SessionExpiryAlarms.update(context, SessionExpiryAlarms.Kind.PACT, old, "", 0);
        }
        drain(); assertEquals(currentToken, pending(SessionExpiryAlarms.Kind.PACT, current));
        try { oldToken.send(); fail("Retired token still sends"); } catch (PendingIntent.CanceledException expected) { }
        assertEquals(current, main().getString("commitment_session_identity", ""));
    }
    @Test public void blockedAndroidAlarmWorkerCoalescesReplacementAndRetiresLegacyOutsideRuntime() throws Exception {
        Intent legacyIntent = new Intent(context, CommitmentExpiryReceiver.class).setAction("com.subhub.app.action.PACT_EXPIRED");
        PendingIntent legacy = PendingIntent.getBroadcast(context, 8317, legacyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        context.getSystemService(AlarmManager.class).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + 3600000, legacy);
        String old = id(), current = id();
        alarmEntered = new CountDownLatch(1); releaseAlarm = new CountDownLatch(1); blockAlarm.set(true);
        pact(old, 60000); schedule("", old); assertTrue(alarmEntered.await(10, TimeUnit.SECONDS));
        for (int i = 0; i < 128; i++) {
            String next = id(); pact(next, 60000); schedule(old, next); old = next;
        }
        pact(current, 60000); schedule(old, current);
        releaseAlarm.countDown(); drain();
        assertNotNull(pending(SessionExpiryAlarms.Kind.PACT, current));
        assertNull(PendingIntent.getBroadcast(context, 8317, legacyIntent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE));
        try { legacy.send(); fail("Legacy token still sends"); } catch (PendingIntent.CanceledException expected) { }
        assertTrue(lockViolations.toString(), lockViolations.isEmpty());
    }
    @Test public void dueNativePactClosesItsMainMarkerAndStatisticsOnceWithoutRearmingOnReplay() throws Exception {
        RemoteSessionClock.Sample clock = RemoteSessionClock.local();
        RemoteSessionCommands.Request request = RemoteSessionCommands.Request.start(id(),
                String.join("", Collections.nCopies(64, "a")), String.join("", Collections.nCopies(64, "b")),
                "expiry_generation", 1, 0, clock.wall, clock.wall + 120000,
                RuntimeSessionIdentity.observe(context, clock).idleFence,
                new RemoteSessionCommands.Duration(3600000, 1800000, 3600000, false));
        RemoteSessionNative.PreparedStart prepared = RemoteSessionNative.prepare(request, clock);
        assertTrue(nativeSession.commitPreparedStart(prepared, RemoteSessionClock.local()));
        assertEquals(prepared.statistics.identity, stats.activeSessionIdentity());
        pact(prepared.pactIdentity, -1000); pactDelivery(prepared.pactIdentity);
        assertTrue(nativeSession.marker().ended); assertEquals("", stats.activeSessionIdentity());
        assertEquals(1, stats.getSessionHistory().size()); assertFalse(main().getBoolean(AppModeManager.KEY_ARMED, true));
        assertFalse(nativeSession.commitPreparedStart(prepared, RemoteSessionClock.local()));
        pactDelivery(prepared.pactIdentity); assertEquals(1, stats.getSessionHistory().size());
    }
    @Test public void delayedExpiryEffectsCannotCancelAReplacementSessionsNotification() throws Exception {
        new AppModeManager(context).setArmed(true);
        String old = id(); pact(old, -1000);
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            blocked.countDown();
            try { assertTrue(release.await(15, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
        });
        assertTrue(blocked.await(10, TimeUnit.SECONDS)); serviceCalls.clear();
        try {
            pactDelivery(old);
            String replacement = id(); pact(replacement, 60000); new AppModeManager(context).setArmed(true);
            release.countDown(); InstrumentationRegistry.getInstrumentation().waitForIdleSync(); drain();
            assertEquals(replacement, main().getString("commitment_session_identity", ""));
            assertTrue(new AppModeManager(context).isArmed());
            assertFalse("Stale notification effect escaped", serviceCalls.contains(Context.NOTIFICATION_SERVICE));
        } finally { release.countDown(); }
    }
    @Test public void localStartBootRescheduleAndSyntheticPaidPauseKeepTheirChosenTimerIdentities() throws Exception {
        assertTrue(CommitmentManager.start(context, 1800000, 3600000, true));
        String pact = main().getString("commitment_session_identity", "");
        long deadline = main().getLong("commitment_ends_at", 0), duration = CommitmentManager.originalDurationMillis(context);
        assertTrue(SessionExpiryAlarms.validIdentity(pact)); assertTrue(duration >= 1800000 && duration <= 3600000);
        assertTrue(new AppModeManager(context).isArmed()); assertTrue(CommitmentManager.isCountdownHidden(context));
        drain(); PendingIntent pactToken = pending(SessionExpiryAlarms.Kind.PACT, pact); assertNotNull(pactToken);
        CommitmentManager.applyBootPolicy(context); drain();
        assertEquals(pact, main().getString("commitment_session_identity", ""));
        assertEquals(deadline, main().getLong("commitment_ends_at", 0));
        assertEquals(duration, CommitmentManager.originalDurationMillis(context));
        assertEquals(pactToken, pending(SessionExpiryAlarms.Kind.PACT, pact));
        new PenanceManager(context); // Complete the fresh wallet migration before choosing pause participation.
        PaidPauseManager paidPause = new PaidPauseManager(context); paidPause.configure(true, 500, 15);
        assertTrue(paidPause.activate());
        String pause = pause().getString("paid_pause_session_identity", "");
        assertTrue(SessionExpiryAlarms.validIdentity(pause)); assertFalse(new AppModeManager(context).isArmed());
        drain(); PendingIntent pauseToken = pending(SessionExpiryAlarms.Kind.PAUSE, pause); assertNotNull(pauseToken);
        paidPause.applyBootPolicy(); drain();
        assertEquals(pause, pause().getString("paid_pause_session_identity", ""));
        assertEquals(pauseToken, pending(SessionExpiryAlarms.Kind.PAUSE, pause));
        paidPause.finish(); assertTrue(new AppModeManager(context).isArmed()); drain();
        assertNull(pending(SessionExpiryAlarms.Kind.PAUSE, pause));
        CommitmentManager.emergencyRelease(context); drain(); assertNull(pending(SessionExpiryAlarms.Kind.PACT, pact));
    }
    @Test public void expiredPauseObservesDeniedAndroidAdminOutsideRuntimeWithoutGrantingARearm() {
        main().edit().putBoolean(com.subhub.app.security.HardcoreModeManager.KEY_REQUESTED, true).commit();
        assertFalse("Fixture requires the unchanged ungranted admin state",
                new com.subhub.app.security.HardcoreModeManager(context).isAdminActive());
        serviceCalls.clear(); String pause = id(); pause(pause, -1000, true);
        pauseDelivery(pause);
        assertFalse(pause().contains("paid_pause_active_until"));
        assertFalse(new AppModeManager(context).isArmed()); assertEquals("", stats.activeSessionIdentity());
        assertTrue(serviceCalls.contains(Context.DEVICE_POLICY_SERVICE)); assertTrue(lockViolations.isEmpty());
    }
    @Test public void deferredHardcoreRearmRejectsOffCommittedWhileItWaitedForRuntime() throws Exception {
        main().edit().putBoolean(com.subhub.app.security.HardcoreModeManager.KEY_REQUESTED, true).commit();
        RuntimeSessionIdentity.ComponentObservation scope = RuntimeSessionIdentity.componentObservation(context);
        java.lang.reflect.Method rearm = com.subhub.app.appmode.SessionExpiryEffects.class.getDeclaredMethod(
                "rearmHardcore", Context.class, RuntimeSessionIdentity.ComponentObservation.class, boolean.class);
        rearm.setAccessible(true);
        java.util.concurrent.atomic.AtomicReference<Thread> thread = new java.util.concurrent.atomic.AtomicReference<>();
        ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread value = new Thread(r, "expiry-hc-observation"); thread.set(value); return value;
        });
        java.util.concurrent.Future<?> result;
        try {
            synchronized (RuntimeSessionIdentity.LOCK) {
                // Synthetic prior-enabled observation; the test never enables Android Device Admin.
                result = worker.submit(() -> {
                    try { return rearm.invoke(null, context, scope, true); }
                    catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                });
                long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
                while ((thread.get() == null || thread.get().getState() != Thread.State.BLOCKED)
                        && android.os.SystemClock.elapsedRealtime() < deadline) Thread.yield();
                assertNotNull(thread.get()); assertEquals(Thread.State.BLOCKED, thread.get().getState());
                java.util.concurrent.FutureTask<Boolean> off = new java.util.concurrent.FutureTask<>(() ->
                        com.subhub.app.security.SubFeaturePolicy.consentHardcore(context, false)
                                && main().edit().putBoolean(com.subhub.app.security.HardcoreModeManager.KEY_REQUESTED, false).commit());
                Thread offThread = new Thread(off, "expiry-policy-off"); offThread.start();
                assertTrue(off.get(5, TimeUnit.SECONDS)); offThread.join(5000); assertFalse(offThread.isAlive());
                // Actual policy off intentionally leaves Runtime's component fence unchanged.
                assertTrue(scope.isCurrent());
            }
            assertNull(result.get(10, TimeUnit.SECONDS));
            assertFalse(new AppModeManager(context).isArmed()); assertEquals("", stats.activeSessionIdentity());
            assertTrue(main().edit().putBoolean(com.subhub.app.security.HardcoreModeManager.KEY_REQUESTED, true).commit());
            RuntimeSessionIdentity.ComponentObservation unchanged = RuntimeSessionIdentity.componentObservation(context);
            assertNotNull(rearm.invoke(null, context, unchanged, true));
            assertTrue(new AppModeManager(context).isArmed()); assertFalse(stats.activeSessionIdentity().isEmpty());
        } finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); }
    }
}
