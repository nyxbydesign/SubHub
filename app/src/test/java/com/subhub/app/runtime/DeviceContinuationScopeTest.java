package com.subhub.app.runtime;

import static org.junit.Assert.*;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class DeviceContinuationScopeTest {
    @Test public void missingContinuationAndOtherScopeCannotBorrowPhysicalMutations() {
        Fixture f = new Fixture(), other = new Fixture();
        assertThrows(SecurityException.class, () -> f.scope.admit(DeviceContinuationScope.Mutation.END));
        assertTrue(f.scope.startup(() -> {
            assertThrows(SecurityException.class, () -> other.scope.admit(DeviceContinuationScope.Mutation.START_OR_REARM));
        }));
    }
    @Test public void startupAndNormalRequireTheActualJournalPhase() {
        Fixture f = new Fixture(); AtomicInteger mutations = new AtomicInteger();
        assertFalse(f.scope.normal(() -> fail("Not ACTIVE")));
        assertTrue(f.scope.startup(() -> mutation(f.scope, DeviceContinuationScope.Mutation.START_OR_REARM, mutations)));
        f.activate();
        assertFalse(f.scope.startup(() -> fail("Not STARTING")));
        assertTrue(f.scope.normal(() -> mutation(f.scope, DeviceContinuationScope.Mutation.CONFIGURATION, mutations)));
        assertEquals(2, mutations.get());
    }
    @Test public void internalLeaseCanNeverConstructDeviceContinuation() {
        Fixture f = new Fixture(RuntimeTarget.internal("dummy_sub_preview"));
        assertThrows(SecurityException.class, () -> new DeviceContinuationScope(f.coordinator, f.runtime, f.lease));
    }
    @Test public void queuedChildCannotInheritAnAdmittedContinuation() throws Exception {
        Fixture f = new Fixture(); AtomicReference<Runnable> child = new AtomicReference<>();
        assertTrue(f.scope.startup(() -> child.set(() -> assertThrows(SecurityException.class,
                () -> f.scope.admit(DeviceContinuationScope.Mutation.START_OR_REARM)))));
        child.get().run(); Thread worker = new Thread(child.get()); worker.start(); worker.join(3000); assertFalse(worker.isAlive());
    }
    @Test public void stopSealsFutureWorkButAdmittedMutationReturnsUnderItsHeldPermit() throws Exception {
        Fixture f = new Fixture(); f.activate();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger changes = new AtomicInteger();
        Thread event = new Thread(() -> f.scope.normal(() -> {
            entered.countDown(); await(release);
            mutation(f.scope, DeviceContinuationScope.Mutation.CONFIGURATION, changes);
        }));
        event.start();
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS)); f.runtime.requestStop();
            assertFalse(f.scope.normal(() -> fail("Sealed owner")));
            assertNull(f.registry.drained(f.lease));
        } finally { release.countDown(); event.join(3000); }
        assertFalse(event.isAlive()); assertEquals(1, changes.get());
    }
    @Test public void cleanupIsEndOnlyAndCountsActualReturn() throws Exception {
        Fixture f = new Fixture(); f.activate(); f.runtime.requestStop();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread cleanup = new Thread(() -> f.scope.cleanup(() -> {
            assertThrows(SecurityException.class, () -> f.scope.admit(DeviceContinuationScope.Mutation.START_OR_REARM));
            assertThrows(SecurityException.class, () -> f.scope.admit(DeviceContinuationScope.Mutation.CONFIGURATION));
            try (var borrowed = f.scope.admit(DeviceContinuationScope.Mutation.END)) {
                assertNotNull(borrowed); entered.countDown(); await(release);
            }
        }));
        cleanup.start();
        try { assertTrue(entered.await(3, TimeUnit.SECONDS)); assertNull(f.registry.drained(f.lease)); }
        finally { release.countDown(); cleanup.join(3000); }
        assertFalse(cleanup.isAlive());
        // The actual component is still retained: cleanup return alone cannot declare it closed.
        assertNull(f.registry.drained(f.lease)); assertTrue(f.coordinator.anyOccupied());
    }
    @Test public void cleanupCannotRunBeforeSealAndBorrowCannotSurviveTheCall() {
        Fixture f = new Fixture();
        assertThrows(SecurityException.class, () -> f.scope.cleanup(() -> fail("Not sealed")));
        AtomicReference<DeviceContinuationScope.Borrow> escaped = new AtomicReference<>();
        assertThrows(IllegalStateException.class, () -> f.scope.startup(() -> escaped.set(f.scope.admit(DeviceContinuationScope.Mutation.END))));
        assertThrows(SecurityException.class, () -> escaped.get().close()); assertTrue(f.coordinator.anyOccupied());
    }
    @Test public void nestedSameOwnerPhasePreservesOuterPermit() {
        Fixture f = new Fixture(); AtomicInteger changes = new AtomicInteger();
        assertTrue(f.scope.startup(() -> {
            try (var outer = f.scope.admit(DeviceContinuationScope.Mutation.START_OR_REARM)) {
                assertNotNull(outer);
                assertTrue(f.scope.startup(() -> mutation(f.scope, DeviceContinuationScope.Mutation.START_OR_REARM, changes)));
                changes.incrementAndGet();
            }
        }));
        assertEquals(2, changes.get());
    }
    private static void mutation(DeviceContinuationScope scope, DeviceContinuationScope.Mutation mutation, AtomicInteger changes) {
        try (var permit = scope.admit(mutation)) { assertNotNull(permit); changes.incrementAndGet(); }
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("Timed out"); }
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
        final RuntimeExecution<String>.Component component;
        final OwnedResourceRegistry registry;
        final DeviceContinuationScope scope;
        Fixture() { this(RuntimeTarget.DEVICE); }
        Fixture(RuntimeTarget target) {
            authority.completeAvailability(authority.beginAvailability(target), "generation", 1, true);
            authority.completeAuthorization(authority.beginAuthorization(target, RuntimeOrigin.LOCAL_UI), 1, true);
            assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.reconcileStartup());
            lease = coordinator.reserve(authority.capture(target, RuntimeOrigin.LOCAL_UI), Set.of("capture")).lease;
            assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.beginStarting(lease, owner -> true));
            runtime = new RuntimeExecution<>(coordinator, lease, "one"); registry = coordinator.resources(lease);
            component = runtime.registerComponent("capture"); component.usesNoConfiguration();
            component.installShutdown(ignored -> { }); // Retain real-resource obligation, never invent drain.
            scope = target.kind == RuntimeTarget.Kind.DEVICE ? new DeviceContinuationScope(coordinator, runtime, lease) : null;
        }
        void activate() {
            assertEquals(ActiveRuntimeCoordinator.Result.OK, component.ready());
            assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.activate(lease));
        }
    }
}
