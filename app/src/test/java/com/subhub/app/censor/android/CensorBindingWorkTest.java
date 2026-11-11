package com.subhub.app.censor.android;

import static org.junit.Assert.*;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class CensorBindingWorkTest {
    @Test public void closeWaitsActualCallbacksAndRejectsLaterRegistration() {
        CensorBindingWork work = new CensorBindingWork(); CensorBindingWork.Ticket one = work.begin(), two = work.begin();
        AtomicInteger closed = new AtomicInteger(); work.seal(closed::incrementAndGet);
        assertNull(work.begin()); assertFalse(one.current()); assertEquals(0, closed.get());
        one.close(); one.close(); assertEquals(1, work.outstanding()); assertEquals(0, closed.get());
        two.close(); assertEquals(1, closed.get()); assertEquals(0, work.outstanding());
    }
    @Test public void noCallbackNeverProducesDrainEvenIfExecutorThrows() {
        CensorBindingWork work = new CensorBindingWork(); CensorBindingWork.Ticket ticket = work.begin();
        AtomicInteger closed = new AtomicInteger(); work.seal(closed::incrementAndGet);
        assertEquals(1, work.outstanding()); assertEquals(0, closed.get());
        ticket.close(); assertEquals(1, closed.get());
    }
    @Test public void queuedThenThrowRetainsActualCompletionObligation() {
        CensorBindingWork work = new CensorBindingWork(); CensorBindingWork.Ticket ticket = work.begin();
        ArrayDeque<Runnable> queued = new ArrayDeque<>(); AtomicInteger effect = new AtomicInteger(), drained = new AtomicInteger();
        try { queued.add(() -> { try { if (ticket.current()) effect.incrementAndGet(); } finally { ticket.close(); } });
            throw new IllegalStateException("Accepted, then threw"); } catch (IllegalStateException expected) { }
        work.seal(drained::incrementAndGet); assertEquals(0, drained.get());
        queued.remove().run(); assertEquals(0, effect.get()); assertEquals(1, drained.get());
    }
    @Test public void sealReturnsWithoutWaitingForRunningCallback() throws Exception {
        CensorBindingWork work = new CensorBindingWork(); CensorBindingWork.Ticket ticket = work.begin();
        AtomicInteger drained = new AtomicInteger(); CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        Thread callback = new Thread(() -> { entered.countDown(); try {
            if (!finish.await(3, TimeUnit.SECONDS)) throw new AssertionError();
        } catch (InterruptedException failure) { throw new AssertionError(failure); } finally { ticket.close(); } });
        callback.start(); assertTrue(entered.await(3, TimeUnit.SECONDS));
        work.seal(drained::incrementAndGet); assertEquals(0, drained.get());
        finish.countDown(); callback.join(3000); assertFalse(callback.isAlive()); assertEquals(1, drained.get());
    }
    @Test public void completionRunsOutsideMetadataMonitor() throws Exception {
        CensorBindingWork work = new CensorBindingWork(); AtomicInteger observed = new AtomicInteger();
        work.seal(() -> { Thread other = new Thread(() -> { assertNull(work.begin()); observed.incrementAndGet(); });
            other.start(); try { other.join(1000); } catch (InterruptedException error) { throw new AssertionError(error); }
            assertFalse(other.isAlive()); });
        assertEquals(1, observed.get());
    }
}
