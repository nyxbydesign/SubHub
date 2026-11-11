package com.subhub.app.service;

import android.view.accessibility.AccessibilityEvent;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Exact-instance managed Accessibility shell. No settings, model, window or DEVICE fallback is
 * created here. This is a service lifecycle consumer, not a complete capture attachment/factory.
 * Its dispatcher must target the service main thread; entry methods are main-confined unless noted.
 */
public final class AccessibilityServiceConnection {
    /** Prepared component shutdown is installed before this interface reaches the OS service. */
    public interface Events extends RuntimeCaptureHost.Attachment {
        /** Pooled framework event: consume synchronously or own a counted copy before returning. */
        void onAccessibilityEvent(AccessibilityEvent event);
        /**
         * Single exact host callback, bound before attach. ONLY the registered component's real
         * runtime shutdown may invoke it. A shutdown preceding binding must remain sticky. This
         * callback requests host cleanup; it is not a cleanup capability or a drain receipt.
         */
        void bindHostStop(Runnable exactHostStop);
    }
    interface MainQueue extends OwnedCaptureLifetime.Dispatcher {
        boolean postDelayed(Runnable callback, long delayMillis);
    }
    interface Port {
        CompletableFuture<Void> connected(RuntimeCaptureHost.Connection connection, RuntimeCaptureHost.Completion completion);
        CompletableFuture<Void> disconnected(RuntimeCaptureHost.Connection connection);
        CompletableFuture<Void> rejected(RuntimeCaptureHost.Connection connection, RuntimeCaptureHost.Request request,
                RuntimeCaptureHost.Rejection reason);
        CompletableFuture<Void> drained(RuntimeCaptureHost.Connection connection, RuntimeCaptureHost.Selection selection);
    }
    private final RuntimeCaptureHost.Connection connection;
    private final Port port;
    private final MainQueue main;
    private final java.util.function.Consumer<AccessibilityServiceConnection> retired;
    private final AtomicBoolean retirementNotified = new AtomicBoolean();
    private final OwnedCaptureLifetime callbacks = new OwnedCaptureLifetime();
    private final AtomicBoolean coreStop = new AtomicBoolean();
    private volatile boolean stopped, failed, retirementDelivered;
    private volatile RuntimeCaptureHost.Selection selected;
    private Events events;
    private volatile OwnedCaptureLifetime.Ticket stopNotification;
    private boolean started, claimed, closeStarted, drainReported, attachmentReceipt;
    private static final int MAX_PENDING_EVENTS = 128;
    private final java.util.ArrayDeque<PendingEvent> pendingEvents = new java.util.ArrayDeque<>();
    private boolean retryQueued;
    private final class PendingEvent implements AutoCloseable {
        final RuntimeCaptureHost.Selection selection;
        final AccessibilityEvent event;
        final OwnedCaptureLifetime.Ticket retained;
        PendingEvent(RuntimeCaptureHost.Selection selection, AccessibilityEvent event,
                OwnedCaptureLifetime.Ticket retained) {
            this.selection = selection; this.event = event; this.retained = retained;
        }
        @Override public void close() {
            try { event.recycle(); } finally { retained.close(); }
        }
    }

    AccessibilityServiceConnection(RuntimeCaptureHost.AccessibilityHost host, Port port,
            MainQueue main) {
        this(host, port, main, ignored -> {});
    }
    AccessibilityServiceConnection(RuntimeCaptureHost.AccessibilityHost host, Port port,
            MainQueue main, java.util.function.Consumer<AccessibilityServiceConnection> retired) {
        connection = RuntimeCaptureHost.Connection.accessibility(Objects.requireNonNull(host));
        this.port = Objects.requireNonNull(port); this.main = Objects.requireNonNull(main);
        this.retired = Objects.requireNonNull(retired);
    }
    RuntimeCaptureHost.Connection connection() { return connection; }

    void connect() {
        if (started) throw new IllegalStateException("Accessibility connection is single-use");
        started = true;
        OwnedCaptureLifetime.Ticket invocation = callbacks.begin();
        OwnedCaptureLifetime.Ticket delivery = callbacks.begin();
        AtomicBoolean delivered = new AtomicBoolean();
        try {
            observe(port.connected(connection, new RuntimeCaptureHost.Completion() {
                @Override public void selected(RuntimeCaptureHost.Selection value) {
                    if (!delivered.compareAndSet(false, true)) { fail(); return; }
                    try { accept(value); } finally { delivery.close(); }
                }
                @Override public void rejected(RuntimeCaptureHost.Rejection reason) {
                    if (!delivered.compareAndSet(false, true)) { fail(); return; }
                    // No owner was selected. Remain neutral, without starting physical defaults.
                    try { stopped = true; connection.disconnected(); }
                    finally { delivery.close(); notifyRetired(); }
                }
            }));
        } catch (RuntimeException | Error uncertain) {
            fail(); // A throwing dispatch might still deliver. Never invent delivery completion.
            throw uncertain;
        } finally { invocation.close(); }
    }

    private void accept(RuntimeCaptureHost.Selection value) {
        Objects.requireNonNull(value);
        if (value.request.connection != connection || selected != null) {
            value.request.execution().requestStop(); fail();
            throw new SecurityException("Different Accessibility selection");
        }
        selected = value; // Retain even a partial/unsupported attachment for truthful occupancy.
        if (!(value.attachment instanceof Events)) {
            reject(RuntimeCaptureHost.Rejection.UNSUPPORTED); return;
        }
        events = (Events) value.attachment;
        stopNotification = callbacks.begin();
        if (stopNotification == null) { reject(RuntimeCaptureHost.Rejection.CLEANUP_UNCERTAIN); return; }
        try {
            events.bindHostStop(this::coreStopping);
            if (stopped || !connection.isConnected() || !value.claim(connection)) {
                reject(RuntimeCaptureHost.Rejection.STALE_CONNECTION); return;
            }
            claimed = true;
            // The gateway owns this exact selected delivery through attach's actual return.
            events.attach(null);
        } catch (RuntimeException | Error failure) {
            reject(RuntimeCaptureHost.Rejection.ATTACHMENT_FAILED);
            throw failure;
        }
    }

    void event(AccessibilityEvent event) {
        RuntimeCaptureHost.Selection exact = selected;
        Events receiver = events;
        if (event == null || stopped || exact == null || receiver == null || !claimed) return;
        OwnedCaptureLifetime.Ticket callback = callbacks.begin();
        if (callback == null) return;
        try {
            // Preserve event order when an earlier event was refused busy/STARTING admission.
            boolean delivered = pendingEvents.isEmpty() && exact.request.execution().runEffect(() -> {
                if (!stopped) receiver.onAccessibilityEvent(event);
            });
            if (!delivered && !stopped) retainEvent(exact, event);
        } catch (RuntimeException | Error failure) {
            reject(RuntimeCaptureHost.Rejection.ATTACHMENT_FAILED); throw failure;
        } finally { callback.close(); }
    }
    private void retainEvent(RuntimeCaptureHost.Selection exact, AccessibilityEvent pooled) {
        if (pendingEvents.size() >= MAX_PENDING_EVENTS) {
            // Never silently discard required foreground/scroll ordering and continue enforcement.
            reject(RuntimeCaptureHost.Rejection.CONFLICT); clearPendingEvents(); return;
        }
        OwnedCaptureLifetime.Ticket retained = callbacks.begin();
        if (retained == null) return;
        AccessibilityEvent copy;
        try { copy = AccessibilityEvent.obtain(pooled); }
        catch (RuntimeException | Error failedCopy) { retained.close(); throw failedCopy; }
        pendingEvents.add(new PendingEvent(exact, copy, retained));
        retryEvents();
    }
    private void retryEvents() {
        if (stopped || retryQueued || pendingEvents.isEmpty()) return;
        retryQueued = true;
        try {
            if (!callbacks.dispatch(command -> main.postDelayed(command, 16L), () -> {
                retryQueued = false;
                if (stopped) { clearPendingEvents(); return; }
                try {
                    while (!pendingEvents.isEmpty()) {
                        // Remove before invoking consumer: it may synchronously disconnect and
                        // clear queued events. This in-flight copy remains ours through return.
                        PendingEvent pending = pendingEvents.remove();
                        boolean delivered;
                        try {
                            delivered = pending.selection.request.execution().runEffect(() -> {
                                if (!stopped) events.onAccessibilityEvent(pending.event);
                            });
                        } catch (RuntimeException | Error failure) { pending.close(); throw failure; }
                        if (delivered || stopped) pending.close();
                        else { pendingEvents.addFirst(pending); break; }
                    }
                    if (stopped) clearPendingEvents(); else retryEvents();
                } catch (RuntimeException | Error failure) {
                    reject(RuntimeCaptureHost.Rejection.ATTACHMENT_FAILED); clearPendingEvents();
                }
            })) {
                retryQueued = false; reject(RuntimeCaptureHost.Rejection.DISPATCH_UNCERTAIN); clearPendingEvents();
            }
        } catch (RuntimeException | Error uncertain) {
            // Accepted retry may still return; do not manufacture its ticket completion.
            fail(); clearPendingEvents(); throw uncertain;
        }
    }
    private void clearPendingEvents() {
        while (!pendingEvents.isEmpty()) pendingEvents.remove().close();
    }

    /** Service loss/user Stop fences immediately but never fabricates END-only cleanup authority. */
    void disconnect() {
        stopped = true; connection.disconnected();
        clearPendingEvents();
        RuntimeCaptureHost.Selection exact = selected;
        if (exact != null) {
            exact.request.execution().requestStop();
            try { exact.attachment.seal(); } catch (RuntimeException | Error failure) { fail(); }
        }
        try { observe(port.disconnected(connection)); }
        catch (RuntimeException | Error failure) { fail(); throw failure; }
    }

    /** May arrive from the runtime owner worker, including before attach or after OS destruction. */
    private void coreStopping() {
        stopped = true;
        if (!coreStop.compareAndSet(false, true)) return;
        OwnedCaptureLifetime.Ticket notification = stopNotification;
        try {
            if (!callbacks.dispatchContinuation(notification, main, this::closeOnMain)) fail();
        } catch (RuntimeException | Error uncertain) { fail(); throw uncertain; }
        finally { notification.close(); }
    }
    private void closeOnMain() {
        if (closeStarted) return;
        closeStarted = true;
        clearPendingEvents();
        RuntimeCaptureHost.Selection exact = selected;
        OwnedCaptureLifetime.Ticket terminal = callbacks.begin();
        AtomicBoolean received = new AtomicBoolean();
        try {
            exact.attachment.seal();
            exact.attachment.beginClose(() -> {
                if (!received.compareAndSet(false, true)) return;
                try {
                    if (!callbacks.dispatchContinuation(terminal, main, () -> attachmentReceipt = true)) fail();
                } catch (RuntimeException | Error uncertain) { fail(); }
                finally { terminal.close(); }
            });
        } catch (RuntimeException | Error uncertain) {
            // A throw may follow partial cleanup. Keep terminal obligation until actual receipt.
            fail();
        } finally {
            callbacks.seal(this::queueDrainCheck);
        }
    }
    private void queueDrainCheck() {
        // Runs only after accepted attach/event/Stop/close callback bodies AND submissions return.
        // The queue is the service main Handler, never an inline executor. A callback may run
        // before an off-main post call returns; requeue from main rather than infer submission
        // completion or finish lifecycle bookkeeping on the arbitrary submitting thread.
        final class Check implements Runnable {
            volatile boolean submitted;
            @Override public void run() {
                if (!submitted) { queueDrainCheck(); return; }
                finishDrain();
            }
        }
        Check check = new Check();
        try {
            if (!main.post(check)) { fail(); return; }
            check.submitted = true;
        } catch (RuntimeException | Error uncertain) { fail(); }
    }

    private void finishDrain() {
        if (failed || drainReported || !attachmentReceipt) return;
        // Last ticket may close off main immediately before its quiescent callback returns.
        // Recheck actual completion, never infer it from the queued marker.
        if (!callbacks.drained()) { queueDrainCheck(); return; }
        RuntimeCaptureHost.Selection exact = selected;
        if (!exact.attachment.managedResourcesDrained()) { fail(); return; }
        if (claimed && !connection.releaseAfterDrain(exact)) { fail(); return; }
        drainReported = true;
        try {
            Objects.requireNonNull(port.drained(connection, exact)).whenComplete((ignored, failure) -> {
                if (failure != null) fail(); else notifyRetired();
            });
        } catch (RuntimeException | Error uncertain) { fail(); }
    }
    private void notifyRetired() {
        if (failed || !retirementNotified.compareAndSet(false, true)) return;
        // This is neutral OS-source availability, not a new owner effect or a drain oracle.
        // Deliver only after the exact gateway drain method returned (or a no-owner rejection).
        try {
            if (!main.post(() -> {
                if (failed) return;
                connection.disconnected(); retirementDelivered = true; retired.accept(this);
            })) fail();
        } catch (RuntimeException | Error uncertain) { fail(); }
    }
    private void reject(RuntimeCaptureHost.Rejection reason) {
        stopped = true;
        RuntimeCaptureHost.Selection exact = selected;
        if (exact == null) return;
        exact.request.execution().requestStop();
        try { exact.attachment.seal(); observe(port.rejected(connection, exact.request, reason)); }
        catch (RuntimeException | Error uncertain) { fail(); }
    }
    private void observe(CompletableFuture<Void> ingress) {
        Objects.requireNonNull(ingress).whenComplete((ignored, failure) -> { if (failure != null) fail(); });
    }
    private void fail() {
        failed = true; stopped = true; connection.disconnected();
        RuntimeCaptureHost.Selection exact = selected;
        if (exact != null) exact.request.execution().requestStop();
    }
    /** Cached shell occupancy only; not a native/window/model drain receipt or installation proof. */
    boolean occupied() { return selected != null && !drainReported || failed; }
    boolean retired() { return retirementDelivered && !occupied(); }
}
