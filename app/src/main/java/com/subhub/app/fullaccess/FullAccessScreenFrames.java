package com.subhub.app.fullaccess;

/** A viewer may take the current frame once. Slow viewers never build a backlog. */
final class FullAccessScreenFrames {
    static final long INTERVAL_MS = 500;
    static final int MAX_BYTES = 256 * 1024;

    private long lastSample = -1;
    private long publishedAt;
    private byte[] latest;
    private boolean closed;

    synchronized boolean beginSample(long now) {
        if (closed || now < 0 || (lastSample >= 0 && now - lastSample < INTERVAL_MS)) return false;
        lastSample = now;
        return true;
    }

    synchronized void publish(byte[] frame, long now) {
        if (!closed && frame != null && frame.length > 0 && frame.length <= MAX_BYTES) {
            latest = frame;
            publishedAt = now;
        }
    }

    synchronized byte[] takeLatest(long now) {
        byte[] frame = latest;
        latest = null;
        long age = now - publishedAt;
        return !closed && age >= 0 && age < 2 * INTERVAL_MS ? frame : null;
    }

    synchronized void clear() { latest = null; }

    synchronized void close() {
        closed = true;
        clear();
    }
}
