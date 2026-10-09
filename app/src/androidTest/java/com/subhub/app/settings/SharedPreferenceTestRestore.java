package com.subhub.app.settings;

import android.content.SharedPreferences;
import java.util.Map;
import java.util.Set;

/** Restore only the test-owned application fixture, never device settings. */
public final class SharedPreferenceTestRestore {
    public static void restore(SharedPreferences preferences, Map<String, ?> original) {
        SharedPreferences.Editor edit = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : original.entrySet()) {
            Object value = entry.getValue();
            String key = entry.getKey();
            if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) edit.putInt(key, (Integer) value);
            else if (value instanceof Long) edit.putLong(key, (Long) value);
            else if (value instanceof Float) edit.putFloat(key, (Float) value);
            else if (value instanceof String) edit.putString(key, (String) value);
            else if (value instanceof Set) {
                @SuppressWarnings("unchecked") Set<String> values = (Set<String>) value;
                edit.putStringSet(key, new java.util.LinkedHashSet<>(values));
            }
        }
        org.junit.Assert.assertTrue(edit.commit());
    }
}
