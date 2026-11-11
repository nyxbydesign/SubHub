package com.subhub.app.runtime;
import static org.junit.Assert.*;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class DeviceReadinessEventsTest {
    @Test public void shortStartupSignalsOnlyAfterActualBodyReturn() {
        Fixture f = new Fixture();
        assertTrue(f.component.startup(() -> assertEquals(0, f.startupEvents.get())));
        assertEquals(1, f.startupEvents.get());
        f.runtime.requestStop();
        assertFalse(f.component.startup(() -> fail("sealed")));
        assertEquals(1, f.startupEvents.get());
    }
    @Test public void retainedStartupSignalsAfterPermitReturnNotCaptureReturn() {
        Fixture f = new Fixture(); var work = f.component.startupWork(() -> "model");
        assertNotNull(work); assertEquals(0, f.startupEvents.get());
        f.component.readyAsync(result -> assertEquals(ActiveRuntimeCoordinator.Result.OK, result));
        assertEquals(1, f.readyEvents.get());
        assertEquals(ActiveRuntimeCoordinator.Result.NOT_READY, f.coordinator.activate(f.lease));
        work.close(); assertEquals(1, f.startupEvents.get());
        assertEquals(ActiveRuntimeCoordinator.Result.OK, f.coordinator.activate(f.lease));
        work.close(); assertEquals(1, f.startupEvents.get());
    }
    @Test public void readinessObserverFollowsActualAcknowledgedCompletion() {
        Fixture f = new Fixture();
        f.component.readyAsync(result -> { assertEquals(ActiveRuntimeCoordinator.Result.OK, result); assertEquals(0, f.readyEvents.get()); });
        assertEquals(1, f.readyEvents.get());
    }
    @Test public void failedReadyConsumerDoesNotPublishReadyEvent() {
        Fixture f = new Fixture();
        f.component.readyAsync(result -> { throw new IllegalStateException("consumer"); });
        assertEquals(0, f.readyEvents.get()); assertNotNull(f.cleanup.get());
        assertFalse(f.component.startup(() -> fail("failed consumer revived")));
    }
    @Test public void startupObserverFailureFencesInsteadOfLeavingStartingOwnerUsable() {
        Fixture f = new Fixture(); f.startupFailure.set(new IllegalStateException("uncertain dispatch"));
        assertThrows(IllegalStateException.class, () -> f.component.startup(() -> {}));
        assertNotNull(f.cleanup.get()); assertFalse(f.component.normal(() -> fail("observer failure")));
    }
    @Test public void readyObserverErrorFencesOwner() {
        Fixture f = new Fixture(); f.readyFailure.set(new AssertionError("dispatch"));
        assertThrows(AssertionError.class, () -> f.component.readyAsync(result -> {}));
        assertNotNull(f.cleanup.get()); assertFalse(f.component.normal(() -> fail("observer error")));
    }
    @Test public void normalNativeReturnsDoNotPretendToBeStartupEvents() {
        Fixture f = new Fixture(); f.component.readyAsync(result -> {});
        assertEquals(ActiveRuntimeCoordinator.Result.OK, f.coordinator.activate(f.lease));
        var work = f.component.normalWork(() -> "frame"); assertNotNull(work); work.close();
        assertEquals(0, f.startupEvents.get()); assertEquals(1, f.readyEvents.get());
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
        final AtomicInteger startupEvents = new AtomicInteger(), readyEvents = new AtomicInteger();
        final AtomicReference<RuntimeException> startupFailure = new AtomicReference<>();
        final AtomicReference<Error> readyFailure = new AtomicReference<>();
        Fixture() {
            authority.completeAvailability(authority.beginAvailability(RuntimeTarget.DEVICE), "device", 1, true);
            authority.completeAuthorization(authority.beginAuthorization(RuntimeTarget.DEVICE, RuntimeOrigin.LOCAL_UI), 1, true);
            assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.reconcileStartup());
            lease = coordinator.reserve(authority.capture(RuntimeTarget.DEVICE, RuntimeOrigin.LOCAL_UI), Set.of("device")).lease;
            assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.beginStarting(lease, owner -> true));
            runtime = new RuntimeExecution<>(coordinator, lease, "device"); effects = new DeviceRuntimeEffects(coordinator, runtime, lease, () -> {
                startupEvents.incrementAndGet();
                RuntimeException failure = startupFailure.get(); if (failure != null) throw failure;
            }, result -> {
                readyEvents.incrementAndGet(); Error failure = readyFailure.get(); if (failure != null) throw failure;
            });
            component = effects.component("device"); component.usesNoConfiguration(); component.installShutdown(cleanup::set);

        }
    }
}
