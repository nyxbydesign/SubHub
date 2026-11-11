package com.subhub.app.runtime;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Counted work and effects for one immutable lease. A permit must span the actual OS/write
 * effect and acknowledgment; closing it after merely queueing unregistered work is invalid.
 */
public final class OwnedResourceRegistry {
    public enum Effect { STARTUP, NORMAL, CLEANUP }
    private enum Mode { RESERVED, STARTING, ACTIVATING, ACTIVE, SEALED }
    private final RuntimeMutex mutex;
    private final RuntimeLease lease;
    private final String epoch = UUID.randomUUID().toString();
    private final Runnable requiredComponentClosed;
    private final Set<Registration> resources = new HashSet<>();
    private final Map<String, Registration> components = new HashMap<>();
    private final Set<EffectPermit> permits = new HashSet<>();
    private final Set<PermissionObligation> permissions = new HashSet<>();
    // Lock order: RuntimeMutex -> permitLock. Never enter RuntimeMutex, persistence, callbacks,
    // or Android from permitLock. Window callbacks can acquire/release without waiting for fsync.
    private final Object permitLock = new Object();
    private volatile Mode mode = Mode.RESERVED;
    private DrainReceipt receipt;
    private RuntimeFenceObservation firstFence;

    OwnedResourceRegistry(RuntimeMutex mutex, RuntimeLease lease, Runnable requiredComponentClosed) {
        this.mutex = mutex; this.lease = lease; this.requiredComponentClosed = requiredComponentClosed;
    }
    /** Coordinator-only reservation; not general RESERVED work/effect authority. */
    PermissionObligation reservePermission(RuntimeLease requested, RuntimeAdmissionAuthority.Witness witness) {
        mutex.requireHeld();
        synchronized (permitLock) {
            if (requested != lease || mode != Mode.RESERVED || receipt != null || !witness.permitsLaunch())
                throw new SecurityException("Permission admission revoked");
            PermissionObligation obligation = new PermissionObligation(this, witness);
            permissions.add(obligation); return obligation;
        }
    }
    boolean permissionsReturned() {
        mutex.requireHeld(); synchronized (permitLock) { return permissions.isEmpty(); }
    }
    boolean starting() {
        mutex.requireHeld(); synchronized (permitLock) {
            if (mode != Mode.RESERVED || !permissions.isEmpty()) return false;
            mode = Mode.STARTING; return true;
        }
    }
    /** Close startup admission BEFORE the ACTIVE journal CAS; a definite failed CAS may retry. */
    boolean prepareActivation() {
        mutex.requireHeld(); synchronized (permitLock) {
            if ((mode != Mode.STARTING && mode != Mode.ACTIVATING) || !permits.isEmpty()) return false;
            mode = Mode.ACTIVATING; return true;
        }
    }
    boolean activate() {
        mutex.requireHeld(); synchronized (permitLock) {
            if (mode != Mode.ACTIVATING || !permits.isEmpty()) return false;
            mode = Mode.ACTIVE; return true;
        }
    }
    void seal() {
        mutex.requireHeld();
        String zone = mode == Mode.SEALED ? null : RuntimeFenceObservation.captureZoneOrUnavailable();
        synchronized (permitLock) { sealNowLocked(zone); }
    }
    private void sealNowLocked(String normalizedZone) {
        if (mode != Mode.SEALED) {
            mode = Mode.SEALED; // A clock/allocation failure cannot prevent the actual fence.
            firstFence = RuntimeFenceObservation.observeNowOrUnavailable(normalizedZone);
        }
    }
    void seal(RuntimeFenceObservation observed) {
        mutex.requireHeld(); synchronized (permitLock) { sealLocked(observed); }
    }
    private void sealLocked(RuntimeFenceObservation observed) {
        if (mode != Mode.SEALED) {
            mode = Mode.SEALED;
            firstFence = observed; // Missing/invalid evidence is never replaced by later cleanup time.
        }
    }
    /** Exact lease observation only; no RuntimeMutex, IO, resampling or effect authority. */
    public RuntimeFenceObservation firstFence(RuntimeLease requested) {
        synchronized (permitLock) {
            if (requested != lease) throw new SecurityException("Different runtime capability");
            if (mode != Mode.SEALED || firstFence == null || firstFence.at <= 0)
                throw new IllegalStateException("First runtime fence clock is unavailable");
            return firstFence;
        }
    }

    /** Immediate exact-capability fence, even while the owner worker is awaiting journal fsync. */
    public void fence(RuntimeLease requested) {
        if (requested != lease) throw new SecurityException("Different runtime capability");
        String zone = mode == Mode.SEALED ? null : RuntimeFenceObservation.captureZoneOrUnavailable();
        synchronized (permitLock) { sealNowLocked(zone); }
    }
    void fence(RuntimeLease requested, RuntimeFenceObservation observed) {
        synchronized (permitLock) {
            if (requested != lease) throw new SecurityException("Different runtime capability");
            sealLocked(observed);
        }
    }

    Registration register(RuntimeLease requested, String name, boolean component) {
        mutex.requireHeld(); RuntimeOrigin.key(name);
        synchronized (permitLock) {
            if (requested != lease || mode == Mode.RESERVED || mode == Mode.ACTIVATING || mode == Mode.SEALED || receipt != null)
                throw new SecurityException("Runtime no longer admits work");
            if (component && components.containsKey(name)) throw new IllegalStateException("Component already registered");
            Registration value = new Registration(this, name, component);
            resources.add(value); if (component) components.put(name, value);
            return value;
        }
    }
    boolean ownsComponent(Registration registration) {
        mutex.requireHeld();
        return registration != null && registration.registry == this && registration.component
                && components.get(registration.name) == registration && resources.contains(registration);
    }
    boolean hasComponent(String name) { mutex.requireHeld(); return components.containsKey(name); }

    public EffectPermit acquireEffectPermit(RuntimeLease requested, Effect effect) {
        Objects.requireNonNull(effect);
        synchronized (permitLock) {
            boolean allowed = effect == Effect.STARTUP && mode == Mode.STARTING
                    || effect == Effect.NORMAL && mode == Mode.ACTIVE
                    || effect == Effect.CLEANUP && mode == Mode.SEALED;
            if (requested != lease || !allowed || receipt != null) throw new SecurityException("Effect admission revoked");
            EffectPermit permit = new EffectPermit(this, effect); permits.add(permit); return permit;
        }
    }

    public DrainReceipt drained(RuntimeLease requested) {
        return mutex.runtime(() -> {
            synchronized (permitLock) {
                if (requested != lease || mode != Mode.SEALED || !resources.isEmpty() || !permits.isEmpty() || !permissions.isEmpty()) return null;
                if (receipt == null) receipt = new DrainReceipt(this, lease, epoch);
                return receipt;
            }
        });
    }
    boolean accepts(DrainReceipt value) {
        mutex.requireHeld();
        synchronized (permitLock) {
            return value != null && value == receipt && value.registry == this && value.lease == lease
                    && value.epoch.equals(epoch) && mode == Mode.SEALED && resources.isEmpty() && permits.isEmpty() && permissions.isEmpty();
        }
    }
    boolean isSealed() { mutex.requireHeld(); return mode == Mode.SEALED; }

    /**
     * Exact prompt obligation, registered before its UI handle is exposed. The short permission
     * gate is separate from RuntimeMutex: launch/return never waits for journal IO. The final
     * freshness read is the launch admission linearization point. A concurrent revocation after
     * that read may not undo an admitted OS call, so both actual launch and result return remain
     * counted. Recovery uses this same set and cannot infer prompt closure from STOPPING alone.
     */
    static final class PermissionObligation {
        private final OwnedResourceRegistry registry;
        private final RuntimeAdmissionAuthority.Witness witness;
        private boolean launched, launchReturned, resultReturned, closed;
        private PermissionObligation(OwnedResourceRegistry registry, RuntimeAdmissionAuthority.Witness witness) {
            this.registry = registry; this.witness = witness;
        }
        boolean tryLaunch() {
            synchronized (registry.permitLock) {
                if (closed || launched || registry.mode != Mode.RESERVED || !witness.permitsLaunch()) return false;
                launched = true; return true;
            }
        }
        void cancelBeforeLaunch() {
            synchronized (registry.permitLock) {
                if (!launched) { closed = true; registry.permissions.remove(this); }
            }
        }
        void launchReturned() {
            synchronized (registry.permitLock) {
                if (!launched) throw new IllegalStateException("Prompt was never admitted");
                launchReturned = true; retireIfReturned();
            }
        }
        boolean resultReturned() {
            synchronized (registry.permitLock) {
                if (!launched) throw new IllegalStateException("Prompt was never admitted");
                boolean current = registry.mode == Mode.RESERVED && witness.permitsLaunch();
                resultReturned = true; retireIfReturned(); return current;
            }
        }
        private void retireIfReturned() {
            if (launchReturned && resultReturned) { closed = true; registry.permissions.remove(this); }
        }
    }

    public static final class Registration implements AutoCloseable {
        private final OwnedResourceRegistry registry;
        public final String name;
        private final boolean component;
        private boolean closed;
        private Registration(OwnedResourceRegistry registry, String name, boolean component) {
            this.registry = registry; this.name = name; this.component = component;
        }
        /** Call only after actual component/work closure. It is not a request to close resources. */
        @Override public void close() {
            registry.mutex.runtime(() -> {
                if (closed) return null;
                closed = true; registry.resources.remove(this);
                if (component) {
                    registry.components.remove(name, this);
                    if (registry.mode != Mode.SEALED) {
                        registry.seal();
                        registry.requiredComponentClosed.run();
                    }
                }
                return null;
            });
        }
    }
    public static final class EffectPermit implements AutoCloseable {
        private final OwnedResourceRegistry registry;
        public final Effect effect;
        private boolean closed;
        private EffectPermit(OwnedResourceRegistry registry, Effect effect) { this.registry = registry; this.effect = effect; }
        @Override public void close() {
            synchronized (registry.permitLock) {
                if (!closed) { closed = true; registry.permits.remove(this); }
            }
        }
    }
    /** Only this registry can issue a receipt after sealing and acknowledged closure. */
    public static final class DrainReceipt {
        private final OwnedResourceRegistry registry;
        private final RuntimeLease lease;
        private final String epoch;
        private DrainReceipt(OwnedResourceRegistry registry, RuntimeLease lease, String epoch) {
            this.registry = registry; this.lease = lease; this.epoch = epoch;
        }
    }
}
