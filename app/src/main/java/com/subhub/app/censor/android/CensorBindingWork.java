package com.subhub.app.censor.android;

import java.util.Objects;

/** Counted binding callbacks. A dispatch exception is not proof a callback was never enqueued. */
final class CensorBindingWork {
    private boolean open = true;
    private int outstanding;
    private Runnable quiescent;

    synchronized Ticket begin() {
        if (!open) return null;
        outstanding++;
        return new Ticket();
    }
    synchronized boolean isOpen() { return open; }
    synchronized int outstanding() { return outstanding; }

    void seal(Runnable afterActualCallbacks) {
        Runnable completion;
        synchronized (this) {
            if (!open) throw new IllegalStateException("Censor binding already sealed");
            open = false; quiescent = Objects.requireNonNull(afterActualCallbacks);
            completion = takeCompletion();
        }
        if (completion != null) completion.run();
    }
    private Runnable takeCompletion() {
        if (open || outstanding != 0) return null;
        Runnable result = quiescent; quiescent = null; return result;
    }
    final class Ticket implements AutoCloseable {
        private boolean returned;
        boolean current() { synchronized (CensorBindingWork.this) { return !returned && open; } }
        @Override public void close() {
            Runnable completion;
            synchronized (CensorBindingWork.this) {
                if (returned) return;
                returned = true; outstanding--; completion = takeCompletion();
            }
            if (completion != null) completion.run();
        }
    }
}
