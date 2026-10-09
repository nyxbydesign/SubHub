package com.subhub.app.service;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.Assert.*;

public final class AccessibilityCaptureSchedulerTest {
    @Test public void callbackJustAfterOldPollWakesImmediatelyInsteadOfWaitingAnotherInterval() {
        Harness h = new Harness();
        h.scheduler.start();
        h.runAt(0);
        long first = h.dispatch();
        h.advance(335);
        assertEquals(1, h.admissions);
        assertTrue(h.scheduler.finish(first, h.now));
        h.runAt(335);
        assertEquals(2, h.admissions);
        assertEquals(335, h.now);
        // The old fixed poll would skip334 while busy, then wait until668.
        assertEquals(333, 668 - h.now);
    }

    @Test public void offPhaseRequestContinuesAtItsOwnNextEligibleDeadline() {
        Harness h = new Harness();
        h.scheduler.start();
        h.runAt(0);
        long first = h.dispatch();
        h.advance(400);
        h.scheduler.finish(first, 500); // An explicit reservation still owns400..500.
        h.runAt(500);
        long second = h.dispatch();
        h.advance(520);
        h.scheduler.finish(second, h.now);
        h.scheduler.wakeAt(668); // Old polling phase cannot break the334ms gate.
        assertEquals(834, h.pendingAt());
        h.runAt(834);
        assertEquals(3, h.admissions);
        assertEquals(168, 1002 - h.now);
    }

    @Test public void actualDispatchNotWindowLookupStartOwnsPlatformSpacing() {
        Harness h = new Harness();
        h.scheduler.start();
        h.runAt(0);
        long id = h.scheduler.acquire(h.now);
        h.advance(90); // Foreground lookup cost, not platform capture time.
        assertTrue(h.scheduler.dispatched(id, h.now));
        h.advance(120);
        h.scheduler.finish(id, h.now);
        assertEquals(424, h.pendingAt());
    }

    @Test public void qualityBudgetUsesSameDispatchBoundaryAsCapture() {
        Harness h = new Harness();
        h.scheduler.start();
        h.runAt(0);
        long id = h.scheduler.acquire(h.now);
        h.advance(90);
        assertTrue(h.scheduler.dispatched(id, h.now));
        assertEquals(174, ScreenshotAccessibilityService.qualityAvailableSlackMs(
                h.scheduler.lastDispatchMillis(), 250));
        assertEquals(424, h.scheduler.nextEligibleMillis());
    }

    @Test public void slowPlatformCallRetainsSpacingFromReturnEvenAcrossStop() {
        Harness h = new Harness();
        h.scheduler.start();
        h.runAt(0);
        long id = h.dispatch();
        h.scheduler.stop();
        h.advance(20);
        h.scheduler.dispatchReturned(id, h.now);
        h.scheduler.start();
        h.scheduler.finish(id, h.now);
        assertEquals(354, h.pendingAt());
        h.scheduler.dispatchReturned(id + 1, 200);
        assertEquals(354, h.scheduler.nextEligibleMillis());
    }

    @Test public void multipleWakeSourcesCoalesceWithoutPostponingEarlierWork() {
        Harness h = new Harness();
        h.scheduler.start();
        h.scheduler.wakeAt(100);
        h.scheduler.wakeAt(200);
        assertEquals(1, h.liveTasks());
        h.runAt(0);
        h.scheduler.wakeAt(700);
        h.scheduler.wakeAt(600);
        h.scheduler.wakeAt(650);
        assertEquals(600, h.pendingAt());
        assertEquals(1, h.liveTasks());
    }

    @Test public void reservationCanEndEarlyWithoutWaitingForItsOldDeadline() {
        Harness h = new Harness();
        h.scheduler.start();
        h.runAt(0);
        h.scheduler.wakeAt(600);
        h.advance(200);
        h.scheduler.wakeAt(h.now);
        h.runAt(200);
        assertEquals(2, h.admissions);
    }

    @Test public void intentionalCaptureGapHasAnExplicitEndWake() {
        Harness h = new Harness();
        h.scheduler.start();
        h.runAt(0);
        h.scheduler.wakeAt(5_000);
        h.advance(4_999);
        assertEquals(1, h.admissions);
        h.runAt(5_000);
        assertEquals(2, h.admissions);
    }

    @Test public void inFlightRequestDoesNotBusyPollOrOverlap() {
        Harness h = new Harness();
        h.scheduler.start();
        h.runAt(0);
        long id = h.dispatch();
        for (int n = 0; n < 100; n++) {
            h.advance(h.now + 20);
            h.scheduler.wakeAt(h.now);
            assertEquals(0, h.scheduler.acquire(h.now));
        }
        assertTrue(h.scheduler.inFlight());
        assertEquals(0, h.liveTasks());
        assertTrue(h.scheduler.finish(id, h.now));
        assertEquals(1, h.liveTasks());
    }

    @Test public void cancelledRunnableCannotFireAfterReplacementOrRestart() {
        Harness h = new Harness();
        h.scheduler.start();
        Task old = h.tasks.get(0);
        h.scheduler.stop();
        h.scheduler.start();
        old.action.run(); // cancel(false) need not prevent delivery.
        assertEquals(0, h.admissions);
        h.runAt(0);
        assertEquals(1, h.admissions);
    }

    @Test public void stopKeepsOwnershipUntilCallbackAndDoesNotWakeStoppedSession() {
        Harness h = new Harness();
        h.scheduler.start();
        h.runAt(0);
        long id = h.dispatch();
        h.scheduler.stop();
        h.advance(400);
        assertTrue(h.scheduler.inFlight());
        assertTrue(h.scheduler.finish(id, h.now));
        assertEquals(0, h.liveTasks());
    }

    @Test public void restartWaitsForOldOwnerAndRetainsActualDispatchSpacing() {
        Harness h = new Harness();
        h.scheduler.start();
        h.runAt(0);
        long first = h.dispatch();
        h.scheduler.stop();
        h.advance(50);
        h.scheduler.start();
        assertEquals(0, h.liveTasks());
        h.scheduler.finish(first, h.now);
        assertEquals(334, h.pendingAt());
        h.runAt(334);
        long second = h.dispatch();
        assertFalse(h.scheduler.finish(first, h.now));
        assertTrue(h.scheduler.inFlight());
        assertTrue(h.scheduler.finish(second, h.now));
    }

    @Test public void oldSessionCannotDispatchAfterForegroundResolutionReturns() {
        Harness h = new Harness();
        h.scheduler.start();
        h.runAt(0);
        long first = h.scheduler.acquire(h.now);
        h.scheduler.stop();
        h.scheduler.start();
        assertFalse(h.scheduler.dispatched(first, h.now));
        assertTrue(h.scheduler.finish(first, h.now));
        h.runAt(0);
        assertEquals(2, h.admissions);
    }

    @Test public void platformFailureStillRetainsDispatchSpacing() {
        Harness h = new Harness();
        h.scheduler.start();
        h.runAt(0);
        long id = h.dispatch();
        h.scheduler.finish(id, h.now);
        assertEquals(334, h.pendingAt());
    }

    @Test public void externalWakeCannotErasePredispatchFailureBackoff() {
        Harness h = new Harness();
        h.scheduler.start();
        h.runAt(0);
        long id = h.scheduler.acquire(h.now);
        h.scheduler.finish(id, 334);
        h.scheduler.wakeAt(0); // Scroll or quality release during a Binder/foreground failure.
        assertEquals(334, h.pendingAt());
        h.scheduler.stop();
        h.scheduler.start();
        assertEquals(334, h.pendingAt());
        assertEquals(0, h.scheduler.acquire(100));
    }

    @Test public void rejectedQueueDoesNotLeavePhantomPendingTask() {
        Harness h = new Harness();
        h.reject = true;
        try { h.scheduler.start(); fail("Expected rejected queue"); }
        catch (RejectedExecutionException expected) { /* Real executor shutdown. */ }
        h.reject = false;
        h.scheduler.wakeAt(0);
        h.runAt(0);
        assertEquals(1, h.admissions);
    }

    private static final class Harness {
        long now;
        int admissions;
        boolean reject;
        final List<Task> tasks = new ArrayList<>();
        final AccessibilityCaptureScheduler scheduler = new AccessibilityCaptureScheduler(
                () -> now, (action, delay) -> {
                    if (reject) throw new RejectedExecutionException();
                    Task task = new Task(now + delay, action);
                    tasks.add(task);
                    return () -> task.cancelled = true;
                }, () -> admissions++, 334L);

        long dispatch() {
            long id = scheduler.acquire(now);
            assertNotEquals(0, id);
            assertTrue(scheduler.dispatched(id, now));
            return id;
        }

        void advance(long time) { assertTrue(time >= now); now = time; }

        long pendingAt() {
            return tasks.stream().filter(t -> !t.cancelled && !t.ran)
                    .mapToLong(t -> t.at).min().orElse(Long.MAX_VALUE);
        }

        int liveTasks() {
            return (int) tasks.stream().filter(t -> !t.cancelled && !t.ran).count();
        }

        void runAt(long time) {
            assertEquals(time, pendingAt());
            advance(time);
            Task task = tasks.stream().filter(t -> !t.cancelled && !t.ran && t.at == time)
                    .findFirst().orElseThrow(AssertionError::new);
            task.ran = true;
            task.action.run();
        }
    }

    private static final class Task {
        final long at;
        final Runnable action;
        boolean cancelled, ran;
        Task(long at, Runnable action) { this.at = at; this.action = action; }
    }
}
