package com.subhub.app.overlay;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class CensorPhrasesTest {
    @Test
    public void customPhrasesAreNormalizedAndDeduplicated() {
        List<String> phrases = CensorPhrases.build(
                Collections.emptySet(),
                new LinkedHashSet<>(Arrays.asList("  obey   now ", "OBEY NOW")));
        assertEquals(1, phrases.size());
        assertEquals("OBEY NOW", phrases.get(0));
    }

    @Test
    public void emptySelectionFallsBackSafely() {
        assertTrue(CensorPhrases.build(Collections.emptySet(), Collections.emptySet())
                .contains("BLOCKED"));
    }

    @Test public void neutralDefaultsRemainAvailableAndThemesHaveDistinctShortLabels() {
        assertEquals(Arrays.asList("BLOCKED", "CENSORED", "DENIED", "LOCKED"),
                CensorPhrases.build(Collections.singleton("short"), Collections.emptySet()));
        LinkedHashSet<String> all = new LinkedHashSet<>();
        for (String category : CensorPhrases.categoryNames()) {
            List<String> phrases = CensorPhrases.build(Collections.singleton(category),
                    Collections.emptySet());
            assertEquals("short".equals(category) ? 4 : 10, phrases.size());
            for (String phrase : phrases) {
                assertTrue(category + ": " + phrase, phrase.length() <= 24);
                assertTrue("Duplicate across categories: " + phrase, all.add(phrase));
            }
        }
        assertEquals(64, all.size());
        assertEquals(new LinkedHashSet<>(Arrays.asList("short", "denial")),
                CensorPhrases.DEFAULT_ENABLED);
    }
}
