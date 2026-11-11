package com.subhub.app.censor.android;

import static org.junit.Assert.*;
import com.subhub.app.censor.engine.LatestReconciliation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class DeferredCensorDemandTest {
    @Test public void actualReconcilerRetriesBusyReadWithoutNewUserEvent() {
        ArrayDeque<Runnable> worker = new ArrayDeque<>(), main = new ArrayDeque<>();
        Scheduler delayed = new Scheduler(); AtomicBoolean busy = new AtomicBoolean(true);
        AtomicInteger committed = new AtomicInteger(), failed = new AtomicInteger();
        final class Harness {
            final DeferredCensorDemand retry = new DeferredCensorDemand(delayed, this::request, failed::incrementAndGet);
            final LatestReconciliation<CensorReconciliation.Selection<Integer>> reconciliation = new LatestReconciliation<>(
                    worker::add, action -> { main.add(action); return true; },
                    () -> CensorReconciliation.read(() -> { if (busy.get()) throw new CensorEffectScope.Unavailable(); return 1; }),
                    selected -> CensorReconciliation.commit(selected, value -> { committed.addAndGet(value); retry.succeeded(); },
                            this::defer), error -> failed.incrementAndGet());
            void request() { reconciliation.request(); }
            void defer() { reconciliation.invalidate(); retry.defer(); }
        }
        Harness h = new Harness(); h.reconciliation.request(); worker.remove().run(); main.remove().run();
        assertEquals(0, committed.get()); assertEquals(1, delayed.tasks.size());
        delayed.tasks.remove().run(); worker.remove().run(); main.remove().run();
        assertEquals(List.of(16L, 32L), delayed.delays); assertEquals(0, failed.get());
        busy.set(false); delayed.tasks.remove().run(); worker.remove().run(); main.remove().run();
        assertEquals(1, committed.get()); assertTrue(delayed.tasks.isEmpty());
    }
    @Test public void cancelMakesRemovedAndLateCallbacksInert() {
        Scheduler scheduler = new Scheduler(); AtomicInteger requested = new AtomicInteger();
        DeferredCensorDemand retry = new DeferredCensorDemand(scheduler, requested::incrementAndGet, () -> fail("Rejected"));
        retry.defer(); Runnable old = scheduler.tasks.element(); retry.cancel(); old.run(); assertEquals(0, requested.get());
        retry.defer(); assertEquals(1, scheduler.tasks.size()); retry.close(); old.run();
        assertTrue(retry.drained()); assertTrue(scheduler.tasks.isEmpty()); assertEquals(0, requested.get());
    }
    @Test public void coalescesDemandWithBoundedBackoffAndResetsOnSuccess() {
        Scheduler scheduler = new Scheduler(); DeferredCensorDemand retry = new DeferredCensorDemand(scheduler, () -> {}, () -> fail("Rejected"));
        for (int i = 0; i < 12; i++) { retry.defer(); retry.defer(); assertEquals(1, scheduler.tasks.size()); scheduler.tasks.remove().run(); }
        assertEquals(Long.valueOf(250), scheduler.delays.get(11)); retry.succeeded(); retry.defer();
        assertEquals(Long.valueOf(16), scheduler.delays.get(12)); retry.close(); assertTrue(retry.drained());
    }
    @Test public void reentrantCloseDoesNotClaimDrainBeforeRequestReturns() {
        Scheduler scheduler = new Scheduler();
        final class Owner {
            final DeferredCensorDemand retry = new DeferredCensorDemand(scheduler, this::request, () -> fail("Rejected"));
            void request() { retry.close(); assertFalse(retry.drained()); }
        }
        Owner owner = new Owner(); owner.retry.defer(); scheduler.tasks.remove().run(); assertTrue(owner.retry.drained());
    }
    @Test public void definitePostFailureInvokesTerminalFailure() {
        AtomicInteger failed = new AtomicInteger();
        DeferredCensorDemand retry = new DeferredCensorDemand(new DeferredCensorDemand.Scheduler() {
            public boolean post(Runnable command, long delay) { return false; }
            public void remove(Runnable command) { fail("No accepted command"); }
        }, () -> fail("Not admitted"), failed::incrementAndGet);
        retry.defer(); assertEquals(1, failed.get()); retry.close(); assertTrue(retry.drained());
    }
    @Test public void uncertainAcceptedPostStaysOwnedAndCannotRequestAfterFailure() {
        ArrayDeque<Runnable> accepted = new ArrayDeque<>(); AtomicInteger failures = new AtomicInteger();
        DeferredCensorDemand retry = new DeferredCensorDemand(new DeferredCensorDemand.Scheduler() {
            public boolean post(Runnable command, long delay) { accepted.add(command); throw new IllegalStateException("Accepted"); }
            public void remove(Runnable command) { accepted.remove(command); }
        }, () -> fail("Failure sealed this demand"), failures::incrementAndGet);
        assertThrows(IllegalStateException.class, retry::defer); assertFalse(retry.drained());
        retry.defer(); assertEquals(1, accepted.size()); accepted.remove().run();
        assertTrue(retry.drained()); assertEquals(1, failures.get());
    }
    static final class Scheduler implements DeferredCensorDemand.Scheduler {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>(); final List<Long> delays = new ArrayList<>();
        public boolean post(Runnable command, long delay) { tasks.add(command); delays.add(delay); return true; }
        public void remove(Runnable command) { tasks.remove(command); }
    }
}
