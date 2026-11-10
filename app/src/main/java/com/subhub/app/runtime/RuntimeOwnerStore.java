package com.subhub.app.runtime;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Lock-checked persistence port. No in-memory implementation is a durable Android store. */
public final class RuntimeOwnerStore {
    public enum Phase { IDLE, RESERVED, STARTING, ACTIVE, STOPPING }
    public enum Outcome { COMMITTED, NOT_COMMITTED, CONFLICT, UNCERTAIN }

    public interface Backend {
        /** Throw on corrupt, missing-after-initialization or unavailable data; never manufacture IDLE. */
        Record read();
        /** One transaction compares the complete expected record and acknowledges exactly next. */
        Outcome compareAndSet(Record expected, Record next);
    }

    public static final class Record {
        public final long revision, savedConfigurationRevision, appliedConfigurationRevision;
        public final Phase phase;
        public final RuntimeLease.Identity owner;
        public final Set<String> requiredComponents, readyComponents;
        public Record(long revision, Phase phase, RuntimeLease.Identity owner, long savedConfigurationRevision,
                long appliedConfigurationRevision, Set<String> requiredComponents, Set<String> readyComponents) {
            if (revision < 0 || savedConfigurationRevision < 0 || appliedConfigurationRevision < 0
                    || appliedConfigurationRevision > savedConfigurationRevision)
                throw new IllegalArgumentException("Invalid owner revision");
            this.phase = Objects.requireNonNull(phase); this.owner = owner;
            this.requiredComponents = keys(requiredComponents); this.readyComponents = keys(readyComponents);
            if ((phase == Phase.IDLE) != (owner == null)
                    || !this.requiredComponents.containsAll(this.readyComponents)
                    || phase == Phase.IDLE && (!this.requiredComponents.isEmpty() || !this.readyComponents.isEmpty()
                            || savedConfigurationRevision != 0 || appliedConfigurationRevision != 0)
                    || phase == Phase.RESERVED && !this.readyComponents.isEmpty()
                    || phase == Phase.ACTIVE && !this.requiredComponents.equals(this.readyComponents)
                    || owner != null && (this.requiredComponents.isEmpty()
                            || appliedConfigurationRevision < owner.initialConfigurationRevision))
                throw new IllegalArgumentException("Invalid owner state");
            this.revision = revision; this.savedConfigurationRevision = savedConfigurationRevision;
            this.appliedConfigurationRevision = appliedConfigurationRevision;
        }
        public static Record idle(long revision) { return new Record(revision, Phase.IDLE, null, 0, 0, Set.of(), Set.of()); }
        Record next(Phase phase, long saved, long applied, Set<String> ready) {
            return new Record(Math.addExact(revision, 1), phase, owner, saved, applied, requiredComponents, ready);
        }
        static Set<String> keys(Set<String> source) {
            if (source == null || source.size() > 64) throw new IllegalArgumentException("Invalid component set");
            Set<String> result = new LinkedHashSet<>();
            for (String value : source) result.add(RuntimeOrigin.key(value));
            return Collections.unmodifiableSet(result);
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Record)) return false;
            Record value = (Record) other;
            return revision == value.revision && phase == value.phase && Objects.equals(owner, value.owner)
                    && savedConfigurationRevision == value.savedConfigurationRevision
                    && appliedConfigurationRevision == value.appliedConfigurationRevision
                    && requiredComponents.equals(value.requiredComponents) && readyComponents.equals(value.readyComponents);
        }
        @Override public int hashCode() { return Objects.hash(revision, phase, owner, savedConfigurationRevision,
                appliedConfigurationRevision, requiredComponents, readyComponents); }
    }

    final RuntimeMutex mutex;
    private final Backend backend;
    public RuntimeOwnerStore(RuntimeMutex mutex, Backend backend) {
        this.mutex = Objects.requireNonNull(mutex); this.backend = Objects.requireNonNull(backend);
    }
    Record read() {
        return mutex.ownerTransaction(() -> Objects.requireNonNull(backend.read(), "Missing owner record"));
    }
    Outcome compareAndSet(Record expected, Record next) {
        if (next.revision != Math.addExact(expected.revision, 1))
            throw new IllegalArgumentException("Owner CAS must advance exactly one revision");
        return mutex.ownerTransaction(() -> Objects.requireNonNull(backend.compareAndSet(expected, next)));
    }
}
