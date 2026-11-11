package com.subhub.app.runtime;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Application;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.service.RuntimeCaptureHost;
import com.subhub.app.service.ScreenCaptureService;
import com.subhub.app.service.ScreenshotAccessibilityService;
import com.subhub.app.settings.CaptureMethod;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Actual DEVICE Start adapter. INACTIVE: no installer may expose this until owned ACTIVE-loss
 * continuation and whole-transition End/boot/remote/expiry routing are qualified. Rearm refuses
 * explicitly rather than invoking a legacy action outside exact admitted resource ownership.
 */
final class AndroidDeviceBackend implements AppRuntimeGateway.Backend {
    private final Application physical;
    private final AndroidRuntimeFactory factory;
    private final Handler main = new Handler(Looper.getMainLooper());

    AndroidDeviceBackend(Application physical, AndroidRuntimeFactory factory) {
        this.physical = Objects.requireNonNull(physical); this.factory = Objects.requireNonNull(factory);
    }
    @Override public CompletableFuture<AppRuntimeGateway.DeviceStart> deviceStart(AppRuntimeGateway.DeviceStartSpec specification) {
        CompletableFuture<AppRuntimeGateway.DeviceStart> result = new CompletableFuture<>();
        factory.reserveDevice(specification).whenComplete((admission, failure) -> {
            if (failure != null) { result.completeExceptionally(failure); return; }
            DeviceConfiguration config = admission.configuration;
            if (config.plan.capture == CaptureMethod.APP_MODE && config.censorEnabled && config.sdkInt < 30) {
                factory.cancel(admission.attempt);
                result.completeExceptionally(new AppRuntimeGateway.DeviceAdmissionException("This Accessibility pixel route requires Android 11"));
                return;
            }
            Start start = new Start(admission);
            if (!result.complete(start)) start.cancel();
        });
        return result;
    }
    @Override public CompletableFuture<AppRuntimeGateway.StartResult> rearmDevice(AppRuntimeGateway.RearmReason reason,
            RuntimeSessionIdentity.ComponentObservation expected, Runnable admittedLegacyAction) {
        Objects.requireNonNull(reason); Objects.requireNonNull(expected); Objects.requireNonNull(admittedLegacyAction);
        return CompletableFuture.failedFuture(new AppRuntimeGateway.DeviceAdmissionException(
                "Owned DEVICE continuation is not qualified; legacy rearm was not invoked"));
    }
    @Override public AppRuntimeGateway.DeviceMutationPermit admitManagedDeviceMutation(
            RuntimeLease.Identity expected, AppRuntimeGateway.Mutation mutation,
            AppRuntimeGateway.ManagedDevicePhase phase) {
        return factory.admitManagedDeviceMutation(expected, mutation, phase);
    }
    @Override public AppRuntimeGateway.DeviceMutationPermit admitDeviceMutation(AppRuntimeGateway.Mutation mutation) {
        return factory.admitDeviceMutation(mutation);
    }
    @Override public AppRuntimeGateway.ServiceAccess serviceAccess() { return factory.serviceAccess(); }

    private final class Start implements AppRuntimeGateway.DeviceStart {
        private final AndroidRuntimeFactory.DeviceAdmission admission;
        private final AtomicBoolean readinessEntered = new AtomicBoolean();
        private volatile AndroidRuntimeFactory.DeviceAssembly assembly;
        private volatile AndroidRuntimeFactory.ProjectionTransport projection;
        private volatile Throwable failed;
        private CompletableFuture<AppRuntimeGateway.StartResult> result;
        private AppRuntimeGateway.StartResult outcome;
        private boolean readinessPostReturned;
        Start(AndroidRuntimeFactory.DeviceAdmission admission) { this.admission = admission; }
        @Override public RuntimeLease.Identity identity() { return admission.attempt.lease().identity; }
        @Override public AppRuntimeGateway.DeviceStartSpec specification() { return admission.configuration.specification; }
        @Override public AppRuntimeGateway.DevicePlan plan() { return admission.configuration.plan; }
        @Override public CompletableFuture<RuntimeStartAttempt.Prompt> permission(RuntimeStartAttempt.Permission kind) {
            return cancellationFences(factory.submitDevice(() -> factory.permission(admission.attempt, kind))
                    .thenCompose(value -> value));
        }
        @Override public CompletableFuture<Boolean> permissionResult(RuntimeStartAttempt.Prompt prompt, boolean granted,
                int resultCode, Intent projectionData) {
            Intent captured = projectionData == null ? null : new Intent(projectionData);
            // Even a withdrawn UI returns the exact obligation; no early cancellation shortcut.
            return cancellationFences(factory.devicePermissionResult(admission, prompt, granted, resultCode, captured));
        }
        @Override public synchronized CompletableFuture<AppRuntimeGateway.StartResult> start() {
            if (result != null) return result;
            result = new CompletableFuture<>();
            result.whenComplete((ignored, failure) -> {
                if (result.isCancelled()) fail(new java.util.concurrent.CancellationException("DEVICE start response withdrawn"));
            });
            try {
                if (!main.post(this::readiness)) fail(new IllegalStateException("DEVICE readiness queue refused"));
            } catch (RuntimeException | Error uncertain) { fail(uncertain); }
            finally { readinessPostReturned = true; settle(); }
            return result;
        }
        private void readiness() {
            if (!readinessEntered.compareAndSet(false, true) || failed != null) return;
            try {
                boolean accessibility = !plan().requiresAccessibility || accessibilityReady();
                boolean overlay = !plan().requiresOverlay || Settings.canDrawOverlays(physical);
                factory.beginDevice(admission, accessibility, overlay).whenComplete((starting, failure) -> {
                    if (failure != null) { fail(failure); return; }
                    try {
                        AndroidRuntimeFactory.DeviceAssembly exact = factory.assembleDevice(starting, admission);
                        assembly = exact;
                        if (failed != null) { exact.runtime.requestStop(); return; }
                        AndroidDeviceStartLifecycle lifecycle = new AndroidDeviceStartLifecycle(physical, factory, exact);
                        lifecycle.start();
                        if (!lifecycle.startAcknowledged()) throw new IllegalStateException("Missing actual DEVICE startup receipt");
                        exact.activation.whenComplete((active, activationFailure) -> {
                            if (activationFailure != null) fail(activationFailure);
                            else synchronized (Start.this) { outcome = active; settle(); }
                        });
                        attach(exact);
                    } catch (RuntimeException | Error unavailable) {
                        try { if (assembly == null) factory.rejectDeviceStarting(admission, starting); }
                        finally { fail(unavailable); }
                    }
                });
            } catch (RuntimeException | Error unavailable) { fail(unavailable); }
        }
        /** Owner worker only; actual physical data ACK precedes either service handoff. */
        private void attach(AndroidRuntimeFactory.DeviceAssembly exact) {
            DeviceConfiguration config = admission.configuration;
            if (config.plan.requiresAccessibility) {
                RuntimeCaptureHost.Mode mode = config.plan.capture == CaptureMethod.SCREEN_RECORDING || !config.censorEnabled
                        ? RuntimeCaptureHost.Mode.ACCESSIBILITY_MODULES
                        : config.sdkInt >= 34 ? RuntimeCaptureHost.Mode.ACCESSIBILITY_WINDOW
                        : RuntimeCaptureHost.Mode.ACCESSIBILITY_DISPLAY;
                RuntimeCaptureHost.CapturePlan selected = new RuntimeCaptureHost.CapturePlan(mode, config.sdkInt);
                factory.awaitAccessibility(exact.runtime, selected, connection -> new RuntimeCaptureHost.DeviceRequest(
                        connection, selected, exact.runtime, exact.effects, exact.statisticsSource(), request -> {
                            if (!(request.connection.accessibility().service() instanceof ScreenshotAccessibilityService))
                                throw new SecurityException("Different physical Accessibility service");
                            return ((ScreenshotAccessibilityService) request.connection.accessibility().service()).prepareDeviceAttachment(request);
                        }));
            }
            if (config.plan.capture == CaptureMethod.SCREEN_RECORDING) {
                RuntimeCaptureHost.CapturePlan selected = new RuntimeCaptureHost.CapturePlan(RuntimeCaptureHost.Mode.PROJECTION, config.sdkInt);
                AndroidRuntimeFactory.ProjectionTransport transport = factory.awaitProjection(exact.runtime, selected,
                        connection -> new RuntimeCaptureHost.DeviceRequest(connection, selected, exact.runtime, exact.effects, exact.statisticsSource(), request -> {
                            if (!(request.connection.projection().service() instanceof ScreenCaptureService))
                                throw new SecurityException("Different physical projection service");
                            return ((ScreenCaptureService) request.connection.projection().service()).prepareDeviceAttachment(request);
                        }));
                projection = transport;
                try {
                    if (!main.post(() -> {
                        if (failed != null) { transport.cancelBeforeLaunch(); return; }
                        try {
                            if (!transport.launch()) {
                                transport.cancelBeforeLaunch();
                                fail(new AppRuntimeGateway.DeviceAdmissionException("DEVICE service start was not admitted"));
                            }
                        } catch (RuntimeException | Error uncertain) { fail(uncertain); }
                    })) {
                        transport.cancelBeforeLaunch(); throw new IllegalStateException("Projection launch queue refused");
                    }
                } catch (RuntimeException | Error uncertain) { transport.cancelBeforeLaunch(); throw uncertain; }
            }
        }
        private <T> CompletableFuture<T> cancellationFences(CompletableFuture<T> response) {
            response.whenComplete((ignored, failure) -> {
                if (response.isCancelled()) factory.cancel(admission.attempt);
            });
            return response;
        }
        @Override public boolean cancel() {
            // A UI cancellation loses to transfer: it cannot end an already handed-off session.
            return factory.cancel(admission.attempt);
        }
        private void fail(Throwable failure) {
            final Throwable original;
            synchronized (this) {
                if (failed == null) failed = Objects.requireNonNull(failure);
                original = failed;
            }
            // Cancellation may have dispatched before throwing. Preserve that uncertainty; do not
            // retry native launch/disposal or skip the independent runtime fence/future settlement.
            completeFailureSteps(original,
                    () -> factory.cancel(admission.attempt),
                    () -> { AndroidRuntimeFactory.ProjectionTransport exact = projection;
                        if (exact != null) exact.cancelBeforeLaunch(); },
                    () -> { AndroidRuntimeFactory.DeviceAssembly exact = assembly;
                        if (exact != null) exact.runtime.requestStop(); },
                    () -> { synchronized (this) { settle(); } });
        }
        private void settle() {
            if (result == null || !readinessPostReturned || result.isDone()) return;
            if (failed != null) result.completeExceptionally(failed);
            else if (outcome != null) result.complete(outcome);
        }
    }
    /** Each independent terminal obligation is attempted once per failure notification. */
    static void completeFailureSteps(Throwable original, Runnable... steps) {
        Objects.requireNonNull(original);
        for (Runnable step : steps) {
            try { step.run(); }
            catch (RuntimeException | Error uncertain) {
                if (uncertain != original) original.addSuppressed(uncertain);
            }
        }
    }
    private boolean accessibilityReady() {
        AccessibilityManager manager = physical.getSystemService(AccessibilityManager.class);
        if (manager == null) return false;
        for (AccessibilityServiceInfo service : manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            android.content.pm.ResolveInfo resolved = service.getResolveInfo();
            if (resolved != null && resolved.serviceInfo != null
                    && physical.getPackageName().equals(resolved.serviceInfo.packageName)
                    && ScreenshotAccessibilityService.class.getName().equals(resolved.serviceInfo.name)) return true;
        }
        return false;
    }
}
