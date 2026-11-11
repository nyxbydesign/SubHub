package com.subhub.app.runtime;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Immutable DEVICE service capability; only the common owner factory constructs one. */
public final class DeviceRuntimeEffects {
    private final RuntimeExecution<?> runtime;
    private final DeviceContinuationScope mutations;
    private final RuntimeLease.Identity owner;
    interface DurationObserver { void observed(DeviceComponent component, long wallMillis, long elapsedRealtimeMillis); }
    private final AtomicReference<DurationObserver> durationObserver = new AtomicReference<>();
    void bindDurationObserver(DurationObserver observer) {
        if (!durationObserver.compareAndSet(null, Objects.requireNonNull(observer)))
            throw new IllegalStateException("DEVICE duration source already bound");
    }
    private final Runnable startupReturned;
    private final Consumer<ActiveRuntimeCoordinator.Result> readinessAcknowledged;

    DeviceRuntimeEffects(ActiveRuntimeCoordinator coordinator, RuntimeExecution<?> runtime, RuntimeLease lease) {
        this(coordinator, runtime, lease, () -> { }, ignored -> { });
    }
    /**
     * Concrete factory observers enqueue exact-assembly work on the owner worker only. They do not
     * acknowledge activation themselves or run journal IO on the service/main callback thread.
     * Dispatch uncertainty must fail closed; there is no polling or timeout success substitute.
     */
    DeviceRuntimeEffects(ActiveRuntimeCoordinator coordinator, RuntimeExecution<?> runtime, RuntimeLease lease,
            Runnable startupReturned, Consumer<ActiveRuntimeCoordinator.Result> readinessAcknowledged) {
        this.runtime = Objects.requireNonNull(runtime); owner = Objects.requireNonNull(lease).identity;
        this.startupReturned = Objects.requireNonNull(startupReturned);
        this.readinessAcknowledged = Objects.requireNonNull(readinessAcknowledged);
        mutations = new DeviceContinuationScope(coordinator, runtime, lease);
    }
    private void startupActuallyReturned() {
        try { startupReturned.run(); }
        catch (RuntimeException | Error unavailable) { runtime.requestStop(); throw unavailable; }
    }
    public RuntimeLease.Identity owner() { return owner; }
    long appliedConfigurationRevision() { return runtime.appliedConfigurationRevision(); }
    /** Owner worker only, before any main service attachment or native construction. */
    public DeviceComponent component(String name) { return new DeviceComponent(runtime.registerComponent(name)); }
    DeviceContinuationScope.Borrow borrowMutation(DeviceContinuationScope.Mutation mutation) {
        return mutations.admit(mutation);
    }

    DeviceContinuationScope.Borrow borrowManagedMutation(RuntimeLease.Identity expected,
            DeviceContinuationScope.Mutation mutation, boolean startup) {
        return mutations.admitManaged(expected, mutation, startup);
    }

    public final class DeviceComponent {
        private final RuntimeExecution<?>.Component registration;
        private final AtomicInteger nativeWork = new AtomicInteger();
        private volatile Cleanup cleanup;
        private DeviceComponent(RuntimeExecution<?>.Component registration) { this.registration = registration; }
        public RuntimeLease.Identity owner() { return owner; }
        public boolean startup(Runnable action) {
            Objects.requireNonNull(action);
            AtomicBoolean entered = new AtomicBoolean();
            try { return mutations.startup(() -> { entered.set(true); action.run(); }); }
            finally { if (entered.get()) startupActuallyReturned(); }
        }
        public boolean normal(Runnable action) { return mutations.normal(action); }
        /** Fresh exact admission, never ambient current-owner data resolution. Busy must be retained. */
        public boolean durationChanged(long wallMillis, long elapsedRealtimeMillis) {
            return normal(() -> {
                DurationObserver observer = durationObserver.get();
                if (observer == null) throw new IllegalStateException("DEVICE duration source is not bound");
                observer.observed(this, wallMillis, elapsedRealtimeMillis);
            });
        }
        /**
         * Capture immutable config/source/frame facts under short admission at actual worker start.
         * Queued submissions, SDK callbacks and frame borrows still need their own counted owner.
         * Only retained native computation may use facts outside this gate; OS reads, accounting
         * and result publication require a new normal admission and source/revision validation.
         * Null means busy/revoked: retain/retry required work, never treat it as completed.
         */
        public <T> NativeWork<T> normalWork(Supplier<T> capture) {
            return work(capture, OwnedResourceRegistry.Effect.NORMAL);
        }
        public <T> NativeWork<T> startupWork(Supplier<T> capture) {
            return work(capture, OwnedResourceRegistry.Effect.STARTUP);
        }
        private <T> NativeWork<T> work(Supplier<T> capture, OwnedResourceRegistry.Effect phase) {
            Objects.requireNonNull(capture);
            AtomicReference<NativeWork<T>> result = new AtomicReference<>();
            Runnable admit = () -> {
                // Retain before capture: its native references cannot outlive a counted phase.
                OwnedResourceRegistry.EffectPermit permit;
                try { permit = runtime.retainNativeWork(phase); }
                catch (SecurityException fenced) { return; }
                boolean transferred = false;
                try {
                    T facts = Objects.requireNonNull(capture.get());
                    NativeWork<T> value = new NativeWork<>(facts, permit, phase);
                    nativeWork.incrementAndGet(); result.set(value); transferred = true;
                } finally { if (!transferred) permit.close(); }
            };
            if (phase == OwnedResourceRegistry.Effect.STARTUP) mutations.startup(admit);
            else mutations.normal(admit);
            return result.get();
        }
        /** Privately minted lifetime, not an inherited DEVICE mutation continuation. */
        public final class NativeWork<T> implements AutoCloseable {
            private final T facts;
            private final OwnedResourceRegistry.EffectPermit permit;
            private final AtomicBoolean returned = new AtomicBoolean();
            private final OwnedResourceRegistry.Effect phase;
            private NativeWork(T facts, OwnedResourceRegistry.EffectPermit permit, OwnedResourceRegistry.Effect phase) {
                this.facts = facts; this.permit = permit; this.phase = phase;
            }
            public T facts() {
                if (returned.get()) throw new SecurityException("Native work already returned");
                return facts;
            }
            /** Call only after the real native body returned; enqueue/cancel/timeout is not return. */
            @Override public void close() {
                if (!returned.compareAndSet(false, true)) return;
                permit.close();
                int remaining = nativeWork.decrementAndGet();
                try {
                    Cleanup current = cleanup;
                    if (remaining == 0 && current != null) current.nativeReturned();
                } finally {
                    runtime.nativeWorkReturned();
                    if (phase == OwnedResourceRegistry.Effect.STARTUP) startupActuallyReturned();
                }
            }
        }
        public void usesNoConfiguration() { registration.usesNoConfiguration(); }
        public void readyAsync(Consumer<ActiveRuntimeCoordinator.Result> completion) {
            readyAsync(completion, ignored -> { });
        }
        /** Supervisor retains the callback through the factory observer's actual return as well. */
        void readyAsync(Consumer<ActiveRuntimeCoordinator.Result> completion, Consumer<Boolean> afterReturn) {
            Objects.requireNonNull(completion);
            Objects.requireNonNull(afterReturn);
            registration.readyAsync(result -> {
                boolean returned = false;
                try {
                    completion.accept(result);
                    readinessAcknowledged.accept(result);
                    returned = true;
                } catch (RuntimeException | Error unavailable) { runtime.requestStop(); throw unavailable; }
                finally { afterReturn.accept(returned); }
            });
        }
        public void failed() { registration.failed(); }
        public void cleanupFailed() { registration.cleanupFailed(); }
        /** The CLEANUP handle is minted only when the real core invokes this exact shutdown. */
        public void installShutdown(Consumer<Cleanup> closer) {
            Objects.requireNonNull(closer);
            registration.installShutdown(afterActualDrain -> {
                Cleanup value = new Cleanup(afterActualDrain); cleanup = value; closer.accept(value);
            });
        }
        public final class Cleanup {
            private final Runnable afterActualDrain;
            private int inFlight;
            private boolean drainRequested, failed, acknowledged;
            private Runnable nativeReturnedObserver;
            private boolean nativeObserverRegistered, nativeObserverDispatched;
            private Cleanup(Runnable afterActualDrain) { this.afterActualDrain = afterActualDrain; }
            /**
             * One actual-return observer, invoked without owner locks on the last return thread.
             * Dispatch disposal onto its bound executor and retain async cleanup until it returns.
             * This observes only native tokens, not queued work, SDK callbacks, frames or windows.
             */
            public void afterNativeWorkReturned(Runnable observer) {
                Objects.requireNonNull(observer);
                synchronized (this) {
                    if (nativeObserverRegistered || drainRequested || failed || acknowledged)
                        throw new SecurityException("Native return observer already retired");
                    nativeObserverRegistered = true; nativeReturnedObserver = observer;
                }
                nativeReturned();
            }
            private void nativeReturned() {
                Runnable observer = null, receipt;
                synchronized (this) {
                    if (nativeWork.get() != 0) return;
                    if (nativeObserverRegistered && !nativeObserverDispatched && !failed && !acknowledged) {
                        nativeObserverDispatched = true; observer = nativeReturnedObserver;
                        // Count before exposing callback so a concurrent drained() cannot pass it.
                        inFlight++;
                    }
                    receipt = takeReceipt();
                }
                if (observer != null) {
                    try { mutations.cleanup(observer); }
                    catch (RuntimeException | Error uncertain) {
                        synchronized (this) { failed = true; }
                        registration.cleanupFailed(); throw uncertain;
                    } finally {
                        synchronized (this) { inFlight--; receipt = takeReceipt(); }
                        if (receipt != null) {
                            receipt.run();
                        }
                        runtime.nativeWorkReturned();
                    }
                } else if (receipt != null) {
                    receipt.run();
                }
            }
            /** Actual synchronous cleanup body, counted through return. No START/rearm authority. */
            public void run(Runnable action) {
                Objects.requireNonNull(action);
                synchronized (this) {
                    if (drainRequested || failed || acknowledged) {
                        throw new SecurityException("DEVICE cleanup retired");
                    }
                    inFlight++;
                }
                try { mutations.cleanup(action); }
                catch (RuntimeException | Error uncertain) {
                    synchronized (this) { failed = true; }
                    registration.cleanupFailed(); throw uncertain;
                } finally {
                    Runnable receipt;
                    synchronized (this) { inFlight--; receipt = takeReceipt(); }
                    if (receipt != null) receipt.run();
                    // A raw CLEANUP permit just returned. Re-query drain off main, including the
                    // race where component closure was observed before this permit was released.
                    runtime.requestStop();
                }
            }
            /**
             * Caller must first prove its actual windows, native/model workers, queued deliveries
             * and borrowers closed. This method additionally waits admitted cleanup bodies and
             * permanently rejects later ones. It cannot infer those external resources are gone.
             */
            public void drained() {
                Runnable receipt;
                synchronized (this) { drainRequested = true; receipt = takeReceipt(); }
                if (receipt != null) receipt.run();
            }
            private Runnable takeReceipt() {
                if (!drainRequested || failed || acknowledged || inFlight != 0 || nativeWork.get() != 0
                        || (nativeObserverRegistered && !nativeObserverDispatched)) return null;
                acknowledged = true; return afterActualDrain;
            }
        }
    }
}
