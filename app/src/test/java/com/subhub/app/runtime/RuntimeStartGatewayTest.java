package com.subhub.app.runtime;

import static org.junit.Assert.*;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import org.junit.Test;

public final class RuntimeStartGatewayTest {
    @Test public void demoStartInputCannotUseDeviceOrMissingRevision() {
        String generation = UUID.randomUUID().toString();
        assertThrows(IllegalArgumentException.class,
                () -> new AppRuntimeGateway.InternalStartSpec("local", generation, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new AppRuntimeGateway.InternalStartSpec("demo", generation, -1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new AppRuntimeGateway.InternalStartSpec("demo", "not-a-generation", 1, 1));
    }
    @Test public void internalProjectionReservesPixelAndModuleComponents() {
        AndroidRuntimeFactory.InternalStartPlan plan = AndroidRuntimeFactory.InternalStartPlan.select(
                true, com.subhub.app.settings.CaptureMethod.SCREEN_RECORDING, 35);
        assertTrue(plan.requiresProjection());
        assertTrue(plan.components.contains(com.subhub.app.service.InternalLegacyProjectionCapture.COMPONENT));
        assertTrue(plan.components.contains(com.subhub.app.service.OwnedProjectionBinding.COMPONENT));
        assertTrue(plan.components.contains(com.subhub.app.service.InternalAccessibilityModules.LIMITS));
    }
    @Test public void internalPlanNeverSubstitutesModuleOnlyForAnUnreadyPixelSource() {
        assertThrows(IllegalStateException.class, () -> AndroidRuntimeFactory.InternalStartPlan.select(
                true, com.subhub.app.settings.CaptureMethod.APP_MODE, 35));
        AndroidRuntimeFactory.InternalStartPlan modules = AndroidRuntimeFactory.InternalStartPlan.select(
                false, com.subhub.app.settings.CaptureMethod.SCREEN_RECORDING, 26);
        assertEquals(com.subhub.app.service.RuntimeCaptureHost.Mode.ACCESSIBILITY_MODULES, modules.source.mode);
        assertFalse(modules.requiresProjection());
        assertFalse(modules.components.contains(com.subhub.app.service.OwnedProjectionBinding.COMPONENT));
    }
    @Test public void reservedDeviceBlocksInternalBeforeAnyPrompt() {
        Fixture f = new Fixture(); RuntimeStartGateway.Admission device = f.reserve(RuntimeTarget.DEVICE);
        assertEquals(ActiveRuntimeCoordinator.Result.OK, device.result);
        assertEquals(RuntimeOwnerStore.Phase.RESERVED, f.backend.record.phase);
        assertEquals(ActiveRuntimeCoordinator.Result.OCCUPIED, f.reserve(f.internal).result);
    }
    @Test public void cancelQueuedPromptDoesNoInlineJournalWriteAndPreventsLaunch() {
        Fixture f = new Fixture(); RuntimeStartAttempt attempt = f.reserve(f.internal).attempt;
        RuntimeStartAttempt.Prompt prompt = f.await(f.gateway.permission(attempt, RuntimeStartAttempt.Permission.PROJECTION));
        int writes = f.backend.writes; assertTrue(f.gateway.cancel(attempt));
        assertEquals(writes, f.backend.writes); assertFalse(prompt.launch(() -> fail("Cancelled")));
        f.worker.drain(); assertEquals(RuntimeOwnerStore.Phase.IDLE, f.backend.record.phase);
    }
    @Test public void admittedPromptKeepsStoppingUntilExactLateResultReturns() {
        Fixture f = new Fixture(); RuntimeStartAttempt attempt = f.reserve(f.internal).attempt;
        RuntimeStartAttempt.Prompt prompt = f.await(f.gateway.permission(attempt, RuntimeStartAttempt.Permission.PROJECTION));
        prompt.launch(() -> {}); f.gateway.cancel(attempt); f.worker.drain();
        assertEquals(RuntimeOwnerStore.Phase.STOPPING, f.backend.record.phase);
        assertEquals(ActiveRuntimeCoordinator.Result.OCCUPIED, f.reserve(RuntimeTarget.DEVICE).result);
        assertFalse(f.await(f.gateway.permissionResult(attempt, prompt, true)));
        f.worker.drain(); assertEquals(RuntimeOwnerStore.Phase.IDLE, f.backend.record.phase);
    }
    @Test public void authorityRevocationDuringPromptCannotStart() {
        Fixture f = new Fixture(); RuntimeStartAttempt attempt = f.reserve(f.internal).attempt;
        RuntimeStartAttempt.Prompt prompt = f.await(f.gateway.permission(attempt, RuntimeStartAttempt.Permission.OVERLAY));
        prompt.launch(() -> {}); f.authority.beginAuthorization(f.internal, RuntimeOrigin.LOCAL_UI);
        assertFalse(f.await(f.gateway.permissionResult(attempt, prompt, true)));
        f.worker.drain(); assertEquals(RuntimeOwnerStore.Phase.IDLE, f.backend.record.phase);
    }
    @Test public void hidingAndReaddingAfterPermissionRequiresFreshStartAttempt() {
        Fixture f = new Fixture();
        RuntimeStartAttempt old = f.reserve(f.internal).attempt;
        RuntimeStartAttempt.Prompt prompt = f.await(f.gateway.permission(old, RuntimeStartAttempt.Permission.PROJECTION));
        assertTrue(prompt.launch(() -> {}));
        assertTrue(f.await(f.gateway.permissionResult(old, prompt, true)));

        assertTrue(f.authority.completeAvailability(f.authority.beginAvailability(f.internal), "hidden", 2, false));
        assertTrue(f.authority.completeAvailability(f.authority.beginAvailability(f.internal), "readded", 3, true));
        assertTrue(f.authority.completeAuthorization(f.authority.beginAuthorization(f.internal, RuntimeOrigin.LOCAL_UI), 1, true));

        CompletableFuture<RuntimeStartGateway.Starting> retired = f.gateway.begin(old, ignored -> {
            fail("Retired generation must not reach readiness");
            return true;
        });
        CompletionException failure = assertThrows(CompletionException.class, () -> f.await(retired));
        assertTrue(failure.getCause() instanceof IllegalStateException);
        assertTrue(old.revoked());
        assertEquals(RuntimeOwnerStore.Phase.IDLE, f.backend.record.phase);

        RuntimeStartGateway.Admission fresh = f.reserve(f.internal);
        assertEquals(ActiveRuntimeCoordinator.Result.OK, fresh.result);
        assertNotSame(old.lease(), fresh.attempt.lease());
        assertEquals("readded", fresh.attempt.lease().identity.profileGeneration);
        assertFalse(f.await(f.gateway.permissionResult(old, prompt, true)));
        assertEquals(RuntimeOwnerStore.Phase.RESERVED, f.backend.record.phase);
        assertEquals(fresh.attempt.lease().identity, f.backend.record.owner);
    }
    @Test public void readinessTransferPreventsOldActivityFromStoppingStartedOwner() {
        Fixture f = new Fixture(); RuntimeStartAttempt attempt = f.reserve(f.internal).attempt;
        RuntimeStartAttempt.Prompt prompt = f.await(f.gateway.permission(attempt, RuntimeStartAttempt.Permission.PROJECTION));
        prompt.launch(() -> {}); assertTrue(f.await(f.gateway.permissionResult(attempt, prompt, true)));
        RuntimeStartGateway.Starting started = f.await(f.gateway.begin(attempt, ignored -> true));
        assertSame(attempt.lease(), started.lease); assertEquals(RuntimeOwnerStore.Phase.STARTING, f.backend.record.phase);
        assertFalse(f.gateway.cancel(attempt)); assertFalse(attempt.revoked());
        assertFalse(f.await(f.gateway.permissionResult(attempt, prompt, true)));
    }
    @Test public void missingFreshReadinessAbortsRatherThanArming() {
        Fixture f = new Fixture(); RuntimeStartAttempt attempt = f.reserve(f.internal).attempt;
        CompletableFuture<RuntimeStartGateway.Starting> start = f.gateway.begin(attempt, ignored -> false); f.worker.drain();
        assertTrue(start.isCompletedExceptionally()); assertEquals(RuntimeOwnerStore.Phase.IDLE, f.backend.record.phase);
    }
    @Test public void inlineCompletedThenThrowNeverPublishesReservationSuccess() {
        Fixture f = new Fixture(); RuntimeStartGateway broken = new RuntimeStartGateway(f.coordinator,
                action -> { action.run(); throw new IllegalStateException("Dispatcher failed after return"); }, () -> {});
        CompletableFuture<RuntimeStartGateway.Admission> result = broken.reserve(f.witness(f.internal), Set.of("capture"));
        assertTrue(result.isCompletedExceptionally()); assertTrue(broken.needsRecovery());
        assertEquals(RuntimeOwnerStore.Phase.RESERVED, f.backend.record.phase);
        assertTrue(f.coordinator.observe().effectsSealed); assertTrue(f.coordinator.anyOccupied());
    }
    @Test public void queuedThenThrowLateCallbackCannotMintAnUnreportedLease() {
        Fixture f = new Fixture(); ArrayDeque<Runnable> pending = new ArrayDeque<>();
        RuntimeStartGateway broken = new RuntimeStartGateway(f.coordinator,
                action -> { pending.add(action); throw new IllegalStateException("Maybe accepted"); }, () -> {});
        CompletableFuture<RuntimeStartGateway.Admission> result = broken.reserve(f.witness(f.internal), Set.of("capture"));
        assertTrue(result.isCompletedExceptionally()); assertTrue(broken.needsRecovery()); pending.remove().run();
        assertEquals(RuntimeOwnerStore.Phase.IDLE, f.backend.record.phase);
    }
    @Test public void duplicateWorkerDeliveryCannotReserveTwice() {
        Fixture f = new Fixture(); RuntimeStartGateway duplicated = new RuntimeStartGateway(f.coordinator,
                action -> { action.run(); action.run(); }, () -> {});
        RuntimeStartGateway.Admission result = duplicated.reserve(f.witness(f.internal), Set.of("capture")).join();
        assertEquals(ActiveRuntimeCoordinator.Result.OK, result.result); assertEquals(1, f.backend.writes);
    }
    @Test public void gatewayDoesNotInventStartupReconciliation() {
        Fixture f = new Fixture(false); assertEquals(ActiveRuntimeCoordinator.Result.STARTUP_RECONCILIATION_REQUIRED,
                f.reserve(f.internal).result); assertEquals(0, f.backend.writes);
    }
    @Test public void withdrawnQueuedResponseDoesNotReserve() {
        Fixture f = new Fixture(); CompletableFuture<RuntimeStartGateway.Admission> pending =
                f.gateway.reserve(f.witness(f.internal), Set.of("capture"));
        pending.cancel(false); f.worker.drain(); assertEquals(RuntimeOwnerStore.Phase.IDLE, f.backend.record.phase);
        assertFalse(f.gateway.needsRecovery());
    }
    static final class Queue implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>(); boolean onWorker;
        public void execute(Runnable task) { tasks.add(task); }
        void drain() { while (!tasks.isEmpty()) { onWorker = true; try { tasks.remove().run(); } finally { onWorker = false; } } }
    }
    static final class Backend implements RuntimeOwnerStore.Backend {
        RuntimeOwnerStore.Record record = RuntimeOwnerStore.Record.idle(0); int writes;
        public RuntimeOwnerStore.Record read() { return record; }
        public RuntimeOwnerStore.Outcome compareAndSet(RuntimeOwnerStore.Record expected, RuntimeOwnerStore.Record next) {
            if (!record.equals(expected)) return RuntimeOwnerStore.Outcome.CONFLICT;
            writes++; record = next; return RuntimeOwnerStore.Outcome.COMMITTED;
        }
    }
    static final class Fixture {
        final RuntimeMutex mutex = new RuntimeMutex(new Object()); final Backend backend = new Backend();
        final RuntimeAdmissionAuthority authority = new RuntimeAdmissionAuthority(mutex);
        final RuntimeTarget internal = RuntimeTarget.internal("dummy_sub_preview");
        final ActiveRuntimeCoordinator coordinator = new ActiveRuntimeCoordinator(new RuntimeOwnerStore(mutex, backend), authority,
                () -> ActiveRuntimeCoordinator.Occupancy.CLEAR, UUID.randomUUID().toString());
        final Queue worker = new Queue();
        final RuntimeStartGateway gateway = new RuntimeStartGateway(coordinator, worker,
                () -> { if (!worker.onWorker) throw new IllegalStateException("Journal off owner worker"); });
        Fixture() { this(true); }
        Fixture(boolean reconcile) {
            for (RuntimeTarget target : Set.of(internal, RuntimeTarget.DEVICE)) {
                assertTrue(authority.completeAvailability(authority.beginAvailability(target), "generation", 1, true));
                assertTrue(authority.completeAuthorization(authority.beginAuthorization(target, RuntimeOrigin.LOCAL_UI), 1, true));
            }
            if (reconcile) assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.reconcileStartup());
        }
        RuntimeAdmissionAuthority.Witness witness(RuntimeTarget target) { return authority.capture(target, RuntimeOrigin.LOCAL_UI); }
        RuntimeStartGateway.Admission reserve(RuntimeTarget target) { return await(gateway.reserve(witness(target), Set.of("capture"))); }
        <T> T await(CompletableFuture<T> future) { worker.drain(); return future.join(); }
    }
}
