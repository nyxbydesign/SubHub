package com.subhub.app.service;

import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import com.subhub.app.runtime.ActiveRuntimeCoordinator;
import com.subhub.app.runtime.DeviceRuntimeEffects;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exact registered DEVICE Accessibility attachment. Factory integration remains uninstalled. */
public final class DeviceAccessibilityAttachment implements AccessibilityServiceConnection.Events {
    public static final String COMPONENT = "device_accessibility";
    private final RuntimeCaptureHost.DeviceRequest request;
    final DeviceRuntimeEffects.DeviceComponent component;
    private final ScreenshotAccessibilityService service;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OwnedCaptureLifetime calls = new OwnedCaptureLifetime();
    private final AtomicBoolean attached = new AtomicBoolean(), sealed = new AtomicBoolean();
    private volatile DeviceRuntimeEffects.DeviceComponent.Cleanup cleanup;
    private volatile boolean active, failed, drained, nativeWorkReturned;
    private Runnable hostStop, afterDrain;
    private boolean closeStarted, observerBound;

    /** Owner worker: install shutdown before main delivery or any physical dependency construction. */
    public static RuntimeCaptureHost.Attachment prepare(RuntimeCaptureHost.DeviceRequest request) {
        Objects.requireNonNull(request);
        if (!(request.connection.accessibility().service() instanceof ScreenshotAccessibilityService))
            throw new SecurityException("Different Accessibility service");
        return new DeviceAccessibilityAttachment((ScreenshotAccessibilityService)
                request.connection.accessibility().service(), request);
    }
    private DeviceAccessibilityAttachment(ScreenshotAccessibilityService service, RuntimeCaptureHost.DeviceRequest request) {
        this.service = service; this.request = request;
        if (request.plan.mode != RuntimeCaptureHost.Mode.ACCESSIBILITY_DISPLAY
                && request.plan.mode != RuntimeCaptureHost.Mode.ACCESSIBILITY_WINDOW
                && request.plan.mode != RuntimeCaptureHost.Mode.ACCESSIBILITY_MODULES)
            throw new IllegalArgumentException("Not an Accessibility screenshot plan");
        if (request.plan.capturesPixels() && request.plan.sdkInt < 30 || request.plan.mode == RuntimeCaptureHost.Mode.ACCESSIBILITY_WINDOW
                && request.plan.sdkInt < 34) throw new IllegalArgumentException("Unsupported screenshot plan");
        component = request.effects.component(COMPONENT);
        component.installShutdown(value -> {
            cleanup = value; seal();
            OwnedCaptureLifetime.Ticket nativeReturn = calls.begin();
            if (nativeReturn == null) { fail(); return; }
            try {
                value.afterNativeWorkReturned(() -> {
                    try {
                        if (!calls.dispatchContinuation(nativeReturn, main::post,
                                () -> nativeWorkReturned = true)) fail();
                    } catch (RuntimeException | Error uncertain) { fail(); }
                    finally { nativeReturn.close(); }
                });
            } catch (RuntimeException | Error uncertain) { fail(); }
            // Factory cancellation can retire a prepared selection before the shell receives it.
            // Actual cleanup therefore cannot depend on a host observer ever being installed.
            try {
                if (!calls.dispatch(main::post, this::startClose)) fail();
            } catch (RuntimeException | Error uncertain) { fail(); }
            Runnable notify;
            synchronized (this) { notify = hostStop; }
            if (notify != null) notify.run();
        });
        component.usesNoConfiguration();
    }
    @Override public RuntimeCaptureHost.DeviceRequest request() { return request; }
    @Override public void bindHostStop(Runnable stop) {
        Objects.requireNonNull(stop); boolean stopping;
        synchronized (this) {
            if (hostStop != null) throw new IllegalStateException("Host Stop already bound");
            hostStop = stop; stopping = cleanup != null;
        }
        if (stopping) stop.run();
    }
    @Override public void attach(RuntimeCaptureHost.ProjectionGrant projection) {
        requireMain();
        if (projection != null || !attached.compareAndSet(false, true))
            throw new IllegalStateException("Accessibility attachment is single-use, without projection");
        OwnedCaptureLifetime.Ticket invocation = calls.begin();
        if (invocation == null) { request.execution().requestStop(); return; }
        try {
            if (sealed.get() || !request.connection.isConnected()
                    || !component.startup(() -> service.attachManagedDevice(this))) {
                request.execution().requestStop(); return;
            }
            OwnedCaptureLifetime.Ticket readiness = calls.begin();
            if (readiness == null) { request.execution().requestStop(); return; }
            component.readyAsync(result -> {
                try { if (result != ActiveRuntimeCoordinator.Result.OK) request.execution().requestStop(); }
                finally { readiness.close(); }
            });
        } catch (RuntimeException | Error failure) { request.execution().requestStop(); throw failure; }
        finally { invocation.close(); }
    }
    @Override public void activationAcknowledged() {
        requireMain(); if (sealed.get()) return;
        active = true;
        if (!component.normal(() -> service.activateManagedDevice(this))) retryActivation();
    }
    private void retryActivation() {
        if (sealed.get()) return;
        try {
            if (!calls.dispatch(callback -> main.postDelayed(callback, 16L), () -> {
                if (!sealed.get()) activationAcknowledged();
            })) fail();
        } catch (RuntimeException | Error uncertain) { fail(); }
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        requireMain(); if (!active || sealed.get()) return;
        // The shell holds this exact runtime's normal gate, so nested DEVICE continuation cannot
        // become busy midway and grants physical mutation scope only for this synchronous body.
        if (!component.normal(() -> service.deliverManagedEvent(this, event))) request.execution().requestStop();
    }
    boolean postNormal(Runnable action, Runnable discarded) {
        OwnedCaptureLifetime.Ticket retained = calls.begin();
        if (retained == null || sealed.get()) {
            if (retained != null) retained.close(); discarded.run(); return false;
        }
        final class Continuation implements Runnable {
            final AtomicBoolean completed = new AtomicBoolean();
            void finish(boolean ran) {
                if (!completed.compareAndSet(false, true)) return;
                try { if (!ran) discarded.run(); } finally { retained.close(); }
            }
            void post(long delay) {
                try {
                    if (!calls.dispatchContinuation(retained, callback -> main.postDelayed(callback, delay), this)) {
                        finish(false); fail();
                    }
                } catch (RuntimeException | Error uncertain) { fail(); }
            }
            @Override public void run() {
                if (sealed.get() || !request.connection.isConnected()) { finish(false); return; }
                try { if (active && component.normal(action)) finish(true); else post(16L); }
                catch (RuntimeException | Error failure) { finish(true); request.execution().requestStop(); throw failure; }
            }
        }
        new Continuation().post(0); return true;
    }
    boolean active() { return active && !sealed.get() && request.connection.isConnected(); }
    boolean sealed() { return sealed.get(); }
    @Override public void seal() {
        sealed.set(true); active = false;
        service.sealManagedDevice(this); // Memory fence only; no native/window disposal here.
    }
    @Override public void beginClose(Runnable done) {
        requireMain(); Objects.requireNonNull(done);
        if (cleanup == null) throw new SecurityException("Only real component shutdown may begin cleanup");
        synchronized (this) {
            if (observerBound) throw new IllegalStateException("Closure already observed");
            observerBound = true; afterDrain = done;
        }
        if (drained) { deliverDrain(); return; }
        startClose();
    }
    private void startClose() {
        requireMain();
        if (cleanup == null) throw new SecurityException("Missing component shutdown");
        if (closeStarted) return;
        closeStarted = true;
        calls.seal(() -> {});
        try { cleanup.run(() -> service.beginManagedDeviceClose(this)); }
        catch (RuntimeException | Error uncertain) { fail(); return; }
        pollDrain();
    }
    private void pollDrain() {
        if (failed || drained) return;
        try {
            if (nativeWorkReturned && calls.drained() && service.managedDeviceResourcesDrained(this)) {
                drained = true;
                cleanup.drained();
                deliverDrain();
                return;
            }
            if (!main.postDelayed(this::pollDrain, 16L)) fail();
        } catch (RuntimeException | Error uncertain) { fail(); }
    }
    private void deliverDrain() {
        Runnable done;
        synchronized (this) { done = afterDrain; afterDrain = null; }
        if (done != null) done.run();
    }
    void cleanup(Runnable action) {
        DeviceRuntimeEffects.DeviceComponent.Cleanup exact = cleanup;
        if (exact == null) throw new SecurityException("Missing exact cleanup");
        exact.run(action);
    }
    void requestStopAfterUncertainCapture() {
        seal();
        request.execution().requestStop();
    }
    void fail() { failed = true; component.cleanupFailed(); }
    @Override public boolean physicalOccupancy() { return !drained; }
    @Override public boolean managedResourcesDrained() { return drained && !failed; }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Accessibility attachment requires main");
    }
}
