package com.subhub.app.atmosphere;

/** Adds a space only when the next literal key starts a word. */
final class GagPunctuation {
    private GagPunctuation() {}

    static boolean needsSpace(char pending, int next, CharSequence before, CharSequence after) {
        if ((pending != '.' && pending != ',') || !Character.isLetter(next)
                || before == null || before.length() < 2 || after == null) return false;
        if (before.charAt(before.length() - 1) != pending) return false;
        int prior = Character.codePointBefore(before, before.length() - 1);
        if (Character.isWhitespace(prior) || Character.isDigit(prior)) return false;
        if (after.length() == 0) return true;
        int following = Character.codePointAt(after, 0);
        return !GagRules.wordCharacter(following) && !GagRules.apostrophe(following)
                && !Character.isDigit(following) && following != '.' && following != ',';
    }
}
