package com.subhub.app.remote;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.view.accessibility.AccessibilityManager;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.security.SubFeaturePolicy;
import com.subhub.app.service.ScreenshotAccessibilityService;
import com.subhub.app.settings.CaptureMethod;
import com.subhub.app.settings.SettingsRepository;
import java.util.Objects;

/** Short-lived system observations. These do not establish peer or local consent authority. */
final class RemoteSessionReadiness {
    private static final long MAX_AGE = 5000;
    private RemoteSessionReadiness() { }

    interface Source {
        RemoteSessionClock.Sample clock();
        int sdk();
        boolean adminActive();
        boolean accessibilityEnabled();
        boolean accessibilityBound();
        CaptureMethod captureMethod();
    }

    static final class Observation {
        final RemoteSessionClock.Sample began, completed;
        final boolean adminActive;
        final RemoteSessionRuntime.Readiness readiness;
        private Observation(RemoteSessionClock.Sample began, RemoteSessionClock.Sample completed,
                boolean adminActive, RemoteSessionRuntime.Readiness readiness) {
            this.began = began; this.completed = completed; this.adminActive = adminActive; this.readiness = readiness;
        }
        /** No OS queries: the controller calls this again at its native application boundary. */
        boolean fresh(RemoteSessionClock.Sample now) {
            return within(began, completed) && within(began, now) && now.elapsed >= completed.elapsed;
        }
        RemoteSessionRuntime.Readiness current(Context context, RemoteSessionClock.Sample now) {
            if (!fresh(now)) return RemoteSessionRuntime.Readiness.UNAVAILABLE;
            if (readiness != RemoteSessionRuntime.Readiness.APP_MODE_READY) return readiness;
            return new SettingsRepository(context).loadCaptureMethod() == CaptureMethod.APP_MODE
                    && ScreenshotAccessibilityService.isRunning() ? readiness
                    : RemoteSessionRuntime.Readiness.NEEDS_SUB_ACTION;
        }
    }

    static Observation observe(Context context, RemoteStore store) {
        return observe(store, system(context));
    }
    static Observation observeClock(Context context, RemoteStore store) {
        return observeClock(store, system(context));
    }
    private static Source system(Context context) {
        Context app = context.getApplicationContext();
        return new Source() {
            public RemoteSessionClock.Sample clock() { return RemoteSessionClock.observe(app); }
            public int sdk() { return Build.VERSION.SDK_INT; }
            public boolean adminActive() { return new HardcoreModeManager(app).isAdminActive(); }
            public boolean accessibilityEnabled() { return enabled(app); }
            public boolean accessibilityBound() { return ScreenshotAccessibilityService.isRunning(); }
            public CaptureMethod captureMethod() { return new SettingsRepository(app).loadCaptureMethod(); }
        };
    }

    private static void requireOutsideLocks(RemoteStore store, Source source) {
        Objects.requireNonNull(store); Objects.requireNonNull(source);
        if (Thread.holdsLock(SubFeaturePolicy.LOCK) || Thread.holdsLock(store)
                || Thread.holdsLock(RuntimeSessionIdentity.LOCK))
            throw new IllegalStateException("Observe system readiness outside authority locks");
    }
    /** Stop needs a fresh clock, independently of Start permission providers. */
    static Observation observeClock(RemoteStore store, Source source) {
        requireOutsideLocks(store, source);
        RemoteSessionClock.Sample began = null;
        try {
            began = Objects.requireNonNull(source.clock());
            return new Observation(began, Objects.requireNonNull(source.clock()), false, RemoteSessionRuntime.Readiness.UNAVAILABLE);
        } catch (RuntimeException unavailable) {
            RemoteSessionClock.Sample invalid = new RemoteSessionClock.Sample(0, -1, -1);
            return new Observation(began == null ? invalid : began, invalid, false, RemoteSessionRuntime.Readiness.UNAVAILABLE);
        }
    }

    static Observation observe(RemoteStore store, Source source) {
        requireOutsideLocks(store, source);
        RemoteSessionClock.Sample began = null;
        try {
            began = Objects.requireNonNull(source.clock());
            boolean admin = source.adminActive();
            boolean ready = source.sdk() >= 30 && source.captureMethod() == CaptureMethod.APP_MODE
                    && source.accessibilityEnabled() && source.accessibilityBound();
            RemoteSessionClock.Sample completed = Objects.requireNonNull(source.clock());
            return new Observation(began, completed, admin, within(began, completed)
                    ? ready ? RemoteSessionRuntime.Readiness.APP_MODE_READY : RemoteSessionRuntime.Readiness.NEEDS_SUB_ACTION
                    : RemoteSessionRuntime.Readiness.UNAVAILABLE);
        } catch (RuntimeException unavailable) {
            RemoteSessionClock.Sample invalid = new RemoteSessionClock.Sample(0, -1, -1);
            return new Observation(began == null ? invalid : began, invalid, false, RemoteSessionRuntime.Readiness.UNAVAILABLE);
        }
    }

    private static boolean within(RemoteSessionClock.Sample began, RemoteSessionClock.Sample now) {
        if (began.boot < 0 || now.boot != began.boot || began.wall <= 0 || now.wall < began.wall
                || began.elapsed < 0 || now.elapsed < began.elapsed) return false;
        long elapsed = now.elapsed - began.elapsed, wall = now.wall - began.wall;
        return elapsed <= MAX_AGE && Math.abs(wall - elapsed) <= 2000;
    }

    private static boolean enabled(Context context) {
        AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
        if (manager == null) return false;
        ComponentName expected = new ComponentName(context, ScreenshotAccessibilityService.class);
        for (AccessibilityServiceInfo info : manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (info.getResolveInfo() == null || info.getResolveInfo().serviceInfo == null) continue;
            android.content.pm.ServiceInfo service = info.getResolveInfo().serviceInfo;
            String name = service.name.startsWith(".") ? service.packageName + service.name : service.name;
            if (expected.equals(new ComponentName(service.packageName, name))) return true;
        }
        return false;
    }
}
