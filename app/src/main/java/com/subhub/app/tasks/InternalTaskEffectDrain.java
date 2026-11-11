package com.subhub.app.tasks;

import java.util.List;
import java.util.Objects;

/** Replayable effect ordering. A SQL completion is never the receipt for an OS post or assessment. */
final class InternalTaskEffectDrain<E> {
    static final int MAX_PER_PASS = 64;
    // Keep below the dispatcher's per-audience limit. Eviction must cancel AND durably ACK first.
    static final int MAX_NOTICES_PER_AUDIENCE = 64;
    enum Notice { READY, SKIP, RETRY }
    static final class Pending<E> {
        final long rowId;
        final E effect;
        Pending(long rowId,E effect) {
            if(rowId<=0)throw new IllegalArgumentException("Invalid effect pagination identity");
            this.rowId=rowId;this.effect=Objects.requireNonNull(effect);
        }
    }
    interface Access<E> {
        void requireCurrent();
        /** Bounded row-ordered page after the cursor, wrapping once. Cursor is not authority. */
        List<Pending<E>> pending(boolean failures,long afterRowId,int limit) throws Exception;
        boolean discard(E effect) throws Exception;
        boolean failure(E effect);
        void assess(E effect) throws Exception;
        void done(E effect) throws Exception;
        String key(E effect);
        TaskNoticeLedger.Audience audience(E effect);
        Notice prepare(E effect) throws Exception;
        List<TaskNoticeLedger.Receipt> receipts();
        boolean obsolete(TaskNoticeLedger.Receipt receipt);
        void cancel(TaskNoticeLedger.Receipt receipt);
        void removeAfterCancellation(TaskNoticeLedger.Receipt receipt);
        TaskNoticeLedger.Receipt admit(TaskNoticeLedger.Receipt receipt);
        boolean post(E effect, TaskNoticeLedger.Receipt receipt) throws Exception;
    }
    private String generation;
    private long afterFailure, afterNotice;
    void run(String generation, long observedAt, Access<E> access) throws Exception {
        Objects.requireNonNull(access); access.requireCurrent();
        if (!generation.equals(this.generation)) {
            this.generation = generation; afterFailure = 0; afterNotice = 0;
        }
        // Read at most 64 effects, not an unbounded backlog. Reserve 32 accounting slots;
        // unused accounting slots go to notices. Each lane wraps by rowid across later passes.
        List<Pending<E>> failures = page(access, true, afterFailure, MAX_PER_PASS / 2);
        Exception problem = null;
        for (Pending<E> pending : failures) {
            E effect = pending.effect; afterFailure = pending.rowId;
            access.requireCurrent();
            try {
                if (!access.discard(effect)) access.assess(effect);
                // Idempotent stable occurrence receipt; ACK must precede SQL completion.
                access.requireCurrent(); access.done(effect);
            } catch (Exception failure) {
                access.requireCurrent(); problem = remember(problem, failure);
            }
        }
        // Optional OS cancellation is never a prerequisite for independent failure accounting.
        // The durable bounded ledger retains every failed cancellation/removal for the next pass.
        for (TaskNoticeLedger.Receipt receipt : access.receipts()) {
            access.requireCurrent();
            try { if (access.obsolete(receipt)) cancel(access, receipt); }
            catch (Exception failure) { access.requireCurrent(); problem = remember(problem, failure); }
        }
        // Even optional SQL decoding/selection happens after independent accounting has progressed.
        List<Pending<E>> notices = page(access, false, afterNotice, MAX_PER_PASS - failures.size());
        for (Pending<E> pending : notices) {
            E effect = pending.effect;
            // Advance before RETRY, false posts and exceptions; SQL completion is never a cursor.
            afterNotice = pending.rowId;
            access.requireCurrent();
            try { notice(generation, observedAt, effect, access); }
            catch (Exception failure) { access.requireCurrent(); problem = remember(problem, failure); }
        }
        if (problem != null) throw problem;
    }
    private List<Pending<E>> page(Access<E> access, boolean failures, long after, int limit) throws Exception {
        access.requireCurrent(); List<Pending<E>> page=Objects.requireNonNull(access.pending(failures,after,limit));
        if(page.size()>limit)throw new IllegalStateException("Unbounded task effect page");
        java.util.Set<Long> seen=new java.util.HashSet<>();
        for(Pending<E> pending:page)if(!seen.add(pending.rowId)||access.failure(pending.effect)!=failures)
            throw new IllegalStateException("Invalid task effect lane page");
        return page;
    }
    private static Exception remember(Exception first, Exception next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }
    private void notice(String generation, long observedAt, E effect, Access<E> access) throws Exception {
        if (access.discard(effect)) { access.requireCurrent(); access.done(effect); return; }
        Notice readiness = access.prepare(effect);
        if (readiness == Notice.RETRY) return;
        if (readiness == Notice.SKIP) { access.requireCurrent(); access.done(effect); return; }
        String key = access.key(effect);
        TaskNoticeLedger.Audience audience = access.audience(effect);
        List<TaskNoticeLedger.Receipt> receipts = access.receipts();
        TaskNoticeLedger.Receipt known = null, oldest = null;
        int count = 0;
        for (TaskNoticeLedger.Receipt receipt : receipts) {
            if (receipt.audience != audience) continue;
            count++;
            if (receipt.effectKey.equals(key)) known = receipt;
            if (oldest == null || receipt.admittedAtMillis < oldest.admittedAtMillis) oldest = receipt;
        }
        if (known == null && count >= MAX_NOTICES_PER_AUDIENCE) cancel(access, oldest);
        access.requireCurrent();
        TaskNoticeLedger.Receipt receipt = access.admit(new TaskNoticeLedger.Receipt(generation, audience, key, observedAt));
        access.requireCurrent();
        if (access.post(effect, receipt)) {
            access.requireCurrent(); access.done(effect);
        } // False or exception retains both exact durable receipt and pending SQL effect.
    }
    private void cancel(Access<E> access, TaskNoticeLedger.Receipt receipt) {
        access.requireCurrent(); access.cancel(receipt);
        access.requireCurrent(); access.removeAfterCancellation(receipt);
    }
}
