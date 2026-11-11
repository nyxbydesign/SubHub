package com.subhub.app.runtime;

import static org.junit.Assert.*;
import java.util.Set;
import java.util.UUID;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public final class RuntimeFenceObservationTest {
    @Test public void firstFenceSurvivesLaterSealAndZoneChange() {
        Fixture f = new Fixture(); var first = new RuntimeFenceObservation(100, "Pacific/Honolulu");
        f.resources.fence(f.lease, first);
        TimeZone prior = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
            f.mutex.runtime(() -> { f.resources.seal(); return null; });
            assertSame(first, f.resources.firstFence(f.lease)); assertEquals("Pacific/Honolulu", first.zone);
        } finally { TimeZone.setDefault(prior); }
    }
    @Test public void coordinatorStopCapturesBeforeFailedJournalWrite() {
        Fixture f = new Fixture(); f.outcome = RuntimeOwnerStore.Outcome.NOT_COMMITTED;
        assertEquals(ActiveRuntimeCoordinator.Result.PERSISTENCE_FAILED, f.coordinator.requestStop(f.lease));
        var observed = f.resources.firstFence(f.lease); assertTrue(observed.at > 0);
        assertThrows(SecurityException.class, () -> f.resources.acquireEffectPermit(f.lease, OwnedResourceRegistry.Effect.NORMAL));
        f.outcome = RuntimeOwnerStore.Outcome.COMMITTED; f.coordinator.requestStop(f.lease);
        assertSame(observed, f.resources.firstFence(f.lease));
    }
    @Test public void uncertainStopNeverResamplesDuringReconciliation() {
        Fixture f = new Fixture(); var held = f.coordinator.registerWork(f.lease, "actual_resource");
        f.outcome = RuntimeOwnerStore.Outcome.UNCERTAIN;
        assertEquals(ActiveRuntimeCoordinator.Result.QUARANTINED, f.coordinator.requestStop(f.lease));
        var observed = f.resources.firstFence(f.lease); f.outcome = RuntimeOwnerStore.Outcome.COMMITTED;
        assertEquals(ActiveRuntimeCoordinator.Result.NOT_READY, f.coordinator.reconcilePersistence());
        assertSame(observed, f.resources.firstFence(f.lease)); assertTrue(f.coordinator.anyOccupied()); held.close();
    }
    @Test public void missingClockStillSealsAndCannotBeReplaced() {
        Fixture f = new Fixture(); f.resources.fence(f.lease, null);
        f.mutex.runtime(() -> { f.resources.seal(); return null; });
        assertThrows(IllegalStateException.class, () -> f.resources.firstFence(f.lease));
        assertThrows(SecurityException.class, () -> f.resources.acquireEffectPermit(f.lease, OwnedResourceRegistry.Effect.NORMAL));
    }
    @Test public void backwardClockIsRetainedRatherThanExtendedToCleanup() {
        Fixture f = new Fixture(); var backward = new RuntimeFenceObservation(10, "UTC");
        f.resources.fence(f.lease, backward); f.resources.fence(f.lease, new RuntimeFenceObservation(200, "UTC"));
        assertEquals(10, f.resources.firstFence(f.lease).at);
        // The accounting reducer rejects stop before its acknowledged start/checkpoint. This
        // evidence layer deliberately does not clamp/extend that value to a later wall clock.
    }
    @Test public void invalidClockSealsButCannotBecomeAValidCleanupClock() {
        Fixture f = new Fixture(); f.resources.fence(f.lease, new RuntimeFenceObservation(0, "UTC"));
        f.resources.fence(f.lease); assertThrows(IllegalStateException.class, () -> f.resources.firstFence(f.lease));
    }
    @Test public void observationNeedsExactCapabilityAndAnActualFence() {
        Fixture f = new Fixture(); assertThrows(IllegalStateException.class, () -> f.resources.firstFence(f.lease));
        RuntimeLease other = new RuntimeLease(f.lease.identity);
        assertThrows(SecurityException.class, () -> f.resources.firstFence(other));
        assertThrows(SecurityException.class, () -> f.resources.fence(other));
        assertThrows(IllegalStateException.class, () -> f.resources.firstFence(f.lease));
    }
    @Test public void concurrentSealAndFenceRetainExactlyOneObservation() throws Exception {
        Fixture f = new Fixture(); var one = new RuntimeFenceObservation(100, "UTC");
        var two = new RuntimeFenceObservation(200, "Asia/Tokyo"); CountDownLatch go = new CountDownLatch(1);
        Thread a = new Thread(() -> { await(go); f.resources.fence(f.lease, one); });
        Thread b = new Thread(() -> { await(go); f.mutex.runtime(() -> { f.resources.seal(two); return null; }); });
        a.start(); b.start(); go.countDown(); a.join(3000); b.join(3000);
        assertFalse(a.isAlive()); assertFalse(b.isAlive());
        var observed = f.resources.firstFence(f.lease); assertTrue(observed == one || observed == two);
        f.resources.fence(f.lease); assertSame(observed, f.resources.firstFence(f.lease));
    }
    private static void await(CountDownLatch gate) {
        try { if (!gate.await(3, TimeUnit.SECONDS)) throw new AssertionError("Timeout"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
    private static final class Fixture {
        final RuntimeMutex mutex = new RuntimeMutex(new Object());
        final RuntimeAdmissionAuthority authority = new RuntimeAdmissionAuthority(mutex);
        RuntimeOwnerStore.Record record = RuntimeOwnerStore.Record.idle(0);
        RuntimeOwnerStore.Outcome outcome = RuntimeOwnerStore.Outcome.COMMITTED;
        final ActiveRuntimeCoordinator coordinator = new ActiveRuntimeCoordinator(new RuntimeOwnerStore(mutex, new RuntimeOwnerStore.Backend() {
            public RuntimeOwnerStore.Record read() { return record; }
            public RuntimeOwnerStore.Outcome compareAndSet(RuntimeOwnerStore.Record before, RuntimeOwnerStore.Record next) {
                if (!record.equals(before)) return RuntimeOwnerStore.Outcome.CONFLICT;
                if (outcome == RuntimeOwnerStore.Outcome.COMMITTED || outcome == RuntimeOwnerStore.Outcome.UNCERTAIN) record = next;
                return outcome;
            }
        }), authority, () -> ActiveRuntimeCoordinator.Occupancy.CLEAR, UUID.randomUUID().toString());
        final RuntimeLease lease;
        final OwnedResourceRegistry resources;
        Fixture() {
            authority.completeAvailability(authority.beginAvailability(RuntimeTarget.DEVICE), "device", 1, true);
            authority.completeAuthorization(authority.beginAuthorization(RuntimeTarget.DEVICE, RuntimeOrigin.LOCAL_UI), 1, true);
            assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.reconcileStartup());
            lease = coordinator.reserve(authority.capture(RuntimeTarget.DEVICE, RuntimeOrigin.LOCAL_UI), Set.of("device")).lease;
            assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.beginStarting(lease, ignored -> true));
            resources = coordinator.resources(lease);
            var component = coordinator.registerComponent(lease, "device");
            assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.componentReady(lease, component));
            assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.activate(lease));
        }
    }
}
