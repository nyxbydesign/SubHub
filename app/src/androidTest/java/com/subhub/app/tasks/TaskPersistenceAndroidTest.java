package com.subhub.app.tasks;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.remote.RemoteProtocol;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.time.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TaskPersistenceAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private TaskStore store;
    private String dom, sub;
    private TaskDefinition task;
    private LocalDate date;
    private long start;
    @Before public void setup() {
        store = new TaskStore(context); dom = "test_dom_" + UUID.randomUUID(); sub = "test_sub_" + UUID.randomUUID();
        date = LocalDate.of(2026, 10, 28); start = date.atTime(9, 0).toInstant(ZoneOffset.UTC).toEpochMilli();
        task = new TaskDefinition(UUID.randomUUID().toString(), "Read", "Read chapter two", date.atTime(9, 0),
                ZoneId.of("UTC"), 3_600_000, EnumSet.allOf(DayOfWeek.class), true, true, null);
    }
    @After public void cleanup() {
        for (String table : new String[]{"projections", "journal", "receipts", "cursors", "effects"})
            store.getWritableDatabase().execSQL("DELETE FROM " + table + " WHERE scope IN (?,?)", new Object[]{dom, sub});
        store.close();
    }
    private TaskAction create() { return new TaskAction("create", task.id, TaskAction.Kind.CREATE, null, start - 1000, "", "", List.of(), task); }
    private TaskAction action(String id, TaskAction.Kind kind, String proof, String submission) {
        return new TaskAction(id, task.id, kind, date, start + 1000, proof, submission, List.of(), null);
    }
    private void definitionDelivered() throws Exception {
        assertTrue(store.append(dom, create(), true, false));
        assertEquals(1, store.receive(sub, store.outgoing(dom, 0), true, false));
    }
    @Test public void observersPublishCommittedDataAndIgnoreReplayRollbackAndClosedSubscriptions() throws Exception {
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        java.util.concurrent.atomic.AtomicInteger changes = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean committedVisible = new java.util.concurrent.atomic.AtomicBoolean(true);
        try (TaskStore.Observation observation = store.observe(() -> {
            try {
                committedVisible.compareAndSet(true, android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
                        && store.load(dom).definitions().size() == 1);
                changes.incrementAndGet();
            } catch (Exception invalid) { committedVisible.set(false); }
        })) {
            assertTrue(store.append(dom, create(), true, false));
            instrumentation.waitForIdleSync();
            assertEquals(1, changes.get());
            assertFalse(store.append(dom, create(), true, false));
            JSONArray valid = store.outgoing(dom, 0);
            JSONArray invalid = new JSONArray(valid.toString());
            invalid.put(new JSONObject().put("seq", 3).put("action", create().json()));
            try { store.receive(sub, invalid, true, false); fail("Sequence gap must roll back"); }
            catch (SecurityException expected) { }
            instrumentation.waitForIdleSync();
            assertEquals(1, changes.get());
            assertTrue(store.load(sub).definitions().isEmpty());
            assertEquals(0, store.received(sub));
            assertEquals(1, store.receive(sub, valid, true, false));
            instrumentation.waitForIdleSync();
            assertEquals(2, changes.get());
            assertEquals(1, store.receive(sub, valid, true, false));
            instrumentation.waitForIdleSync();
            assertEquals(2, changes.get());
            assertTrue(committedVisible.get());
        }
        assertTrue(store.append(sub, action("after-close", TaskAction.Kind.SUBMIT, "Synthetic proof", ""), false, false));
        instrumentation.waitForIdleSync();
        assertEquals(2, changes.get());
    }
    @Test public void commitBeforeLostReceiptAndExactReplayDoNotDuplicateCompletionNotification() throws Exception {
        definitionDelivered();
        TaskAction request = action("proof", TaskAction.Kind.SUBMIT, "Finished", "");
        assertTrue(store.append(sub, request, false, false));
        JSONArray first = store.outgoing(sub, 0);
        assertEquals(1, store.receive(dom, first, false, true));
        assertEquals(1, store.pending(dom).size());
        store.close(); store = new TaskStore(context);
        assertEquals(1, store.receive(dom, first, false, true));
        assertEquals(1, store.pending(dom).size());
        assertFalse(store.append(sub, request, false, false));
        assertEquals(0, store.outgoing(sub, store.received(dom)).length());
        assertEquals(TaskEngine.Status.PENDING, store.load(dom).occurrence(task.id, date, start + 10_000_000).status);
    }
    @Test public void refusedSeriesSurvivesDatabaseReopenAndCannotBecomeTimeoutFailure() throws Exception {
        definitionDelivered();
        store.append(sub, action("refuse", TaskAction.Kind.REFUSE_SERIES, "", ""), false, false);
        store.receive(dom, store.outgoing(sub, 0), false, true);
        store.close(); store = new TaskStore(context);
        assertTrue(store.load(sub).seriesRefused(task.id));
        store.append(sub, new TaskAction("late", task.id, TaskAction.Kind.TIMEOUT, date.plusDays(1),
                start + 2 * 86_400_000, "", "", List.of(), null), false, false);
        assertEquals(TaskEngine.Status.REFUSED, store.load(sub).occurrence(task.id, date.plusDays(1), start).status);
        assertTrue(store.pending(sub).isEmpty());
        assertEquals(TaskEngine.Status.REFUSED, store.load(dom).occurrence(task.id, date.plusDays(20), start).status);
    }
    @Test public void deniedProofCreatesOneOccurrenceFailureEvenAfterDeadlineAndReplay() throws Exception {
        definitionDelivered();
        store.append(sub, action("outside", TaskAction.Kind.OUTSIDE, "", ""), false, false);
        store.receive(dom, store.outgoing(sub, 0), false, true);
        store.append(dom, action("deny", TaskAction.Kind.DENY, "", "outside"), true, false);
        JSONArray decision = store.outgoing(dom, 1);
        store.receive(sub, decision, true, false); store.receive(sub, decision, true, false);
        store.append(sub, new TaskAction("deadline", task.id, TaskAction.Kind.TIMEOUT, date, start + 3_600_000,
                "", "", List.of(), null), false, false);
        assertEquals(1, store.pending(sub).size());
        assertEquals("failure", store.pending(sub).get(0).kind);
        assertEquals(TaskEngine.Status.DENIED, store.load(sub).occurrence(task.id, date, start).status);
    }
    @Test public void cancellationAtomicallyRetiresProofAndFailureEffectsAndKeepsOtherDates() throws Exception {
        definitionDelivered();
        store.append(sub, action("proof", TaskAction.Kind.OUTSIDE, "", ""), false, false);
        store.receive(dom, store.outgoing(sub, 0), false, true);
        store.append(dom, action("deny", TaskAction.Kind.DENY, "", "proof"), true, false);
        assertEquals(2, store.pending(dom).size());
        TaskAction cancel = action("cancel", TaskAction.Kind.CANCEL_ONCE, "", "");
        store.append(dom, cancel, true, false); assertTrue(store.pending(dom).isEmpty());
        JSONArray rows = store.outgoing(dom, 1); assertEquals(2, rows.length());
        store.receive(sub, rows, true, false); store.receive(sub, rows, true, false);
        assertTrue(store.pending(sub).isEmpty());
        store.close(); store = new TaskStore(context);
        for (String scope : List.of(dom, sub)) {
            assertEquals(TaskEngine.Status.CANCELLED, store.load(scope).occurrence(task.id, date, start).status);
            assertEquals(TaskEngine.Status.ACTIVE, store.load(scope).occurrence(task.id, date.plusDays(1), start + 86400000).status);
            assertFalse(store.load(scope).seriesCancelled(task.id)); assertTrue(store.pending(scope).isEmpty());
        }
        store.append(sub, new TaskAction("late-timeout", task.id, TaskAction.Kind.TIMEOUT, date,
                start + 3600000, "", "", List.of(), null), false, false);
        assertTrue(store.pending(sub).isEmpty());
    }

    @Test public void historyEntryAndRequestTimesSurviveReviewReplayAndDatabaseReopen() throws Exception {
        definitionDelivered();
        LocalDate next = date.plusDays(1);
        long nextStart = task.start(next).toEpochMilli();
        TaskAction first = action("first-proof", TaskAction.Kind.SUBMIT, "Synthetic proof", "");
        TaskAction second = new TaskAction("second-proof", task.id, TaskAction.Kind.OUTSIDE, next,
                nextStart + 1000, "", "", List.of(), null);
        store.append(sub, first, false, false); store.append(sub, second, false, false);
        store.receive(dom, store.outgoing(sub, 0), false, false);
        TaskAction approved = new TaskAction("approve-first", task.id, TaskAction.Kind.ACCEPT, date,
                nextStart + 3000, "", first.id, List.of(), null);
        TaskAction declined = new TaskAction("decline-second", task.id, TaskAction.Kind.DENY, next,
                nextStart + 2000, "", second.id, List.of(), null);
        store.append(dom, approved, true, false); store.append(dom, declined, true, false);
        JSONArray decisions = store.outgoing(dom, 1);
        store.receive(sub, decisions, true, false);
        store.receive(sub, decisions, true, false);
        store.append(sub, new TaskAction("ignored-timeout", task.id, TaskAction.Kind.TIMEOUT, date,
                nextStart + 4000, "", "", List.of(), null), false, false);
        store.close(); store = new TaskStore(context);
        for (String scope : List.of(dom, sub)) {
            TaskEngine engine = store.load(scope);
            List<TaskEngine.Occurrence> rows = engine.recorded(nextStart + 5000);
            rows.sort(TaskEngine.LIST_ORDER);
            assertEquals(2, rows.size());
            assertEquals(date, rows.get(0).date); // Earlier request/schedule, later History entry.
            assertEquals(approved.at, rows.get(0).historyEnteredAt);
            assertEquals(first.at, rows.get(0).submission.at);
            assertEquals(declined.at, rows.get(1).historyEnteredAt);
            assertEquals(second.at, rows.get(1).submission.at);
            assertEquals(TaskEngine.Status.ACCEPTED, rows.get(0).status);
            assertEquals(TaskEngine.Status.DENIED, rows.get(1).status);
        }
        TaskAction cancel = new TaskAction("cancel-series", task.id, TaskAction.Kind.CANCEL_SERIES, next,
                nextStart + 6000, "", "", List.of(), null);
        store.append(dom, cancel, true, false);
        store.receive(sub, store.outgoing(dom, 3), true, false);
        store.close(); store = new TaskStore(context);
        for (String scope : List.of(dom, sub)) {
            TaskEngine engine = store.load(scope);
            assertEquals(approved.at, engine.occurrence(task.id, date, nextStart + 7000).historyEnteredAt);
            assertEquals(declined.at, engine.occurrence(task.id, next, nextStart + 7000).historyEnteredAt);
            assertNull(engine.occurrence(task.id, date, nextStart + 7000).submission);
            assertNull(engine.occurrence(task.id, next, nextStart + 7000).submission);
        }
    }

    @Test public void conflictingReplayGapAndWrongWriterLeaveCursorAndProjectionUnchanged() throws Exception {
        definitionDelivered();
        TaskAction request = action("proof", TaskAction.Kind.SUBMIT, "Finished", "");
        store.append(sub, request, false, false); JSONArray rows = store.outgoing(sub, 0);
        store.receive(dom, rows, false, true);
        JSONArray changed = new JSONArray(rows.toString());
        changed.getJSONObject(0).getJSONObject("action").put("text", "Different bytes");
        assertThrows(SecurityException.class, () -> store.receive(dom, changed, false, true));
        JSONArray gap = new JSONArray(rows.toString()); gap.getJSONObject(0).put("seq", 3);
        assertThrows(SecurityException.class, () -> store.receive(dom, gap, false, true));
        JSONArray wrongWriter = new JSONArray().put(new JSONObject().put("seq", 2).put("action",
                action("accept", TaskAction.Kind.ACCEPT, "", "proof").json()));
        assertThrows(SecurityException.class, () -> store.receive(dom, wrongWriter, false, true));
        assertEquals(1, store.received(dom));
        assertEquals(TaskEngine.Status.PENDING, store.load(dom).occurrence(task.id, date, start).status);
        assertEquals(1, store.pending(dom).size());
    }
    @Test public void unofferedAcknowledgementCannotDeleteOrSkipOfflineActions() throws Exception {
        store.append(dom, create(), true, false);
        assertThrows(SecurityException.class, () -> store.outgoing(dom, 1));
        assertEquals(1, store.outgoing(dom, 0).length());
        assertThrows(SecurityException.class, () -> store.outgoing(dom, 2));
        assertEquals(1, store.outgoing(dom, 0).length());
    }
    @Test public void conflictingMediaPageRollsBackBeforeCursorAndCanBeRepaired() throws Exception {
        definitionDelivered();
        String hash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        TaskDefinition illustrated = new TaskDefinition("illustrated", "Read image", "", task.firstStart, task.zone,
                task.durationMillis, Set.of(), false, false, new TaskAttachment(hash, "image/png", 100));
        store.append(dom, new TaskAction("illustrated-create", illustrated.id, TaskAction.Kind.CREATE, null, start - 1000,
                "", "", List.of(), illustrated), true, false);
        TaskAction invalid = new TaskAction("invalid-media", task.id, TaskAction.Kind.SUBMIT, date, start + 1000,
                "", "", List.of(new TaskAttachment(hash, "image/png", 101)), null);
        JSONArray page = new JSONArray().put(new JSONObject().put("seq", 1).put("action", invalid.json()));
        assertThrows(SecurityException.class, () -> store.receive(dom, page, false, true));
        assertEquals(0, store.received(dom)); assertTrue(store.pending(dom).isEmpty());
        assertEquals(TaskEngine.Status.ACTIVE, store.load(dom).occurrence(task.id, date, start).status);
        TaskAction repaired = new TaskAction("valid-media", task.id, TaskAction.Kind.SUBMIT, date, start + 1000,
                "", "", List.of(new TaskAttachment(hash, "image/png", 100)), null);
        assertEquals(1, store.receive(dom, new JSONArray().put(new JSONObject().put("seq", 1).put("action", repaired.json())), false, true));
        assertEquals(TaskEngine.Status.PENDING, store.load(dom).occurrence(task.id, date, start).status);
        assertEquals(1, store.pending(dom).size());
    }
}
