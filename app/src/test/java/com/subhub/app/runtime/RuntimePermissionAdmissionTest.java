package com.subhub.app.runtime;

import static org.junit.Assert.*;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.Test;

/** Actual core/gateway: no Android, no synthetic permission or journal completion shortcuts. */
public final class RuntimePermissionAdmissionTest {
    @Test public void revokedAuthorizationRejectsAlreadyIssuedPrompt() {
        Fixture f = new Fixture(); var p = f.prompt();
        f.authority.beginAuthorization(f.target, RuntimeOrigin.LOCAL_UI);
        assertFalse(f.coordinator.acceptsPermissionResult(f.attempt.lease()));
        assertFalse(p.launch(() -> fail("Stale authority launched")));
        assertTrue(f.attempt.promptsReturned()); f.worker.drain(); assertFalse(f.coordinator.anyOccupied());
    }
    @Test public void stoppedLeaseRejectsAlreadyIssuedPrompt() {
        Fixture f = new Fixture(); var p = f.prompt();
        assertEquals(ActiveRuntimeCoordinator.Result.OK, f.coordinator.requestStop(f.attempt.lease()));
        assertFalse(p.launch(() -> fail("Stopped lease launched"))); f.worker.drain();
        assertFalse(f.coordinator.anyOccupied());
    }
    @Test public void availabilityReplacementNeverRevivesOldPromptEvenWithSameRevision() {
        Fixture f = new Fixture(); var p = f.prompt();
        assertTrue(f.authority.completeAvailability(f.authority.beginAvailability(f.target), "generation", 1, true));
        assertTrue(f.authority.completeAuthorization(f.authority.beginAuthorization(f.target, RuntimeOrigin.LOCAL_UI), 1, true));
        assertFalse(p.launch(() -> fail("Republished availability revived old witness")));
    }
    @Test public void authorizationReplacementNeverRevivesOldPromptEvenWithSameRevision() {
        Fixture f = new Fixture(); var p = f.prompt();
        assertTrue(f.authority.completeAuthorization(f.authority.beginAuthorization(f.target, RuntimeOrigin.LOCAL_UI), 1, true));
        assertFalse(p.launch(() -> fail("Republished authorization revived old witness")));
    }
    @Test public void anotherTargetsRevocationDoesNotInvalidateExactPrompt() {
        Fixture f = new Fixture(); var p = f.prompt();
        f.authority.beginAvailability(RuntimeTarget.DEVICE);
        f.authority.beginAuthorization(RuntimeTarget.DEVICE, RuntimeOrigin.LOCAL_UI);
        assertTrue(p.launch(() -> {})); assertTrue(p.resultReturned());
    }
    @Test public void issuedButUnlaunchedPromptAlreadyBlocksCoreDrain() {
        Fixture f = new Fixture(); var p = f.prompt();
        assertEquals(ActiveRuntimeCoordinator.Result.OK, f.coordinator.requestStop(f.attempt.lease()));
        assertNull(f.registry.drained(f.attempt.lease()));
        assertTrue(f.gateway.cancel(f.attempt)); f.worker.drain();
        assertFalse(f.coordinator.anyOccupied()); assertFalse(p.launch(() -> fail("Retired prompt")));
    }
    @Test public void startingCannotBypassIssuedPromptObligation() {
        Fixture f = new Fixture(); var p = f.prompt();
        assertEquals(ActiveRuntimeCoordinator.Result.NOT_READY, f.coordinator.beginStarting(f.attempt.lease(), owner -> true));
        assertEquals(RuntimeOwnerStore.Phase.RESERVED, f.backend.value.phase);
        assertTrue(p.launch(() -> {})); assertTrue(p.resultReturned());
        assertNotNull(f.await(f.gateway.begin(f.attempt, owner -> true)));
    }
    @Test public void reservedPermissionDoesNotAdmitGeneralEffectsOrWork() {
        Fixture f = new Fixture(); f.prompt();
        for (OwnedResourceRegistry.Effect effect : OwnedResourceRegistry.Effect.values())
            assertThrows(SecurityException.class, () -> f.registry.acquireEffectPermit(f.attempt.lease(), effect));
        assertThrows(SecurityException.class, () -> f.coordinator.registerWork(f.attempt.lease(), "not-a-permission"));
    }
    @Test public void uncertainCommittedStopRecoveryRetainsPromptUntilExactResult() {
        Fixture f = new Fixture(); var p = f.prompt(); assertTrue(p.launch(() -> {}));
        f.backend.uncertain = true; f.gateway.cancel(f.attempt); f.worker.drain();
        assertEquals(ActiveRuntimeCoordinator.Result.NOT_READY, f.coordinator.reconcilePersistence());
        assertEquals(RuntimeOwnerStore.Phase.STOPPING, f.backend.value.phase);
        assertTrue(f.coordinator.anyOccupied()); assertNull(f.registry.drained(f.attempt.lease()));
        assertFalse(p.resultReturned()); f.worker.drain(); assertFalse(f.coordinator.anyOccupied());
    }
    @Test public void definiteFailedStopRetainsPromptAndRetriesWithoutLosingCallback() {
        Fixture f = new Fixture(); var p = f.prompt(); p.launch(() -> {});
        f.backend.refuse = true; f.gateway.cancel(f.attempt); f.worker.drain();
        assertEquals(RuntimeOwnerStore.Phase.RESERVED, f.backend.value.phase);
        assertTrue(f.coordinator.anyOccupied()); assertNull(f.registry.drained(f.attempt.lease()));
        assertFalse(p.resultReturned()); f.worker.drain(); assertFalse(f.coordinator.anyOccupied());
    }
    @Test public void stopAndRecoveryCannotReleaseLaunchWhoseResultArrivedInline() throws Exception {
        Fixture f = new Fixture(); var p = f.prompt();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        FutureTask<Boolean> launch = new FutureTask<>(() -> p.launch(() -> {
            assertTrue(p.resultReturned()); entered.countDown(); awaitLatch(release);
        }));
        Thread thread = new Thread(launch, "permission-launch"); thread.start();
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            f.backend.uncertain = true; f.gateway.cancel(f.attempt); f.worker.drain();
            assertEquals(ActiveRuntimeCoordinator.Result.NOT_READY, f.coordinator.reconcilePersistence());
            assertTrue(f.coordinator.anyOccupied()); assertFalse(f.attempt.promptsReturned());
        } finally { release.countDown(); thread.join(3000); }
        assertTrue(launch.get(3, TimeUnit.SECONDS)); f.worker.drain(); assertFalse(f.coordinator.anyOccupied());
    }
    @Test public void actualLaunchDoesNotWaitForUnrelatedRuntimeMutexOwner() throws Exception {
        Fixture f = new Fixture(); var p = f.prompt();
        withBlockedRuntime(f, () -> assertTrue(onOtherThread(() -> p.launch(() -> {}))));
        assertTrue(p.resultReturned());
    }
    @Test public void revokedLaunchDoesNotWaitForBlockedStoppingJournal() throws Exception {
        Fixture f = new Fixture(); var p = f.prompt();
        f.backend.block = true;
        FutureTask<ActiveRuntimeCoordinator.Result> stop = new FutureTask<>(() -> f.coordinator.requestStop(f.attempt.lease()));
        Thread thread = new Thread(stop, "stopping-journal"); thread.start();
        try {
            assertTrue(f.backend.entered.await(3, TimeUnit.SECONDS));
            assertFalse(onOtherThread(() -> p.launch(() -> { throw new AssertionError("Stopped prompt launched"); })));
        } finally { f.backend.release.countDown(); thread.join(3000); }
        assertEquals(ActiveRuntimeCoordinator.Result.OK, stop.get(3, TimeUnit.SECONDS));
        f.worker.drain(); assertFalse(f.coordinator.anyOccupied());
    }
    @Test public void promptResultAndLaunchReturnDoNotWaitForBlockedStoppingJournal() throws Exception {
        Fixture f = new Fixture(); var p = f.prompt();
        CountDownLatch launched = new CountDownLatch(1), returnLaunch = new CountDownLatch(1);
        FutureTask<Boolean> launch = new FutureTask<>(() -> p.launch(() -> { launched.countDown(); awaitLatch(returnLaunch); }));
        Thread launchThread = new Thread(launch, "launch-return"); launchThread.start();
        assertTrue(launched.await(3, TimeUnit.SECONDS)); f.backend.block = true;
        FutureTask<ActiveRuntimeCoordinator.Result> stop = new FutureTask<>(() -> f.coordinator.requestStop(f.attempt.lease()));
        Thread stopThread = new Thread(stop, "stopping-journal"); stopThread.start();
        try {
            assertTrue(f.backend.entered.await(3, TimeUnit.SECONDS));
            // Terminal return is nonblocking and its cached sealed gate rejects the stale grant.
            assertFalse(onOtherThread(p::resultReturned));
            returnLaunch.countDown(); assertTrue(launch.get(1, TimeUnit.SECONDS));
            assertTrue(f.attempt.promptsReturned());
        } finally {
            returnLaunch.countDown(); f.backend.release.countDown();
            launchThread.join(3000); stopThread.join(3000);
        }
        assertEquals(ActiveRuntimeCoordinator.Result.OK, stop.get(3, TimeUnit.SECONDS));
        assertFalse(f.await(f.gateway.permissionResult(f.attempt, p, true)));
        f.gateway.cancel(f.attempt); f.worker.drain(); assertFalse(f.coordinator.anyOccupied());
    }
    @Test public void refusedAuthorityBeforePermissionIssuanceAddsNoPhantomObligation() {
        Fixture f = new Fixture(); f.authority.beginAvailability(f.target);
        CompletableFuture<RuntimeStartAttempt.Prompt> future = f.gateway.permission(f.attempt, RuntimeStartAttempt.Permission.OVERLAY);
        f.worker.drain(); assertTrue(future.isCompletedExceptionally()); assertFalse(f.coordinator.anyOccupied());
    }
    private static <T> T onOtherThread(Supplier<T> action) {
        FutureTask<T> value = new FutureTask<>(action::get); Thread thread = new Thread(value, "simulated-main"); thread.start();
        try { return value.get(1, TimeUnit.SECONDS); }
        catch (Exception failure) { throw new AssertionError("Main operation blocked/failed", failure); }
    }
    private static void withBlockedRuntime(Fixture f, Runnable action) throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread holder = new Thread(() -> f.mutex.runtime(() -> { entered.countDown(); awaitLatch(release); return null; }));
        holder.start();
        try { assertTrue(entered.await(3, TimeUnit.SECONDS)); action.run(); }
        finally { release.countDown(); holder.join(3000); }
    }
    private static void awaitLatch(CountDownLatch latch) {
        try { if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("Test latch timeout"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    static final class Backend implements RuntimeOwnerStore.Backend {
        RuntimeOwnerStore.Record value = RuntimeOwnerStore.Record.idle(0);
        volatile boolean uncertain, refuse, block;
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        public RuntimeOwnerStore.Record read() { return value; }
        public RuntimeOwnerStore.Outcome compareAndSet(RuntimeOwnerStore.Record expected, RuntimeOwnerStore.Record next) {
            if (!expected.equals(value)) return RuntimeOwnerStore.Outcome.CONFLICT;
            if (block) { block = false; entered.countDown(); awaitLatch(release); }
            if (refuse) { refuse = false; return RuntimeOwnerStore.Outcome.NOT_COMMITTED; }
            value = next;
            if (uncertain) { uncertain = false; return RuntimeOwnerStore.Outcome.UNCERTAIN; }
            return RuntimeOwnerStore.Outcome.COMMITTED;
        }
    }
    static final class Fixture {
        final RuntimeMutex mutex = new RuntimeMutex(new Object());
        final Backend backend = new Backend();
        final RuntimeAdmissionAuthority authority = new RuntimeAdmissionAuthority(mutex);
        final RuntimeTarget target = RuntimeTarget.internal("dummy_sub_preview");
        final ActiveRuntimeCoordinator coordinator = new ActiveRuntimeCoordinator(new RuntimeOwnerStore(mutex, backend), authority,
                () -> ActiveRuntimeCoordinator.Occupancy.CLEAR, UUID.randomUUID().toString());
        final RuntimeStartGatewayTest.Queue worker = new RuntimeStartGatewayTest.Queue();
        final RuntimeStartGateway gateway = new RuntimeStartGateway(coordinator, worker, () -> {});
        final RuntimeStartAttempt attempt;
        final OwnedResourceRegistry registry;
        Fixture() {
            authority.completeAvailability(authority.beginAvailability(target), "generation", 1, true);
            authority.completeAuthorization(authority.beginAuthorization(target, RuntimeOrigin.LOCAL_UI), 1, true);
            assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.reconcileStartup());
            attempt = await(gateway.reserve(authority.capture(target, RuntimeOrigin.LOCAL_UI), Set.of("capture"))).attempt;
            registry = coordinator.resources(attempt.lease());
        }
        RuntimeStartAttempt.Prompt prompt() { return await(gateway.permission(attempt, RuntimeStartAttempt.Permission.PROJECTION)); }
        <T> T await(CompletableFuture<T> future) { worker.drain(); return future.join(); }
    }
}
