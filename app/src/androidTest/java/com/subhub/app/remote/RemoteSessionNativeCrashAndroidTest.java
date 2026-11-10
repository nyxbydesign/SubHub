package com.subhub.app.remote;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.stats.RemoteSessionStatsFixture;
import com.subhub.app.stats.StatsRepository;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Two explicitly selected phases separated by host force-stop; the second phase checks a new PID. */
public class RemoteSessionNativeCrashAndroidTest {
    private Context context;
    private SharedPreferences journal;
    private StatsRepository stats;
    private RemoteSessionNative nativeSession;
    private final AtomicBoolean refuseStats = new AtomicBoolean(), refusePause = new AtomicBoolean();
    private final AtomicBoolean memoryStats = new AtomicBoolean(), memoryPause = new AtomicBoolean();
    private final java.util.Map<String, MemoryAdvancingPreferences> memoryStores = new java.util.concurrent.ConcurrentHashMap<>();
    private int armingWrites;
    @Before public void setup() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String id = InstrumentationRegistry.getArguments().getString("storage_id", "");
        org.junit.Assume.assumeTrue("Explicit isolated crash fixture required", id.matches("nativecrash_[A-Za-z0-9_]{1,80}"));
        context = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                SharedPreferences raw = target.getSharedPreferences(id + "-" + name, mode);
                SharedPreferences saved = StatsRepository.PREFS_NAME.equals(name)
                        || com.subhub.app.penance.PenanceManager.PREFS_NAME.equals(name)
                        ? memoryStores.computeIfAbsent(name, ignored -> new MemoryAdvancingPreferences(raw,
                                StatsRepository.PREFS_NAME.equals(name) ? memoryStats : memoryPause)) : raw;
                return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                        new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (method.getName().equals("edit")) return editor(saved.edit(), name);
                    try { return method.invoke(saved, args); } catch (InvocationTargetException error) { throw error.getCause(); }
                });
            }
        };
        journal = context.getSharedPreferences("crash-fixture", 0);
        stats = RemoteSessionStatsFixture.create(context);
        nativeSession = RemoteSessionNative.installFixture(context, () -> stats);
        RemoteSessionClock.observe(context);
    }
    private SharedPreferences.Editor editor(SharedPreferences.Editor saved, String name) {
        return (SharedPreferences.Editor) Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
            if (name.equals(SettingsRepository.PREFERENCES_NAME) && method.getName().equals("putBoolean")
                    && args[0].equals(AppModeManager.KEY_ARMED)) ++armingWrites;
            if (method.getName().equals("commit") && name.equals(StatsRepository.PREFS_NAME) && refuseStats.get()) return false;
            if (method.getName().equals("commit") && name.equals(com.subhub.app.penance.PenanceManager.PREFS_NAME)
                    && refusePause.get()) return false;
            Object value;
            try { value = method.invoke(saved, args); } catch (InvocationTargetException error) { throw error.getCause(); }
            return value == saved ? proxy : value;
        });
    }
    @After public void close() { if (nativeSession != null) nativeSession.close(); }
    private SharedPreferences main() { return context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0); }
    private RemoteSessionNative.PreparedStart prepare() throws Exception {
        assertEquals("", journal.getString("prepared", ""));
        RemoteSessionClock.Sample now = RemoteSessionClock.observe(context);
        RemoteSessionCommands.Request request = RemoteSessionCommands.Request.start(UUID.randomUUID().toString(),
                String.join("", Collections.nCopies(64, "a")), String.join("", Collections.nCopies(64, "b")),
                "crash_generation", 1, 0, now.wall, now.wall + 120000,
                RuntimeSessionIdentity.observe(context, now).idleFence,
                new RemoteSessionCommands.Duration(-1, 1800000, 3600000, false));
        RemoteSessionNative.PreparedStart prepared = RemoteSessionNative.prepare(request, now);
        assertTrue(journal.edit().putString("prepared", prepared.json().toString())
                .putInt("pid", android.os.Process.myPid()).commit());
        return prepared;
    }
    private RemoteSessionNative.PreparedStart persisted() throws Exception {
        assertNotEquals("A separate Android process must run recovery", journal.getInt("pid", -1), android.os.Process.myPid());
        assertTrue(journal.getInt("pid", -1) > 0);
        return RemoteSessionNative.PreparedStart.parse(new JSONObject(journal.getString("prepared", "")));
    }
    private int count() { return context.getSharedPreferences(StatsRepository.PREFS_NAME, 0).getInt("sessions_count", 0); }
    private void holdForHostCrash() throws InterruptedException {
        if (!"true".equals(InstrumentationRegistry.getArguments().getString("crash_hold"))) return;
        android.os.Bundle boundary = new android.os.Bundle();
        boundary.putString("native_boundary", "ready"); boundary.putInt("native_pid", android.os.Process.myPid());
        InstrumentationRegistry.getInstrumentation().sendStatus(42, boundary);
        if (!new java.util.concurrent.CountDownLatch(1).await(2, java.util.concurrent.TimeUnit.MINUTES))
            fail("Host did not terminate the live process at the committed boundary");
    }
    private void cleanup() {
        for (String name : new String[]{SettingsRepository.PREFERENCES_NAME, StatsRepository.PREFS_NAME,
                com.subhub.app.penance.PenanceManager.PREFS_NAME, "crash-fixture"})
            assertTrue(context.getSharedPreferences(name, 0).edit().clear().commit());
        stats.sessionStamp();
    }
    @Test public void seedCommittedStartBeforeStatistics() throws Exception {
        RemoteSessionNative.PreparedStart prepared = prepare(); refuseStats.set(true);
        assertFalse(nativeSession.commitPreparedStart(prepared, RemoteSessionClock.observe(context)));
        assertNotNull(nativeSession.marker()); assertEquals(0, count());
        assertTrue(main().getBoolean(AppModeManager.KEY_ARMED, false));
        holdForHostCrash();
    }
    @Test public void recoverCommittedStartWithoutRearmingOrCountingTwice() throws Exception {
        RemoteSessionNative.PreparedStart prepared = persisted(); assertEquals(0, count());
        assertTrue(RemoteSessionNative.reconcile(context)); assertEquals(0, armingWrites);
        assertTrue(RemoteSessionNative.reconcile(context)); assertEquals(1, count());
        assertEquals(prepared.statistics.identity, stats.activeSessionIdentity());
        assertTrue(main().getBoolean(AppModeManager.KEY_ARMED, false)); cleanup();
    }
    @Test public void seedLocalEndBeforeStatistics() throws Exception {
        RemoteSessionNative.PreparedStart prepared = prepare(); refuseStats.set(true);
        assertFalse(nativeSession.commitPreparedStart(prepared, RemoteSessionClock.observe(context)));
        new AppModeManager(context).setArmed(false);
        assertTrue(nativeSession.marker().ended); assertEquals(0, count());
        assertFalse(main().getBoolean(AppModeManager.KEY_ARMED, false));
        holdForHostCrash();
    }
    @Test public void recoverEndedSessionWithoutOpeningAnActiveSession() throws Exception {
        RemoteSessionNative.PreparedStart prepared = persisted();
        assertTrue(RemoteSessionNative.reconcile(context)); assertEquals(0, armingWrites);
        assertEquals(1, count()); assertEquals(1, stats.getSessionHistory().size()); assertEquals("", stats.activeSessionIdentity());
        assertFalse(nativeSession.commitPreparedStart(prepared, RemoteSessionClock.observe(context)));
        assertFalse(main().getBoolean(AppModeManager.KEY_ARMED, false)); assertEquals(1, count()); cleanup();
    }
    @Test public void seedPreparationWithoutNativeCommit() throws Exception {
        prepare(); assertNull(nativeSession.marker()); assertEquals(0, count());
        holdForHostCrash();
    }
    @Test public void recoverPreparationCannotCreateFreshSessionInANewProcess() throws Exception {
        RemoteSessionNative.PreparedStart prepared = persisted();
        assertFalse(nativeSession.commitPreparedStart(prepared, RemoteSessionClock.observe(context)));
        assertNull(nativeSession.marker()); assertEquals(0, armingWrites); assertEquals(0, count());
        assertFalse(main().getBoolean(AppModeManager.KEY_ARMED, false)); cleanup();
    }

    private RemoteSessionNative.PreparedStop prepareStop() throws Exception {
        RemoteSessionClock.Sample now = RemoteSessionClock.observe(context);
        RemoteSessionCommands.Request request = RemoteSessionCommands.Request.stop(UUID.randomUUID().toString(),
                String.join("", Collections.nCopies(64, "a")), String.join("", Collections.nCopies(64, "b")),
                "crash_generation", 2, 0, now.wall, now.wall + 120000, RuntimeSessionIdentity.observe(context, now).identity);
        RemoteSessionNative.PreparedStop prepared = nativeSession.prepareStop(request, now);
        assertTrue(journal.edit().putString("stop_prepared", prepared.json().toString()).commit());
        return prepared;
    }
    private void recoverStoppedNativeSession(boolean expectPause) throws Exception {
        RemoteSessionNative.PreparedStart start = persisted();
        RemoteSessionNative.PreparedStop stop = RemoteSessionNative.PreparedStop.parse(new JSONObject(journal.getString("stop_prepared", "")));
        if (expectPause) assertFalse(stop.pause.isEmpty());
        assertTrue(RemoteSessionNative.reconcile(context)); assertEquals(0, armingWrites);
        assertTrue(nativeSession.stopMarker().ready); assertTrue(nativeSession.marker().ended);
        assertEquals(1, count()); assertEquals(1, stats.getSessionHistory().size()); assertEquals("", stats.activeSessionIdentity());
        assertEquals(0L, context.getSharedPreferences(com.subhub.app.penance.PenanceManager.PREFS_NAME, 0)
                .getLong("paid_pause_active_until", 0L));
        assertFalse(main().getBoolean(AppModeManager.KEY_ARMED, true));
        assertTrue(nativeSession.commitPreparedStop(stop, RemoteSessionClock.observe(context)));
        assertFalse(nativeSession.commitPreparedStart(start, RemoteSessionClock.observe(context)));
        assertEquals(0, armingWrites); assertEquals(1, count()); assertEquals(1, stats.getSessionHistory().size()); cleanup();
    }
    @Test public void seedNativeStopBeforeStatistics() throws Exception {
        RemoteSessionNative.PreparedStart start = prepare();
        assertTrue(nativeSession.commitPreparedStart(start, RemoteSessionClock.observe(context))); assertEquals(1, count());
        RemoteSessionNative.PreparedStop stop = prepareStop(); refuseStats.set(true);
        assertFalse(nativeSession.commitPreparedStop(stop, RemoteSessionClock.observe(context)));
        assertTrue(nativeSession.marker().ended); assertFalse(nativeSession.stopMarker().ready);
        assertEquals(start.statistics.identity, stats.activeSessionIdentity());
        assertFalse(main().getBoolean(AppModeManager.KEY_ARMED, true)); holdForHostCrash();
    }
    @Test public void recoverNativeStopStatisticsWithoutRearmingOrDoubleCounting() throws Exception {
        recoverStoppedNativeSession(false);
    }
    @Test public void seedNativeStopBeforePauseCleanup() throws Exception {
        RemoteSessionNative.PreparedStart start = prepare();
        assertTrue(nativeSession.commitPreparedStart(start, RemoteSessionClock.observe(context)));
        String pause = UUID.randomUUID().toString();
        assertTrue(context.getSharedPreferences(com.subhub.app.penance.PenanceManager.PREFS_NAME, 0).edit()
                .putString("paid_pause_session_identity", pause).putLong("paid_pause_active_until", System.currentTimeMillis() + 60000)
                .putBoolean("paid_pause_rearm", true).commit()); RuntimeSessionIdentity.changed();
        RemoteSessionNative.PreparedStop stop = prepareStop(); refusePause.set(true);
        assertFalse(nativeSession.commitPreparedStop(stop, RemoteSessionClock.observe(context)));
        assertTrue(nativeSession.marker().ended); assertFalse(nativeSession.stopMarker().ready);
        assertEquals("", stats.activeSessionIdentity()); assertEquals(1, stats.getSessionHistory().size());
        assertEquals(pause, context.getSharedPreferences(com.subhub.app.penance.PenanceManager.PREFS_NAME, 0)
                .getString("paid_pause_session_identity", ""));
        assertFalse(main().getBoolean(AppModeManager.KEY_ARMED, true)); holdForHostCrash();
    }
    @Test public void recoverNativeStopPauseCleanupWithoutUsingItsRearmFlag() throws Exception {
        recoverStoppedNativeSession(true);
    }
    private void seedStoppedAfterMemoryOnlyFailure(boolean pauseFailure) throws Exception {
        new AppModeManager(context).setArmed(true); String statistics = stats.activeSessionIdentity();
        assertFalse(statistics.isEmpty()); assertNull(nativeSession.marker());
        if (pauseFailure) {
            assertTrue(context.getSharedPreferences(com.subhub.app.penance.PenanceManager.PREFS_NAME, 0).edit()
                    .putString("paid_pause_session_identity", UUID.randomUUID().toString())
                    .putLong("paid_pause_active_until", System.currentTimeMillis() + 60000).putBoolean("paid_pause_rearm", true).commit());
            RuntimeSessionIdentity.changed();
        }
        assertTrue(journal.edit().putInt("pid", android.os.Process.myPid()).putString("old_statistics", statistics)
                .putBoolean("memory_pause_failure", pauseFailure).commit());
        RemoteSessionNative.PreparedStop prepared = prepareStop();
        (pauseFailure ? memoryPause : memoryStats).set(true);
        assertFalse(nativeSession.commitPreparedStop(prepared, RemoteSessionClock.observe(context)));
        assertFalse(nativeSession.stopMarker().ready); assertTrue(RemoteSessionNative.reconcile(context));
        assertTrue(nativeSession.stopMarker().ready); assertEquals("", stats.activeSessionIdentity());
        assertEquals(1, count()); assertEquals(1, stats.getSessionHistory().size());
        assertFalse(main().getBoolean(AppModeManager.KEY_ARMED, true)); holdForHostCrash();
    }
    private void recoverStoppedAfterMemoryOnlyFailure(boolean pauseFailure) throws Exception {
        assertNotEquals(journal.getInt("pid", -1), android.os.Process.myPid()); assertTrue(journal.getInt("pid", -1) > 0);
        assertEquals(pauseFailure, journal.getBoolean("memory_pause_failure", !pauseFailure));
        RemoteSessionNative.PreparedStop prepared = RemoteSessionNative.PreparedStop.parse(new JSONObject(journal.getString("stop_prepared", "")));
        assertTrue(nativeSession.stopMarker().ready); assertNull(nativeSession.marker());
        // This is a new process with no fixture overlay: these values come from Android's real preference files.
        assertEquals("", stats.activeSessionIdentity()); assertEquals(1, count()); assertEquals(1, stats.getSessionHistory().size());
        SharedPreferences pause = context.getSharedPreferences(com.subhub.app.penance.PenanceManager.PREFS_NAME, 0);
        assertEquals("", pause.getString("paid_pause_session_identity", "")); assertEquals(0L, pause.getLong("paid_pause_active_until", 0));
        assertTrue(RemoteSessionNative.reconcile(context)); assertTrue(nativeSession.commitPreparedStop(prepared, RemoteSessionClock.observe(context)));
        assertEquals(0, armingWrites); assertFalse(main().getBoolean(AppModeManager.KEY_ARMED, true));
        assertEquals("", stats.activeSessionIdentity()); assertEquals(1, stats.getSessionHistory().size()); cleanup();
    }
    @Test public void seedStoppedStatisticsAfterMemoryOnlyFailure() throws Exception { seedStoppedAfterMemoryOnlyFailure(false); }
    @Test public void recoverStoppedStatisticsAfterMemoryOnlyFailure() throws Exception { recoverStoppedAfterMemoryOnlyFailure(false); }
    @Test public void seedStoppedPauseAfterMemoryOnlyFailure() throws Exception { seedStoppedAfterMemoryOnlyFailure(true); }
    @Test public void recoverStoppedPauseAfterMemoryOnlyFailure() throws Exception { recoverStoppedAfterMemoryOnlyFailure(true); }
}
