package com.subhub.app.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import org.junit.Test;

public final class GagLexiconTest {
    private final GagLexicon words = new GagLexicon(Map.of("the", 200_000, "there", 90_000,
            "their", 50_000, "hallo", 80_000, "halloween", 4_000, "schon", 40_000,
            "schön", 20_000, "plan", 10_000, "play", 8_000));

    @Test public void readerRanksOrderedUnsortedAndDuplicateRowsByTheirFinalCounts() throws Exception {
        var highestCount = GagLexicon.class.getDeclaredField("highestCount");
        highestCount.setAccessible(true);
        for (String rows : List.of(
                "hello\t80000\nhelp\t40000\nher\t30000\nhere\t10000\n",
                "here\t10000\nher\t30000\nhelp\t40000\nhello\t80000\n",
                "hello\t80000\nhelp\t40000\nher\t9000000\nher\t30000\nhere\t10000\n")) {
            var read = GagLexicon.read(new ByteArrayInputStream(rows.getBytes(StandardCharsets.UTF_8)));
            assertEquals(List.of("hello", "help", "her"), read.suggestions("he"));
            // A replaced maximum may keep the typo search running when no alternative can qualify.
            assertEquals(80_000, highestCount.getInt(read));
        }
    }

    @Test public void suggestsTranspositionsAndPreservesInitialCapitalization() {
        assertEquals(List.of("the"), words.suggestions("teh"));
        assertEquals(List.of("Hallo"), words.suggestions("Halol"));
        assertTrue(words.suggestions("teH").isEmpty());
    }

    @Test public void literalExactLookupRecognizesUppercaseAndCanonicalUnicode() {
        assertTrue(words.isKnownWord("THE"));
        assertTrue(words.isKnownWord("tHe"));
        assertTrue(words.isKnownWord("SCHO\u0308N"));
        assertTrue(!words.isKnownWord("teh"));
        assertTrue(!words.isKnownWord("192.168.1.1"));
        assertTrue(words.suggestions("teH").isEmpty());
    }

    @Test public void shippedLexiconSuggestsStrongEarlyEditsWithoutReplacingTheTypedWord() throws Exception {
        GagLexicon bundled = shippedLexicon();
        assertTrue(bundled.isKnownWord("hose"));
        assertEquals(List.of("those", "house"), bundled.suggestions("hose").subList(0, 2));
        assertEquals("Those", bundled.suggestions("Hose").get(0));
        assertEquals("from", bundled.suggestions("form").get(0));
        assertEquals("to", bundled.suggestions("ot").get(0));
        assertEquals("my", bundled.suggestions("ym").get(0));
        assertEquals("du", bundled.suggestions("ud").get(0));
        assertEquals("die", bundled.suggestions("dei").get(0));
        assertTrue(bundled.suggestions("whise").contains("whose"));
        for (String ordinary : List.of("hello", "home", "play", "plan", "quiet", "quite")) {
            assertTrue(bundled.suggestions(ordinary).stream().allMatch(word -> word.startsWith(ordinary)));
        }
        assertTrue(bundled.suggestions("192.168.1.1").isEmpty());
        assertTrue(bundled.suggestions("1234").isEmpty());
    }

    @Test public void shippedLexiconRepairsMultipleBoundariesAndOneAdditionalTypo() throws Exception {
        GagLexicon bundled = shippedLexicon();
        assertEquals("yeah so like", bundled.phraseRepair("yeahbsoblike", "en"));
        assertEquals("hello world", bundled.phraseRepair("hellobworld", "en"));
        assertEquals("hello world", bundled.phraseRepair("hellobwrold", "en"));
        assertEquals("hello world", bundled.phraseRepair("hellobbworld", "en"));
        assertEquals("look at this", bundled.phraseRepair("lookbatbthis", "en"));
        assertEquals("I love you", bundled.phraseRepair("iloveyou", "en"));
        assertEquals("Guten morgen", bundled.phraseRepair("Gutenbmorgen", "de"));
        assertEquals("ich bin", bundled.phraseRepair("ichbbin", "de"));
        assertEquals("wie geht es", bundled.phraseRepair("wiegehtes", "de"));
        assertEquals("HELLO WORLD", bundled.phraseRepair("HELLOBWORLD", "en"));
        assertEquals("thank you", bundled.phraseRepair("thankyou", "en"));
    }

    @Test public void phraseRepairsPreserveValidAndPersonalWordsAndExistingCorrections() throws Exception {
        GagLexicon bundled = shippedLexicon();
        for (String literal : List.of("blueberry", "clipboard", "keyboard", "rainbow", "someone", "anything",
                "already", "without", "goodbye", "vielleicht", "deshalb", "from", "teh", "recieve", "wrold",
                "doesn't", "hello,world", "42b10", "rainb", "HelloBWorld", "bluebonnet", "bookbinding",
                "handbook", "webhook")) {
            assertNull(literal, bundled.phraseRepair(literal, "en"));
        }
        assertEquals(List.of("thankyou", "thank you", "thanks"),
                GagLexicon.prioritizePhrase("thankyou", true, List.of("thankyou", "thanks"), "thank you"));
        assertEquals(List.of("hello world", "hello", "hellos"),
                GagLexicon.prioritizePhrase("hellobworld", false,
                        List.of("hello", "hellos", "hell"), "hello world"));
        assertEquals(List.of("hellobworld", "hello"),
                GagLexicon.prioritizePhrase("hellobworld", false, List.of("hellobworld", "hello"), "hello world"));
    }

    @Test public void commonNonNeighborSubstitutionsFillAnOtherwiseEmptySuggestionList() throws Exception {
        GagLexicon bundled = shippedLexicon();
        assertEquals(List.of("definitely"), bundled.suggestions("definately"));
        assertEquals(List.of("Vielleicht"), bundled.suggestions("Vielleacht"));
        assertEquals(List.of("wahrscheinlich"), bundled.suggestions("wahrscpeinlich"));
        assertTrue(bundled.suggestions("xqzw").isEmpty());
        assertTrue(bundled.suggestions("vielleicht").isEmpty());
    }

    private static GagLexicon shippedLexicon() throws Exception {
        Path source = Path.of("src/main/assets/gag_words/words.tsv.gz");
        return GagLexicon.read(new GZIPInputStream(Files.newInputStream(source)));
    }

    @Test public void comparableExactWordsAvoidTypoSuggestions() {
        assertTrue(words.suggestions("schon").isEmpty());
        assertTrue(words.suggestions("schön").isEmpty());
        assertEquals(List.of("plan", "play"), words.suggestions("pla"));
    }

    @Test public void suggestionsContainBoundedPrefixCompletionsAndTypoCandidates() {
        assertEquals(List.of("the", "there", "their"), words.suggestions("th"));
        assertEquals("Hallo", words.suggestions("Halol").get(0));
        assertTrue(words.suggestions("a".repeat(25)).isEmpty());
    }

    @Test public void committedBackspacePrefixesRequireAWholeCurrentToken() {
        String prefix = GagLexicon.cursorPrefix("another wor", "");
        assertEquals("wor", prefix);
        assertEquals(List.of("plan", "play"),
                words.suggestions(GagLexicon.cursorPrefix("another pla", " ")));
        assertEquals("", GagLexicon.cursorPrefix("another pla", "n"));
        assertEquals("", GagLexicon.cursorPrefix("another ", ""));
        assertEquals("", GagLexicon.cursorPrefix("a".repeat(GagLexicon.cursorPrefixLimit()), ""));
        assertEquals("", GagLexicon.cursorPrefix("pla", null));
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
        for (int pass = 0; pass < 2; pass++) {
            assertEquals(List.of("Get", "Getting", "Geht"), lexicon.suggestions("G"));
            assertEquals(List.of("get", "getting", "geht"), lexicon.suggestions("g"));
        }
        assertEquals(List.of("get", "getting", "geht"), lexicon.suggestions("ge"));
    }

    @Test public void gagDecidesAgainstTheOriginalWordBeforeUsingTheExplicitSuggestion() {
        GagRules rules = new GagRules(GagRules.Mode.MATCH, 2, Set.of("teh"), List.of("mmph"));
        assertEquals("mmph", rules.complete("teh", "the", bound -> 0));
        assertEquals("hallo", rules.complete("halol", "hallo", bound -> 0));
    }
}
