package com.subhub.app.atmosphere;

import android.view.inputmethod.InputConnection;

/** Owns one automatically appended separator until the next key or cursor change. */
record GagSuggestionSpace(InputConnection input, GagDeleteSuggestion.Snapshot position) {
    static boolean needsSpace(CharSequence after) {
        if (after == null) return false;
        if (after.length() == 0) return true;
        int next = Character.codePointAt(after, 0);
        return !Character.isWhitespace(next) && !Character.isSpaceChar(next) && !joinsPrevious(next);
    }

    static boolean joinsPrevious(int key) {
        return switch (Character.getType(key)) {
            case Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION,
                    Character.START_PUNCTUATION, Character.END_PUNCTUATION,
                    Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                    Character.OTHER_PUNCTUATION -> true;
            default -> key == '<' || key == '>';
        };
    }

    static GagSuggestionSpace capture(InputConnection input, GagDeleteSuggestion.Snapshot before,
            int originalLength, String appended) {
        if (before == null || before.cursor() < originalLength || !appended.endsWith(" ")) return null;
        GagDeleteSuggestion.Snapshot after = GagDeleteSuggestion.editorSnapshot(input);
        int expected = before.cursor() - originalLength + appended.length();
        return after != null && after.cursor() == expected && after.left().endsWith(" ")
                && after.right().equals(before.right())
                && after.left().endsWith(appended.substring(Math.max(0, appended.length() - after.left().length())))
                ? new GagSuggestionSpace(input, after) : null;
    }

    boolean atSelection(InputConnection current, int start, int end) {
        return input == current && start == end && end == position.cursor();
    }

    void removeBefore(InputConnection current, int key) {
        if (current == input && joinsPrevious(key)
                && position.equals(GagDeleteSuggestion.editorSnapshot(current)))
            current.deleteSurroundingText(1, 0);
    }
}
