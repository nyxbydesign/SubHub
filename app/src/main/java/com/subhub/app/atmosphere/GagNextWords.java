package com.subhub.app.atmosphere;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Small offline EN/DE phrase table for the current cursor. No editor history is collected. */
final class GagNextWords {
    static final int CONTEXT_LIMIT = 48;
    private static final List<String> ENGLISH_STARTERS = List.of("I", "You", "Ich");
    private static final List<String> GERMAN_STARTERS = List.of("Ich", "Du", "I");
    private static final List<String> ENGLISH_CONTINUATIONS = List.of("and", "but", "or", "so");
    private static final List<String> GERMAN_CONTINUATIONS = List.of("und", "aber", "oder", "also");
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
    private static final Map<String, List<String>> ENGLISH_PREDICTIONS = Map.ofEntries(
            Map.entry("hello", List.of("there", "everyone", "again")),
            Map.entry("hi", List.of("there", "everyone", "again")),
            Map.entry("hey", List.of("there", "you", "everyone")),
            Map.entry("hello there", List.of("how", "I", "again")),
            Map.entry("we", List.of("can", "are", "should")),
            Map.entry("can", List.of("you", "I", "we")),
            Map.entry("could", List.of("you", "I", "we")),
            Map.entry("you can", List.of("do", "try", "get")),
            Map.entry("want", List.of("to", "a", "the")),
            Map.entry("want to", List.of("go", "see", "know")),
            Map.entry("need", List.of("to", "a", "the")),
            Map.entry("think", List.of("that", "about", "so")),
            Map.entry("know", List.of("that", "how", "what")),
            Map.entry("like", List.of("to", "it", "that")),
            Map.entry("so like", List.of("what", "I", "that")),
            Map.entry("love", List.of("you", "it", "this")),
            Map.entry("have", List.of("a", "to", "been")),
            Map.entry("are", List.of("you", "we", "they")),
            Map.entry("nice", List.of("to", "work", "one")),
            Map.entry("nice to", List.of("meet", "see", "hear")),
            Map.entry("very", List.of("good", "much", "nice")),
            Map.entry("to", List.of("be", "do", "get")),
            Map.entry("the", List.of("same", "best", "first")),
            Map.entry("not", List.of("sure", "really", "now")),
            Map.entry("please", List.of("let", "send", "tell")),
            Map.entry("please let", List.of("me", "us", "them")),
            Map.entry("yes", List.of("please", "absolutely", "of")),
            Map.entry("yeah", List.of("I", "that", "so")),
            Map.entry("just", List.of("wanted", "need", "got")),
            Map.entry("it", List.of("is", "was", "will")),
            Map.entry("can't", List.of("wait", "believe", "find")),
            Map.entry("and", List.of("I", "then", "you")),
            Map.entry("but", List.of("I", "it", "you")),
            Map.entry("or", List.of("maybe", "you", "I")),
            Map.entry("so", List.of("I", "what", "we")));
    private static final Map<String, List<String>> GERMAN_PREDICTIONS = Map.ofEntries(
            Map.entry("hallo", List.of("zusammen", "du", "und")),
            Map.entry("bin", List.of("da", "hier", "bereit")),
            Map.entry("bist", List.of("du", "da", "bereit")),
            Map.entry("kann", List.of("ich", "dir", "das")),
            Map.entry("kannst", List.of("du", "mir", "das")),
            Map.entry("ich kann", List.of("dir", "das", "leider")),
            Map.entry("habe", List.of("ich", "Zeit", "eine")),
            Map.entry("möchte", List.of("ich", "gerne", "noch")),
            Map.entry("vielen", List.of("Dank", "Grüßen", "Leuten")),
            Map.entry("vielen dank", List.of("für", "und", "nochmal")),
            Map.entry("es", List.of("ist", "geht", "war")),
            Map.entry("nicht", List.of("mehr", "so", "ganz")),
            Map.entry("sehr", List.of("gut", "gerne", "schön")),
            Map.entry("gerne", List.of("wieder", "noch", "auch")),
            Map.entry("geht", List.of("es", "das", "nicht")),
            Map.entry("und", List.of("ich", "du", "dann")),
            Map.entry("aber", List.of("ich", "das", "jetzt")),
            Map.entry("oder", List.of("auch", "doch", "lieber")),
            Map.entry("was", List.of("ist", "machst", "soll")));

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

    /** Extends curated transitions for the strip without changing phrase-repair scores. */
    static List<String> predict(CharSequence before, String language) {
        int length = before.length();
        int end = skipSpaces(before, length);
        if (end == 0) return length < CONTEXT_LIMIT ? starters(language) : List.of();
        if ("\n\r".indexOf(before.charAt(end - 1)) >= 0) return starters(language);
        if (end == length) return List.of();
        char previous = before.charAt(end - 1);
        if (".?!".indexOf(previous) >= 0) return starters(language);
        int wordEnd = ",;:".indexOf(previous) >= 0 ? end - 1 : end;
        int start = predictionWordStart(before, wordEnd);
        if (start == wordEnd || !atWordBoundary(before, start)) return List.of();
        String last = predictionWord(before, start, wordEnd);
        int priorEnd = skipSpaces(before, start);
        int priorStart = predictionWordStart(before, priorEnd);
        String prior = priorEnd < start && priorStart < priorEnd && atWordBoundary(before, priorStart)
                ? predictionWord(before, priorStart, priorEnd) : "";

        String pair = prior.isEmpty() ? "" : prior + " " + last;
        boolean german = "de".equals(language);
        int englishEvidence = languageEvidence(ENGLISH, ENGLISH_PREDICTIONS, pair, last, prior);
        int germanEvidence = languageEvidence(GERMAN, GERMAN_PREDICTIONS, pair, last, prior);
        if (englishEvidence != germanEvidence) german = germanEvidence > englishEvidence;
        List<String> backoff = german ? GERMAN_CONTINUATIONS : ENGLISH_CONTINUATIONS;
        List<String> known = new ArrayList<>(3);
        if (wordEnd == end) {
            Map<String, List<String>> original = german ? GERMAN : ENGLISH;
            Map<String, List<String>> extra = german ? GERMAN_PREDICTIONS : ENGLISH_PREDICTIONS;
            appendDistinct(known, original.getOrDefault(pair, List.of()));
            appendDistinct(known, extra.getOrDefault(pair, List.of()));
            appendDistinct(known, original.getOrDefault(last, List.of()));
            appendDistinct(known, extra.getOrDefault(last, List.of()));
        }
        appendDistinct(known, backoff);
        return List.copyOf(known);
    }

    private static int languageEvidence(Map<String, List<String>> original, Map<String, List<String>> extra,
            String pair, String last, String prior) {
        if (original.containsKey(pair) || extra.containsKey(pair)) return 3;
        if (original.containsKey(last) || extra.containsKey(last)) return 2;
        return original.containsKey(prior) || extra.containsKey(prior) ? 1 : 0;
    }

    private static void appendDistinct(List<String> output, List<String> candidates) {
        for (String candidate : candidates) {
            if (output.size() == 3) break;
            if (output.stream().noneMatch(candidate::equalsIgnoreCase)) output.add(candidate);
        }
    }

    private static int predictionWordStart(CharSequence text, int end) {
        int start = end;
        while (start > 0) {
            int codePoint = Character.codePointBefore(text, start);
            if (!GagRules.wordCharacter(codePoint) && !GagRules.apostrophe(codePoint)) break;
            start -= Character.charCount(codePoint);
        }
        return start;
    }

    private static String predictionWord(CharSequence text, int start, int end) {
        return text.subSequence(start, end).toString().toLowerCase(Locale.ROOT).replace('’', '\'');
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
