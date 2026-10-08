package com.subhub.app.service;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
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

    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override public void execute(Runnable command) { tasks.addLast(command); }

        void runAll() {
            while (!tasks.isEmpty()) tasks.removeFirst().run();
        }

        int pendingCount() { return tasks.size(); }
    }
}
