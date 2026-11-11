package com.subhub.app.popup;

import java.util.Objects;

/** Exact-instance async drain. Queuing cleanup, cancelling a Future, or timing out is not closure. */
final class PopupWorkScope {
    private boolean sealed, windowsClosed, failed, acknowledged;
    private int pending;
    private Runnable afterDrained;

    synchronized Ticket begin() {
        if (sealed) return null;
        pending++;
        return new Ticket();
    }
    synchronized boolean isOpen() { return !sealed; }
    synchronized int pendingCount() { return pending; }
    synchronized boolean isDrained() { return sealed && windowsClosed && !failed && pending == 0; }

    void close(Runnable completion) {
        synchronized (this) {
            if (afterDrained != null) throw new IllegalStateException("Popup close already requested");
            afterDrained = Objects.requireNonNull(completion);
            sealed = true;
        }
        acknowledgeIfDrained();
    }
    void windowsClosed() {
        synchronized (this) { windowsClosed = true; }
        acknowledgeIfDrained();
    }
    synchronized void failed() { failed = true; sealed = true; }

    private void acknowledgeIfDrained() {
        Runnable completion;
        synchronized (this) {
            if (!isDrained() || acknowledged || afterDrained == null) return;
            acknowledged = true;
            completion = afterDrained;
        }
        // Never call the runtime registry while holding our private counter monitor.
        completion.run();
    }

    final class Ticket implements AutoCloseable {
        private boolean finished;
        boolean isCurrent() { return PopupWorkScope.this.isOpen(); }
        @Override public void close() {
            synchronized (PopupWorkScope.this) {
                if (finished) return;
                finished = true;
                pending--;
            }
            acknowledgeIfDrained();
        }
    }
}
