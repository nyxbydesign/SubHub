package com.subhub.app.internal;

import com.subhub.app.runtime.RuntimeLease;
import com.subhub.app.runtime.OwnedResourceRegistry;
import com.subhub.app.runtime.RuntimeTarget;
import com.subhub.app.stats.OwnerWriteQueue;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;

/**
 * One handle on one supplied serial worker. Admission monitors protect bookkeeping only: no
 * opening, IO, user action, completion callback or executor invocation occurs while they are held.
 * Queueing a generic command is not admission; its exact generation/policy is checked on the worker.
 */
public final class SerialOwnerDataBroker<H extends AutoCloseable> {
    @FunctionalInterface public interface Factory<H> { H open(Guards guards) throws Exception; }
    public interface Policy {
        void task(String generation);
        void editor(String generation, long controllerRevision);
        void read(String generation);
        default void visibility(String generation, long controllerRevision) { throw new SecurityException("Visibility admission unavailable"); }
    }
    public interface Guards {
        void requireData();
        void requireSession();
        void dataUnavailable(Throwable failure);
    }
    private enum Lane { DATA, RETIRED_TASK_NOTICES, RUNTIME, TEARDOWN }
    private final Object state = new Object();
    private final Executor worker;
    private final Policy policy;
    private final Factory<H> factory;
    private final ThreadLocal<Boolean> dispatched = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<Lane> lane = new ThreadLocal<>();
    private final ThreadLocal<Binding> activeBinding = new ThreadLocal<>();
    private final java.util.concurrent.atomic.AtomicBoolean executing = new java.util.concurrent.atomic.AtomicBoolean();
    private final Guards guards = new Guards() {
        @Override public void requireData() {
            if (!dispatched.get() || lane.get() == null) throw new SecurityException("No owner-data admission");
            if (lane.get() != Lane.TEARDOWN) requireAvailable();
            if (lane.get() == Lane.RUNTIME) requireSession();
        }
        @Override public void dataUnavailable(Throwable failure) { failData(failure); }
        @Override public void requireSession() {
            requireAvailable();
            Binding active = activeBinding.get();
            if (!dispatched.get() || lane.get() != Lane.RUNTIME || active == null)
                throw new SecurityException("No exact runtime-data admission");
            synchronized (state) {
                if (binding != active || active.released) throw new SecurityException("Runtime-data binding released");
            }
            active.queue.requireWrite();
        }
    };
    private H handle;
    private Throwable openingFailure;
    private boolean openingAttempted, closing, closeQueued, recovering, changingVisibility;
    private volatile Throwable unavailable;
    private int pending;
    private Binding binding;
    private final CompletableFuture<Void> closed = new CompletableFuture<>();

    public SerialOwnerDataBroker(Executor serialWorker, Policy policy, Factory<H> factory) {
        worker = Objects.requireNonNull(serialWorker); this.policy = Objects.requireNonNull(policy);
        this.factory = Objects.requireNonNull(factory);
    }
    /** Memory-only cache hint health. This grants no data or runtime admission. */
    public boolean acceptsDataHint() {
        synchronized (state) { return !closing && unavailable == null; }
    }
    /** Trusted host setup only; does not require the initially hidden profile to be visible. */
    public CompletableFuture<Void> initialize() { return submit(() -> { }, ignored -> null); }
    public <T> CompletableFuture<T> submitTask(String generation, Function<H, T> command) {
        return submit(() -> policy.task(generation), command);
    }
    public <T> CompletableFuture<T> submitEditor(String generation, long controller, Function<H, T> command) {
        return submit(() -> policy.editor(generation, controller), command);
    }
    /** Queued coherent observation. It cannot race any queued write or session lane publication. */
    public <T> CompletableFuture<T> submitRead(String generation, Function<H, T> command) {
        return submit(() -> policy.read(generation), command);
    }
    /** The host owns full-record/controller checks; runtime binding cannot race the visibility CAS. */
    public <T> CompletableFuture<T> submitVisibility(String generation, long controller, Function<H, T> command) {
        return submit(() -> policy.visibility(generation, controller), handle -> {
            synchronized (state) {
                if (binding != null || recovering || changingVisibility)
                    throw new SecurityException("Stop and drain the runtime before visibility changes");
                changingVisibility = true;
            }
            try { return command.apply(handle); }
            finally { synchronized (state) { changingVisibility = false; } }
        }, Lane.DATA, true);
    }
    /** Data repair is mutually exclusive with runtime binding, without holding a lock over repair IO. */
    public <T> CompletableFuture<T> submitRecovery(String generation, long controller, Function<H, T> command) {
        Objects.requireNonNull(command);
        return submit(() -> policy.editor(generation, controller), handle -> {
            synchronized (state) {
                if (binding != null || recovering) throw new SecurityException("Runtime binding prevents data recovery");
                recovering = true;
            }
            try { return command.apply(handle); }
            finally { synchronized (state) { recovering = false; } }
        });
    }
    /** Host exposes only exact recorded notice cancellation through this lane, never task work. */
    public <T> CompletableFuture<T> submitRetiredTaskNotices(Function<H, T> command) {
        return submit(() -> { }, command, Lane.RETIRED_TASK_NOTICES);
    }
    private <T> CompletableFuture<T> submit(Runnable admit, Function<H, T> command) {
        return submit(admit, command, Lane.DATA);
    }
    private <T> CompletableFuture<T> submit(Runnable admit, Function<H, T> command, Lane admittedLane) {
        return submit(admit, command, admittedLane, false);
    }
    private <T> CompletableFuture<T> submit(Runnable admit, Function<H, T> command, Lane admittedLane, boolean settleActualOutcome) {
        Objects.requireNonNull(command); CompletableFuture<T> result = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicBoolean executorInvoked = new java.util.concurrent.atomic.AtomicBoolean();
        try { dispatch(() -> {
            T value;
            try {
                requireNoLane(); admit.run(); lane.set(admittedLane);
                try { value = command.apply(handle()); }
                finally { lane.remove(); }
            } catch (Throwable failure) { result.completeExceptionally(failure); return; }
            // Completion observers never inherit the completed data command's authority.
            result.complete(value);
        }, false, executorInvoked); }
        catch (RuntimeException | Error uncertain) {
            if (!settleActualOutcome || !executorInvoked.get()) throw uncertain;
            // A visibility proof remains outstanding until the actual callback settles. Do not
            // complete it early merely because an executor threw after accepting the callback.
        }
        return result;
    }
    /** Only use this executor for the exact retained OwnerWriteQueue supplied to bindRuntime. */
    public Executor runtimeWorker() { return action -> dispatch(action, true); }

    public Binding bindRuntime(RuntimeLease.Identity expected, OwnerWriteQueue queue) {
        Objects.requireNonNull(expected); Objects.requireNonNull(queue);
        if (expected.target.kind != RuntimeTarget.Kind.INTERNAL
                || !InternalStorageScope.TARGET_ID.equals(expected.target.id)
                || !expected.equals(queue.identity())) throw new SecurityException("Different owner-data runtime");
        synchronized (state) {
            if (closing || recovering || changingVisibility || unavailable != null || binding != null)
                throw new IllegalStateException("Owner-data runtime is bound, unavailable, changing or closing");
            binding = new Binding(queue); return binding;
        }
    }
    /** No capability is reconstructed from an identity read from disk. */
    public final class Binding {
        private final OwnerWriteQueue queue;
        private boolean stopping, released;
        private Binding(OwnerWriteQueue queue) { this.queue = queue; }
        public RuntimeLease.Identity identity() { return queue.identity(); }
        /** Memory-only lifetime check, not permission to mutate any source. */
        public void requireBound() {
            synchronized (state) {
                if (binding != this || released || unavailable != null) throw new SecurityException("Stale or unavailable runtime-data binding");
            }
        }
        /** Exact-phase assertion uses the already-held permit; it never reacquires authority. */
        public <T> T held(OwnedResourceRegistry.Effect phase, Function<H, T> command) {
            queue.requireWrite(Objects.requireNonNull(phase)); return held(command);
        }
        /** Inline only inside this queue's already-counted STARTUP/NORMAL/CLEANUP action. */
        public <T> T held(Function<H, T> command) {
            Objects.requireNonNull(command); requireNoLane();
            requireBound();
            queue.requireWrite(); // Existing permits stay valid after Stop or generation rotation.
            lane.set(Lane.RUNTIME); activeBinding.set(this);
            try { return command.apply(handle()); }
            finally { activeBinding.remove(); lane.remove(); }
        }
        /** Session cleanup releases only this wrapper, never the shared handle/backend. */
        public CompletableFuture<Void> stop(Function<H, Void> cleanup, Runnable afterDrained) {
            Objects.requireNonNull(cleanup); Objects.requireNonNull(afterDrained);
            synchronized (state) {
                if (binding != this || stopping || released) throw new IllegalStateException("Runtime-data closure already requested");
                stopping = true;
            }
            return queue.closeAfterDrain(() -> held(OwnedResourceRegistry.Effect.CLEANUP, cleanup), () -> {
                requireNoLane();
                if (queue.unresolvedWrites() != 0) throw new IllegalStateException("Runtime-data writes remain unresolved");
                afterDrained.run();
                synchronized (state) { released = true; binding = null; }
                scheduleCloseIfDrained();
            });
        }
    }
    /** Reject fresh data work immediately; a bound session must independently stop and ACK cleanup. */
    public CompletableFuture<Void> closeAfterDrain() {
        synchronized (state) { closing = true; }
        scheduleCloseIfDrained(); return closed;
    }
    private void dispatch(Runnable action, boolean runtime) { dispatch(action, runtime, null); }
    private void dispatch(Runnable action, boolean runtime, java.util.concurrent.atomic.AtomicBoolean executorInvoked) {
        Objects.requireNonNull(action);
        synchronized (state) {
            // A bound session retains its cleanup route while the broker is closing.
            if (closeQueued || closing && !(runtime && binding != null))
                throw new RejectedExecutionException("Owner-data broker closing");
            pending++;
        }
        java.util.concurrent.atomic.AtomicBoolean invoked = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean returned = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            if (executorInvoked != null) executorInvoked.set(true);
            worker.execute(() -> {
                if (!invoked.compareAndSet(false, true)) return;
                try {
                    if (!executing.compareAndSet(false, true))
                        throw new IllegalStateException("Owner-data worker must be serial and non-reentrant");
                    dispatched.set(true);
                    try { action.run(); returned.set(true); }
                    finally { dispatched.remove(); executing.set(false); }
                } finally {
                    if (completed.compareAndSet(false, true)) {
                        synchronized (state) { pending--; }
                        scheduleCloseIfDrained();
                    }
                }
            });
        } catch (RuntimeException | Error uncertainDispatch) {
            // Executor invocation already occurred. It may have enqueued work before throwing.
            // Only actual callback completion releases this dispatch obligation; never double
            // decrement or infer that a generic exception means the callback cannot still run.
            if (completed.get() && returned.get()) return; // The caller receives the action's actual completed receipt.
            if (!completed.get()) failData(uncertainDispatch);
            throw uncertainDispatch;
        }
    }
    private void requireAvailable() {
        Throwable failure = unavailable;
        if (failure != null) throw new IllegalStateException("Owner data is unavailable", failure);
    }
    private void failData(Throwable failure) {
        Objects.requireNonNull(failure); Binding notify;
        synchronized (state) {
            if (unavailable != null) return;
            unavailable = failure; notify = binding;
        }
        // No state monitor spans registry fencing or the host's failure callback.
        if (notify != null) notify.queue.reportDataFailure(failure);
    }
    private H handle() {
        guards.requireData();
        if (openingFailure != null) throw new IllegalStateException("Owner-data opening requires recovery", openingFailure);
        if (!openingAttempted) {
            openingAttempted = true;
            try { handle = Objects.requireNonNull(factory.open(guards)); }
            catch (Throwable failure) { openingFailure = failure; failData(failure); throw new IllegalStateException("Owner data unavailable", failure); }
        }
        return handle;
    }
    private void requireNoLane() {
        if (!dispatched.get()) throw new SecurityException("Use the shared owner-data worker");
        if (lane.get() != null) throw new SecurityException("Cannot borrow another data admission");
    }
    private void scheduleCloseIfDrained() {
        synchronized (state) {
            if (!closing || closeQueued || pending != 0 || binding != null) return;
            closeQueued = true;
        }
        java.util.concurrent.atomic.AtomicBoolean invoked = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            worker.execute(() -> {
                if (!invoked.compareAndSet(false, true)) return;
                try {
                    if (!executing.compareAndSet(false, true)) throw new IllegalStateException("Concurrent owner-data close");
                    dispatched.set(true); lane.set(Lane.TEARDOWN);
                    try { if (handle != null) handle.close(); }
                    finally { lane.remove(); dispatched.remove(); executing.set(false); }
                    closed.complete(null);
                } catch (Throwable failure) { closed.completeExceptionally(failure); }
            });
        } catch (RuntimeException | Error rejected) { closed.completeExceptionally(rejected); }
    }
}
