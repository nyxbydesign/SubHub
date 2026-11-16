package com.subhub.app.atmosphere;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/** Authored, case-insensitive matches shared by contract validation and the keyboard. */
public final class GagWordPairs {
    public static final int MAX_COUNT = 64;
    public static final int MAX_SOURCE = 128;
    public static final int MAX_REPLACEMENT = 2048;
    private static final int MAX_CHARACTERS = 4096;

    private GagWordPairs() { }

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

    public static Map<String, String> read(JSONObject pairs) throws JSONException {
        if (pairs.length() == 0 || pairs.length() > MAX_COUNT)
            throw new IllegalArgumentException("Invalid number of Gag word pairs");
        Map<String, String> values = new LinkedHashMap<>();
        Set<String> sources = new HashSet<>();
        int length = 0;
        for (var keys = pairs.keys(); keys.hasNext(); ) {
            String source = keys.next();
            Object value = pairs.get(source);
            if (!(value instanceof String replacement) || !validSource(source)
                    || !validReplacement(replacement) || !sources.add(sourceKey(source)))
                throw new IllegalArgumentException("Invalid Gag word pair");
            length += source.length() + replacement.length();
            if (length > MAX_CHARACTERS) throw new IllegalArgumentException("Gag word pairs are too long");
            values.put(source, replacement);
        }
        return Collections.unmodifiableMap(values);
    }
}
