package com.subhub.app.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

public final class LatestFrameBrokerTest {
    @Test
    public void replacesPendingFramesAndDisposesEveryOwnedValue() {
        ManualExecutor executor = new ManualExecutor();
        List<Integer> processed = new ArrayList<>();
        List<Integer> disposed = new ArrayList<>();
        LatestFrameBroker<Integer> broker = new LatestFrameBroker<>(
                executor, processed::add, disposed::add);

        broker.submit(1);
        broker.submit(2);
        broker.submit(3);
        executor.runAll();

        assertEquals(List.of(3), processed);
        assertEquals(List.of(1, 2, 3), disposed);
    }

    @Test
    public void submissionDuringProcessingIsDrainedWithoutStartingAParallelWorker() {
        ManualExecutor executor = new ManualExecutor();
        List<Integer> processed = new ArrayList<>();
        AtomicReference<LatestFrameBroker<Integer>> holder = new AtomicReference<>();
        holder.set(new LatestFrameBroker<>(executor, value -> {
            processed.add(value);
            if (value == 1) holder.get().submit(2);
        }, ignored -> {}));

        holder.get().submit(1);
        executor.runAll();

        assertEquals(List.of(1, 2), processed);
        assertEquals(0, executor.pendingCount());
    }

    @Test public void blockedMainAdmissionRetainsOneCallbackAndOneCompletedFrame() {
        ManualExecutor main = new ManualExecutor();
        List<Integer> processed = new ArrayList<>(), disposed = new ArrayList<>();
        LatestFrameBroker<Integer> broker = new LatestFrameBroker<>(
                main, processed::add, disposed::add, 1);
        for (int value = 1; value <= 24; value++) broker.submit(value);
        assertEquals(1, main.pendingCount());
        assertEquals(23, disposed.size());
        assertEquals(List.of(), processed);
        main.runAll();
        assertEquals(List.of(24), processed);
        assertEquals(24, disposed.size());
    }

    @Test public void displayDispatchYieldsEvenWhenAnotherFrameArrivesWhilePresenting() {
        ManualExecutor main = new ManualExecutor();
        List<Integer> processed = new ArrayList<>();
        AtomicReference<LatestFrameBroker<Integer>> holder = new AtomicReference<>();
        holder.set(new LatestFrameBroker<>(main, value -> {
            processed.add(value);
            if (value == 1) holder.get().submit(2);
        }, ignored -> {}, 1));
        holder.get().submit(1);
        main.runOne();
        assertEquals(List.of(1), processed);
        assertEquals(1, main.pendingCount());
        main.runOne();
        assertEquals(List.of(1, 2), processed);
    }

    @Test public void closingWithQueuedDisplayCallbackDisposesOnceWithoutPresentation() {
        ManualExecutor main = new ManualExecutor();
        List<Integer> processed = new ArrayList<>(), disposed = new ArrayList<>();
        LatestFrameBroker<Integer> broker = new LatestFrameBroker<>(
                main, processed::add, disposed::add, 1);
        broker.submit(1);
        broker.close();
        broker.submit(2);
        main.runAll();
        broker.close();
        assertEquals(List.of(), processed);
        assertEquals(List.of(1, 2), disposed);
    }

    @Test public void executorRejectionDoesNotStrandAnOwnedPendingFrame() {
        List<Integer> disposed = new ArrayList<>();
        LatestFrameBroker<Integer> broker = new LatestFrameBroker<>(
                task -> { throw new RejectedExecutionException(); }, ignored -> {}, disposed::add, 1);
        assertThrows(RejectedExecutionException.class, () -> broker.submit(1));
        assertThrows(RejectedExecutionException.class, () -> broker.submit(2));
        broker.close();
        assertEquals(List.of(1, 2), disposed);
    }

    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override public void execute(Runnable command) { tasks.addLast(command); }

        void runOne() { tasks.removeFirst().run(); }

        void runAll() {
            while (!tasks.isEmpty()) tasks.removeFirst().run();
        }

        int pendingCount() { return tasks.size(); }
    }
}
