package com.subhub.app.atmosphere;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Small offline EN/DE phrase table for the current cursor. No editor history is collected. */
final class GagNextWords {
    static final int CONTEXT_LIMIT = 48;
    private static final List<String> ENGLISH_STARTERS = List.of("I", "You", "Ich");
    private static final List<String> GERMAN_STARTERS = List.of("Ich", "Du", "I");
    private static final Map<String, List<String>> ENGLISH = Map.ofEntries(
            Map.entry("i", List.of("am", "have", "can")),
            Map.entry("i am", List.of("here", "ready", "sorry")),
            Map.entry("i will", List.of("be", "have", "try")),
            Map.entry("you", List.of("can", "are", "have")),
            Map.entry("thank", List.of("you")),
            Map.entry("thank you", List.of("for", "so", "very")),
            Map.entry("thanks", List.of("for", "again")),
            Map.entry("how", List.of("are", "do", "much")),
            Map.entry("how are", List.of("you")),
            Map.entry("see", List.of("you", "if", "the")),
            Map.entry("see you", List.of("soon", "later", "tomorrow")),
            Map.entry("good", List.of("morning", "night", "luck")),
            Map.entry("let", List.of("me", "us")),
            Map.entry("let me", List.of("know", "see", "try")));
    private static final Map<String, List<String>> GERMAN = Map.ofEntries(
            Map.entry("ich", List.of("bin", "habe", "kann")),
            Map.entry("ich bin", List.of("da", "bereit", "hier")),
            Map.entry("ich habe", List.of("Zeit", "eine", "keine")),
            Map.entry("du", List.of("bist", "hast", "kannst")),
            Map.entry("wie", List.of("geht", "lange", "viel")),
            Map.entry("wie geht", List.of("es")),
            Map.entry("mir", List.of("geht", "ist", "fehlt")),
            Map.entry("mir geht", List.of("es")),
            Map.entry("danke", List.of("dir", "für", "schön")),
            Map.entry("danke für", List.of("die", "deine", "das")),
            Map.entry("guten", List.of("Morgen", "Tag", "Abend")),
            Map.entry("gute", List.of("Nacht", "Reise", "Besserung")),
            Map.entry("bis", List.of("bald", "später", "morgen")),
            Map.entry("wir", List.of("haben", "sind", "können")));

    private GagNextWords() {}

    static List<String> starters(String language) {
        return "de".equals(language) ? GERMAN_STARTERS : ENGLISH_STARTERS;
    }

    static List<String> after(CharSequence before, String language) {
        int length = before.length();
        int end = skipSpaces(before, length);
        if (end > 0 && "\n\r".indexOf(before.charAt(end - 1)) >= 0) return starters(language);
        if (end == length) return List.of();
        if (end == 0) return length < CONTEXT_LIMIT ? starters(language) : List.of();
        if (".?!".indexOf(before.charAt(end - 1)) >= 0) return starters(language);
        int start = wordStart(before, end);
        if (start == end || !atWordBoundary(before, start)) return List.of();
        String last = before.subSequence(start, end).toString().toLowerCase(Locale.ROOT);
        Map<String, List<String>> preferred = "de".equals(language) ? GERMAN : ENGLISH;
        Map<String, List<String>> other = preferred == GERMAN ? ENGLISH : GERMAN;
        int previousEnd = skipSpaces(before, start);
        int previousStart = wordStart(before, previousEnd);
        if (previousEnd != start && previousStart != previousEnd && atWordBoundary(before, previousStart)) {
            String pair = before.subSequence(previousStart, previousEnd).toString().toLowerCase(Locale.ROOT)
                    + " " + last;
            List<String> pairChoices = preferred.getOrDefault(pair, other.get(pair));
            if (pairChoices != null) return pairChoices;
        }
        return preferred.getOrDefault(last, other.getOrDefault(last, List.of()));
    }

    private static int skipSpaces(CharSequence text, int end) {
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))
                && text.charAt(end - 1) != '\n' && text.charAt(end - 1) != '\r') end--;
        return end;
    }

    private static int wordStart(CharSequence text, int end) {
        while (end > 0 && GagRules.wordCharacter(text.charAt(end - 1))) end--;
        return end;
    }

    private static boolean atWordBoundary(CharSequence text, int start) {
        if (start == 0) return text.length() < CONTEXT_LIMIT;
        char previous = text.charAt(start - 1);
        return !Character.isLetterOrDigit(previous) && !GagRules.apostrophe(previous) && previous != '_';
    }
}
