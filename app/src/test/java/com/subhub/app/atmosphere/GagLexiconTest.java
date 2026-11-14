package com.subhub.app.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public final class GagLexiconTest {
    private final GagLexicon words = new GagLexicon(Map.of("the", 200_000, "there", 90_000,
            "their", 50_000, "hallo", 80_000, "halloween", 4_000, "schon", 40_000,
            "schön", 20_000, "plan", 10_000, "play", 8_000));

    @Test public void suggestsTranspositionsAndPreservesInitialCapitalization() {
        assertEquals(List.of("the"), words.suggestions("teh"));
        assertEquals(List.of("Hallo"), words.suggestions("Halol"));
        assertTrue(words.suggestions("teH").isEmpty());
    }

    @Test public void exactWordsAvoidTypoSuggestions() {
        assertTrue(words.suggestions("schon").isEmpty());
        assertTrue(words.suggestions("schön").isEmpty());
        assertEquals(List.of("plan", "play"), words.suggestions("pla"));
    }

    @Test public void suggestionsContainBoundedPrefixCompletionsAndTypoCandidates() {
        assertEquals(List.of("the", "there", "their"), words.suggestions("th"));
        assertEquals("Hallo", words.suggestions("Halol").get(0));
        assertTrue(words.suggestions("a".repeat(25)).isEmpty());
    }

    @Test public void oneAndTwoLetterPrefixesConsiderTheWholeMatchRange() {
        Map<String, Integer> counts = new HashMap<>();
        for (char first = 'a'; first <= 'z'; first++)
            for (char second = 'a'; second <= 'z'; second++)
                counts.put("ges" + first + second, 100);
        counts.put("get", 20_000);
        counts.put("getting", 10_000);
        counts.put("geht", 5_000);
        GagLexicon lexicon = new GagLexicon(counts);
        assertEquals(List.of("Get", "Getting", "Geht"), lexicon.suggestions("G"));
        assertEquals(List.of("get", "getting", "geht"), lexicon.suggestions("ge"));
    }

    @Test public void gagDecidesAgainstTheOriginalWordBeforeUsingTheExplicitSuggestion() {
        GagRules rules = new GagRules(GagRules.Mode.MATCH, 2, Set.of("teh"), List.of("mmph"));
        assertEquals("mmph", rules.complete("teh", "the", bound -> 0));
        assertEquals("hallo", rules.complete("halol", "hallo", bound -> 0));
    }
}
