package com.subhub.app.atmosphere;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Stores configuration only. Input from other apps never reaches these preferences. */
record GagSettings(boolean enabled, GagRules.Mode mode, int interval, String words, String replacements) {
    private static final String PREFS = "atmosphere_gag";

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context context) { return preferences(context).getBoolean("enabled", false); }

    static void setEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("enabled", enabled).apply();
    }

    static GagSettings load(Context context) {
        SharedPreferences prefs = preferences(context);
        GagRules.Mode mode;
        try {
            mode = GagRules.Mode.valueOf(prefs.getString("mode", GagRules.Mode.ALL.name()));
        } catch (IllegalArgumentException invalid) {
            mode = GagRules.Mode.ALL;
        }
        return new GagSettings(prefs.getBoolean("enabled", false), mode, prefs.getInt("interval", 3),
                prefs.getString("words", ""), prefs.getString("replacements", "mmph\nmph"));
    }

    void save(Context context) {
        preferences(context).edit().putBoolean("enabled", enabled).putString("mode", mode.name())
                .putInt("interval", interval).putString("words", words).putString("replacements", replacements).apply();
    }

    GagRules rules() {
        return new GagRules(mode, interval, Set.copyOf(items(words.toLowerCase(Locale.ROOT))), items(replacements));
    }

    static List<String> items(String raw) {
        List<String> values = new ArrayList<>();
        for (String line : raw.split("\\r\\n|\\r|\\n")) {
            String value = line.trim();
            if (!value.isEmpty()) values.add(value);
        }
        return values;
    }
}
