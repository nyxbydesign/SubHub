package com.subhub.app.runtime;

import static org.junit.Assert.*;
import static com.subhub.app.runtime.ActiveRuntimeCoordinator.Result.*;
import static com.subhub.app.runtime.RuntimeOwnerStore.Outcome.*;
import static com.subhub.app.runtime.RuntimeOwnerStore.Phase.*;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;

/** Real owner-thread tests with a deliberately blocked journal CAS, not an immediate fake executor. */
public final class RuntimePermitConcurrencyTest {
    @Test public void lateDeclinedEffectReturnsWhileStopJournalIsBlocked() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.bind(); f.activate(); f.backend.block(STOPPING, COMMITTED);
            assertEquals(NOT_READY, f.execution.requestStop()); await(f.backend.entered);
            nonblocking(() -> assertFalse(f.execution.runEffect(() -> fail("Stopped effect ran"))));
            nonblocking(() -> assertThrows(SecurityException.class,
                    () -> f.registry.acquireEffectPermit(f.lease, OwnedResourceRegistry.Effect.NORMAL)));
            assertNull(f.close.get()); f.backend.release.countDown(); f.finish();
        }
    }
    @Test public void admittedEffectCanReturnAndReleasePermitDuringBlockedStopCas() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.bind(); f.activate(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            FutureTask<Boolean> effect = new FutureTask<>(() -> f.execution.runEffect(() -> {
                entered.countDown(); await(release);
            }));
            Thread main = new Thread(effect, "simulated-window-callback"); main.start(); await(entered);
            f.backend.block(STOPPING, COMMITTED); f.execution.requestStop(); await(f.backend.entered);
            release.countDown(); assertTrue(effect.get(2, TimeUnit.SECONDS));
            assertNull("No teardown while STOPPING persistence is pending", f.close.get());
            f.backend.release.countDown(); f.finish();
        }
    }
    @Test public void permitAcquireAndReleaseDoNotWaitForConfigurationJournalIo() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.bind(); f.activate(); f.backend.block(ACTIVE, COMMITTED);
            Future<ActiveRuntimeCoordinator.Result> write = f.worker.submit(() -> f.coordinator.offerConfiguration(f.lease, 2, 3));
            await(f.backend.entered);
            nonblocking(() -> {
                try (OwnedResourceRegistry.EffectPermit permit = f.registry.acquireEffectPermit(f.lease, OwnedResourceRegistry.Effect.NORMAL)) {
                    assertNotNull(permit);
                }
            });
            f.backend.release.countDown(); assertEquals(OK, write.get(2, TimeUnit.SECONDS));
            f.execution.requestStop(); f.finish();
        }
    }
    @Test public void activationFencesStartupAndNormalUntilDurableAck() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.bind(); f.backend.block(ACTIVE, COMMITTED);
            Future<ActiveRuntimeCoordinator.Result> activation = f.worker.submit(() -> f.coordinator.activate(f.lease));
            await(f.backend.entered);
            nonblocking(() -> {
                assertFalse(f.execution.runStartupEffect(() -> fail("Startup entered ACTIVATING")));
                assertFalse(f.execution.runEffect(() -> fail("Normal effect preceded ACTIVE acknowledgement")));
            });
            f.backend.release.countDown(); assertEquals(OK, activation.get(2, TimeUnit.SECONDS));
            assertTrue(f.execution.runEffect(() -> { })); f.execution.requestStop(); f.finish();
        }
    }
    @Test public void cancellationDuringActivationCannotReopenNormalEffects() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.bind(); f.backend.block(ACTIVE, COMMITTED);
            Future<ActiveRuntimeCoordinator.Result> activation = f.worker.submit(() -> f.coordinator.activate(f.lease));
            await(f.backend.entered);
            nonblocking(() -> assertEquals(NOT_READY, f.execution.requestStop()));
            nonblocking(() -> assertThrows(SecurityException.class,
                    () -> f.registry.acquireEffectPermit(f.lease, OwnedResourceRegistry.Effect.NORMAL)));
            f.backend.release.countDown(); assertEquals(WRONG_PHASE, activation.get(2, TimeUnit.SECONDS));
            assertFalse(f.execution.runEffect(() -> fail("Cancelled activation reopened")));
            assertThrows(SecurityException.class, () -> f.registry.acquireEffectPermit(f.lease, OwnedResourceRegistry.Effect.NORMAL));
            f.finish();
        }
    }
    @Test public void cancelledStartingCasNeverPublishesStartupCapability() throws Exception {
        try (Fixture f = new Fixture(false)) {
            f.backend.block(STARTING, COMMITTED);
            Future<ActiveRuntimeCoordinator.Result> starting = f.worker.submit(() -> f.coordinator.beginStarting(f.lease, ignored -> true));
            await(f.backend.entered);
            nonblocking(f.execution::requestStop);
            assertFalse(f.execution.runStartupEffect(() -> fail("Cancelled startup ran")));
            f.backend.release.countDown(); assertEquals(WRONG_PHASE, starting.get(2, TimeUnit.SECONDS));
            f.finish();
        }
    }
    @Test public void failedActivationAckKeepsBothEffectKindsClosedUntilRetry() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.bind(); f.backend.block(ACTIVE, NOT_COMMITTED);
            Future<ActiveRuntimeCoordinator.Result> activation = f.worker.submit(() -> f.coordinator.activate(f.lease));
            await(f.backend.entered); f.backend.release.countDown();
            assertEquals(PERSISTENCE_FAILED, activation.get(2, TimeUnit.SECONDS));
            assertEquals(STARTING, f.backend.record.phase);
            assertFalse(f.execution.runStartupEffect(() -> fail("Unacknowledged activation reopened startup")));
            assertFalse(f.execution.runEffect(() -> fail("Unacknowledged activation published normal effect")));
            assertEquals(OK, f.coordinator.activate(f.lease));
            assertTrue(f.execution.runEffect(() -> { })); f.execution.requestStop(); f.finish();
        }
    }
    @Test public void failedStopWriteRetainsPhysicalOccupancyAfterActualDrain() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.bind(); f.activate(); f.backend.block(STOPPING, NOT_COMMITTED);
            f.execution.requestStop(); await(f.backend.entered);
            nonblocking(() -> assertFalse(f.execution.runEffect(() -> fail("Failed stop unfenced effect"))));
            f.backend.release.countDown(); f.barrier();
            assertNotNull(f.close.get()); nonblocking(f.close.get()); f.barrier();
            assertEquals(ACTIVE, f.backend.record.phase);
            assertEquals(STOPPING, f.coordinator.observe().effectivePhase());
            assertEquals(OCCUPIED, f.coordinator.reserve(f.authority.capture(f.target, RuntimeOrigin.LOCAL_UI), Set.of("renderer")).result);
            assertThrows(SecurityException.class, () -> f.registry.acquireEffectPermit(f.lease, OwnedResourceRegistry.Effect.NORMAL));
            f.execution.requestStop(); f.finish();
        }
    }
    @Test public void startupPermitRacingActivationIsCountedOrRefused() throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) try (Fixture f = new Fixture(true)) {
            f.bind(); CountDownLatch together = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread startup = new Thread(() -> {
                await(together);
                try (OwnedResourceRegistry.EffectPermit permit = f.registry.acquireEffectPermit(f.lease, OwnedResourceRegistry.Effect.STARTUP)) {
                    assertNotNull(permit); Thread.yield();
                } catch (SecurityException refused) { /* ACTIVATING won admission. */ }
                catch (Throwable failure) { error.set(failure); }
            });
            startup.start(); Future<ActiveRuntimeCoordinator.Result> activation = f.worker.submit(() -> {
                await(together); return f.coordinator.activate(f.lease);
            });
            together.countDown(); startup.join(2000); assertFalse(startup.isAlive());
            if (error.get() != null) throw new AssertionError(error.get());
            ActiveRuntimeCoordinator.Result result = activation.get(2, TimeUnit.SECONDS);
            assertTrue(result == OK || result == NOT_READY);
            if (result == NOT_READY) assertEquals(OK, f.coordinator.activate(f.lease));
            assertThrows(SecurityException.class, () -> f.registry.acquireEffectPermit(f.lease, OwnedResourceRegistry.Effect.STARTUP));
            f.execution.requestStop(); f.finish();
        }
    }
    @Test public void duplicateCloseAndLateCallbackDoNotWaitForIdleCommit() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.bind(); f.activate(); f.backend.block(IDLE, COMMITTED);
            f.execution.requestStop(); f.barrier(); assertNotNull(f.close.get());
            nonblocking(f.close.get()); await(f.backend.entered);
            nonblocking(f.close.get());
            nonblocking(() -> assertFalse(f.execution.runEffect(() -> fail("Retired callback ran"))));
            nonblocking(f.execution::requestStop);
            f.backend.release.countDown(); f.finish();
        }
    }
    @Test public void oldCapabilityFenceCannotSealAReplacementRegistry() throws Exception {
        try (Fixture old = new Fixture(true); Fixture current = new Fixture(true)) {
            old.bind(); old.activate(); current.bind(); current.activate();
            assertThrows(SecurityException.class, () -> current.registry.fence(old.lease));
            assertTrue(current.execution.runEffect(() -> { }));
            old.execution.requestStop(); old.finish();
            current.execution.requestStop(); current.finish();
        }
    }
    private static final class Fixture implements AutoCloseable {
        final RuntimeMutex mutex = new RuntimeMutex(new Object());
        final Backend backend = new Backend();
        final RuntimeAdmissionAuthority authority = new RuntimeAdmissionAuthority(mutex);
        final RuntimeTarget target = RuntimeTarget.internal("owned-concurrency-test");
        final ActiveRuntimeCoordinator coordinator = new ActiveRuntimeCoordinator(new RuntimeOwnerStore(mutex, backend), authority,
                () -> ActiveRuntimeCoordinator.Occupancy.CLEAR, UUID.randomUUID().toString());
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final RuntimeLease lease;
        final RuntimeExecution<String> execution;
        final OwnedResourceRegistry registry;
        final AtomicReference<Runnable> close = new AtomicReference<>();
        Fixture(boolean starting) {
            authority.completeAvailability(authority.beginAvailability(target), "generation", 2, true);
            authority.completeAuthorization(authority.beginAuthorization(target, RuntimeOrigin.LOCAL_UI), 1, true);
            assertEquals(OK, coordinator.reconcileStartup());
            ActiveRuntimeCoordinator.Admission admitted = coordinator.reserve(authority.capture(target, RuntimeOrigin.LOCAL_UI), Set.of("renderer"));
            assertEquals(OK, admitted.result); lease = admitted.lease;
            if (starting) assertEquals(OK, coordinator.beginStarting(lease, ignored -> true));
            registry = coordinator.resources(lease); execution = new RuntimeExecution<>(coordinator, lease, "initial", worker);
        }
        void bind() {
            RuntimeExecution<String>.Component component = execution.registerComponent("renderer");
            component.installShutdown(close::set); component.usesNoConfiguration(); assertEquals(OK, component.ready());
        }
        void activate() { assertEquals(OK, coordinator.activate(lease)); }
        void barrier() throws Exception { for (int i = 0; i < 4; i++) worker.submit(() -> { }).get(2, TimeUnit.SECONDS); }
        void finish() throws Exception {
            barrier(); if (close.get() != null) close.get().run(); barrier(); assertEquals(IDLE, backend.record.phase);
        }
        @Override public void close() {
            backend.release.countDown(); worker.shutdownNow();
            try { assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        }
    }
    private static final class Backend implements RuntimeOwnerStore.Backend {
        volatile RuntimeOwnerStore.Record record = RuntimeOwnerStore.Record.idle(0);
        volatile RuntimeOwnerStore.Phase phase;
        volatile RuntimeOwnerStore.Outcome outcome = COMMITTED;
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicBoolean used = new AtomicBoolean();
        void block(RuntimeOwnerStore.Phase phase, RuntimeOwnerStore.Outcome outcome) { this.outcome = outcome; this.phase = phase; }
        @Override public RuntimeOwnerStore.Record read() { return record; }
        @Override public RuntimeOwnerStore.Outcome compareAndSet(RuntimeOwnerStore.Record before, RuntimeOwnerStore.Record next) {
            if (!record.equals(before)) return CONFLICT;
            RuntimeOwnerStore.Outcome result = COMMITTED;
            if (next.phase == phase && used.compareAndSet(false, true)) {
                entered.countDown(); await(release); result = outcome;
            }
            if (result == COMMITTED) record = next;
            return result;
        }
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue("Deterministic boundary missing", latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
    private static void nonblocking(Runnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(action, null); Thread thread = new Thread(task, "simulated-main");
        thread.start();
        try { task.get(2, TimeUnit.SECONDS); }
        catch (TimeoutException blocked) {
            throw new AssertionError("Main callback waited for journal lock: " + java.util.Arrays.toString(thread.getStackTrace()), blocked);
        }
    }
}
