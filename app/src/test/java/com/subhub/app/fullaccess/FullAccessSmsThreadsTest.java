package com.subhub.app.fullaccess;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
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
}
