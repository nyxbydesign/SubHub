package com.subhub.app.remote;

/** One trailing source-data wake, never a recurring poll. No source values or authority live here. */
final class DeviceDataSyncWake implements AutoCloseable {
    static final long ACTIVITY_DELAY_MILLIS = 1_000L;
    static final long TELEMETRY_DELAY_MILLIS = 60_000L;
    interface Scheduler { Runnable schedule(Runnable work, long delayMillis); }
    private final Scheduler scheduler;
    private final java.util.function.LongSupplier clock;
    private final Runnable synchronize;
    private Pending pending;
    private boolean closed;
    private static final class Pending { final long due; Runnable cancel; Pending(long due) { this.due = due; } }

    DeviceDataSyncWake(Scheduler scheduler, java.util.function.LongSupplier clock, Runnable synchronize) {
        this.scheduler = scheduler; this.clock = clock; this.synchronize = synchronize;
    }
    synchronized void activity() { request(ACTIVITY_DELAY_MILLIS); }
    synchronized void telemetry() { request(TELEMETRY_DELAY_MILLIS); }
    private void request(long delay) {
        long due = clock.getAsLong() + delay;
        if (closed || pending != null && pending.due <= due) return;
        if (pending != null) pending.cancel.run();
        Pending next = new Pending(due); pending = next;
        try { next.cancel = scheduler.schedule(() -> fire(next), delay); }
        catch (RuntimeException unavailable) { if (pending == next) pending = null; }
    }
    private void fire(Pending expected) {
        synchronized (this) {
            if (closed || pending != expected) return;
            pending = null;
        }
        synchronize.run();
    }
    @Override public synchronized void close() {
        closed = true;
        if (pending != null) { pending.cancel.run(); pending = null; }
    }
}
