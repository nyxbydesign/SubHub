package com.subhub.app.censor.android;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * One product's callbacks on an already capability-bound serial writer. This is not admission
 * authority and never closes the shared writer. Drain requires both actual callback return and
 * the writer's acknowledged receipt; an uncertain dispatch/write remains an owned obligation.
 */
final class CensorAccountingQueue implements Executor, AutoCloseable {
    /** Adapter proof: the bound writer refused before invoking its executor. Never wrap uncertainty. */
    static final class AdmissionRefused extends RejectedExecutionException {
        private static final long serialVersionUID = 1L;
        AdmissionRefused(Throwable cause) { super("Censor accounting admission was refused", cause); }
    }
    private final Function<Runnable, CompletableFuture<Void>> dispatch;
    private final Consumer<Throwable> failure;
    private final Set<Work> work = new LinkedHashSet<>();
    private boolean closed, failed;

    CensorAccountingQueue(Function<Runnable, CompletableFuture<Void>> dispatch, Consumer<Throwable> failure) {
        this.dispatch = Objects.requireNonNull(dispatch); this.failure = Objects.requireNonNull(failure);
    }
    private final class Work {
        final Runnable action;
        boolean started, returned, acknowledged, dispatched, uncertain;
        final Receipt receipt = new Receipt();
        Work(Runnable action) { this.action = action; }
        void run() {
            synchronized (CensorAccountingQueue.this) {
                if (started) return;
                started = true;
            }
            try { action.run(); }
            finally {
                synchronized (CensorAccountingQueue.this) { returned = true; }
                releaseIfComplete();
            }
        }
        void releaseIfComplete() {
            boolean complete;
            synchronized (CensorAccountingQueue.this) {
                complete = returned && acknowledged && dispatched && !uncertain;
                if (complete) work.remove(this);
            }
            if (complete) receipt.succeeded();
        }
    }
    @Override public void execute(Runnable action) { submit(action); }
    /** Exact callback + writer ACK + dispatch-return receipt. Cancellation/direct completion cannot retire queued work. */
    CompletableFuture<Void> submit(Runnable action) {
        Work admitted = new Work(Objects.requireNonNull(action));
        synchronized (this) {
            if (closed || failed) throw new RejectedExecutionException("Censor accounting is closed");
            work.add(admitted);
        }
        try {
            Objects.requireNonNull(dispatch.apply(admitted::run)).whenComplete((ignored, error) -> {
                if (error != null) { failed(admitted, error); return; }
                synchronized (CensorAccountingQueue.this) { admitted.acknowledged = true; }
                admitted.releaseIfComplete();
            });
            synchronized (this) { admitted.dispatched = true; }
            admitted.releaseIfComplete();
        } catch (AdmissionRefused refused) {
            boolean contradictory;
            synchronized (this) {
                contradictory = admitted.started;
                if (!contradictory) { work.remove(admitted); closed = true; }
            }
            if (contradictory) failed(admitted, refused);
            throw refused;
        } catch (RuntimeException | Error uncertain) {
            // A throwing dispatcher may have queued the actual callback. Keep its obligation;
            // the bound owner writer separately retains failed payloads for reconciliation.
            failed(admitted, uncertain); throw uncertain;
        }
        return admitted.receipt;
    }
    private void failed(Work admitted, Throwable error) {
        boolean report;
        synchronized (this) {
            admitted.uncertain = true; report = !failed; failed = true; closed = true;
        }
        admitted.receipt.failed(error);
        if (report) failure.accept(error);
    }
    private static final class Receipt extends CompletableFuture<Void> {
        void succeeded() { super.complete(null); }
        void failed(Throwable error) { super.completeExceptionally(error); }
        @Override public boolean complete(Void value) { return false; }
        @Override public boolean completeExceptionally(Throwable error) { return false; }
        @Override public boolean cancel(boolean interrupt) { return false; }
        @Override public void obtrudeValue(Void value) { throw new UnsupportedOperationException("Read-only accounting receipt"); }
        @Override public void obtrudeException(Throwable error) { throw new UnsupportedOperationException("Read-only accounting receipt"); }
    }
    @Override public synchronized void close() { closed = true; }
    synchronized boolean drained() { return closed && !failed && work.isEmpty(); }
}
