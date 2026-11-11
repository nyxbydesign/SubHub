package com.subhub.app.censor.android;

import static org.junit.Assert.*;
import com.subhub.app.censor.engine.LatestReconciliation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.Test;

public final class CensorReconciliationTest {
    @Test public void refusedWindowLookupRemainsRetryableWithoutInventingAnEmptyWindow() {
        Harness h = new Harness(); h.read = () -> { throw new CensorEffectScope.Unavailable(); };
        h.request(); assertEquals(1, h.deferrals); assertTrue(h.commits.isEmpty()); assertTrue(h.faults.isEmpty());
        assertTrue(h.worker.tasks.isEmpty());
        h.read = () -> 2; h.request(); assertEquals(List.of(2), h.commits);
    }

    @Test public void busyAppearanceCommitDoesNotPermanentlyCloseActualReconciler() {
        Harness h = new Harness(); h.commit = value -> { throw new CensorEffectScope.Unavailable(); };
        h.request(); assertEquals(1, h.deferrals); assertTrue(h.faults.isEmpty());
        h.commit = h.commits::add; h.request(); assertEquals(List.of(1), h.commits);
    }

    @Test public void staleDeferredLookupCannotWithdrawNewerSelection() {
        Harness h = new Harness(); h.read = () -> { throw new CensorEffectScope.Unavailable(); };
        h.lane.request(); h.worker.next(); h.lane.request(); h.read = () -> 3; h.main.next();
        assertEquals(0, h.deferrals); h.worker.next(); h.main.next(); assertEquals(List.of(3), h.commits);
    }

    @Test public void stopRevokesDeferredResultWithoutSelfRestart() {
        Harness h = new Harness(); h.read = () -> { throw new CensorEffectScope.Unavailable(); };
        h.lane.request(); h.worker.next(); h.lane.invalidate(); h.main.next();
        assertEquals(0, h.deferrals); assertTrue(h.worker.tasks.isEmpty());
        h.read = () -> 4; h.request(); assertEquals(List.of(4), h.commits);
    }

    @Test public void actualLookupResourceFailureStillClosesAndCannotRetry() {
        Harness h = new Harness(); IllegalStateException failure = new IllegalStateException("Actual native resource failure");
        h.read = () -> { throw failure; }; h.request();
        assertEquals(List.of(failure), h.faults); assertTrue(h.lane.drained());
        h.read = () -> 5; h.lane.request(); assertTrue(h.worker.tasks.isEmpty());
    }

    @Test public void actualCommitResourceFailureStillClosesAndCannotRetry() {
        Harness h = new Harness(); IllegalStateException failure = new IllegalStateException("Actual window update failure");
        h.commit = value -> { throw failure; }; h.request();
        assertEquals(List.of(failure), h.faults); assertTrue(h.lane.drained()); assertEquals(0, h.deferrals);
    }

    @Test public void failedDeferralCleanupIsNotMistakenForBusyRetry() {
        Harness h = new Harness(); IllegalStateException failure = new IllegalStateException("Actual detach failed");
        h.read = () -> { throw new CensorEffectScope.Unavailable(); }; h.deferFailure = failure; h.request();
        assertEquals(List.of(failure), h.faults); assertTrue(h.lane.drained());
    }

    @Test public void reentrantWantedRequestIsInvalidatedByDeferredStop() {
        Harness h = new Harness();
        h.commit = value -> { h.lane.request(); throw new CensorEffectScope.Unavailable(); };
        h.request(); assertTrue(h.worker.tasks.isEmpty()); assertEquals(1, h.deferrals);
        h.commit = h.commits::add; h.request(); assertEquals(List.of(1), h.commits);
    }

    @Test public void revokedReadNeverAcquiresAnyNativeHandle() {
        AtomicInteger reads = new AtomicInteger(); CensorEffectScope scope = effect -> false;
        assertThrows(CensorEffectScope.Unavailable.class, () -> scope.read(reads::incrementAndGet));
        assertEquals(0, reads.get());
    }

    @Test public void readPermitSpansActualLookupAndHandleDisposal() {
        AtomicBoolean admitted = new AtomicBoolean();
        CensorEffectScope scope = effect -> {
            admitted.set(true); try { effect.run(); return true; } finally { admitted.set(false); }
        };
        assertEquals("window", scope.read(() -> {
            assertTrue(admitted.get());
            try { return "window"; } finally { assertTrue("Native disposal remains admitted", admitted.get()); }
        }));
        assertFalse(admitted.get()); assertNull(CensorEffectScope.DEVICE.read(() -> null));
    }

    private static final class Queue implements Executor, LatestReconciliation.Delivery {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable action) { tasks.addLast(action); }
        @Override public boolean post(Runnable action) { execute(action); return true; }
        void next() { tasks.removeFirst().run(); }
    }
    private static final class Harness {
        final Queue worker = new Queue(), main = new Queue();
        final List<Integer> commits = new ArrayList<>(); final List<Throwable> faults = new ArrayList<>();
        Supplier<Integer> read = () -> 1; Consumer<Integer> commit = commits::add;
        int deferrals; RuntimeException deferFailure;
        final LatestReconciliation<CensorReconciliation.Selection<Integer>> lane;
        Harness() {
            AtomicReference<LatestReconciliation<CensorReconciliation.Selection<Integer>>> ref = new AtomicReference<>();
            lane = new LatestReconciliation<>(worker, main, () -> CensorReconciliation.read(() -> read.get()),
                    selected -> CensorReconciliation.commit(selected, value -> commit.accept(value), () -> {
                        deferrals++; ref.get().invalidate(); if (deferFailure != null) throw deferFailure;
                    }), faults::add);
            ref.set(lane);
        }
        void request() { lane.request(); worker.next(); main.next(); }
    }
}
