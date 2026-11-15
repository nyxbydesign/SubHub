package com.subhub.app.atmosphere;

import android.view.inputmethod.InputConnection;

/** Remembers where a suggestion can be followed by a separator. */
record GagSuggestionSpace(InputConnection input, GagDeleteSuggestion.Snapshot position) {
    static boolean needsSpace(CharSequence after) {
        if (after == null) return false;
        if (after.length() == 0) return true;
        int next = Character.codePointAt(after, 0);
        return !Character.isWhitespace(next) && !Character.isSpaceChar(next) && !joinsPrevious(next);
    }

    private static boolean joinsPrevious(int key) {
        return switch (Character.getType(key)) {
            case Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION,
                    Character.START_PUNCTUATION, Character.END_PUNCTUATION,
                    Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                    Character.OTHER_PUNCTUATION -> true;
            default -> key == '<' || key == '>';
        };
    }

    static GagSuggestionSpace capture(InputConnection input, GagDeleteSuggestion.Snapshot before,
            int originalLength, String inserted) {
        if (before == null || before.cursor() < originalLength || inserted.isEmpty()
                || !needsSpace(before.right())) return null;
        GagDeleteSuggestion.Snapshot after = GagDeleteSuggestion.editorSnapshot(input);
        int expected = before.cursor() - originalLength + inserted.length();
        return after != null && after.cursor() == expected && !after.left().isEmpty()
                && after.right().equals(before.right())
                && after.left().endsWith(inserted.substring(Math.max(0, inserted.length() - after.left().length())))
                ? new GagSuggestionSpace(input, after) : null;
    }

    boolean atSelection(InputConnection current, int start, int end) {
        return input == current && start == end && end == position.cursor();
    }

    boolean matches(InputConnection current) {
        return current == input && position.equals(GagDeleteSuggestion.editorSnapshot(current));
    }
}
