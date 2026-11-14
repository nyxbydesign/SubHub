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
        assertEquals(List.of("¹", "½", "⅓", "¼", "⅕", "⅙", "⅐", "⅛", "⅑", "⅒"),
                GagKeyAlternatives.forSymbol("1"));
        assertEquals(List.of("²", "⅔", "⅖"), GagKeyAlternatives.forSymbol("2"));
        assertEquals(List.of("³", "¾", "⅗", "⅜"), GagKeyAlternatives.forSymbol("3"));
        assertEquals(List.of("⁴", "⅘"), GagKeyAlternatives.forSymbol("4"));
        assertEquals(List.of("⁵", "⅚", "⅝"), GagKeyAlternatives.forSymbol("5"));
        assertEquals(List.of("⁶"), GagKeyAlternatives.forSymbol("6"));
        assertEquals(List.of("⁷", "⅞"), GagKeyAlternatives.forSymbol("7"));
        assertEquals(List.of("⁸"), GagKeyAlternatives.forSymbol("8"));
        assertEquals(List.of("⁹"), GagKeyAlternatives.forSymbol("9"));
        assertEquals(List.of("⁰", "ⁿ", "∅"), GagKeyAlternatives.forSymbol("0"));
        assertEquals(List.of("_", "–", "—", "·"), GagKeyAlternatives.forSymbol("-"));
        assertEquals(List.of("Π", "μ", "Ω"), GagKeyAlternatives.forSymbol("π"));
        assertEquals(List.of("¿", "‽"), GagKeyAlternatives.forSymbol("?"));
        assertEquals(List.of("¢", "£", "€", "¥", "₱", "₹"), GagKeyAlternatives.forSymbol("$"));
        assertEquals(List.of("‹", "≤", "«", "⟨"), GagKeyAlternatives.forSymbol("<"));
        assertEquals(List.of("›", "≥", "»", "⟩"), GagKeyAlternatives.forSymbol(">"));
        assertTrue(GagKeyAlternatives.forSymbol("(").contains("["));
        assertTrue(GagKeyAlternatives.forSymbol("=").contains("≠"));
        assertTrue(GagKeyAlternatives.forSymbol("•").contains("·"));
        assertTrue(GagKeyAlternatives.forSymbol(".").contains("·"));
        assertFalse(GagKeyAlternatives.forSymbol("€").contains("€"));
        assertTrue(GagKeyAlternatives.forSymbol(",").isEmpty());
    }
}
