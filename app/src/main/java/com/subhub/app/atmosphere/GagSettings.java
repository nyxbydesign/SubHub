package com.subhub.app.atmosphere;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.subhub.app.remote.GagContractFields;
import org.json.JSONException;
import org.json.JSONObject;

/** Stores configuration only. Input from other apps never reaches these preferences. */
record GagSettings(boolean enabled, GagRules.Mode mode, int interval, String words, String replacements,
        Map<String, String> pairs) {
    static GagSettings from(JSONObject value) throws JSONException {
        return new GagSettings(value.getBoolean("enabled"), GagRules.Mode.valueOf(value.getString("mode")),
                value.getInt("interval"), value.getString("words"), value.getString("replacements"),
                value.has(GagContractFields.PAIRS)
                        ? GagWordPairs.read(value.getJSONObject(GagContractFields.PAIRS)) : Map.of());
    }

    JSONObject json() throws JSONException {
        JSONObject value = new JSONObject().put("enabled", enabled).put("mode", mode.name()).put("interval", interval)
                .put("words", words).put("replacements", replacements);
        if (!pairs.isEmpty()) value.put(GagContractFields.PAIRS, new JSONObject(pairs));
        return value;
    }

    GagRules rules() {
        Map<String, String> normalized = new LinkedHashMap<>();
        pairs.forEach((source, replacement) -> normalized.put(GagWordPairs.sourceKey(source), replacement));
        return new GagRules(mode, interval, Set.copyOf(items(words.toLowerCase(Locale.ROOT))),
                items(replacements), normalized);
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
