package com.subhub.app.capture.export;

import android.content.Context;
import android.content.SharedPreferences;
import com.subhub.app.settings.SettingsRepository;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.*;

/** Only censor configuration is copied; controller, wallet and account values never enter jobs. */
public final class ExportSettings {
    private static final String STORE = "subhub_export_settings";
    private static final Set<String> KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "enabled_categories", "detection_confidence_percent", "detection_quality", "censor_type", "censor_coverage",
            "censor_intensity", "censor_size_padding", "show_border", "animate_border", "border_effect",
            "show_text", "border_color", "enabled_phrase_categories", "custom_phrases", "reverse_mode",
            "reverse_strength", "reverse_cutout_shape", "error_popup_title", "error_popup_text",
            "border_gradient_start", "border_gradient_end")));
    private ExportSettings() { }
    public static SharedPreferences preferences(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
        if (!prefs.contains("initialized")) reset(context);
        return prefs;
    }
    public static void reset(Context context) {
        SharedPreferences.Editor target = context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().clear();
        for (Map.Entry<String, ?> entry : new SettingsRepository(context).preferences().getAll().entrySet())
            if (allowed(entry.getKey())) put(target, entry.getKey(), entry.getValue());
        if (!target.putBoolean("initialized", true).commit()) throw new IllegalStateException("Could not save export settings");
    }
    public static boolean allowed(String key) { return KEYS.contains(key) || SettingsRepository.palettePreferenceKeys().contains(key); }
    public static ExportOptions options(Context context) {
        SharedPreferences p = preferences(context);
        ExportOptions.Quality quality;
        try { quality = ExportOptions.Quality.valueOf(p.getString("export_quality", "BALANCED")); }
        catch (IllegalArgumentException e) { quality = ExportOptions.Quality.BALANCED; }
        return new ExportOptions(quality, p.getBoolean("export_fast", false) ? 2 : 1,
                p.getBoolean("export_mute", false), false);
    }
    public static JSONObject snapshot(Context context) throws JSONException {
        return encode(preferences(context).getAll());
    }
    public static JSONObject encode(Map<String, ?> source) throws JSONException {
        JSONObject result = new JSONObject();
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            if (!allowed(entry.getKey())) continue;
            Object value = entry.getValue(); JSONObject encoded = new JSONObject();
            if (value instanceof Set<?>) { encoded.put("type", "set"); value = new JSONArray((Collection<?>) value); }
            else if (value instanceof Float) encoded.put("type", "float");
            else if (value instanceof Integer) encoded.put("type", "int");
            else if (value instanceof Long) encoded.put("type", "long");
            else if (value instanceof Boolean) encoded.put("type", "boolean");
            else if (value instanceof String) encoded.put("type", "string");
            else continue;
            encoded.put("value", value); result.put(entry.getKey(), encoded);
        }
        return result;
    }
    @SuppressWarnings("unchecked")
    private static void put(SharedPreferences.Editor target, String key, Object value) {
        if (value instanceof String) target.putString(key, (String) value);
        else if (value instanceof Boolean) target.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) target.putInt(key, (Integer) value);
        else if (value instanceof Long) target.putLong(key, (Long) value);
        else if (value instanceof Float) target.putFloat(key, (Float) value);
        else if (value instanceof Set<?>) target.putStringSet(key, new HashSet<>((Set<String>) value));
    }
    public static SharedPreferences frozen(JSONObject object) throws JSONException {
        Map<String, Object> values = new HashMap<>();
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next(); if (!allowed(key)) continue;
            JSONObject entry = object.getJSONObject(key); Object value;
            switch (entry.getString("type")) {
                case "set":
                    Set<String> set = new LinkedHashSet<>(); JSONArray array = entry.getJSONArray("value");
                    for (int index = 0; index < array.length(); index++) set.add(array.getString(index));
                    value = Collections.unmodifiableSet(set); break;
                case "int": value = entry.getInt("value"); break;
                case "long": value = entry.getLong("value"); break;
                case "float": value = (float) entry.getDouble("value"); break;
                case "boolean": value = entry.getBoolean("value"); break;
                case "string": value = entry.getString("value"); break;
                default: throw new JSONException("Unknown export setting type");
            }
            values.put(key, value);
        }
        return new Frozen(values);
    }
    private static final class Frozen implements SharedPreferences {
        private final Map<String, Object> values;
        Frozen(Map<String, Object> values) { this.values = Collections.unmodifiableMap(values); }
        public Map<String, ?> getAll() { return values; }
        public String getString(String k, String d) { return (String) values.getOrDefault(k, d); }
        @SuppressWarnings("unchecked") public Set<String> getStringSet(String k, Set<String> d) { return (Set<String>) values.getOrDefault(k, d); }
        public int getInt(String k, int d) { return (Integer) values.getOrDefault(k, d); }
        public long getLong(String k, long d) { return (Long) values.getOrDefault(k, d); }
        public float getFloat(String k, float d) { return (Float) values.getOrDefault(k, d); }
        public boolean getBoolean(String k, boolean d) { return (Boolean) values.getOrDefault(k, d); }
        public boolean contains(String key) { return values.containsKey(key); }
        public Editor edit() { throw new UnsupportedOperationException("An export snapshot is immutable"); }
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
    }
}
