package com.subhub.app.atmosphere;

import android.os.Build;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.SurroundingText;

/** A short-lived witness for a forwarded Backspace when an editor sends no selection callback. */
final class GagDeleteSuggestion {
    private static final int BEFORE = 8;
    private static final int AFTER = 2;

    private final InputConnection connection;
    private final Snapshot before;

    private GagDeleteSuggestion(InputConnection connection) {
        this.connection = connection;
        before = editorSnapshot(connection);
    }

    static GagDeleteSuggestion capture(InputConnection connection) {
        return new GagDeleteSuggestion(connection);
    }

    int deletedCursor(InputConnection current) {
        if (current != connection || before == null || before.left().isEmpty()) return -1;
        Snapshot after = editorSnapshot(current);
        if (after == null) return -1;
        int start = before.left().offsetByCodePoints(before.left().length(), -1);
        int removed = before.left().length() - start;
        return after.cursor() == before.cursor() - removed
                && after.left().endsWith(before.left().substring(0, start))
                && after.right().equals(before.right()) ? after.cursor() : -1;
    }

    int selectionCursor(InputConnection current, int selectionStart, int selectionEnd, int composingStart) {
        if (current != connection || composingStart >= 0 || selectionStart < 0 || selectionStart != selectionEnd)
            return -1;
        return before == null || deletedCursor(current) == selectionEnd ? selectionEnd : -1;
    }

    private static Snapshot editorSnapshot(InputConnection connection) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                SurroundingText text = connection.getSurroundingText(BEFORE, AFTER, 0);
                if (text != null && text.getOffset() >= 0) {
                    return snapshot(text.getText(), text.getOffset(), text.getSelectionStart(), text.getSelectionEnd());
                }
            }
            ExtractedTextRequest request = new ExtractedTextRequest();
            request.hintMaxChars = BEFORE + AFTER;
            ExtractedText text = connection.getExtractedText(request, 0);
            return text == null ? null : snapshot(text.text, text.startOffset, text.selectionStart, text.selectionEnd);
        } catch (RuntimeException unavailable) {
            // Some editors do not support contextual reads. Forward the key anyway.
            return null;
        }
    }

    private static Snapshot snapshot(CharSequence text, int offset, int start, int end) {
        if (text == null || offset < 0 || start < 0 || start != end || start > text.length()
                || offset > Integer.MAX_VALUE - start) return null;
        int left = Math.max(0, start - BEFORE);
        int right = start + Math.min(AFTER, text.length() - start);
        return new Snapshot(offset + start, text.subSequence(left, start).toString(),
                text.subSequence(start, right).toString());
    }

    private record Snapshot(int cursor, String left, String right) {}
}
