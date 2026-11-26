package com.subhub.app.atmosphere;

import android.os.Build;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.SurroundingText;

/** A short-lived witness for a forwarded Backspace when an editor sends no selection callback. */
final class GagDeleteSuggestion {
    static final int BEFORE = 8;
    private static final int AFTER = 2;
    private static final int DELETE_BEFORE = 32;

    private final InputConnection connection;
    private final Snapshot before;

    private GagDeleteSuggestion(InputConnection connection) {
        this.connection = connection;
        before = editorSnapshot(connection, DELETE_BEFORE);
    }

    static GagDeleteSuggestion capture(InputConnection connection) {
        return new GagDeleteSuggestion(connection);
    }

    boolean isFor(InputConnection current) { return current == connection; }

    int deletedCursor(InputConnection current) {
        if (current != connection || before == null || before.left().isEmpty()) return -1;
        Snapshot after = editorSnapshot(current, DELETE_BEFORE);
        if (after == null) return -1;
        // The editor may delete a whole grapheme, such as a heart with its variation selector.
        int removed = before.cursor() - after.cursor();
        if (removed <= 0 || removed > before.left().length()) return -1;
        int start = before.left().length() - removed;
        if (start == 0 && after.cursor() > 0) return -1;
        if (start > 0 && Character.isLowSurrogate(before.left().charAt(start))
                && Character.isHighSurrogate(before.left().charAt(start - 1))) return -1;
        return after.left().endsWith(before.left().substring(0, start))
                && after.right().equals(before.right()) ? after.cursor() : -1;
    }

    int selectionCursor(InputConnection current, int selectionStart, int selectionEnd, int composingStart) {
        if (current != connection || composingStart >= 0 || selectionStart < 0 || selectionStart != selectionEnd)
            return -1;
        return before == null || deletedCursor(current) == selectionEnd ? selectionEnd : -1;
    }

    static Snapshot editorSnapshot(InputConnection connection) {
        return editorSnapshot(connection, BEFORE);
    }

    private static Snapshot editorSnapshot(InputConnection connection, int before) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                SurroundingText text = connection.getSurroundingText(before, AFTER, 0);
                if (text != null && text.getOffset() >= 0) {
                    return snapshot(text.getText(), text.getOffset(), text.getSelectionStart(), text.getSelectionEnd(),
                            before);
                }
            }
            ExtractedTextRequest request = new ExtractedTextRequest();
            request.hintMaxChars = before + AFTER;
            ExtractedText text = connection.getExtractedText(request, 0);
            return text == null ? null : snapshot(text.text, text.startOffset, text.selectionStart, text.selectionEnd,
                    before);
        } catch (RuntimeException unavailable) {
            // Some editors do not support contextual reads. Forward the key anyway.
            return null;
        }
    }

    static GagDeletionHistory.Snapshot historySnapshot(InputConnection connection) {
        if (connection == null) return null;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                SurroundingText text = connection.getSurroundingText(GagDeletionHistory.BEFORE,
                        GagDeletionHistory.CONTEXT, 0);
                if (text != null && text.getOffset() >= 0)
                    return historySlice(text.getText(), text.getOffset(), text.getSelectionStart(), text.getSelectionEnd());
            }
            ExtractedTextRequest request = new ExtractedTextRequest();
            request.hintMaxChars = GagDeletionHistory.BEFORE + GagDeletionHistory.MAX_DELETED
                    + GagDeletionHistory.CONTEXT;
            ExtractedText text = connection.getExtractedText(request, 0);
            return text == null ? null : historySlice(text.text, text.startOffset, text.selectionStart, text.selectionEnd);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static GagDeletionHistory.Snapshot historySlice(CharSequence text, int offset, int start, int end) {
        if (text == null || offset < 0 || start < 0 || end < start || end > text.length()
                || offset > Integer.MAX_VALUE - end || end - start > GagDeletionHistory.MAX_DELETED) return null;
        int left = Math.max(0, start - GagDeletionHistory.BEFORE);
        int right = end + Math.min(GagDeletionHistory.CONTEXT, text.length() - end);
        return new GagDeletionHistory.Snapshot(offset + start, offset + end, text.subSequence(left, start).toString(),
                text.subSequence(start, end).toString(), text.subSequence(end, right).toString());
    }

    private static Snapshot snapshot(CharSequence text, int offset, int start, int end, int before) {
        if (text == null || offset < 0 || start < 0 || start != end || start > text.length()
                || offset > Integer.MAX_VALUE - start) return null;
        int left = Math.max(0, start - before);
        int right = start + Math.min(AFTER, text.length() - start);
        return new Snapshot(offset + start, text.subSequence(left, start).toString(),
                text.subSequence(start, right).toString());
    }

    record Snapshot(int cursor, String left, String right) {}
}
