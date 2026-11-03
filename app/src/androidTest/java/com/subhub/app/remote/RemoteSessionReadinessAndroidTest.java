package com.subhub.app.remote;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.security.SubFeaturePolicy;
import com.subhub.app.service.ScreenshotAccessibilityService;
import com.subhub.app.settings.CaptureMethod;
import java.util.UUID;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Synthetic readiness positives exercise the observer, never Android permission or Start acceptance. */
@RunWith(AndroidJUnit4.class)
public final class RemoteSessionReadinessAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final String name = "readiness-" + UUID.randomUUID() + ".db";
    private final RemoteStore store = new RemoteStore(context, name);
    private static RemoteSessionClock.Sample clock(long offset) {
        return new RemoteSessionClock.Sample(100000 + offset, 1000 + offset, 1);
    }
    private static final class Source implements RemoteSessionReadiness.Source {
        long queryTime;
        boolean admin = true, enabled = true, bound = true, fail;
        int sdk = 35, calls;
        CaptureMethod method = CaptureMethod.APP_MODE;
        public RemoteSessionClock.Sample clock() { return RemoteSessionReadinessAndroidTest.clock(calls++ == 0 ? 0 : queryTime); }
        public int sdk() { return sdk; }
        public boolean adminActive() { if (fail) throw new SecurityException("Synthetic provider unavailable"); return admin; }
        public boolean accessibilityEnabled() { return enabled; }
        public boolean accessibilityBound() { return bound; }
        public CaptureMethod captureMethod() { return method; }
    }
    @After public void cleanup() { store.close(); context.deleteDatabase(name); }

    @Test public void boundAppModeDoesNotRequireActiveRecognitionAndAdminIsSeparate() {
        Source source = new Source(); source.admin = false;
        RemoteSessionReadiness.Observation observed = RemoteSessionReadiness.observe(store, source);
        assertEquals(RemoteSessionRuntime.Readiness.APP_MODE_READY, observed.readiness);
        assertFalse(observed.adminActive);
        assertTrue(observed.fresh(clock(5000)));
        assertFalse(observed.fresh(clock(5001)));
        // The synthetic bound flag cannot override the actual disconnected service at application time.
        assertFalse(ScreenshotAccessibilityService.isRunning());
        assertEquals(RemoteSessionRuntime.Readiness.NEEDS_SUB_ACTION, observed.current(context, clock(1)));
    }
    @Test public void missingBindingProjectionAndOldPlatformRequireLocalAction() {
        Source source = new Source(); source.bound = false;
        assertEquals(RemoteSessionRuntime.Readiness.NEEDS_SUB_ACTION, RemoteSessionReadiness.observe(store, source).readiness);
        source = new Source(); source.enabled = false;
        assertEquals(RemoteSessionRuntime.Readiness.NEEDS_SUB_ACTION, RemoteSessionReadiness.observe(store, source).readiness);
        source = new Source(); source.method = CaptureMethod.SCREEN_RECORDING;
        assertEquals(RemoteSessionRuntime.Readiness.NEEDS_SUB_ACTION, RemoteSessionReadiness.observe(store, source).readiness);
        source = new Source(); source.sdk = 29;
        assertEquals(RemoteSessionRuntime.Readiness.NEEDS_SUB_ACTION, RemoteSessionReadiness.observe(store, source).readiness);
    }
    @Test public void slowFailedRebootedAndCorrectedClockObservationsFailClosed() {
        Source source = new Source(); source.queryTime = 5001;
        assertEquals(RemoteSessionRuntime.Readiness.UNAVAILABLE, RemoteSessionReadiness.observe(store, source).readiness);
        source = new Source(); source.fail = true;
        RemoteSessionReadiness.Observation failed = RemoteSessionReadiness.observe(store, source);
        assertEquals(RemoteSessionRuntime.Readiness.UNAVAILABLE, failed.readiness);
        assertFalse(failed.adminActive); assertFalse(failed.fresh(clock(0)));
        RemoteSessionReadiness.Observation observed = RemoteSessionReadiness.observe(store, new Source());
        assertFalse(observed.fresh(new RemoteSessionClock.Sample(100001, 1001, 2)));
        assertFalse(observed.fresh(new RemoteSessionClock.Sample(99999, 1001, 1)));
        assertFalse(observed.fresh(new RemoteSessionClock.Sample(103002, 1001, 1)));
        source = new Source(); source.queryTime = 2000;
        observed = RemoteSessionReadiness.observe(store, source);
        assertFalse(observed.fresh(clock(1999)));
    }
    @Test public void allAuthorityLocksRefuseBeforeAnySystemQuery() {
        Source source = new Source();
        synchronized (store) { assertThrows(IllegalStateException.class, () -> RemoteSessionReadiness.observe(store, source)); }
        synchronized (SubFeaturePolicy.LOCK) { assertThrows(IllegalStateException.class, () -> RemoteSessionReadiness.observe(store, source)); }
        synchronized (RuntimeSessionIdentity.LOCK) { assertThrows(IllegalStateException.class, () -> RemoteSessionReadiness.observe(store, source)); }
        assertEquals(0, source.calls);
    }
    @Test public void actualSystemProviderObservesWithoutGrantingOrStartingAnything() {
        assertFalse(ScreenshotAccessibilityService.isRunning());
        RemoteSessionReadiness.Observation observed = RemoteSessionReadiness.observe(context, store);
        assertEquals(new HardcoreModeManager(context).isAdminActive(), observed.adminActive);
        assertEquals(RemoteSessionRuntime.Readiness.NEEDS_SUB_ACTION, observed.readiness);
        assertTrue(observed.fresh(RemoteSessionClock.local()));
        assertFalse(ScreenshotAccessibilityService.isRunning());
        assertTrue(RemoteSessionRuntime.applicationEnabled());
    }
}
