package com.subhub.app.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.subhub.app.runtime.ActiveRuntimeCoordinator.Admission;
import com.subhub.app.runtime.ActiveRuntimeCoordinator.Result;
import com.subhub.app.runtime.OwnedResourceRegistry.Effect;
import com.subhub.app.runtime.RuntimeOwnerStore.Outcome;
import com.subhub.app.runtime.RuntimeOwnerStore.Phase;
import com.subhub.app.runtime.RuntimeOwnerStore.Record;

/** Same pure contracts run by JUnit and by main when Android/Gradle is unavailable. No fake JUnit. */
public final class RuntimeFoundationChecks {
    private static final RuntimeTarget INTERNAL = RuntimeTarget.internal("internal_sub");
    private static final String PROCESS = "00000000-0000-4000-8000-000000000001";
    private static final String OTHER_PROCESS = "00000000-0000-4000-8000-000000000002";
    private static final Set<String> COMPONENTS = Set.of("capture", "limits");
    private RuntimeFoundationChecks() { }

    static void startupBarrier() {
        Fixture f = new Fixture();
        equal(Result.STARTUP_RECONCILIATION_REQUIRED, f.reserve(INTERNAL).result);
        check(f.coordinator.anyOccupied(), "Unreconciled startup must stay occupied");
        equal(0, f.backend.writes);
        equal(Result.OK, f.coordinator.reconcileStartup());
        check(!f.coordinator.anyOccupied(), "Acknowledged empty startup should open admission");
    }
    static void oldProcessRecordCannotMintLease() {
        Fixture f = new Fixture();
        RuntimeLease.Identity old = identity(INTERNAL, OTHER_PROCESS);
        f.backend.record = new Record(4, Phase.ACTIVE, old, 7, 7, COMPONENTS, COMPONENTS);
        equal(Result.STARTUP_RECONCILIATION_REQUIRED, f.coordinator.reconcileStartup());
        equal(Result.STARTUP_RECONCILIATION_REQUIRED, f.reserve(INTERNAL).result);
        check(!f.coordinator.acceptsPermissionResult(new RuntimeLease(old)), "Persisted evidence is not a capability");
        equal(0, f.backend.writes);
    }
    static void corruptAndUnknownStartupFailClosed() {
        Fixture corrupt = new Fixture(); corrupt.backend.failRead = true;
        equal(Result.QUARANTINED, corrupt.coordinator.reconcileStartup());
        check(corrupt.coordinator.anyOccupied(), "Corrupt state cannot become idle");
        Fixture unknown = new Fixture(); unknown.occupancy.set(ActiveRuntimeCoordinator.Occupancy.UNKNOWN);
        equal(Result.STARTUP_RECONCILIATION_REQUIRED, unknown.coordinator.reconcileStartup());
        Fixture legacy = new Fixture(); legacy.occupancy.set(ActiveRuntimeCoordinator.Occupancy.OCCUPIED);
        equal(Result.STARTUP_RECONCILIATION_REQUIRED, legacy.coordinator.reconcileStartup());
        equal(0, legacy.backend.writes);
    }
    static void physicalOccupancyBlocksAllOwners() {
        Fixture f = ready(); f.occupancy.set(ActiveRuntimeCoordinator.Occupancy.OCCUPIED);
        equal(Result.OCCUPIED, f.reserve(INTERNAL).result);
        equal(Result.OCCUPIED, f.reserve(RuntimeTarget.DEVICE).result);
        equal(0, f.backend.writes);
        f.occupancy.set(ActiveRuntimeCoordinator.Occupancy.UNKNOWN);
        equal(Result.OCCUPIED, f.reserve(INTERNAL).result);
    }
    static void authorityAndGenerationRevocation() {
        Fixture f = ready();
        RuntimeAdmissionAuthority.Witness old = f.authority.capture(INTERNAL, RuntimeOrigin.LOCAL_UI);
        publishAuthorization(f.authority, INTERNAL, RuntimeOrigin.LOCAL_UI, 99, false);
        equal(Result.AUTHORITY_CHANGED, f.coordinator.reserve(old, COMPONENTS).result);
        publishAuthorization(f.authority, INTERNAL, RuntimeOrigin.LOCAL_UI, 99, true);
        equal(Result.AUTHORITY_CHANGED, f.coordinator.reserve(old, COMPONENTS).result);
        old = f.authority.capture(INTERNAL, RuntimeOrigin.LOCAL_UI);
        publishAvailability(f.authority, INTERNAL, "generation2", 7, true);
        equal(Result.AUTHORITY_CHANGED, f.coordinator.reserve(old, COMPONENTS).result);
        old = f.authority.capture(INTERNAL, RuntimeOrigin.LOCAL_UI);
        publishAvailability(f.authority, INTERNAL, "generation2", 7, false);
        equal(Result.AUTHORITY_CHANGED, f.coordinator.reserve(old, COMPONENTS).result);
        equal(0, f.backend.writes);
    }
    static void remoteAuthorityCannotAuthorizeLocalUi() {
        Fixture f = ready(); RuntimeOrigin remote = remote("00000000-0000-4000-8000-000000000003", Long.MIN_VALUE);
        publishAuthorization(f.authority, RuntimeTarget.DEVICE, RuntimeOrigin.LOCAL_UI, 100, false);
        publishAuthorization(f.authority, RuntimeTarget.DEVICE, remote, Long.MIN_VALUE, true);
        equal(Result.AUTHORITY_CHANGED, f.reserve(RuntimeTarget.DEVICE).result);
        Admission request = f.coordinator.reserve(f.authority.capture(RuntimeTarget.DEVICE, remote), COMPONENTS);
        equal(Result.OK, request.result);
        equal(Long.MIN_VALUE, request.lease.identity.origin.remote.authorizationRevision);
    }
    static void definiteReservationFailurePublishesNothing() {
        Fixture f = ready(); f.backend.next = Outcome.NOT_COMMITTED;
        Admission failed = f.reserve(INTERNAL);
        equal(Result.PERSISTENCE_FAILED, failed.result); check(failed.lease == null, "Failed commit published a lease");
        check(!f.coordinator.anyOccupied(), "Definite no-write can retry");
        equal(Result.OK, f.reserve(INTERNAL).result);
    }
    static void uncertainReservationReconcilesBothOutcomes() {
        for (boolean applied : new boolean[]{false, true}) {
            Fixture f = ready(); f.backend.next = Outcome.UNCERTAIN; f.backend.applyUncertain = applied;
            Admission failed = f.reserve(INTERNAL);
            equal(Result.QUARANTINED, failed.result); check(failed.lease == null, "Uncertain commit published a lease");
            equal(Result.QUARANTINED, f.reserve(RuntimeTarget.DEVICE).result);
            equal(Result.OK, f.coordinator.reconcilePersistence());
            check(!f.coordinator.anyOccupied(), "Known unpublished attempt can retire after exact readback");
            equal(Result.OK, f.reserve(RuntimeTarget.DEVICE).result);
        }
    }
    static void uncertainUnrelatedReadbackStaysQuarantined() {
        Fixture f = ready(); f.backend.next = Outcome.UNCERTAIN; f.reserve(INTERNAL);
        f.backend.record = Record.idle(80);
        equal(Result.QUARANTINED, f.coordinator.reconcilePersistence());
        equal(Result.QUARANTINED, f.reserve(INTERNAL).result);
    }
    static void permissionCallbacksAreProcessAndPhaseBound() {
        Fixture f = ready(); RuntimeLease lease = admitted(f.reserve(INTERNAL));
        check(f.coordinator.acceptsPermissionResult(lease), "Valid pending permission must survive ordinary host pause");
        check(!f.coordinator.acceptsPermissionResult(new RuntimeLease(lease.identity)), "Copied identity forged a capability");
        publishAuthorization(f.authority, INTERNAL, RuntimeOrigin.LOCAL_UI, 100, false);
        check(!f.coordinator.acceptsPermissionResult(lease), "Revoked permission callback was accepted");
        equal(Result.AUTHORITY_CHANGED, f.coordinator.beginStarting(lease, ignored -> true));
        equal(Phase.STOPPING, f.coordinator.observe().persisted.phase);
    }
    static void interveningPhysicalStartAbortsReservation() {
        Fixture f = ready(); RuntimeLease lease = admitted(f.reserve(INTERNAL));
        f.occupancy.set(ActiveRuntimeCoordinator.Occupancy.OCCUPIED);
        equal(Result.NOT_READY, f.coordinator.beginStarting(lease, ignored -> true));
        equal(Phase.STOPPING, f.coordinator.observe().persisted.phase);
        deny(() -> f.coordinator.resources(lease).acquireEffectPermit(lease, Effect.STARTUP));
    }
    static void requiredReceiptsAndStartupEffectsGateActive() {
        Fixture f = ready(); RuntimeLease lease = admitted(f.reserve(INTERNAL));
        equal(Result.OK, f.coordinator.beginStarting(lease, ignored -> true));
        OwnedResourceRegistry registry = f.coordinator.resources(lease);
        OwnedResourceRegistry.Registration capture = f.coordinator.registerComponent(lease, "capture");
        equal(Result.OK, f.coordinator.componentReady(lease, capture));
        equal(Result.NOT_READY, f.coordinator.activate(lease));
        OwnedResourceRegistry.Registration limits = f.coordinator.registerComponent(lease, "limits");
        equal(Result.OK, f.coordinator.componentReady(lease, limits));
        OwnedResourceRegistry.EffectPermit startup = registry.acquireEffectPermit(lease, Effect.STARTUP);
        equal(Result.NOT_READY, f.coordinator.activate(lease)); startup.close();
        equal(Result.OK, f.coordinator.activate(lease));
        deny(() -> registry.acquireEffectPermit(lease, Effect.STARTUP));
        try (OwnedResourceRegistry.EffectPermit ignored = registry.acquireEffectPermit(lease, Effect.NORMAL)) {
            equal(Effect.NORMAL, ignored.effect);
            check(!f.coordinator.observe().effectsSealed, "Active lease unexpectedly fenced");
        }
        check(!f.coordinator.acceptsPermissionResult(lease), "Late permission callback must not restart active run");
    }
    static void componentLossImmediatelyStopsEffects() {
        Fixture f = ready(); Run run = f.start(INTERNAL);
        run.components.get(0).close();
        equal(Phase.STOPPING, f.coordinator.observe().persisted.phase);
        deny(() -> run.registry.acquireEffectPermit(run.lease, Effect.NORMAL));
        check(run.registry.drained(run.lease) == null, "Other component has not closed");
    }
    static void failedStopWriteStillFencesEffects() {
        Fixture f = ready(); Run run = f.start(INTERNAL);
        OwnedResourceRegistry.EffectPermit inFlight = run.registry.acquireEffectPermit(run.lease, Effect.NORMAL);
        f.backend.next = Outcome.NOT_COMMITTED;
        equal(Result.PERSISTENCE_FAILED, f.coordinator.requestStop(run.lease));
        equal(Phase.ACTIVE, f.coordinator.observe().persisted.phase);
        check(f.coordinator.observe().effectsSealed, "Failed STOPPING persistence left effects admitted");
        equal(Phase.STOPPING, f.coordinator.stateFor(INTERNAL).effectivePhase());
        deny(() -> run.registry.acquireEffectPermit(run.lease, Effect.NORMAL));
        equal(Result.OCCUPIED, f.reserve(RuntimeTarget.DEVICE).result);
        run.closeComponents(); check(run.registry.drained(run.lease) == null, "In-flight effect not counted");
        inFlight.close();
        equal(Result.WRONG_PHASE, f.coordinator.completeStop(run.lease, run.registry.drained(run.lease)));
        equal(Result.OK, f.coordinator.requestStop(run.lease));
        equal(Result.OK, f.coordinator.completeStop(run.lease, run.registry.drained(run.lease)));
    }
    static void uncertainStopNeverReactivates() {
        for (boolean applied : new boolean[]{false, true}) {
            Fixture f = ready(); Run run = f.start(INTERNAL);
            f.backend.next = Outcome.UNCERTAIN; f.backend.applyUncertain = applied;
            equal(Result.QUARANTINED, f.coordinator.requestStop(run.lease));
            deny(() -> run.registry.acquireEffectPermit(run.lease, Effect.NORMAL));
            equal(Result.NOT_READY, f.coordinator.reconcilePersistence());
            equal(Phase.STOPPING, f.coordinator.observe().persisted.phase);
            deny(() -> run.registry.acquireEffectPermit(run.lease, Effect.NORMAL));
            run.closeComponents();
            equal(Result.OK, f.coordinator.completeStop(run.lease, run.registry.drained(run.lease)));
        }
    }
    static void stopCompletionNeedsAcknowledgedPersistence() {
        Fixture f = ready(); Run run = f.start(INTERNAL);
        equal(Result.OK, f.coordinator.requestStop(run.lease)); run.closeComponents();
        OwnedResourceRegistry.DrainReceipt receipt = run.registry.drained(run.lease);
        f.backend.next = Outcome.NOT_COMMITTED;
        equal(Result.PERSISTENCE_FAILED, f.coordinator.completeStop(run.lease, receipt));
        equal(Result.OCCUPIED, f.reserve(RuntimeTarget.DEVICE).result);
        f.backend.next = Outcome.UNCERTAIN; f.backend.applyUncertain = true;
        equal(Result.QUARANTINED, f.coordinator.completeStop(run.lease, receipt));
        equal(Result.QUARANTINED, f.reserve(RuntimeTarget.DEVICE).result);
        equal(Result.OK, f.coordinator.reconcilePersistence());
        equal(Result.OK, f.reserve(RuntimeTarget.DEVICE).result);
    }
    static void oldReceiptCannotRetireReplacement() {
        Fixture f = ready(); Run first = f.start(INTERNAL);
        equal(Result.OK, f.coordinator.requestStop(first.lease)); first.closeComponents();
        OwnedResourceRegistry.DrainReceipt old = first.registry.drained(first.lease);
        equal(Result.OK, f.coordinator.completeStop(first.lease, old));
        Run second = f.start(INTERNAL);
        equal(Result.STALE_LEASE, f.coordinator.requestStop(first.lease));
        equal(Result.STALE_LEASE, f.coordinator.completeStop(first.lease, old));
        equal(Result.OK, f.coordinator.requestStop(second.lease)); second.closeComponents();
        equal(Result.NOT_READY, f.coordinator.completeStop(second.lease, old));
        equal(Result.OK, f.coordinator.completeStop(second.lease, second.registry.drained(second.lease)));
    }
    static void queuedWorkAndCleanupAreCounted() {
        Fixture f = ready(); Run run = f.start(INTERNAL);
        OwnedResourceRegistry.Registration queued = f.coordinator.registerWork(run.lease, "queued_inference");
        equal(Result.OK, f.coordinator.requestStop(run.lease));
        deny(() -> f.coordinator.registerWork(run.lease, "late_work"));
        deny(() -> run.registry.acquireEffectPermit(run.lease, Effect.NORMAL));
        run.closeComponents(); check(run.registry.drained(run.lease) == null, "Queued work lost from registry");
        queued.close(); queued.close();
        OwnedResourceRegistry.EffectPermit cleanup = run.registry.acquireEffectPermit(run.lease, Effect.CLEANUP);
        check(run.registry.drained(run.lease) == null, "Cleanup effect lost from registry"); cleanup.close();
        OwnedResourceRegistry.DrainReceipt receipt = run.registry.drained(run.lease);
        check(receipt != null, "Closed registry must issue its receipt");
        deny(() -> run.registry.acquireEffectPermit(run.lease, Effect.CLEANUP));
        equal(Result.OK, f.coordinator.completeStop(run.lease, receipt));
    }
    static void inFlightEffectBlocksReplacement() {
        Fixture f = ready(); Run run = f.start(INTERNAL);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>(); AtomicInteger effects = new AtomicInteger();
        Thread worker = new Thread(() -> {
            try (OwnedResourceRegistry.EffectPermit ignored = run.registry.acquireEffectPermit(run.lease, Effect.NORMAL)) {
                equal(Effect.NORMAL, ignored.effect);
                entered.countDown(); await(release); effects.incrementAndGet();
            } catch (Throwable failure) { error.set(failure); }
        });
        worker.start(); await(entered);
        equal(Result.OK, f.coordinator.requestStop(run.lease)); run.closeComponents();
        check(run.registry.drained(run.lease) == null, "Effect must cover the real operation, not just admission check");
        equal(Result.OCCUPIED, f.reserve(RuntimeTarget.DEVICE).result);
        release.countDown(); join(worker); rethrow(error.get()); equal(1, effects.get());
        equal(Result.OK, f.coordinator.completeStop(run.lease, run.registry.drained(run.lease)));
        equal(Result.OK, f.reserve(RuntimeTarget.DEVICE).result);
    }
    static void registrationRacingSealIsDeniedOrCounted() {
        for (int iteration = 0; iteration < 40; iteration++) {
            Fixture f = ready(); Run run = f.start(INTERNAL); CountDownLatch go = new CountDownLatch(1);
            AtomicReference<OwnedResourceRegistry.Registration> registered = new AtomicReference<>();
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                await(go);
                try { registered.set(f.coordinator.registerWork(run.lease, "racing_child")); }
                catch (SecurityException expected) { /* Sealing won the lock. */ }
                catch (Throwable failure) { error.set(failure); }
            });
            worker.start(); go.countDown(); equal(Result.OK, f.coordinator.requestStop(run.lease)); join(worker); rethrow(error.get());
            run.closeComponents();
            if (registered.get() != null) {
                check(run.registry.drained(run.lease) == null, "Accepted racing child was omitted");
                registered.get().close();
            }
            equal(Result.OK, f.coordinator.completeStop(run.lease, run.registry.drained(run.lease)));
        }
    }
    static void duplicateRemoteStartIsNotAnotherActivation() {
        Fixture f = ready(); RuntimeOrigin origin = remote("00000000-0000-4000-8000-000000000003", 91);
        publishAuthorization(f.authority, RuntimeTarget.DEVICE, origin, 91, true);
        RuntimeAdmissionAuthority.Witness proof = f.authority.capture(RuntimeTarget.DEVICE, origin);
        Admission first = f.coordinator.reserve(proof, COMPONENTS);
        Admission repeated = f.coordinator.reserve(proof, COMPONENTS);
        equal(Result.OK, repeated.result); check(repeated.repeatedRemoteCommand, "Remote retry lost its original receipt");
        check(first.lease == repeated.lease, "Remote retry created another capability"); equal(1, f.backend.writes);
        equal(Result.OCCUPIED, f.coordinator.reserve(proof, Set.of("capture")).result);
        RuntimeOrigin other = remote("00000000-0000-4000-8000-000000000004", 91);
        check(f.authority.capture(RuntimeTarget.DEVICE, other).authorizationRevision() == 91, "Same peer principal lost its grant");
        equal(Result.OCCUPIED, f.coordinator.reserve(f.authority.capture(RuntimeTarget.DEVICE, other), COMPONENTS).result);
    }
    static void remoteStopIsFreshAndExactlyTargeted() {
        Fixture f = ready(); Run internal = f.start(INTERNAL);
        RuntimeOrigin.RemoteProof stop = remote("00000000-0000-4000-8000-000000000005", 99).remote;
        AtomicInteger callbacks = new AtomicInteger();
        equal(Result.STALE_LEASE, f.coordinator.requestRemoteStop(internal.lease.identity, stop,
                (command, expected) -> { callbacks.incrementAndGet(); return true; }));
        equal(0, callbacks.get()); f.finish(internal);
        Run first = f.start(RuntimeTarget.DEVICE);
        equal(Result.AUTHORITY_CHANGED, f.coordinator.requestRemoteStop(first.lease.identity, stop, (command, expected) -> false));
        equal(Phase.ACTIVE, f.coordinator.observe().persisted.phase);
        f.finish(first); Run second = f.start(RuntimeTarget.DEVICE);
        equal(Result.STALE_LEASE, f.coordinator.requestRemoteStop(first.lease.identity, stop, (command, expected) -> true));
        equal(Result.OK, f.coordinator.requestRemoteStop(second.lease.identity, stop, (command, expected) -> true));
    }
    static void targetObservationsDoNotBorrowInternalCapture() {
        Fixture f = ready(); Run internal = f.start(INTERNAL);
        check(f.coordinator.stateFor(RuntimeTarget.DEVICE) == null, "Internal capture leaked into DEVICE state");
        equal(internal.lease.identity, f.coordinator.stateFor(INTERNAL).persisted.owner);
        check(f.coordinator.anyOccupied(), "Internal runtime must still occupy physical resources");
    }
    static void configurationSaveAndApplicationRemainDistinct() {
        Fixture f = ready(); Run run = f.start(INTERNAL);
        equal(Result.OK, f.coordinator.offerConfiguration(run.lease, 7, 8));
        check(f.coordinator.observe().configurationPending(), "Unapplied saved configuration reported applied");
        equal(7, run.lease.identity.initialConfigurationRevision);
        equal(Result.OK, f.coordinator.offerConfiguration(run.lease, 7, 9));
        equal(Result.CONFIGURATION_CHANGED, f.coordinator.configurationApplied(run.lease, 7, 8));
        equal(Result.OK, f.coordinator.configurationApplied(run.lease, 7, 9));
        check(!f.coordinator.observe().configurationPending(), "Matching application receipt was not reflected");
        equal(Result.CONFIGURATION_CHANGED, f.coordinator.offerConfiguration(run.lease, 7, 10));
        equal(Result.CONFIGURATION_CHANGED, f.coordinator.offerConfiguration(run.lease, 9, 8));
    }
    static void uncertainConfigurationWriteStopsRatherThanResumes() {
        Fixture f = ready(); Run run = f.start(INTERNAL);
        f.backend.next = Outcome.UNCERTAIN; f.backend.applyUncertain = true;
        equal(Result.QUARANTINED, f.coordinator.offerConfiguration(run.lease, 7, 8));
        deny(() -> run.registry.acquireEffectPermit(run.lease, Effect.NORMAL));
        equal(Result.NOT_READY, f.coordinator.reconcilePersistence());
        equal(Phase.STOPPING, f.coordinator.observe().persisted.phase);
        run.closeComponents();
        equal(Result.OK, f.coordinator.completeStop(run.lease, run.registry.drained(run.lease)));
    }
    static void editorTransactionCannotEnterRuntime() {
        Fixture f = ready();
        expect(IllegalStateException.class, () -> f.mutex.editorTransaction(() -> f.reserve(INTERNAL)));
        expect(IllegalStateException.class, () -> f.mutex.runtime(() -> f.mutex.editorTransaction(() -> true)));
        equal(0, f.backend.writes);
        equal(Result.OK, f.reserve(INTERNAL).result);
    }
    static void ownerTransactionCannotReenterRuntime() {
        Fixture f = ready();
        f.backend.beforeWrite = () -> f.coordinator.observe();
        Admission result = f.reserve(INTERNAL);
        equal(Result.QUARANTINED, result.result); check(result.lease == null, "Backend callback published capability");
        f.backend.beforeWrite = null;
        equal(Result.OK, f.coordinator.reconcilePersistence());
    }
    static void revocationRacingReservationCannotAuthorizeStartup() {
        Fixture f = ready(); CountDownLatch transaction = new CountDownLatch(1), finish = new CountDownLatch(1);
        AtomicReference<Admission> result = new AtomicReference<>(); AtomicReference<Throwable> error = new AtomicReference<>();
        f.backend.beforeWrite = () -> { transaction.countDown(); await(finish); };
        Thread reserver = new Thread(() -> { try { result.set(f.reserve(INTERNAL)); } catch (Throwable problem) { error.set(problem); } });
        reserver.start(); await(transaction);
        Thread revoker = new Thread(() -> {
            try { publishAvailability(f.authority, INTERNAL, "generation1", 7, false); }
            catch (Throwable problem) { error.set(problem); }
        });
        revoker.start(); finish.countDown(); join(reserver); join(revoker); rethrow(error.get());
        f.backend.beforeWrite = null;
        RuntimeLease lease = admitted(result.get());
        check(!f.coordinator.acceptsPermissionResult(lease), "Availability revocation was not atomic with publication");
        equal(Result.AUTHORITY_CHANGED, f.coordinator.beginStarting(lease, ignored -> true));
    }
    static void casComparesNamespaceAndGeneration() {
        Fixture f = ready(); RuntimeLease lease = admitted(f.reserve(INTERNAL)); Record exact = f.backend.record;
        RuntimeLease.Identity different = new RuntimeLease.Identity(INTERNAL, RuntimeOrigin.LOCAL_UI, "generation2",
                exact.owner.session, exact.owner.activation, exact.owner.process, 7);
        Record wrong = new Record(exact.revision, Phase.RESERVED, different, 7, 7, COMPONENTS, Set.of());
        Record desired = wrong.next(Phase.STARTING, 7, 7, Set.of());
        equal(Outcome.CONFLICT, f.mutex.runtime(() -> f.store.compareAndSet(wrong, desired)));
        equal(exact, f.backend.record);
        expect(IllegalArgumentException.class, () -> f.mutex.runtime(() -> f.store.compareAndSet(exact, exact)));
        check(f.coordinator.acceptsPermissionResult(lease), "Unrelated failed CAS invalidated original capability");
    }
    static void malformedModelsCannotRepresentAmbiguousOwners() {
        expect(IllegalArgumentException.class, () -> RuntimeTarget.internal("local"));
        expect(IllegalArgumentException.class, () -> RuntimeTarget.internal("remote_foo"));
        expect(IllegalArgumentException.class, () -> RuntimeTarget.internal("../real"));
        expect(IllegalArgumentException.class, () -> new Record(0, Phase.IDLE, identity(INTERNAL, PROCESS), 0, 0, Set.of(), Set.of()));
        expect(IllegalArgumentException.class, () -> new Record(1, Phase.ACTIVE, identity(INTERNAL, PROCESS), 7, 7,
                COMPONENTS, Set.of("capture")));
        expect(IllegalArgumentException.class, () -> new RuntimeLease.Identity(INTERNAL,
                remote("00000000-0000-4000-8000-000000000005", 99), "generation1", uuid(), uuid(), PROCESS, 7));
        Fixture f = ready(); expect(IllegalArgumentException.class, () -> f.coordinator.reserve(
                f.authority.capture(INTERNAL, RuntimeOrigin.LOCAL_UI), Set.of()));
    }
    static void storeReadFailureDoesNotDiscardLiveResources() {
        Fixture f = ready(); Run run = f.start(INTERNAL);
        f.backend.next = Outcome.UNCERTAIN; f.coordinator.requestStop(run.lease); f.backend.failRead = true;
        equal(Result.QUARANTINED, f.coordinator.reconcilePersistence());
        check(run.registry.drained(run.lease) == null, "Read failure discarded registered resources");
        run.closeComponents(); f.backend.failRead = false;
        equal(Result.OK, f.coordinator.reconcilePersistence());
    }

    static void failedStartupCommitCannotAdmitEffects() {
        Fixture f = ready(); RuntimeLease lease = admitted(f.reserve(INTERNAL));
        f.backend.next = Outcome.NOT_COMMITTED;
        equal(Result.PERSISTENCE_FAILED, f.coordinator.beginStarting(lease, ignored -> true));
        OwnedResourceRegistry registry = f.coordinator.resources(lease);
        deny(() -> registry.acquireEffectPermit(lease, Effect.STARTUP));
        equal(Result.OK, f.coordinator.beginStarting(lease, ignored -> true));
        for (String name : COMPONENTS) {
            OwnedResourceRegistry.Registration component = f.coordinator.registerComponent(lease, name);
            equal(Result.OK, f.coordinator.componentReady(lease, component));
        }
        f.backend.next = Outcome.NOT_COMMITTED;
        equal(Result.PERSISTENCE_FAILED, f.coordinator.activate(lease));
        deny(() -> registry.acquireEffectPermit(lease, Effect.NORMAL));
        equal(Result.OK, f.coordinator.activate(lease));
    }
    static void uncertainActivationNeverPublishesNormalEffects() {
        Fixture f = ready(); RuntimeLease lease = admitted(f.reserve(INTERNAL));
        equal(Result.OK, f.coordinator.beginStarting(lease, ignored -> true));
        List<OwnedResourceRegistry.Registration> components = new ArrayList<>();
        for (String name : COMPONENTS) {
            OwnedResourceRegistry.Registration component = f.coordinator.registerComponent(lease, name);
            components.add(component); equal(Result.OK, f.coordinator.componentReady(lease, component));
        }
        f.backend.next = Outcome.UNCERTAIN; f.backend.applyUncertain = true;
        equal(Result.QUARANTINED, f.coordinator.activate(lease));
        OwnedResourceRegistry registry = f.coordinator.resources(lease);
        deny(() -> registry.acquireEffectPermit(lease, Effect.NORMAL));
        equal(Result.NOT_READY, f.coordinator.reconcilePersistence());
        for (OwnedResourceRegistry.Registration component : components) component.close();
        equal(Result.OK, f.coordinator.completeStop(lease, registry.drained(lease)));
    }
    static void unavailableReadinessFailsClosed() {
        Fixture f = ready(); RuntimeLease lease = admitted(f.reserve(INTERNAL));
        equal(Result.NOT_READY, f.coordinator.beginStarting(lease, ignored -> { throw new IllegalStateException("Readiness unavailable"); }));
        equal(Phase.STOPPING, f.coordinator.observe().effectivePhase());
    }
    static void exhaustedRevisionNeverPublishesCapability() {
        Fixture f = new Fixture(); f.backend.record = Record.idle(Long.MAX_VALUE);
        equal(Result.OK, f.coordinator.reconcileStartup());
        Admission denied = f.reserve(INTERNAL);
        equal(Result.QUARANTINED, denied.result); check(denied.lease == null, "Exhausted revision published a capability");
        equal(0, f.backend.writes);
    }

    static void authorizationIsTargetScopedInBothDirections() {
        Fixture deviceOnly = ready();
        publishAuthorization(deviceOnly.authority, INTERNAL, RuntimeOrigin.LOCAL_UI, 100, false);
        equal(Result.AUTHORITY_CHANGED, deviceOnly.reserve(INTERNAL).result);
        equal(Result.OK, deviceOnly.reserve(RuntimeTarget.DEVICE).result);
        Fixture internalOnly = ready();
        publishAuthorization(internalOnly.authority, RuntimeTarget.DEVICE, RuntimeOrigin.LOCAL_UI, 100, false);
        equal(Result.AUTHORITY_CHANGED, internalOnly.reserve(RuntimeTarget.DEVICE).result);
        equal(Result.OK, internalOnly.reserve(INTERNAL).result);
    }
    static void authorizationDoesNotCrossInternalProfiles() {
        Fixture f = ready(); RuntimeTarget other = RuntimeTarget.internal("other_internal_sub");
        publishAvailability(f.authority, other, "other_generation", 2, true);
        equal(Result.AUTHORITY_CHANGED, f.reserve(other).result);
        publishAuthorization(f.authority, other, RuntimeOrigin.LOCAL_UI, 99, true);
        publishAuthorization(f.authority, INTERNAL, RuntimeOrigin.LOCAL_UI, 100, false);
        equal(Result.AUTHORITY_CHANGED, f.reserve(INTERNAL).result);
        equal(Result.OK, f.reserve(other).result);
    }
    static void delayedEnableCannotUndoNewerDisable() {
        Fixture f = ready();
        RuntimeAdmissionAuthority.AvailabilityPublication old = f.authority.beginAvailability(INTERNAL);
        publishAvailability(f.authority, INTERNAL, "generation1", 7, false);
        check(!f.authority.completeAvailability(old, "generation1", 7, true), "Delayed enable undid newer disable");
        equal(Result.AUTHORITY_CHANGED, f.reserve(INTERNAL).result);
        equal(0, f.backend.writes);
    }
    static void delayedGrantCannotUndoRoleRevocation() {
        Fixture f = ready();
        RuntimeAdmissionAuthority.AuthorizationPublication old = f.authority.beginAuthorization(INTERNAL, RuntimeOrigin.LOCAL_UI);
        publishAuthorization(f.authority, INTERNAL, RuntimeOrigin.LOCAL_UI, Long.MIN_VALUE, false);
        check(!f.authority.completeAuthorization(old, Long.MAX_VALUE, true), "Opaque revision ordering revived revoked authority");
        equal(Result.AUTHORITY_CHANGED, f.reserve(INTERNAL).result);
        equal(Result.OK, f.reserve(RuntimeTarget.DEVICE).result);
    }
    static void removeAndReenableRetiresOldPublicationsAndWitnesses() {
        Fixture f = ready();
        RuntimeAdmissionAuthority.Witness oldWitness = f.authority.capture(INTERNAL, RuntimeOrigin.LOCAL_UI);
        RuntimeAdmissionAuthority.AuthorizationPublication oldGrant = f.authority.beginAuthorization(INTERNAL, RuntimeOrigin.LOCAL_UI);
        RuntimeAdmissionAuthority.AvailabilityPublication oldEnable = f.authority.beginAvailability(INTERNAL);
        publishAvailability(f.authority, INTERNAL, "generation1", 7, false);
        publishAvailability(f.authority, INTERNAL, "generation2", 0, true);
        publishAuthorization(f.authority, INTERNAL, RuntimeOrigin.LOCAL_UI, 99, true);
        check(!f.authority.completeAvailability(oldEnable, "generation1", 7, true), "Removed generation was restored");
        check(!f.authority.completeAuthorization(oldGrant, 99, true), "Removed generation grant was restored");
        equal(Result.AUTHORITY_CHANGED, f.coordinator.reserve(oldWitness, COMPONENTS).result);
        RuntimeLease current = admitted(f.reserve(INTERNAL));
        equal("generation2", current.identity.profileGeneration); equal(0, current.identity.initialConfigurationRevision);
    }
    static void reorderedConfigurationPublicationsKeepNewestSnapshot() {
        Fixture f = ready();
        RuntimeAdmissionAuthority.AvailabilityPublication old = f.authority.beginAvailability(INTERNAL);
        RuntimeAdmissionAuthority.AvailabilityPublication latest = f.authority.beginAvailability(INTERNAL);
        check(f.authority.completeAvailability(latest, "generation1", 9, true), "Newest configuration not published");
        check(!f.authority.completeAvailability(old, "generation1", 8, true), "Old configuration replaced new configuration");
        publishAuthorization(f.authority, INTERNAL, RuntimeOrigin.LOCAL_UI, 99, true);
        equal(9, admitted(f.reserve(INTERNAL)).identity.initialConfigurationRevision);
    }
    static void invalidationDoesNotWaitForWorkerCompletion() {
        Fixture f = ready();
        f.authority.beginAuthorization(INTERNAL, RuntimeOrigin.LOCAL_UI);
        equal(Result.AUTHORITY_CHANGED, f.reserve(INTERNAL).result);
        publishAuthorization(f.authority, INTERNAL, RuntimeOrigin.LOCAL_UI, 99, true);
        f.authority.beginAvailability(INTERNAL);
        equal(Result.AUTHORITY_CHANGED, f.reserve(INTERNAL).result);
        equal(0, f.backend.writes);
    }
    static void publicationTokensAreOneUseAndAuthorityBound() {
        Fixture f = ready(); RuntimeAdmissionAuthority other = new RuntimeAdmissionAuthority(f.mutex);
        RuntimeAdmissionAuthority.AvailabilityPublication available = f.authority.beginAvailability(INTERNAL);
        check(!other.completeAvailability(available, "generation1", 7, true), "Foreign authority consumed publication");
        check(f.authority.completeAvailability(available, "generation1", 7, true), "Own publication was consumed by foreign completion");
        check(!f.authority.completeAvailability(available, "generation1", 8, true), "Availability token was reused");
        RuntimeAdmissionAuthority.AuthorizationPublication grant = f.authority.beginAuthorization(INTERNAL, RuntimeOrigin.LOCAL_UI);
        check(!other.completeAuthorization(grant, 99, true), "Foreign authority consumed grant");
        check(f.authority.completeAuthorization(grant, 99, true), "Own grant was consumed by foreign completion");
        check(!f.authority.completeAuthorization(grant, 100, true), "Authorization token was reused");
        equal(Result.OK, f.reserve(INTERNAL).result);
    }
    static void configurationChangeInvalidatesPendingAuthorization() {
        Fixture f = ready();
        RuntimeAdmissionAuthority.AuthorizationPublication pending = f.authority.beginAuthorization(INTERNAL, RuntimeOrigin.LOCAL_UI);
        publishAvailability(f.authority, INTERNAL, "generation1", 8, true);
        check(!f.authority.completeAuthorization(pending, 99, true), "Grant borrowed a newer profile observation");
        equal(Result.AUTHORITY_CHANGED, f.reserve(INTERNAL).result);
        publishAuthorization(f.authority, INTERNAL, RuntimeOrigin.LOCAL_UI, 99, true);
        equal(8, admitted(f.reserve(INTERNAL)).identity.initialConfigurationRevision);
    }
    static void freshPublicationCannotRegressAcknowledgedConfiguration() {
        Fixture f = ready(); publishAvailability(f.authority, INTERNAL, "generation1", 9, true);
        RuntimeAdmissionAuthority.AvailabilityPublication staleRead = f.authority.beginAvailability(INTERNAL);
        check(!f.authority.completeAvailability(staleRead, "generation1", 8, true), "Stale store read regressed configuration");
        check(!f.authority.completeAvailability(staleRead, "generation1", 9, true), "Refused publication was reused");
        equal(Result.AUTHORITY_CHANGED, f.reserve(INTERNAL).result);
        publishAvailability(f.authority, INTERNAL, "generation1", 9, true);
        publishAuthorization(f.authority, INTERNAL, RuntimeOrigin.LOCAL_UI, 99, true);
        equal(9, admitted(f.reserve(INTERNAL)).identity.initialConfigurationRevision);
    }
    static void delayedWorkerCannotRacePastNewRevocation() {
        Fixture f = ready();
        RuntimeAdmissionAuthority.AuthorizationPublication pending = f.authority.beginAuthorization(INTERNAL, RuntimeOrigin.LOCAL_UI);
        CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Boolean> completed = new AtomicReference<>(); AtomicReference<Throwable> error = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { reading.countDown(); await(release); completed.set(f.authority.completeAuthorization(pending, 99, true)); }
            catch (Throwable failure) { error.set(failure); }
        });
        worker.start(); await(reading);
        publishAuthorization(f.authority, INTERNAL, RuntimeOrigin.LOCAL_UI, 100, false);
        release.countDown(); join(worker); rethrow(error.get());
        equal(Boolean.FALSE, completed.get());
        equal(Result.AUTHORITY_CHANGED, f.reserve(INTERNAL).result);
    }
    static void delayedEnableWorkerCannotRacePastNewDisable() {
        Fixture f = ready(); RuntimeAdmissionAuthority.AvailabilityPublication pending = f.authority.beginAvailability(INTERNAL);
        CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Boolean> completed = new AtomicReference<>(); AtomicReference<Throwable> error = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { reading.countDown(); await(release); completed.set(f.authority.completeAvailability(pending, "generation1", 7, true)); }
            catch (Throwable failure) { error.set(failure); }
        });
        worker.start(); await(reading); publishAvailability(f.authority, INTERNAL, "generation1", 7, false);
        release.countDown(); join(worker); rethrow(error.get());
        equal(Boolean.FALSE, completed.get()); equal(Result.AUTHORITY_CHANGED, f.reserve(INTERNAL).result);
    }
    static void remotePrincipalRevocationCoversEveryCommand() {
        Fixture f = ready(); RuntimeOrigin first = remote("00000000-0000-4000-8000-000000000003", 91);
        RuntimeOrigin second = remote("00000000-0000-4000-8000-000000000004", 91);
        publishAuthorization(f.authority, RuntimeTarget.DEVICE, first, 91, true);
        RuntimeAdmissionAuthority.Witness old = f.authority.capture(RuntimeTarget.DEVICE, second);
        publishAuthorization(f.authority, RuntimeTarget.DEVICE, second, 92, false);
        equal(Result.AUTHORITY_CHANGED, f.coordinator.reserve(old, COMPONENTS).result);
        equal(Result.AUTHORITY_CHANGED, f.coordinator.reserve(f.authority.capture(RuntimeTarget.DEVICE, first), COMPONENTS).result);
        publishAuthorization(f.authority, RuntimeTarget.DEVICE, second, 92, true);
        equal(Result.AUTHORITY_CHANGED, f.coordinator.reserve(f.authority.capture(RuntimeTarget.DEVICE, first), COMPONENTS).result);
        RuntimeOrigin current = remote("00000000-0000-4000-8000-000000000005", 92);
        equal(Result.OK, f.coordinator.reserve(f.authority.capture(RuntimeTarget.DEVICE, current), COMPONENTS).result);
    }

    static void startAuthorityRevocationDoesNotStopActiveOwner() {
        Fixture f = ready(); Run run = f.start(INTERNAL);
        f.authority.beginAuthorization(INTERNAL, RuntimeOrigin.LOCAL_UI);
        equal(Phase.ACTIVE, f.coordinator.observe().effectivePhase());
        try (OwnedResourceRegistry.EffectPermit ignored = run.registry.acquireEffectPermit(run.lease, Effect.NORMAL)) {
            check(ignored != null, "Start-authority revocation ended an admitted session");
        }
        // Removal is a separate explicit owner stop, not an incidental workspace-lock event.
        equal(Result.OK, f.coordinator.requestStop(run.lease));
        deny(() -> run.registry.acquireEffectPermit(run.lease, Effect.NORMAL));
        run.closeComponents();
        equal(Result.OK, f.coordinator.completeStop(run.lease, run.registry.drained(run.lease)));
        equal(Result.AUTHORITY_CHANGED, f.reserve(INTERNAL).result);
    }
    static void authorizationDoesNotCrossRemotePrincipals() {
        Fixture f = ready(); RuntimeOrigin first = remote("00000000-0000-4000-8000-000000000003", 91);
        RuntimeOrigin otherPeer = RuntimeOrigin.remote(new RuntimeOrigin.RemoteProof("d".repeat(64), "peer_generation",
                uuid(), "b".repeat(64), "c".repeat(64), 91, uuid(), "", ""));
        RuntimeOrigin otherGeneration = RuntimeOrigin.remote(new RuntimeOrigin.RemoteProof("a".repeat(64), "new_generation",
                uuid(), "b".repeat(64), "c".repeat(64), 91, uuid(), "", ""));
        publishAuthorization(f.authority, RuntimeTarget.DEVICE, first, 91, true);
        equal(Result.AUTHORITY_CHANGED, f.coordinator.reserve(f.authority.capture(RuntimeTarget.DEVICE, otherPeer), COMPONENTS).result);
        equal(Result.AUTHORITY_CHANGED, f.coordinator.reserve(f.authority.capture(RuntimeTarget.DEVICE, otherGeneration), COMPONENTS).result);
        equal(Result.OK, f.coordinator.reserve(f.authority.capture(RuntimeTarget.DEVICE, first), COMPONENTS).result);
    }

    private static void publishAvailability(RuntimeAdmissionAuthority authority, RuntimeTarget target,
            String generation, long configurationRevision, boolean enabled) {
        check(authority.completeAvailability(authority.beginAvailability(target), generation, configurationRevision, enabled),
                "Current availability publication unexpectedly refused");
    }
    private static void publishAuthorization(RuntimeAdmissionAuthority authority, RuntimeTarget target,
            RuntimeOrigin origin, long revision, boolean allowed) {
        check(authority.completeAuthorization(authority.beginAuthorization(target, origin), revision, allowed),
                "Current authorization publication unexpectedly refused");
    }

    private static Fixture ready() { Fixture f = new Fixture(); equal(Result.OK, f.coordinator.reconcileStartup()); return f; }
    private static RuntimeLease admitted(Admission admission) {
        equal(Result.OK, admission.result); check(admission.lease != null, "Acknowledged admission lacks capability"); return admission.lease;
    }
    private static String uuid() { return UUID.randomUUID().toString(); }
    private static RuntimeLease.Identity identity(RuntimeTarget target, String process) {
        return new RuntimeLease.Identity(target, RuntimeOrigin.LOCAL_UI, "generation1", uuid(), uuid(), process, 7);
    }
    private static RuntimeOrigin remote(String command, long authorityRevision) {
        return RuntimeOrigin.remote(new RuntimeOrigin.RemoteProof("a".repeat(64), "peer_generation", command,
                "b".repeat(64), "c".repeat(64), authorityRevision, uuid(), "", ""));
    }
    private static final class Fixture {
        final RuntimeMutex mutex = new RuntimeMutex(new Object());
        final FakeBackend backend = new FakeBackend();
        final RuntimeOwnerStore store = new RuntimeOwnerStore(mutex, backend);
        final RuntimeAdmissionAuthority authority = new RuntimeAdmissionAuthority(mutex);
        final AtomicReference<ActiveRuntimeCoordinator.Occupancy> occupancy = new AtomicReference<>(ActiveRuntimeCoordinator.Occupancy.CLEAR);
        final ActiveRuntimeCoordinator coordinator = new ActiveRuntimeCoordinator(store, authority, occupancy::get, PROCESS);
        Fixture() {
            publishAvailability(authority, INTERNAL, "generation1", 7, true);
            publishAvailability(authority, RuntimeTarget.DEVICE, "device_generation", 3, true);
            publishAuthorization(authority, INTERNAL, RuntimeOrigin.LOCAL_UI, 99, true);
            publishAuthorization(authority, RuntimeTarget.DEVICE, RuntimeOrigin.LOCAL_UI, 99, true);
        }
        Admission reserve(RuntimeTarget target) { return coordinator.reserve(authority.capture(target, RuntimeOrigin.LOCAL_UI), COMPONENTS); }
        Run start(RuntimeTarget target) {
            RuntimeLease lease = admitted(reserve(target));
            equal(Result.OK, coordinator.beginStarting(lease, ignored -> true));
            Run run = new Run(lease, coordinator.resources(lease));
            for (String name : COMPONENTS) {
                OwnedResourceRegistry.Registration component = coordinator.registerComponent(lease, name);
                run.components.add(component); equal(Result.OK, coordinator.componentReady(lease, component));
            }
            equal(Result.OK, coordinator.activate(lease)); return run;
        }
        void finish(Run run) {
            equal(Result.OK, coordinator.requestStop(run.lease)); run.closeComponents();
            equal(Result.OK, coordinator.completeStop(run.lease, run.registry.drained(run.lease)));
        }
    }
    /** Failure-injection fake only; this intentionally makes NO durability claim. */
    private static final class FakeBackend implements RuntimeOwnerStore.Backend {
        Record record = Record.idle(0);
        Outcome next = Outcome.COMMITTED;
        boolean applyUncertain, failRead;
        int writes;
        Runnable beforeWrite;
        @Override public Record read() {
            if (failRead) throw new IllegalStateException("Unreadable owner record"); return record;
        }
        @Override public Outcome compareAndSet(Record expected, Record update) {
            writes++;
            if (beforeWrite != null) beforeWrite.run();
            if (!record.equals(expected)) return Outcome.CONFLICT;
            Outcome result = next; next = Outcome.COMMITTED;
            if (result == Outcome.COMMITTED || result == Outcome.UNCERTAIN && applyUncertain) record = update;
            return result;
        }
    }
    private static final class Run {
        final RuntimeLease lease;
        final OwnedResourceRegistry registry;
        final List<OwnedResourceRegistry.Registration> components = new ArrayList<>();
        Run(RuntimeLease lease, OwnedResourceRegistry registry) { this.lease = lease; this.registry = registry; }
        void closeComponents() { for (OwnedResourceRegistry.Registration value : components) value.close(); }
    }
    private interface Throwing { void run() throws Exception; }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + "; got " + actual);
    }
    private static void equal(long expected, long actual) {
        if (expected != actual) throw new AssertionError("Expected " + expected + "; got " + actual);
    }
    private static void expect(Class<? extends Throwable> type, Throwing work) {
        try { work.run(); }
        catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("Expected " + type.getSimpleName() + ", got " + failure, failure);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    private static void deny(Throwing work) { expect(SecurityException.class, work); }
    private static void await(CountDownLatch latch) {
        try { check(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for deterministic test boundary"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
    private static void join(Thread thread) {
        try { thread.join(5_000); check(!thread.isAlive(), "Worker did not complete"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
    private static void rethrow(Throwable failure) { if (failure != null) throw new AssertionError(failure); }

    public static void main(String[] args) throws Exception {
        int passed = 0; List<String> failures = new ArrayList<>();
        for (java.lang.reflect.Method method : RuntimeFoundationChecks.class.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0
                    || method.getReturnType() != void.class || java.lang.reflect.Modifier.isPrivate(method.getModifiers())) continue;
            try { method.invoke(null); System.out.println("PASS " + method.getName()); passed++; }
            catch (java.lang.reflect.InvocationTargetException failed) {
                failures.add(method.getName() + ": " + failed.getCause()); failed.getCause().printStackTrace(System.err);
            }
        }
        System.out.println("Runtime foundation checks: " + passed + " passed, " + failures.size() + " failed");
        if (!failures.isEmpty()) throw new AssertionError(String.join("\n", failures));
    }
}
