package com.subhub.app.atmosphere;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

/** Local emoji usage. The order of stored entries breaks frequency ties. */
final class GagEmojiUsage {
    static final String PREFS = "subhub_gag_emoji";
    private static final String LEGACY_RECENT = "recent";
    private static final String FREQUENCY = "frequency";
    private static final int DISPLAY_LIMIT = 32;

    private final SharedPreferences preferences;
    private final GagEmojiCatalog catalog;

    GagEmojiUsage(SharedPreferences preferences, GagEmojiCatalog catalog) {
        this.preferences = preferences;
        this.catalog = catalog;
    }

    List<GagEmojiCatalog.Entry> frequentlyUsed() {
        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(read().entrySet());
        Collections.reverse(ranked);
        ranked.sort(Map.Entry.comparingByValue(Comparator.reverseOrder()));
        List<GagEmojiCatalog.Entry> displayed = new ArrayList<>();
        for (int i = 0; i < Math.min(DISPLAY_LIMIT, ranked.size()); i++)
            displayed.add(catalog.entry(ranked.get(i).getKey()));
        return displayed;
    }

    void record(String sequence) {
        if (catalog.entry(sequence) == null) return;
        Map<String, Integer> counts = read();
        Integer previous = counts.remove(sequence);
        counts.put(sequence, previous == null ? 1 : (int) Math.min(Integer.MAX_VALUE, previous + 1L));
        StringBuilder stored = new StringBuilder();
        counts.forEach((emoji, count) -> stored.append(count).append(' ').append(emoji).append(' '));
        preferences.edit().putString(FREQUENCY, stored.toString()).remove(LEGACY_RECENT).apply();
    }

    static void clear(SharedPreferences preferences) {
        preferences.edit().remove(FREQUENCY).remove(LEGACY_RECENT).apply();
    }

    private Map<String, Integer> read() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        String saved = preferences.getString(FREQUENCY, null);
        if (saved == null) {
            List<String> legacy = new ArrayList<>();
            StringTokenizer recent = new StringTokenizer(preferences.getString(LEGACY_RECENT, ""));
            while (recent.hasMoreTokens() && legacy.size() < DISPLAY_LIMIT) {
                String emoji = recent.nextToken();
                if (catalog.entry(emoji) != null && !legacy.contains(emoji)) legacy.add(emoji);
            }
            Collections.reverse(legacy);
            for (String emoji : legacy) counts.put(emoji, 1);
            return counts;
        }
        StringTokenizer pairs = new StringTokenizer(saved);
        while (pairs.hasMoreTokens()) {
            String count = pairs.nextToken();
            if (!pairs.hasMoreTokens()) break;
            String emoji = pairs.nextToken();
            if (catalog.entry(emoji) == null) continue;
            try {
                int uses = Integer.parseInt(count);
                if (uses > 0) counts.put(emoji, uses);
            } catch (NumberFormatException malformed) {
                // Ignore invalid local entries while retaining the rest of the history.
            }
        }
        return counts;
    }
}
