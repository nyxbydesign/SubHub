package com.subhub.app.remote;

import static org.junit.Assert.*;
import static com.subhub.app.remote.RemoteSessionCommands.*;
import static com.subhub.app.remote.RemoteSessionRuntime.Direction.INCOMING;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.stats.RemoteSessionStatsFixture;
import com.subhub.app.stats.StatsRepository;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Controller-reachable accounting with private preferences. No OS clock writes or permission grants. */
public final class RemoteSessionStopClockAndroidTest {
    private static final String DOM = String.join("", Collections.nCopies(64, "a"));
    private static final String SUB = String.join("", Collections.nCopies(64, "b")), GEN = "controller_generation";
    private final RemoteSessionNativeControllerAndroidTest fixture = new RemoteSessionNativeControllerAndroidTest();
    private Context context;
    private RemoteStore store;
    private StatsRepository stats;
    private RemoteSessionNative nativeSession;
    private RemoteSessionNativeController controller;
    private AtomicBoolean refuseStats;
    private long before, after, started;
    private Request request;
    @Before public void setup() throws Exception {
        fixture.setup(); context = (Context) field("context"); store = (RemoteStore) field("store");
        stats = (StatsRepository) field("stats"); nativeSession = (RemoteSessionNative) field("nativeSession");
        controller = (RemoteSessionNativeController) field("controller"); refuseStats = (AtomicBoolean) field("refuseStats");
    }
    @After public void cleanup() throws Exception { fixture.cleanup(); }
    private Object field(String name) throws Exception {
        Field value = RemoteSessionNativeControllerAndroidTest.class.getDeclaredField(name); value.setAccessible(true); return value.get(fixture);
    }
    private void field(String name, Object next) throws Exception {
        Field value = RemoteSessionNativeControllerAndroidTest.class.getDeclaredField(name); value.setAccessible(true); value.set(fixture, next);
    }
    private SharedPreferences main() { return context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0); }
    private SharedPreferences savedStats() { return context.getSharedPreferences(StatsRepository.PREFS_NAME, 0); }
    private SharedPreferences journal() { return context.getSharedPreferences("controller_crash_fixture", 0); }
    private void prepareDelayedStop(boolean nativeOwned) throws Exception {
        if (nativeOwned) {
            RemoteSessionClock.Sample now = RemoteSessionClock.observe(context);
            Request start = Request.start(UUID.randomUUID().toString(), DOM, SUB, GEN, 999, 7, now.wall, now.wall + 120000,
                    RuntimeSessionIdentity.observe(context, now).idleFence, new Duration(-1, 3600000, 86400000, false));
            assertTrue(nativeSession.commitPreparedStart(RemoteSessionNative.prepare(start, now), now));
        } else {
            new AppModeManager(context).setArmed(true);
            // Age only this synthetic session's private start; the operating system clocks remain untouched.
            assertTrue(savedStats().edit().putLong("active_session_start_ms", System.currentTimeMillis() - 10000).commit());
        }
        started = savedStats().getLong("active_session_start_ms", 0); assertTrue(started > 0);
        RemoteSessionClock.Sample now = RemoteSessionClock.observe(context);
        request = Request.stop(UUID.randomUUID().toString(), DOM, SUB, GEN, 1, 7, now.wall, now.wall + 120000,
                RuntimeSessionIdentity.observe(context, now).identity);
        store.rememberSessionControl(DOM, GEN, INCOMING, request, now);
        store.prepareNativeSessionControl(DOM, GEN, request.id, nativeSession.prepareStop(request, now).json(), now);
        main().edit().putBoolean(HardcoreModeManager.KEY_REQUESTED, false).commit();
        SystemClock.sleep(1200); // Distinguish preparation time from the subsequent native Stop transition.
        refuseStats.set(true); before = System.currentTimeMillis();
        assertEquals(State.APPLYING, controller.advance(DOM, GEN, request.id).result.state);
        after = System.currentTimeMillis(); assertTrue(after >= before);
        assertFalse(main().getBoolean(AppModeManager.KEY_ARMED, true)); assertEquals(0, stats.getSessionHistory().size());
        assertFalse(nativeSession.stopMarker().ready);
    }
    private void reopen() throws Exception {
        refuseStats.set(false); nativeSession.close(); stats = RemoteSessionStatsFixture.create(context);
        StatsRepository reopened = stats; nativeSession = RemoteSessionNative.installFixture(context, () -> reopened);
        controller = new RemoteSessionNativeController(context, store, SUB, (RemoteSessionReadiness.Source) field("source"));
        field("stats", stats); field("nativeSession", nativeSession); field("controller", controller);
    }
    private void verifyOriginalClock() throws Exception {
        assertEquals(State.APPLIED, controller.advance(DOM, GEN, request.id).result.state);
        long ended = savedStats().getLong("remote_native_ended_at", 0);
        android.os.Bundle observedClock = new android.os.Bundle(); observedClock.putLong("clock_ended_at", ended);
        observedClock.putLong("clock_stop_before", before); observedClock.putLong("clock_stop_after", after);
        InstrumentationRegistry.getInstrumentation().sendStatus(44, observedClock);
        assertTrue("End timestamp predates the actual Stop transition", ended >= before);
        assertTrue("Recovery time inflated the original Stop end timestamp", ended <= after);
        assertEquals(1, stats.getSessionHistory().size()); StatsRepository.SessionEntry entry = stats.getSessionHistory().get(0);
        assertEquals(started, entry.getStartMillis());
        long lower = Math.max(0, (before - started) / 1000), upper = Math.max(0, (after - started) / 1000);
        assertTrue("Recovery delay inflated session history duration", entry.getDurationSeconds() >= lower && entry.getDurationSeconds() <= upper);
        assertEquals(entry.getDurationSeconds(), stats.load().getTotalSessionSeconds());
        assertEquals(entry.getDurationSeconds(), stats.load().getTotalProtectedSeconds());
        assertEquals(State.APPLIED, controller.advance(DOM, GEN, request.id).result.state);
        assertEquals(ended, savedStats().getLong("remote_native_ended_at", 0)); assertEquals(1, stats.getSessionHistory().size());
    }
    @Test public void localStopRetainsActualTransitionClockAcrossDelayedStatisticsReopen() throws Exception {
        prepareDelayedStop(false); SystemClock.sleep(2200); reopen(); verifyOriginalClock();
    }
    @Test public void nativeStartOwnedStopRetainsItsOriginalClockAcrossDelayedStatisticsReopen() throws Exception {
        prepareDelayedStop(true); SystemClock.sleep(2200); reopen(); verifyOriginalClock();
    }
    @Test public void seedLocalStopClockBeforeStatisticsFailure() throws Exception {
        String storage = InstrumentationRegistry.getArguments().getString("storage_id", "");
        org.junit.Assume.assumeTrue("Explicit host crash fixture required", storage.matches("controllercrash_[a-z0-9]{32}"));
        prepareDelayedStop(false);
        assertTrue(journal().edit().putString("clock_request", RemoteProtocol.canonical(request.json())).putInt("clock_pid", android.os.Process.myPid())
                .putLong("clock_before", before).putLong("clock_after", after).putLong("clock_started", started).commit());
        android.os.Bundle boundary = new android.os.Bundle(); boundary.putString("native_boundary", "ready"); boundary.putInt("native_pid", android.os.Process.myPid());
        InstrumentationRegistry.getInstrumentation().sendStatus(42, boundary);
        if (!new CountDownLatch(1).await(2, TimeUnit.MINUTES)) fail("Host did not terminate the committed local Stop fixture");
    }
    @Test public void recoverLocalStopClockAfterActualProcessDeath() throws Exception {
        String storage = InstrumentationRegistry.getArguments().getString("storage_id", "");
        org.junit.Assume.assumeTrue("Explicit host crash fixture required", storage.matches("controllercrash_[a-z0-9]{32}"));
        assertTrue(journal().getInt("clock_pid", -1) > 0); assertNotEquals(journal().getInt("clock_pid", -1), android.os.Process.myPid());
        request = Request.parse(new JSONObject(journal().getString("clock_request", "")));
        before = journal().getLong("clock_before", 0); after = journal().getLong("clock_after", 0); started = journal().getLong("clock_started", 0);
        assertTrue(before > 0 && after >= before && started > 0); verifyOriginalClock();
        android.os.Bundle observed = new android.os.Bundle(); observed.putInt("recovered_pid", android.os.Process.myPid());
        InstrumentationRegistry.getInstrumentation().sendStatus(43, observed);
    }
    @Test public void legacyUnreadyLocalMarkerWithoutAnEndWitnessCannotInventHistory() throws Exception {
        prepareDelayedStop(false); RemoteSessionNative.PreparedStop prepared = nativeSession.stopMarker().prepared;
        assertTrue(main().edit().putString(RemoteSessionNative.STOP_MAIN_KEY,
                RemoteProtocol.canonical(new RemoteSessionNative.StopMarker(prepared, false).json())).commit());
        refuseStats.set(false); assertFalse(RemoteSessionNative.reconcile(context));
        assertEquals(prepared.statistics, stats.activeSessionIdentity()); assertEquals(0, stats.getSessionHistory().size());
        assertFalse(nativeSession.stopMarker().ready); assertEquals(1, nativeSession.stopMarker().json().getInt("schema"));
    }
    @Test public void legacyAlreadyEndedReceiptCanAcknowledgeLostReadyCacheWithoutRewritingHistory() throws Exception {
        prepareDelayedStop(false); refuseStats.set(false); assertTrue(RemoteSessionNative.reconcile(context));
        RemoteSessionNative.PreparedStop prepared = nativeSession.stopMarker().prepared;
        long ended = savedStats().getLong("remote_native_ended_at", 0); long duration = stats.getSessionHistory().get(0).getDurationSeconds();
        assertTrue(main().edit().putString(RemoteSessionNative.STOP_MAIN_KEY,
                RemoteProtocol.canonical(new RemoteSessionNative.StopMarker(prepared, false).json())).commit());
        assertTrue(RemoteSessionNative.reconcile(context)); assertTrue(nativeSession.stopMarker().ready);
        assertEquals(ended, savedStats().getLong("remote_native_ended_at", 0)); assertEquals(1, stats.getSessionHistory().size());
        assertEquals(duration, stats.getSessionHistory().get(0).getDurationSeconds());
    }
    @Test public void legacyNativeStartOwnedMarkerUsesItsKnownEndClock() throws Exception {
        prepareDelayedStop(true); RemoteSessionNative.PreparedStop prepared = nativeSession.stopMarker().prepared;
        assertTrue(main().edit().putString(RemoteSessionNative.STOP_MAIN_KEY,
                RemoteProtocol.canonical(new RemoteSessionNative.StopMarker(prepared, false).json())).commit());
        refuseStats.set(false); verifyOriginalClock(); assertTrue(nativeSession.stopMarker().ready);
    }
    @Test public void newEndMarkerRoundTripsActualTransitionAndRejectsCorruptOrChangedOrigin() throws Exception {
        prepareDelayedStop(false); RemoteSessionNative.StopMarker marker = nativeSession.stopMarker(); JSONObject original = marker.json();
        assertEquals(2, original.getInt("schema")); assertNotNull(marker.end); assertFalse(marker.end.nativeOwned);
        assertTrue(marker.end.clock.wall >= before && marker.end.clock.wall <= after);
        assertTrue(marker.end.clock.elapsed >= marker.prepared.clock.elapsed + 1000); assertEquals(started, marker.end.statisticsStartedAt);
        assertEquals(RemoteProtocol.canonical(original), RemoteProtocol.canonical(RemoteSessionNative.StopMarker.parse(original).json()));
        refuseStats.set(false);
        for (String mutation : new String[]{"missing", "extra", "negative", "basis", "clock", "origin"}) {
            JSONObject damaged = new JSONObject(RemoteProtocol.canonical(original)); JSONObject end = damaged.getJSONObject("end");
            if (mutation.equals("missing")) end.remove("durationSeconds");
            if (mutation.equals("extra")) end.put("unknown", true);
            if (mutation.equals("negative")) end.put("durationSeconds", -1);
            if (mutation.equals("basis")) end.put("nativeOwned", "false");
            if (mutation.equals("clock")) end.put("boot", -1);
            if (mutation.equals("origin")) {
                long changed = started + 1000; end.put("statisticsStartedAt", changed)
                        .put("durationSeconds", Math.max(0, (marker.end.clock.wall - changed) / 1000));
            }
            assertTrue(main().edit().putString(RemoteSessionNative.STOP_MAIN_KEY, RemoteProtocol.canonical(damaged)).commit());
            assertFalse(mutation, RemoteSessionNative.reconcile(context)); assertEquals(0, stats.getSessionHistory().size());
            assertEquals(marker.prepared.statistics, stats.activeSessionIdentity());
        }
        assertTrue(main().edit().putString(RemoteSessionNative.STOP_MAIN_KEY, RemoteProtocol.canonical(original)).commit());
        verifyOriginalClock();
    }
    @Test public void nativeOwnedCorruptStopWitnessCannotHideBehindAlreadyEndedStatistics() throws Exception {
        prepareDelayedStop(true); RemoteSessionNative.StopMarker marker = nativeSession.stopMarker();
        assertTrue(marker.end.nativeOwned); long originalDuration = marker.end.durationSeconds;
        JSONObject original = marker.json(), damaged = new JSONObject(RemoteProtocol.canonical(original));
        damaged.getJSONObject("end").put("durationSeconds", originalDuration + 1);
        assertTrue(main().edit().putString(RemoteSessionNative.STOP_MAIN_KEY, RemoteProtocol.canonical(damaged)).commit());
        refuseStats.set(false);
        assertFalse("An already-ended receipt must not hide a mismatched durable Stop witness", RemoteSessionNative.reconcile(context));
        assertFalse(nativeSession.stopMarker().ready); assertTrue(nativeSession.marker().statisticsReady);
        assertEquals("", stats.activeSessionIdentity()); assertEquals(1, stats.getSessionHistory().size());
        assertEquals(originalDuration, stats.getSessionHistory().get(0).getDurationSeconds());
        long ended = savedStats().getLong("remote_native_ended_at", 0);
        assertTrue(main().edit().putString(RemoteSessionNative.STOP_MAIN_KEY, RemoteProtocol.canonical(original)).commit());
        assertTrue(RemoteSessionNative.reconcile(context)); assertTrue(nativeSession.stopMarker().ready);
        assertEquals(ended, savedStats().getLong("remote_native_ended_at", 0)); assertEquals(1, stats.getSessionHistory().size());
    }
}
