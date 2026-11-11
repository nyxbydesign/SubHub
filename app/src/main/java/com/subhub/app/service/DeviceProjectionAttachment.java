package com.subhub.app.service;

import android.os.Handler;
import android.os.Looper;
import com.subhub.app.runtime.ActiveRuntimeCoordinator;
import com.subhub.app.runtime.DeviceRuntimeEffects;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exact DEVICE projection attachment. The installed gateway, never this class, reserves authority. */
final class DeviceProjectionAttachment implements RuntimeCaptureHost.Attachment {
    static final String COMPONENT = "device_projection";
    private final RuntimeCaptureHost.DeviceRequest request;
    final DeviceRuntimeEffects.DeviceComponent component;
    private final ScreenCaptureService service;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OwnedCaptureLifetime normal = new OwnedCaptureLifetime();
    private final OwnedCaptureLifetime cleanupWork = new OwnedCaptureLifetime();
    private final AtomicBoolean attached = new AtomicBoolean(), sealed = new AtomicBoolean(), closing = new AtomicBoolean();
    private volatile boolean active, normalReturned, nativeReturned, frontReturned, backStarted, backReturned, failure, drained;
    private volatile DeviceRuntimeEffects.DeviceComponent.Cleanup cleanup;
    private Runnable afterDrain;
    private volatile CaptureLoop captureLoop;

    /** Owner worker only, before any OS/native/data construction or main delivery. */
    DeviceProjectionAttachment(ScreenCaptureService service, RuntimeCaptureHost.DeviceRequest request) {
        this.service = Objects.requireNonNull(service); this.request = Objects.requireNonNull(request);
        if (request.plan.mode != RuntimeCaptureHost.Mode.PROJECTION
                || request.connection.projection().service() != service)
            throw new SecurityException("Different exact projection service");
        component = request.effects.component(COMPONENT);
        component.installShutdown(receipt -> {
            cleanup = receipt;
            seal();
            receipt.afterNativeWorkReturned(() -> { nativeReturned = true; maybeCloseBack(); });
            startClosing();
        });
        component.usesNoConfiguration();
    }
    @Override public RuntimeCaptureHost.DeviceRequest request() { return request; }
    @Override public void attach(RuntimeCaptureHost.ProjectionGrant projection) {
        requireMain();
        if (!attached.compareAndSet(false, true)) throw new IllegalStateException("Projection attachment is single-use");
        OwnedCaptureLifetime.Ticket creation = normal.begin();
        if (creation == null) { request.execution().requestStop(); return; }
        try {
            if (!request.connection.isConnected()
                    || !component.startup(() -> service.attachManagedDevice(this, projection))) {
                request.execution().requestStop(); return;
            }
            component.readyAsync(result -> {
                if (result != ActiveRuntimeCoordinator.Result.OK) request.execution().requestStop();
            });
        } catch (RuntimeException | Error failed) { request.execution().requestStop(); throw failed; }
        finally { creation.close(); }
    }
    @Override public void activationAcknowledged() {
        requireMain();
        if (sealed.get()) return;
        active = true;
        postMain(() -> service.activateManagedDevice(this), () -> {});
    }
    OwnedCaptureLifetime.Ticket borrowFrame() { return sealed.get() ? null : normal.begin(); }
    boolean active() { return active && !sealed.get() && request.connection.isConnected(); }
    void stop() { seal(); request.execution().requestStop(); }
    @Override public void seal() {
        if (!sealed.compareAndSet(false, true)) return;
        active = false;
        service.sealManagedDevice(this);
    }
    /** Counted exact main continuation. Busy admission is retained, not dropped or replayed after execution. */
    boolean postMain(Runnable action, Runnable discarded) { return submit(main::post, action, discarded); }
    boolean postMainAfter(Runnable action, long delay) {
        return submit(callback -> main.postDelayed(callback, delay), action, () -> {});
    }
    boolean execute(Executor executor, Runnable action) {
        return submit(callback -> { executor.execute(callback); return true; }, action, () -> {});
    }
    /** Short exact admission; only pinned native computation executes outside the effect gate. */
    <T> boolean executeNative(Executor executor, java.util.function.Supplier<T> capture,
            java.util.function.Consumer<T> nativeBody, Runnable discarded) {
        return submit(callback -> { executor.execute(callback); return true; }, () -> {
            DeviceRuntimeEffects.DeviceComponent.NativeWork<T> work = component.normalWork(capture);
            if (work == null) return false;
            try { nativeBody.accept(work.facts()); }
            catch (RuntimeException | Error failed) { stop(); discarded.run(); throw failed; }
            finally { work.close(); }
            return true;
        }, discarded);
    }
    boolean executeNormal(Executor executor, Runnable action, Runnable discarded) {
        return submit(callback -> { executor.execute(callback); return true; }, action, discarded);
    }
    private boolean submit(OwnedCaptureLifetime.Dispatcher dispatcher, Runnable action, Runnable discarded) {
        return submit(dispatcher, () -> component.normal(action), discarded);
    }
    private boolean submit(OwnedCaptureLifetime.Dispatcher dispatcher, java.util.function.BooleanSupplier admitted, Runnable discarded) {
        Objects.requireNonNull(dispatcher); Objects.requireNonNull(admitted); Objects.requireNonNull(discarded);
        OwnedCaptureLifetime.Ticket ticket = normal.begin();
        if (ticket == null) { discarded.run(); return false; }
        return new Continuation(ticket, dispatcher, admitted, discarded).post();
    }
    private final class Continuation {
        final OwnedCaptureLifetime.Ticket ticket;
        final OwnedCaptureLifetime.Dispatcher dispatcher;
        final java.util.function.BooleanSupplier admitted;
        final Runnable discarded;
        final AtomicBoolean finished = new AtomicBoolean();
        Continuation(OwnedCaptureLifetime.Ticket ticket, OwnedCaptureLifetime.Dispatcher dispatcher,
                java.util.function.BooleanSupplier admitted, Runnable discarded) {
            this.ticket = ticket; this.dispatcher = dispatcher; this.admitted = admitted; this.discarded = discarded;
        }
        boolean post() {
            if (finished.get()) return false;
            if (sealed.get() || !request.connection.isConnected()) { finish(false); return false; }
            try {
                boolean accepted = normal.dispatchContinuation(ticket, dispatcher, this::run);
                if (!accepted) { finish(false); stop(); }
                return accepted;
            } catch (RuntimeException | Error uncertain) {
                // An enqueue/run-then-throw retains its exact child and parent until actual return.
                stop(); throw uncertain;
            }
        }
        void run() {
            if (finished.get()) return;
            if (sealed.get() || !request.connection.isConnected()) { finish(false); return; }
            boolean ran = false;
            try { ran = admitted.getAsBoolean(); }
            catch (RuntimeException | Error failed) { finish(true); stop(); throw failed; }
            if (ran) { finish(true); return; }
            // No body ran. One counted retry preserves exact demand while the ordinary gate is busy.
            try {
                if (!normal.dispatchContinuation(ticket, callback -> main.postDelayed(callback, 16), this::post)) {
                    finish(false); stop();
                }
            } catch (RuntimeException | Error uncertain) { stop(); throw uncertain; }
        }
        void finish(boolean executed) {
            if (!finished.compareAndSet(false, true)) return;
            try { if (!executed) discarded.run(); }
            finally { ticket.close(); }
        }
    }
    /** Fixed-delay capture loop; cancellation fences demand, never removes an accepted callback. */
    void startCaptureLoop(Executor executor, Runnable capture) {
        requireMain(); cancelCaptureLoop();
        if (sealed.get()) return;
        CaptureLoop loop = new CaptureLoop(executor, capture); captureLoop = loop; loop.post();
    }
    void cancelCaptureLoop() {
        CaptureLoop loop = captureLoop; captureLoop = null;
        if (loop != null) loop.cancelled = true;
    }
    private final class CaptureLoop {
        final Executor executor; final Runnable capture; volatile boolean cancelled;
        CaptureLoop(Executor executor, Runnable capture) { this.executor = executor; this.capture = capture; }
        void post() {
            if (cancelled || sealed.get()) return;
            execute(executor, () -> {
                if (cancelled || sealed.get()) return;
                capture.run();
                if (!cancelled && !sealed.get()) postMainAfter(this::post, 16);
            });
        }
    }
    @Override public void beginClose(Runnable receipt) {
        Objects.requireNonNull(receipt);
        synchronized (this) {
            if (afterDrain != null && afterDrain != receipt) throw new IllegalStateException("Different attachment closure observer");
            afterDrain = receipt;
        }
        stop();
        if (drained) deliverReceipt();
    }
    private void startClosing() {
        if (!closing.compareAndSet(false, true)) return;
        normal.seal(() -> { normalReturned = true; maybeCloseBack(); });
        cleanupDispatch(main::post, () -> {
            cleanup.run(() -> service.closeManagedFront(this));
            frontReturned = true;
            maybeCloseBack();
        });
        pollDrain();
    }
    private void maybeCloseBack() {
        synchronized (this) {
            if (!normalReturned || !nativeReturned || !frontReturned || backStarted || !service.managedProjectionFrontendRetired(this)) return;
            backStarted = true;
        }
        service.closeManagedBack(this, () -> backReturned = true);
    }
    /** Driver cleanup queues are also retained through enqueue uncertainty and real callback return. */
    void cleanupDispatch(OwnedCaptureLifetime.Dispatcher dispatcher, Runnable action) {
        try {
            if (!cleanupWork.dispatch(dispatcher, () -> {
                try { action.run(); }
                catch (RuntimeException | Error uncertain) { cleanupFailed(); throw uncertain; }
            })) cleanupFailed();
        } catch (RuntimeException | Error uncertain) { cleanupFailed(); throw uncertain; }
    }
    void cleanupFailed() { failure = true; component.cleanupFailed(); }
    private void pollDrain() {
        if (drained) return;
        maybeCloseBack();
        if (failure && !service.managedProjectionFrontendPending(this)) return;
        if (!failure && normalReturned && normal.drained() && frontReturned && backReturned
                && cleanupWork.outstanding() == 0 && service.managedProjectionResourcesDrained(this)) {
            cleanupWork.seal(() -> {});
            if (!cleanupWork.drained()) { cleanupFailed(); return; }
            drained = true;
            try { service.managedProjectionDrained(this); }
            catch (RuntimeException | Error uncertain) { drained = false; cleanupFailed(); return; }
            cleanup.drained();
            deliverReceipt();
        } else {
            try { if (!main.postDelayed(this::pollDrain, 16)) cleanupFailed(); }
            catch (RuntimeException | Error uncertain) { cleanupFailed(); }
        }
    }
    private void deliverReceipt() {
        Runnable receipt;
        synchronized (this) { receipt = afterDrain; afterDrain = null; }
        if (receipt != null) receipt.run();
    }
    @Override public boolean physicalOccupancy() { return !drained; }
    @Override public boolean managedResourcesDrained() { return drained; }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Projection attachment requires main");
    }
}
