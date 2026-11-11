package com.subhub.app.detection.text;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class OcrRecognizerLeaseTest {
    @Test public void retirementWaitsForTheSdkTaskAndClosesExactlyOnce() {
        AtomicInteger closed = new AtomicInteger();
        OcrRecognizerLease lease = new OcrRecognizerLease(closed::incrementAndGet);
        OcrRecognizerLease.Operation task = lease.begin();
        lease.close();
        assertNull(lease.begin());
        assertEquals(0, closed.get());
        task.close();task.close();lease.close();
        assertEquals(1, closed.get());
    }

    @Test public void allTasksMustCompleteBeforeResourceRelease() {
        AtomicInteger closed = new AtomicInteger();
        OcrRecognizerLease lease = new OcrRecognizerLease(closed::incrementAndGet);
        OcrRecognizerLease.Operation first = lease.begin(), second = lease.begin();
        lease.close();first.close();
        assertEquals(0, closed.get());
        second.close();assertEquals(1, closed.get());
    }

    @Test public void completedWorkDoesNotCloseAnActiveRecognizer() {
        AtomicInteger closed = new AtomicInteger();
        OcrRecognizerLease lease = new OcrRecognizerLease(closed::incrementAndGet);
        lease.begin().close();
        assertEquals(0, closed.get());
        lease.close();lease.close();assertEquals(1, closed.get());
    }
    @Test public void nativeCloseReturnNotInvocationEstablishesDrain() throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch finish = new java.util.concurrent.CountDownLatch(1);
        OcrRecognizerLease lease = new OcrRecognizerLease(() -> {
            entered.countDown();
            try { finish.await(); }
            catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
        });
        assertFalse(lease.managedResourcesDrained());
        Thread closer = new Thread(lease::close);
        closer.start();
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(lease.managedResourcesDrained());
            lease.close();
            assertFalse(lease.managedResourcesDrained());
        } finally { finish.countDown(); closer.join(5000); }
        assertFalse(closer.isAlive());
        assertTrue(lease.managedResourcesDrained());
    }

    @Test public void failedNativeCloseNeverReportsDrainOrRetries() {
        AtomicInteger attempts = new AtomicInteger();
        OcrRecognizerLease lease = new OcrRecognizerLease(() -> {
            attempts.incrementAndGet(); throw new IllegalStateException("native close failed");
        });
        OcrRecognizerLease.Operation task = lease.begin();
        lease.close();
        assertFalse(lease.managedResourcesDrained());
        assertThrows(IllegalStateException.class, task::close);
        task.close(); lease.close();
        assertFalse(lease.managedResourcesDrained());
        assertEquals(1, attempts.get());
    }

    @Test public void completedTaskBeforeRetirementIsNotDrain() {
        OcrRecognizerLease lease = new OcrRecognizerLease(() -> {});
        OcrRecognizerLease.Operation task = lease.begin();
        task.close();
        assertFalse(lease.managedResourcesDrained());
        lease.close();
        assertTrue(lease.managedResourcesDrained());
    }
}
