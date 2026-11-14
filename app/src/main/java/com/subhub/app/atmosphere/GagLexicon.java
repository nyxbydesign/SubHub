package com.subhub.app.atmosphere;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Immutable local word frequencies. Candidate generation uses only the active token. */
final class GagLexicon {
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzäöüß";
    private static final String[] NEIGHBORS = {
            "qwsz", "vghn", "xdfv", "serfcx", "wsdr", "drtgvc", "ftyhbv", "gyujnb",
            "ujko", "huikmn", "jiolm", "kop", "njk", "bhjm", "iklp", "ol",
            "wa", "edft", "awedxz", "rfgy", "yhji", "cfgb", "qase", "zsdc", "tghu", "asx"
    };
    private static final int MAX_LOOKUP_LENGTH = 24;
    private static final int MAX_SUGGESTIONS = 3;
    private static final int MAX_WORDS = 100_000;
    private static final int SUGGEST_MIN_COUNT = 40;
    private final Map<String, Integer> counts;
    private final String[] sorted;

    GagLexicon(Map<String, Integer> counts) {
        this.counts = Map.copyOf(counts);
        sorted = counts.keySet().toArray(new String[0]);
        Arrays.sort(sorted);
    }

    static GagLexicon read(InputStream source) throws IOException {
        Map<String, Integer> counts = new HashMap<>();
        try (BufferedReader input = new BufferedReader(new InputStreamReader(
                source, StandardCharsets.UTF_8))) {
            for (String line; (line = input.readLine()) != null; ) {
                int split = line.indexOf('\t');
                if (split < 2 || split > 32 || counts.size() >= MAX_WORDS)
                    throw new IOException("Invalid spelling dictionary");
                counts.put(line.substring(0, split), Integer.parseInt(line.substring(split + 1)));
            }
        }
        return new GagLexicon(counts);
    }

    static int cursorPrefixLimit() { return MAX_LOOKUP_LENGTH + 1; }

    static String cursorPrefix(CharSequence before, CharSequence after) {
        if (before == null || before.length() == 0 || after == null) return "";
        if (after.length() > 0) {
            int first = Character.codePointAt(after, 0);
            if (GagRules.wordCharacter(first) || GagRules.apostrophe(first)) return "";
        }
        int end = before.length();
        int start = end;
        while (start > 0) {
            int point = Character.codePointBefore(before, start);
            if (!GagRules.wordCharacter(point) && !GagRules.apostrophe(point)) break;
            start -= Character.charCount(point);
        }
        if (end - start > MAX_LOOKUP_LENGTH) return "";
        return before.subSequence(start, end).toString();
    }

    boolean isKnownWord(String original) {
        String word = lookupWord(original);
        return word != null && counts.containsKey(word);
    }

    List<String> suggestions(String original) {
        String word = lookupWord(original);
        if (word == null) return List.of();
        Map<String, Long> matches = typos(word);
        int start = Arrays.binarySearch(sorted, word);
        if (start < 0) start = -start - 1;
        String[] prefixes = new String[MAX_SUGGESTIONS];
        int[] frequencies = new int[MAX_SUGGESTIONS];
        for (int i = start; i < sorted.length; i++) {
            String candidate = sorted[i];
            if (!candidate.startsWith(word)) break;
            if (candidate.equals(word) || candidate.length() - word.length() > 12) continue;
            int count = counts.get(candidate);
            if (count < SUGGEST_MIN_COUNT || count <= frequencies[MAX_SUGGESTIONS - 1]) continue;
            for (int rank = 0; rank < MAX_SUGGESTIONS; rank++) {
                if (count <= frequencies[rank]) continue;
                System.arraycopy(prefixes, rank, prefixes, rank + 1, MAX_SUGGESTIONS - rank - 1);
                System.arraycopy(frequencies, rank, frequencies, rank + 1, MAX_SUGGESTIONS - rank - 1);
                prefixes[rank] = candidate;
                frequencies[rank] = count;
                break;
            }
        }
        for (int rank = 0; rank < MAX_SUGGESTIONS && prefixes[rank] != null; rank++)
            matches.putIfAbsent(prefixes[rank], (long) frequencies[rank]);
        List<String> ranked = ranked(matches);
        List<String> result = new ArrayList<>(MAX_SUGGESTIONS);
        for (String candidate : ranked.subList(0, Math.min(MAX_SUGGESTIONS, ranked.size())))
            result.add(withCase(original, candidate));
        return List.copyOf(result);
    }

    private static List<String> ranked(Map<String, Long> matches) {
        List<String> ranked = new ArrayList<>(matches.keySet());
        ranked.sort(Comparator.<String>comparingLong(matches::get).reversed().thenComparing(word -> word));
        return ranked;
    }

    private Map<String, Long> typos(String word) {
        Map<String, Long> result = new HashMap<>();
        if (word.length() < 3 || counts.containsKey(word)) return result;
        for (int i = 0; i <= word.length(); i++) {
            String head = word.substring(0, i);
            String tail = word.substring(i);
            for (int key = 0; key < ALPHABET.length(); key++)
                add(result, head + ALPHABET.charAt(key) + tail, 2);
            if (tail.isEmpty()) continue;
            char current = tail.charAt(0);
            String rest = tail.substring(1);
            add(result, head + rest, 2);
            if (tail.length() > 1 && current != tail.charAt(1))
                add(result, head + tail.charAt(1) + current + tail.substring(2), 8);
            String nearby = current >= 'a' && current <= 'z' ? NEIGHBORS[current - 'a'] : "";
            nearby += switch (current) {
                case 'a' -> "ä"; case 'o' -> "ö"; case 'u' -> "ü";
                case 'ä' -> "a"; case 'ö' -> "o"; case 'ü' -> "u";
                default -> "";
            };
            for (int key = 0; key < nearby.length(); key++)
                add(result, head + nearby.charAt(key) + rest, 3);
        }
        return result;
    }

    private void add(Map<String, Long> result, String word, int weight) {
        Integer count = counts.get(word);
        if (count != null && count >= SUGGEST_MIN_COUNT)
            result.merge(word, (long) count * weight, Math::max);
    }

    private static String lookupWord(String original) {
        if (original.isEmpty() || original.length() > MAX_LOOKUP_LENGTH) return null;
        String word = original.toLowerCase(Locale.ROOT);
        if (!original.equals(word) && (!Character.isUpperCase(original.charAt(0))
                || !original.substring(1).equals(word.substring(1)))) return null;
        for (int i = 0; i < word.length(); i++)
            if (ALPHABET.indexOf(word.charAt(i)) < 0) return null;
        return word;
    }

    private static String withCase(String original, String candidate) {
        return Character.isUpperCase(original.charAt(0))
                ? Character.toUpperCase(candidate.charAt(0)) + candidate.substring(1) : candidate;
    }
}
