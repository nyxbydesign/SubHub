package com.subhub.app.fullaccess;

import static org.junit.Assert.*;

import com.subhub.app.R;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class FullAccessSmsThreadsTest {
    @Test public void newestPerThreadIsSelectedAndNeverRepeatedAcrossPages() {
        var first = new FullAccessSmsSource.ConversationPage(null, 2);
        feedOrderedSms(first);
        assertEquals(List.of(9L, 2L), first.items.stream().map(FullAccessSmsSource.Conversation::thread).toList());
        assertTrue(first.more);
        assertEquals("100-10", first.last().suffix());

        var next = new FullAccessSmsSource.ConversationPage(
                FullAccessSmsSource.conversationCursor("list-" + first.last().suffix()), 2);
        feedOrderedSms(next);
        assertEquals(List.of(7L, 6L), next.items.stream().map(FullAccessSmsSource.Conversation::thread).toList());
        assertFalse(next.more);
    }

    private static void feedOrderedSms(FullAccessSmsSource.ConversationPage page) {
        long[][] rows = {{9, 110, 14}, {9, 109, 13}, {2, 100, 10}, {7, 100, 8},
                {9, 92, 7}, {2, 88, 6}, {6, 77, 5}};
        for (long[] row : rows) if (!page.offer(row[0], row[1], row[2])) break;
    }

    @Test public void threadCursorIsBoundToItsThreadAndUsesStableTimestampTies() {
        assertEquals(42, FullAccessSmsSource.threadId("sms-42"));
        assertEquals(new FullAccessSmsSource.Position(100, 9),
                FullAccessSmsSource.threadCursor(42, "sms-42-100-9"));
        assertNull(FullAccessSmsSource.threadCursor(42, ""));
        assertThrows(IllegalArgumentException.class, () -> FullAccessSmsSource.threadCursor(42, "sms-4-100-9"));
        assertThrows(IllegalArgumentException.class, () -> FullAccessSmsSource.threadCursor(42, "list-100-9"));
        assertThrows(IllegalArgumentException.class, () -> FullAccessSmsSource.threadId("sms-1 OR 1=1"));
        assertThrows(IllegalArgumentException.class, () -> FullAccessSmsSource.threadId("sms-01"));
        assertThrows(IllegalArgumentException.class, () -> FullAccessSmsSource.threadId("export-42"));
    }

    @Test public void exportIdentifierIsBoundToSelectedFileAndDemoScope() {
        byte[] older = "older synthetic transcript".getBytes(StandardCharsets.UTF_8);
        byte[] newer = "newer synthetic transcript".getBytes(StandardCharsets.UTF_8);
        String oldNode = FullAccessSmsExport.node("", older, false);
        String newNode = FullAccessSmsExport.node("", newer, false);
        assertEquals(oldNode, FullAccessSmsExport.node("", older, false));
        assertNotEquals(oldNode, newNode);
        assertNotEquals(oldNode, FullAccessSmsExport.node("", older, true));
        assertEquals(5, FullAccessSmsExport.exportEnd(oldNode, "", 5));
        assertEquals(3, FullAccessSmsExport.exportEnd(oldNode, oldNode + "-3", 5));
        assertThrows(IllegalArgumentException.class, () -> FullAccessSmsExport.exportEnd(newNode, oldNode + "-3", 5));
        assertThrows(IllegalArgumentException.class, () -> FullAccessSmsExport.exportEnd(oldNode, oldNode + "-6", 5));
    }

    @Test public void continuousThreadKeepsLoadedHistoryWhenNewMessagesOverlapItsHead() throws Exception {
        FullAccessViewer.Continuous pages = new FullAccessViewer.Continuous();
        pages.accept(page("older", "3", "2"), "", true);
        pages.accept(page("", "1", "0"), "older", true);
        pages.accept(page("changed-head", "4", "3"), "", true);
        assertEquals(List.of("4", "3", "2", "1", "0"), List.copyOf(pages.rows.keySet()));
        assertFalse(pages.canAdvance());
    }

    @Test public void continuousListRequiresRetryForTailFailureAfterAHeadRefresh() throws Exception {
        FullAccessViewer.Continuous pages = new FullAccessViewer.Continuous();
        pages.accept(page("tail", "first"), "", false);
        pages.fail("tail", R.string.full_access_unavailable);
        pages.accept(page("tail", "first"), "", false);
        assertFalse(pages.canAdvance());
        assertEquals("tail", pages.failedCursor);
        pages.accept(page("", "second"), "tail", false);
        assertEquals(List.of("first", "second"), List.copyOf(pages.rows.keySet()));
        assertEquals(0, pages.error);
    }

    @Test public void continuousListStopsCursorCyclesAndNonAdvancingRows() throws Exception {
        FullAccessViewer.Continuous cycle = new FullAccessViewer.Continuous();
        cycle.accept(page("a", "first"), "", false);
        cycle.accept(page("b", "second"), "a", false);
        cycle.accept(page("a", "third"), "b", false);
        assertFalse(cycle.canAdvance());
        assertTrue(cycle.repeated);

        FullAccessViewer.Continuous duplicate = new FullAccessViewer.Continuous();
        duplicate.accept(page("0", "first"), "", false);
        for (int step = 0; step < 5 && duplicate.canAdvance(); step++)
            duplicate.accept(page(Integer.toString(step + 1), "first"), Integer.toString(step), false);
        assertFalse(duplicate.canAdvance());
        assertTrue(duplicate.repeated);
    }

    private static JSONObject page(String next, String... ids) throws Exception {
        JSONArray rows = new JSONArray();
        for (String id : ids) rows.put(new JSONObject().put("id", id));
        return new JSONObject().put("items", rows).put("nextCursor", next);
    }
}
