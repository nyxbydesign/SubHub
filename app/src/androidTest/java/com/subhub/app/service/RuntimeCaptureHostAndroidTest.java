package com.subhub.app.service;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Service;
import android.content.Intent;
import com.subhub.app.runtime.ActiveRuntimeCoordinator;
import com.subhub.app.runtime.DeviceRuntimeEffects;
import com.subhub.app.runtime.RuntimeAdmissionAuthority;
import com.subhub.app.runtime.RuntimeExecution;
import com.subhub.app.runtime.RuntimeLease;
import com.subhub.app.runtime.RuntimeMutex;
import com.subhub.app.runtime.RuntimeOrigin;
import com.subhub.app.runtime.RuntimeOwnerStore;
import com.subhub.app.runtime.RuntimeTarget;
import java.lang.reflect.Constructor;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import androidx.test.ext.junit.runners.AndroidJUnit4;

/** Real Android Service/Intent routing data only; no bound service, capture or physical-drain claim. */
@RunWith(AndroidJUnit4.class)
public final class RuntimeCaptureHostAndroidTest {
    @Test public void connectionIsExactAndNeverRestoredByGeneration() {
        RuntimeCaptureHost.Connection first = projection(), second = projection();
        assertNotSame(first, second); assertTrue(second.generation > first.generation);
        first.disconnected(); assertFalse(first.isConnected()); assertTrue(second.isConnected());
        assertThrows(IllegalStateException.class, first::accessibility);
    }

    @Test public void deviceRequestCannotCarryInternalExecution() throws Exception {
        Execution internal = execution(RuntimeTarget.internal("dummy_sub_preview"));
        Execution device = execution(RuntimeTarget.DEVICE);
        assertThrows(SecurityException.class, () -> new RuntimeCaptureHost.DeviceRequest(
                projection(), plan(), internal.runtime, device.effects, FakeAttachment::new));
    }

    @Test public void requestRejectsDifferentCaptureHostKind() throws Exception {
        Execution device = execution(RuntimeTarget.DEVICE);
        assertThrows(IllegalArgumentException.class, () -> new RuntimeCaptureHost.DeviceRequest(
                RuntimeCaptureHost.Connection.accessibility(() -> null), plan(), device.runtime, device.effects, FakeAttachment::new));
    }

    @Test public void deviceRequestRequiresItsOwnEffectsCapability() throws Exception {
        Execution first = execution(RuntimeTarget.DEVICE), second = execution(RuntimeTarget.DEVICE);
        assertThrows(SecurityException.class, () -> new RuntimeCaptureHost.DeviceRequest(
                projection(), plan(), first.runtime, second.effects, FakeAttachment::new));
        assertThrows(NullPointerException.class, () -> new RuntimeCaptureHost.DeviceRequest(
                projection(), plan(), first.runtime, null, FakeAttachment::new));
        RuntimeCaptureHost.DeviceRequest exact = new RuntimeCaptureHost.DeviceRequest(
                projection(), plan(), first.runtime, first.effects, FakeAttachment::new);
        assertSame(first.effects, exact.effects);
    }

    @Test public void selectedRequestClaimsOnlyItsExactLiveConnectionOnce() throws Exception {
        RuntimeCaptureHost.DeviceRequest request = request();
        RuntimeCaptureHost.Selection selected = selection(request);
        assertFalse(selected.claim(projection()));
        assertTrue(selected.claim(request.connection));
        assertFalse(selected.claim(request.connection));
    }

    @Test public void delayedSelectionCannotClaimDisconnectedShell() throws Exception {
        RuntimeCaptureHost.DeviceRequest request = request();
        RuntimeCaptureHost.Selection selected = selection(request);
        request.connection.disconnected(); assertFalse(selected.claim(request.connection));
    }

    @Test public void preparationIsSingleUseEvenWhenFactoryThrows() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        Execution device = execution(RuntimeTarget.DEVICE);
        RuntimeCaptureHost.DeviceRequest request = new RuntimeCaptureHost.DeviceRequest(
                projection(), plan(), device.runtime, device.effects, value -> {
                    attempts.incrementAndGet(); throw new IllegalStateException("partial preparation");
                });
        assertThrows(IllegalStateException.class, request::prepare);
        assertThrows(IllegalStateException.class, request::prepare);
        assertEquals(1, attempts.get());
    }

    @Test public void preparationCannotReturnAnotherRequestsAttachment() throws Exception {
        FakeAttachment foreign = new FakeAttachment(request());
        Execution device = execution(RuntimeTarget.DEVICE);
        RuntimeCaptureHost.DeviceRequest request = new RuntimeCaptureHost.DeviceRequest(
                projection(), plan(), device.runtime, device.effects, ignored -> foreign);
        assertThrows(SecurityException.class, request::prepare);
    }

    @Test public void projectionSelectionRequiresExactGrant() throws Exception {
        RuntimeCaptureHost.DeviceRequest request = request(), other = request();
        RuntimeCaptureHost.Attachment attachment = request.prepare();
        assertThrows(SecurityException.class, () -> new RuntimeCaptureHost.Selection(request, attachment, null));
        RuntimeCaptureHost.ProjectionGrant foreign = new RuntimeCaptureHost.ProjectionGrant(other,
                Activity.RESULT_OK, new Intent());
        assertThrows(SecurityException.class, () -> new RuntimeCaptureHost.Selection(request, attachment, foreign));
    }

    @Test public void projectionGrantRequiresApprovedResultAndProjectionMode() throws Exception {
        RuntimeCaptureHost.DeviceRequest request = request();
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeCaptureHost.ProjectionGrant(request, Activity.RESULT_CANCELED, new Intent()));
        Execution device = execution(RuntimeTarget.DEVICE);
        RuntimeCaptureHost.DeviceRequest display = new RuntimeCaptureHost.DeviceRequest(
                RuntimeCaptureHost.Connection.accessibility(() -> null),
                new RuntimeCaptureHost.CapturePlan(RuntimeCaptureHost.Mode.ACCESSIBILITY_DISPLAY, 30),
                device.runtime, device.effects, FakeAttachment::new);
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeCaptureHost.ProjectionGrant(display, Activity.RESULT_OK, new Intent()));
        assertNotNull(new RuntimeCaptureHost.Selection(display, display.prepare(), null));
    }

    @Test public void permissionCannotBeConsumedBeforeRoutingClaimOrByForeignRequest() throws Exception {
        RuntimeCaptureHost.DeviceRequest request = request(), other = request();
        RuntimeCaptureHost.Selection selected = selection(request);
        assertThrows(SecurityException.class, () -> selected.projection.consume(request, request.connection));
        assertTrue(selected.claim(request.connection));
        assertThrows(SecurityException.class, () -> selected.projection.consume(other, request.connection));
        assertThrows(SecurityException.class, () -> selected.projection.consume(request, other.connection));
        assertNotNull(selected.projection.consume(request, request.connection));
        assertThrows(SecurityException.class, () -> selected.projection.consume(request, request.connection));
    }

    @Test public void lateDisconnectSealsUnusedProjectionResult() throws Exception {
        RuntimeCaptureHost.DeviceRequest request = request();
        RuntimeCaptureHost.Selection selected = selection(request);
        assertTrue(selected.claim(request.connection)); request.connection.disconnected();
        assertThrows(SecurityException.class, () -> selected.projection.consume(request, request.connection));
    }

    @Test public void permissionPayloadIsCapturedInsteadOfMutableIntentAlias() throws Exception {
        RuntimeCaptureHost.DeviceRequest request = request();
        Intent input = new Intent("approved");
        RuntimeCaptureHost.ProjectionGrant grant = new RuntimeCaptureHost.ProjectionGrant(request, Activity.RESULT_OK, input);
        input.setAction("later mutation");
        RuntimeCaptureHost.Selection selected = new RuntimeCaptureHost.Selection(request, request.prepare(), grant);
        assertTrue(selected.claim(request.connection));
        assertEquals("approved", grant.consume(request, request.connection).getAction());
    }

    @Test public void connectionRetainsExactSelectionUntilVerifiedDrain() throws Exception {
        RuntimeCaptureHost.DeviceRequest first = request();
        RuntimeCaptureHost.Selection old = selection(first);
        Execution device = execution(RuntimeTarget.DEVICE);
        RuntimeCaptureHost.DeviceRequest second = new RuntimeCaptureHost.DeviceRequest(first.connection, plan(),
                device.runtime, device.effects, FakeAttachment::new);
        RuntimeCaptureHost.Selection next = selection(second);
        assertTrue(old.claim(first.connection)); assertTrue(first.connection.occupied());
        assertFalse(next.claim(first.connection));
        assertFalse(first.connection.releaseAfterDrain(old));
        ((FakeAttachment) old.attachment).drained = true;
        assertTrue(first.connection.releaseAfterDrain(old)); assertFalse(first.connection.occupied());
        assertTrue(next.claim(first.connection));
        assertFalse(first.connection.releaseAfterDrain(old)); assertTrue(first.connection.occupied());
        assertThrows(SecurityException.class, () -> old.projection.consume(first, first.connection));
    }

    @Test public void disconnectDoesNotPretendOccupiedAttachmentDrained() throws Exception {
        RuntimeCaptureHost.DeviceRequest request = request();
        RuntimeCaptureHost.Selection selected = selection(request);
        assertTrue(selected.claim(request.connection)); request.connection.disconnected();
        assertTrue(request.connection.occupied()); assertFalse(request.connection.releaseAfterDrain(selected));
    }

    private static RuntimeCaptureHost.Selection selection(RuntimeCaptureHost.DeviceRequest request) {
        return new RuntimeCaptureHost.Selection(request, request.prepare(),
                new RuntimeCaptureHost.ProjectionGrant(request, Activity.RESULT_OK, new Intent()));
    }
    private static RuntimeCaptureHost.DeviceRequest request() throws Exception {
        Execution device = execution(RuntimeTarget.DEVICE);
        return new RuntimeCaptureHost.DeviceRequest(projection(), plan(), device.runtime, device.effects, FakeAttachment::new);
    }
    private static RuntimeCaptureHost.CapturePlan plan() {
        return new RuntimeCaptureHost.CapturePlan(RuntimeCaptureHost.Mode.PROJECTION, 26);
    }
    private static RuntimeCaptureHost.Connection projection() {
        return RuntimeCaptureHost.Connection.projection(new RuntimeCaptureHost.ProjectionHost() {
            @Override public Service service() { throw new AssertionError("Routing test cannot use Android service"); }
            @Override public void showNeutralForegroundNotification() { throw new AssertionError("No OS effects"); }
            @Override public void stopAfterDrain() { throw new AssertionError("No OS effects"); }
        });
    }
    private static final class FakeAttachment implements RuntimeCaptureHost.Attachment {
        private final RuntimeCaptureHost.Request request;
        boolean drained;
        FakeAttachment(RuntimeCaptureHost.Request request) { this.request = request; }
        @Override public RuntimeCaptureHost.Request request() { return request; }
        @Override public void attach(RuntimeCaptureHost.ProjectionGrant projection) { throw new AssertionError("No native fixture"); }
        @Override public void activationAcknowledged() { throw new AssertionError("No ACTIVE fixture"); }
        @Override public void seal() { }
        @Override public void beginClose(Runnable afterActualDrain) { throw new AssertionError("No drain fixture"); }
        @Override public boolean physicalOccupancy() { return false; }
        @Override public boolean managedResourcesDrained() { return drained; }
    }

    /** Real pure coordinator/lease; reflection only accesses its package-private execution constructor. */
    private static final class Execution {
        final RuntimeExecution<?> runtime;
        final DeviceRuntimeEffects effects;
        Execution(RuntimeExecution<?> runtime, DeviceRuntimeEffects effects) { this.runtime = runtime; this.effects = effects; }
    }
    private static Execution execution(RuntimeTarget target) throws Exception {
        RuntimeMutex mutex = new RuntimeMutex(new Object());
        RuntimeAdmissionAuthority authority = new RuntimeAdmissionAuthority(mutex);
        RuntimeOwnerStore.Backend backend = new RuntimeOwnerStore.Backend() {
            RuntimeOwnerStore.Record record = RuntimeOwnerStore.Record.idle(0);
            @Override public RuntimeOwnerStore.Record read() { return record; }
            @Override public RuntimeOwnerStore.Outcome compareAndSet(RuntimeOwnerStore.Record expected, RuntimeOwnerStore.Record next) {
                if (!record.equals(expected)) return RuntimeOwnerStore.Outcome.CONFLICT;
                record = next; return RuntimeOwnerStore.Outcome.COMMITTED;
            }
        };
        ActiveRuntimeCoordinator coordinator = new ActiveRuntimeCoordinator(new RuntimeOwnerStore(mutex, backend),
                authority, () -> ActiveRuntimeCoordinator.Occupancy.CLEAR, UUID.randomUUID().toString());
        assertTrue(authority.completeAvailability(authority.beginAvailability(target), "generation", 2, true));
        assertTrue(authority.completeAuthorization(authority.beginAuthorization(target, RuntimeOrigin.LOCAL_UI), 1, true));
        assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.reconcileStartup());
        ActiveRuntimeCoordinator.Admission admitted = coordinator.reserve(authority.capture(target, RuntimeOrigin.LOCAL_UI), Set.of("capture"));
        assertEquals(ActiveRuntimeCoordinator.Result.OK, admitted.result);
        assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.beginStarting(admitted.lease, ignored -> true));
        Constructor<?> constructor = RuntimeExecution.class.getDeclaredConstructor(
                ActiveRuntimeCoordinator.class, RuntimeLease.class, Object.class);
        constructor.setAccessible(true);
        RuntimeExecution<?> runtime = (RuntimeExecution<?>) constructor.newInstance(coordinator, admitted.lease, "fixture configuration");
        DeviceRuntimeEffects effects = null;
        if (target.kind == RuntimeTarget.Kind.DEVICE) {
            Constructor<DeviceRuntimeEffects> effectConstructor = DeviceRuntimeEffects.class.getDeclaredConstructor(
                    ActiveRuntimeCoordinator.class, RuntimeExecution.class, RuntimeLease.class);
            effectConstructor.setAccessible(true); effects = effectConstructor.newInstance(coordinator, runtime, admitted.lease);
        }
        return new Execution(runtime, effects);
    }
}
