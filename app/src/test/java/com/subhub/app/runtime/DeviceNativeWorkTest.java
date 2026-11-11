package com.subhub.app.runtime;

import static org.junit.Assert.*;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class DeviceNativeWorkTest {
    @Test public void nativeBodiesDoNotHoldEffectGateOrInheritMutationAuthority() {
        Fixture f = new Fixture();
        var first = f.component.normalWork(() -> "first");
        var second = f.component.normalWork(() -> "second");
        assertNotNull(first); assertNotNull(second); assertEquals("first", first.facts());
        assertTrue(f.component.normal(() -> {}));
        assertThrows(SecurityException.class, () -> f.effects.borrowMutation(DeviceContinuationScope.Mutation.CONFIGURATION));
        first.close(); second.close();
    }
    @Test public void stopFencesNewWorkButWaitsActualExistingReturns() {
        Fixture f = new Fixture(); var work = f.component.normalWork(() -> "frame");
        f.runtime.requestStop(); assertNotNull(f.cleanup.get());
        assertNull(f.component.normalWork(() -> { fail("late capture"); return "bad"; }));
        assertFalse(f.component.normal(() -> fail("late publication")));
        f.cleanup.get().drained(); assertTrue(f.coordinator.anyOccupied());
        work.close(); assertFalse(f.coordinator.anyOccupied()); work.close();
        assertThrows(SecurityException.class, work::facts);
    }
    @Test public void startupNativeWorkBlocksActivationUntilActualReturn() {
        Fixture f = new Fixture(false); var work = f.component.startupWork(() -> "model");
        assertNotNull(work); assertNull(f.component.normalWork(() -> "bad"));
        assertEquals(ActiveRuntimeCoordinator.Result.NOT_READY, f.coordinator.activate(f.lease));
        work.close(); assertEquals(ActiveRuntimeCoordinator.Result.OK, f.coordinator.activate(f.lease));
        assertNull(f.component.startupWork(() -> "late"));
    }
    @Test public void nativeDisposalObserverRunsOnlyAfterAllActualReturns() {
        Fixture f = new Fixture(); var a = f.component.normalWork(() -> "a"); var b = f.component.normalWork(() -> "b");
        f.runtime.requestStop(); AtomicInteger calls = new AtomicInteger();
        f.cleanup.get().afterNativeWorkReturned(() -> { calls.incrementAndGet(); f.cleanup.get().drained(); });
        assertEquals(0, calls.get()); a.close(); assertEquals(0, calls.get()); assertTrue(f.coordinator.anyOccupied());
        b.close(); assertEquals(1, calls.get()); assertFalse(f.coordinator.anyOccupied()); b.close();
        assertEquals(1, calls.get());
    }
    @Test public void lateObserverAfterNativeReturnRunsOnceWithoutPrematureAck() {
        Fixture f = new Fixture(); var work = f.component.normalWork(() -> "a");
        f.runtime.requestStop(); work.close(); assertTrue(f.coordinator.anyOccupied()); AtomicInteger calls = new AtomicInteger();
        f.cleanup.get().afterNativeWorkReturned(() -> { calls.incrementAndGet(); assertTrue(f.coordinator.anyOccupied()); });
        assertEquals(1, calls.get()); assertTrue(f.coordinator.anyOccupied());
        assertThrows(SecurityException.class, () -> f.cleanup.get().afterNativeWorkReturned(() -> {}));
        f.cleanup.get().drained(); assertFalse(f.coordinator.anyOccupied());
    }
    @Test public void failedCaptureReleasesRetainedPermitAndFencesOwner() {
        Fixture f = new Fixture();
        assertThrows(IllegalStateException.class, () -> f.component.normalWork(() -> { throw new IllegalStateException("capture"); }));
        assertNotNull(f.cleanup.get()); f.cleanup.get().drained(); assertFalse(f.coordinator.anyOccupied());
    }
    @Test public void failingNativeDisposalNeverManufacturesDrain() {
        Fixture f = new Fixture(); var work = f.component.normalWork(() -> "a"); f.runtime.requestStop();
        f.cleanup.get().afterNativeWorkReturned(() -> { throw new IllegalStateException("dispose"); });
        assertThrows(IllegalStateException.class, work::close); f.cleanup.get().drained();
        assertTrue(f.runtime.cleanupNeedsAttention()); assertTrue(f.coordinator.anyOccupied());
    }
    @Test public void concurrentDrainCannotPassObserverActualReturn() throws Exception {
        Fixture f = new Fixture(); var work = f.component.normalWork(() -> "a"); f.runtime.requestStop();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        f.cleanup.get().afterNativeWorkReturned(() -> { entered.countDown(); await(release); });
        Thread worker = new Thread(work::close); worker.start();
        try { assertTrue(entered.await(3, TimeUnit.SECONDS)); f.cleanup.get().drained(); assertTrue(f.coordinator.anyOccupied()); }
        finally { release.countDown(); worker.join(3000); }
        assertFalse(worker.isAlive()); assertFalse(f.coordinator.anyOccupied());
    }
    @Test public void twoNativeBodiesOverlapAndStopDoesNotWaitForTheirComputation() throws Exception {
        Fixture f = new Fixture(); CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var first = f.component.normalWork(() -> "first"); var second = f.component.normalWork(() -> "second");
        assertNotNull(first); assertNotNull(second);
        java.util.function.Consumer<DeviceRuntimeEffects.DeviceComponent.NativeWork<String>> body = work -> {
            try (var retained = work) { assertNotNull(retained.facts()); entered.countDown(); await(release); }
            catch (Throwable problem) { failure.compareAndSet(null, problem); }
        };
        Thread a = new Thread(() -> body.accept(first)), b = new Thread(() -> body.accept(second)); a.start(); b.start();
        try { assertTrue(entered.await(3, TimeUnit.SECONDS)); f.runtime.requestStop(); f.cleanup.get().drained(); assertTrue(f.coordinator.anyOccupied()); }
        finally { release.countDown(); a.join(3000); b.join(3000); }
        assertNull(failure.get()); assertFalse(f.coordinator.anyOccupied());
    }
    @Test public void coreCannotMintLifetimeWithoutShortAdmittedEffect() {
        Fixture f = new Fixture();
        assertThrows(SecurityException.class, () -> f.runtime.retainNativeWork(OwnedResourceRegistry.Effect.NORMAL));
        assertTrue(f.component.normal(() -> assertThrows(SecurityException.class,
                () -> f.runtime.retainNativeWork(OwnedResourceRegistry.Effect.CLEANUP))));
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
        final RuntimeLease lease;
        final RuntimeExecution<String> runtime;
        final DeviceRuntimeEffects effects;
        final DeviceRuntimeEffects.DeviceComponent component;
        final AtomicReference<DeviceRuntimeEffects.DeviceComponent.Cleanup> cleanup = new AtomicReference<>();
        Fixture() { this(true); }
        Fixture(boolean activate) {
            authority.completeAvailability(authority.beginAvailability(RuntimeTarget.DEVICE), "device", 1, true);
            authority.completeAuthorization(authority.beginAuthorization(RuntimeTarget.DEVICE, RuntimeOrigin.LOCAL_UI), 1, true);
            assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.reconcileStartup());
            lease = coordinator.reserve(authority.capture(RuntimeTarget.DEVICE, RuntimeOrigin.LOCAL_UI), Set.of("device")).lease;
            assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.beginStarting(lease, owner -> true));
            runtime = new RuntimeExecution<>(coordinator, lease, "device"); effects = new DeviceRuntimeEffects(coordinator, runtime, lease);
            component = effects.component("device"); component.usesNoConfiguration(); component.installShutdown(cleanup::set);
            component.readyAsync(result -> assertEquals(ActiveRuntimeCoordinator.Result.OK, result));
            if (activate) assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.activate(lease));
        }
    }
}
