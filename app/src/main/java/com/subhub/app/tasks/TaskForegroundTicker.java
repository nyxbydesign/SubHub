package com.subhub.app.tasks;

/** Only visible activities own the 30-second Tasks refresh; background work has its own worker. */
final class TaskForegroundTicker {
    static final long INTERVAL_MILLIS = 30_000L;

    interface Ticket { void cancel(); }
    interface Scheduler {
        /** Run immediately, then wait the given delay after each completed tick. */
        Ticket repeat(Runnable tick, long delayMillis);
    }

    private final Scheduler scheduler;
    private final Runnable tick;
    private int started;
    private Object generation;
    private Ticket pending;

    TaskForegroundTicker(Scheduler scheduler, Runnable tick) {
        this.scheduler = scheduler;
        this.tick = tick;
    }

    synchronized void activityStarted() {
        if (started++ != 0) return;
        Object expected = new Object();
        generation = expected;
        pending = scheduler.repeat(() -> run(expected), INTERVAL_MILLIS);
    }

    synchronized void activityStopped() {
        if (started == 0 || --started != 0) return;
        generation = null;
        if (pending != null) pending.cancel();
        pending = null;
    }

    private void run(Object expected) {
        synchronized (this) {
            // Cancellation need not remove an already-dispatched callback. Do not let an old
            // foreground session perform work after a later activity becomes visible.
            if (generation != expected) return;
        }
        // Never interrupt an admitted sweep or hold up lifecycle callbacks while it uses storage.
        tick.run();
    }
}
