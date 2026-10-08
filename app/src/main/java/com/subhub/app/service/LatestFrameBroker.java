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
    private final AtomicReference<T> pending = new AtomicReference<>();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong dropped = new AtomicLong();

    LatestFrameBroker(Executor executor, Consumer<T> processor, Consumer<T> disposer) {
        this.executor = executor;
        this.processor = processor;
        this.disposer = disposer;
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
        scheduleDrain();
    }

    long droppedCount() { return dropped.get(); }

    private void scheduleDrain() {
        if (closed.get() || !draining.compareAndSet(false, true)) return;
        executor.execute(this::drain);
    }

    private void drain() {
        try {
            while (!closed.get()) {
                T value = pending.getAndSet(null);
                if (value == null) return;
                try {
                    processor.accept(value);
                } finally {
                    disposer.accept(value);
                }
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
