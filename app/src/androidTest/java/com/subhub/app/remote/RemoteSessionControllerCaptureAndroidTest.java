package com.subhub.app.remote;

import static org.junit.Assert.*;
import static com.subhub.app.remote.RemoteSessionCommands.*;
import static com.subhub.app.remote.RemoteSessionRuntime.Direction.INCOMING;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.service.RemoteSessionCaptureOwnershipAndroidTest;
import com.subhub.app.service.ScreenCaptureService;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.stats.StatsRepository;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Select the two declared methods only. Inherited fixture owns restoration and the synthetic capture. */
public final class RemoteSessionControllerCaptureAndroidTest extends RemoteSessionCaptureOwnershipAndroidTest {
    private static final String DOM = String.join("", Collections.nCopies(64, "a"));
    private static final String SUB = String.join("", Collections.nCopies(64, "b")), GEN = "controller_capture";
    private Context context;
    private StatsRepository stats;
    private RemoteStore store;
    private String database;
    private RemoteSessionNativeController controller;
    private final CountDownLatch releaseMain = new CountDownLatch(1);

    @Before public void controllerSetup() throws Exception {
        context = (Context) inheritedField("context"); stats = (StatsRepository) inheritedField("stats");
        database = "controller-capture-" + UUID.randomUUID() + ".db"; store = new RemoteStore(context, database);
        store.getWritableDatabase().execSQL("INSERT INTO peers(peer,generation,name,local_role,state) VALUES(?,?,'Synthetic','SUB','ACTIVE')", new Object[]{DOM, GEN});
        store.putMetadata(DOM, GEN, "session_control_version", "1");
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                .putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.SUB_ONLY.name()).commit();
        controller = new RemoteSessionNativeController(context, store, SUB);
    }
    @After public void controllerCleanup() {
        releaseMain.countDown();
        if (store != null) store.close();
        if (database != null) context.deleteDatabase(database);
    }
    private Object inheritedField(String name) throws Exception {
        Field value = RemoteSessionCaptureOwnershipAndroidTest.class.getDeclaredField(name); value.setAccessible(true); return value.get(this);
    }
    private Object inheritedCall(String name) throws Exception {
        Method method = RemoteSessionCaptureOwnershipAndroidTest.class.getDeclaredMethod(name); method.setAccessible(true); return method.invoke(this);
    }
    private ScreenCaptureService.StopTarget capture() throws Exception {
        inheritedCall("admitSyntheticCapture");
        ScreenCaptureService.StopTarget target = ScreenCaptureService.observedStopTarget(context); assertNotNull(target); return target;
    }
    private Request stop() throws Exception {
        RemoteSessionClock.Sample now = RemoteSessionClock.observe(context);
        Request request = Request.stop(UUID.randomUUID().toString(), DOM, SUB, GEN, 1, 0, now.wall, now.wall + 120000,
                RuntimeSessionIdentity.observe(context, now).identity);
        store.rememberSessionControl(DOM, GEN, INCOMING, request, now); return request;
    }
    private void blockMain() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1);
        assertTrue(new Handler(Looper.getMainLooper()).post(() -> {
            blocked.countDown();
            try { if (!releaseMain.await(10, TimeUnit.SECONDS)) throw new AssertionError("Main fixture was not released"); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
        }));
        assertTrue(blocked.await(5, TimeUnit.SECONDS));
    }
    @Test public void controllerRetainsApplyingUntilOriginalAndroidServiceResourcesActuallyClose() throws Exception {
        ScreenCaptureService.StopTarget target = capture(); Request request = stop(); blockMain();
        RemoteSessionRuntime.Record pending;
        try {
            pending = controller.advance(DOM, GEN, request.id);
            assertEquals(State.APPLYING, pending.result.state); assertNull(pending.result.proof); assertFalse(target.isClosed());
            assertEquals("", stats.activeSessionIdentity());
        } finally { releaseMain.countDown(); }
        inheritedCall("assertOwnResourcesClosed"); assertTrue(target.isClosed());
        RemoteSessionRuntime.Record complete = controller.advance(DOM, GEN, request.id);
        assertEquals(State.APPLIED, complete.result.state); assertFalse(complete.result.proof.active);
        assertEquals(1, stats.getSessionHistory().size());
    }
    @Test public void controllerCannotReportStoppedOrRetireReplacementStateWhileOriginalStopIsQueued() throws Exception {
        ScreenCaptureService.StopTarget target = capture(); Request request = stop(); blockMain();
        String replacement;
        try {
            assertEquals(State.APPLYING, controller.advance(DOM, GEN, request.id).result.state); assertFalse(target.isClosed());
            new AppModeManager(context).setArmed(true); replacement = stats.activeSessionIdentity(); assertFalse(replacement.isEmpty());
        } finally { releaseMain.countDown(); }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        RemoteSessionRuntime.Record result = controller.advance(DOM, GEN, request.id);
        assertEquals(State.REJECTED, result.result.state); assertEquals(Reason.SESSION_CHANGED, result.result.reason); assertNull(result.result.proof);
        assertTrue(new AppModeManager(context).isArmed()); assertEquals(replacement, stats.activeSessionIdentity());
        // Historical teardown remains owned by this fixture and must preserve that replacement too.
        context.stopService(new Intent(context, ScreenCaptureService.class)); inheritedCall("assertOwnResourcesClosed");
        assertTrue(target.isClosed()); assertTrue(new AppModeManager(context).isArmed()); assertEquals(replacement, stats.activeSessionIdentity());
    }
    @Test public void anUnclosedKnownCaptureBecomesExplicitFailureAtItsRecordedDeadline() throws Exception {
        ScreenCaptureService.StopTarget target = capture(); Request request = stop(); blockMain();
        try {
            assertEquals(State.APPLYING, controller.advance(DOM, GEN, request.id).result.state); assertFalse(target.isClosed());
            // Accelerate only this fixture's recorded elapsed deadline; keep its request/marker/owner unchanged.
            store.getWritableDatabase().execSQL("UPDATE session_control_journal SET deadline_elapsed=received_elapsed "
                    + "WHERE peer=? AND generation=? AND direction='INCOMING' AND id=?", new Object[]{DOM, GEN, request.id});
            RemoteSessionRuntime.Record result = controller.advance(DOM, GEN, request.id);
            assertEquals(State.FAILED, result.result.state); assertEquals(Reason.APPLICATION_FAILED, result.result.reason);
            assertNull(result.result.proof); assertFalse(target.isClosed());
        } finally { releaseMain.countDown(); }
        inheritedCall("assertOwnResourcesClosed"); assertTrue(target.isClosed());
        assertEquals(State.FAILED, controller.advance(DOM, GEN, request.id).result.state);
    }
}
