package com.subhub.app.tasks;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public final class InternalTaskSchedulerTest {
    private static final String A = "00000000-0000-0000-0000-000000000001";
    private static final String B = "00000000-0000-0000-0000-000000000002";
    private static final class Host implements InternalTaskScheduler.Scheduling, InternalTaskScheduler.Notices {
        final List<Runnable> callbacks = new ArrayList<>();
        final List<String> requested = new ArrayList<>(), cancelledNotices = new ArrayList<>();
        final List<String> background = new ArrayList<>();
        int cancelledTimers, cancelledWork, admissions;
        boolean failNotices, failTimerCancel;
        final InternalTaskScheduler scheduler = new InternalTaskScheduler(() -> admissions++, this, requested::add, this);
        @Override public InternalTaskScheduler.Ticket foreground(Runnable wake, long intervalMillis) {
            assertEquals(30_000, intervalMillis); callbacks.add(wake); return () -> {
                if (failTimerCancel) { failTimerCancel = false; throw new IllegalStateException("Injected timer cancellation failure"); }
                cancelledTimers++;
            };
        }
        @Override public void background(String name, String generation, long minutes) {
            assertEquals(InternalTaskScheduler.UNIQUE_WORK_NAME, name); assertEquals(15, minutes); background.add(generation);
        }
        @Override public void cancelBackground(String name) { assertEquals(InternalTaskScheduler.UNIQUE_WORK_NAME, name); cancelledWork++; }
        @Override public void cancelGeneration(String generation) {
            if (failNotices) throw new IllegalStateException("Injected optional host unavailable");
            cancelledNotices.add(generation);
        }
    }
    @Test public void backgroundPolicyExistsWithoutForegroundOrProtection() {
        Host h = new Host(); h.scheduler.reconcile(A);
        assertEquals(List.of(A), h.background); assertTrue(h.callbacks.isEmpty());
        h.scheduler.changed(A); assertEquals(List.of(A), h.requested);
    }
    @Test public void foregroundCountsActivitiesAndRestartsOnlyAfterLastStops() {
        Host h = new Host(); h.scheduler.reconcile(A); h.scheduler.activityStarted(); h.scheduler.activityStarted();
        assertEquals(1, h.callbacks.size()); h.callbacks.get(0).run(); assertEquals(List.of(A), h.requested);
        h.scheduler.activityStopped(); assertEquals(0, h.cancelledTimers);
        h.scheduler.activityStopped(); assertEquals(1, h.cancelledTimers);
        h.callbacks.get(0).run(); assertEquals(List.of(A), h.requested);
        h.scheduler.activityStarted(); assertEquals(2, h.callbacks.size());
        h.callbacks.get(0).run(); assertEquals(List.of(A), h.requested);
        h.callbacks.get(1).run(); assertEquals(List.of(A, A), h.requested);
    }
    @Test public void hideAndReaddFenceOldCallbacksAndCancelOnlyRetiredGeneration() {
        Host h = new Host(); h.scheduler.activityStarted(); h.scheduler.reconcile(A);
        Runnable old = h.callbacks.get(0); h.scheduler.reconcile(null); old.run(); h.scheduler.changed(A);
        assertTrue(h.requested.isEmpty()); assertEquals(List.of(A), h.cancelledNotices); assertEquals(1, h.cancelledWork);
        h.scheduler.reconcile(B); old.run(); h.callbacks.get(1).run();
        assertEquals(List.of(B), h.requested); assertEquals(List.of(A, B), h.background);
    }
    @Test public void sameGenerationReconciliationRetainsForegroundTicket() {
        Host h = new Host(); h.scheduler.activityStarted(); h.scheduler.reconcile(A); h.scheduler.reconcile(A);
        assertEquals(1, h.callbacks.size()); assertEquals(0, h.cancelledTimers); assertTrue(h.cancelledNotices.isEmpty());
    }
    @Test public void failedNoticeCleanupIsRetriedWithoutRevivingOldWork() {
        Host h = new Host(); h.scheduler.activityStarted(); h.scheduler.reconcile(A); h.failNotices = true;
        assertThrows(IllegalStateException.class, () -> h.scheduler.reconcile(null));
        h.callbacks.get(0).run(); assertTrue(h.requested.isEmpty());
        h.failNotices = false; h.scheduler.reconcile(null);
        assertEquals(List.of(A), h.cancelledNotices); assertEquals(1, h.cancelledWork);
    }
    @Test public void failedTimerCancellationRetriesExactTicketOnSameGenerationReconcile() {
        Host h = new Host(); h.scheduler.activityStarted(); h.scheduler.reconcile(A); h.failTimerCancel = true;
        assertThrows(IllegalStateException.class, () -> h.scheduler.reconcile(B));
        h.callbacks.get(0).run(); assertTrue(h.requested.isEmpty());
        h.scheduler.reconcile(B);
        assertEquals(1, h.cancelledTimers); assertEquals(2, h.callbacks.size());
        h.callbacks.get(0).run(); assertTrue(h.requested.isEmpty());
        h.callbacks.get(1).run(); assertEquals(List.of(B), h.requested);
        assertEquals(List.of(A), h.cancelledNotices);
    }
    @Test public void closeFencesQueuedWorkAndCancelsOnlyItsUniqueNamespace() {
        Host h = new Host(); h.scheduler.activityStarted(); h.scheduler.reconcile(A); h.scheduler.close();
        h.callbacks.get(0).run(); h.scheduler.changed(A); h.scheduler.activityStarted();
        assertTrue(h.requested.isEmpty()); assertEquals(List.of(A), h.cancelledNotices); assertEquals(1, h.cancelledWork);
        assertThrows(IllegalStateException.class, () -> h.scheduler.reconcile(B));
    }
}
