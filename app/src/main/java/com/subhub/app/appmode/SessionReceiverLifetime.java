package com.subhub.app.appmode;

import android.content.BroadcastReceiver;
import android.util.Log;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Keeps a real broadcast active while its short queued session work finishes. */
public final class SessionReceiverLifetime {
    private static final int MAX_PENDING = 32;
    private static final long LIMIT_MILLIS = 8000;
    private static final AtomicInteger ACTIVE = new AtomicInteger();
    private static final ScheduledThreadPoolExecutor DEADLINES = new ScheduledThreadPoolExecutor(1, r -> {
        Thread thread = new Thread(r, "session-receiver-deadlines"); thread.setDaemon(true); return thread;
    });
    static { DEADLINES.setRemoveOnCancelPolicy(true); }
    private SessionReceiverLifetime() { }

    public static Work begin(BroadcastReceiver receiver) {
        BroadcastReceiver.PendingResult pending = receiver.goAsync();
        if (pending == null) return new Work(null); // A direct method invocation has no Android lifetime to retain.
        if (ACTIVE.incrementAndGet() > MAX_PENDING) {
            ACTIVE.decrementAndGet();
            try { pending.finish(); }
            finally { Log.w("SessionReceiver", "Receiver completion capacity exceeded"); }
            return new Work(null);
        }
        Work work = new Work(pending);
        try { work.deadline = DEADLINES.schedule(() -> work.finish("Receiver work timed out"), LIMIT_MILLIS, TimeUnit.MILLISECONDS); }
        catch (RuntimeException unavailable) { work.finish("Receiver completion unavailable"); }
        return work;
    }

    public static final class Work {
        private final BroadcastReceiver.PendingResult pending;
        private final AtomicBoolean finished = new AtomicBoolean();
        private ScheduledFuture<?> deadline;
        private volatile java.util.concurrent.CompletableFuture<?> completion;
        private Work(BroadcastReceiver.PendingResult pending) { this.pending = pending; }
        public void afterQueues() {
            if (pending == null || finished.get()) return;
            try {
                java.util.concurrent.CompletableFuture<Void> main = SessionExpiryEffects.whenDispatched(); completion = main;
                if (finished.get()) { main.cancel(false); return; }
                main.whenComplete((ignored, mainFailure) -> {
                    if (finished.get()) return;
                    if (mainFailure != null) { finish("Receiver dispatch unavailable"); return; }
                    java.util.concurrent.CompletableFuture<Void> alarms = SessionExpiryAlarms.whenIdle(); completion = alarms;
                    if (finished.get()) { alarms.cancel(false); return; }
                    alarms.whenComplete((unused, alarmFailure) -> finish(alarmFailure == null ? null : "Receiver work unavailable"));
                });
            } catch (RuntimeException unavailable) { finish("Receiver work unavailable"); }
        }
        public void failed() { finish("Receiver handling failed"); }
        private void finish(String warning) {
            if (pending == null || !finished.compareAndSet(false, true)) return;
            java.util.concurrent.CompletableFuture<?> wait = completion;
            if (wait != null) wait.cancel(false);
            ScheduledFuture<?> timer = deadline;
            if (timer != null) timer.cancel(false);
            try {
                if (warning != null) Log.w("SessionReceiver", warning);
                pending.finish();
            } catch (RuntimeException failure) { Log.w("SessionReceiver", "Could not finish receiver result"); }
            finally { ACTIVE.decrementAndGet(); }
        }
    }
}
