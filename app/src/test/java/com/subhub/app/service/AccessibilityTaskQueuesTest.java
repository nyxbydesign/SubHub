package com.subhub.app.service;

import static org.junit.Assert.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class AccessibilityTaskQueuesTest {
    @Test public void mainRemovalDetachesOwnerButNeverClaimsRawCallbackReturned() {
        AccessibilityTaskQueues owner = new AccessibilityTaskQueues(); Queue q = new Queue();
        AtomicInteger ran = new AtomicInteger(), closed = new AtomicInteger(); Runnable action = ran::incrementAndGet;
        owner.post(q, action); owner.removeCallbacks(action); owner.seal(closed::incrementAndGet);
        assertTrue(owner.drained()); assertEquals(1, closed.get()); assertEquals(1, q.callbacks.size());
        q.run(); assertEquals(0, ran.get()); assertTrue(owner.drained());
    }
    @Test public void canceledWhileRunningWaitsActualBodyReturn() throws Exception {
        AccessibilityTaskQueues owner = new AccessibilityTaskQueues(); Queue q = new Queue();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Runnable action = () -> { entered.countDown(); try { release.await(); } catch (InterruptedException e) { throw new AssertionError(e); } };
        owner.post(q, action); Thread worker = new Thread(q::run); worker.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); owner.removeCallbacks(action); owner.seal(() -> {});
            assertFalse(owner.drained());
        } finally { release.countDown(); worker.join(5000); }
        assertFalse(worker.isAlive()); assertTrue(owner.drained());
    }
    @Test public void inlineBodyReturnStillWaitsDispatchReturn() {
        AccessibilityTaskQueues owner = new AccessibilityTaskQueues();
        owner.post(callback -> { callback.run(); owner.seal(() -> {}); assertFalse(owner.drained()); return true; }, () -> {});
        assertTrue(owner.drained());
    }
    @Test public void refusedMainCallbackReleasesWithoutInventingExecution() {
        AccessibilityTaskQueues owner = new AccessibilityTaskQueues(); AtomicInteger ran = new AtomicInteger();
        assertFalse(owner.post(callback -> false, ran::incrementAndGet)); owner.seal(() -> {});
        assertTrue(owner.drained()); assertEquals(0, ran.get());
    }
    @Test public void acceptedThenThrowRetainsCallbackAndFailure() {
        AccessibilityTaskQueues owner = new AccessibilityTaskQueues(); Queue q = new Queue();
        assertThrows(IllegalStateException.class, () -> owner.post(callback -> { q.post(callback); throw new IllegalStateException(); }, () -> {}));
        owner.seal(() -> {}); assertTrue(owner.outstanding() > 0); q.run();
        assertEquals(0, owner.outstanding()); assertFalse(owner.drained()); assertTrue(owner.failed());
    }
    @Test public void scheduledCancellationDetachesBeforeCancelingNeutralWrapper() {
        AccessibilityTaskQueues owner = new AccessibilityTaskQueues(); ManualScheduler raw = new ManualScheduler();
        ScheduledExecutorService lane = owner.wrap(raw); AtomicInteger ran = new AtomicInteger();
        ScheduledFuture<?> future = lane.schedule(ran::incrementAndGet, 1000, TimeUnit.MILLISECONDS);
        assertEquals(1000, raw.delay); assertTrue(future.cancel(false)); assertTrue(future.isCancelled());
        owner.seal(() -> {}); assertTrue(owner.drained()); raw.run();
        assertEquals(0, ran.get()); assertTrue(owner.drained());
    }
    @Test public void independentLanesRetainConcurrencyWithoutEffectLock() throws Exception {
        AccessibilityTaskQueues owner = new AccessibilityTaskQueues(); ManualScheduler a = new ManualScheduler(), b = new ManualScheduler();
        CountDownLatch both = new CountDownLatch(2), release = new CountDownLatch(1);
        Runnable nativeBody = () -> { both.countDown(); try { release.await(); } catch (InterruptedException e) { throw new AssertionError(e); } };
        owner.wrap(a).execute(nativeBody); owner.wrap(b).execute(nativeBody);
        Thread first = new Thread(a::run), second = new Thread(b::run); first.start(); second.start();
        try { assertTrue(both.await(5, TimeUnit.SECONDS)); owner.seal(() -> {}); assertFalse(owner.drained()); }
        finally { release.countDown(); first.join(5000); second.join(5000); }
        assertFalse(first.isAlive()); assertFalse(second.isAlive()); assertTrue(owner.drained());
    }
    @Test public void rejectionAfterEnqueueIsNotTreatedAsDefiniteRefusal() {
        AccessibilityTaskQueues owner = new AccessibilityTaskQueues(); ManualScheduler raw = new ManualScheduler(); raw.throwAfterEnqueue = true;
        assertThrows(RejectedExecutionException.class, () -> owner.wrap(raw).execute(() -> {}));
        owner.seal(() -> {}); assertTrue(owner.outstanding() > 0); raw.run();
        assertEquals(0, owner.outstanding()); assertFalse(owner.drained());
    }
    @Test public void legacyShutdownNowPreservesInterruptCallWithoutClaimingDrain() {
        AccessibilityTaskQueues owner = new AccessibilityTaskQueues(); ManualScheduler raw = new ManualScheduler();
        ScheduledExecutorService lane = owner.wrapLegacy(raw); lane.execute(() -> {}); lane.shutdownNow();
        assertEquals(1, raw.immediateShutdowns); owner.seal(() -> {}); assertFalse(owner.drained());
    }
    @Test public void cancelledScheduledBodyStillOwnsRunningNativeWork() throws Exception {
        AccessibilityTaskQueues owner = new AccessibilityTaskQueues(); ManualScheduler raw = new ManualScheduler();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ScheduledFuture<?> future = owner.wrap(raw).schedule(() -> {
            entered.countDown(); try { release.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
        }, 0, TimeUnit.MILLISECONDS);
        Thread worker = new Thread(raw::run); worker.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); assertTrue(future.cancel(false));
            assertTrue(future.isDone()); owner.seal(() -> {}); assertFalse(owner.drained());
        } finally { release.countDown(); worker.join(5000); }
        assertFalse(worker.isAlive()); assertTrue(owner.drained());
    }
    @Test public void falseAfterInlineExecutionIsNotSuccessfulDrain() {
        AccessibilityTaskQueues owner = new AccessibilityTaskQueues();
        assertThrows(IllegalStateException.class, () -> owner.post(callback -> { callback.run(); return false; }, () -> {}));
        owner.seal(() -> {}); assertEquals(0, owner.outstanding()); assertTrue(owner.failed()); assertFalse(owner.drained());
    }
    @Test public void beforeBodyDetachStillWaitsActualDispatchReturn() {
        AccessibilityTaskQueues owner = new AccessibilityTaskQueues(); Queue q = new Queue(); Runnable action = () -> fail("detached");
        owner.post(callback -> {
            q.post(callback); owner.removeCallbacks(action); owner.seal(() -> {});
            assertFalse(owner.drained()); return true;
        }, action);
        assertTrue(owner.drained()); q.run();
    }
    @Test public void longDeadlineCancellationChurnLeavesOnlyOwnerFreeRelays() throws Exception {
        AccessibilityTaskQueues owner = new AccessibilityTaskQueues(); Queue q = new Queue();
        for (int i = 0; i < 1000; i++) {
            Runnable action = new Runnable() { @Override public void run() { fail("cancelled payload"); } };
            owner.post(q, action); owner.removeCallbacks(action);
        }
        owner.seal(() -> {}); assertTrue(owner.drained()); assertEquals(1000, q.callbacks.size());
        for (Runnable callback : q.callbacks) {
            assertTrue(java.lang.reflect.Modifier.isStatic(callback.getClass().getModifiers()));
            java.lang.reflect.Field target = callback.getClass().getDeclaredField("target"); target.setAccessible(true);
            assertNull(target.get(callback));
        }
        while (!q.callbacks.isEmpty()) q.run(); assertTrue(owner.drained());
    }
    @Test public void concurrentCancelCannotRetireEnteredBody() throws Exception {
        for (int i = 0; i < 100; i++) {
            AccessibilityTaskQueues owner = new AccessibilityTaskQueues(); Queue q = new Queue();
            AtomicInteger bodies = new AtomicInteger();
            Runnable action = () -> { bodies.incrementAndGet(); assertFalse(owner.drained()); };
            java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
            owner.post(q, action); Thread callback = new Thread(() -> {
                try { q.run(); } catch (Throwable error) { failure.set(error); }
            }); callback.start();
            owner.removeCallbacks(action); owner.seal(() -> {}); callback.join(5000);
            assertFalse(callback.isAlive()); if (failure.get() != null) throw new AssertionError(failure.get());
            assertTrue(bodies.get() <= 1); assertTrue(owner.drained());
        }
    }
    @Test public void concurrentDetachAndFalseReceiptCannotInventEnteredBody() throws Exception {
        for (int i = 0; i < 100; i++) {
            AccessibilityTaskQueues owner = new AccessibilityTaskQueues(); Runnable action = () -> fail("must not enter");
            java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<Thread> cancellation = new java.util.concurrent.atomic.AtomicReference<>();
            assertFalse(owner.post(relay -> {
                try {
                    java.lang.reflect.Field field = relay.getClass().getDeclaredField("target"); field.setAccessible(true);
                    Object task; synchronized (relay) { task = field.get(relay); }
                    synchronized (task) {
                        Thread cancel = new Thread(() -> { try { owner.removeCallbacks(action); } catch (Throwable e) { error.set(e); } });
                        cancellation.set(cancel); cancel.start();
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                        while (true) {
                            synchronized (relay) { if (field.get(relay) == null) break; }
                            if (System.nanoTime() > deadline) throw new AssertionError("detach did not occur");
                            Thread.yield();
                        }
                        return false;
                    }
                } catch (ReflectiveOperationException reflection) { throw new AssertionError(reflection); }
            }, action));
            cancellation.get().join(5000); assertFalse(cancellation.get().isAlive());
            if (error.get() != null) throw new AssertionError(error.get());
            owner.seal(() -> {}); assertFalse(owner.failed()); assertTrue(owner.drained());
        }
    }
    private static final class Queue implements OwnedCaptureLifetime.Dispatcher {
        final ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
        @Override public boolean post(Runnable action) { callbacks.add(action); return true; }
        void run() { callbacks.remove().run(); }
    }
    private static final class ManualScheduler extends AbstractExecutorService implements ScheduledExecutorService {
        final ArrayDeque<Runnable> callbacks = new ArrayDeque<>(); boolean shutdown, throwAfterEnqueue; long delay; int immediateShutdowns;
        @Override public void execute(Runnable action) { schedule(action, 0, TimeUnit.MILLISECONDS); }
        @Override public ScheduledFuture<?> schedule(Runnable action, long delay, TimeUnit unit) {
            this.delay = unit.toMillis(delay); callbacks.add(action);
            if (throwAfterEnqueue) throw new RejectedExecutionException("accepted then threw");
            return new ScheduledFuture<Object>() {
                @Override public long getDelay(TimeUnit target) { return target.convert(delay, unit); }
                @Override public int compareTo(Delayed other) { return 0; }
                @Override public boolean cancel(boolean interrupt) { assertFalse(interrupt); return true; }
                @Override public boolean isCancelled() { return false; }
                @Override public boolean isDone() { return false; }
                @Override public Object get() { throw new UnsupportedOperationException(); }
                @Override public Object get(long t, TimeUnit u) { throw new UnsupportedOperationException(); }
            };
        }
        void run() { callbacks.remove().run(); }
        @Override public <V> ScheduledFuture<V> schedule(Callable<V> action, long delay, TimeUnit unit) { throw new UnsupportedOperationException(); }
        @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable r, long i, long p, TimeUnit u) { throw new UnsupportedOperationException(); }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable r, long i, long p, TimeUnit u) { throw new UnsupportedOperationException(); }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { immediateShutdowns++; shutdown = true; List<Runnable> removed = new ArrayList<>(callbacks); callbacks.clear(); return removed; }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && callbacks.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }
}
