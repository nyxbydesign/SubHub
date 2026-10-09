package com.subhub.app.service;

import static org.junit.Assert.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.Test;

public final class SerialOwnedLookupTest {
    private static final class ManualExecutor implements Executor {
        final ArrayDeque<Runnable> work = new ArrayDeque<>();
        @Override public void execute(Runnable task) { work.addLast(task); }
        void run() { while (!work.isEmpty()) work.removeFirst().run(); }
    }

    @Test public void callbackReturnsWithoutLookupAndAllProducersRetainFifoOrder() {
        ManualExecutor worker = new ManualExecutor();
        List<Integer> processed = new ArrayList<>(), disposed = new ArrayList<>();
        SerialOwnedLookup<Integer> queue = new SerialOwnedLookup<>(4, worker,
                (value, lease) -> { processed.add(value); lease.release(); }, disposed::add, (value, error) -> fail());
        for (int value : new int[]{1, 2, 3}) assertEquals(SerialOwnedLookup.Offer.ACCEPTED, queue.offer(value));
        assertTrue(processed.isEmpty());
        worker.run();
        assertEquals(List.of(1, 2, 3), processed);
        assertEquals(processed, disposed);
        assertEquals(0, queue.outstanding());
    }

    @Test public void postedUiResultsStillConsumeCapacityAndReleaseExactlyOnce() {
        ManualExecutor worker = new ManualExecutor();
        List<SerialOwnedLookup.Lease> leases = new ArrayList<>();
        List<Integer> disposed = new ArrayList<>();
        SerialOwnedLookup<Integer> queue = new SerialOwnedLookup<>(1, worker,
                (value, lease) -> leases.add(lease), disposed::add, (value, error) -> fail());
        queue.offer(1); worker.run();
        assertEquals(SerialOwnedLookup.Offer.FULL, queue.offer(2));
        assertEquals(List.of(2), disposed);
        leases.get(0).release(); leases.get(0).release();
        assertEquals(List.of(2, 1), disposed);
        assertEquals(0, queue.outstanding());
    }

    @Test public void invalidateDisposesPendingButDoesNotRecycleActiveLookupInput() {
        ManualExecutor worker = new ManualExecutor();
        List<Integer> disposed = new ArrayList<>();
        SerialOwnedLookup<Integer>[] holder = new SerialOwnedLookup[1];
        holder[0] = new SerialOwnedLookup<>(3, worker, (value, lease) -> {
            holder[0].invalidate();
            assertEquals(List.of(2), disposed);
            assertFalse(lease.isCurrent());
            lease.release();
        }, disposed::add, (value, error) -> fail());
        holder[0].offer(1); holder[0].offer(2); worker.run();
        assertEquals(List.of(2, 1), disposed);
        assertEquals(0, holder[0].outstanding());
    }

    @Test public void structuralFenceRejectsAlreadyPostedUiResult() {
        ManualExecutor worker = new ManualExecutor();
        List<SerialOwnedLookup.Lease> leases = new ArrayList<>();
        List<Integer> disposed = new ArrayList<>();
        SerialOwnedLookup<Integer> queue = new SerialOwnedLookup<>(2, worker,
                (value, lease) -> leases.add(lease), disposed::add, (value, error) -> fail());
        queue.offer(1); worker.run();
        queue.invalidate();
        assertFalse(leases.get(0).isCurrent());
        assertTrue(disposed.isEmpty());
        leases.get(0).release();
        assertEquals(List.of(1), disposed);
    }

    @Test public void shutdownAndExecutorRejectionDisposeAllQueuedInputs() {
        List<Integer> disposed = new ArrayList<>();
        SerialOwnedLookup<Integer> rejected = new SerialOwnedLookup<>(2,
                task -> { throw new RejectedExecutionException(); }, (value, lease) -> fail(),
                disposed::add, (value, error) -> fail());
        assertEquals(SerialOwnedLookup.Offer.EXECUTOR_REJECTED, rejected.offer(1));
        assertEquals(0, rejected.outstanding());
        ManualExecutor worker = new ManualExecutor();
        SerialOwnedLookup<Integer> closed = new SerialOwnedLookup<>(2, worker,
                (value, lease) -> fail(), disposed::add, (value, error) -> fail());
        closed.offer(2); closed.close(); worker.run();
        assertEquals(SerialOwnedLookup.Offer.CLOSED, closed.offer(3));
        assertEquals(List.of(1, 2, 3), disposed);
        assertEquals(0, closed.outstanding());
    }

    @Test public void failedLookupCannotLeakResourceOrStopLaterWork() {
        ManualExecutor worker = new ManualExecutor();
        List<Integer> disposed = new ArrayList<>();
        List<RuntimeException> failures = new ArrayList<>();
        SerialOwnedLookup<Integer> queue = new SerialOwnedLookup<>(2, worker, (value, lease) -> {
            if (value == 1) throw new IllegalStateException();
            lease.release();
        }, disposed::add, (value, error) -> failures.add(error));
        queue.offer(1); queue.offer(2); worker.run();
        assertEquals(List.of(1, 2), disposed);
        assertEquals(1, failures.size());
        assertEquals(0, queue.outstanding());
    }
}
