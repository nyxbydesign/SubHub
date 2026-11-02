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
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.stats.RemoteSessionStatsFixture;
import com.subhub.app.stats.StatsRepository;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Native persistence only: an acknowledged Stop marker does not prove Android resource closure. */
@RunWith(AndroidJUnit4.class)
public final class RemoteSessionNativeStopAndroidTest {
    private Context target, context;
    private String prefix;
    private RemoteSessionNative nativeSession;
    private StatsRepository stats;
    private final AtomicBoolean refuseMain = new AtomicBoolean(), refuseStats = new AtomicBoolean(),
            refusePause = new AtomicBoolean(), uncertainMain = new AtomicBoolean();
    private final AtomicBoolean memoryStats = new AtomicBoolean(), memoryPause = new AtomicBoolean();
    private final Map<String, MemoryAdvancingPreferences> memoryStores = new java.util.concurrent.ConcurrentHashMap<>();
    @Before public void setup() {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefix = "native-session-stop-" + UUID.randomUUID() + "-";
        context = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                SharedPreferences raw = target.getSharedPreferences(prefix + name, mode);
                SharedPreferences saved = StatsRepository.PREFS_NAME.equals(name) || PenanceManager.PREFS_NAME.equals(name)
                        ? memoryStores.computeIfAbsent(name, ignored -> new MemoryAdvancingPreferences(raw,
                                StatsRepository.PREFS_NAME.equals(name) ? memoryStats : memoryPause)) : raw;
                return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                        new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (method.getName().equals("edit")) return editor(saved.edit(), name);
                    try { return method.invoke(saved, args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
            }
            @Override public Object getSystemService(String name) {
                assertFalse("Android lookup under Runtime: " + name, Thread.holdsLock(RuntimeSessionIdentity.LOCK));
                return super.getSystemService(name);
            }
        };
        stats = RemoteSessionStatsFixture.create(context);
        nativeSession = RemoteSessionNative.installFixture(context, () -> stats);
        RemoteSessionClock.observe(context);
    }
    private SharedPreferences.Editor editor(SharedPreferences.Editor saved, String name) {
        return (SharedPreferences.Editor) Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
            boolean commit = method.getName().equals("commit");
            if (commit && (name.equals(SettingsRepository.PREFERENCES_NAME) && refuseMain.get()
                    || name.equals(StatsRepository.PREFS_NAME) && refuseStats.get()
                    || name.equals(PenanceManager.PREFS_NAME) && refusePause.get())) return false;
            Object value;
            try { value = method.invoke(saved, args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
            if (commit && name.equals(SettingsRepository.PREFERENCES_NAME) && uncertainMain.getAndSet(false)) return false;
            return value == saved ? proxy : value;
        });
    }
    @After public void cleanup() throws Exception {
        refuseMain.set(false); refuseStats.set(false); refusePause.set(false); uncertainMain.set(false);
        memoryStats.set(false); memoryPause.set(false);
        synchronized (RuntimeSessionIdentity.LOCK) {
            for (SessionExpiryAlarms.Kind kind : SessionExpiryAlarms.Kind.values()) {
                SharedPreferences saved = kind == SessionExpiryAlarms.Kind.PACT ? main() : pause();
                String key = kind == SessionExpiryAlarms.Kind.PACT ? "commitment_session_identity" : "paid_pause_session_identity";
                String old = saved.getString(key, ""); saved.edit().remove(key).commit();
                SessionExpiryAlarms.update(context, kind, old, "", 0);
            }
            RuntimeSessionIdentity.changed();
        }
        java.lang.reflect.Field worker = SessionExpiryAlarms.class.getDeclaredField("WORKER"); worker.setAccessible(true);
        ((ExecutorService) worker.get(null)).submit(() -> { }).get(15, TimeUnit.SECONDS);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync(); nativeSession.close();
        for (String name : new String[]{SettingsRepository.PREFERENCES_NAME, PenanceManager.PREFS_NAME,
                StatsRepository.PREFS_NAME, RemoteConfiguration.DURATION_PREFS, "subhub_hardcore_auto_pay"})
            target.getSharedPreferences(prefix + name, 0).edit().clear().commit();
    }
    private SharedPreferences main() { return context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0); }
    private SharedPreferences pause() { return context.getSharedPreferences(PenanceManager.PREFS_NAME, 0); }
    private static String id() { return UUID.randomUUID().toString(); }
    private RemoteSessionCommands.Request stopRequest() {
        RemoteSessionClock.Sample now = RemoteSessionClock.local();
        return RemoteSessionCommands.Request.stop(id(), String.join("", Collections.nCopies(64, "a")),
                String.join("", Collections.nCopies(64, "b")), "native_stop_generation", 1, 0, now.wall,
                now.wall + 120000, RuntimeSessionIdentity.observe(context, now).identity);
    }
    private RemoteSessionNative.PreparedStop prepare() throws Exception {
        return nativeSession.prepareStop(stopRequest(), RemoteSessionClock.local());
    }
    private boolean stop(RemoteSessionNative.PreparedStop prepared) throws Exception {
        return nativeSession.commitPreparedStop(prepared, RemoteSessionClock.local());
    }
    private void startLocal() { new AppModeManager(context).setArmed(true); assertFalse(stats.activeSessionIdentity().isEmpty()); }

    @Test public void stopCommitsIntentAndStatisticsOnceButOldReplayCannotEndALaterLocalSession() throws Exception {
        startLocal(); String oldStatistics = stats.activeSessionIdentity();
        RemoteSessionNative.PreparedStop prepared = prepare();
        assertEquals(oldStatistics, prepared.statistics); assertTrue(stop(prepared));
        assertTrue(nativeSession.stopMarker().ready); assertFalse(main().getBoolean(AppModeManager.KEY_ARMED, true));
        assertFalse(main().getBoolean("media_projection_session_desired", true));
        assertEquals("", stats.activeSessionIdentity()); assertEquals(1, stats.getSessionHistory().size());
        assertTrue(stop(RemoteSessionNative.PreparedStop.parse(prepared.json()))); assertEquals(1, stats.getSessionHistory().size());
        startLocal(); String nextStatistics = stats.activeSessionIdentity(); assertNotEquals(oldStatistics, nextStatistics);
        Map<String, ?> next = main().getAll(); assertTrue(stop(prepared));
        assertEquals(next, main().getAll()); assertEquals(nextStatistics, stats.activeSessionIdentity());
        assertEquals(1, stats.getSessionHistory().size());
    }
    @Test public void stopAlsoEndsTheNativeStartMarkerAndNeverReplaysThatStart() throws Exception {
        RemoteSessionClock.Sample now = RemoteSessionClock.local();
        RemoteSessionCommands.Request request = RemoteSessionCommands.Request.start(id(),
                String.join("", Collections.nCopies(64, "a")), String.join("", Collections.nCopies(64, "b")),
                "native_stop_generation", 1, 0, now.wall, now.wall + 120000,
                RuntimeSessionIdentity.observe(context, now).idleFence,
                new RemoteSessionCommands.Duration(3600000, 1800000, 3600000, false));
        RemoteSessionNative.PreparedStart start = RemoteSessionNative.prepare(request, now);
        assertTrue(nativeSession.commitPreparedStart(start, RemoteSessionClock.local()));
        assertTrue(stop(prepare())); assertTrue(nativeSession.marker().ended); assertTrue(nativeSession.stopMarker().ready);
        assertEquals(1, stats.getSessionHistory().size()); assertEquals("", stats.activeSessionIdentity());
        assertFalse(nativeSession.commitPreparedStart(start, RemoteSessionClock.local()));
        assertFalse(main().getBoolean(AppModeManager.KEY_ARMED, true));
    }
    @Test public void pendingStatisticsBlocksReplacementAndReopenOnlyCompletesItsOldBookkeeping() throws Exception {
        startLocal(); RemoteSessionNative.PreparedStop prepared = prepare(); String old = stats.activeSessionIdentity();
        refuseStats.set(true); assertFalse(stop(prepared));
        assertFalse(main().getBoolean(AppModeManager.KEY_ARMED, true)); assertFalse(nativeSession.stopMarker().ready);
        assertEquals(old, stats.activeSessionIdentity()); new AppModeManager(context).setArmed(true);
        assertFalse(main().getBoolean(AppModeManager.KEY_ARMED, true)); assertEquals(old, stats.activeSessionIdentity());
        refuseStats.set(false); nativeSession.close();
        nativeSession = RemoteSessionNative.installFixture(context, () -> stats);
        assertTrue(RemoteSessionNative.reconcile(context)); assertTrue(nativeSession.stopMarker().ready);
        assertEquals("", stats.activeSessionIdentity()); assertEquals(1, stats.getSessionHistory().size());
        assertFalse(main().getBoolean(AppModeManager.KEY_ARMED, true)); assertTrue(stop(prepared));
    }
    @Test public void pendingPauseCommitRecoversWithoutUsingItsRearmFlag() throws Exception {
        assertTrue(CommitmentManager.start(context, 1800000));
        String pauseId = id(); pause().edit().putString("paid_pause_session_identity", pauseId)
                .putLong("paid_pause_active_until", System.currentTimeMillis() + 60000).putBoolean("paid_pause_rearm", true).commit();
        RuntimeSessionIdentity.changed(); RemoteSessionNative.PreparedStop prepared = prepare();
        refusePause.set(true); assertFalse(stop(prepared));
        assertFalse(main().getBoolean(AppModeManager.KEY_ARMED, true)); assertFalse(main().contains("commitment_session_identity"));
        assertEquals(pauseId, pause().getString("paid_pause_session_identity", ""));
        assertFalse(RemoteSessionNative.beforeLocalStart(context));
        refusePause.set(false); assertTrue(RemoteSessionNative.reconcile(context));
        assertFalse(pause().contains("paid_pause_active_until")); assertFalse(main().getBoolean(AppModeManager.KEY_ARMED, true));
        assertEquals("", stats.activeSessionIdentity()); assertEquals(1, stats.getSessionHistory().size());
    }
    @Test public void changedPactWithTheSameStatisticsRejectsAnOldPreparedStop() throws Exception {
        assertTrue(CommitmentManager.start(context, 1800000)); RemoteSessionNative.PreparedStop prepared = prepare();
        String statistics = stats.activeSessionIdentity(), laterPact = id();
        main().edit().putString("commitment_session_identity", laterPact).commit(); RuntimeSessionIdentity.changed();
        Map<String, ?> later = main().getAll(); assertFalse(stop(prepared));
        assertEquals(later, main().getAll()); assertEquals(statistics, stats.activeSessionIdentity());
        assertNull(nativeSession.stopMarker());
    }
    @Test public void preparedOnlyFromAnotherProcessCannotEndTheCurrentSession() throws Exception {
        startLocal(); JSONObject value = prepare().json(); value.put("process", id());
        Map<String, ?> original = main().getAll(); assertFalse(stop(RemoteSessionNative.PreparedStop.parse(value)));
        assertEquals(original, main().getAll()); assertFalse(stats.activeSessionIdentity().isEmpty()); assertNull(nativeSession.stopMarker());
    }
    @Test public void failedMainCommitKeepsTheSessionAndUnacknowledgedCommitReconcilesOnce() throws Exception {
        startLocal(); RemoteSessionNative.PreparedStop prepared = prepare(); String statistics = stats.activeSessionIdentity();
        refuseMain.set(true); assertFalse(stop(prepared)); assertTrue(main().getBoolean(AppModeManager.KEY_ARMED, false));
        assertNull(nativeSession.stopMarker()); assertEquals(statistics, stats.activeSessionIdentity());
        refuseMain.set(false); uncertainMain.set(true); assertFalse(stop(prepared));
        assertFalse(main().getBoolean(AppModeManager.KEY_ARMED, true)); assertNotNull(nativeSession.stopMarker());
        assertEquals(statistics, stats.activeSessionIdentity()); assertTrue(RemoteSessionNative.reconcile(context));
        assertTrue(nativeSession.stopMarker().ready); assertEquals("", stats.activeSessionIdentity());
        assertEquals(1, stats.getSessionHistory().size()); assertTrue(stop(prepared)); assertEquals(1, stats.getSessionHistory().size());
    }
    @Test public void changedPreparationForAnAlreadyCommittedCommandIsRejected() throws Exception {
        startLocal(); RemoteSessionNative.PreparedStop prepared = prepare(); assertTrue(stop(prepared));
        JSONObject altered = prepared.json().put("statistics", id());
        try { stop(RemoteSessionNative.PreparedStop.parse(altered)); fail("Changed native stop preparation accepted"); }
        catch (SecurityException expected) { }
        assertEquals(1, stats.getSessionHistory().size()); assertFalse(main().getBoolean(AppModeManager.KEY_ARMED, true));
    }
    @Test public void queuedStopRechecksItsTargetAfterAnActualRuntimeLockWait() throws Exception {
        startLocal(); RemoteSessionNative.PreparedStop prepared = prepare();
        ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1); Future<Boolean> result;
        try {
            synchronized (RuntimeSessionIdentity.LOCK) {
                result = worker.submit(() -> { entered.countDown(); return stop(prepared); });
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                new AppModeManager(context).setArmed(false); new AppModeManager(context).setArmed(true);
            }
            String later = stats.activeSessionIdentity(); assertFalse(result.get(10, TimeUnit.SECONDS));
            assertEquals(later, stats.activeSessionIdentity()); assertTrue(main().getBoolean(AppModeManager.KEY_ARMED, false));
            assertNull(nativeSession.stopMarker());
        } finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    @Test public void memoryFaultKeepsRealBackingOldUntilAnAcknowledgedWrite() throws Exception {
        SharedPreferences visible = context.getSharedPreferences(StatsRepository.PREFS_NAME, 0);
        SharedPreferences backing = target.getSharedPreferences(prefix + StatsRepository.PREFS_NAME, 0);
        String old = id(); assertTrue(visible.edit().putString("active_session_identity", old).commit());
        java.io.File file = new java.io.File(target.getApplicationInfo().dataDir,
                "shared_prefs/" + prefix + StatsRepository.PREFS_NAME + ".xml");
        byte[] before = java.nio.file.Files.readAllBytes(file.toPath());
        memoryStats.set(true); assertFalse(visible.edit().remove("active_session_identity").commit());
        assertFalse(visible.contains("active_session_identity")); assertEquals(old, backing.getString("active_session_identity", ""));
        assertArrayEquals(before, java.nio.file.Files.readAllBytes(file.toPath()));
        assertTrue(visible.edit().putString("ack_probe", id()).commit());
        assertFalse(backing.contains("active_session_identity"));
        assertFalse(new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8).contains(old));
    }
    @Test public void statisticsMemoryAdvanceStillRequiresAnAcknowledgedEmptyStateBeforeReady() throws Exception {
        startLocal(); String old = stats.activeSessionIdentity(); RemoteSessionNative.PreparedStop prepared = prepare();
        memoryStats.set(true); assertFalse(stop(prepared)); assertEquals("", stats.activeSessionIdentity());
        SharedPreferences backing = target.getSharedPreferences(prefix + StatsRepository.PREFS_NAME, 0);
        assertEquals(old, backing.getString("active_session_identity", "")); assertFalse(nativeSession.stopMarker().ready);
        refuseStats.set(true); assertFalse(RemoteSessionNative.reconcile(context));
        assertFalse(nativeSession.stopMarker().ready); assertEquals(old, backing.getString("active_session_identity", "")); refuseStats.set(false);
        assertTrue(RemoteSessionNative.reconcile(context)); assertTrue(nativeSession.stopMarker().ready);
        assertEquals("Unacknowledged statistics can resurrect after process death", "", backing.getString("active_session_identity", ""));
        assertEquals(1, stats.getSessionHistory().size());
    }
    @Test public void pauseMemoryAdvanceStillRequiresAnAcknowledgedEmptyStateBeforeReady() throws Exception {
        assertTrue(CommitmentManager.start(context, 1800000)); String pause = id();
        pause().edit().putString("paid_pause_session_identity", pause).putLong("paid_pause_active_until", System.currentTimeMillis() + 60000)
                .putBoolean("paid_pause_rearm", true).commit(); RuntimeSessionIdentity.changed();
        RemoteSessionNative.PreparedStop prepared = prepare(); memoryPause.set(true); assertFalse(stop(prepared));
        assertEquals("", pause().getString("paid_pause_session_identity", ""));
        SharedPreferences backing = target.getSharedPreferences(prefix + PenanceManager.PREFS_NAME, 0);
        assertEquals(pause, backing.getString("paid_pause_session_identity", "")); assertFalse(nativeSession.stopMarker().ready);
        refusePause.set(true); assertFalse(RemoteSessionNative.reconcile(context));
        assertFalse(nativeSession.stopMarker().ready); assertEquals(pause, backing.getString("paid_pause_session_identity", "")); refusePause.set(false);
        assertTrue(RemoteSessionNative.reconcile(context)); assertTrue(nativeSession.stopMarker().ready);
        assertEquals("Unacknowledged pause can resurrect after process death", "", backing.getString("paid_pause_session_identity", ""));
        assertEquals(0L, backing.getLong("paid_pause_active_until", 0));
    }
    @Test public void ordinaryExpiryCannotRearmTheExactPauseOwnedByAPendingNativeStop() throws Exception {
        assertTrue(CommitmentManager.start(context, 1800000)); String pause = id();
        pause().edit().putString("paid_pause_session_identity", pause).putLong("paid_pause_active_until", System.currentTimeMillis() + 60000)
                .putBoolean("paid_pause_rearm", true).commit(); RuntimeSessionIdentity.changed();
        RemoteSessionNative.PreparedStop prepared = prepare(); refusePause.set(true); assertFalse(stop(prepared)); refusePause.set(false);
        pause().edit().putLong("paid_pause_active_until", System.currentTimeMillis() - 1000).commit();
        main().edit().putBoolean(com.subhub.app.security.HardcoreModeManager.KEY_REQUESTED, true).commit();
        java.lang.reflect.Method finish = com.subhub.app.penance.PaidPauseManager.class.getDeclaredMethod(
                "finishLocked", String.class, long.class, boolean.class, Boolean.class); finish.setAccessible(true);
        synchronized (RuntimeSessionIdentity.LOCK) {
            // Synthetic trusted prior admin observation; no Android Device Admin permission is granted.
            assertEquals(true, finish.invoke(new com.subhub.app.penance.PaidPauseManager(context), pause, 0L, true, Boolean.TRUE));
        }
        assertFalse("Pending native Stop must suppress its old pause rearm", main().getBoolean(AppModeManager.KEY_ARMED, false));
        assertEquals("", stats.activeSessionIdentity()); assertTrue(nativeSession.stopMarker().ready);
        String laterPause = id(); pause().edit().putString("paid_pause_session_identity", laterPause)
                .putLong("paid_pause_active_until", System.currentTimeMillis() - 1000).putBoolean("paid_pause_rearm", true).commit();
        RuntimeSessionIdentity.changed();
        synchronized (RuntimeSessionIdentity.LOCK) {
            assertEquals(true, finish.invoke(new com.subhub.app.penance.PaidPauseManager(context), laterPause, 0L, true, Boolean.TRUE));
        }
        assertTrue("A later ordinary pause keeps its existing Hardcore rearm", main().getBoolean(AppModeManager.KEY_ARMED, false));
    }
    @Test public void stopWithoutAStatisticsUuidStillRequiresAnAcknowledgedEmptyStore() throws Exception {
        assertTrue(CommitmentManager.start(context, 1800000)); new AppModeManager(context).setArmed(false);
        assertEquals("", stats.activeSessionIdentity()); assertEquals(1, stats.getSessionHistory().size());
        RemoteSessionNative.PreparedStop prepared = prepare(); assertEquals("", prepared.statistics);
        refuseStats.set(true); assertFalse(stop(prepared)); assertFalse(nativeSession.stopMarker().ready);
        refuseStats.set(false); assertTrue(RemoteSessionNative.reconcile(context)); assertTrue(nativeSession.stopMarker().ready);
        assertEquals("", stats.activeSessionIdentity()); assertEquals(1, stats.getSessionHistory().size());
        assertEquals(1, context.getSharedPreferences(StatsRepository.PREFS_NAME, 0).getInt("sessions_count", 0));
    }
    @Test public void malformedPendingStopCannotUseThePauseRearmFlag() throws Exception {
        assertTrue(CommitmentManager.start(context, 1800000)); String pause = id(), statistics = stats.activeSessionIdentity();
        pause().edit().putString("paid_pause_session_identity", pause).putLong("paid_pause_active_until", System.currentTimeMillis() + 60000)
                .putBoolean("paid_pause_rearm", true).commit(); RuntimeSessionIdentity.changed();
        refusePause.set(true); assertFalse(stop(prepare())); refusePause.set(false);
        main().edit().putString(RemoteSessionNative.STOP_MAIN_KEY, "{bad")
                .putBoolean(com.subhub.app.security.HardcoreModeManager.KEY_REQUESTED, true).commit();
        java.lang.reflect.Method finish = com.subhub.app.penance.PaidPauseManager.class.getDeclaredMethod(
                "finishLocked", String.class, long.class, boolean.class, Boolean.class); finish.setAccessible(true);
        synchronized (RuntimeSessionIdentity.LOCK) {
            assertEquals(false, finish.invoke(new com.subhub.app.penance.PaidPauseManager(context), pause, 0L, false, Boolean.TRUE));
        }
        assertFalse(main().getBoolean(AppModeManager.KEY_ARMED, false)); assertFalse(RemoteSessionNative.beforeLocalStart(context));
        assertEquals(statistics, stats.activeSessionIdentity());
    }
}
