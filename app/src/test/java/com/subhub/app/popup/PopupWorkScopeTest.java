package com.subhub.app.popup;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class PopupWorkScopeTest {
    @Test public void stopWaitsForWorkerAndPostedCallbackAfterWindowsClose() {
        PopupWorkScope scope = new PopupWorkScope();
        PopupWorkScope.Ticket worker = scope.begin(), callback = scope.begin();
        AtomicInteger receipts = new AtomicInteger();
        scope.close(receipts::incrementAndGet);
        assertNull(scope.begin());
        assertFalse(worker.isCurrent());
        scope.windowsClosed();
        assertEquals(0, receipts.get());
        worker.close();
        assertEquals(0, receipts.get());
        callback.close();
        assertEquals(1, receipts.get());
        callback.close();
        scope.windowsClosed();
        assertEquals(1, receipts.get());
        assertTrue(scope.isDrained());
    }
    @Test public void workerCompletionDoesNotAcknowledgePendingWindowRemoval() {
        PopupWorkScope scope = new PopupWorkScope();
        PopupWorkScope.Ticket worker = scope.begin();
        AtomicInteger receipts = new AtomicInteger();
        scope.close(receipts::incrementAndGet);
        worker.close();
        assertEquals(0, receipts.get());
        assertFalse(scope.isDrained());
        scope.windowsClosed();
        assertEquals(1, receipts.get());
    }
    @Test public void failedDetachNeverBecomesAReceiptJustBecauseQueuesAreEmpty() {
        PopupWorkScope scope = new PopupWorkScope();
        PopupWorkScope.Ticket callback = scope.begin();
        AtomicInteger receipts = new AtomicInteger();
        scope.close(receipts::incrementAndGet);
        scope.failed();
        scope.windowsClosed();
        callback.close();
        assertEquals(0, receipts.get());
        assertFalse(scope.isDrained());
    }
    @Test public void oldOwnerCallbacksCannotEnterOrDrainAReplacement() {
        PopupWorkScope old = new PopupWorkScope(), replacement = new PopupWorkScope();
        PopupWorkScope.Ticket oldCallback = old.begin(), newCallback = replacement.begin();
        AtomicInteger oldReceipt = new AtomicInteger(), newReceipt = new AtomicInteger();
        old.close(oldReceipt::incrementAndGet);
        old.windowsClosed();
        assertTrue(newCallback.isCurrent());
        oldCallback.close();
        assertEquals(1, oldReceipt.get());
        assertEquals(1, replacement.pendingCount());
        replacement.close(newReceipt::incrementAndGet);
        replacement.windowsClosed();
        assertEquals(0, newReceipt.get());
        newCallback.close();
        assertEquals(1, newReceipt.get());
    }
    @Test public void drainCallbackRunsOutsidePrivateMonitor() {
        PopupWorkScope scope = new PopupWorkScope();
        scope.close(() -> assertFalse(Thread.holdsLock(scope)));
        scope.windowsClosed();
    }
    @Test public void racingTerminalCallbacksAndCloseAcknowledgeExactlyOnce() throws Exception {
        for (int iteration = 0; iteration < 100; iteration++) {
            PopupWorkScope scope = new PopupWorkScope();
            PopupWorkScope.Ticket first = scope.begin(), second = scope.begin();
            AtomicInteger receipts = new AtomicInteger();
            java.util.concurrent.CountDownLatch together = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { together.await(); first.close(); second.close(); }
                catch (Throwable failed) { failure.set(failed); }
            });
            worker.start(); together.countDown();
            scope.close(receipts::incrementAndGet);
            scope.windowsClosed();
            worker.join(2000);
            assertFalse("Worker did not terminate", worker.isAlive());
            if (failure.get() != null) throw new AssertionError(failure.get());
            assertEquals(1, receipts.get());
            assertEquals(0, scope.pendingCount());
            assertTrue(scope.isDrained());
        }
    }
    @Test public void duplicateCloseCannotReplaceOriginalAcknowledgment() {
        PopupWorkScope scope = new PopupWorkScope();
        AtomicInteger receipt = new AtomicInteger();
        scope.close(receipt::incrementAndGet);
        assertThrows(IllegalStateException.class, () -> scope.close(() -> fail("Replacement")));
        scope.windowsClosed();
        assertEquals(1, receipt.get());
    }
}
