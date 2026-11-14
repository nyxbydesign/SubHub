package com.subhub.app.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public final class GagPersonalDictionaryTest {
    @Test
    public void literalKnownWordKeepsItsSpellingAheadOfCustomLearnedAndGenericSuggestions() {
        GagPersonalDictionary.Model model = new GagPersonalDictionary.Model();
        assertTrue(model.addCustomWord("Showtime"));
        assertTrue(model.record("showcase", null));
        assertEquals(List.of("SHOW", "Showtime", "Showcase"),
                model.suggestions("SHOW", List.of("show", "shown"), 3));
        assertEquals(List.of("Showtime", "showcase", "shown"),
                model.suggestions("show", List.of("shown"), 3));
    }

    @Test
    public void unicodeWordsAreNormalizedAndPrivateLookingInputIsRejected() {
        GagPersonalDictionary.Model model = new GagPersonalDictionary.Model();
        assertTrue(model.addCustomWord("E\u0301lan"));
        assertTrue(model.record("cœur", null));
        assertFalse(model.record("person@example", null));
        assertFalse(model.record("pin2468", null));
        assertFalse(model.addCustomWord("two words"));
        assertEquals(List.of("Élan"), model.suggestions("E\u0301", List.of(), 3));
        assertEquals(List.of("cœur"), model.suggestions("cœ", List.of(), 3));
        assertTrue(model.removeCustomWord("élan"));
        assertEquals(List.of(), model.suggestions("é", List.of(), 3));
    }

    @Test
    public void openingsNeedTwoBoundedMatchingCommitsAndUseGenericFallback() {
        GagPersonalDictionary.Model model = new GagPersonalDictionary.Model();
        assertTrue(model.record("morning", "Good morning"));
        assertTrue(model.record("morning", "Very good morning everyone"));
        assertTrue(model.record("morning", "Good night"));
        assertEquals(List.of("Hello there"), model.suggestions("", List.of("Hello there"), 3));
        assertTrue(model.record("Morning", "Good Morning"));
        assertEquals(List.of("Good Morning", "Hello there"),
                model.suggestions("", List.of("good morning", "Hello there"), 3));
    }

    @Test
    public void learnedCountsOrderResultsAndOldItemsLeaveTheBoundedStore() {
        GagPersonalDictionary.Model model = new GagPersonalDictionary.Model();
        assertTrue(model.record("first", null));
        assertTrue(model.record("first", null));
        assertTrue(model.record("final", null));
        assertEquals(List.of("first", "final"), model.suggestions("fi", List.of(), 3));
        for (int i = 0; i < 256; i++)
            assertTrue(model.record("word" + (char) ('a' + i / 26) + (char) ('a' + i % 26), null));
        assertEquals(List.of(), model.suggestions("fi", List.of(), 3));
    }
}
