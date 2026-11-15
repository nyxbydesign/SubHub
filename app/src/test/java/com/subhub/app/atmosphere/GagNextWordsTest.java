package com.subhub.app.atmosphere;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

public final class GagNextWordsTest {
    @Test public void offersWholeBilingualStartersInLocaleOrder() {
        assertEquals(List.of("I", "You", "Ich"), GagNextWords.starters("en"));
        assertEquals(List.of("Ich", "Du", "I"), GagNextWords.starters("de"));
    }

    @Test public void usesLongestKnownContextAndRecognizesBothLanguages() {
        assertEquals(List.of("am", "have", "can"), GagNextWords.after("I ", "de"));
        assertEquals(List.of("here", "ready", "sorry"), GagNextWords.after("Well, I  am ", "de"));
        assertEquals(List.of("you"), GagNextWords.after("How are ", "en"));
        assertEquals(List.of("bin", "habe", "kann"), GagNextWords.after("Ich ", "en"));
        assertEquals(List.of("es"), GagNextWords.after("Wie geht ", "en"));
        assertEquals(List.of("Morgen", "Tag", "Abend"), GagNextWords.after("Guten ", "de"));
    }

    @Test public void fallsBackAtNewSentencesAndSkipsUnknownOrPartialTokens() {
        assertEquals(GagNextWords.starters("de"), GagNextWords.after("Hallo! ", "de"));
        assertEquals(GagNextWords.starters("en"), GagNextWords.after("Hello\n", "en"));
        assertEquals(List.of(), GagNextWords.after("Thank you", "en"));
        assertEquals(List.of(), GagNextWords.after("pomegranate ", "en"));
        assertEquals(List.of(), GagNextWords.after("x'ich ", "de"));
        assertEquals(List.of(), GagNextWords.after(" ".repeat(GagNextWords.CONTEXT_LIMIT), "en"));
    }

    @Test public void predictsThreeFromTheLatestWordOrPhraseWithoutChangingRepairEvidence() {
        assertEquals(List.of("there", "everyone", "again"), GagNextWords.predict("hello ", "en"));
        assertEquals(List.of("how", "I", "again"), GagNextWords.predict("hello there ", "en"));
        assertEquals(List.of("you", "we", "they"), GagNextWords.predict("How are ", "en"));
        assertEquals(List.of("for", "again", "and"), GagNextWords.predict("Thanks ", "en"));
        assertEquals(List.of("what", "I", "that"), GagNextWords.predict("yeah so like ", "en"));
        assertEquals(List.of("wait", "believe", "find"), GagNextWords.predict("I can’t ", "en"));
        assertEquals(List.of("es", "das", "nicht"), GagNextWords.predict("Wie geht ", "en"));
        assertEquals(List.of("für", "und", "nochmal"), GagNextWords.predict("Vielen Dank ", "en"));
        assertEquals(List.of("and", "but", "or"), GagNextWords.predict("pomegranate ", "en"));
        assertEquals(List.of("und", "aber", "oder"), GagNextWords.predict("Danke nochmal ", "en"));
        assertEquals(List.of(), GagNextWords.after("hello ", "en"));
        assertEquals(List.of("you"), GagNextWords.after("How are ", "en"));
    }

    @Test public void predictionRequiresACompleteBoundaryAndRetainsSentenceStarters() {
        assertEquals(List.of(), GagNextWords.predict("hello", "en"));
        assertEquals(List.of(), GagNextWords.predict("hello_world ", "en"));
        assertEquals(List.of(), GagNextWords.predict("a".repeat(GagNextWords.CONTEXT_LIMIT - 1) + " ", "en"));
        assertEquals(GagNextWords.starters("en"), GagNextWords.predict("Hello. ", "en"));
        assertEquals(GagNextWords.starters("de"), GagNextWords.predict("Hallo\n", "de"));
        assertEquals(List.of("and", "but", "or"), GagNextWords.predict("Well, ", "en"));
    }
}
