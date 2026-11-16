package com.subhub.app.atmosphere;

import android.view.inputmethod.InputConnection;

/** Remembers where a suggestion can be followed by a separator. */
record GagSuggestionSpace(InputConnection input, GagDeleteSuggestion.Snapshot position, int previousCursor) {
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
        if (before == null || originalLength < 0 || before.cursor() < originalLength || inserted.isEmpty()
                || !needsSpace(before.right())) return null;
        int start = before.cursor() - originalLength;
        if (start > Integer.MAX_VALUE - inserted.length()) return null;
        String knownLeft = before.left().substring(0, Math.max(0, before.left().length() - originalLength))
                + inserted;
        String suffix = knownLeft.substring(Math.max(0, knownLeft.length() - GagDeleteSuggestion.BEFORE));
        GagSuggestionSpace pending = new GagSuggestionSpace(input,
                new GagDeleteSuggestion.Snapshot(start + inserted.length(), suffix, before.right()),
                before.cursor());
        GagDeleteSuggestion.Snapshot after = GagDeleteSuggestion.editorSnapshot(input);
        // Some editors acknowledge the commit before their contextual reads catch up.
        return after == null || before.equals(after) || pending.isExpected(after) ? pending : null;
    }

    boolean cursorMoved(InputConnection current, int oldStart, int oldEnd, int newStart, int newEnd) {
        if (current != input || newStart != newEnd) return true;
        if (newEnd == position.cursor()) return false;
        // A queued report for the old caret may precede the commit's selection update.
        return newEnd != previousCursor || (oldStart == position.cursor() && oldEnd == position.cursor());
    }

    boolean matches(InputConnection current) {
        return current == input && isExpected(GagDeleteSuggestion.editorSnapshot(current));
    }

    private boolean isExpected(GagDeleteSuggestion.Snapshot current) {
        return current != null && current.cursor() == position.cursor()
                && current.right().equals(position.right())
                && current.left().endsWith(position.left());
    }
}
