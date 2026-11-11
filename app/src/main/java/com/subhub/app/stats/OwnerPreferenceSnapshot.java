package com.subhub.app.stats;

import android.content.SharedPreferences;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/** Already immutable typed record view, never a writable or authority-bearing preference store. */
final class OwnerPreferenceSnapshot implements SharedPreferences {
    private final Map<String, Object> values;
    OwnerPreferenceSnapshot(Map<String, Object> values) { this.values = values == null ? Collections.emptyMap() : values; }
    @Override public Map<String, ?> getAll() { return values; }
    @Override public String getString(String key, String fallback) { Object value = values.get(key); return value == null ? fallback : (String) value; }
    @Override public Set<String> getStringSet(String key, Set<String> fallback) {
        Object value = values.get(key);
        @SuppressWarnings("unchecked") Set<String> result = value == null ? fallback : (Set<String>) value;
        return result;
    }
    @Override public int getInt(String key, int fallback) { Object value = values.get(key); return value == null ? fallback : (Integer) value; }
    @Override public long getLong(String key, long fallback) { Object value = values.get(key); return value == null ? fallback : (Long) value; }
    @Override public float getFloat(String key, float fallback) { Object value = values.get(key); return value == null ? fallback : (Float) value; }
    @Override public boolean getBoolean(String key, boolean fallback) { Object value = values.get(key); return value == null ? fallback : (Boolean) value; }
    @Override public boolean contains(String key) { return values.containsKey(key); }
    @Override public Editor edit() { throw new SecurityException("Immutable owner observation"); }
    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { throw new UnsupportedOperationException("Immutable owner observation"); }
    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { throw new UnsupportedOperationException("Immutable owner observation"); }
}
