package com.subhub.app.atmosphere;

import static com.subhub.app.atmosphere.GagDeletionHistory.Action.REDO;
import static com.subhub.app.atmosphere.GagDeletionHistory.Action.UNDO;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class GagDeletionHistoryTest {
    @Test public void requiresTwoWholeWordsAndRetainsExactUnicodeAndSpaces() {
        GagDeletionHistory history = new GagDeletionHistory();
        var before = cursor("hello café  über🙂", " tail");
        history.record(before, cursor("hello caf", " tail"));
        assertFalse(history.hasEntries());

        var after = cursor("hello ", " tail");
        history.record(before, after);
        var edit = history.edit(UNDO, after);
        assertNotNull(edit);
        assertEquals("café  über🙂", edit.deleted());
        assertNull(history.edit(UNDO, cursor("hellx ", " tail")));
        assertNull(history.edit(UNDO, cursor("hello ", " changed")));
        assertTrue(history.confirm(UNDO, edit, before));
        assertNull(history.edit(UNDO, before));
        assertEquals(edit, history.edit(REDO, before));
        assertTrue(history.confirm(REDO, edit, after));
        assertEquals(edit, history.edit(UNDO, after));
    }

    @Test public void capturesSelectionAndInvalidatesInexactOrOversizedChanges() {
        GagDeletionHistory history = new GagDeletionHistory();
        var before = new GagDeletionHistory.Snapshot(5, 14, "keep ", "über café", " tail");
        var after = cursor("keep ", " tail");
        history.record(before, after);
        var edit = history.edit(UNDO, after);
        assertNotNull(edit);
        assertEquals("über café", edit.deleted());
        assertNull(history.edit(UNDO, new GagDeletionHistory.Snapshot(0, 5, "", "keep ", " tail")));
        assertFalse(history.confirm(UNDO, edit, cursor("keep über café", " tail")));
        assertFalse(history.hasEntries());

        history.record(cursor("leave " + "word ".repeat(54), ""), cursor("leave ", ""));
        assertFalse(history.hasEntries());
    }

    @Test public void retainsAtMostThreeCompatibleBurstsAndInvalidatesRedoOnNewDeletion() {
        GagDeletionHistory history = new GagDeletionHistory();
        String text = "a b ".repeat(4);
        var before = cursor(text, "");
        for (int index = 0; index < 4; index++) {
            var after = cursor(text.substring(0, text.length() - (index + 1) * 4), "");
            history.record(before, after);
            before = after;
        }
        var current = cursor("", "");
        for (int index = 0; index < 3; index++) {
            var edit = history.edit(UNDO, current);
            assertNotNull(edit);
            current = edit.before();
            assertTrue(history.confirm(UNDO, edit, current));
        }
        assertNull(history.edit(UNDO, current));
        assertNotNull(history.edit(REDO, current));
        var afterNewDeletion = cursor("a b ", "");
        history.record(current, afterNewDeletion);
        assertNull(history.edit(REDO, afterNewDeletion));
        assertNotNull(history.edit(UNDO, afterNewDeletion));
    }

    private static GagDeletionHistory.Snapshot cursor(String left, String right) {
        return new GagDeletionHistory.Snapshot(left.length(), left.length(), left, "", right);
    }
}
