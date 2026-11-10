package com.subhub.app.runtime;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import com.subhub.app.runtime.RuntimeOwnerStore.Outcome;
import com.subhub.app.runtime.RuntimeOwnerStore.Phase;
import com.subhub.app.runtime.RuntimeOwnerStore.Record;

/**
 * Inactive foundation: no application singleton, Android services, or production persistence.
 * Every callback passed here is a pure, already-observed fact check under RuntimeMutex; it
 * must not query Binder or acquire policy/remote/editor locks. Android integration is separate.
 */
public final class ActiveRuntimeCoordinator {
    public enum Occupancy { CLEAR, OCCUPIED, UNKNOWN }
    public enum Result {
        OK, STARTUP_RECONCILIATION_REQUIRED, OCCUPIED, AUTHORITY_CHANGED, STALE_LEASE,
        WRONG_PHASE, NOT_READY, PERSISTENCE_FAILED, QUARANTINED, CONFIGURATION_CHANGED
    }
    public interface PhysicalOccupancy { Occupancy observe(); }
    public interface StartReadiness { boolean current(RuntimeLease.Identity owner); }
    public interface RemoteStopAuthority {
        boolean current(RuntimeOrigin.RemoteProof stopCommand, RuntimeLease.Identity expectedOwner);
    }
    public static final class Admission {
        public final Result result;
        public final RuntimeLease lease;
        public final boolean repeatedRemoteCommand;
        private Admission(Result result, RuntimeLease lease, boolean repeated) {
            this.result = result; this.lease = lease; repeatedRemoteCommand = repeated;
        }
    }
    public static final class Observation {
        public final Record persisted;
        public final boolean startupReconciled, quarantined, effectsSealed;
        private Observation(Record persisted, boolean startupReconciled, boolean quarantined, boolean sealed) {
            this.persisted = persisted; this.startupReconciled = startupReconciled;
            this.quarantined = quarantined; effectsSealed = sealed;
        }
        /** Logical revocation takes effect even if the durable STOPPING write was refused. */
        public Phase effectivePhase() {
            if (persisted == null) return null;
            return effectsSealed && persisted.owner != null ? Phase.STOPPING : persisted.phase;
        }
        public boolean configurationPending() {
            return persisted != null && persisted.savedConfigurationRevision != persisted.appliedConfigurationRevision;
        }
    }

    private final RuntimeMutex mutex;
    private final RuntimeOwnerStore store;
    private final RuntimeAdmissionAuthority authority;
    private final PhysicalOccupancy physical;
    private final String process;
    private Record record, uncertainBefore, uncertainAfter;
    private boolean startupReconciled, quarantined;
    private RuntimeLease lease;
    private RuntimeAdmissionAuthority.Witness witness;
    private OwnedResourceRegistry resources;

    public ActiveRuntimeCoordinator(RuntimeOwnerStore store, RuntimeAdmissionAuthority authority,
            PhysicalOccupancy physical, String process) {
        this.store = Objects.requireNonNull(store); mutex = store.mutex;
        this.authority = Objects.requireNonNull(authority);
        if (authority.mutex != mutex) throw new IllegalArgumentException("Authority must share the runtime lock");
        this.physical = Objects.requireNonNull(physical); this.process = RuntimeOrigin.uuid(process);
    }

    /**
     * Opens only an already-IDLE, physically clear startup barrier. Occupied/corrupt legacy or
     * old-process records require the future Android reconciliation adapter; never mint a lease.
     */
    public Result reconcileStartup() {
        return mutex.runtime(() -> {
            if (lease != null || quarantined) return Result.QUARANTINED;
            startupReconciled = false;
            try { record = store.read(); }
            catch (RuntimeException invalid) { quarantined = true; return Result.QUARANTINED; }
            if (record.phase != Phase.IDLE || physicalState() != Occupancy.CLEAR)
                return Result.STARTUP_RECONCILIATION_REQUIRED;
            startupReconciled = true; return Result.OK;
        });
    }

    /**
     * REMOTE retry reuse covers only the current admitted command. The Android adapter must
     * consult existing terminal RemoteStore receipts before calling this for historical replay.
     */
    public Admission reserve(RuntimeAdmissionAuthority.Witness requested, Set<String> requiredComponents) {
        Set<String> required = Record.keys(requiredComponents);
        if (required.isEmpty()) throw new IllegalArgumentException("A runtime requires at least one component");
        return mutex.runtime(() -> {
            if (quarantined) return denied(Result.QUARANTINED);
            if (!startupReconciled) return denied(Result.STARTUP_RECONCILIATION_REQUIRED);
            if (record.revision >= Long.MAX_VALUE - 1) {
                quarantined = true; if (resources != null) resources.seal();
                return denied(Result.QUARANTINED);
            }
            if (!authority.current(requested)) return denied(Result.AUTHORITY_CHANGED);
            if (requested.origin.kind == RuntimeOrigin.Kind.REMOTE
                    && (requested.target.kind != RuntimeTarget.Kind.DEVICE
                    || requested.origin.remote.authorizationRevision != requested.authorizationRevision()))
                return denied(Result.AUTHORITY_CHANGED);
            if (lease != null || record.phase != Phase.IDLE) {
                if (lease != null && !resources.isSealed() && authority.current(witness) && requested.origin.kind == RuntimeOrigin.Kind.REMOTE
                        && lease.identity.origin.equals(requested.origin) && lease.identity.target.equals(requested.target)
                        && lease.identity.profileGeneration.equals(requested.profileGeneration())
                        && lease.identity.initialConfigurationRevision == requested.configurationRevision()
                        && record.requiredComponents.equals(required))
                    return new Admission(Result.OK, lease, true);
                return denied(Result.OCCUPIED);
            }
            if (physicalState() != Occupancy.CLEAR) return denied(Result.OCCUPIED);
            RuntimeLease candidate = new RuntimeLease(new RuntimeLease.Identity(requested.target, requested.origin,
                    requested.profileGeneration(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                    process, requested.configurationRevision()));
            lease = candidate; witness = requested;
            resources = new OwnedResourceRegistry(mutex, candidate, () -> stopLocked(candidate));
            Record next = new Record(Math.addExact(record.revision, 1), Phase.RESERVED, candidate.identity,
                    requested.configurationRevision(), requested.configurationRevision(), required, Set.of());
            Result saved = write(next);
            if (saved == Result.OK) return new Admission(Result.OK, candidate, false);
            if (!quarantined) releaseMemory(); // A definite failed reservation published no capability.
            return denied(saved);
        });
    }

    /** Permission dialogs may pause/recreate a host; only the exact reserved capability can return. */
    public boolean acceptsPermissionResult(RuntimeLease requested) {
        return mutex.runtime(() -> current(requested) && !quarantined && record.phase == Phase.RESERVED
                && !resources.isSealed() && authority.current(witness));
    }

    public Result beginStarting(RuntimeLease requested, StartReadiness readiness) {
        Objects.requireNonNull(readiness);
        return mutex.runtime(() -> {
            Result guard = guard(requested, Phase.RESERVED);
            if (guard != Result.OK) return guard;
            if (!authority.current(witness)) { stopLocked(requested); return Result.AUTHORITY_CHANGED; }
            boolean ready;
            try { ready = readiness.current(requested.identity); }
            catch (RuntimeException unknown) { ready = false; }
            if (physicalState() != Occupancy.CLEAR || !ready) {
                stopLocked(requested); return Result.NOT_READY;
            }
            Result saved = write(record.next(Phase.STARTING, record.savedConfigurationRevision,
                    record.appliedConfigurationRevision, record.readyComponents));
            if (saved == Result.OK) resources.starting();
            return saved;
        });
    }

    public OwnedResourceRegistry resources(RuntimeLease requested) {
        return mutex.runtime(() -> {
            if (!current(requested)) throw new SecurityException("Stale runtime lease");
            return resources;
        });
    }
    public OwnedResourceRegistry.Registration registerComponent(RuntimeLease requested, String component) {
        return mutex.runtime(() -> {
            if (guard(requested, Phase.STARTING) != Result.OK || !record.requiredComponents.contains(component))
                throw new SecurityException("Unrequested runtime component");
            return resources.register(requested, component, true);
        });
    }
    public OwnedResourceRegistry.Registration registerWork(RuntimeLease requested, String name) {
        return mutex.runtime(() -> {
            if (!current(requested) || quarantined) throw new SecurityException("Stale runtime work");
            return resources.register(requested, name, false);
        });
    }
    public Result componentReady(RuntimeLease requested, OwnedResourceRegistry.Registration component) {
        return mutex.runtime(() -> {
            Result guard = guard(requested, Phase.STARTING);
            if (guard != Result.OK) return guard;
            if (!resources.ownsComponent(component)) return Result.NOT_READY;
            if (record.readyComponents.contains(component.name)) return Result.OK;
            Set<String> ready = new LinkedHashSet<>(record.readyComponents); ready.add(component.name);
            return write(record.next(Phase.STARTING, record.savedConfigurationRevision, record.appliedConfigurationRevision, ready));
        });
    }
    public Result activate(RuntimeLease requested) {
        return mutex.runtime(() -> {
            Result guard = guard(requested, Phase.STARTING);
            if (guard != Result.OK) return guard;
            if (!authority.current(witness)) { stopLocked(requested); return Result.AUTHORITY_CHANGED; }
            if (!record.readyComponents.equals(record.requiredComponents) || !resources.startupEffectsDrained())
                return Result.NOT_READY;
            for (String component : record.requiredComponents) if (!resources.hasComponent(component)) return Result.NOT_READY;
            Result saved = write(record.next(Phase.ACTIVE, record.savedConfigurationRevision,
                    record.appliedConfigurationRevision, record.readyComponents));
            if (saved == Result.OK) resources.activate();
            return saved;
        });
    }

    public Result offerConfiguration(RuntimeLease requested, long expectedAppliedRevision, long savedRevision) {
        return mutex.runtime(() -> {
            Result guard = guard(requested, Phase.ACTIVE); if (guard != Result.OK) return guard;
            if (expectedAppliedRevision != record.appliedConfigurationRevision || savedRevision < record.savedConfigurationRevision)
                return Result.CONFIGURATION_CHANGED;
            if (savedRevision == record.savedConfigurationRevision) return Result.OK;
            return write(record.next(Phase.ACTIVE, savedRevision, record.appliedConfigurationRevision, record.readyComponents));
        });
    }
    public Result configurationApplied(RuntimeLease requested, long expectedAppliedRevision, long appliedRevision) {
        return mutex.runtime(() -> {
            Result guard = guard(requested, Phase.ACTIVE); if (guard != Result.OK) return guard;
            if (expectedAppliedRevision != record.appliedConfigurationRevision || appliedRevision != record.savedConfigurationRevision)
                return Result.CONFIGURATION_CHANGED;
            if (appliedRevision == record.appliedConfigurationRevision) return Result.OK;
            return write(record.next(Phase.ACTIVE, record.savedConfigurationRevision, appliedRevision, record.readyComponents));
        });
    }

    public Result requestStop(RuntimeLease requested) { return mutex.runtime(() -> stopLocked(requested)); }

    /** Remote authority must be fresh and target the complete expected DEVICE identity, never INTERNAL. */
    public Result requestRemoteStop(RuntimeLease.Identity expected, RuntimeOrigin.RemoteProof stopCommand,
            RemoteStopAuthority stopAuthority) {
        Objects.requireNonNull(stopCommand); Objects.requireNonNull(stopAuthority);
        return mutex.runtime(() -> {
            if (expected == null || expected.target.kind != RuntimeTarget.Kind.DEVICE || lease == null
                    || !expected.equals(lease.identity)) return Result.STALE_LEASE;
            boolean authorized;
            try { authorized = stopAuthority.current(stopCommand, expected); }
            catch (RuntimeException unknown) { authorized = false; }
            if (!authorized) return Result.AUTHORITY_CHANGED;
            return stopLocked(lease);
        });
    }
    private Result stopLocked(RuntimeLease requested) {
        mutex.requireHeld();
        if (!current(requested)) return Result.STALE_LEASE;
        resources.seal(); // Revoke effects BEFORE a possibly failed durable STOPPING transition.
        if (quarantined) return Result.QUARANTINED;
        if (record.phase == Phase.STOPPING) return Result.OK;
        return write(record.next(Phase.STOPPING, record.savedConfigurationRevision,
                record.appliedConfigurationRevision, record.readyComponents));
    }
    public Result completeStop(RuntimeLease requested, OwnedResourceRegistry.DrainReceipt receipt) {
        return mutex.runtime(() -> completeStopLocked(requested, receipt));
    }
    private Result completeStopLocked(RuntimeLease requested, OwnedResourceRegistry.DrainReceipt receipt) {
        if (!current(requested)) return Result.STALE_LEASE;
        if (quarantined) return Result.QUARANTINED;
        if (record.phase != Phase.STOPPING) return Result.WRONG_PHASE;
        if (!resources.accepts(receipt)) return Result.NOT_READY;
        Result saved = write(Record.idle(Math.addExact(record.revision, 1)));
        if (saved == Result.OK) releaseMemory();
        return saved;
    }

    /**
     * Read after an uncertain/conflicting write before any retry. Only exact before/after records
     * reconcile. Even an acknowledged ACTIVE result remains sealed and must stop; it never resumes.
     */
    public Result reconcilePersistence() {
        return mutex.runtime(() -> {
            if (!quarantined || uncertainBefore == null || uncertainAfter == null) return Result.QUARANTINED;
            Record observed;
            try { observed = store.read(); }
            catch (RuntimeException invalid) { return Result.QUARANTINED; }
            if (!observed.equals(uncertainBefore) && !observed.equals(uncertainAfter)) return Result.QUARANTINED;
            record = observed;
            if (record.phase == Phase.IDLE) {
                // Reservation never published, or the sealed registry already acknowledged closure.
                if (resources != null && resources.drained(lease) == null) return Result.QUARANTINED;
                quarantined = false; uncertainBefore = null; uncertainAfter = null;
                releaseMemory(); return Result.OK;
            }
            if (lease == null || !lease.identity.equals(record.owner)) return Result.QUARANTINED;
            quarantined = false; uncertainBefore = null; uncertainAfter = null;
            Result stopped = stopLocked(lease);
            if (stopped != Result.OK) return stopped;
            OwnedResourceRegistry.DrainReceipt receipt = resources.drained(lease);
            return receipt == null ? Result.NOT_READY : completeStopLocked(lease, receipt);
        });
    }

    public Observation observe() {
        return mutex.runtime(() -> new Observation(record, startupReconciled, quarantined,
                resources != null && resources.isSealed()));
    }
    public boolean anyOccupied() {
        return mutex.runtime(() -> !startupReconciled || quarantined || lease != null
                || record == null || record.phase != Phase.IDLE || physicalState() != Occupancy.CLEAR);
    }
    /** Target-filtered data cannot attribute another owner's capture to the current user. */
    public Observation stateFor(RuntimeTarget target) {
        return mutex.runtime(() -> record != null && record.owner != null && record.owner.target.equals(target)
                ? new Observation(record, startupReconciled, quarantined, resources != null && resources.isSealed()) : null);
    }

    private Result guard(RuntimeLease requested, Phase phase) {
        if (!current(requested)) return Result.STALE_LEASE;
        if (quarantined) return Result.QUARANTINED;
        if (record.phase != phase || resources.isSealed()) return Result.WRONG_PHASE;
        return Result.OK;
    }
    private boolean current(RuntimeLease requested) {
        return requested != null && requested == lease && process.equals(requested.identity.process);
    }
    private Occupancy physicalState() {
        try { return Objects.requireNonNull(physical.observe()); }
        catch (RuntimeException unknown) { return Occupancy.UNKNOWN; }
    }
    private Result write(Record next) {
        if (next.revision == Long.MAX_VALUE && next.phase != Phase.IDLE) {
            quarantined = true; if (resources != null) resources.seal();
            return Result.QUARANTINED;
        }
        Record before = record;
        Outcome outcome;
        try { outcome = store.compareAndSet(before, next); }
        catch (RuntimeException uncertain) { outcome = Outcome.UNCERTAIN; }
        if (outcome == Outcome.COMMITTED) { record = next; return Result.OK; }
        if (outcome == Outcome.NOT_COMMITTED) return Result.PERSISTENCE_FAILED;
        uncertainBefore = before; uncertainAfter = next; quarantined = true;
        if (resources != null) resources.seal();
        return Result.QUARANTINED;
    }
    private void releaseMemory() { lease = null; witness = null; resources = null; }
    private static Admission denied(Result reason) { return new Admission(reason, null, false); }
}
