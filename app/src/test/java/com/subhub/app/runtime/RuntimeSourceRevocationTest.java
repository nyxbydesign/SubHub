package com.subhub.app.runtime;

import static org.junit.Assert.*;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class RuntimeSourceRevocationTest {
    @Test public void issuedPromptCannotLaunchAfterRealSourceRetires() {
        Fixture f = new Fixture(); var attempt = f.gateway.reserve(f.witness(), Set.of("component")).join().attempt;
        var prompt = f.gateway.permission(attempt, RuntimeStartAttempt.Permission.PROJECTION).join();
        f.source.revoke(); AtomicInteger launched = new AtomicInteger();
        assertFalse(prompt.launch(launched::incrementAndGet)); assertEquals(0, launched.get());
        assertFalse(f.coordinator.acceptsPermissionResult(attempt.lease()));
    }
    @Test public void retiredSourceCannotPublishACompletedAuthorization() {
        Fixture f = new Fixture(); var publication = f.authority.beginAuthorization(f.target, RuntimeOrigin.LOCAL_UI);
        f.source.revoke(); assertFalse(f.authority.completeAuthorization(publication, 2, true, f.source));
        assertEquals(ActiveRuntimeCoordinator.Result.AUTHORITY_CHANGED, f.coordinator.reserve(f.witness(), Set.of("component")).result);
    }
    @Test public void relockAndUnlockNeverRevivesTheOldPrompt() {
        Fixture f = new Fixture(); var attempt = f.gateway.reserve(f.witness(), Set.of("component")).join().attempt;
        var prompt = f.gateway.permission(attempt, RuntimeStartAttempt.Permission.OVERLAY).join();
        f.source.revoke(); var replacement = new RuntimeAdmissionAuthority.Revocation();
        assertTrue(f.authority.completeAuthorization(f.authority.beginAuthorization(f.target, RuntimeOrigin.LOCAL_UI), 1, true, replacement));
        assertFalse(prompt.launch(() -> fail("Old prompt revived")));
    }
    @Test public void sourceRetirementDoesNotStopAnAlreadyActiveInternalSession() {
        Fixture f = new Fixture(); RuntimeLease lease = f.coordinator.reserve(f.witness(), Set.of("component")).lease;
        assertEquals(ActiveRuntimeCoordinator.Result.OK, f.coordinator.beginStarting(lease, ignored -> true));
        var component = f.coordinator.registerComponent(lease, "component");
        assertEquals(ActiveRuntimeCoordinator.Result.OK, f.coordinator.componentReady(lease, component));
        assertEquals(ActiveRuntimeCoordinator.Result.OK, f.coordinator.activate(lease));
        f.source.revoke();
        try (var permit = f.coordinator.resources(lease).acquireEffectPermit(lease, OwnedResourceRegistry.Effect.NORMAL)) {
            assertNotNull(permit);
        }
        assertEquals(RuntimeOwnerStore.Phase.ACTIVE, f.coordinator.observe().persisted.phase);
    }
    @Test public void internalSourceCannotBeAttachedToDeviceAuthorization() {
        Fixture f = new Fixture(); var availability = f.authority.beginAvailability(RuntimeTarget.DEVICE);
        f.authority.completeAvailability(availability, "device", 1, true);
        var device = f.authority.beginAuthorization(RuntimeTarget.DEVICE, RuntimeOrigin.LOCAL_UI);
        assertThrows(IllegalArgumentException.class, () -> f.authority.completeAuthorization(device, 1, true, f.source));
        assertTrue(f.authority.completeAuthorization(device, 1, true));
        f.source.revoke(); assertEquals(ActiveRuntimeCoordinator.Result.OK,
                f.coordinator.reserve(f.authority.capture(RuntimeTarget.DEVICE, RuntimeOrigin.LOCAL_UI), Set.of("component")).result);
    }
    @Test public void alreadyAdmittedPromptStillOwnsActualResultAfterSourceRetires() {
        Fixture f = new Fixture(); var attempt = f.gateway.reserve(f.witness(), Set.of("component")).join().attempt;
        var prompt = f.gateway.permission(attempt, RuntimeStartAttempt.Permission.PROJECTION).join();
        assertTrue(prompt.launch(f.source::revoke)); f.gateway.cancel(attempt);
        assertTrue(f.coordinator.anyOccupied());
        assertFalse(f.gateway.permissionResult(attempt, prompt, true).join()); assertFalse(f.coordinator.anyOccupied());
    }
    private static final class Fixture {
        final RuntimeMutex mutex = new RuntimeMutex(new Object());
        final RuntimeAdmissionAuthority authority = new RuntimeAdmissionAuthority(mutex);
        final RuntimeTarget target = RuntimeTarget.internal("dummy_sub_preview");
        final RuntimeAdmissionAuthority.Revocation source = new RuntimeAdmissionAuthority.Revocation();
        final ActiveRuntimeCoordinator coordinator = new ActiveRuntimeCoordinator(new RuntimeOwnerStore(mutex, new RuntimeOwnerStore.Backend() {
            RuntimeOwnerStore.Record record = RuntimeOwnerStore.Record.idle(0);
            public RuntimeOwnerStore.Record read() { return record; }
            public RuntimeOwnerStore.Outcome compareAndSet(RuntimeOwnerStore.Record before, RuntimeOwnerStore.Record next) {
                if (!record.equals(before)) return RuntimeOwnerStore.Outcome.CONFLICT;
                record = next; return RuntimeOwnerStore.Outcome.COMMITTED;
            }
        }), authority, () -> ActiveRuntimeCoordinator.Occupancy.CLEAR, UUID.randomUUID().toString());
        final RuntimeStartGateway gateway = new RuntimeStartGateway(coordinator, Runnable::run, () -> { });
        Fixture() {
            authority.completeAvailability(authority.beginAvailability(target), "profile", 1, true);
            assertTrue(authority.completeAuthorization(authority.beginAuthorization(target, RuntimeOrigin.LOCAL_UI), 1, true, source));
            assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.reconcileStartup());
        }
        RuntimeAdmissionAuthority.Witness witness() { return authority.capture(target, RuntimeOrigin.LOCAL_UI); }
    }
}
