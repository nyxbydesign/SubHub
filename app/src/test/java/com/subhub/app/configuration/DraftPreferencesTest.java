package com.subhub.app.configuration;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class DraftPreferencesTest {
    @Test public void independentDraftsCannotShareMutableSelectionSets() {
        DraftPreferences first=new DraftPreferences(),second=new DraftPreferences();
        Set<String> source=new HashSet<>(Arrays.asList("app.one"));
        first.edit().putStringSet("selected",source).commit();source.add("app.two");
        first.getStringSet("selected",Collections.emptySet()).clear();
        assertEquals(Collections.singleton("app.one"),first.getStringSet("selected",Collections.emptySet()));
        assertFalse(second.contains("selected"));
    }
    @Test public void callbacksObserveCompleteAtomicEditsAndCanReadTheNewState() {
        DraftPreferences draft=new DraftPreferences();List<String> events=new ArrayList<>();
        draft.registerOnSharedPreferenceChangeListener((preferences,key)->{
            assertTrue(preferences.getBoolean("enabled",false));
            assertEquals(73,preferences.getInt("minutes",0));events.add(key);
        });
        draft.edit().putBoolean("enabled",true).putInt("minutes",73).apply();
        assertEquals(new HashSet<>(Arrays.asList("enabled","minutes")),new HashSet<>(events));
    }
    @Test public void frozenDraftRejectsExistingAndNewEditorsWithoutNotifications() {
        DraftPreferences draft=new DraftPreferences();
        draft.edit().putString("name","sample").putBoolean("enabled",true).putInt("minutes",45)
                .putLong("duration",3600000L).putFloat("ratio",0.5f)
                .putStringSet("selected",new HashSet<>(Arrays.asList("preview.reader"))).commit();
        Map<String,?> before=draft.getAll();
        android.content.SharedPreferences.Editor acquiredBeforeFreeze=draft.edit().putInt("minutes",99);
        List<String> events=new ArrayList<>();
        draft.registerOnSharedPreferenceChangeListener((preferences,key)->events.add(key));
        draft.freeze();
        assertFalse(acquiredBeforeFreeze.commit());
        assertFalse(draft.edit().clear().putString("injected","value").commit());
        draft.edit().remove("name").putBoolean("enabled",false).putLong("duration",0L).apply();
        assertEquals(before,draft.getAll());
        assertEquals("sample",draft.getString("name",""));
        assertTrue(draft.getBoolean("enabled",false));
        assertEquals(45,draft.getInt("minutes",0));
        assertEquals(3600000L,draft.getLong("duration",0L));
        assertEquals(0.5f,draft.getFloat("ratio",0f),0f);
        assertFalse(draft.contains("injected"));
        draft.getStringSet("selected",Collections.emptySet()).clear();
        ((Set<?>)draft.getAll().get("selected")).clear();
        assertEquals(Collections.singleton("preview.reader"),draft.getStringSet("selected",Collections.emptySet()));
        assertTrue(events.isEmpty());
    }
}
