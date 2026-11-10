package com.subhub.app.remote;

import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** A failed write advances the fixture's visible map while its real Android backing remains old. */
final class MemoryAdvancingPreferences implements SharedPreferences {
    private final SharedPreferences backing;
    private final AtomicBoolean failNext;
    private Map<String, Object> memory;
    MemoryAdvancingPreferences(SharedPreferences backing, AtomicBoolean failNext) {
        this.backing = backing; this.failNext = failNext;
    }
    private static Map<String, Object> copy(Map<String, ?> source) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, ?> entry : source.entrySet())
            result.put(entry.getKey(), entry.getValue() instanceof Set ? new HashSet<>((Set<?>) entry.getValue()) : entry.getValue());
        return result;
    }
    @Override public synchronized Map<String, ?> getAll() { return copy(memory == null ? backing.getAll() : memory); }
    private synchronized Object value(String key, Object fallback) {
        Map<String, ?> values = memory == null ? backing.getAll() : memory;
        return values.containsKey(key) ? values.get(key) : fallback;
    }
    @Override public String getString(String key, String fallback) { return (String) value(key, fallback); }
    @SuppressWarnings("unchecked") @Override public Set<String> getStringSet(String key, Set<String> fallback) {
        Set<String> result = (Set<String>) value(key, fallback); return result == null ? null : new HashSet<>(result);
    }
    @Override public int getInt(String key, int fallback) { return (Integer) value(key, fallback); }
    @Override public long getLong(String key, long fallback) { return (Long) value(key, fallback); }
    @Override public float getFloat(String key, float fallback) { return (Float) value(key, fallback); }
    @Override public boolean getBoolean(String key, boolean fallback) { return (Boolean) value(key, fallback); }
    @Override public synchronized boolean contains(String key) { return (memory == null ? backing.getAll() : memory).containsKey(key); }
    @Override public Editor edit() { return new Edit(); }
    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        backing.registerOnSharedPreferenceChangeListener(listener);
    }
    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        backing.unregisterOnSharedPreferenceChangeListener(listener);
    }
    private final class Edit implements Editor {
        private final Map<String, Object> changed = new HashMap<>();
        private boolean clear;
        @Override public Editor putString(String key, String value) { changed.put(key, value); return this; }
        @Override public Editor putStringSet(String key, Set<String> value) { changed.put(key, value == null ? null : new HashSet<>(value)); return this; }
        @Override public Editor putInt(String key, int value) { changed.put(key, value); return this; }
        @Override public Editor putLong(String key, long value) { changed.put(key, value); return this; }
        @Override public Editor putFloat(String key, float value) { changed.put(key, value); return this; }
        @Override public Editor putBoolean(String key, boolean value) { changed.put(key, value); return this; }
        @Override public Editor remove(String key) { changed.put(key, null); return this; }
        @Override public Editor clear() { clear = true; return this; }
        @Override public void apply() { commit(); }
        @Override public boolean commit() {
            synchronized (MemoryAdvancingPreferences.this) {
                Map<String, Object> next = clear ? new HashMap<>() : copy(memory == null ? backing.getAll() : memory);
                for (Map.Entry<String, Object> entry : changed.entrySet()) {
                    if (entry.getValue() == null) next.remove(entry.getKey()); else next.put(entry.getKey(), entry.getValue());
                }
                if (failNext.getAndSet(false)) { memory = next; return false; }
                SharedPreferences.Editor edit = backing.edit().clear();
                for (Map.Entry<String, Object> entry : next.entrySet()) put(edit, entry.getKey(), entry.getValue());
                boolean saved = edit.commit(); memory = saved ? null : next; return saved;
            }
        }
    }
    @SuppressWarnings("unchecked") private static void put(Editor edit, String key, Object value) {
        if (value instanceof String) edit.putString(key, (String) value);
        else if (value instanceof Long) edit.putLong(key, (Long) value);
        else if (value instanceof Integer) edit.putInt(key, (Integer) value);
        else if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
        else if (value instanceof Float) edit.putFloat(key, (Float) value);
        else if (value instanceof Set) edit.putStringSet(key, (Set<String>) value);
        else throw new AssertionError("Unsupported fixture preference type");
    }
}
