package com.subhub.app.remote;

import java.util.Objects;

/** One wake per continuously usable default-network epoch; stale delayed callbacks are inert. */
final class StableConnectionWake implements AutoCloseable {
    static final long STABLE_MILLIS = 2_000;
    interface Scheduler { Runnable schedule(Runnable work, long delayMillis); }
    private final Scheduler scheduler;
    private final Runnable available;
    private Object network, pending;
    private Runnable cancel;
    private boolean usable, delivered, closed;

    StableConnectionWake(Scheduler scheduler, Runnable available) {
        this.scheduler = Objects.requireNonNull(scheduler);
        this.available = Objects.requireNonNull(available);
    }

    synchronized void available(Object selected) {
        if (closed || Objects.equals(network, selected)) return;
        retire(); network = Objects.requireNonNull(selected); usable = false; delivered = false;
    }

    synchronized void capabilities(Object selected, boolean ready) {
        if (closed || network == null || !network.equals(selected)) return;
        if (!ready) { retire(); usable = false; delivered = false; return; }
        if (usable) return;
        usable = true;
        Object ticket = new Object(); pending = ticket;
        cancel = scheduler.schedule(() -> deliver(ticket), STABLE_MILLIS);
    }

    synchronized void lost(Object selected) {
        if (closed || !Objects.equals(network, selected)) return;
        retire(); network = null; usable = false; delivered = false;
    }

    private synchronized void deliver(Object ticket) {
        if (closed || pending != ticket || !usable || delivered) return;
        pending = null; cancel = null; delivered = true;
        available.run();
    }

    private void retire() {
        pending = null;
        if (cancel != null) cancel.run();
        cancel = null;
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true; retire(); network = null;
    }
}
