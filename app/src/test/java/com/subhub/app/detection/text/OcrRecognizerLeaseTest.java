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
}
