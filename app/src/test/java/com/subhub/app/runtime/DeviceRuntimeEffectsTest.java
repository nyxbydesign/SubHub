package com.subhub.app.runtime;

import static org.junit.Assert.*;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class DeviceRuntimeEffectsTest {
    @Test public void cleanupHandleExistsOnlyAfterCoreShutdown() {
        Fixture f = new Fixture(); assertNull(f.cleanup.get());
        f.runtime.requestStop(); assertNotNull(f.cleanup.get()); assertTrue(f.coordinator.anyOccupied());
        f.cleanup.get().drained(); assertFalse(f.coordinator.anyOccupied());
    }
    @Test public void cleanupCanEndButCannotRearm() {
        Fixture f = new Fixture(); f.runtime.requestStop(); AtomicInteger end = new AtomicInteger();
        f.cleanup.get().run(() -> {
            assertThrows(SecurityException.class, () -> f.effects.borrowMutation(DeviceContinuationScope.Mutation.START_OR_REARM));
            try (var permit = f.effects.borrowMutation(DeviceContinuationScope.Mutation.END)) { assertNotNull(permit); end.incrementAndGet(); }
        });
        assertEquals(1, end.get()); f.cleanup.get().drained(); assertFalse(f.coordinator.anyOccupied());
    }
    @Test public void drainRequestWaitsActualCleanupReturn() throws Exception {
        Fixture f = new Fixture(); f.runtime.requestStop(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread worker = new Thread(() -> f.cleanup.get().run(() -> { entered.countDown(); await(release); })); worker.start();
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS)); f.cleanup.get().drained(); assertTrue(f.coordinator.anyOccupied());
            assertThrows(SecurityException.class, () -> f.cleanup.get().run(() -> fail("Drain already requested")));
        } finally { release.countDown(); worker.join(3000); }
        assertFalse(worker.isAlive()); assertFalse(f.coordinator.anyOccupied());
    }
    @Test public void stopDoesNotIssueCleanupWhileNormalEffectStillRuns() throws Exception {
        Fixture f = new Fixture(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread worker = new Thread(() -> f.component.normal(() -> { entered.countDown(); await(release); })); worker.start();
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS)); f.runtime.requestStop(); assertNull(f.cleanup.get());
        } finally { release.countDown(); worker.join(3000); }
        assertFalse(worker.isAlive()); assertNotNull(f.cleanup.get()); f.cleanup.get().drained(); assertFalse(f.coordinator.anyOccupied());
    }
    @Test public void failedCleanupCanNeverManufactureDrain() {
        Fixture f = new Fixture(); f.runtime.requestStop();
        assertThrows(IllegalStateException.class, () -> f.cleanup.get().run(() -> { throw new IllegalStateException("Native close unknown"); }));
        f.cleanup.get().drained(); assertTrue(f.coordinator.anyOccupied()); assertTrue(f.runtime.cleanupNeedsAttention());
        assertThrows(SecurityException.class, () -> f.cleanup.get().run(() -> fail("Unknown close replay")));
    }
    @Test public void returnedCleanupRejectsLateMutationAndDuplicateDrainIsHarmless() {
        Fixture f = new Fixture(); f.runtime.requestStop(); var closed = f.cleanup.get();
        closed.drained(); closed.drained(); assertFalse(f.coordinator.anyOccupied());
        assertThrows(SecurityException.class, () -> closed.run(() -> fail("Late cleanup")));
        assertFalse(f.component.normal(() -> fail("Ended owner")));
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("Timeout"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
    private static final class Fixture {
        final RuntimeMutex mutex = new RuntimeMutex(new Object());
        final RuntimeAdmissionAuthority authority = new RuntimeAdmissionAuthority(mutex);
        final RuntimeOwnerStore.Backend backend = new RuntimeOwnerStore.Backend() {
            RuntimeOwnerStore.Record current = RuntimeOwnerStore.Record.idle(0);
            public RuntimeOwnerStore.Record read() { return current; }
            public RuntimeOwnerStore.Outcome compareAndSet(RuntimeOwnerStore.Record expected, RuntimeOwnerStore.Record next) {
                if (!current.equals(expected)) return RuntimeOwnerStore.Outcome.CONFLICT;
                current = next; return RuntimeOwnerStore.Outcome.COMMITTED;
            }
        };
        final ActiveRuntimeCoordinator coordinator = new ActiveRuntimeCoordinator(new RuntimeOwnerStore(mutex, backend), authority,
                () -> ActiveRuntimeCoordinator.Occupancy.CLEAR, UUID.randomUUID().toString());
        final RuntimeExecution<String> runtime;
        final DeviceRuntimeEffects effects;
        final DeviceRuntimeEffects.DeviceComponent component;
        final AtomicReference<DeviceRuntimeEffects.DeviceComponent.Cleanup> cleanup = new AtomicReference<>();
        Fixture() {
            authority.completeAvailability(authority.beginAvailability(RuntimeTarget.DEVICE), "device", 1, true);
            authority.completeAuthorization(authority.beginAuthorization(RuntimeTarget.DEVICE, RuntimeOrigin.LOCAL_UI), 1, true);
            assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.reconcileStartup());
            RuntimeLease lease = coordinator.reserve(authority.capture(RuntimeTarget.DEVICE, RuntimeOrigin.LOCAL_UI), Set.of("device")).lease;
            assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.beginStarting(lease, owner -> true));
            runtime = new RuntimeExecution<>(coordinator, lease, "device"); effects = new DeviceRuntimeEffects(coordinator, runtime, lease);
            component = effects.component("device"); component.usesNoConfiguration(); component.installShutdown(cleanup::set);
            component.readyAsync(result -> assertEquals(ActiveRuntimeCoordinator.Result.OK, result));
            assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.activate(lease));
        }
    }
}
