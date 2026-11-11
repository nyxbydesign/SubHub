package com.subhub.app.service;

import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;

public final class LatestFrameBrokerAsyncTest {
    static final class Queue implements java.util.concurrent.Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        public void execute(Runnable task) { tasks.add(task); }
        void one() { tasks.remove().run(); }
    }
    @Test public void currentFrameWaitsForFinalUseAndNewestPendingReplacesOnlyPending() {
        Queue queue = new Queue(); List<Integer> processed = new ArrayList<>(), disposed = new ArrayList<>();
        AtomicReference<LatestFrameBroker.Completion> done = new AtomicReference<>();
        LatestFrameBroker.Async<Integer> broker = LatestFrameBroker.async(queue, (v,c) -> { processed.add(v); done.set(c); }, disposed::add);
        broker.submit(1); queue.one(); broker.submit(2); broker.submit(3);
        assertEquals(List.of(1), processed); assertEquals(List.of(2), disposed); assertEquals(1, broker.droppedCount());
        assertTrue(queue.tasks.isEmpty()); done.get().complete(() -> assertEquals(List.of(2), disposed));
        assertEquals(List.of(2,1), disposed); queue.one(); assertEquals(List.of(1,3), processed);
        done.get().complete(() -> {}); broker.close(); assertTrue(broker.managedResourcesDrained());
    }
    @Test public void completionBeforeProcessorReturnDoesNotDisposeOrStartSuccessor() {
        List<Integer> disposed = new ArrayList<>(); AtomicReference<LatestFrameBroker.Async<Integer>> ref = new AtomicReference<>();
        ref.set(LatestFrameBroker.async(Runnable::run, (v,c) -> {
            c.complete(() -> {}); assertTrue(disposed.isEmpty()); ref.get().close(); assertFalse(ref.get().managedResourcesDrained());
        }, disposed::add));
        ref.get().submit(1); assertEquals(List.of(1), disposed); assertTrue(ref.get().managedResourcesDrained());
    }
    @Test public void directExecutorWaitsForSubmissionReturnBeforeNextFrame() {
        List<Integer> events = new ArrayList<>(); AtomicInteger depth = new AtomicInteger();
        AtomicReference<LatestFrameBroker.Async<Integer>> ref = new AtomicReference<>();
        ref.set(LatestFrameBroker.async(task -> { assertEquals(0, depth.getAndIncrement()); try { task.run(); } finally { depth.decrementAndGet(); } }, (v,c) -> {
            events.add(v); if (v==1) ref.get().submit(2); c.complete(() -> {});
        }, ignored -> {}));
        ref.get().submit(1); assertEquals(List.of(1,2), events); ref.get().close(); assertTrue(ref.get().managedResourcesDrained());
    }
    @Test public void heldFinalUseKeepsCurrentAndPendingWithoutRunningAnotherFrame() throws Exception {
        Queue queue = new Queue(); AtomicReference<LatestFrameBroker.Completion> done = new AtomicReference<>(); List<Integer> disposed = new ArrayList<>();
        LatestFrameBroker.Async<Integer> broker = LatestFrameBroker.async(queue, (v,c) -> done.set(c), disposed::add);
        broker.submit(1); queue.one(); broker.submit(2);
        CountDownLatch entered = new CountDownLatch(1), leave = new CountDownLatch(1); AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread completion = new Thread(() -> { try { done.get().complete(() -> { entered.countDown(); await(leave); }); } catch (Throwable bad) { failure.set(bad); } });
        completion.start();
        try { assertTrue(entered.await(5,TimeUnit.SECONDS)); assertTrue(disposed.isEmpty()); assertTrue(queue.tasks.isEmpty()); broker.close(); assertEquals(List.of(2),disposed); assertFalse(broker.managedResourcesDrained()); }
        finally { leave.countDown(); completion.join(5000); }
        assertFalse(completion.isAlive()); assertNull(failure.get()); assertEquals(List.of(2,1),disposed); assertTrue(broker.managedResourcesDrained());
    }
    @Test public void stopDoesNotCancelAcceptedDispatch() {
        Queue queue = new Queue(); List<Integer> disposed = new ArrayList<>();
        LatestFrameBroker.Async<Integer> broker = LatestFrameBroker.async(queue,(v,c)->fail("Closed before dispatch"), disposed::add);
        broker.submit(1); broker.close(); assertEquals(List.of(1), disposed); assertFalse(broker.managedResourcesDrained());
        queue.one(); assertTrue(broker.managedResourcesDrained());
    }
    @Test public void stopWithRunningNativeWorkWaitsForActualCompletion() {
        Queue queue = new Queue(); AtomicReference<LatestFrameBroker.Completion> done = new AtomicReference<>(); List<Integer> disposed = new ArrayList<>();
        LatestFrameBroker.Async<Integer> broker = LatestFrameBroker.async(queue,(v,c)->done.set(c),disposed::add);
        broker.submit(1); queue.one(); broker.close(); assertFalse(broker.managedResourcesDrained()); assertTrue(disposed.isEmpty());
        done.get().complete(()->{}); assertEquals(List.of(1),disposed); assertTrue(broker.managedResourcesDrained());
    }
    @Test public void duplicateLateCompletionDoesNotRunFinalUseOrDisposeAgain() {
        Queue queue = new Queue(); AtomicReference<LatestFrameBroker.Completion> done = new AtomicReference<>(); AtomicInteger disposed = new AtomicInteger();
        LatestFrameBroker.Async<Integer> broker = LatestFrameBroker.async(queue,(v,c)->done.set(c),v->disposed.incrementAndGet());
        broker.submit(1); queue.one(); LatestFrameBroker.Completion exact = done.get(); exact.complete(()->{}); broker.close();
        exact.complete(()->fail("Duplicate use")); assertEquals(1,disposed.get()); assertTrue(broker.managedResourcesDrained());
    }
    @Test public void enqueueThenThrowRetainsUnknownDispatchAndNeverClaimsDrain() {
        Queue queue = new Queue(); AtomicInteger disposed = new AtomicInteger();
        LatestFrameBroker.Async<Integer> broker = LatestFrameBroker.async(task->{queue.execute(task);throw new IllegalStateException();},(v,c)->fail("Fenced"),v->disposed.incrementAndGet());
        assertThrows(IllegalStateException.class,()->broker.submit(1)); broker.close(); assertFalse(broker.managedResourcesDrained());
        queue.one(); assertEquals(1,disposed.get()); assertFalse(broker.managedResourcesDrained()); assertTrue(broker.failed());
    }
    @Test public void throwBeforeEnqueueIsNotAssumedNoDispatch() {
        LatestFrameBroker.Async<Integer> broker = LatestFrameBroker.async(task->{throw new IllegalStateException();},(v,c)->fail("No delivery"),v->{});
        assertThrows(IllegalStateException.class,()->broker.submit(1)); broker.close(); assertFalse(broker.managedResourcesDrained()); assertTrue(broker.failed());
    }
    @Test public void runThenThrowCannotBecomeDrain() {
        AtomicInteger disposed = new AtomicInteger(); LatestFrameBroker.Async<Integer> broker = LatestFrameBroker.async(task->{task.run();throw new IllegalStateException();},(v,c)->c.complete(()->{}),v->disposed.incrementAndGet());
        assertThrows(IllegalStateException.class,()->broker.submit(1)); broker.close(); assertEquals(1,disposed.get()); assertFalse(broker.managedResourcesDrained());
    }
    @Test public void processorThrowAfterQueuingCompletionDoesNotAssumeCompletionWasCancelled() {
        AtomicReference<LatestFrameBroker.Completion> done = new AtomicReference<>(); AtomicInteger disposed = new AtomicInteger();
        LatestFrameBroker.Async<Integer> broker = LatestFrameBroker.async(Runnable::run,(v,c)->{done.set(c);throw new IllegalStateException();},v->disposed.incrementAndGet());
        assertThrows(IllegalStateException.class,()->broker.submit(1)); broker.close(); assertEquals(0,disposed.get());
        done.get().complete(()->{}); assertEquals(1,disposed.get()); assertFalse(broker.managedResourcesDrained());
    }
    @Test public void failedFinalUseIsNotRetriedOrAcceptedAsDrain() {
        AtomicReference<LatestFrameBroker.Completion> done = new AtomicReference<>(); AtomicInteger disposed = new AtomicInteger();
        LatestFrameBroker.Async<Integer> broker = LatestFrameBroker.async(Runnable::run,(v,c)->done.set(c),v->disposed.incrementAndGet());
        broker.submit(1); assertThrows(AssertionError.class,()->done.get().complete(()->{throw new AssertionError();}));
        done.get().complete(()->fail("Replay")); broker.close(); assertEquals(1,disposed.get()); assertFalse(broker.managedResourcesDrained());
    }
    @Test public void failedDisposalRemainsOccupiedAndIsNotRetried() {
        AtomicInteger disposals = new AtomicInteger(); LatestFrameBroker.Async<Integer> broker = LatestFrameBroker.async(Runnable::run,(v,c)->c.complete(()->{}),v->{disposals.incrementAndGet();throw new IllegalStateException();});
        assertThrows(IllegalStateException.class,()->broker.submit(1)); broker.close(); broker.close(); assertEquals(1,disposals.get()); assertFalse(broker.managedResourcesDrained());
    }
    @Test public void closeDuringPendingDisposalCannotReportDrainEarly() throws Exception {
        Queue queue = new Queue(); CountDownLatch entered = new CountDownLatch(1), leave = new CountDownLatch(1); AtomicReference<Throwable> failure = new AtomicReference<>();
        LatestFrameBroker.Async<Integer> broker = LatestFrameBroker.async(queue,(v,c)->fail("No delivery"),v->{entered.countDown();await(leave);});
        broker.submit(1); Thread close = new Thread(()->{try{broker.close();}catch(Throwable bad){failure.set(bad);}});close.start();
        try { assertTrue(entered.await(5,TimeUnit.SECONDS)); queue.one(); assertFalse(broker.managedResourcesDrained()); }
        finally { leave.countDown(); close.join(5000); }
        assertFalse(close.isAlive()); assertNull(failure.get()); assertTrue(broker.managedResourcesDrained());
    }
    @Test public void retainedQueueAndCompletionCallbacksDetachTheirOwnerTargets() throws Exception {
        Queue queue = new Queue(); AtomicReference<LatestFrameBroker.Completion> completion = new AtomicReference<>();
        LatestFrameBroker.Async<Integer> broker = LatestFrameBroker.async(queue,(v,c)->completion.set(c),v->{});
        broker.submit(1); Runnable dispatch = queue.tasks.remove(); dispatch.run();
        java.lang.reflect.Field owner = dispatch.getClass().getDeclaredField("owner"); owner.setAccessible(true);
        assertNull(owner.get(dispatch));
        LatestFrameBroker.Completion done = completion.get(); done.complete(()->{}); broker.close();
        java.lang.reflect.Field target = done.getClass().getDeclaredField("target"); target.setAccessible(true); assertNull(target.get(done));
        for(java.lang.reflect.Field field : dispatch.getClass().getDeclaredFields()) assertFalse(field.getName().startsWith("this$"));
        for(java.lang.reflect.Field field : done.getClass().getDeclaredFields()) assertFalse(field.getName().startsWith("this$"));
        dispatch.run(); done.complete(()->fail("Detached duplicate")); assertTrue(broker.managedResourcesDrained());
    }
    @Test public void actualExecuteReturnIsRequiredAfterInlineCompletedAndClosedBody() {
        AtomicReference<LatestFrameBroker.Async<Integer>> ref = new AtomicReference<>();
        ref.set(LatestFrameBroker.async(task->{task.run();assertFalse(ref.get().managedResourcesDrained());},(v,c)->{c.complete(()->{});ref.get().close();},v->{}));
        ref.get().submit(1); assertTrue(ref.get().managedResourcesDrained());
    }
    @Test public void pendingDisposeFailureIsRetainedAndNotReplayedOnClose() throws Exception {
        Queue queue = new Queue(); AtomicInteger disposals = new AtomicInteger();
        LatestFrameBroker.Async<Integer> broker = LatestFrameBroker.async(queue,(v,c)->fail("Fenced"),v->{disposals.incrementAndGet();throw new IllegalStateException();});
        broker.submit(1); assertThrows(IllegalStateException.class,()->broker.submit(2));
        assertThrows(IllegalStateException.class,broker::close); broker.close(); queue.one();
        assertEquals(2,disposals.get()); assertFalse(broker.managedResourcesDrained());
        java.lang.reflect.Field failures = broker.getClass().getDeclaredField("failedDisposals");failures.setAccessible(true);
        assertEquals(List.of(1,2),failures.get(broker));
    }
    private static void await(CountDownLatch latch) { try { assertTrue(latch.await(5,TimeUnit.SECONDS)); } catch(InterruptedException interrupted){throw new AssertionError(interrupted);} }
}
