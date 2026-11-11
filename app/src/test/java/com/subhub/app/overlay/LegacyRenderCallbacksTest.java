package com.subhub.app.overlay;

import static org.junit.Assert.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.Test;

/** Actual detached renderer callback bookkeeping, no Android frame/window execution. */
public final class LegacyRenderCallbacksTest {
    @Test public void sealWaitsForActualDeliveryAndMakesItInert() {
        LegacyRenderDependencies.Callbacks work = new LegacyRenderDependencies.Callbacks();
        AtomicReference<Consumer<Integer>> queued = new AtomicReference<>(); AtomicInteger calls = new AtomicInteger();
        assertTrue(work.<Integer>dispatch(new Object(), value -> calls.addAndGet(value), callback -> { queued.set(callback); return true; }));
        work.seal(); assertFalse(work.drained()); queued.get().accept(3);
        assertEquals(0, calls.get()); assertTrue(work.drained());
    }
    @Test public void cancellationIsOnlyAnEffectFenceNotAClosureReceipt() {
        LegacyRenderDependencies.Callbacks work = new LegacyRenderDependencies.Callbacks(); Object key = new Object();
        AtomicReference<Consumer<Object>> queued = new AtomicReference<>();
        work.dispatch(key, value -> fail("Cancelled action"), callback -> { queued.set(callback); return true; });
        work.cancel(key); work.seal(); assertFalse(work.drained());
        queued.get().accept(null); assertTrue(work.drained());
    }
    @Test public void enqueueThenThrowRetainsActualCallbackObligation() {
        LegacyRenderDependencies.Callbacks work = new LegacyRenderDependencies.Callbacks();
        AtomicReference<Consumer<Object>> queued = new AtomicReference<>();
        assertThrows(IllegalStateException.class, () -> work.dispatch(new Object(), value -> {}, callback -> {
            queued.set(callback); throw new IllegalStateException();
        }));
        work.seal(); assertFalse(work.drained()); queued.get().accept(null); assertTrue(work.drained());
    }
    @Test public void unknownThrowWithoutDeliveryCannotDrain() {
        LegacyRenderDependencies.Callbacks work = new LegacyRenderDependencies.Callbacks();
        assertThrows(IllegalStateException.class, () -> work.dispatch(new Object(), value -> {}, callback -> { throw new IllegalStateException(); }));
        work.seal(); assertFalse(work.drained());
    }
    @Test public void explicitFalseBeforeDispatchHasNoRemainingObligation() {
        LegacyRenderDependencies.Callbacks work = new LegacyRenderDependencies.Callbacks();
        assertFalse(work.dispatch(new Object(), value -> fail(), callback -> false));
        work.seal(); assertTrue(work.drained());
    }
    @Test public void falseAfterInlineDeliveryCannotMasqueradeAsNonAdmission() {
        LegacyRenderDependencies.Callbacks work = new LegacyRenderDependencies.Callbacks();
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> work.dispatch(new Object(), value -> calls.incrementAndGet(), callback -> {
            callback.accept(null); return false;
        }));
        work.seal(); assertEquals(1, calls.get()); assertTrue(work.drained());
    }
    @Test public void callbackReturnedInlineStillWaitsForSubmissionReturn() {
        LegacyRenderDependencies.Callbacks work = new LegacyRenderDependencies.Callbacks();
        assertThrows(IllegalStateException.class, () -> work.dispatch(new Object(), value -> work.seal(), callback -> {
            callback.accept(null); assertFalse(work.drained()); throw new IllegalStateException();
        }));
        assertTrue(work.drained());
    }
    @Test public void admittedDrawCannotDrainUntilItsBodyReturns() throws Exception {
        LegacyRenderDependencies.Callbacks work = new LegacyRenderDependencies.Callbacks();
        CountDownLatch inside = new CountDownLatch(1), leave = new CountDownLatch(1);
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread draw = new Thread(() -> {
            try { work.direct(() -> { inside.countDown(); await(leave); }); }
            catch (Throwable error) { failed.set(error); }
        }); draw.start();
        try {
            assertTrue(inside.await(5, TimeUnit.SECONDS)); work.seal();
            assertFalse(work.drained()); assertFalse(work.direct(() -> fail("New draw")));
        } finally { leave.countDown(); draw.join(5000); }
        assertFalse(draw.isAlive()); assertNull(failed.get()); assertTrue(work.drained());
    }
    @Test public void exceptionalNestedDrawReturnsBothAdmissions() {
        LegacyRenderDependencies.Callbacks work = new LegacyRenderDependencies.Callbacks();
        assertThrows(IllegalStateException.class, () -> work.direct(() -> work.direct(() -> {
            work.seal(); assertFalse(work.drained()); throw new IllegalStateException();
        })));
        assertTrue(work.drained());
    }
    @Test public void duplicateLateCallbackCannotInvokeOrReleaseTwice() {
        LegacyRenderDependencies.Callbacks work = new LegacyRenderDependencies.Callbacks();
        AtomicReference<Consumer<Object>> queued = new AtomicReference<>(); AtomicInteger calls = new AtomicInteger();
        work.dispatch(new Object(), value -> calls.incrementAndGet(), callback -> { queued.set(callback); return true; });
        queued.get().accept(null); work.seal(); assertTrue(work.drained());
        queued.get().accept(null); assertEquals(1, calls.get()); assertTrue(work.drained());
    }
    @Test public void cancellationOfOneKeyDoesNotCancelAnother() {
        LegacyRenderDependencies.Callbacks work = new LegacyRenderDependencies.Callbacks();
        Object first = new Object(), second = new Object();
        AtomicReference<Consumer<Object>> a = new AtomicReference<>(), b = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        work.dispatch(first, value -> fail("Cancelled"), callback -> { a.set(callback); return true; });
        work.dispatch(second, value -> calls.incrementAndGet(), callback -> { b.set(callback); return true; });
        work.cancel(first); b.get().accept(null); work.seal(); assertFalse(work.drained());
        a.get().accept(null); assertEquals(1, calls.get()); assertTrue(work.drained());
    }
    @Test public void acceptedConcurrentCallbacksStayCountedUntilEveryInvocationReturns() throws Exception {
        LegacyRenderDependencies.Callbacks work = new LegacyRenderDependencies.Callbacks();
        CountDownLatch entered = new CountDownLatch(4), leave = new CountDownLatch(1);
        java.util.List<Thread> threads = new java.util.ArrayList<>(); AtomicReference<Throwable> failed = new AtomicReference<>();
        for (int i = 0; i < 4; i++) {
            AtomicReference<Consumer<Object>> queued = new AtomicReference<>();
            work.dispatch(new Object(), ignored -> { entered.countDown(); await(leave); }, callback -> { queued.set(callback); return true; });
            Thread thread = new Thread(() -> { try { queued.get().accept(null); } catch (Throwable error) { failed.set(error); } });
            threads.add(thread); thread.start();
        }
        try { assertTrue(entered.await(5, TimeUnit.SECONDS)); work.seal(); assertFalse(work.drained()); }
        finally { leave.countDown(); for (Thread thread : threads) thread.join(5000); }
        for (Thread thread : threads) assertFalse(thread.isAlive());
        assertNull(failed.get()); assertTrue(work.drained());
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
