package com.subhub.app.remote;

import static org.junit.Assert.*;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.security.SubFeaturePolicy;
import com.subhub.app.service.ScreenshotAccessibilityService;
import com.subhub.app.settings.CaptureMethod;
import java.lang.reflect.Field;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Source eligibility tests reuse the owned fixture. Synthetic admin/binding are never OS acceptance. */
public final class RemoteSessionCoordinatorSourceAndroidTest {
    private RemoteSessionNativeControllerAndroidTest fixture;
    private android.content.Context context;
    private RemoteStore store;
    private RemoteSessionNative nativeSession;
    private int clocks;
    private boolean throwStart;
    private final RemoteSessionReadiness.Source source = new RemoteSessionReadiness.Source() {
        private void outside() {
            assertFalse(Thread.holdsLock(store)); assertFalse(Thread.holdsLock(SubFeaturePolicy.LOCK));
            assertFalse(Thread.holdsLock(RuntimeSessionIdentity.LOCK)); assertFalse(store.getWritableDatabase().inTransaction());
        }
        public RemoteSessionClock.Sample clock() { outside(); clocks++; return RemoteSessionClock.observe(context); }
        public int sdk() { outside(); return 35; }
        public boolean adminActive() { outside(); if (throwStart) throw new IllegalStateException("Synthetic Start provider unavailable"); return true; }
        public boolean accessibilityEnabled() { outside(); return true; }
        public boolean accessibilityBound() { outside(); return true; }
        public CaptureMethod captureMethod() { outside(); return CaptureMethod.APP_MODE; }
    };
    @Before public void setup() throws Exception {
        fixture = new RemoteSessionNativeControllerAndroidTest(); fixture.setup();
        context = (android.content.Context) field("context"); store = (RemoteStore) field("store"); nativeSession = (RemoteSessionNative) field("nativeSession");
    }
    @After public void cleanup() throws Exception { if (fixture != null) fixture.cleanup(); }
    private Object field(String name) throws Exception {
        Field field = RemoteSessionNativeControllerAndroidTest.class.getDeclaredField(name); field.setAccessible(true); return field.get(fixture);
    }
    private RemoteStore.Peer peer() { return store.peers(false).get(0); }
    private String self() { return String.join("", java.util.Collections.nCopies(64, "b")); }
    @Test public void syntheticReadySourceBindsCurrentConsentAndConfigurationWithoutApplyingAnything() throws Exception {
        RemoteSessionControlCoordinator coordinator = new RemoteSessionControlCoordinator(context, store, self(), source);
        RemoteSessionControlState state = coordinator.receiverState(peer()); assertTrue(state.canStart); assertFalse(state.canStop);
        assertFalse(state.active); assertEquals(RemoteSessionCommands.Reason.NONE, state.reason); assertEquals(7, state.appliedRevision);
        assertNull(nativeSession.marker()); assertNull(nativeSession.stopMarker());
        context.getSharedPreferences(com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME, 0).edit()
                .putBoolean(com.subhub.app.security.HardcoreModeManager.KEY_REQUESTED, false).commit();
        state = coordinator.receiverState(peer()); assertFalse(state.canStart); assertFalse(state.canStop);
        assertEquals(RemoteSessionCommands.Reason.HARDCORE_REQUIRED, state.reason); assertNull(nativeSession.marker());
    }
    @Test public void throwingStartProviderDoesNotHideFreshStopClockAndExactActiveIdentity() throws Exception {
        new AppModeManager(context).setArmed(true); throwStart = true;
        RemoteSessionClock.Sample now = RemoteSessionClock.observe(context);
        String identity = RuntimeSessionIdentity.observe(context, now).identity;
        RemoteSessionControlState state = new RemoteSessionControlCoordinator(context, store, self(), source).receiverState(peer());
        assertTrue(state.active); assertTrue(state.canStop); assertFalse(state.canStart); assertEquals(identity, state.sessionIdentity);
        assertEquals(RemoteSessionRuntime.Readiness.UNAVAILABLE, state.readiness); assertTrue(clocks >= 3);
        assertNull(nativeSession.stopMarker()); assertTrue(new AppModeManager(context).isArmed());
    }
    @Test public void actualUnboundSourceCannotAdvertiseStartAndNoBindingIsGranted() throws Exception {
        ((AutoCloseable) field("binding")).close(); assertFalse(ScreenshotAccessibilityService.isRunning());
        RemoteSessionControlState state = new RemoteSessionControlCoordinator(context, store, self()).receiverState(peer());
        assertFalse(state.canStart); assertFalse(state.canStop); assertFalse(ScreenshotAccessibilityService.isRunning());
        assertNull(nativeSession.marker()); assertNull(nativeSession.stopMarker());
    }
}
