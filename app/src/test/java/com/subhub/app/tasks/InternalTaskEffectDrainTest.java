package com.subhub.app.tasks;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public final class InternalTaskEffectDrainTest {
    private static final String GENERATION = "11111111-1111-1111-1111-111111111111";
    private static final class Fake implements InternalTaskEffectDrain.Access<String> {
        final List<String> effects = new ArrayList<>(), events = new ArrayList<>();
        final List<TaskNoticeLedger.Receipt> receipts = new ArrayList<>();
        final InternalTaskEffectDrain<String> drain = new InternalTaskEffectDrain<>();
        final Set<String> retry = new HashSet<>(), prepareFails = new HashSet<>(), postFails = new HashSet<>();
        int attempted; boolean removeDone, noticePageFails;
        final Map<String,Long> rowIds=new LinkedHashMap<>();long nextRowId;
        boolean current = true, posted = true, cancelFails, removalFails, assessmentFails, obsolete, discard, revokeAtAdmit, revokeAtPost;
        InternalTaskEffectDrain.Notice readiness = InternalTaskEffectDrain.Notice.READY;
        public void requireCurrent() { if (!current) throw new SecurityException("revoked"); }
        public List<InternalTaskEffectDrain.Pending<String>> pending(boolean failures,long after,int limit) {
            if(!failures&&noticePageFails)throw new IllegalStateException("Optional page unavailable");
            for(String effect:effects)rowIds.computeIfAbsent(effect,k->++nextRowId);
            List<String> ordered=new ArrayList<>();for(String effect:effects)if(failure(effect)==failures)ordered.add(effect);
            ordered.sort(Comparator.comparingLong(rowIds::get));
            List<InternalTaskEffectDrain.Pending<String>> result=new ArrayList<>();
            for(boolean wrap:new boolean[]{false,true})for(String effect:ordered)
                if((wrap?rowIds.get(effect)<=after:rowIds.get(effect)>after)&&result.size()<limit)
                    result.add(new InternalTaskEffectDrain.Pending<>(rowIds.get(effect),effect));
            return result;
        }
        public boolean discard(String e) { return discard; }
        public boolean failure(String e) { return e.startsWith("failure"); }
        public void assess(String e) { events.add("assess:" + e); if (assessmentFails) throw new IllegalStateException(); }
        public void done(String e) { events.add("done:" + e); if(removeDone)effects.remove(e); }
        public String key(String e) { return e; }
        public TaskNoticeLedger.Audience audience(String e) { return TaskNoticeLedger.Audience.SUB_TASKS; }
        public InternalTaskEffectDrain.Notice prepare(String e) { attempted++; if(prepareFails.contains(e))throw new IllegalStateException("prepare:"+e); return retry.contains(e)?InternalTaskEffectDrain.Notice.RETRY:readiness; }
        public List<TaskNoticeLedger.Receipt> receipts() { return new ArrayList<>(receipts); }
        public boolean obsolete(TaskNoticeLedger.Receipt r) { return obsolete; }
        public void cancel(TaskNoticeLedger.Receipt r) { events.add("cancel:" + r.effectKey); if (cancelFails) throw new IllegalStateException(); }
        public void removeAfterCancellation(TaskNoticeLedger.Receipt r) { events.add("remove:" + r.effectKey); if (removalFails) throw new IllegalStateException(); receipts.remove(r); }
        public TaskNoticeLedger.Receipt admit(TaskNoticeLedger.Receipt r) {
            events.add("admit:" + r.effectKey);
            for (TaskNoticeLedger.Receipt old : receipts) if (old.effectKey.equals(r.effectKey)) return old;
            receipts.add(r); if (revokeAtAdmit) current = false; return r;
        }
        public boolean post(String e, TaskNoticeLedger.Receipt r) { events.add("post:" + e); if(postFails.contains(e))throw new IllegalStateException("post:"+e); if (revokeAtPost) current = false; return posted; }
        void run() throws Exception { drain.run(GENERATION, 2000, this); }
        void fill() { for (int i = 0; i < 64; i++) receipts.add(new TaskNoticeLedger.Receipt(GENERATION, audience(""), "old" + i, i + 1)); }
    }
    @Test public void receiptPrecedesPostAndSqlCompletion() throws Exception {
        Fake f = new Fake(); f.effects.add("new"); f.run();
        assertEquals(List.of("admit:new", "post:new", "done:new"), f.events);
    }
    @Test public void uncertainPostRetainsReceiptAndSqlPendingForIdempotentReplay() throws Exception {
        Fake f = new Fake(); f.effects.add("new"); f.posted = false; f.run();
        assertEquals(1, f.receipts.size()); assertFalse(f.events.contains("done:new"));
        f.posted = true; f.run(); assertEquals(1, f.receipts.size()); assertTrue(f.events.contains("done:new"));
    }
    @Test public void capacityCancelsExactOldReceiptAndAcknowledgesRemovalBeforeAdmit() throws Exception {
        Fake f = new Fake(); f.fill(); f.effects.add("new"); f.run();
        assertEquals(List.of("cancel:old0", "remove:old0", "admit:new", "post:new", "done:new"), f.events);
        assertEquals(64, f.receipts.size());
    }
    @Test public void failedCancellationNeverReclaimsCapacity() throws Exception {
        Fake f = new Fake(); f.fill(); f.effects.add("new"); f.cancelFails = true;
        assertThrows(IllegalStateException.class, f::run);
        assertEquals(List.of("cancel:old0"), f.events); assertEquals(64, f.receipts.size());
        f.cancelFails = false; f.run(); assertEquals(64, f.receipts.size());
    }
    @Test public void failedDurableRemovalNeverAdmitsReplacement() {
        Fake f = new Fake(); f.fill(); f.effects.add("new"); f.removalFails = true;
        assertThrows(IllegalStateException.class, f::run);
        assertEquals(List.of("cancel:old0", "remove:old0"), f.events); assertEquals(64, f.receipts.size());
    }
    @Test public void exactRetryAtCapacityDoesNotEvictAnotherNotice() throws Exception {
        Fake f = new Fake(); f.fill(); f.effects.add("old20"); f.run();
        assertEquals(List.of("admit:old20", "post:old20", "done:old20"), f.events);
    }
    @Test public void disabledOptionalNoticesDoNotAdmitOrPost() throws Exception {
        Fake f = new Fake(); f.effects.add("new"); f.readiness = InternalTaskEffectDrain.Notice.SKIP; f.run();
        assertEquals(List.of("done:new"), f.events); assertTrue(f.receipts.isEmpty());
    }
    @Test public void unavailableNoticeServiceRetainsSqlPending() throws Exception {
        Fake f = new Fake(); f.effects.add("new"); f.readiness = InternalTaskEffectDrain.Notice.RETRY; f.run();
        assertTrue(f.events.isEmpty());
    }
    @Test public void assessmentAcknowledgesBeforeSqlDoneAndFailureLeavesPending() throws Exception {
        Fake f = new Fake(); f.effects.add("failure1"); f.assessmentFails = true;
        assertThrows(IllegalStateException.class, f::run); assertEquals(List.of("assess:failure1"), f.events);
        f.events.clear(); f.assessmentFails = false; f.run(); assertEquals(List.of("assess:failure1", "done:failure1"), f.events);
    }
    @Test public void revokedGenerationDoesNothing() {
        Fake f = new Fake(); f.effects.add("new"); f.current = false;
        assertThrows(SecurityException.class, f::run); assertTrue(f.events.isEmpty());
    }
    @Test public void cancellationCleansRecordedNoticeBeforeNewEffects() throws Exception {
        Fake f = new Fake(); f.receipts.add(new TaskNoticeLedger.Receipt(GENERATION, f.audience(""), "old", 1));
        f.obsolete = true; f.effects.add("new"); f.run();
        assertEquals(List.of("cancel:old", "remove:old", "admit:new", "post:new", "done:new"), f.events);
    }
    @Test public void hiddenBoundaryDiscardDoesNotAssessOrNotify() throws Exception {
        Fake f = new Fake(); f.effects.addAll(List.of("failure1", "notice")); f.discard = true; f.run();
        assertEquals(List.of("done:failure1", "done:notice"), f.events);
    }
    @Test public void passIsBounded() throws Exception {
        Fake f = new Fake(); f.discard = true; for (int i = 0; i < 80; i++) f.effects.add("n" + i); f.run();
        assertEquals(64, f.events.size());
    }
    @Test public void revocationAfterReceiptPreventsPostAndRetainsExactCleanupEvidence() {
        Fake f = new Fake(); f.effects.add("new"); f.revokeAtAdmit = true;
        assertThrows(SecurityException.class, f::run); assertEquals(List.of("admit:new"), f.events);
        assertEquals(1, f.receipts.size());
    }
    @Test public void revocationAfterPostPreventsSqlCompletionAndRetainsReceipt() {
        Fake f = new Fake(); f.effects.add("new"); f.revokeAtPost = true;
        assertThrows(SecurityException.class, f::run); assertEquals(List.of("admit:new", "post:new"), f.events);
        assertEquals(1, f.receipts.size());
    }
    @Test public void noticeKeyRecoversSeriesAndOccurrenceCancellationWithoutOtherOwners() {
        String key = InternalTaskNoticeKey.create("task_1", java.time.LocalDate.of(2026, 11, 11), false, "sub_started", "task_1:2026-11-11");
        InternalTaskNoticeKey parsed = InternalTaskNoticeKey.parse(key);
        assertEquals("task_1", parsed.task); assertEquals("2026-11-11", parsed.date.toString()); assertFalse(parsed.cancellation);
        assertTrue(InternalTaskNoticeKey.parse(InternalTaskNoticeKey.create("task_1", null, true, "notice", "id")).cancellation);
        assertThrows(RuntimeException.class, () -> InternalTaskNoticeKey.parse("tasks:physical:other"));
    }
    @Test public void retryingReviewBacklogNeverStarvesLaterAssessment() throws Exception {
        Fake f=new Fake(); f.removeDone=true;
        for(int i=0;i<64;i++){f.effects.add("review"+i);f.retry.add("review"+i);}
        f.effects.add("failure-later"); f.run();
        assertEquals(List.of("assess:failure-later","done:failure-later"),f.events);
        assertEquals(64,f.effects.size()); assertEquals(63,f.attempted); assertTrue(f.receipts.isEmpty());
        for(int i=0;i<100;i++)f.run(); assertEquals(2,f.events.size()); assertEquals(64,f.effects.size());
    }
    @Test public void retryingNoticeLaneRotatesAndRetainsEveryDeferredEffect() throws Exception {
        Fake f=new Fake();f.removeDone=true;
        for(int i=0;i<130;i++){f.effects.add("retry"+i);f.retry.add("retry"+i);}
        f.effects.add("ready-later");
        for(int i=0;i<3;i++){int before=f.attempted;f.run();assertTrue(f.attempted-before<=64);}
        assertTrue(f.events.contains("done:ready-later"));assertEquals(130,f.effects.size());
        f.retry.clear();for(int i=0;i<3;i++)f.run();assertTrue(f.effects.isEmpty());
        assertEquals(131,f.events.stream().filter(e->e.startsWith("done:")).count());
    }
    @Test public void optionalPrepareAndPostExceptionsDoNotBlockAssessmentOrOtherNotices() {
        Fake f=new Fake();f.effects.addAll(List.of("prepare-broken","post-broken","ready","failure-later"));
        f.prepareFails.add("prepare-broken");f.postFails.add("post-broken");
        assertThrows(IllegalStateException.class,f::run);
        assertEquals("assess:failure-later",f.events.get(0));assertEquals("done:failure-later",f.events.get(1));
        assertTrue(f.events.contains("done:ready"));assertFalse(f.events.contains("done:post-broken"));
        assertFalse(f.events.contains("done:prepare-broken"));assertEquals(2,f.receipts.size());
    }
    @Test public void obsoleteReceiptCancellationFailureCannotBlockFailureAccounting() {
        Fake f=new Fake();f.fill();f.obsolete=true;f.cancelFails=true;f.effects.add("failure-later");
        assertThrows(IllegalStateException.class,f::run);
        assertEquals(List.of("assess:failure-later","done:failure-later"),f.events.subList(0,2));
        assertEquals(64,f.receipts.size());assertFalse(f.events.stream().anyMatch(e->e.startsWith("remove:")));
    }
    @Test public void bothLanesReceiveBoundedCapacityUnderContinuousBacklogs() throws Exception {
        Fake f=new Fake();f.removeDone=true;
        for(int i=0;i<100;i++){f.effects.add("failure"+i);f.effects.add("notice"+i);}
        f.run();assertEquals(32,f.events.stream().filter(e->e.startsWith("assess:")).count());
        assertEquals(32,f.attempted);assertEquals(64,f.events.stream().filter(e->e.startsWith("done:")).count());
    }
    @Test public void failedAssessmentRetainsPendingReceiptButOtherWorkCanProgress() {
        Fake f=new Fake();f.effects.addAll(List.of("failure-broken","ready"));f.assessmentFails=true;
        assertThrows(IllegalStateException.class,f::run);
        assertFalse(f.events.contains("done:failure-broken"));assertTrue(f.events.contains("done:ready"));
    }

    @Test public void optionalPageReadFailureDoesNotPrecedeIndependentAccounting() {
        Fake f=new Fake();f.effects.add("failure-later");f.noticePageFails=true;
        assertThrows(IllegalStateException.class,f::run);
        assertEquals(List.of("assess:failure-later","done:failure-later"),f.events);
    }

}
