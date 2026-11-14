package com.subhub.app.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public final class GagKeyAlternativesTest {
    @Test public void lettersKeepTheirHintAndExposeAccentsWithShift() {
        assertEquals(List.of("1"), GagKeyAlternatives.forLetter("q", "1", false));
        assertEquals("@", GagKeyAlternatives.forLetter("a", "@", false).get(0));
        assertTrue(GagKeyAlternatives.forLetter("a", "@", false).contains("ä"));
        assertTrue(GagKeyAlternatives.forLetter("o", "9", false).contains("ö"));
        assertTrue(GagKeyAlternatives.forLetter("u", "7", false).contains("ü"));
        assertTrue(GagKeyAlternatives.forLetter("s", "#", false).contains("ß"));
        assertTrue(GagKeyAlternatives.forLetter("a", "@", true).contains("Ä"));
        assertTrue(GagKeyAlternatives.forLetter("s", "#", true).contains("ẞ"));
    }

    @Test public void bothSymbolPagesHoldUsableAlternates() {
        assertTrue(GagKeyAlternatives.forSymbol("1").contains("½"));
        assertTrue(GagKeyAlternatives.forSymbol("(").contains("["));
        assertTrue(GagKeyAlternatives.forSymbol("=").contains("≠"));
        assertTrue(GagKeyAlternatives.forSymbol("•").contains("·"));
        assertTrue(GagKeyAlternatives.forSymbol(".").contains("·"));
        assertFalse(GagKeyAlternatives.forSymbol("€").contains("€"));
        assertTrue(GagKeyAlternatives.forSymbol(",").isEmpty());
    }
}
