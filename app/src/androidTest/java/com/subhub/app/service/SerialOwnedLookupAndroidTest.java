package com.subhub.app.service;

import static org.junit.Assert.*;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class SerialOwnedLookupAndroidTest {
    @Test public void copiedMetadataSurvivesFrameworkRecycleAcrossWorkerAndMain() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Handler main = new Handler(Looper.getMainLooper());
        List<Integer> applied = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch delivered = new CountDownLatch(2);
        AtomicInteger disposed = new AtomicInteger();
        AtomicReference<Throwable> asynchronousFailure = new AtomicReference<>();
        SerialOwnedLookup<AccessibilityEvent> queue = new SerialOwnedLookup<>(2, worker, (event, lease) -> {
            try {
                assertNotEquals(Looper.getMainLooper().getThread(), Thread.currentThread());
                new AccessibilitySurfaceIdentityResolver().resolve(event);
                assertTrue(main.post(() -> {
                    try {
                        assertTrue(lease.isCurrent());
                        applied.add(event.getScrollDeltaY());
                    } catch (Throwable error) {
                        asynchronousFailure.compareAndSet(null, error);
                    } finally {
                        lease.release();
                        delivered.countDown();
                    }
                }));
            } catch (Throwable error) {
                asynchronousFailure.compareAndSet(null, error);
                lease.release(); delivered.countDown();
            }
        }, event -> { event.recycle(); disposed.incrementAndGet(); },
                (value, error) -> asynchronousFailure.compareAndSet(null, error));
        try {
            for (int delta : new int[]{41, -23}) {
                AccessibilityEvent original = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED);
                original.setScrollDeltaY(delta);
                AccessibilityEvent owned = AccessibilityEvent.obtain(original);
                original.recycle();
                assertEquals(SerialOwnedLookup.Offer.ACCEPTED, queue.offer(owned));
            }
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertNull(asynchronousFailure.get());
            assertEquals(List.of(41, -23), applied);
            assertEquals(2, disposed.get());
            assertEquals(0, queue.outstanding());
        } finally {
            queue.close(); worker.shutdown();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void closingDuringLookupDoesNotRecycleActiveEventEarly() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1), done = new CountDownLatch(1);
        AtomicInteger disposed = new AtomicInteger();
        AtomicReference<Throwable> asynchronousFailure = new AtomicReference<>();
        SerialOwnedLookup<AccessibilityEvent> queue = new SerialOwnedLookup<>(2, worker, (event, lease) -> {
            entered.countDown();
            try {
                assertTrue(finish.await(5, TimeUnit.SECONDS));
                assertEquals(57, event.getScrollDeltaY());
                assertFalse(lease.isCurrent());
            } catch (InterruptedException interrupted) {
                asynchronousFailure.compareAndSet(null, interrupted);
            } catch (Throwable error) {
                asynchronousFailure.compareAndSet(null, error);
            } finally { lease.release(); done.countDown(); }
        }, event -> { event.recycle(); disposed.incrementAndGet(); },
                (value, error) -> asynchronousFailure.compareAndSet(null, error));
        try {
            AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED);
            event.setScrollDeltaY(57);
            queue.offer(event);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            queue.close();
            assertEquals(0, disposed.get());
            finish.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertNull(asynchronousFailure.get());
            assertEquals(1, disposed.get());
            assertEquals(0, queue.outstanding());
        } finally {
            finish.countDown(); queue.close(); worker.shutdown();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
