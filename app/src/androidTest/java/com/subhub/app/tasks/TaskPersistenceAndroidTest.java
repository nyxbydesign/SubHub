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
