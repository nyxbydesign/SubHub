package com.subhub.app.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service queue resource bookkeeping only. This grants no runtime/OS/effect admission. Every
 * entered callback is retained through actual return. Before-body cancellation atomically detaches
 * a static platform relay from all owner/payload references, then waits the original dispatch return.
 * The neutral raw relay may still run later; this never claims that callback already returned.
 */
final class AccessibilityTaskQueues {
    private final OwnedCaptureLifetime work = new OwnedCaptureLifetime();
    private final Map<Runnable, List<Task>> mainTasks = new IdentityHashMap<>();
    private volatile boolean failed;

    boolean post(OwnedCaptureLifetime.Dispatcher dispatcher, Runnable action) {
        Objects.requireNonNull(action);
        return post(dispatcher, action, action);
    }
    boolean post(OwnedCaptureLifetime.Dispatcher dispatcher, Runnable identity, Runnable action) {
        Objects.requireNonNull(identity); Objects.requireNonNull(action);
        Task task = task(identity, action);
        return task != null && task.post(dispatcher);
    }
    private Task task(Runnable identity, Runnable action) {
        OwnedCaptureLifetime.Ticket retained = work.begin();
        if (retained == null) return null;
        Task task = new Task(identity, action, retained);
        if (identity != null) synchronized (mainTasks) {
            mainTasks.computeIfAbsent(identity, ignored -> new ArrayList<>()).add(task);
        }
        return task;
    }
    void removeCallbacks(Runnable identity) {
        List<Task> snapshot;
        synchronized (mainTasks) {
            List<Task> tasks = mainTasks.get(identity);
            snapshot = tasks == null ? Collections.emptyList() : new ArrayList<>(tasks);
        }
        // Closing a final ticket may invoke a drain observer; never do that under the map lock.
        for (Task task : snapshot) task.cancelPayload();
    }
    void seal(Runnable afterActualCallbacks) { work.seal(afterActualCallbacks); }
    boolean drained() { return !failed && work.drained(); }
    int outstanding() { return work.outstanding(); }
    boolean failed() { return failed; }
    Lane wrap(ScheduledExecutorService exclusive) { return new Lane(exclusive, false); }
    Lane wrapLegacy(ScheduledExecutorService exclusive) { return new Lane(exclusive, true); }

    /** A queued platform callback holds only this detachable relay, never an enclosing owner. */
    private static final class Relay implements Runnable {
        private Target target;
        private boolean detached;
        Relay(Target target) { this.target = target; }
        synchronized boolean detach(Target expected) {
            if (detached) return true;
            if (target != expected) return false;
            detached = true; target = null; return true;
        }
        synchronized boolean wasDetached() { return detached; }
        @Override public void run() {
            Target entered;
            synchronized (this) { entered = target; target = null; }
            if (entered != null) entered.runEntered();
        }
    }
    private interface Target { void runEntered(); }
    private final class Task implements Target {
        final Runnable identity, action;
        final OwnedCaptureLifetime.Ticket retained;
        final Relay relay = new Relay(this);
        final AtomicBoolean finished = new AtomicBoolean();
        private boolean dispatchReturned, bodyReturned;
        Task(Runnable identity, Runnable action, OwnedCaptureLifetime.Ticket retained) {
            this.identity = identity; this.action = action; this.retained = retained;
        }
        boolean post(OwnedCaptureLifetime.Dispatcher dispatcher) {
            try {
                boolean accepted = dispatcher.post(relay);
                if (!accepted) {
                    cancelPayload();
                    if (!relay.wasDetached()) {
                        failed = true;
                        throw new IllegalStateException("Dispatcher refused an entered Accessibility callback");
                    }
                }
                return accepted;
            } catch (RuntimeException | Error uncertain) {
                failed = true; // A throwing dispatcher may have accepted the callback.
                throw uncertain;
            } finally {
                synchronized (this) { dispatchReturned = true; }
                finishIfReturned();
            }
        }
        @Override public void runEntered() {
            // Relay's pending-to-running handoff removed its reference atomically. Cancellation
            // can no longer retire this body: the local entered reference owns actual return.
            try { action.run(); }
            finally {
                synchronized (this) { bodyReturned = true; }
                finishIfReturned();
            }
        }
        boolean cancelPayload() {
            if (!relay.detach(this)) return false;
            // Not a claim that the raw OS callback returned. Its static relay is now owner-free;
            // no later invocation can admit/access this task's action, data or resource ledger.
            finishIfReturned(); return true;
        }
        void finishIfReturned() {
            boolean neverAdmitted = relay.wasDetached();
            synchronized (this) { if (!dispatchReturned || !bodyReturned && !neverAdmitted) return; }
            if (!finished.compareAndSet(false, true)) return;
            if (identity != null) synchronized (mainTasks) {
                List<Task> tasks = mainTasks.get(identity);
                if (tasks != null) {
                    tasks.remove(this); if (tasks.isEmpty()) mainTasks.remove(identity);
                }
            }
            retained.close();
        }
    }

    /** Same exclusive lane/thread/priority/cadence supplied by the original service factory. */
    final class Lane extends AbstractExecutorService implements ScheduledExecutorService {
        private final ScheduledExecutorService executor;
        private volatile boolean shutdown;
        private final boolean legacyInterrupts;
        Lane(ScheduledExecutorService executor, boolean legacyInterrupts) {
            this.executor = Objects.requireNonNull(executor); this.legacyInterrupts = legacyInterrupts;
        }
        @Override public void execute(Runnable command) { schedule(command, 0, TimeUnit.MILLISECONDS); }
        @Override public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            Objects.requireNonNull(command); return schedule(java.util.concurrent.Executors.callable(command, null), delay, unit);
        }
        @Override public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            Objects.requireNonNull(callable); Objects.requireNonNull(unit);
            if (shutdown) throw new RejectedExecutionException("Accessibility lane is shut down");
            FutureTask<V> result = new FutureTask<>(callable);
            Task task = task(null, result);
            if (task == null) throw new RejectedExecutionException("Accessibility callbacks are sealed");
            final class Receipt implements ScheduledFuture<V> {
                ScheduledFuture<?> scheduled;
                @Override public long getDelay(TimeUnit target) { return scheduled.getDelay(target); }
                @Override public int compareTo(Delayed other) { return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS)); }
                @Override public boolean cancel(boolean mayInterrupt) {
                    boolean cancelled = result.cancel(mayInterrupt);
                    if (cancelled && task.cancelPayload()) {
                        // Only a proven owner-free static relay may be removed from its queue.
                        // Already-entered bodies retain their ticket through actual return.
                        scheduled.cancel(false); // Executor termination is a separate owner receipt.
                    }
                    return cancelled;
                }
                @Override public boolean isCancelled() { return result.isCancelled(); }
                @Override public boolean isDone() { return result.isDone(); }
                @Override public V get() throws java.util.concurrent.ExecutionException, InterruptedException { return result.get(); }
                @Override public V get(long timeout, TimeUnit target) throws java.util.concurrent.ExecutionException,
                        InterruptedException, java.util.concurrent.TimeoutException { return result.get(timeout, target); }
            }
            Receipt receipt = new Receipt();
            try {
                boolean accepted = task.post(callback -> {
                    receipt.scheduled = executor.schedule(callback, delay, unit); return true;
                });
                if (!accepted) { result.cancel(false); throw new RejectedExecutionException("Accessibility lane refused callback"); }
            } catch (RuntimeException | Error uncertain) {
                result.cancel(false); throw uncertain;
            }
            return receipt;
        }
        @Override public void shutdown() { shutdown = true; executor.shutdown(); }
        @Override public List<Runnable> shutdownNow() {
            // No false claim from Future cancellation: queued wrappers still return at their
            // scheduled deadlines, running work is not treated as terminated. Managed consumers
            // must use asynchronous drain; the legacy service preserves its own stop fences.
            if (legacyInterrupts) {
                shutdown = true;
                // Preserve old compatibility behavior, but deliberately do not manufacture
                // callback returns from the cancellation list. Legacy teardown remains unqualified.
                return executor.shutdownNow();
            }
            shutdown(); return Collections.emptyList();
        }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return executor.isTerminated(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return executor.awaitTermination(timeout, unit);
        }
        @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initial, long period, TimeUnit unit) {
            throw new UnsupportedOperationException("Accessibility service uses owned one-shot scheduling only");
        }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initial, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException("Accessibility service uses owned one-shot scheduling only");
        }
    }
}
