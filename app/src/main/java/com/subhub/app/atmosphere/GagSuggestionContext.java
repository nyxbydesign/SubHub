package com.subhub.app.atmosphere;

import android.os.Build;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.SurroundingText;
import java.util.function.UnaryOperator;

/** Revalidates the cursor before offering or inserting a suggestion outside a composing word. */
record GagSuggestionContext(String before, int cursor) {
    static GagSuggestionContext read(InputConnection input) {
        if (input == null) return null;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                SurroundingText text = input.getSurroundingText(GagNextWords.CONTEXT_LIMIT, 1, 0);
                if (text != null) {
                    int end = text.getSelectionEnd();
                    CharSequence buffer = text.getText();
                    if (end < 0 || text.getSelectionStart() != end || end != buffer.length()) return null;
                    int offset = text.getOffset();
                    if (offset > Integer.MAX_VALUE - end) return null;
                    return new GagSuggestionContext(boundedTail(buffer), offset < 0 ? -1 : offset + end);
                }
            }
            CharSequence selected = input.getSelectedText(0);
            if (selected != null && selected.length() != 0) return null;
            CharSequence before = input.getTextBeforeCursor(GagNextWords.CONTEXT_LIMIT, 0);
            CharSequence after = input.getTextAfterCursor(1, 0);
            return before == null || after == null || after.length() != 0
                    ? null : new GagSuggestionContext(boundedTail(before), -1);
        } catch (RuntimeException unavailable) {
            // Context is optional for editors that do not support surrounding-text reads.
            return null;
        }
    }

    private static String boundedTail(CharSequence text) {
        return text.subSequence(Math.max(0, text.length() - GagNextWords.CONTEXT_LIMIT), text.length()).toString();
    }

    boolean commit(InputConnection input, String candidate, UnaryOperator<String> replace) {
        if (!equals(read(input))) return false;
        StringBuilder completed = new StringBuilder();
        for (String word : candidate.split(" ")) completed.append(replace.apply(word)).append(' ');
        return input.commitText(completed.toString(), 1);
    }
}
