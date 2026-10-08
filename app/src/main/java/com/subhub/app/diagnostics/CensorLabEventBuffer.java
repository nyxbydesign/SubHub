package com.subhub.app.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded, non-blocking event handoff used only while an explicit lab session is active. */
final class CensorLabEventBuffer {
    static final int DEFAULT_CAPACITY = 50_000;
    static final long DEFAULT_BYTE_CAPACITY = 16L * 1024L * 1024L;

    private final int capacity;
    private final long byteCapacity;
    private final ConcurrentLinkedQueue<Event> events = new ConcurrentLinkedQueue<>();
    private final AtomicInteger accepted = new AtomicInteger();
    private final AtomicLong acceptedBytes = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    CensorLabEventBuffer() {
        this(DEFAULT_CAPACITY, DEFAULT_BYTE_CAPACITY);
    }

    CensorLabEventBuffer(int capacity) {
        this(capacity, DEFAULT_BYTE_CAPACITY);
    }

    CensorLabEventBuffer(int capacity, long byteCapacity) {
        this.capacity = Math.max(1, capacity);
        this.byteCapacity = Math.max(1L, byteCapacity);
    }

    boolean offer(Event event) {
        if (event == null) return false;
        int slot = accepted.getAndIncrement();
        long eventBytes = event.estimatedBytes();
        long bytes = acceptedBytes.addAndGet(eventBytes);
        if (slot >= capacity || bytes > byteCapacity) {
            accepted.decrementAndGet();
            acceptedBytes.addAndGet(-eventBytes);
            dropped.incrementAndGet();
            return false;
        }
        events.offer(event);
        return true;
    }

    List<Event> snapshot() {
        return new ArrayList<>(events);
    }

    int size() {
        return accepted.get();
    }

    long dropped() {
        return dropped.get();
    }

    static final class Event {
        final long sequence;
        final long elapsedNanos;
        final long uptimeMillis;
        final long wallMillis;
        final String thread;
        final String tag;
        final String message;

        Event(long sequence, long elapsedNanos, long uptimeMillis, long wallMillis, String thread,
                String tag, String message) {
            this.sequence = sequence;
            this.elapsedNanos = elapsedNanos;
            this.uptimeMillis = uptimeMillis;
            this.wallMillis = wallMillis;
            this.thread = thread;
            this.tag = tag;
            this.message = message;
        }

        long estimatedBytes() {
            return 40L + utf8(thread) + utf8(tag) + utf8(message);
        }

        private static int utf8(String value) {
            return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
        }
    }
}
