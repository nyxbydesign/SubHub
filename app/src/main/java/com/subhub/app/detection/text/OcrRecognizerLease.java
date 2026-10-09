package com.subhub.app.detection.text;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Retires a recognizer without closing its model while an SDK task still owns it. */
final class OcrRecognizerLease implements AutoCloseable {
    private final Runnable release;
    private boolean retired;
    private boolean released;
    private int tasks;

    OcrRecognizerLease(Runnable release) { this.release = Objects.requireNonNull(release); }

    synchronized Operation begin() {
        if (retired) return null;
        tasks++;
        return new Operation(this);
    }

    @Override public void close() {
        boolean releaseNow;
        synchronized (this) {
            retired = true;
            releaseNow = releaseIfDrained();
        }
        if (releaseNow) release.run();
    }

    private void finished() {
        boolean releaseNow;
        synchronized (this) {
            tasks--;
            releaseNow = releaseIfDrained();
        }
        if (releaseNow) release.run();
    }

    private boolean releaseIfDrained() {
        if (!retired || released || tasks != 0) return false;
        released = true;
        return true;
    }

    static final class Operation implements AutoCloseable {
        private final OcrRecognizerLease owner;
        private final AtomicBoolean finished = new AtomicBoolean();
        Operation(OcrRecognizerLease owner) { this.owner = owner; }
        @Override public void close() { if (finished.compareAndSet(false, true)) owner.finished(); }
    }
}
