package com.subhub.app.capture;

import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;

/** Pure actual lifetime controller; no Android bitmap/draw or native closure claim. */
public final class CustomImagePoolLifetimeTest {
    @Test public void closeWaitsForQueuedLoadAndDoesNotCancel() {
        Queue q = new Queue(); List<String> disposed = new ArrayList<>();
        CustomImagePool.State<String> state = new CustomImagePool.State<>(q, disposed::add);
        AtomicInteger loads = new AtomicInteger();
        state.request(obsolete -> { loads.incrementAndGet(); return List.of("image"); }, null);
        state.close(); assertFalse(state.drained()); q.drain();
        assertEquals(0, loads.get()); assertTrue(state.drained()); assertTrue(disposed.isEmpty());
    }
    @Test public void heldDrawPreventsRecycleAndBlocksExtraGeneration() throws Exception {
        Queue q = new Queue(); List<String> disposed = new CopyOnWriteArrayList<>();
        CustomImagePool.State<String> state = new CustomImagePool.State<>(q, disposed::add);
        state.seed(List.of("old")); CountDownLatch read = new CountDownLatch(1), leave = new CountDownLatch(1);
        Thread draw = new Thread(() -> state.read(1, value -> { assertEquals("old", value); read.countDown(); await(leave); }));
        draw.start();
        try {
            assertTrue(read.await(5, TimeUnit.SECONDS));
            state.request(obsolete -> List.of("new"), null); q.drain(); assertTrue(disposed.isEmpty());
            state.request(obsolete -> List.of("discarded descriptor"), null);
            state.request(obsolete -> List.of("latest"), null);
            assertTrue(q.tasks.isEmpty()); assertTrue(state.pending());
        } finally { leave.countDown(); draw.join(5000); }
        assertFalse(draw.isAlive()); q.drain();
        assertEquals(List.of("old", "new"), disposed);
        assertEquals("latest", state.unleased(1)); state.close(); q.drain(); assertTrue(state.drained());
    }
    @Test public void entireDrawBorrowsOneSetUntilReturnedAcrossReloadAndSeal() {
        Queue q = new Queue(); List<String> disposed = new ArrayList<>();
        CustomImagePool.State<String> state = new CustomImagePool.State<>(q, disposed::add);
        state.seed(List.of("old")); CustomImagePool.State<String>.Read drawing = state.borrow();
        state.request(obsolete -> List.of("new"), null); q.drain();
        assertEquals("old", drawing.image(0)); assertEquals("new", state.unleased(0));
        state.close(); assertFalse(state.drained()); assertEquals(List.of("new"), disposed);
        assertEquals("old", drawing.image(0)); drawing.close(); drawing.close();
        assertThrows(IllegalStateException.class, () -> drawing.image(0));
        assertEquals(List.of("new", "old"), disposed); assertTrue(state.drained());
    }
    @Test public void sealedPoolStillWaitsForReadBodyActualReturn() throws Exception {
        Queue q = new Queue(); AtomicInteger disposed = new AtomicInteger();
        CustomImagePool.State<Object> state = new CustomImagePool.State<>(q, value -> disposed.incrementAndGet());
        state.seed(List.of(new Object())); CountDownLatch read = new CountDownLatch(1), leave = new CountDownLatch(1);
        Thread draw = new Thread(() -> state.read(0, value -> { read.countDown(); await(leave); })); draw.start();
        try {
            assertTrue(read.await(5, TimeUnit.SECONDS)); state.close();
            assertFalse(state.read(0, value -> fail("Sealed read"))); assertFalse(state.drained()); assertEquals(0, disposed.get());
        } finally { leave.countDown(); draw.join(5000); }
        assertEquals(1, disposed.get()); assertTrue(state.drained());
    }
    @Test public void enqueueThenThrowDoesNotForgeDrain() {
        Queue q = new Queue(); q.throwAfterEnqueue = true;
        CustomImagePool.State<String> state = new CustomImagePool.State<>(q, value -> {});
        assertThrows(IllegalStateException.class, () -> state.request(obsolete -> List.of("image"), null));
        state.close(); assertFalse(state.drained()); q.drain(); assertTrue(state.drained());
    }
    @Test public void unknownRejectionWithNoCallbackRemainsOccupied() {
        Queue q = new Queue(); q.throwBeforeEnqueue = true;
        CustomImagePool.State<String> state = new CustomImagePool.State<>(q, value -> {});
        assertThrows(IllegalStateException.class, () -> state.request(obsolete -> List.of("image"), null));
        state.close(); assertFalse(state.drained());
    }
    @Test public void runInlineThenThrowCountsSubmissionUntilItsActualReturn() {
        Queue q = new Queue(); q.inline = true; q.throwAfterEnqueue = true;
        AtomicReference<CustomImagePool.State<String>> ref = new AtomicReference<>();
        CustomImagePool.State<String> state = new CustomImagePool.State<>(q, value -> {}); ref.set(state);
        assertThrows(IllegalStateException.class, () -> state.request(obsolete -> List.of("image"), () -> {
            ref.get().close(); assertFalse(ref.get().drained());
        }));
        assertTrue(state.drained());
    }
    @Test public void synchronousLoadPreservesFailureAndNeverClaimsCleanup() {
        Queue q = new Queue(); CustomImagePool.State<String> state = new CustomImagePool.State<>(q, value -> {});
        IllegalArgumentException expected = new IllegalArgumentException("load");
        assertSame(expected, assertThrows(IllegalArgumentException.class, () -> state.loadNow(obsolete -> { throw expected; }, null)));
        state.close(); assertTrue(state.failed()); assertFalse(state.drained());
    }
    @Test public void releaseFailureDoesNotPreventOtherReleasesButRetainsOccupancy() {
        Queue q = new Queue(); List<String> disposed = new ArrayList<>();
        CustomImagePool.State<String> state = new CustomImagePool.State<>(q, value -> {
            disposed.add(value); if (value.equals("bad")) throw new IllegalStateException();
        });
        state.seed(List.of("bad", "other")); state.close(); state.close();
        assertEquals(List.of("bad", "other"), disposed); assertTrue(state.failed()); assertFalse(state.drained());
    }
    @Test public void explicitPreWorkRefusalRetainsRetryWithoutUnknownResourceFailure() {
        Queue q = new Queue(); CustomImagePool.State<String> state = new CustomImagePool.State<>(q, value -> {});
        state.request(obsolete -> { throw new CustomImagePool.Deferred(); }, null); q.drain();
        assertTrue(state.needsRetry()); assertFalse(state.failed());
        state.request(obsolete -> List.of("retry"), null); q.drain(); assertFalse(state.needsRetry());
        assertEquals("retry", state.unleased(0)); state.close(); assertTrue(state.drained());
    }
    @Test public void obsoleteLoadDisposesItsActualOutputWithoutReadyCallback() {
        Queue q = new Queue(); List<String> disposed = new ArrayList<>();
        CustomImagePool.State<String> state = new CustomImagePool.State<>(q, disposed::add);
        state.request(obsolete -> { state.invalidate(); assertTrue(obsolete.getAsBoolean()); return List.of("old"); }, () -> fail("Obsolete ready"));
        q.drain(); assertEquals(List.of("old"), disposed); assertTrue(state.isEmpty()); state.close(); assertTrue(state.drained());
    }
    @Test public void readinessCallbackAndReentrantCloseMustActuallyReturn() {
        Queue q = new Queue(); CustomImagePool.State<String> state = new CustomImagePool.State<>(q, value -> {});
        state.request(obsolete -> List.of("image"), () -> {
            state.close(); assertFalse(state.drained());
        }); q.drain(); assertTrue(state.drained());
    }
    @Test public void callbackCanOfferSuccessorWithoutLosingDemand() {
        Queue q = new Queue(); List<String> disposed = new ArrayList<>();
        CustomImagePool.State<String> state = new CustomImagePool.State<>(q, disposed::add);
        state.request(obsolete -> List.of("first"), () -> state.request(obsolete -> List.of("second"), null));
        q.drain(); assertEquals("second", state.unleased(0)); assertEquals(List.of("first"), disposed);
        state.close(); assertTrue(state.drained());
    }
    @Test public void shutdownFailureIsLatchedAcrossRepeatedClose() {
        Queue q = new Queue(); q.throwShutdown = true;
        CustomImagePool.State<String> state = new CustomImagePool.State<>(q, value -> {});
        state.seed(List.of("image")); state.close();
        assertTrue(state.failed()); assertFalse(state.drained());
        q.throwShutdown = false; state.close();
        assertFalse(q.isShutdown()); assertFalse(state.drained());
    }
    @Test public void readyFailureRetainsUncertainResourceStateAfterReturnedLoad() {
        Queue q = new Queue(); AtomicInteger disposed = new AtomicInteger();
        CustomImagePool.State<String> state = new CustomImagePool.State<>(q, value -> disposed.incrementAndGet());
        state.request(obsolete -> List.of("image"), () -> { throw new AssertionError("callback"); });
        q.drain(); assertTrue(state.failed()); state.close();
        assertEquals(1, disposed.get()); assertFalse(state.drained());
    }
    @Test public void retiredDisposerMustActuallyReturnBeforeDrain() throws Exception {
        Queue q = new Queue(); CountDownLatch entered = new CountDownLatch(1), leave = new CountDownLatch(1);
        AtomicReference<Throwable> failed = new AtomicReference<>();
        CustomImagePool.State<String> state = new CustomImagePool.State<>(q, value -> { entered.countDown(); await(leave); });
        state.seed(List.of("image")); Thread close = new Thread(() -> {
            try { state.close(); } catch (Throwable error) { failed.set(error); }
        }); close.start();
        try { assertTrue(entered.await(5, TimeUnit.SECONDS)); assertFalse(state.drained()); }
        finally { leave.countDown(); close.join(5000); }
        assertFalse(close.isAlive()); assertNull(failed.get()); assertTrue(state.drained());
    }
    @Test public void supersedeDuringDecodeRejectsOutputAndPreservesPublishedBorrow() {
        Queue q = new Queue(); List<String> disposed = new ArrayList<>();
        CustomImagePool.State<String> state = new CustomImagePool.State<>(q, disposed::add);
        state.seed(List.of("published")); CustomImagePool.State<String>.Read held = state.borrow();
        state.request(obsolete -> {
            state.supersedePending(); assertTrue(obsolete.getAsBoolean());
            assertEquals("published", state.unleased(0)); return List.of("superseded");
        }, () -> fail("Superseded ready"));
        q.drain(); assertEquals("published", state.unleased(0)); assertEquals(List.of("superseded"), disposed);
        state.request(obsolete -> List.of("replacement"), null); q.drain();
        assertEquals("replacement", state.unleased(0)); assertEquals("published", held.image(0));
        state.close(); assertFalse(state.drained()); assertEquals(List.of("superseded", "replacement"), disposed);
        held.close(); assertEquals(List.of("superseded", "replacement", "published"), disposed); assertTrue(state.drained());
    }
    @Test public void repeatedSupersedesCoalesceWithoutStartingOrRetiringCurrent() {
        Queue q = new Queue(); List<String> disposed = new ArrayList<>();
        CustomImagePool.State<String> state = new CustomImagePool.State<>(q, disposed::add);
        state.seed(List.of("published"));
        state.request(obsolete -> { fail("Superseded queued decode"); return List.of(); }, null);
        for (int i = 0; i < 100; i++) state.supersedePending();
        assertEquals(1, q.tasks.size()); assertEquals("published", state.unleased(0)); assertTrue(disposed.isEmpty());
        state.request(obsolete -> List.of("latest"), null); assertEquals(1, q.tasks.size()); q.drain();
        assertEquals("latest", state.unleased(0)); assertEquals(List.of("published"), disposed);
        state.close(); q.drain(); assertTrue(state.drained());
    }
    @Test public void supersedeCannotRetireUncertainDispatchBeforeActualReturn() {
        Queue q = new Queue(); q.throwAfterEnqueue = true;
        CustomImagePool.State<String> state = new CustomImagePool.State<>(q, value -> {});
        assertThrows(IllegalStateException.class, () -> state.request(obsolete -> List.of("never"), null));
        state.supersedePending(); state.close(); assertFalse(state.drained()); q.drain(); assertTrue(state.drained());
    }
    @Test public void supersededOutputDisposalFailureStaysLatchedAfterClose() {
        Queue q = new Queue(); CustomImagePool.State<String> state = new CustomImagePool.State<>(q, value -> { throw new IllegalStateException("Disposal uncertain"); });
        state.request(obsolete -> { state.supersedePending(); return List.of("obsolete"); }, () -> fail("Not current"));
        q.drain(); assertTrue(state.failed()); state.close(); state.close(); assertFalse(state.drained());
    }
    @Test public void supersedeAfterCloseCannotReviveDescriptorsOrImages() {
        Queue q = new Queue(); CustomImagePool.State<String> state = new CustomImagePool.State<>(q, value -> {});
        state.close(); state.supersedePending(); state.request(obsolete -> { fail("Closed load"); return List.of(); }, null);
        assertTrue(q.tasks.isEmpty()); assertTrue(state.isEmpty()); assertTrue(state.drained());
    }
    @Test public void supersedeRevisionExhaustionCannotReopenPublication() throws Exception {
        Queue q = new Queue(); CustomImagePool.State<String> state = new CustomImagePool.State<>(q, value -> {});
        java.lang.reflect.Field revision = state.getClass().getDeclaredField("revision"); revision.setAccessible(true); revision.setLong(state, Long.MAX_VALUE);
        assertThrows(IllegalStateException.class, state::supersedePending); assertTrue(state.failed()); state.close(); assertFalse(state.drained());
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    static final class Queue extends AbstractExecutorService {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        boolean shutdown, running, throwAfterEnqueue, throwBeforeEnqueue, inline, throwShutdown;
        @Override public synchronized void execute(Runnable action) {
            if (throwBeforeEnqueue || shutdown) throw new IllegalStateException();
            if (inline) { running = true; try { action.run(); } finally { running = false; } }
            else tasks.add(action);
            if (throwAfterEnqueue) { throwAfterEnqueue = false; throw new IllegalStateException(); }
        }
        void drain() { while (!tasks.isEmpty()) { running = true; try { tasks.remove().run(); } finally { running = false; } } }
        @Override public void shutdown() { if (throwShutdown) throw new IllegalStateException("shutdown"); shutdown = true; }
        @Override public List<Runnable> shutdownNow() { throw new AssertionError("Cancellation is not drain"); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && !running && tasks.isEmpty(); }
        @Override public boolean awaitTermination(long time, TimeUnit unit) { throw new AssertionError("No blocking shutdown"); }
    }
}
