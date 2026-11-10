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
}
