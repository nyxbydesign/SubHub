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
    private enum Mode { RESERVED, STARTING, ACTIVE, SEALED }
    private final RuntimeMutex mutex;
    private final RuntimeLease lease;
    private final String epoch = UUID.randomUUID().toString();
    private final Runnable requiredComponentClosed;
    private final Set<Registration> resources = new HashSet<>();
    private final Map<String, Registration> components = new HashMap<>();
    private final Set<EffectPermit> permits = new HashSet<>();
    private Mode mode = Mode.RESERVED;
    private DrainReceipt receipt;

    OwnedResourceRegistry(RuntimeMutex mutex, RuntimeLease lease, Runnable requiredComponentClosed) {
        this.mutex = mutex; this.lease = lease; this.requiredComponentClosed = requiredComponentClosed;
    }
    void starting() { mutex.requireHeld(); if (mode != Mode.RESERVED) throw new IllegalStateException("Start already sealed"); mode = Mode.STARTING; }
    void activate() { mutex.requireHeld(); if (mode != Mode.STARTING) throw new IllegalStateException("Start already sealed"); mode = Mode.ACTIVE; }
    void seal() { mutex.requireHeld(); mode = Mode.SEALED; }

    Registration register(RuntimeLease requested, String name, boolean component) {
        mutex.requireHeld(); RuntimeOrigin.key(name);
        if (requested != lease || mode == Mode.RESERVED || mode == Mode.SEALED || receipt != null)
            throw new SecurityException("Runtime no longer admits work");
        if (component && components.containsKey(name)) throw new IllegalStateException("Component already registered");
        Registration value = new Registration(this, name, component);
        resources.add(value); if (component) components.put(name, value);
        return value;
    }
    boolean ownsComponent(Registration registration) {
        mutex.requireHeld();
        return registration != null && registration.registry == this && registration.component
                && components.get(registration.name) == registration && resources.contains(registration);
    }
    boolean hasComponent(String name) { mutex.requireHeld(); return components.containsKey(name); }

    public EffectPermit acquireEffectPermit(RuntimeLease requested, Effect effect) {
        Objects.requireNonNull(effect);
        return mutex.runtime(() -> {
            boolean allowed = effect == Effect.STARTUP && mode == Mode.STARTING
                    || effect == Effect.NORMAL && mode == Mode.ACTIVE
                    || effect == Effect.CLEANUP && mode == Mode.SEALED;
            if (requested != lease || !allowed || receipt != null) throw new SecurityException("Effect admission revoked");
            EffectPermit permit = new EffectPermit(this, effect); permits.add(permit); return permit;
        });
    }

    public DrainReceipt drained(RuntimeLease requested) {
        return mutex.runtime(() -> {
            if (requested != lease || mode != Mode.SEALED || !resources.isEmpty() || !permits.isEmpty()) return null;
            if (receipt == null) receipt = new DrainReceipt(this, lease, epoch);
            return receipt;
        });
    }
    boolean accepts(DrainReceipt value) {
        mutex.requireHeld();
        return value != null && value == receipt && value.registry == this && value.lease == lease
                && value.epoch.equals(epoch) && mode == Mode.SEALED && resources.isEmpty() && permits.isEmpty();
    }
    boolean isSealed() { mutex.requireHeld(); return mode == Mode.SEALED; }
    boolean startupEffectsDrained() { mutex.requireHeld(); return permits.isEmpty(); }

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
            registry.mutex.runtime(() -> {
                if (!closed) { closed = true; registry.permits.remove(this); }
                return null;
            });
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
