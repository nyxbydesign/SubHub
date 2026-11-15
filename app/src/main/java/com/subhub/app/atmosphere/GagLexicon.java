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
    private static final int KNOWN_WORD_ALTERNATIVE_MIN_COUNT = 20_000;
    private static final int KNOWN_WORD_ALTERNATIVE_RATIO = 30;
    private static final int SEPARATED_PART_MIN_COUNT = 25_000;
    private static final int JOINED_PART_MIN_COUNT = 40_000;
    private static final int KNOWN_JOIN_RATIO = 160;
    private final Map<String, Integer> counts;
    private final String[] sorted;
    private final int highestCount;

    GagLexicon(Map<String, Integer> counts) {
        this.counts = Map.copyOf(counts);
        sorted = counts.keySet().toArray(new String[0]);
        Arrays.sort(sorted);
        int highest = 0;
        for (int count : counts.values()) highest = Math.max(highest, count);
        highestCount = highest;
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
        String word = lookupWord(java.text.Normalizer.normalize(original, java.text.Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT));
        return word != null && counts.containsKey(word);
    }

    List<String> suggestions(String original) {
        String word = lookupWord(original);
        if (word == null) return List.of();
        Map<String, Long> edits = typos(word);
        List<String> rankedEdits = counts.containsKey(word) ? ranked(edits) : List.of();
        List<String> result = new ArrayList<>(MAX_SUGGESTIONS);
        // A strong non-prefix alternative must survive the frequency ranking of completions.
        if (!rankedEdits.isEmpty()) result.add(withCase(original, rankedEdits.get(0)));
        Map<String, Long> matches = edits;
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
        for (String candidate : ranked) {
            if (result.size() == MAX_SUGGESTIONS) break;
            if (rankedEdits.isEmpty() || !candidate.equals(rankedEdits.get(0)))
                result.add(withCase(original, candidate));
        }
        return List.copyOf(result);
    }

    String phraseRepair(String original, String language) {
        if (original.length() < 4) return null;
        boolean uppercase = original.equals(original.toUpperCase(Locale.ROOT));
        String word = lookupWord(uppercase ? original.toLowerCase(Locale.ROOT) : original);
        if (word == null) return null;
        PhraseSearch search = new PhraseSearch(word, language);
        search.visit(0, 0, 0, 0, Integer.MAX_VALUE, 0, "");
        if (search.best == null) return null;
        return uppercase ? search.best.toUpperCase(Locale.ROOT) : withCase(original, search.best);
    }

    static List<String> prioritizePhrase(String token, boolean known, List<String> singles, String phrase) {
        boolean literalFirst = !singles.isEmpty() && token.equalsIgnoreCase(singles.get(0));
        if (literalFirst && !known) return singles; // A personal word should stay intact.
        List<String> choices = new ArrayList<>(singles);
        choices.add(literalFirst ? 1 : 0, phrase);
        return List.copyOf(choices.subList(0, Math.min(MAX_SUGGESTIONS, choices.size())));
    }

    private final class PhraseSearch {
        private final String token;
        private final String language;
        private final int literalCount;
        private final Map<String, String> typoParts = new HashMap<>();
        private String best;
        private double bestScore = Double.NEGATIVE_INFINITY;

        PhraseSearch(String token, String language) {
            this.token = token;
            this.language = language;
            literalCount = counts.getOrDefault(token, 0);
        }

        private void visit(int start, int words, int missing, int edits, int weakest,
                double frequency, String phrase) {
            if (words == 3) return;
            int last = token.length();
            for (int end = start + 1; end <= last; end++) {
                if (words == 0 && end == last) break;
                String part = token.substring(start, end);
                int count = partCount(part);
                int extraEdit = 0;
                boolean besideB = (start > 1 && token.charAt(start - 1) == 'b')
                        || (end < last - 1 && token.charAt(end) == 'b');
                if (count == 0 && edits == 0 && literalCount == 0 && besideB && part.length() >= 2) {
                    part = typoParts.computeIfAbsent(part, GagLexicon.this::phraseTypo);
                    count = partCount(part);
                    extraEdit = 1;
                }
                if (count < SEPARATED_PART_MIN_COUNT) continue;
                if (part.equals("i")) part = "I";
                String extended = phrase.isEmpty() ? part : phrase + " " + part;
                int nextWeakest = Math.min(weakest, count);
                int nextEdits = edits + extraEdit;
                double nextFrequency = frequency + Math.log(count);
                if (!phrase.isEmpty() && GagNextWords.after(phrase + " ", language).contains(part))
                    nextFrequency += 16;
                if (end == last) {
                    rank(extended, words + 1, missing, nextEdits, nextWeakest, nextFrequency);
                } else if (words < 2) {
                    if (token.charAt(end) == 'b' && end + 1 < last) {
                        visit(end + 1, words + 1, missing, nextEdits, nextWeakest, nextFrequency, extended);
                        if (nextEdits == 0 && end + 2 < last && token.charAt(end + 1) == 'b') {
                            visit(end + 2, words + 1, missing, 1, nextWeakest, nextFrequency, extended);
                        }
                    }
                    visit(end, words + 1, missing + 1, nextEdits, nextWeakest, nextFrequency, extended);
                }
            }
        }

        private void rank(String phrase, int words, int missing, int edits, int weakest, double frequency) {
            if (missing > 0 && weakest < JOINED_PART_MIN_COUNT) return;
            if (edits > 0 && missing == words - 1) return;
            if (literalCount > 0 && (edits > 0 || weakest < (long) literalCount * KNOWN_JOIN_RATIO)) return;
            double score = Math.log(weakest) + frequency / (4 * words)
                    - 2.75 * missing - 1.8 * edits - 0.45 * (words - 2);
            if (score > bestScore) {
                best = phrase;
                bestScore = score;
            }
        }
    }

    private int partCount(String word) {
        if (word.equals("i") || word.equals("a")) return 2_000_000;
        return counts.getOrDefault(word, 0);
    }

    private String phraseTypo(String part) {
        String best = "";
        int highest = JOINED_PART_MIN_COUNT - 1;
        for (int i = 0; i < part.length(); i++) {
            String head = part.substring(0, i);
            String tail = part.substring(i + 1);
            char current = part.charAt(i);
            if (!tail.isEmpty() && current != tail.charAt(0)) {
                String swapped = head + tail.charAt(0) + current + tail.substring(1);
                int count = partCount(swapped);
                if (count > highest) {
                    best = swapped;
                    highest = count;
                }
            }
            String neighbors = neighboringKeys(current);
            for (int key = 0; key < neighbors.length(); key++) {
                String adjusted = head + neighbors.charAt(key) + tail;
                int count = partCount(adjusted);
                if (count > highest) {
                    best = adjusted;
                    highest = count;
                }
            }
        }
        return best;
    }

    private static List<String> ranked(Map<String, Long> matches) {
        List<String> ranked = new ArrayList<>(matches.keySet());
        ranked.sort(Comparator.<String>comparingLong(matches::get).reversed().thenComparing(word -> word));
        return ranked;
    }

    private Map<String, Long> typos(String word) {
        Map<String, Long> result = new HashMap<>();
        if (word.length() < 2) return result;
        // Broad edits swamp useful completions for short tokens. Admit only a very common
        // transposition for a two-letter token or a three-letter exact match.
        if (word.length() == 2 || (word.length() == 3 && counts.containsKey(word))) {
            long minimum = Math.max(100_000, (long) counts.getOrDefault(word, 0) * 200);
            for (int i = 1; i < word.length(); i++) {
                if (word.charAt(i - 1) == word.charAt(i)) continue;
                String swapped = word.substring(0, i - 1) + word.charAt(i) + word.charAt(i - 1)
                        + word.substring(i + 1);
                add(result, swapped, 8, minimum);
            }
            return result;
        }
        Integer exactCount = counts.get(word);
        long minimumCount = SUGGEST_MIN_COUNT;
        if (exactCount != null) {
            minimumCount = Math.max(KNOWN_WORD_ALTERNATIVE_MIN_COUNT,
                    (long) exactCount * KNOWN_WORD_ALTERNATIVE_RATIO);
            if (minimumCount > highestCount) return result;
        }
        for (int i = 0; i <= word.length(); i++) {
            String head = word.substring(0, i);
            String tail = word.substring(i);
            for (int key = 0; key < ALPHABET.length(); key++)
                add(result, head + ALPHABET.charAt(key) + tail, 2, minimumCount);
            if (tail.isEmpty()) continue;
            char current = tail.charAt(0);
            String rest = tail.substring(1);
            add(result, head + rest, 2, minimumCount);
            if (tail.length() > 1 && current != tail.charAt(1))
                add(result, head + tail.charAt(1) + current + tail.substring(2), 8, minimumCount);
            String nearby = neighboringKeys(current);
            for (int key = 0; key < nearby.length(); key++)
                add(result, head + nearby.charAt(key) + rest, 3, minimumCount);
        }
        return result;
    }

    private static String neighboringKeys(char key) {
        String nearby = key >= 'a' && key <= 'z' ? NEIGHBORS[key - 'a'] : "";
        return nearby + switch (key) {
            case 'a' -> "ä"; case 'o' -> "ö"; case 'u' -> "ü";
            case 'ä' -> "a"; case 'ö' -> "o"; case 'ü' -> "u";
            default -> "";
        };
    }

    private void add(Map<String, Long> result, String word, int weight, long minimumCount) {
        Integer count = counts.get(word);
        if (count != null && count >= minimumCount)
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
