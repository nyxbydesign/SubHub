package com.subhub.app.atmosphere;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

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

/** Private keyboard suggestions. Only the IME may supply confirmed input from eligible editors. */
public final class GagPersonalDictionary {
    private static final String PREFS = "gag_personal_dictionary_v1";
    private static final int MAX_WORDS = 256;
    private static final int MAX_CUSTOM = 64;
    private static final int MAX_OPENINGS = 64;
    private static final int MAX_WORD_CODE_POINTS = 32;
    private static final int MAX_COUNT = 255;
    private static final long WRITE_DELAY_MS = 750;
    private static Store shared;
    private final Store store;

    public GagPersonalDictionary(Context context) {
        synchronized (GagPersonalDictionary.class) {
            if (shared == null) shared = new Store(context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE));
            store = shared;
        }
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
        synchronized (store) {
            if (!store.learning || !store.model.record(token, openingPhrase)) return;
            store.scheduleWrite();
        }
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
            store.persistNow();
        }
    }

    private static final class Store {
        private final SharedPreferences preferences;
        private final Handler writer = new Handler(Looper.getMainLooper());
        private final Model model = new Model();
        private boolean learning;
        private boolean writeScheduled;
        private final Runnable write = this::persistNow;

        private Store(SharedPreferences preferences) {
            this.preferences = preferences;
            Map<String, ?> state = preferences.getAll();
            learning = !Boolean.FALSE.equals(state.get("learning"));
            model.restore(text(state, "words"), text(state, "openings"), text(state, "custom"));
        }

        private static String text(Map<String, ?> state, String key) {
            Object value = state.get(key);
            return value instanceof String ? (String) value : "";
        }

        private void scheduleWrite() {
            if (!writeScheduled) writeScheduled = writer.postDelayed(write, WRITE_DELAY_MS);
        }

        private synchronized void persistNow() {
            writer.removeCallbacks(write);
            writeScheduled = false;
            preferences.edit().putBoolean("learning", learning)
                    .putString("words", encode(model.words))
                    .putString("openings", encode(model.openings))
                    .putString("custom", new JSONArray(model.custom.values()).toString())
                    .apply();
        }
    }

    static final class Model {
        private final Map<String, Learned> words = new LinkedHashMap<>();
        private final Map<String, Learned> openings = new LinkedHashMap<>();
        private final Map<String, String> custom = new LinkedHashMap<>();

        boolean record(String token, String openingPhrase) {
            String word = word(token);
            if (word == null) return false;
            increment(words, word, MAX_WORDS);
            String opening = opening(openingPhrase);
            if (opening != null && key(opening.substring(opening.lastIndexOf(' ') + 1)).equals(key(word)))
                increment(openings, opening, MAX_OPENINGS);
            return true;
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
                if (custom.containsKey(match) || words.containsKey(match) || containsWord(generic, match))
                    ranked.put(match, prefix);
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

        void restore(String savedWords, String savedOpenings, String savedCustom) {
            readLearned(words, savedWords, MAX_WORDS, false);
            readLearned(openings, savedOpenings, MAX_OPENINGS, true);
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

    private static void increment(Map<String, Learned> entries, String text, int maximum) {
        String key = key(text);
        Learned previous = entries.remove(key);
        if (entries.size() == maximum) entries.remove(entries.keySet().iterator().next());
        entries.put(key, new Learned(text, previous == null ? 1 : Math.min(MAX_COUNT, previous.count + 1)));
    }

    private static String encode(Map<String, Learned> entries) {
        JSONArray result = new JSONArray();
        for (Learned entry : entries.values()) result.put(new JSONArray().put(entry.text).put(entry.count));
        return result.toString();
    }

    private static void readLearned(Map<String, Learned> entries, String value, int maximum, boolean phrases) {
        if (value.length() > 50_000) return;
        try {
            JSONArray array = new JSONArray(value);
            for (int index = 0; index < Math.min(array.length(), maximum); index++) {
                JSONArray row = array.optJSONArray(index);
                if (row == null) continue;
                String raw = row.optString(0, "");
                String text = phrases ? opening(raw) : word(raw);
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
