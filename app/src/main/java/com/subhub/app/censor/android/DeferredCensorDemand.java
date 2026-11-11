package com.subhub.app.censor.android;

import java.util.Objects;

/** One retained main-thread demand after a temporary gate refusal; never retries accepted writes. */
final class DeferredCensorDemand implements AutoCloseable {
    interface Scheduler { boolean post(Runnable command, long delay); void remove(Runnable command); }
    private final Scheduler scheduler;
    private final Runnable request, failure;
    private Runnable pending;
    private boolean closed, running;
    private long delay = 16;
    DeferredCensorDemand(Scheduler scheduler, Runnable request, Runnable failure) {
        this.scheduler = Objects.requireNonNull(scheduler); this.request = Objects.requireNonNull(request);
        this.failure = Objects.requireNonNull(failure);
    }
    void defer() {
        if (closed || pending != null) return;
        Runnable callback = new Runnable() {
            @Override public void run() {
                if (pending != this) return;
                pending = null;
                if (closed) return;
                running = true;
                try { request.run(); } finally { running = false; }
            }
        };
        pending = callback;
        long wait = delay; delay = Math.min(250, delay * 2);
        try {
            if (!scheduler.post(callback, wait)) { pending = null; closed = true; failure.run(); }
        } catch (RuntimeException | Error uncertain) {
            // The exact callback might have been accepted. Keep it until same-main removal/return.
            closed = true; failure.run();
            throw uncertain;
        }
    }
    void succeeded() { delay = 16; }
    void cancel() {
        Runnable selected = pending;
        if (selected != null) {
            // Scheduler is the same main Looper. Removal cannot race this callback's execution.
            scheduler.remove(selected); pending = null;
        }
    }
    @Override public void close() { closed = true; cancel(); }
    boolean drained() { return closed && pending == null && !running; }
}
