package com.subhub.app.service;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Single-flight worker that always processes the newest pending observation and drops stale work. */
final class LatestFrameBroker<T> implements AutoCloseable {
    private final Executor executor;
    private final Consumer<T> processor;
    private final Consumer<T> disposer;
    private final int maximumPerDispatch;
    private final AtomicReference<T> pending = new AtomicReference<>();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong dropped = new AtomicLong();

    LatestFrameBroker(Executor executor, Consumer<T> processor, Consumer<T> disposer) {
        this(executor, processor, disposer, Integer.MAX_VALUE);
    }

    LatestFrameBroker(Executor executor, Consumer<T> processor, Consumer<T> disposer,
            int maximumPerDispatch) {
        if (maximumPerDispatch < 1) throw new IllegalArgumentException("positive dispatch limit required");
        this.executor = executor;
        this.processor = processor;
        this.disposer = disposer;
        this.maximumPerDispatch = maximumPerDispatch;
    }

    void submit(T value) {
        if (value == null) return;
        if (closed.get()) {
            disposer.accept(value);
            return;
        }
        T replaced = pending.getAndSet(value);
        if (replaced != null) {
            dropped.incrementAndGet();
            disposer.accept(replaced);
        }
        // close() can win after the first check but before insertion. Reclaim the orphan even
        // when close already drained its earlier snapshot and scheduleDrain will now refuse.
        if (closed.get()) {
            T orphan = pending.getAndSet(null);
            if (orphan != null) disposer.accept(orphan);
            return;
        }
        scheduleDrain();
    }

    long droppedCount() { return dropped.get(); }

    private void scheduleDrain() {
        if (closed.get() || !draining.compareAndSet(false, true)) return;
        try {
            executor.execute(this::drain);
        } catch (RuntimeException | Error rejected) {
            draining.set(false);
            T undeliverable = pending.getAndSet(null);
            if (undeliverable != null) disposer.accept(undeliverable);
            throw rejected;
        }
    }

    private void drain() {
        try {
            int processed = 0;
            while (!closed.get()) {
                T value = pending.getAndSet(null);
                if (value == null) return;
                try {
                    processor.accept(value);
                } finally {
                    disposer.accept(value);
                }
                if (++processed >= maximumPerDispatch) return;
            }
        } finally {
            draining.set(false);
            if (!closed.get() && pending.get() != null) scheduleDrain();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        T value = pending.getAndSet(null);
        if (value != null) disposer.accept(value);
    }

    /**
     * Optional async processor, leaving the original synchronous broker unchanged. Exactly one
     * current frame plus the newest pending frame is retained. Completion owns the last-use body;
     * signal only after real native work, and use a new effect admission for its final publication.
     */
    static <T> Async<T> async(Executor executor, AsyncProcessor<T> processor, Consumer<T> disposer) {
        return new Async<>(executor, processor, disposer);
    }
    interface AsyncProcessor<T> { void process(T frame, Completion completion); }
    interface Completion {
        /** Single use. No frame/resource access may follow this final-use body. */
        void complete(Runnable finalUse);
    }
    static final class Async<T> implements AutoCloseable {
        private final Executor executor;
        private final AsyncProcessor<T> processor;
        private final Consumer<T> disposer;
        private T pending;
        private Work current;
        private boolean scheduled, closed, failed;
        private int dispatches, disposals;
        private long dropped;
        private final java.util.List<T> failedDisposals = new java.util.ArrayList<>();
        Async(Executor executor, AsyncProcessor<T> processor, Consumer<T> disposer) {
            this.executor = java.util.Objects.requireNonNull(executor);
            this.processor = java.util.Objects.requireNonNull(processor);
            this.disposer = java.util.Objects.requireNonNull(disposer);
        }
        void submit(T value) {
            if (value == null) return;
            T replaced;
            synchronized (this) {
                if (closed || failed) replaced = value;
                else { replaced = pending; pending = value; if (replaced != null) dropped++; }
                if (replaced != null) disposals++;
            }
            if (replaced != null) disposePending(replaced);
            schedule();
        }
        synchronized long droppedCount() { return dropped; }
        synchronized boolean failed() { return failed; }
        synchronized boolean managedResourcesDrained() {
            return closed && !failed && pending == null && current == null && !scheduled
                    && dispatches == 0 && disposals == 0;
        }
        private void schedule() {
            Dispatch<T> dispatch;
            synchronized (this) {
                if (closed || failed || scheduled || current != null || pending == null
                        || dispatches != 0 || disposals != 0) return;
                scheduled = true; dispatches++; dispatch = new Dispatch<>(this);
            }
            try { executor.execute(dispatch); }
            catch (RuntimeException | Error uncertain) {
                // Generic execute may enqueue/run then throw. Keep this delivery until actual return.
                synchronized (this) { failed = true; }
                throw uncertain;
            } finally { dispatch.submissionReturned(); }
        }
        /** Detached queue callback: a retained duplicate cannot retain the broker after return. */
        private static final class Dispatch<T> implements Runnable {
            private final AtomicBoolean entered = new AtomicBoolean();
            private Async<T> owner;
            private boolean submitted, returned, retired;
            Dispatch(Async<T> owner) { this.owner = owner; }
            @Override public void run() {
                if (!entered.compareAndSet(false, true)) return;
                Async<T> exact;
                synchronized (this) { exact = owner; }
                try { if (exact != null) exact.process(); }
                finally {
                    exact = null;
                    synchronized (this) { returned = true; }
                    retire();
                }
            }
            void submissionReturned() { synchronized (this) { submitted = true; } retire(); }
            void retire() {
                Async<T> exact;
                synchronized (this) {
                    if (retired || !submitted || !returned) return;
                    retired = true; exact = owner; owner = null;
                }
                if (exact != null) exact.dispatchReturned();
            }
        }
        private void process() {
            Work work;
            synchronized (this) {
                scheduled = false;
                if (closed || failed || pending == null) work = null;
                else { work = new Work(pending); pending = null; current = work; }
            }
            if (work == null) return;
            try { processor.process(work.value, work.completion); }
            catch (RuntimeException | Error uncertain) {
                synchronized (this) { failed = true; }
                throw uncertain;
            } finally {
                synchronized (this) { work.processorReturned = true; }
                work.finish();
            }
        }
        private void dispatchReturned() {
            synchronized (this) { dispatches--; }
            schedule();
        }
        /** One-use completion admission; retained duplicate callbacks have no owner target. */
        private static final class CompletionRelay implements Completion {
            private Consumer<Runnable> target;
            CompletionRelay(Consumer<Runnable> target) { this.target = target; }
            @Override public void complete(Runnable finalUse) {
                java.util.Objects.requireNonNull(finalUse);
                Consumer<Runnable> exact;
                synchronized (this) { exact = target; target = null; }
                if (exact != null) exact.accept(finalUse);
            }
        }
        private final class Work {
            private T value;
            private boolean processorReturned, completionEntered, completionReturned, retiring;
            final Completion completion = new CompletionRelay(this::complete);
            Work(T value) { this.value = value; }
            private void complete(Runnable finalUse) {
                java.util.Objects.requireNonNull(finalUse);
                synchronized (Async.this) {
                    if (completionEntered) return;
                    completionEntered = true;
                }
                try { finalUse.run(); }
                catch (RuntimeException | Error uncertain) {
                    synchronized (Async.this) { failed = true; }
                    throw uncertain;
                } finally {
                    synchronized (Async.this) { completionReturned = true; }
                    finish();
                }
            }
            void finish() {
                T retired;
                synchronized (Async.this) {
                    if (retiring || !processorReturned || !completionReturned) return;
                    retiring = true; disposals++; retired = value;
                }
                boolean returned = false;
                try { disposer.accept(retired); returned = true; }
                catch (RuntimeException | Error uncertain) {
                    synchronized (Async.this) { failed = true; }
                    throw uncertain;
                } finally {
                    synchronized (Async.this) {
                        disposals--;
                        // A failed disposer retains the exact frame and occupancy; never retry it.
                        if (returned) { value = null; if (current == this) current = null; }
                    }
                }
                schedule();
            }
        }
        private void disposePending(T value) {
            try { disposer.accept(value); }
            catch (RuntimeException | Error uncertain) {
                synchronized (this) { failed = true; failedDisposals.add(value); }
                throw uncertain;
            } finally { synchronized (this) { disposals--; } }
        }
        @Override public void close() {
            T retired;
            synchronized (this) {
                if (closed) return;
                closed = true; retired = pending; pending = null;
                if (retired != null) disposals++;
            }
            if (retired != null) disposePending(retired);
            // Current work and accepted dispatch are not cancelled or acknowledged here.
        }
    }
}
