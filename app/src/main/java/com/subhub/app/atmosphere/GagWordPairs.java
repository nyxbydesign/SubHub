package com.subhub.app.atmosphere;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

/** Authored matches shared by contract validation and the keyboard. */
public final class GagWordPairs {
    public static final int MAX_COUNT = 64;
    public static final int MAX_SOURCE = 128;
    public static final int MAX_REPLACEMENT = 2048;
    private static final int MAX_CHARACTERS = 4096;

    private GagWordPairs() { }

    public record Rule(List<String> options, boolean caseSensitive) {
        public Rule { options = List.copyOf(options); }

        boolean needsOptions() { return caseSensitive || options.size() != 1; }
    }

    public static String sourceKey(String source) {
        return source.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean validSource(String source) {
        if (source.isEmpty() || source.length() > MAX_SOURCE
                || !GagRules.wordCharacter(source.codePointAt(0))
                || !GagRules.wordCharacter(source.codePointBefore(source.length()))) return false;
        return source.codePoints().allMatch(point -> GagRules.wordCharacter(point) || GagRules.apostrophe(point));
    }

    public static boolean validReplacement(String replacement) {
        return !replacement.trim().isEmpty() && replacement.equals(replacement.trim())
                && replacement.length() <= MAX_REPLACEMENT
                && replacement.codePoints().noneMatch(Character::isISOControl);
    }

    public static boolean containsOptions(JSONObject pairs) {
        if (pairs == null) return false;
        for (var keys = pairs.keys(); keys.hasNext(); ) {
            if (!(pairs.opt(keys.next()) instanceof String)) return true;
        }
        return false;
    }

    static JSONObject json(Map<String, Rule> pairs) throws JSONException {
        JSONObject encoded = new JSONObject();
        for (var entry : pairs.entrySet()) {
            Rule rule = entry.getValue();
            encoded.put(entry.getKey(), rule.needsOptions() ? new JSONObject()
                    .put("options", new JSONArray(rule.options())).put("caseSensitive", rule.caseSensitive())
                    : rule.options().get(0));
        }
        return encoded;
    }

    private static Rule readRule(Object value) throws JSONException {
        if (value instanceof String replacement) return new Rule(List.of(replacement), false);
        if (!(value instanceof JSONObject rule) || rule.length() != 2
                || !(rule.opt("caseSensitive") instanceof Boolean caseSensitive)
                || !(rule.opt("options") instanceof JSONArray options) || options.length() == 0
                || options.length() > MAX_REPLACEMENT) throw new IllegalArgumentException("Invalid Gag word options");
        List<String> choices = new ArrayList<>();
        for (int i = 0; i < options.length(); i++) {
            Object item = options.get(i);
            if (!(item instanceof String option)) throw new IllegalArgumentException("Invalid Gag word option");
            choices.add(option);
        }
        return new Rule(choices, caseSensitive);
    }

    public static Map<String, Rule> read(JSONObject pairs) throws JSONException {
        if (pairs.length() == 0 || pairs.length() > MAX_COUNT)
            throw new IllegalArgumentException("Invalid number of Gag word pairs");
        Map<String, Rule> values = new LinkedHashMap<>();
        Set<String> insensitive = new HashSet<>();
        int length = 0;
        for (var keys = pairs.keys(); keys.hasNext(); ) {
            String source = keys.next();
            Rule rule = readRule(pairs.get(source));
            if (!validSource(source) || !rule.caseSensitive() && !insensitive.add(sourceKey(source)))
                throw new IllegalArgumentException("Invalid Gag word pair");
            int optionLength = rule.options().size() - 1;
            for (String option : rule.options()) {
                if (!validReplacement(option)) throw new IllegalArgumentException("Invalid Gag word option");
                optionLength += option.length();
            }
            if (optionLength > MAX_REPLACEMENT) throw new IllegalArgumentException("Gag word options are too long");
            length += source.length() + optionLength;
            if (length > MAX_CHARACTERS) throw new IllegalArgumentException("Gag word pairs are too long");
            values.put(source, rule);
        }
        return Collections.unmodifiableMap(values);
    }
}
