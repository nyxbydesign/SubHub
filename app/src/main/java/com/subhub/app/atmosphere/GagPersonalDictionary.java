package com.subhub.app.atmosphere;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/** Private keyboard suggestions. Only the IME may supply confirmed input from eligible editors. */
public final class GagPersonalDictionary {
    private static final String PREFS = "gag_personal_dictionary_v1";
    private static final int MAX_WORDS = 256;
    private static final int MAX_CUSTOM = 64;
    private static final int MAX_OPENINGS = 64;
    private static final int MAX_TRANSITIONS = 256;
    private static final int MIN_TRANSITION_COUNT = 2;
    private static final int MAX_WORD_CODE_POINTS = 32;
    private static final int MAX_COUNT = 255;
    private static final long WRITE_DELAY_MS = 750;
    private static Store shared;
    private final Store store;

    public GagPersonalDictionary(Context context) {
        synchronized (GagPersonalDictionary.class) {
            if (shared == null) shared = new Store(context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE), newWriter());
            store = shared;
        }
    }

    GagPersonalDictionary(SharedPreferences preferences, ScheduledThreadPoolExecutor writer) {
        store = new Store(preferences, writer);
    }

    private static ScheduledThreadPoolExecutor newWriter() {
        ScheduledThreadPoolExecutor writer = new ScheduledThreadPoolExecutor(1,
                task -> new Thread(task, "gag-dictionary-write"));
        writer.setRemoveOnCancelPolicy(true);
        return writer;
    }

    public boolean isLearningEnabled() {
        synchronized (store) { return store.learning; }
    }

    public void setLearningEnabled(boolean enabled) {
        synchronized (store) {
            store.learning = enabled;
            store.persistNow();
        }
    }

    /** Pass a committed word and, optionally, a short opener ending in that word. */
    public void recordToken(String token, String openingPhrase) {
        recordToken(token, openingPhrase, null);
    }

    /** The optional predecessor must also have been committed by the IME in this editor. */
    boolean recordToken(String token, String openingPhrase, String predecessor) {
        synchronized (store) {
            if (!store.learning || !store.model.record(token, openingPhrase, predecessor)) return false;
            store.scheduleWrite();
            return true;
        }
    }

    List<String> nextWords(String previous, List<String> base) {
        synchronized (store) { return store.learning ? store.model.nextWords(previous, base) : base; }
    }

    public List<String> suggestions(String prefix, List<String> generic, int limit) {
        synchronized (store) { return store.model.suggestions(prefix, generic, limit); }
    }

    public boolean addCustomWord(String word) {
        synchronized (store) {
            if (!store.model.addCustomWord(word)) return false;
            store.persistNow();
            return true;
        }
    }

    public boolean removeCustomWord(String word) {
        synchronized (store) {
            if (!store.model.removeCustomWord(word)) return false;
            store.persistNow();
            return true;
        }
    }

    public List<String> customWords() {
        synchronized (store) { return List.copyOf(store.model.custom.values()); }
    }

    public void clearLearned() {
        synchronized (store) {
            store.model.words.clear();
            store.model.openings.clear();
            store.model.transitions.clear();
            store.persistNow();
        }
    }

    private static final class Store {
        private final SharedPreferences preferences;
        private final ScheduledThreadPoolExecutor writer;
        private final Model model = new Model();
        private boolean learning;
        private ScheduledFuture<?> scheduled;
        private long settingsRevision;

        private Store(SharedPreferences preferences, ScheduledThreadPoolExecutor writer) {
            this.preferences = preferences;
            this.writer = writer;
            Map<String, ?> state = preferences.getAll();
            learning = !Boolean.FALSE.equals(state.get("learning"));
            model.restore(text(state, "words"), text(state, "openings"), text(state, "custom"),
                    text(state, "transitions"));
        }

        private static String text(Map<String, ?> state, String key) {
            Object value = state.get(key);
            return value instanceof String ? (String) value : "";
        }

        private void scheduleWrite() {
            if (scheduled != null) return;
            long revision = settingsRevision;
            scheduled = writer.schedule(() -> persistLearned(revision), WRITE_DELAY_MS, TimeUnit.MILLISECONDS);
        }

        private void persistLearned(long revision) {
            Snapshot snapshot;
            synchronized (this) {
                if (revision != settingsRevision) return;
                scheduled = null;
                snapshot = snapshot();
            }
            SharedPreferences.Editor edit = encodeSnapshot(snapshot);
            synchronized (this) {
                // Explicit edits publish immediately. A snapshot prepared before one cannot replace it.
                if (revision == settingsRevision) edit.apply();
            }
        }

        private void persistNow() {
            settingsRevision++;
            if (scheduled != null) scheduled.cancel(false);
            scheduled = null;
            encodeSnapshot(snapshot()).apply();
        }

        private Snapshot snapshot() {
            return new Snapshot(learning, List.copyOf(model.words.values()),
                    List.copyOf(model.openings.values()), List.copyOf(model.custom.values()),
                    List.copyOf(model.transitions.values()));
        }

        private SharedPreferences.Editor encodeSnapshot(Snapshot snapshot) {
            return preferences.edit().putBoolean("learning", snapshot.learning())
                    .putString("words", encode(snapshot.words()))
                    .putString("openings", encode(snapshot.openings()))
                    .putString("transitions", encode(snapshot.transitions()))
                    .putString("custom", new JSONArray(snapshot.custom()).toString());
        }
    }

    private record Snapshot(boolean learning, List<Learned> words, List<Learned> openings,
            List<String> custom, List<Learned> transitions) {}

    static final class Model {
        private final Map<String, Learned> words = new LinkedHashMap<>();
        private final Map<String, Learned> openings = new LinkedHashMap<>();
        private final Map<String, Learned> transitions = new LinkedHashMap<>();
        private final Map<String, String> custom = new LinkedHashMap<>();

        boolean record(String token, String openingPhrase) {
            return record(token, openingPhrase, null);
        }

        boolean record(String token, String openingPhrase, String predecessor) {
            String word = word(token);
            if (word == null) return false;
            String previous = word(predecessor);
            if (previous != null && words.containsKey(key(previous)))
                increment(transitions, previous + " " + word, MAX_TRANSITIONS);
            increment(words, word, MAX_WORDS);
            String opening = opening(openingPhrase);
            if (opening != null && key(opening.substring(opening.lastIndexOf(' ') + 1)).equals(key(word)))
                increment(openings, opening, MAX_OPENINGS);
            return true;
        }

        List<String> nextWords(String previous, List<String> base) {
            String word = word(previous);
            if (word == null || transitions.isEmpty()) return base;
            String prefix = key(word) + " ";
            Map<String, String> ranked = new LinkedHashMap<>();
            rankLearned(ranked, transitions, prefix, MIN_TRANSITION_COUNT);
            if (ranked.isEmpty()) return base;
            List<String> results = new ArrayList<>(3);
            for (String pair : ranked.values()) {
                results.add(pair.substring(pair.indexOf(' ') + 1));
                if (results.size() == 3) return List.copyOf(results);
            }
            for (String candidate : base) {
                if (!containsWord(results, key(candidate))) results.add(candidate);
                if (results.size() == 3) break;
            }
            return List.copyOf(results);
        }

        boolean addCustomWord(String input) {
            String word = word(input == null ? null : input.trim());
            if (word == null || (custom.size() == MAX_CUSTOM && !custom.containsKey(key(word)))) return false;
            custom.put(key(word), word);
            return true;
        }

        boolean removeCustomWord(String input) {
            String word = word(input);
            return word != null && custom.remove(key(word)) != null;
        }

        List<String> suggestions(String prefix, List<String> generic, int limit) {
            if (prefix == null || limit <= 0) return List.of();
            String normalized = prefix.isEmpty() ? "" : word(prefix);
            if (normalized == null) return List.of();
            String match = key(normalized);
            Map<String, String> ranked = new LinkedHashMap<>();
            if (match.isEmpty()) {
                rankLearned(ranked, openings, match, 2);
            } else {
                boolean genericExact = containsWord(generic, match);
                if (custom.containsKey(match) || words.containsKey(match) || genericExact)
                    ranked.put(match, prefix);
                // Keep one strong spelling alternate alongside the literal, even with personal completions.
                if (genericExact) {
                    for (String candidate : generic) {
                        String valid = word(candidate);
                        if (valid != null && !key(valid).startsWith(match)) {
                            ranked.put(key(valid), valid);
                            break;
                        }
                    }
                }
                for (Map.Entry<String, String> candidate : custom.entrySet()) {
                    if (matches(candidate.getKey(), match))
                        ranked.put(candidate.getKey(), withInitialCase(normalized, candidate.getValue()));
                }
                rankLearned(ranked, words, normalized, 1);
            }
            for (String candidate : generic) {
                String valid = match.isEmpty() ? opening(candidate) : word(candidate);
                if (valid != null && !key(valid).equals(match)) ranked.putIfAbsent(key(valid), valid);
            }
            List<String> result = new ArrayList<>(ranked.values());
            return List.copyOf(result.subList(0, Math.min(limit, result.size())));
        }

        private static void rankLearned(Map<String, String> result, Map<String, Learned> entries,
                String prefix, int minimum) {
            String match = key(prefix);
            List<Learned> candidates = new ArrayList<>();
            for (Map.Entry<String, Learned> entry : entries.entrySet()) {
                if (entry.getValue().count >= minimum && matches(entry.getKey(), match))
                    candidates.add(entry.getValue());
            }
            // Most recent records appear last in the store. Stable sorting keeps recent ties first.
            Collections.reverse(candidates);
            candidates.sort(Comparator.comparingInt((Learned entry) -> entry.count).reversed());
            for (Learned candidate : candidates)
                result.putIfAbsent(key(candidate.text), withInitialCase(prefix, candidate.text));
        }

        void restore(String savedWords, String savedOpenings, String savedCustom, String savedTransitions) {
            readLearned(words, savedWords, MAX_WORDS, GagPersonalDictionary::word);
            readLearned(openings, savedOpenings, MAX_OPENINGS, GagPersonalDictionary::opening);
            readLearned(transitions, savedTransitions, MAX_TRANSITIONS, GagPersonalDictionary::transition);
            if (savedCustom.length() > 50_000) return;
            try {
                JSONArray array = new JSONArray(savedCustom);
                for (int i = 0; i < Math.min(array.length(), MAX_CUSTOM); i++)
                    addCustomWord(array.optString(i, ""));
            } catch (JSONException ignored) {
                // A damaged dictionary must not prevent the keyboard from opening.
            }
        }
    }

    private static boolean matches(String candidate, String prefix) {
        return candidate.startsWith(prefix) && !candidate.equals(prefix);
    }

    private static boolean containsWord(List<String> candidates, String target) {
        for (String candidate : candidates) {
            String valid = word(candidate);
            if (valid != null && key(valid).equals(target)) return true;
        }
        return false;
    }

    private static String withInitialCase(String prefix, String candidate) {
        if (prefix.isEmpty() || !Character.isUpperCase(prefix.codePointAt(0))) return candidate;
        int firstEnd = Character.charCount(candidate.codePointAt(0));
        return candidate.substring(0, firstEnd).toUpperCase(Locale.ROOT) + candidate.substring(firstEnd);
    }

    private static String key(String word) {
        return Normalizer.normalize(word.toLowerCase(Locale.ROOT), Normalizer.Form.NFC).replace('’', '\'');
    }

    private static String word(String input) {
        if (input == null || input.isEmpty() || input.length() > MAX_WORD_CODE_POINTS * 2) return null;
        String value = Normalizer.normalize(input, Normalizer.Form.NFC);
        int length = value.codePointCount(0, value.length());
        if (length == 0 || length > MAX_WORD_CODE_POINTS || !Character.isLetter(value.codePointAt(0))) return null;
        boolean afterJoiner = false;
        for (int index = 0; index < value.length(); ) {
            int code = value.codePointAt(index);
            int type = Character.getType(code);
            if (Character.isLetter(code)) {
                afterJoiner = false;
            } else if (!afterJoiner && (code == '-' || code == '\'' || code == '’')) {
                afterJoiner = true;
            } else if (afterJoiner || (type != Character.NON_SPACING_MARK
                    && type != Character.COMBINING_SPACING_MARK && type != Character.ENCLOSING_MARK)) {
                return null;
            }
            index += Character.charCount(code);
        }
        return afterJoiner ? null : value;
    }

    private static String opening(String input) {
        if (input == null || input.length() > 3 * MAX_WORD_CODE_POINTS * 2 + 2) return null;
        String[] parts = input.split(" ", -1);
        if (parts.length < 2 || parts.length > 3) return null;
        for (int i = 0; i < parts.length; i++) {
            parts[i] = word(parts[i]);
            if (parts[i] == null) return null;
        }
        return String.join(" ", parts);
    }

    private static String transition(String input) {
        int space = input.indexOf(' ');
        if (space <= 0 || input.length() > MAX_WORD_CODE_POINTS * 4 + 1) return null;
        String previous = word(input.substring(0, space));
        String next = word(input.substring(space + 1));
        return previous != null && next != null ? previous + " " + next : null;
    }

    private static void increment(Map<String, Learned> entries, String text, int maximum) {
        String key = key(text);
        Learned previous = entries.remove(key);
        if (entries.size() == maximum) entries.remove(entries.keySet().iterator().next());
        entries.put(key, new Learned(text, previous == null ? 1 : Math.min(MAX_COUNT, previous.count + 1)));
    }

    private static String encode(List<Learned> entries) {
        JSONArray result = new JSONArray();
        for (Learned entry : entries) result.put(new JSONArray().put(entry.text).put(entry.count));
        return result.toString();
    }

    private static void readLearned(Map<String, Learned> entries, String value, int maximum,
            UnaryOperator<String> validate) {
        if (value.length() > 50_000) return;
        try {
            JSONArray array = new JSONArray(value);
            for (int index = 0; index < Math.min(array.length(), maximum); index++) {
                JSONArray row = array.optJSONArray(index);
                if (row == null) continue;
                String raw = row.optString(0, "");
                String text = validate.apply(raw);
                int count = row.optInt(1, 0);
                if (text != null && count > 0 && count <= MAX_COUNT)
                    entries.put(key(text), new Learned(text, count));
            }
        } catch (JSONException ignored) {
            // Incomplete persisted data is not used for learning.
        }
    }

    private static final class Learned {
        private final String text;
        private final int count;

        private Learned(String text, int count) {
            this.text = text;
            this.count = count;
        }
    }
}
