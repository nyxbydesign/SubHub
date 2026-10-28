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
}
