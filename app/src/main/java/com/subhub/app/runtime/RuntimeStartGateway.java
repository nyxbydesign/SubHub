package com.subhub.app.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Sole start/permission orchestration, intentionally not installed by this source slice. All
 * coordinator/journal calls run on the supplied serial owner worker. UI callbacks receive only
 * current-process attempts; outgoing peers are not physical targets. No profile/role/settings or
 * platform permission changes occur here. DEVICE and INTERNAL use the same reservation protocol.
 */
public final class RuntimeStartGateway {
    public static final class Admission {
        public final ActiveRuntimeCoordinator.Result result;
        public final RuntimeStartAttempt attempt;
        public final boolean repeatedRemoteCommand;
        private Admission(ActiveRuntimeCoordinator.Result result, RuntimeStartAttempt attempt, boolean repeated) {
            this.result = result; this.attempt = attempt; repeatedRemoteCommand = repeated;
        }
    }
    /** Exact capability handed to dependency construction, never serialized into a service Intent. */
    public static final class Starting {
        public final RuntimeLease lease;
        private Starting(RuntimeLease lease) { this.lease = lease; }
    }
    private final ActiveRuntimeCoordinator coordinator;
    private final Executor ownerWorker;
    private final Runnable requireOwnerWorker;
    private final Map<RuntimeLease, RuntimeStartAttempt> attempts = new ConcurrentHashMap<>();
    private final Map<RuntimeLease, AtomicBoolean> closing = new ConcurrentHashMap<>();
    private final AtomicBoolean dispatchUncertain = new AtomicBoolean();

    public RuntimeStartGateway(ActiveRuntimeCoordinator coordinator, Executor serialOwnerWorker,
            Runnable requireOwnerWorker) {
        this.coordinator = Objects.requireNonNull(coordinator); ownerWorker = Objects.requireNonNull(serialOwnerWorker);
        this.requireOwnerWorker = Objects.requireNonNull(requireOwnerWorker);
    }
    public boolean needsRecovery() { return dispatchUncertain.get(); }

    /** Startup reconciliation must already have succeeded; this method never provisions or adopts. */
    public CompletableFuture<Admission> reserve(RuntimeAdmissionAuthority.Witness witness, Set<String> components) {
        Set<String> selected = Set.copyOf(components);
        return submit(() -> {
            if (dispatchUncertain.get()) return denied(ActiveRuntimeCoordinator.Result.QUARANTINED);
            ActiveRuntimeCoordinator.Admission admitted = coordinator.reserve(witness, selected);
            if (admitted.result != ActiveRuntimeCoordinator.Result.OK) return denied(admitted.result);
            RuntimeStartAttempt existing = attempts.get(admitted.lease);
            if (admitted.repeatedRemoteCommand) {
                // Already handed-off commands belong to existing remote terminal/native receipts.
                // Never manufacture a new start attempt for the same active native command.
                return new Admission(ActiveRuntimeCoordinator.Result.OK, existing, true);
            }
            AtomicReference<RuntimeStartAttempt> reference = new AtomicReference<>();
            RuntimeStartAttempt attempt = new RuntimeStartAttempt(admitted.lease, coordinator.resources(admitted.lease),
                    () -> coordinator.reservePermission(admitted.lease), () -> {
                RuntimeStartAttempt exact = reference.get();
                if (exact != null && exact.revoked()) scheduleCancellation(exact);
            });
            reference.set(attempt); attempts.put(admitted.lease, attempt);
            return new Admission(ActiveRuntimeCoordinator.Result.OK, attempt, false);
        }, ignored -> {}, false);
    }
    /** Validate on the owner worker before the UI may launch the returned exact prompt. */
    public CompletableFuture<RuntimeStartAttempt.Prompt> permission(RuntimeStartAttempt attempt,
            RuntimeStartAttempt.Permission permission) {
        return submit(() -> {
            requireAttempt(attempt);
            if (!coordinator.acceptsPermissionResult(attempt.lease())) {
                attempt.revoke(); throw new SecurityException("Start authority changed before prompt");
            }
            return attempt.issue(permission);
        }, ignored -> {}, false);
    }
    /** Call from the matching Activity result only; passing true is not a substitute for OS readiness. */
    public CompletableFuture<Boolean> permissionResult(RuntimeStartAttempt attempt,
            RuntimeStartAttempt.Prompt prompt, boolean granted) {
        Objects.requireNonNull(prompt);
        return submit(() -> {
            if (!prompt.belongsTo(attempt)) throw new SecurityException("Permission result belongs to another attempt");
            boolean usable = prompt.resultReturned();
            if (!usable || attempts.get(attempt.lease()) != attempt) return false;
            if (!granted || !coordinator.acceptsPermissionResult(attempt.lease())) { attempt.revoke(); return false; }
            return true;
        }, ignored -> {}, true);
    }
    /** Readiness is a fresh pure observation; its platform reads must finish before this call. */
    public CompletableFuture<Starting> begin(RuntimeStartAttempt attempt, ActiveRuntimeCoordinator.StartReadiness readiness) {
        return submit(() -> {
            requireAttempt(attempt);
            if (!attempt.promptsReturned()) throw new IllegalStateException("Permission callback is still owned");
            ActiveRuntimeCoordinator.Result result = coordinator.beginStarting(attempt.lease(), readiness);
            if (result != ActiveRuntimeCoordinator.Result.OK || !attempt.transferToStarting()) {
                attempt.revoke(); throw new IllegalStateException("Start not admitted: " + result);
            }
            return new Starting(attempt.lease());
        }, started -> attempts.remove(started.lease, attempt), false);
    }
    /** Immediate cached fence; actual stop writes and prompt drain checks are queued, never waited. */
    public boolean cancel(RuntimeStartAttempt attempt) {
        if (attempt == null || attempts.get(attempt.lease()) != attempt) return false;
        return attempt.revoke();
    }
    private void requireAttempt(RuntimeStartAttempt attempt) {
        if (attempt == null || attempts.get(attempt.lease()) != attempt || attempt.revoked() || attempt.transferred())
            throw new SecurityException("Start attempt is no longer current");
    }
    private void scheduleCancellation(RuntimeStartAttempt attempt) {
        AtomicBoolean again = new AtomicBoolean();
        AtomicBoolean active = closing.putIfAbsent(attempt.lease(), again);
        if (active != null) { active.set(true); return; }
        submit(() -> {
            try {
                if (attempts.get(attempt.lease()) != attempt) return null;
                ActiveRuntimeCoordinator.Result stopped = coordinator.requestStop(attempt.lease());
                if (stopped != ActiveRuntimeCoordinator.Result.OK || !attempt.promptsReturned()) return null;
                OwnedResourceRegistry resources = coordinator.resources(attempt.lease());
                OwnedResourceRegistry.DrainReceipt proof = resources.drained(attempt.lease());
                if (proof != null && coordinator.completeStop(attempt.lease(), proof) == ActiveRuntimeCoordinator.Result.OK)
                    attempts.remove(attempt.lease(), attempt);
                return null;
            } finally {
                closing.remove(attempt.lease(), again);
                if (again.get() && attempts.get(attempt.lease()) == attempt) scheduleCancellation(attempt);
            }
        }, ignored -> {}, true);
    }
    private Admission denied(ActiveRuntimeCoordinator.Result result) { return new Admission(result, null, false); }

    /**
     * Completion requires BOTH actual callback return and successful dispatcher return. An executor
     * may enqueue/run then throw, including RejectedExecutionException; that is never non-admission
     * proof. Unknown dispatch fences this gateway and every unreported reservation for recovery.
     */
    private <T> CompletableFuture<T> submit(Supplier<T> action, Consumer<T> published, boolean cleanup) {
        Dispatch<T> dispatch = new Dispatch<>(action, published, cleanup);
        Throwable failure = null;
        try { ownerWorker.execute(dispatch::run); }
        catch (RuntimeException | Error uncertain) { failure = uncertain; quarantine(); }
        dispatch.dispatched(failure);
        return dispatch.result;
    }
    private void quarantine() {
        dispatchUncertain.set(true);
        for (RuntimeStartAttempt attempt : attempts.values()) {
            // Includes a STARTING receipt not yet published to dependency construction.
            attempt.fenceUnreported();
        }
    }
    private final class Dispatch<T> {
        final Supplier<T> action; final Consumer<T> published; final boolean cleanup;
        final CompletableFuture<T> result = new CompletableFuture<>();
        final AtomicBoolean started = new AtomicBoolean();
        boolean dispatched, returned, completed;
        T value; Throwable actionFailure, dispatchFailure;
        Dispatch(Supplier<T> action, Consumer<T> published, boolean cleanup) {
            this.action = action; this.published = published; this.cleanup = cleanup;
        }
        void run() {
            if (!started.compareAndSet(false, true)) return;
            T observed = null; Throwable failure = null;
            try {
                requireOwnerWorker.run();
                if (!cleanup && result.isDone()) throw new java.util.concurrent.CancellationException("Start response was withdrawn before worker admission");
                if (!cleanup && dispatchUncertain.get()) throw new SecurityException("Owner dispatcher needs reconciliation");
                observed = action.get();
            } catch (RuntimeException | Error caught) { failure = caught; }
            synchronized (this) { value = observed; actionFailure = failure; returned = true; }
            finish();
        }
        void dispatched(Throwable failure) {
            synchronized (this) { dispatched = true; dispatchFailure = failure; }
            finish();
        }
        void finish() {
            T output; Throwable failure;
            synchronized (this) {
                if (completed || !dispatched) return;
                // A failed dispatch may never invoke its callback. Expose failure but retain all
                // gateway reservations/quarantine; late callbacks remain fenced and cannot replay.
                if (!returned && dispatchFailure == null) return;
                completed = true; output = value; failure = dispatchFailure == null ? actionFailure : dispatchFailure;
            }
            if (failure != null) result.completeExceptionally(failure);
            else {
                try {
                    if (result.complete(output)) published.accept(output);
                    else quarantine(); // A withdrawn response cannot silently lose its new owner.
                } catch (RuntimeException | Error failed) { quarantine(); result.completeExceptionally(failed); }
            }
        }
    }
}
