package com.subhub.app.overlay;

import static org.junit.Assert.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Dispatcher/control contract only; no Android traversal, RenderThread or compositor evidence. */
public final class LegacyNativeRemovalTest {
    @Test public void queuedRemovalWaitsForActualBodyReturn() {
        AtomicReference<Runnable> queued = new AtomicReference<>(); AtomicInteger calls = new AtomicInteger();
        OverlayController.NativeRemoval removal = new OverlayController.NativeRemoval(calls::incrementAndGet);
        removal.post(work -> { queued.set(work); return true; });
        assertFalse(removal.returned()); assertEquals(0, calls.get());
        queued.get().run(); assertTrue(removal.returned()); assertEquals(1, calls.get());
    }
    @Test public void inlineBodyCannotRetireBeforePostReturn() {
        AtomicReference<OverlayController.NativeRemoval> ref = new AtomicReference<>();
        ref.set(new OverlayController.NativeRemoval(() -> assertFalse(ref.get().returned())));
        ref.get().post(work -> { work.run(); assertFalse(ref.get().returned()); return true; });
        assertTrue(ref.get().returned());
    }
    @Test public void heldBodyCannotRetireWhileRunning() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), leave = new CountDownLatch(1);
        AtomicReference<Runnable> queued = new AtomicReference<>(); AtomicReference<Throwable> failure = new AtomicReference<>();
        OverlayController.NativeRemoval removal = new OverlayController.NativeRemoval(() -> {
            entered.countDown(); try { assertTrue(leave.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
        });
        removal.post(work -> { queued.set(work); return true; });
        Thread worker = new Thread(() -> { try { queued.get().run(); } catch (Throwable error) { failure.set(error); } });
        worker.start();
        try { assertTrue(entered.await(5, TimeUnit.SECONDS)); assertFalse(removal.returned()); }
        finally { leave.countDown(); worker.join(5000); }
        assertFalse(worker.isAlive()); assertNull(failure.get()); assertTrue(removal.returned());
    }
    @Test public void refusedDispatchNeverBecomesReceiptEvenIfDeliveredIllegallyLater() {
        AtomicReference<Runnable> queued = new AtomicReference<>(); AtomicInteger calls = new AtomicInteger();
        OverlayController.NativeRemoval removal = new OverlayController.NativeRemoval(calls::incrementAndGet);
        assertThrows(IllegalStateException.class, () -> removal.post(work -> { queued.set(work); return false; }));
        queued.get().run(); assertEquals(0, calls.get()); assertFalse(removal.returned()); assertTrue(removal.failed());
    }
    @Test public void enqueueThenThrowStillAttemptsCleanupButRemainsUncertain() {
        AtomicReference<Runnable> queued = new AtomicReference<>(); AtomicInteger calls = new AtomicInteger();
        OverlayController.NativeRemoval removal = new OverlayController.NativeRemoval(calls::incrementAndGet);
        assertThrows(IllegalStateException.class, () -> removal.post(work -> { queued.set(work); throw new IllegalStateException(); }));
        assertFalse(removal.returned()); queued.get().run(); assertEquals(1, calls.get());
        assertFalse(removal.returned()); assertTrue(removal.failed());
    }
    @Test public void runThenThrowNeverBecomesReceipt() {
        AtomicInteger calls = new AtomicInteger(); OverlayController.NativeRemoval removal = new OverlayController.NativeRemoval(calls::incrementAndGet);
        assertThrows(IllegalStateException.class, () -> removal.post(work -> { work.run(); throw new IllegalStateException(); }));
        assertEquals(1, calls.get()); assertFalse(removal.returned()); assertTrue(removal.failed());
    }
    @Test public void throwBeforeDispatchRemainsOccupied() {
        OverlayController.NativeRemoval removal = new OverlayController.NativeRemoval(() -> fail("Not dispatched"));
        assertThrows(IllegalStateException.class, () -> removal.post(work -> { throw new IllegalStateException(); }));
        assertFalse(removal.returned()); assertTrue(removal.failed());
    }
    @Test public void removalFailureIsLatchedThroughRepeatedCallback() {
        AtomicInteger calls = new AtomicInteger(); AtomicReference<Runnable> queued = new AtomicReference<>();
        OverlayController.NativeRemoval removal = new OverlayController.NativeRemoval(() -> { calls.incrementAndGet(); throw new IllegalStateException(); });
        removal.post(work -> { queued.set(work); return true; });
        assertThrows(IllegalStateException.class, queued.get()::run); queued.get().run();
        assertEquals(1, calls.get()); assertFalse(removal.returned()); assertTrue(removal.failed());
    }
    @Test public void errorIsNotAClosureReceipt() {
        OverlayController.NativeRemoval removal = new OverlayController.NativeRemoval(() -> { throw new AssertionError("Window uncertain"); });
        assertThrows(AssertionError.class, () -> removal.post(work -> { work.run(); return true; }));
        assertFalse(removal.returned()); assertTrue(removal.failed());
    }
    @Test public void duplicateCallbackCannotRepeatRemoval() {
        AtomicInteger calls = new AtomicInteger(); AtomicReference<Runnable> queued = new AtomicReference<>();
        OverlayController.NativeRemoval removal = new OverlayController.NativeRemoval(calls::incrementAndGet);
        removal.post(work -> { queued.set(work); return true; }); queued.get().run(); queued.get().run();
        assertEquals(1, calls.get()); assertTrue(removal.returned());
    }
    @Test public void duplicateSubmissionCannotReplaceFirstObligation() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        OverlayController.NativeRemoval removal = new OverlayController.NativeRemoval(() -> {});
        removal.post(work -> { queued.set(work); return true; });
        assertThrows(IllegalStateException.class, () -> removal.post(work -> { fail("Second post"); return true; }));
        assertFalse(removal.returned()); queued.get().run(); assertTrue(removal.returned());
    }
    @Test public void runThenFalseIsUncertainNotARefusal() {
        OverlayController.NativeRemoval removal = new OverlayController.NativeRemoval(() -> {});
        assertThrows(IllegalStateException.class, () -> removal.post(work -> { work.run(); return false; }));
        assertFalse(removal.returned()); assertTrue(removal.failed());
    }
}
