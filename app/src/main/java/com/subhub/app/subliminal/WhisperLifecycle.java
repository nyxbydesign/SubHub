package com.subhub.app.subliminal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Main-thread-confined ownership of one Whisper window and every callback that can touch it.
 * Android supplies the queue/window operations; this exact production state machine is pure Java.
 */
final class WhisperLifecycle {
    interface Effects {
        /** False means busy or revoked, never successful application. */
        boolean run(Runnable effect);
        boolean revoked();
        void failed();
    }
    interface Queue {
        boolean post(Runnable callback, long delayMillis);
        /** Returning acknowledges removal of this exact callback from this queue. */
        void remove(Runnable callback);
    }
    interface Window {
        void add();
        /** True if either the native attachment or the View parent is still present. */
        boolean attached();
        void remove();
        void hide();
    }

    private final Effects effects;
    private final Queue queue;
    private final Window window;
    private final Set<Task> tasks = new LinkedHashSet<>();
    private boolean closed, attachmentAttempted, detached = true, hidden;
    private Runnable afterDrained;
    private boolean acknowledged;

    WhisperLifecycle(Effects effects, Queue queue, Window window) {
        this.effects = Objects.requireNonNull(effects);
        this.queue = Objects.requireNonNull(queue);
        this.window = Objects.requireNonNull(window);
    }

    boolean isClosed() { return closed; }
    boolean hasWindow() { return attachmentAttempted; }

    void ensureAdded() {
        if (closed || effects.revoked()) return;
        if (attachmentAttempted) return;
        // addView may attach and then throw. Retain the obligation BEFORE calling the platform.
        attachmentAttempted = true;
        detached = false;
        window.add();
    }

    /** Keep the exact request when the effect gate is busy; a newer request with this key wins. */
    boolean runOrRetry(Object key, Runnable action) {
        cancel(key);
        if (closed) return false;
        Task task = new Task(key, action);
        tasks.add(task);
        return task.attempt();
    }

    void post(Object key, Runnable action, long delayMillis) {
        cancel(key);
        if (closed) return;
        Task task = new Task(key, action);
        tasks.add(task);
        task.enqueue(delayMillis);
    }

    void cancel(Object key) {
        for (Task task : new ArrayList<>(tasks)) if (task.key == key) task.cancel();
    }

    void hide() { window.hide(); }

    void close() { close(null); }

    /** ACK only after actual detach, queue removal and return of an already executing callback. */
    void close(Runnable completion) {
        if (completion != null) {
            if (afterDrained != null && afterDrained != completion)
                throw new IllegalStateException("Whisper closure already has an acknowledgment");
            afterDrained = completion;
        }
        closed = true;
        RuntimeException failure = null;
        for (Task task : new ArrayList<>(tasks)) {
            try { task.cancel(); }
            catch (RuntimeException failed) { failure = append(failure, failed); }
        }
        hidden = false;
        try { window.hide(); hidden = true; }
        catch (RuntimeException failed) { failure = append(failure, failed); }
        try { detach(); }
        catch (RuntimeException failed) { failure = append(failure, failed); }
        if (failure != null) throw failure;
        acknowledgeIfDrained();
    }

    private void detach() {
        // Even a repeated close must inspect reality, not just an optimistic boolean.
        detached = false;
        if (attachmentAttempted || window.attached()) {
            try { window.remove(); }
            catch (IllegalArgumentException absent) {
                if (window.attached()) throw absent;
            }
        }
        if (window.attached()) throw new IllegalStateException("Whisper window still attached");
        attachmentAttempted = false;
        detached = true;
    }

    boolean drained() { return closed && hidden && detached && tasks.isEmpty(); }
    int pendingCallbacks() { return tasks.size(); }

    private void acknowledgeIfDrained() {
        if (!drained() || afterDrained == null || acknowledged) return;
        acknowledged = true;
        afterDrained.run();
    }

    private static RuntimeException append(RuntimeException first, RuntimeException next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }

    private final class Task implements Runnable {
        final Object key;
        final Runnable action;
        boolean queued, running, retired, finished;
        Task(Object key, Runnable action) {
            this.key = Objects.requireNonNull(key);
            this.action = Objects.requireNonNull(action);
        }
        @Override public void run() { attempt(); }
        boolean attempt() {
            if (finished) return false; // A stale framework callback cannot acquire any capability.
            queued = false;
            running = true;
            boolean applied = false;
            try {
                if (closed || retired) return false;
                if (effects.revoked()) { close(); return false; }
                boolean[] performed = { false };
                effects.run(() -> {
                    if (closed || retired || effects.revoked()) return;
                    action.run();
                    performed[0] = true;
                });
                applied = performed[0];
                if (!applied && !closed && !retired) {
                    if (effects.revoked()) close();
                    else enqueue(32L);
                }
                return applied;
            } catch (RuntimeException | Error failed) {
                retired = true;
                effects.failed();
                throw failed;
            } finally {
                running = false;
                if (!queued) finish();
                acknowledgeIfDrained();
            }
        }
        void enqueue(long delayMillis) {
            if (closed || retired || finished) { if (!running) finish(); return; }
            queued = true;
            try {
                if (!queue.post(this, delayMillis))
                    throw new IllegalStateException("Whisper callback queue unavailable");
            } catch (RuntimeException rejected) {
                queued = false;
                retired = true;
                if (!running) finish();
                effects.failed();
                throw rejected;
            }
        }
        void cancel() {
            retired = true; // Fence before remove: an uncertain cancellation can never render.
            if (queued) {
                queue.remove(this); // Failure retains the ticket until actual callback return/retry.
                queued = false;
            }
            if (!running) finish();
        }
        void finish() {
            if (finished) return;
            finished = true;
            tasks.remove(this);
        }
    }
}
