package com.subhub.app.runtime;

import static org.junit.Assert.*;
import com.subhub.app.stats.OwnerWriteQueue;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.junit.Test;

public final class OwnerWriteQueueTest {
    @Test public void definiteStopRefusalNeverEnqueuesAndNormalCleanupCanDrain() {
        Fixture f = new Fixture(); f.activate(); f.seal();
        assertThrows(OwnerWriteQueue.AdmissionRejected.class,
                () -> f.writer.submit(f.lease.identity, () -> fail("Never admitted")));
        assertTrue(f.worker.tasks.isEmpty()); assertEquals(0, f.writer.unresolvedWrites()); assertTrue(f.failures.isEmpty());
        CompletableFuture<Void> closed = f.writer.closeAfterDrain(f.writer::requireWrite, f.component::close);
        f.worker.next(); assertTrue(closed.isDone()); assertFalse(closed.isCompletedExceptionally());
        assertNotNull(f.registry.drained(f.lease));
        assertThrows(OwnerWriteQueue.AdmissionRejected.class,
                () -> f.writer.submit(f.lease.identity, () -> fail("Closed writer")));
        assertTrue(f.worker.tasks.isEmpty()); assertEquals(0, f.writer.unresolvedWrites());
    }

    @Test public void queuedThenThrowKeepsPermitUntilActualCallbackAndRetainsUncertainPayload() {
        Fixture f = new Fixture(); f.activate(); java.util.concurrent.atomic.AtomicReference<Runnable> queued = new java.util.concurrent.atomic.AtomicReference<>();
        OwnerWriteQueue queue = new OwnerWriteQueue(f.lease, f.registry, action -> {
            queued.set(action); throw new IllegalStateException("Accepted then threw");
        }, f.failures::add);
        assertThrows(IllegalStateException.class, () -> queue.submit(f.lease.identity, () -> fail("Failed dispatch must not write")));
        assertEquals(1, queue.unresolvedWrites()); assertEquals(1, f.failures.size()); f.seal();
        // Adverse early component acknowledgment must not bypass the independently counted permit.
        f.component.close(); assertNull(f.registry.drained(f.lease));
        queued.get().run(); assertEquals(1, queue.unresolvedWrites());
        assertNotNull(f.registry.drained(f.lease)); // Only this fixture deliberately closed the failed component early.
    }

    @Test public void runningThenThrowDoesNotReleasePermitBeforeActualReturn() throws Exception {
        Fixture f = new Fixture(); f.activate();
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.FutureTask<Void>> running = new java.util.concurrent.atomic.AtomicReference<>();
        OwnerWriteQueue queue = new OwnerWriteQueue(f.lease, f.registry, action -> {
            java.util.concurrent.FutureTask<Void> task = new java.util.concurrent.FutureTask<>(action, null); running.set(task);
            Thread worker = new Thread(task, "owner-accepted-then-throw"); worker.setDaemon(true); worker.start();
            await(entered); throw new IllegalStateException("Worker already running");
        }, f.failures::add);
        try {
            assertThrows(IllegalStateException.class, () -> queue.submit(f.lease.identity, () -> {
                entered.countDown(); await(release);
            }));
            f.seal(); f.component.close(); assertNull("Running callback still owns its permit", f.registry.drained(f.lease));
            assertEquals(1, queue.unresolvedWrites());
        } finally { release.countDown(); }
        running.get().get(2, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(1, queue.unresolvedWrites()); assertEquals(1, f.failures.size());
    }

    @Test public void synchronousCompleteThenThrowRetainsUncertaintyAndCannotReplay() {
        Fixture f = new Fixture(); f.activate(); java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        OwnerWriteQueue queue = new OwnerWriteQueue(f.lease, f.registry, action -> {
            action.run(); throw new IllegalStateException("Executed then threw");
        }, f.failures::add);
        assertThrows(IllegalStateException.class, () -> queue.submit(f.lease.identity, calls::incrementAndGet));
        assertEquals(1, calls.get()); assertEquals(1, queue.unresolvedWrites());
        assertThrows(OwnerWriteQueue.AdmissionRejected.class, () -> queue.submit(f.lease.identity, calls::incrementAndGet));
        f.seal(); CompletableFuture<Void> closed = queue.closeAfterDrain(() -> fail("Uncertain cleanup"), f.component::close);
        assertTrue(closed.isCompletedExceptionally()); assertNull(f.registry.drained(f.lease)); assertEquals(1, calls.get());
    }

    @Test public void executorThrownAdmissionRejectedIsWrappedAsUncertainNotTrustedTransitively() {
        Fixture f = new Fixture(); f.activate(); java.util.concurrent.atomic.AtomicReference<Runnable> queued = new java.util.concurrent.atomic.AtomicReference<>();
        OwnerWriteQueue queue = new OwnerWriteQueue(f.lease, f.registry, action -> {
            queued.set(action); throw new OwnerWriteQueue.AdmissionRejected("Not an admission proof", null);
        }, f.failures::add);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> queue.submit(f.lease.identity, () -> fail("Uncertain queued action")));
        assertEquals(IllegalStateException.class, error.getClass());
        assertTrue(error.getCause() instanceof OwnerWriteQueue.AdmissionRejected);
        assertEquals(1, queue.unresolvedWrites()); queued.get().run();
        assertEquals(1, queue.unresolvedWrites()); f.seal(); assertNull(f.registry.drained(f.lease));
    }

    @Test public void admittedActionTypedFailureIsNotAFalseNeverEnqueuedReceipt() {
        Fixture f = new Fixture(); f.activate();
        CompletableFuture<Void> result = f.writer.submit(f.lease.identity, () -> {
            throw new OwnerWriteQueue.AdmissionRejected("Nested admission failed after real action began", null);
        });
        f.worker.next(); java.util.concurrent.CompletionException failure = assertThrows(java.util.concurrent.CompletionException.class, result::join);
        assertEquals(IllegalStateException.class, failure.getCause().getClass());
        assertTrue(failure.getCause().getCause() instanceof OwnerWriteQueue.AdmissionRejected);
        assertEquals(1, f.writer.unresolvedWrites()); f.seal(); assertNull(f.registry.drained(f.lease));
    }

    @Test public void cleanupExecutorTypedFailureIsUncertainAndDoesNotAcknowledgeDrain() {
        Fixture f = new Fixture(); f.activate(); f.seal();
        OwnerWriteQueue queue = new OwnerWriteQueue(f.lease, f.registry, action -> {
            throw new OwnerWriteQueue.AdmissionRejected("Downstream executor", null);
        }, f.failures::add);
        CompletableFuture<Void> result = queue.closeAfterDrain(() -> fail("Not run"), f.component::close);
        java.util.concurrent.CompletionException failure = assertThrows(java.util.concurrent.CompletionException.class, result::join);
        assertEquals(IllegalStateException.class, failure.getCause().getClass()); assertNull(f.registry.drained(f.lease));
    }

    @Test public void externallyReportedTypedFailureCannotForgeNeverEnqueuedFutureReceipt() {
        Fixture f = new Fixture(); f.activate();
        CompletableFuture<Void> admitted = f.writer.submit(f.lease.identity, () -> fail("Unavailable data"));
        f.writer.reportDataFailure(new OwnerWriteQueue.AdmissionRejected("External failure after enqueue", null));
        f.worker.next();
        java.util.concurrent.CompletionException failed = assertThrows(java.util.concurrent.CompletionException.class, admitted::join);
        assertEquals(IllegalStateException.class, failed.getCause().getClass());
        assertTrue(failed.getCause().getCause() instanceof OwnerWriteQueue.AdmissionRejected);
        assertEquals(IllegalStateException.class, f.failures.get(0).getClass());
        assertEquals(1, f.writer.unresolvedWrites()); f.seal();
        CompletableFuture<Void> closed = f.writer.closeAfterDrain(() -> fail("Unavailable cleanup"), f.component::close);
        f.worker.next();
        assertEquals(IllegalStateException.class,
                assertThrows(java.util.concurrent.CompletionException.class, closed::join).getCause().getClass());
        assertNull(f.registry.drained(f.lease));
        assertThrows(OwnerWriteQueue.AdmissionRejected.class,
                () -> f.writer.submit(f.lease.identity, () -> fail("This NEW request was never admitted")));
    }

    @Test public void admittedDeltasFinishAfterSealBeforeActualDrainAcknowledgement() {
        Fixture f = new Fixture(); f.activate(); List<String> done = new ArrayList<>();
        CompletableFuture<Void> one = f.writer.submit(f.lease.identity, () -> { f.writer.requireWrite(); done.add("delta"); });
        assertFalse(one.isDone()); assertEquals(1, f.writer.unresolvedWrites()); f.seal();
        assertThrows(OwnerWriteQueue.AdmissionRejected.class, () -> f.writer.submit(f.lease.identity, () -> fail("Late write")));
        CompletableFuture<Void> close = f.writer.closeAfterDrain(() -> { f.writer.requireWrite(); done.add("history"); },
                () -> { done.add("closed"); f.component.close(); });
        assertNull(f.registry.drained(f.lease)); assertTrue(done.isEmpty());
        f.worker.next(); assertTrue(one.isDone()); assertFalse(close.isDone());
        assertEquals(List.of("delta"), done); assertNull(f.registry.drained(f.lease));
        f.worker.next(); assertTrue(close.isDone()); assertFalse(close.isCompletedExceptionally());
        assertEquals(List.of("delta", "history", "closed"), done); assertEquals(0, f.writer.unresolvedWrites()); assertNotNull(f.registry.drained(f.lease));
        assertThrows(SecurityException.class, f.writer::requireWrite);
    }
    @Test public void wrongOwnerAndOutsideWorkerHaveNoWriteAuthority() {
        Fixture f = new Fixture(); f.activate();
        RuntimeLease.Identity other = identity();
        assertThrows(OwnerWriteQueue.AdmissionRejected.class, () -> f.writer.submit(other, () -> fail("Different owner")));
        assertThrows(SecurityException.class, f.writer::requireWrite);
        assertTrue(f.worker.tasks.isEmpty());
    }
    @Test public void uncertainActionFencesPendingWritesAndNeverInventsDrain() {
        Fixture f = new Fixture(); f.activate();
        CompletableFuture<Void> failed = f.writer.submit(f.lease.identity, () -> { throw new IllegalStateException("Uncertain commit"); });
        CompletableFuture<Void> later = f.writer.submit(f.lease.identity, () -> fail("Pending write after failure"));
        f.worker.next(); assertTrue(failed.isCompletedExceptionally()); assertEquals(1, f.failures.size());
        f.seal(); CompletableFuture<Void> close = f.writer.closeAfterDrain(() -> fail("Uncertain cleanup"), f.component::close);
        f.worker.next(); assertTrue(later.isCompletedExceptionally());
        assertEquals("Failed and skipped accepted payloads remain held for reconciliation", 2, f.writer.unresolvedWrites());
        f.worker.next(); assertTrue(close.isCompletedExceptionally()); assertNull(f.registry.drained(f.lease));
    }
    @Test public void startupPermitLivesUntilTheInitializationActionAcknowledges() {
        Fixture f = new Fixture();
        CompletableFuture<Void> start = f.writer.submitStartup(f.lease.identity, f.writer::requireWrite);
        assertFalse(f.mutex.runtime(f.registry::prepareActivation)); assertFalse(start.isDone());
        f.worker.next(); assertTrue(start.isDone()); assertTrue(f.mutex.runtime(f.registry::prepareActivation));
        f.activate(); assertThrows(OwnerWriteQueue.AdmissionRejected.class, () -> f.writer.submitStartup(f.lease.identity, () -> {}));
    }
    @Test public void arbitraryExecutorRejectionRetainsUncertainPermitAndComponentObligation() {
        Fixture f = new Fixture(); f.activate();
        OwnerWriteQueue rejected = new OwnerWriteQueue(f.lease, f.registry, action -> {
            throw new java.util.concurrent.RejectedExecutionException();
        }, f.failures::add);
        assertThrows(java.util.concurrent.RejectedExecutionException.class,
                () -> rejected.submit(f.lease.identity, () -> fail("Rejected action ran")));
        assertEquals(1, f.failures.size()); f.seal(); assertNull(f.registry.drained(f.lease));
        assertEquals(1, rejected.unresolvedWrites());
        f.component.close(); assertNull("No actual callback return", f.registry.drained(f.lease));
    }
    @Test public void failedObserverCannotStrandTheDurabilityReceipt() {
        Fixture f = new Fixture(); f.activate();
        OwnerWriteQueue queue = new OwnerWriteQueue(f.lease, f.registry, f.worker,
                error -> { throw new IllegalStateException("Observer failed"); });
        CompletableFuture<Void> receipt = queue.submit(f.lease.identity,
                () -> { throw new IllegalArgumentException("Commit failed"); });
        f.worker.next(); assertTrue(receipt.isCompletedExceptionally());
        assertThrows(OwnerWriteQueue.AdmissionRejected.class, () -> queue.submit(f.lease.identity, () -> {}));
        assertThrows(SecurityException.class, () -> f.registry.acquireEffectPermit(f.lease, OwnedResourceRegistry.Effect.NORMAL));
        f.seal(); assertNull(f.registry.drained(f.lease));
    }
    @Test public void exactPhaseGuardUsesHeldPermitWithoutReacquiringAfterSeal() {
        Fixture f = new Fixture();
        f.writer.submitStartup(f.lease.identity, () -> {
            f.writer.requireWrite(OwnedResourceRegistry.Effect.STARTUP);
            assertThrows(SecurityException.class, () -> f.writer.requireWrite(OwnedResourceRegistry.Effect.NORMAL));
        });
        f.worker.next(); f.activate();
        f.writer.submit(f.lease.identity, () -> {
            f.writer.requireWrite(OwnedResourceRegistry.Effect.NORMAL);
            assertThrows(SecurityException.class, () -> f.writer.requireWrite(OwnedResourceRegistry.Effect.STARTUP));
        });
        f.seal(); f.worker.next();
        f.writer.closeAfterDrain(() -> {
            f.writer.requireWrite(OwnedResourceRegistry.Effect.CLEANUP);
            assertThrows(SecurityException.class, () -> f.writer.requireWrite(OwnedResourceRegistry.Effect.NORMAL));
        }, f.component::close);
        f.worker.next(); assertNotNull(f.registry.drained(f.lease));
    }
    @Test public void genericDataFailureFencesBoundRuntimeWithoutBorrowingOrReleasingAPermit() {
        Fixture f = new Fixture(); f.activate();
        CompletableFuture<Void> admitted = f.writer.submit(f.lease.identity, () -> fail("Must not use unavailable data"));
        IllegalStateException unavailable = new IllegalStateException("Generic data commit uncertain");
        f.writer.reportDataFailure(unavailable); f.writer.reportDataFailure(new IllegalStateException("Repeated"));
        assertEquals(List.of(unavailable), f.failures);
        assertThrows(SecurityException.class, () -> f.registry.acquireEffectPermit(f.lease, OwnedResourceRegistry.Effect.NORMAL));
        f.worker.next(); assertTrue(admitted.isCompletedExceptionally()); assertEquals(1, f.writer.unresolvedWrites());
        CompletableFuture<Void> close = f.writer.closeAfterDrain(() -> fail("Uncertain cleanup"), f.component::close);
        f.worker.next(); assertTrue(close.isCompletedExceptionally()); assertNull(f.registry.drained(f.lease));
    }
    private static RuntimeLease.Identity identity() {
        return new RuntimeLease.Identity(RuntimeTarget.internal("dummy_sub_preview"), RuntimeOrigin.LOCAL_UI,
                "generation", UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1);
    }
    private static void await(java.util.concurrent.CountDownLatch latch) {
        try { assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
        catch (InterruptedException failed) { Thread.currentThread().interrupt(); throw new AssertionError(failed); }
    }
    private static final class Queue implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable action) { tasks.addLast(action); }
        void next() { tasks.removeFirst().run(); }
    }
    private static final class Fixture {
        final RuntimeMutex mutex = new RuntimeMutex(new Object());
        final RuntimeLease lease = new RuntimeLease(identity());
        final OwnedResourceRegistry registry = new OwnedResourceRegistry(mutex, lease, () -> {});
        final Queue worker = new Queue();
        final List<Throwable> failures = new ArrayList<>();
        final OwnerWriteQueue writer = new OwnerWriteQueue(lease, registry, worker, failures::add);
        final OwnedResourceRegistry.Registration component;
        Fixture() { component = mutex.runtime(() -> { registry.starting(); return registry.register(lease, "statistics", true); }); }
        void activate() { mutex.runtime(() -> { assertTrue(registry.prepareActivation()); assertTrue(registry.activate()); return null; }); }
        void seal() { mutex.runtime(() -> { registry.seal(); return null; }); }
    }
}
