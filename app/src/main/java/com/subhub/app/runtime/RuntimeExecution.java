package com.subhub.app.runtime;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One immutable runtime capability, serialized effects and acknowledged configuration.
 * This Android-independent execution core is used by RuntimeEnvironment; it never resolves a
 * current owner or opens a device store. Component shutdown callbacks must acknowledge actual closure, never a timeout or post.
 */
public class RuntimeExecution<C> {
    public interface Shutdown {
        /** The callback may run only after all owned windows, animations, queues and workers closed. */
        void close(Runnable afterDrained);
    }
    public interface ConfigurationReceiver<C> {
        /** Synchronous module update on its bound executor; returning means the snapshot is applied. */
        void apply(C acknowledgedMainConfiguration);
    }
    private final ConfigurationReceiver<C> noConfiguration = ignored -> { };

    private final ActiveRuntimeCoordinator coordinator;
    private final RuntimeLease lease;
    private final OwnedResourceRegistry registry;
    private final Executor lifecycle;
    private final CopyOnWriteArrayList<Component> components = new CopyOnWriteArrayList<>();
    private final AtomicBoolean stopping = new AtomicBoolean();
    // Serializes actual synchronous effects and configuration updates, not Android permission work.
    // Never wait for this gate from a main-thread callback or stop. The current effect's finally
    // path performs deferred closure. A contended caller must retain/retry its work explicitly.
    private final ReentrantLock effectGate = new ReentrantLock();
    private final ThreadLocal<Integer> effectDepth = ThreadLocal.withInitial(() -> 0);
    private int effectsRunning;
    private volatile boolean stopSealed;
    private volatile C mainConfiguration;
    private volatile long appliedConfigurationRevision;
    private volatile boolean cleanupUnacknowledged;
    private final AtomicBoolean stopQueued = new AtomicBoolean(), completionQueued = new AtomicBoolean();
    private final AtomicBoolean completionRequested = new AtomicBoolean();
    private volatile boolean lifecycleDispatchFailed;

    RuntimeExecution(ActiveRuntimeCoordinator coordinator, RuntimeLease lease,
            C acknowledgedMainConfiguration) {
        this(coordinator, lease, acknowledgedMainConfiguration, Runnable::run);
    }

    /** Android callers supply their serial owner worker; the synchronous overload is for pure tests. */
    RuntimeExecution(ActiveRuntimeCoordinator coordinator, RuntimeLease lease,
            C acknowledgedMainConfiguration, Executor lifecycle) {
        this.coordinator = Objects.requireNonNull(coordinator);
        this.lease = Objects.requireNonNull(lease);
        registry = coordinator.resources(lease); // Exact current-process capability, not persisted evidence.
        mainConfiguration = Objects.requireNonNull(acknowledgedMainConfiguration);
        appliedConfigurationRevision = lease.identity.initialConfigurationRevision;
        this.lifecycle = Objects.requireNonNull(lifecycle);
    }

    public RuntimeLease.Identity identity() { return lease.identity; }
    protected final boolean insideEffect() { return effectDepth.get() > 0; }
    public boolean cleanupNeedsAttention() { return cleanupUnacknowledged; }
    public boolean lifecycleNeedsAttention() { return lifecycleDispatchFailed; }
    public C configuration() { return mainConfiguration; }
    public long appliedConfigurationRevision() { return appliedConfigurationRevision; }
    protected final void requireComponent(Component component) {
        if (component == null || component.execution() != this)
            throw new SecurityException("Component belongs to another runtime execution");
    }

    /** Owner worker only: register BEFORE dispatching OS creation, and install cleanup on failure. */
    public Component registerComponent(String name) {
        if (stopping.get()) throw new SecurityException("Runtime component admission revoked");
        Component component = new Component(coordinator.registerComponent(lease, name));
        components.add(component);
        // Stop can race list publication. The already-counted registration prevents early IDLE.
        dispatchClosuresIfQuiescent();
        return component;
    }

    /**
     * Keeps the permit through the actual synchronous effect/write. False means nothing ran
     * (busy or revoked). Mandatory accounting must stay queued until admitted, not be discarded.
     * Async work requires its own registration and terminal acknowledgment, not this wrapper.
     */
    public boolean runEffect(Runnable effect) {
        return run(OwnedResourceRegistry.Effect.NORMAL, effect);
    }
    public boolean runStartupEffect(Runnable effect) {
        return run(OwnedResourceRegistry.Effect.STARTUP, effect);
    }
    private boolean run(OwnedResourceRegistry.Effect kind, Runnable effect) {
        Objects.requireNonNull(effect);
        if (!effectGate.tryLock()) return false;
        boolean acquired = false;
        try {
            if (stopping.get()) return false;
            OwnedResourceRegistry.EffectPermit permit;
            try { permit = registry.acquireEffectPermit(lease, kind); }
            catch (SecurityException revoked) { return false; }
            acquired = true;
            effectsRunning++;
            try (OwnedResourceRegistry.EffectPermit admitted = permit) {
                Objects.requireNonNull(admitted);
                effectDepth.set(effectDepth.get() + 1);
                try { effect.run(); return true; }
                catch (RuntimeException | Error failed) { requestStop(); throw failed; }
                finally { effectDepth.set(effectDepth.get() - 1); }
            } finally { effectsRunning--; }
        } finally {
            effectGate.unlock();
            dispatchClosuresIfQuiescent();
            if (acquired) tryCompleteStop();
        }
    }

    /**
     * Apply only an acknowledged snapshot of this generation, on the components' bound executor.
     * The effect gate drains earlier callbacks first. Every module updates its existing data source
     * before the owner journal acknowledges the applied revision. Partial application stops the
     * session; a newer saved draft is never falsely displayed as fully enforced.
     */
    public ActiveRuntimeCoordinator.Result applyConfiguration(String generation, long revision,
            C acknowledgedMainConfiguration) {
        Objects.requireNonNull(acknowledgedMainConfiguration);
        // tryLock is reentrant. An effect/receiver cannot nest a newer revision inside an older
        // module loop and let the outer loop overwrite another module after nested success.
        if (insideEffect()) return ActiveRuntimeCoordinator.Result.NOT_READY;
        if (!lease.identity.profileGeneration.equals(generation))
            return ActiveRuntimeCoordinator.Result.CONFIGURATION_CHANGED;
        // Do not block an Activity/service main thread behind worker work. The integration keeps
        // the acknowledged saved snapshot pending and retries this exact revision on NOT_READY.
        if (!effectGate.tryLock()) return ActiveRuntimeCoordinator.Result.NOT_READY;
        try {
            AtomicReference<ActiveRuntimeCoordinator.Result> result = new AtomicReference<>(
                    ActiveRuntimeCoordinator.Result.STALE_LEASE);
            boolean admitted = runEffect(() -> {
                long expected = appliedConfigurationRevision;
                if (revision < expected) {
                    result.set(ActiveRuntimeCoordinator.Result.CONFIGURATION_CHANGED);
                    return;
                }
                if (revision == expected) { result.set(ActiveRuntimeCoordinator.Result.OK); return; }
                ActiveRuntimeCoordinator.Result offered = coordinator.offerConfiguration(lease, expected, revision);
                result.set(offered);
                if (offered != ActiveRuntimeCoordinator.Result.OK) {
                    if (offered != ActiveRuntimeCoordinator.Result.CONFIGURATION_CHANGED) requestStop();
                    return;
                }
                for (Component component : components) {
                    ConfigurationReceiver<C> receiver = component.configurationReceiver.get();
                    if (receiver == null) throw new IllegalStateException("Active component has no configuration binding");
                    receiver.apply(acknowledgedMainConfiguration);
                    if (stopping.get()) { result.set(ActiveRuntimeCoordinator.Result.STALE_LEASE); return; }
                }
                ActiveRuntimeCoordinator.Result applied = coordinator.configurationApplied(lease, expected, revision);
                result.set(applied);
                if (applied == ActiveRuntimeCoordinator.Result.OK) {
                    mainConfiguration = acknowledgedMainConfiguration;
                    appliedConfigurationRevision = revision;
                } else requestStop();
            });
            return admitted ? result.get() : ActiveRuntimeCoordinator.Result.STALE_LEASE;
        } finally { effectGate.unlock(); dispatchClosuresIfQuiescent(); }
    }

    public ActiveRuntimeCoordinator.Result requestStop() {
        stopping.set(true);
        registry.fence(lease); // Also fences separately admitted owner writers without journal IO.
        // The local gate closes immediately, including on a main-thread effect exception. No
        // resource can reopen or be released while the serial worker awaits its durable stop.
        AtomicReference<ActiveRuntimeCoordinator.Result> result = new AtomicReference<>(
                ActiveRuntimeCoordinator.Result.NOT_READY);
        if (!stopQueued.compareAndSet(false, true)) return result.get();
        try {
            lifecycle.execute(() -> {
                try {
                    result.set(coordinator.requestStop(lease));
                    stopSealed = true; // A failed STOPPING write still sealed the registry first.
                    dispatchClosuresIfQuiescent();
                    tryCompleteStop();
                } finally { stopQueued.set(false); }
            });
        } catch (RuntimeException rejected) {
            stopQueued.set(false); lifecycleDispatchFailed = true;
        }
        // NOT_READY is queued, not success. The physical owner remains occupied until completeStop.
        return result.get();
    }

    private void dispatchClosuresIfQuiescent() {
        if (!stopping.get() || !stopSealed) return;
        if (!effectGate.tryLock()) return;
        try {
            // A stop called from an admitted effect is reentrant; its finally path performs drain.
            if (effectsRunning != 0) return;
        } finally { effectGate.unlock(); }
        // No gate is held across externally supplied cleanup; new effects are already refused.
        for (Component component : components) component.requestClose();
    }

    private void tryCompleteStop() {
        if (!stopping.get()) return;
        completionRequested.set(true);
        scheduleCompletion();
    }
    private void scheduleCompletion() {
        if (!completionQueued.compareAndSet(false, true)) return;
        try {
            lifecycle.execute(() -> {
                completionRequested.set(false);
                try {
                    // Even the drain query takes RuntimeMutex, which may currently span journal
                    // fsync. Neither it nor the completion CAS may run on an Android callback.
                    OwnedResourceRegistry.DrainReceipt receipt = registry.drained(lease);
                    if (receipt != null) coordinator.completeStop(lease, receipt);
                } finally {
                    completionQueued.set(false);
                    // Do not lose an effect/closure completion racing the worker's last query.
                    if (completionRequested.get()) scheduleCompletion();
                }
            });
        } catch (RuntimeException rejected) {
            completionQueued.set(false); lifecycleDispatchFailed = true;
        }
        // Failure remains occupied in the coordinator. No synthetic retry or time-based release.
    }

    public final class Component {
        private final OwnedResourceRegistry.Registration registration;
        private final AtomicReference<Shutdown> shutdown = new AtomicReference<>();
        private final AtomicReference<ConfigurationReceiver<C>> configurationReceiver = new AtomicReference<>();
        private final AtomicBoolean closeDispatched = new AtomicBoolean(), acknowledged = new AtomicBoolean();
        private final AtomicBoolean registrationCloseQueued = new AtomicBoolean();
        private volatile boolean registrationClosed;
        private Component(OwnedResourceRegistry.Registration registration) { this.registration = registration; }
        private RuntimeExecution<C> execution() { return RuntimeExecution.this; }

        public void installShutdown(Shutdown closer) {
            if (!shutdown.compareAndSet(null, Objects.requireNonNull(closer)))
                throw new IllegalStateException("Component cleanup already installed");
            dispatchClosuresIfQuiescent();
        }
        public void installConfigurationReceiver(ConfigurationReceiver<C> receiver) {
            if (!configurationReceiver.compareAndSet(null, Objects.requireNonNull(receiver)))
                throw new IllegalStateException("Component configuration already bound");
        }
        /** Capture/host infrastructure still explicitly declares that it has no configurable data. */
        public void usesNoConfiguration() {
            if (!configurationReceiver.compareAndSet(null, noConfiguration))
                throw new IllegalStateException("Component configuration already bound");
        }
        public ActiveRuntimeCoordinator.Result ready() {
            if (shutdown.get() == null) throw new IllegalStateException("Unowned component cleanup");
            if (configurationReceiver.get() == null) throw new IllegalStateException("Unbound component configuration");
            ActiveRuntimeCoordinator.Result result = coordinator.componentReady(lease, registration);
            if (result != ActiveRuntimeCoordinator.Result.OK) requestStop();
            return result;
        }
        /** Posts journal readiness off the Android component thread; not an immediate receipt. */
        public void readyAsync(java.util.function.Consumer<ActiveRuntimeCoordinator.Result> completion) {
            Objects.requireNonNull(completion);
            try { lifecycle.execute(() -> {
                try { completion.accept(ready()); }
                catch (RuntimeException failed) { lifecycleDispatchFailed = true; requestStop(); }
            }); }
            catch (RuntimeException rejected) { lifecycleDispatchFailed = true; requestStop(); }
        }
        public void failed() { requestStop(); }
        /** A queued cleanup failed after close() returned; this is not a drain acknowledgment. */
        public void cleanupFailed() { cleanupUnacknowledged = true; requestStop(); }
        private void requestClose() {
            if (acknowledged.get()) { closeRegistration(); return; }
            Shutdown closer = shutdown.get();
            if (closer == null || !closeDispatched.compareAndSet(false, true)) return;
            try {
                closer.close(() -> {
                    acknowledged.set(true);
                    closeRegistration();
                });
            } catch (RuntimeException failed) {
                // Cleanup may be partially complete. Retain registration and occupancy for recovery.
                cleanupUnacknowledged = true;
            }
        }
        private void closeRegistration() {
            if (registrationClosed || !registrationCloseQueued.compareAndSet(false, true)) return;
            try {
                lifecycle.execute(() -> {
                    try { registration.close(); registrationClosed = true; }
                    finally { registrationCloseQueued.set(false); }
                    tryCompleteStop();
                });
            } catch (RuntimeException rejected) {
                registrationCloseQueued.set(false); lifecycleDispatchFailed = true;
                // The already observed OS closure can retry its registration ACK without closing
                // a window twice. Occupancy remains held while this worker dispatch is unavailable.
            }
        }
    }
}
