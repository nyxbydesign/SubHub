package com.subhub.app.service;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Resource bookkeeping, not runtime admission. Count before dispatch/OS invocation and close a
 * ticket only after actual callback/resource disposal. Sealing cannot cancel accepted obligations.
 */
final class OwnedCaptureLifetime {
    interface Dispatcher {
        /** False is an explicit no-enqueue receipt. A throw may have enqueued or run the callback. */
        boolean post(Runnable callback);
    }
    private boolean open = true;
    private int outstanding;
    private Runnable quiescent;
    private boolean completionStarted, completionReturned, completionFailed;

    synchronized Ticket begin() {
        if (!open) return null;
        outstanding = Math.addExact(outstanding, 1);
        return new Ticket();
    }
    synchronized boolean open() { return open; }
    synchronized int outstanding() { return outstanding; }
    synchronized boolean drained() {
        return !open && outstanding == 0 && completionReturned && !completionFailed;
    }

    boolean dispatch(Dispatcher dispatcher, Runnable action) {
        Objects.requireNonNull(dispatcher); Objects.requireNonNull(action);
        Ticket ticket = begin();
        if (ticket == null) return false;
        return dispatch(ticket, dispatcher, action);
    }
    boolean dispatchContinuation(Ticket acceptedRequest, Dispatcher dispatcher, Runnable action) {
        Objects.requireNonNull(dispatcher); Objects.requireNonNull(action);
        Objects.requireNonNull(acceptedRequest);
        if (acceptedRequest.owner() != this) throw new SecurityException("Foreign capture request ticket");
        Ticket continuation = acceptedRequest.child();
        if (continuation == null) throw new IllegalStateException("Capture callback followed terminal request");
        return dispatch(continuation, dispatcher, action);
    }
    private boolean dispatch(Ticket ticket, Dispatcher dispatcher, Runnable action) {
        Dispatch dispatched = new Dispatch(ticket, action);
        try {
            boolean accepted = dispatcher.post(dispatched::run);
            if (!accepted && dispatched.entered.get())
                throw new IllegalStateException("Dispatcher denied an executed capture callback");
            dispatched.settled(!accepted);
            return accepted;
        } catch (RuntimeException | Error uncertain) {
            dispatched.settled(false); // Unknown acceptance is not cancellation or a drain receipt.
            throw uncertain;
        }
    }

    void seal(Runnable afterActualWork) {
        Objects.requireNonNull(afterActualWork);
        synchronized (this) {
            if (!open) throw new IllegalStateException("Capture lifetime already sealed");
            open = false; quiescent = Objects.requireNonNull(afterActualWork);
        }
        completeIfQuiescent();
    }
    private void completeIfQuiescent() {
        Runnable completion;
        synchronized (this) {
            if (open || outstanding != 0 || completionStarted) return;
            completionStarted = true; completion = quiescent;
        }
        boolean failed = true;
        try { completion.run(); failed = false; }
        finally {
            synchronized (this) { completionFailed = failed; completionReturned = true; }
        }
    }
    final class Ticket implements AutoCloseable {
        private boolean returned;
        private OwnedCaptureLifetime owner() { return OwnedCaptureLifetime.this; }
        boolean current() { synchronized (OwnedCaptureLifetime.this) { return open && !returned; } }
        private Ticket child() {
            synchronized (OwnedCaptureLifetime.this) {
                if (returned) return null;
                outstanding = Math.addExact(outstanding, 1);
                return new Ticket(); // Continuation of accepted native work, even after seal.
            }
        }
        @Override public void close() {
            synchronized (OwnedCaptureLifetime.this) {
                if (returned) return;
                returned = true; outstanding--;
            }
            completeIfQuiescent();
        }
    }
    private static final class Dispatch {
        final Ticket ticket;
        final Runnable action;
        final AtomicBoolean entered = new AtomicBoolean();
        private boolean callbackReturned, dispatchReturned, refused;
        Dispatch(Ticket ticket, Runnable action) { this.ticket = ticket; this.action = action; }
        void run() {
            if (!entered.compareAndSet(false, true)) return;
            try { action.run(); }
            finally {
                synchronized (this) { callbackReturned = true; }
                releaseIfReturned();
            }
        }
        void settled(boolean definitelyRefused) {
            synchronized (this) { dispatchReturned = true; refused |= definitelyRefused; }
            releaseIfReturned();
        }
        void releaseIfReturned() {
            synchronized (this) {
                if (!dispatchReturned || !callbackReturned && !refused) return;
            }
            ticket.close();
        }
    }
}
