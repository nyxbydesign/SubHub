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
    private final AtomicBoolean refuseStats = new AtomicBoolean();
    private int armingWrites;
    @Before public void setup() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String id = InstrumentationRegistry.getArguments().getString("storage_id", "");
        org.junit.Assume.assumeTrue("Explicit isolated crash fixture required", id.matches("nativecrash_[A-Za-z0-9_]{1,80}"));
        context = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                SharedPreferences saved = target.getSharedPreferences(id + "-" + name, mode);
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
        for (String name : new String[]{SettingsRepository.PREFERENCES_NAME, StatsRepository.PREFS_NAME, "crash-fixture"})
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
}
