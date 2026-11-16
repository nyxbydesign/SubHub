package com.subhub.app.atmosphere;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;

/** Selects replacements without retaining any typed text. */
final class GagRules {
    enum Mode { ALL, INTERVAL, MATCH }

    private final Mode mode;
    private final int interval;
    private final Set<String> matches;
    private final List<String> replacements;
    private final Map<String, String> pairs;
    private int count;

    GagRules(Mode mode, int interval, Set<String> matches, List<String> replacements) {
        this(mode, interval, matches, replacements, Map.of());
    }

    GagRules(Mode mode, int interval, Set<String> matches, List<String> replacements, Map<String, String> pairs) {
        this.mode = mode;
        this.interval = Math.max(1, interval);
        this.matches = Set.copyOf(matches);
        this.replacements = List.copyOf(replacements);
        this.pairs = Map.copyOf(pairs);
    }

    String complete(String word, IntUnaryOperator choose) {
        return complete(word, word, choose);
    }

    String complete(String word, String accepted, IntUnaryOperator choose) {
        if (word.isEmpty()) return accepted;
        int end = word.length();
        while (end > 0 && apostrophe(word.charAt(end - 1))) end--;
        if (end == 0) return accepted;
        String token = word.substring(0, end);
        if (mode == Mode.MATCH) {
            String key = token.toLowerCase(Locale.ROOT);
            String paired = pairs.get(key);
            if (paired != null) return paired + word.substring(end);
            if (!matches.contains(key)) return accepted;
        }
        if (replacements.isEmpty()) return accepted;
        boolean selected = switch (mode) {
            case ALL, MATCH -> true;
            case INTERVAL -> {
                count = (count + 1) % interval;
                yield count == 0;
            }
        };
        return selected ? replacements.get(choose.applyAsInt(replacements.size())) + word.substring(end) : accepted;
    }

    static boolean apostrophe(int codePoint) { return codePoint == '\'' || codePoint == '’'; }

    static boolean wordCharacter(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isLetter(codePoint) || type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK;
    }
}
