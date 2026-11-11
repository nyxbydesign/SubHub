package com.subhub.app.runtime;

import java.util.Objects;
import java.util.function.Consumer;

/** Immutable DEVICE service capability; only the common owner factory constructs one. */
public final class DeviceRuntimeEffects {
    private final RuntimeExecution<?> runtime;
    private final DeviceContinuationScope mutations;
    private final RuntimeLease.Identity owner;

    DeviceRuntimeEffects(ActiveRuntimeCoordinator coordinator, RuntimeExecution<?> runtime, RuntimeLease lease) {
        this.runtime = Objects.requireNonNull(runtime); owner = Objects.requireNonNull(lease).identity;
        mutations = new DeviceContinuationScope(coordinator, runtime, lease);
    }
    public RuntimeLease.Identity owner() { return owner; }
    /** Owner worker only, before any main service attachment or native construction. */
    public DeviceComponent component(String name) { return new DeviceComponent(runtime.registerComponent(name)); }
    DeviceContinuationScope.Borrow borrowMutation(DeviceContinuationScope.Mutation mutation) {
        return mutations.admit(mutation);
    }

    public final class DeviceComponent {
        private final RuntimeExecution<?>.Component registration;
        private DeviceComponent(RuntimeExecution<?>.Component registration) { this.registration = registration; }
        public RuntimeLease.Identity owner() { return owner; }
        public boolean startup(Runnable action) { return mutations.startup(action); }
        public boolean normal(Runnable action) { return mutations.normal(action); }
        public void usesNoConfiguration() { registration.usesNoConfiguration(); }
        public void readyAsync(Consumer<ActiveRuntimeCoordinator.Result> completion) { registration.readyAsync(completion); }
        public void failed() { registration.failed(); }
        public void cleanupFailed() { registration.cleanupFailed(); }
        /** The CLEANUP handle is minted only when the real core invokes this exact shutdown. */
        public void installShutdown(Consumer<Cleanup> closer) {
            Objects.requireNonNull(closer);
            registration.installShutdown(afterActualDrain -> closer.accept(new Cleanup(afterActualDrain)));
        }
        public final class Cleanup {
            private final Runnable afterActualDrain;
            private int inFlight;
            private boolean drainRequested, failed, acknowledged;
            private Cleanup(Runnable afterActualDrain) { this.afterActualDrain = afterActualDrain; }
            /** Actual synchronous cleanup body, counted through return. No START/rearm authority. */
            public void run(Runnable action) {
                Objects.requireNonNull(action);
                synchronized (this) {
                    if (drainRequested || failed || acknowledged) throw new SecurityException("DEVICE cleanup retired");
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
                if (!drainRequested || failed || acknowledged || inFlight != 0) return null;
                acknowledged = true; return afterActualDrain;
            }
        }
    }
}
