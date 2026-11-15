package com.subhub.app.atmosphere;

import java.util.ArrayDeque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded, in-memory history of contiguous deletions with exact cursor and text witnesses. */
final class GagDeletionHistory {
    static final int MAX_DELETED = 256;
    static final int CONTEXT = 24;
    static final int BEFORE = MAX_DELETED + CONTEXT;
    private static final int DEPTH = 3;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}\\p{M}]*"
            + "(?:['’][\\p{L}\\p{N}][\\p{L}\\p{N}\\p{M}]*)*");

    enum Action { UNDO, REDO }
    record Snapshot(int start, int end, String left, String selected, String right) {}
    record Edit(Snapshot before, Snapshot after, String deleted) {
        Snapshot source(Action action) { return action == Action.UNDO ? after : before; }
        Snapshot destination(Action action) { return action == Action.UNDO ? before : after; }
    }

    private final ArrayDeque<Edit> undo = new ArrayDeque<>();
    private final ArrayDeque<Edit> redo = new ArrayDeque<>();

    boolean hasEntries() { return !undo.isEmpty() || !redo.isEmpty(); }

    void clear() {
        undo.clear();
        redo.clear();
    }

    void record(Snapshot before, Snapshot after) {
        if (before == null || after == null) {
            clear();
            return;
        }
        if (before.equals(after)) return;
        String deleted = contiguousDeletion(before, after);
        if (deleted == null || !removedAtLeastTwoWords(deleted, after.left(), after.right())) {
            clear();
            return;
        }
        if (hasEntries() && edit(Action.UNDO, before) == null && edit(Action.REDO, before) == null) clear();
        redo.clear();
        undo.addFirst(new Edit(before, after, deleted));
        if (undo.size() > DEPTH) undo.removeLast();
    }

    Edit edit(Action action, Snapshot current) {
        Edit latest = source(action).peekFirst();
        return latest != null && latest.source(action).equals(current) ? latest : null;
    }

    boolean confirm(Action action, Edit applied, Snapshot current) {
        ArrayDeque<Edit> source = source(action);
        if (source.peekFirst() != applied || !applied.destination(action).equals(current)) {
            clear();
            return false;
        }
        source.removeFirst();
        (action == Action.UNDO ? redo : undo).addFirst(applied);
        return true;
    }

    private ArrayDeque<Edit> source(Action action) { return action == Action.UNDO ? undo : redo; }

    private static String contiguousDeletion(Snapshot before, Snapshot after) {
        int removedBeforeSelection = before.start() - after.start();
        if (after.start() != after.end() || removedBeforeSelection < 0
                || removedBeforeSelection > before.left().length()
                || removedBeforeSelection + before.selected().length() > MAX_DELETED
                || !before.right().equals(after.right())) return null;
        int cutoff = before.left().length() - removedBeforeSelection;
        if (cutoff < Math.min(CONTEXT, after.start()) || !after.left().endsWith(before.left().substring(0, cutoff)))
            return null;
        return before.left().substring(cutoff) + before.selected();
    }

    private static boolean removedAtLeastTwoWords(String text, String left, String right) {
        Matcher words = WORD.matcher(text);
        int complete = 0;
        while (words.find()) {
            if (words.start() == 0 && !left.isEmpty() && joinsWord(left.codePointBefore(left.length()))) continue;
            if (words.end() == text.length() && !right.isEmpty() && joinsWord(right.codePointAt(0))) continue;
            if (++complete == 2) return true;
        }
        return false;
    }

    private static boolean joinsWord(int point) {
        return GagRules.wordCharacter(point) || GagRules.apostrophe(point) || Character.isDigit(point);
    }
}
