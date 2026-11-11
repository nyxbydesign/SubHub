package com.subhub.app.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.accessibility.AccessibilityEvent;
import androidx.core.content.ContextCompat;
import com.subhub.app.internal.InternalRuntimePreferences;
import com.subhub.app.runtime.ActiveRuntimeCoordinator;
import com.subhub.app.runtime.InternalSessionClock;
import com.subhub.app.runtime.InternalSessionClockBinding;
import com.subhub.app.runtime.RuntimeConfiguration;
import com.subhub.app.runtime.RuntimeExecution;
import com.subhub.app.runtime.RuntimeFenceObservation;
import com.subhub.app.stats.OwnerObservation;
import com.subhub.app.stats.OwnerWriteQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Real exact INTERNAL Accessibility assembly, never a reservation or pixel-readiness substitute. */
public final class InternalRuntimeSession implements AccessibilityServiceConnection.Events {
    public static final String COMPONENT = "internal_session_assembly";
    public interface Capture extends RuntimeCaptureHost.Attachment {
        void sourceChanged();
        void accessibilityEvent(AccessibilityEvent event);
        void screenChanged(String action);
        void powerChanged();
    }
    public interface CaptureFactory { Capture prepare(InternalRuntimeSession session); }
    private final RuntimeCaptureHost.InternalRequest request;
    private final Context signalContext;
    private final RuntimeExecution<RuntimeConfiguration>.Component component;
    private final OwnedCaptureLifetime work = new OwnedCaptureLifetime();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean sealed = new AtomicBoolean(), attached = new AtomicBoolean();
    private final AtomicBoolean closeStarted = new AtomicBoolean();
    private final AtomicInteger closeCallbacks = new AtomicInteger();
    private final List<Runnable> closeObservers = new ArrayList<>();
    private final CompletableFuture<Void> prepared = new CompletableFuture<>();
    private volatile InternalSessionClockBinding clock;
    private volatile InternalAccessibilityModules modules;
    private volatile Capture capture;
    private final AtomicReference<Capture> projectionCapture = new AtomicReference<>();
    private volatile boolean constructionFailed, cleanupFailed, drained;
    private boolean activated, activationQueued;
    private BroadcastReceiver screenAndPower;
    private Runnable afterCoreDrain;
    private final AtomicReference<Runnable> hostStop = new AtomicReference<>();
    private final AtomicBoolean hostStopSent = new AtomicBoolean();
    private final AtomicInteger hostStopRunning = new AtomicInteger();

    public InternalRuntimeSession(RuntimeCaptureHost.InternalRequest request, OwnerWriteQueue writes,
            OwnerObservation started, long startedElapsed, Supplier<RuntimeFenceObservation> firstFence,
            Executor ownerWorker, ExecutorService foregroundReads, Executor artworkWorker,
            InternalLimitsBinding.LocalIntervention notice, CaptureFactory selectedCapture) {
        InternalRuntimePreferences.requireWorker();
        this.request = Objects.requireNonNull(request);
        if (request.connection.kind != RuntimeCaptureHost.Kind.ACCESSIBILITY || !request.connection.isConnected())
            throw new SecurityException("Session modules require a connected Accessibility host");
        // Keep the registration alive across service loss until the exact runtime retires it.
        signalContext = Objects.requireNonNull(request.connection.accessibility().service().getApplicationContext());
        boolean modulesOnly = request.plan.mode == RuntimeCaptureHost.Mode.ACCESSIBILITY_MODULES;
        if (modulesOnly != (selectedCapture == null))
            throw new IllegalArgumentException("Module-only and selected capture routes must remain distinct");
        request.data.acknowledgedSessionStamp();
        component = request.runtime.registerComponent(COMPONENT);
        component.usesNoConfiguration();
        component.installShutdown(this::closeFromRuntime);
        try {
            clock = new InternalSessionClockBinding(request.runtime, request.data, writes, started,
                    startedElapsed, firstFence, this::eligibilityChanged);
            modules = new InternalAccessibilityModules(request.runtime, request.data, request.storage,
                    request.connection, request.resources, ownerWorker, foregroundReads, artworkWorker,
                    clock::protectionEligible, this::captureChanged, notice);
            if (selectedCapture != null) {
                capture = Objects.requireNonNull(selectedCapture.prepare(this));
                if (capture.request() != request) throw new SecurityException("Different capture attachment");
            }
        } catch (RuntimeException | Error failed) {
            constructionFailed = true; component.failed(); throw failed;
        }
    }
    @Override public RuntimeCaptureHost.InternalRequest request() { return request; }
    @Override public void bindHostStop(Runnable stop) {
        if (!hostStop.compareAndSet(null, Objects.requireNonNull(stop)))
            throw new IllegalStateException("Accessibility host Stop already bound");
        if (closeStarted.get()) notifyHostStop();
    }
    private void notifyHostStop() {
        Runnable stop = hostStop.get();
        if (stop == null) return;
        synchronized (closeObservers) {
            if (!hostStopSent.compareAndSet(false, true)) return;
            hostStopRunning.incrementAndGet();
        }
        try { stop.run(); }
        catch (RuntimeException | Error uncertain) { cleanupFailed = true; component.cleanupFailed(); throw uncertain; }
        finally { hostStopRunning.decrementAndGet(); }
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { accessibilityEvent(event); }
    public boolean protectionEligible() { return !sealed.get() && clock.protectionEligible(); }
    public InternalSessionClock.Snapshot clockSnapshot() { return clock.snapshot(); }
    public CompletableFuture<InternalSessionClock.Snapshot> simulatePause(InternalSessionClock.Snapshot expected) {
        return clock.simulatePause(expected);
    }
    public InternalAccessibilityModules modules() { return modules; }
    /** Factory once-only handoff after both real objects returned from construction. */
    public void bindProjectionCapture(Capture projection) {
        InternalRuntimePreferences.requireWorker(); Objects.requireNonNull(projection);
        RuntimeCaptureHost.Request generic = projection.request();
        if (!(generic instanceof RuntimeCaptureHost.InternalRequest))
            throw new SecurityException("Projection requires exact INTERNAL dependencies");
        RuntimeCaptureHost.InternalRequest source = (RuntimeCaptureHost.InternalRequest) generic;
        if (request.plan.mode != RuntimeCaptureHost.Mode.ACCESSIBILITY_MODULES || capture != null
                || source.plan.mode != RuntimeCaptureHost.Mode.PROJECTION || source.connection == request.connection
                || source.runtime != request.runtime || source.data != request.data || source.storage != request.storage
                || source.resources != request.resources || !source.owner().equals(request.owner()))
            throw new SecurityException("Projection feed belongs to another source/owner");
        if (!prepared.isDone() || prepared.isCompletedExceptionally() || prepared.isCancelled())
            throw new IllegalStateException("Accessibility modules have not acknowledged preparation");
        if (!request.runtime.runStartupEffect(() -> {
            if (sealed.get() || !request.connection.isConnected() || !source.connection.isConnected()
                    || source.initialConfiguration != request.runtime.configuration()
                    || !projectionCapture.compareAndSet(null, projection))
                throw new SecurityException("Projection feed is closed, stale or already bound");
        })) throw new SecurityException("Projection feed startup admission unavailable");
    }
    @Override public void attach(RuntimeCaptureHost.ProjectionGrant projection) {
        requireMain();
        if (projection != null || hostStop.get() == null || sealed.get() || !attached.compareAndSet(false, true))
            throw new SecurityException("Accessibility session attach is exact, single-use and not projection");
        OwnedCaptureLifetime.Ticket attachment = work.begin();
        if (attachment == null) throw new SecurityException("Session attachment sealed");
        try {
            if (capture != null) capture.attach(null);
            if (!request.runtime.runStartupEffect(this::registerScreenAndPower))
                throw new SecurityException("Screen and power observation startup admission unavailable");
            OwnedCaptureLifetime.Ticket preparation = work.begin();
            if (preparation == null) throw new SecurityException("Session preparation sealed");
            CompletableFuture.allOf(clock.prepared(), modules.prepared()).whenComplete((ignored, failure) -> {
                try {
                    if (failure != null || sealed.get()) {
                        prepared.completeExceptionally(failure == null ? new IllegalStateException("Session stopped during prepare") : failure);
                        component.failed(); return;
                    }
                    OwnedCaptureLifetime.Ticket receipt = work.begin();
                    if (receipt == null) throw new SecurityException("Session readiness sealed");
                    component.readyAsync(result -> {
                        try {
                            if (result == ActiveRuntimeCoordinator.Result.OK && !sealed.get()) prepared.complete(null);
                            else { prepared.completeExceptionally(new IllegalStateException("Session assembly readiness refused")); component.failed(); }
                        } finally { receipt.close(); }
                    });
                } catch (RuntimeException | Error failed) { prepared.completeExceptionally(failed); component.failed(); }
                finally { preparation.close(); }
            });
        } catch (RuntimeException | Error failed) { prepared.completeExceptionally(failed); component.failed(); throw failed; }
        finally { attachment.close(); }
    }
    public CompletableFuture<Void> prepared() {
        CompletableFuture<Void> result = new CompletableFuture<>();
        result.whenComplete((value, failure) -> { if (result.isCancelled()) seal(); });
        prepared.whenComplete((value, failure) -> {
            boolean delivered = failure == null ? result.complete(null) : result.completeExceptionally(failure);
            if (!delivered) seal();
        });
        return result;
    }
    @Override public void activationAcknowledged() {
        requireMain(); if (sealed.get() || activated) return;
        if (!prepared.isDone() || prepared.isCompletedExceptionally())
            throw new IllegalStateException("Session modules have not acknowledged preparation");
        if (!request.runtime.runEffect(() -> {
            if (sealed.get() || !request.connection.isConnected()) throw new SecurityException("Session host retired");
            clock.activationAcknowledged(); modules.activationAcknowledged();
            if (capture != null) capture.activationAcknowledged();
            activated = true;
        })) queueActivation();
    }
    private void queueActivation() {
        requireMain(); if (activationQueued || sealed.get()) return;
        activationQueued = true;
        try {
            if (!work.dispatch(command -> main.postDelayed(command, 16), () -> {
                activationQueued = false;
                if (!sealed.get()) activationAcknowledged();
            })) component.failed();
        } catch (RuntimeException | Error uncertain) { component.failed(); }
    }
    public void accessibilityEvent(AccessibilityEvent event) {
        Objects.requireNonNull(event); source(() -> {
            int type = event.getEventType();
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    || type == AccessibilityEvent.TYPE_VIEW_FOCUSED) modules.sourceChanged();
            if (capture != null) capture.accessibilityEvent(event);
            Capture projection = projectionCapture.get();
            if (projection != null) projection.accessibilityEvent(event);
        });
    }
    public void screenChanged(String action) { source(() -> {
        modules.sourceChanged(); if (capture != null) capture.screenChanged(action);
        Capture projection = projectionCapture.get(); if (projection != null) projection.screenChanged(action);
    }); }
    public void powerChanged() { source(() -> {
        modules.sourceChanged(); if (capture != null) capture.powerChanged();
        Capture projection = projectionCapture.get(); if (projection != null) projection.powerChanged();
    }); }
    private void registerScreenAndPower() {
        requireMain();
        if (sealed.get() || !request.connection.isConnected()) throw new SecurityException("Session host retired");
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        screenAndPower = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!activated || screenAndPower != this) return;
                String action = intent.getAction();
                if (PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(action)) powerChanged();
                else if (Intent.ACTION_SCREEN_OFF.equals(action) || Intent.ACTION_SCREEN_ON.equals(action)
                        || Intent.ACTION_USER_PRESENT.equals(action)) screenChanged(action);
            }
        };
        ContextCompat.registerReceiver(signalContext, screenAndPower, filter, null, main, ContextCompat.RECEIVER_NOT_EXPORTED);
    }
    private void eligibilityChanged() { source(() -> modules.sourceChanged()); }
    private void captureChanged() { source(() -> {
        if (capture != null) capture.sourceChanged();
        Capture projection = projectionCapture.get(); if (projection != null) projection.sourceChanged();
    }); }
    private void source(Runnable action) {
        requireMain(); if (sealed.get()) return;
        OwnedCaptureLifetime.Ticket event = work.begin(); if (event == null) return;
        try {
            if (!request.connection.isConnected()) { seal(); return; }
            action.run();
        } catch (RuntimeException | Error failed) { component.failed(); throw failed; }
        finally { event.close(); }
    }
    @Override public void seal() { sealed.set(true); request.runtime.requestStop(); }
    @Override public void beginClose(Runnable afterActualDrain) {
        Objects.requireNonNull(afterActualDrain);
        synchronized (closeObservers) {
            if (!drained) { closeObservers.add(afterActualDrain); afterActualDrain = null; }
        }
        if (afterActualDrain != null) afterActualDrain.run(); else seal();
    }
    private void closeFromRuntime(Runnable afterDrained) {
        sealed.set(true); prepared.completeExceptionally(new IllegalStateException("Session stopped"));
        afterCoreDrain = Objects.requireNonNull(afterDrained);
        if (!closeStarted.compareAndSet(false, true)) throw new IllegalStateException("Session shutdown repeated");
        try { notifyHostStop(); }
        finally { work.seal(() -> pollChildren(false)); }
    }
    /** Poll only observes real receipts; delay is scheduling, never proof that anything closed. */
    private void pollChildren(boolean delayed) {
        closeCallbacks.incrementAndGet();
        Runnable observe = () -> {
            boolean complete = false;
            try {
                if (screenAndPower != null) {
                    BroadcastReceiver retiring = screenAndPower;
                    screenAndPower = null;
                    signalContext.unregisterReceiver(retiring);
                }
                if (constructionFailed) { cleanupFailed = true; component.cleanupFailed(); return; }
                Capture projection = projectionCapture.get();
                complete = hostStopRunning.get() == 0 && work.drained() && clock != null && clock.managedResourcesDrained()
                        && modules != null && modules.managedResourcesDrained()
                        && (capture == null || capture.managedResourcesDrained())
                        && (projection == null || projection.managedResourcesDrained());
                if (!complete && !cleanupFailed) pollChildren(true);
            } catch (RuntimeException | Error uncertain) { cleanupFailed = true; component.cleanupFailed(); }
            finally {
                int remaining = closeCallbacks.decrementAndGet();
                if (complete && remaining == 0 && !cleanupFailed) completeClose();
            }
        };
        try {
            if (!(delayed ? main.postDelayed(observe, 16) : main.post(observe))) {
                closeCallbacks.decrementAndGet(); cleanupFailed = true; component.cleanupFailed();
            }
        } catch (RuntimeException | Error uncertain) {
            // Accepted-then-throw is not proof of callback return or safe window retirement.
            cleanupFailed = true; component.cleanupFailed();
        }
    }
    private void completeClose() {
        try {
            while (true) {
                List<Runnable> observers;
                synchronized (closeObservers) {
                    if (drained || cleanupFailed) return;
                    if (closeObservers.isEmpty()) {
                        drained = true;
                        break;
                    }
                    observers = new ArrayList<>(closeObservers);
                    closeObservers.clear();
                }
                for (Runnable observer : observers) observer.run();
            }
            afterCoreDrain.run();
        } catch (RuntimeException | Error uncertain) { cleanupFailed = true; component.cleanupFailed(); }
    }
    @Override public boolean physicalOccupancy() { return !managedResourcesDrained(); }
    @Override public boolean managedResourcesDrained() { return drained && !cleanupFailed && closeCallbacks.get() == 0; }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Session callback requires main");
    }
}
