package com.subhub.app.service;

import static org.junit.Assert.*;
import android.view.accessibility.AccessibilityEvent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.subhub.app.runtime.*;
import java.lang.reflect.Constructor;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Actual shell state machine/core, controlled queues/native attachment. Not a bound-service proof. */
@RunWith(AndroidJUnit4.class)
public final class AccessibilityServiceConnectionTest {
    @Test public void selectedAttachmentReceivesExactPooledEventSynchronously() throws Exception {
        Fixture f = new Fixture(); f.select(); f.activate();
        AccessibilityEvent event = AccessibilityEvent.obtain();
        try { f.shell.event(event); assertSame(event, f.attachment.event); assertEquals(1, f.attachment.events); }
        finally { event.recycle(); }
        assertEquals(1, f.attachment.attaches);
        assertTrue(f.shell.connection().occupied()); assertTrue(f.shell.occupied());
    }
    @Test public void rejectionRemainsNeutralWithoutAttachmentCreation() throws Exception {
        Fixture f = new Fixture(); f.shell.connect();
        f.completion.rejected(RuntimeCaptureHost.Rejection.NO_RESERVATION);
        AccessibilityEvent event = AccessibilityEvent.obtain();
        try { f.shell.event(event); } finally { event.recycle(); }
        assertEquals(0, f.attachment.attaches); assertEquals(0, f.attachment.events);
        assertFalse(f.shell.connection().occupied()); assertFalse(f.shell.occupied());
    }
    @Test public void disconnectBeforeSelectionNeverAttachesAndWaitsActualClose() throws Exception {
        Fixture f = new Fixture(); f.shell.connect(); f.shell.disconnect();
        f.completion.selected(f.selection);
        assertEquals(0, f.attachment.attaches); assertEquals(0, f.drained);
        f.queue.drain(); assertEquals(1, f.attachment.closes);
        assertEquals(0, f.drained);
        f.attachment.completeDrain(); f.queue.drain();
        assertEquals(1, f.drained); assertFalse(f.shell.occupied());
    }
    @Test public void disconnectFencesEventsButIsNotCleanupAuthorityOrDrain() throws Exception {
        Fixture f = new Fixture(); f.select(); f.activate(); f.shell.disconnect();
        AccessibilityEvent event = AccessibilityEvent.obtain();
        try { f.shell.event(event); } finally { event.recycle(); }
        assertEquals(0, f.attachment.events); assertEquals(0, f.attachment.closes);
        assertTrue(f.shell.connection().occupied());
        f.queue.drain(); assertEquals(1, f.attachment.closes); assertEquals(0, f.drained);
        f.attachment.completeDrain(); assertEquals(0, f.drained);
        f.queue.drain(); assertEquals(1, f.drained); assertFalse(f.shell.connection().occupied());
    }
    @Test public void runtimeStopWithoutOsDisconnectDrivesExactHostCleanup() throws Exception {
        Fixture f = new Fixture(); f.select(); f.activate();
        f.runtime.requestStop(); f.queue.drain();
        assertEquals(1, f.attachment.closes); assertEquals(0, f.drained);
        f.attachment.completeDrain(); f.queue.drain();
        assertEquals(1, f.drained);
    }
    @Test public void stopWithinEventDoesNotCloseUntilEventActuallyReturns() throws Exception {
        Fixture f = new Fixture(); f.select(); f.activate();
        f.attachment.eventAction = () -> {
            f.runtime.requestStop(); assertEquals(0, f.attachment.closes); assertEquals(0, f.queue.tasks.size());
        };
        AccessibilityEvent event = AccessibilityEvent.obtain();
        try { f.shell.event(event); } finally { event.recycle(); }
        f.queue.drain(); assertEquals(1, f.attachment.closes); assertEquals(0, f.drained);
        f.attachment.completeDrain(); f.queue.drain(); assertEquals(1, f.drained);
    }
    @Test public void falseNativeReceiptCannotReleaseConnection() throws Exception {
        Fixture f = new Fixture(); f.select(); f.activate(); f.runtime.requestStop(); f.queue.drain();
        f.attachment.receipt.run(); f.queue.drain();
        assertEquals(0, f.drained); assertTrue(f.shell.connection().occupied()); assertTrue(f.shell.occupied());
    }
    @Test public void attachFailureRetainsExactPartiallyCreatedAttachment() throws Exception {
        Fixture f = new Fixture(); f.attachment.failAttach = true;
        f.shell.connect(); assertThrows(IllegalStateException.class, () -> f.completion.selected(f.selection));
        assertEquals(1, f.rejections); assertTrue(f.shell.occupied());
        f.queue.drain(); assertEquals(1, f.attachment.closes);
        f.attachment.completeDrain(); f.queue.drain(); assertEquals(1, f.drained);
    }
    @Test public void mainDispatchThrowAfterEnqueueNeverClaimsDrain() throws Exception {
        Fixture f = new Fixture(); f.select(); f.activate(); f.queue.throwAfterEnqueue = true;
        // Runtime records its shutdown observer failure; accepted callback is still retained.
        f.runtime.requestStop(); f.queue.throwAfterEnqueue = false; f.queue.drain();
        assertEquals(1, f.attachment.closes);
        f.attachment.completeDrain(); f.queue.drain();
        assertEquals(0, f.drained); assertTrue(f.shell.occupied());
    }
    @Test public void duplicateSelectionCannotReattachOrReplaceOwner() throws Exception {
        Fixture f = new Fixture(); f.select(); f.completion.selected(f.selection);
        assertEquals(1, f.attachment.attaches); assertTrue(f.shell.occupied());
    }
    @Test public void closeReceiptDuringAttachCannotRetireItsDelivery() throws Exception {
        Fixture f = new Fixture();
        f.attachment.attachAction = () -> {
            f.runtime.requestStop(); f.queue.drain(); f.attachment.completeDrain(); f.queue.drain();
            assertEquals(0, f.drained); assertTrue(f.shell.connection().occupied());
        };
        f.select(); assertEquals(0, f.drained); f.queue.drain(); assertEquals(1, f.drained);
    }
    @Test public void ingressFailureDoesNotFallBackOrReleasePendingSelection() throws Exception {
        Fixture f = new Fixture(); f.ingress.completeExceptionally(new IllegalStateException("unknown dispatch"));
        f.shell.connect(); f.completion.selected(f.selection); f.queue.drain();
        assertEquals(0, f.attachment.attaches); assertTrue(f.shell.occupied());
    }
    @Test public void startupEventIsCopiedAndDeliveredAfterActivationNotDropped() throws Exception {
        Fixture f = new Fixture(); f.select();
        AccessibilityEvent original = AccessibilityEvent.obtain();
        f.shell.event(original); assertEquals(0, f.attachment.events);
        original.recycle(); f.activate(); f.queue.drain();
        assertEquals(1, f.attachment.events); assertNotSame(original, f.attachment.event);
    }
    @Test public void busyEventRetainsCopyUntilExactNormalGateReopens() throws Exception {
        Fixture f = new Fixture(); f.select(); f.activate();
        java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread worker = new Thread(() -> f.runtime.runEffect(() -> {
            held.countDown();
            try { release.await(); } catch (InterruptedException failure) { throw new AssertionError(failure); }
        }));
        worker.start(); AccessibilityEvent original = AccessibilityEvent.obtain();
        try {
            assertTrue(held.await(5, java.util.concurrent.TimeUnit.SECONDS));
            f.shell.event(original); assertEquals(0, f.attachment.events);
        } finally { original.recycle(); release.countDown(); worker.join(5000); }
        assertFalse(worker.isAlive()); f.queue.drain();
        assertEquals(1, f.attachment.events); assertNotSame(original, f.attachment.event);
    }
    @Test public void stoppedDeferredEventsAreRecycledWithoutLaterDelivery() throws Exception {
        Fixture f = new Fixture(); f.select(); AccessibilityEvent original = AccessibilityEvent.obtain();
        try { f.shell.event(original); } finally { original.recycle(); }
        f.shell.disconnect(); f.queue.drain(); assertEquals(0, f.attachment.events);
        f.attachment.completeDrain(); f.queue.drain(); assertEquals(1, f.drained);
    }
    @Test public void overflowStopsRatherThanSilentlyLosingEventOrdering() throws Exception {
        Fixture f = new Fixture(); f.select(); AccessibilityEvent original = AccessibilityEvent.obtain();
        try { for (int i = 0; i < 129; i++) f.shell.event(original); }
        finally { original.recycle(); }
        assertEquals(1, f.rejections); f.queue.drain(); assertEquals(0, f.attachment.events);
        f.attachment.completeDrain(); f.queue.drain(); assertEquals(1, f.drained);
    }
    @Test public void deferredConsumerCanDisconnectWithoutRecyclingItsLiveEvent() throws Exception {
        Fixture f = new Fixture(); f.select();
        AccessibilityEvent original = AccessibilityEvent.obtain();
        try { f.shell.event(original); } finally { original.recycle(); }
        f.attachment.eventAction = () -> {
            f.shell.disconnect();
            // getEventType touches the currently borrowed event before consumer return.
            f.attachment.event.getEventType();
        };
        f.activate(); f.queue.drain();
        assertEquals(1, f.attachment.events); assertEquals(1, f.attachment.closes); assertEquals(0, f.rejections);
        f.attachment.completeDrain(); f.queue.drain(); assertEquals(1, f.drained);
    }
    private static final class Queue implements AccessibilityServiceConnection.MainQueue {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>(); boolean throwAfterEnqueue;
        @Override public boolean post(Runnable task) {
            tasks.add(task); if (throwAfterEnqueue) throw new IllegalStateException("accepted then threw"); return true;
        }
        @Override public boolean postDelayed(Runnable task, long delayMillis) { return post(task); }
        void drain() { int budget = 100; while (!tasks.isEmpty()) { if (--budget == 0) fail("nonquiescent queue"); tasks.remove().run(); } }
    }
    private static final class Fixture implements AccessibilityServiceConnection.Port {
        final Queue queue = new Queue(); final AccessibilityServiceConnection shell;
        final ActiveRuntimeCoordinator coordinator; final RuntimeLease lease;
        final RuntimeExecution<?> runtime; final Fake attachment; final RuntimeCaptureHost.Selection selection;
        final CompletableFuture<Void> ingress = new CompletableFuture<>();
        RuntimeCaptureHost.Completion completion; int drained, rejections;
        Fixture() throws Exception {
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
            coordinator = new ActiveRuntimeCoordinator(new RuntimeOwnerStore(mutex, backend), authority,
                    () -> ActiveRuntimeCoordinator.Occupancy.CLEAR, UUID.randomUUID().toString());
            RuntimeTarget target = RuntimeTarget.DEVICE;
            assertTrue(authority.completeAvailability(authority.beginAvailability(target), "generation", 2, true));
            assertTrue(authority.completeAuthorization(authority.beginAuthorization(target, RuntimeOrigin.LOCAL_UI), 1, true));
            assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.reconcileStartup());
            ActiveRuntimeCoordinator.Admission admitted = coordinator.reserve(authority.capture(target, RuntimeOrigin.LOCAL_UI), Set.of("capture"));
            lease = admitted.lease;
            assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.beginStarting(lease, ignored -> true));
            Constructor<?> constructor = RuntimeExecution.class.getDeclaredConstructor(ActiveRuntimeCoordinator.class, RuntimeLease.class, Object.class);
            constructor.setAccessible(true); runtime = (RuntimeExecution<?>) constructor.newInstance(coordinator, lease, "fixture");
            Constructor<DeviceRuntimeEffects> effectsConstructor = DeviceRuntimeEffects.class.getDeclaredConstructor(
                    ActiveRuntimeCoordinator.class, RuntimeExecution.class, RuntimeLease.class);
            effectsConstructor.setAccessible(true); DeviceRuntimeEffects effects = effectsConstructor.newInstance(coordinator, runtime, lease);
            shell = new AccessibilityServiceConnection(() -> null, this, queue);
            RuntimeCaptureHost.DeviceRequest request = new RuntimeCaptureHost.DeviceRequest(shell.connection(),
                    new RuntimeCaptureHost.CapturePlan(RuntimeCaptureHost.Mode.ACCESSIBILITY_DISPLAY, 30), runtime, effects, Fake::new);
            attachment = (Fake) request.prepare(); selection = new RuntimeCaptureHost.Selection(request, attachment, null);
        }
        void select() { shell.connect(); completion.selected(selection); }
        void activate() { assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.activate(lease)); attachment.activationAcknowledged(); }
        @Override public CompletableFuture<Void> connected(RuntimeCaptureHost.Connection c, RuntimeCaptureHost.Completion result) { completion = result; return ingress; }
        @Override public CompletableFuture<Void> disconnected(RuntimeCaptureHost.Connection c) { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Void> rejected(RuntimeCaptureHost.Connection c, RuntimeCaptureHost.Request r, RuntimeCaptureHost.Rejection why) {
            assertSame(selection.request, r); rejections++; return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> drained(RuntimeCaptureHost.Connection c, RuntimeCaptureHost.Selection s) {
            assertSame(selection, s); assertFalse(c.occupied()); drained++; return CompletableFuture.completedFuture(null);
        }
    }
    private static final class Fake implements AccessibilityServiceConnection.Events {
        final RuntimeCaptureHost.DeviceRequest request; final DeviceRuntimeEffects.DeviceComponent component;
        DeviceRuntimeEffects.DeviceComponent.Cleanup cleanup;
        Runnable hostStop, receipt, eventAction = () -> {}, attachAction = () -> {}; AccessibilityEvent event;
        int attaches, events, closes; boolean drained, failAttach;
        Fake(RuntimeCaptureHost.DeviceRequest request) {
            this.request = request; component = request.effects.component("capture"); component.usesNoConfiguration();
            component.installShutdown(value -> { cleanup = value; if (hostStop != null) hostStop.run(); });
        }
        @Override public RuntimeCaptureHost.Request request() { return request; }
        @Override public void bindHostStop(Runnable stop) { hostStop = stop; if (cleanup != null) stop.run(); }
        @Override public void attach(RuntimeCaptureHost.ProjectionGrant ignored) {
            attaches++; assertNull(ignored); assertTrue(request.connection.occupied());
            if (failAttach) throw new IllegalStateException("partial native creation");
            component.readyAsync(result -> assertEquals(ActiveRuntimeCoordinator.Result.OK, result));
            attachAction.run();
        }
        @Override public void activationAcknowledged() { }
        @Override public void onAccessibilityEvent(AccessibilityEvent value) { event = value; events++; eventAction.run(); }
        @Override public void seal() { }
        @Override public void beginClose(Runnable done) { assertNotNull(cleanup); closes++; receipt = done; }
        void completeDrain() { cleanup.run(() -> drained = true); cleanup.drained(); receipt.run(); }
        @Override public boolean physicalOccupancy() { return !drained; }
        @Override public boolean managedResourcesDrained() { return drained; }
    }
}
