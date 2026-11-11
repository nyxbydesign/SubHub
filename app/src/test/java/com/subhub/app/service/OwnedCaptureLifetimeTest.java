package com.subhub.app.service;

import static org.junit.Assert.*;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class OwnedCaptureLifetimeTest {
    @Test public void sealRefusesNewWorkButRetainsExactBorrow() {
        OwnedCaptureLifetime life = new OwnedCaptureLifetime();
        OwnedCaptureLifetime.Ticket held = life.begin(); AtomicBoolean done = new AtomicBoolean();
        life.seal(() -> done.set(true)); assertFalse(held.current()); assertNull(life.begin());
        assertFalse(life.drained()); assertFalse(done.get()); held.close();
        assertTrue(done.get()); assertTrue(life.drained()); held.close(); assertEquals(0, life.outstanding());
    }
    @Test public void queuedCallbackIsOwnedUntilItReallyReturns() {
        OwnedCaptureLifetime life = new OwnedCaptureLifetime(); ArrayDeque<Runnable> queue = new ArrayDeque<>();
        AtomicBoolean done = new AtomicBoolean();
        assertTrue(life.dispatch(task -> { queue.add(task); return true; }, () -> assertFalse(done.get())));
        life.seal(() -> done.set(true)); assertFalse(life.drained()); queue.remove().run();
        assertTrue(life.drained()); assertTrue(done.get());
    }
    @Test public void explicitNoEnqueueCanReleaseWithoutCallback() {
        OwnedCaptureLifetime life = new OwnedCaptureLifetime();
        assertFalse(life.dispatch(task -> false, () -> fail("Refused action ran")));
        life.seal(() -> { }); assertTrue(life.drained());
    }
    @Test public void throwBeforeObservableCallbackKeepsUnknownWork() {
        OwnedCaptureLifetime life = new OwnedCaptureLifetime();
        assertThrows(IllegalStateException.class, () -> life.dispatch(task -> { throw new IllegalStateException(); }, () -> { }));
        life.seal(() -> fail("Unknown accepted work was released")); assertFalse(life.drained());
        assertEquals(1, life.outstanding());
    }
    @Test public void enqueueThenThrowRetainsUntilActualQueuedReturn() {
        OwnedCaptureLifetime life = new OwnedCaptureLifetime(); AtomicReference<Runnable> queued = new AtomicReference<>();
        assertThrows(IllegalStateException.class, () -> life.dispatch(task -> {
            queued.set(task); throw new IllegalStateException();
        }, () -> { }));
        life.seal(() -> { }); assertFalse(life.drained()); queued.get().run(); assertTrue(life.drained());
    }
    @Test public void inlineThenThrowCannotDrainBeforeDispatchInvocationSettles() {
        OwnedCaptureLifetime life = new OwnedCaptureLifetime(); AtomicBoolean completed = new AtomicBoolean();
        assertThrows(IllegalStateException.class, () -> life.dispatch(task -> {
            task.run(); assertFalse(completed.get()); assertEquals(1, life.outstanding());
            throw new IllegalStateException();
        }, () -> life.seal(() -> completed.set(true))));
        assertTrue(completed.get()); assertTrue(life.drained());
    }
    @Test public void callbackExceptionStillClosesActualWorkNotDispatchEarly() {
        OwnedCaptureLifetime life = new OwnedCaptureLifetime();
        assertThrows(IllegalArgumentException.class, () -> life.dispatch(task -> {
            task.run(); return true;
        }, () -> { throw new IllegalArgumentException(); }));
        life.seal(() -> { }); assertTrue(life.drained());
    }
    @Test public void duplicateDeliveryCannotRunActionTwice() {
        OwnedCaptureLifetime life = new OwnedCaptureLifetime(); AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicInteger runs = new AtomicInteger(); life.dispatch(task -> { queued.set(task); return true; }, runs::incrementAndGet);
        life.seal(() -> { }); queued.get().run(); queued.get().run();
        assertEquals(1, runs.get()); assertTrue(life.drained());
    }
    @Test public void falseAfterInlineExecutionIsProtocolFailureNotNonAdmission() {
        OwnedCaptureLifetime life = new OwnedCaptureLifetime(); AtomicInteger runs = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> life.dispatch(task -> { task.run(); return false; }, runs::incrementAndGet));
        assertEquals(1, runs.get()); life.seal(() -> { }); assertTrue(life.drained());
    }
    @Test public void acceptedNativeRequestCanDeliverCleanupContinuationAfterSeal() {
        OwnedCaptureLifetime life = new OwnedCaptureLifetime();
        OwnedCaptureLifetime.Ticket request = life.begin(); AtomicReference<Runnable> queued = new AtomicReference<>();
        life.seal(() -> { });
        assertTrue(life.dispatchContinuation(request, task -> { queued.set(task); return true; }, request::close));
        assertEquals(2, life.outstanding()); assertFalse(life.drained());
        queued.get().run(); assertTrue(life.drained());
        assertThrows(IllegalStateException.class,
                () -> life.dispatchContinuation(request, task -> true, () -> fail("New work after terminal")));
    }
    @Test public void callbackContinuationKeepsOwnerUntilItsDispatchInvocationSettles() {
        OwnedCaptureLifetime life = new OwnedCaptureLifetime();
        OwnedCaptureLifetime.Ticket request = life.begin(); life.seal(() -> { });
        assertThrows(IllegalStateException.class, () -> life.dispatchContinuation(request, task -> {
            task.run(); assertFalse(life.drained()); throw new IllegalStateException();
        }, request::close));
        assertTrue(life.drained());
    }
    @Test public void continuationCannotBorrowAnotherOwnersNativeTicket() {
        OwnedCaptureLifetime first = new OwnedCaptureLifetime(), other = new OwnedCaptureLifetime();
        OwnedCaptureLifetime.Ticket request = first.begin();
        assertThrows(SecurityException.class,
                () -> other.dispatchContinuation(request, task -> true, () -> fail("Foreign callback")));
        assertEquals(1, first.outstanding()); assertEquals(0, other.outstanding());
        request.close(); first.seal(() -> { }); other.seal(() -> { });
        assertTrue(first.drained()); assertTrue(other.drained());
    }
    @Test public void closureObserverMustActuallyReturnAndCannotFakeSuccessWhenItThrows() {
        OwnedCaptureLifetime life = new OwnedCaptureLifetime();
        assertThrows(IllegalStateException.class, () -> life.seal(() -> {
            assertFalse(life.drained()); throw new IllegalStateException();
        }));
        assertFalse(life.drained());
    }
}
