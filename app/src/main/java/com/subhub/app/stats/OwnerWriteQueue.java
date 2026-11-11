package com.subhub.app.stats;

import com.subhub.app.runtime.OwnedResourceRegistry;
import com.subhub.app.runtime.RuntimeLease;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Exact-owner admitted work, never a lookup of whichever owner is current later. A required
 * runtime component must retain this queue until closeAfterDrain acknowledges actual closure.
 */
public final class OwnerWriteQueue {
    /** Definite refusal before executor invocation. Never use for an ambiguous dispatch/write. */
    public static final class AdmissionRejected extends java.util.concurrent.RejectedExecutionException {
        private static final long serialVersionUID = 1L;
        /** Broker callers may map only their own verified pre-dispatch binding refusal. */
        public AdmissionRejected(String message, Throwable cause) { super(message, cause); }
    }
    private final RuntimeLease lease;
    private final OwnedResourceRegistry registry;
    private final Executor serialWorker;
    private final Consumer<Throwable> failure;
    private final Object admission = new Object();
    private final ThreadLocal<Boolean> writing = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<OwnedResourceRegistry.Effect> writingEffect = new ThreadLocal<>();
    private boolean sealed, closeQueued;
    private volatile Throwable failed;
    // Keep failed/unacknowledged payloads with this required component for explicit reconciliation.
    // An uncertain action is never replayed automatically, and its payload is not silently lost.
    private final java.util.Set<Work> unresolved = new java.util.LinkedHashSet<>();
    private static final class Work {
        final Runnable action;
        final java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
        Work(Runnable action) { this.action = action; }
    }
    public int unresolvedWrites() { synchronized (admission) { return unresolved.size(); } }

    public OwnerWriteQueue(RuntimeLease lease, OwnedResourceRegistry registry, Executor serialWorker,
            Consumer<Throwable> failure) {
        this.lease = Objects.requireNonNull(lease); this.registry = Objects.requireNonNull(registry);
        this.serialWorker = Objects.requireNonNull(serialWorker); this.failure = Objects.requireNonNull(failure);
    }
    public RuntimeLease.Identity identity() { return lease.identity; }
    /** Backend guard: must remain inside this admitted task through actual durable commit/ACK. */
    public void requireWrite() {
        if (!writing.get() || failed != null) throw new SecurityException("No admitted owner writer");
    }
    /** Exact held phase, without a new admission after registry sealing. */
    public void requireWrite(OwnedResourceRegistry.Effect expected) {
        requireWrite();
        if (writingEffect.get() != Objects.requireNonNull(expected))
            throw new SecurityException("Different owner writer phase");
    }
    /** Main-safe admission only. Never wait for this future from a window or Activity callback. */
    public CompletableFuture<Void> submit(RuntimeLease.Identity expected, Runnable durableAction) {
        return submit(expected, durableAction, OwnedResourceRegistry.Effect.NORMAL);
    }
    /** Startup initialization runs on the same queue before the required component becomes ready. */
    public CompletableFuture<Void> submitStartup(RuntimeLease.Identity expected, Runnable durableAction) {
        return submit(expected, durableAction, OwnedResourceRegistry.Effect.STARTUP);
    }
    private CompletableFuture<Void> submit(RuntimeLease.Identity expected, Runnable action,
            OwnedResourceRegistry.Effect kind) {
        Objects.requireNonNull(action);
        if (!lease.identity.equals(expected)) throw new AdmissionRejected("Different runtime write owner", null);
        CompletableFuture<Void> receipt = new CompletableFuture<>();
        synchronized (admission) {
            if (sealed || failed != null) throw new AdmissionRejected("Owner writer is sealed or unavailable", failed);
            OwnedResourceRegistry.EffectPermit permit;
            try { permit = registry.acquireEffectPermit(lease, kind); }
            catch (SecurityException revoked) { throw new AdmissionRejected("Owner write admission was revoked", revoked); }
            Work work = new Work(action); unresolved.add(work);
            try {
                serialWorker.execute(() -> execute(work, permit, receipt, null));
            } catch (RuntimeException | Error uncertain) {
                // Executor invocation already happened. Even RejectedExecutionException cannot
                // prove that a callback wasn't queued or started. Only its actual return may
                // release the permit; a callback that never arrives needs explicit reconciliation.
                unresolved.add(work);
                if (uncertain instanceof AdmissionRejected) {
                    IllegalStateException ambiguous = new IllegalStateException("Owner executor dispatch outcome is uncertain", uncertain);
                    fail(ambiguous); receipt.completeExceptionally(ambiguous); throw ambiguous;
                }
                fail(uncertain); receipt.completeExceptionally(uncertain); throw uncertain;
            }
        }
        return receipt;
    }
    /**
     * Called by the registered component's shutdown after registry sealing. No new admissions;
     * already accepted permits finish first. Cleanup includes final stats/history ACK and backend
     * close. Failure retains the component obligation and physical occupancy for reconciliation.
     */
    public CompletableFuture<Void> closeAfterDrain(Runnable cleanup, Runnable afterDrained) {
        Objects.requireNonNull(cleanup); Objects.requireNonNull(afterDrained);
        CompletableFuture<Void> receipt = new CompletableFuture<>();
        synchronized (admission) {
            sealed = true;
            if (closeQueued) throw new IllegalStateException("Owner writer closure already queued");
            closeQueued = true;
            try {
                serialWorker.execute(() -> {
                    if (failed != null) { receipt.completeExceptionally(afterDispatch(failed)); return; }
                    try {
                        OwnedResourceRegistry.EffectPermit permit = registry.acquireEffectPermit(lease, OwnedResourceRegistry.Effect.CLEANUP);
                        Work work = new Work(cleanup);
                        synchronized (admission) { unresolved.add(work); }
                        execute(work, permit, receipt, afterDrained);
                    } catch (RuntimeException error) {
                        Throwable uncertain = afterDispatch(error); fail(uncertain); receipt.completeExceptionally(uncertain);
                    }
                });
            } catch (RuntimeException rejected) {
                Throwable uncertain = afterDispatch(rejected); fail(uncertain); receipt.completeExceptionally(uncertain);
            }
        }
        return receipt;
    }
    private void execute(Work work, OwnedResourceRegistry.EffectPermit permit,
            CompletableFuture<Void> receipt, Runnable afterDrained) {
        if (!work.started.compareAndSet(false, true)) return;
        Throwable error = null;
        try (OwnedResourceRegistry.EffectPermit held = permit) {
            Objects.requireNonNull(held);
            if (failed != null) { error = afterDispatch(failed); }
            else {
                if (writing.get()) throw new IllegalStateException("Owner writer must be serial, not reentrant");
                writing.set(true); writingEffect.set(permit.effect);
                try { work.action.run(); }
                finally { writingEffect.remove(); writing.remove(); }
            }
        } catch (Throwable failedAction) { error = afterDispatch(failedAction); fail(error); }
        // No terminal callback can release the component while its cleanup permit remains open.
        if (error != null) { receipt.completeExceptionally(error); return; }
        try {
            synchronized (admission) {
                // An executor can throw while this already-started callback is returning. The
                // action's return closes its permit but does not erase that uncertain dispatch.
                if (failed != null) error = afterDispatch(failed);
                else unresolved.remove(work);
            }
            if (error != null) { receipt.completeExceptionally(error); return; }
            if (afterDrained != null) afterDrained.run();
            receipt.complete(null);
        } catch (Throwable failedClosure) {
            Throwable uncertain = afterDispatch(failedClosure); fail(uncertain); receipt.completeExceptionally(uncertain);
        }
    }
    private static Throwable afterDispatch(Throwable error) {
        return error instanceof AdmissionRejected
                ? new IllegalStateException("Owner work failed after dispatch", error) : error;
    }
    /** Broker persistence failure may originate in a task/editor lane without a runtime permit. */
    public void reportDataFailure(Throwable error) { fail(Objects.requireNonNull(error)); }

    private void fail(Throwable error) {
        error = afterDispatch(error);
        boolean report;
        synchronized (admission) { report = failed == null; if (report) failed = error; sealed = true; }
        if (report) registry.fence(lease);
        if (report) try { failure.accept(error); }
        catch (Throwable observerFailure) {
            // Failure reporting must not prevent the actual failed receipt from completing.
            if (observerFailure != error) error.addSuppressed(observerFailure);
        }
    }
}
