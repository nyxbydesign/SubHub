package com.subhub.app.remote;

import static org.junit.Assert.*;
import static com.subhub.app.remote.RemoteSessionCommands.*;

import android.content.Context;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.settings.SettingsRepository;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Reuses the unchanged owned controller fixture; its admin/binding observations are synthetic. */
public final class RemoteSessionQueryControllerAndroidTest {
    private RemoteSessionNativeControllerAndroidTest fixture;
    private Context context;
    private RemoteStore store;
    private RemoteSessionNative nativeSession;
    private RemoteSessionNativeController controller;
    @Before public void setup() throws Exception {
        fixture = new RemoteSessionNativeControllerAndroidTest(); fixture.setup();
        context = (Context) field("context"); store = (RemoteStore) field("store");
        nativeSession = (RemoteSessionNative) field("nativeSession"); controller = (RemoteSessionNativeController) field("controller");
    }
    @After public void cleanup() throws Exception { if (fixture != null) fixture.cleanup(); }
    private Object field(String name) throws Exception {
        Field field = RemoteSessionNativeControllerAndroidTest.class.getDeclaredField(name); field.setAccessible(true); return field.get(fixture);
    }
    private Request request() throws Exception {
        Method method = RemoteSessionNativeControllerAndroidTest.class.getDeclaredMethod("start", boolean.class); method.setAccessible(true);
        try { return (Request) method.invoke(fixture, false); }
        catch (InvocationTargetException failure) { throw (Exception) failure.getCause(); }
    }
    private RemoteSessionNative.PreparedStart prepare(Request request) throws Exception {
        RemoteSessionClock.Sample now = RemoteSessionClock.observe(context);
        RemoteSessionNative.PreparedStart prepared = RemoteSessionNative.prepare(request, now);
        assertEquals(State.APPLYING, store.prepareNativeSessionControl(request.sender, request.generation, request.id, prepared.json(), now).result.state);
        assertTrue(RemoteSessionNative.currentProcess(prepared.process)); assertNull(nativeSession.marker());
        return prepared;
    }
    private int count() { return context.getSharedPreferences(com.subhub.app.stats.StatsRepository.PREFS_NAME, 0).getInt("sessions_count", 0); }
    @Test public void reconcileOnlyPreparedStartCannotCommitFirstEffectWithFreshReceiverClock() throws Exception {
        Request request = request(); prepare(request);
        RemoteSessionRuntime.Record before = store.sessionControl(request.sender, request.generation, RemoteSessionRuntime.Direction.INCOMING, request.id);
        assertEquals(Reason.NONE, before.lifetime.check(request, RemoteSessionClock.observe(context)));
        assertEquals(State.EXPIRED, controller.reconcile(request.sender, request.generation, request.id).result.state);
        assertNull(nativeSession.marker()); assertFalse(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).getBoolean(AppModeManager.KEY_ARMED, false));
        assertEquals(0, count()); assertEquals(0, ((Integer) field("armingWrites")).intValue());
        RemoteSessionRuntime.Record after = store.sessionControl(request.sender, request.generation, RemoteSessionRuntime.Direction.INCOMING, request.id);
        assertEquals(before.request.digest(), after.request.digest()); assertEquals(before.request.expiresAt, after.request.expiresAt);
        assertEquals(before.lifetime.receivedWall, after.lifetime.receivedWall); assertEquals(before.lifetime.deadlineElapsed, after.lifetime.deadlineElapsed);
    }
    @Test public void reconcileOnlyCommittedStartFinishesStatisticsWithoutNewArmingWrite() throws Exception {
        Request request = request(); RemoteSessionNative.PreparedStart prepared = prepare(request);
        AtomicBoolean refuseStats = (AtomicBoolean) field("refuseStats"); refuseStats.set(true);
        assertFalse(nativeSession.commitPreparedStart(prepared, RemoteSessionClock.observe(context)));
        assertNotNull(nativeSession.marker()); assertFalse(nativeSession.marker().statisticsReady); assertEquals(0, count());
        int writes = (Integer) field("armingWrites"); refuseStats.set(false);
        RemoteSessionRuntime.Record result = controller.reconcile(request.sender, request.generation, request.id);
        assertEquals(State.APPLIED, result.result.state); assertEquals(1, count()); assertTrue(nativeSession.marker().statisticsReady);
        assertEquals(writes, ((Integer) field("armingWrites")).intValue());
        assertEquals(RemoteProtocol.canonical(result.result.json()), RemoteProtocol.canonical(controller.reconcile(request.sender, request.generation, request.id).result.json()));
        assertEquals(1, count()); assertEquals(writes, ((Integer) field("armingWrites")).intValue());
    }
    @Test public void ordinaryPreparedStartStillCommitsItsFirstEffect() throws Exception {
        Request request = request(); prepare(request);
        assertEquals(State.APPLIED, controller.advance(request.sender, request.generation, request.id).result.state);
        assertNotNull(nativeSession.marker()); assertEquals(1, count()); assertEquals(1, ((Integer) field("armingWrites")).intValue());
    }
}
