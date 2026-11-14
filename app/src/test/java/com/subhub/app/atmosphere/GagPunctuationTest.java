package com.subhub.app.atmosphere;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class GagPunctuationTest {
    @Test public void spacesBeforeTheNextWordAfterCommasPeriodsAndEllipses() {
        assertTrue(GagPunctuation.needsSpace(',', 'w', "hello,", ""));
        assertTrue(GagPunctuation.needsSpace('.', 'W', "hello.", ""));
        assertTrue(GagPunctuation.needsSpace('.', 'W', "hello...", ""));
        assertFalse(GagPunctuation.needsSpace('.', '.', "hello.", ""));
        assertFalse(GagPunctuation.needsSpace('.', '.', "hello..", ""));
        assertFalse(GagPunctuation.needsSpace(',', ' ', "hello,", ""));
    }

    @Test public void preservesDottedAndLocaleNumbers() {
        for (String number : new String[]{"192.168.1.14", "3.14", "1,234.56", "1.234,56", "1.e3"})
            assertUnchanged(number);
        assertFalse(GagPunctuation.needsSpace(',', '4', "3,", ""));
        assertFalse(GagPunctuation.needsSpace('.', 0x0664, "\u0663.", ""));
        assertFalse(GagPunctuation.needsSpace(',', 0xff14, "\uff13,", ""));
    }

    @Test public void leavesUnexpectedEditorContextAlone() {
        assertFalse(GagPunctuation.needsSpace('.', 'a', "word", ""));
        assertFalse(GagPunctuation.needsSpace('.', 'a', "3.", "14"));
        assertFalse(GagPunctuation.needsSpace('.', 'a', "word.", "tail"));
        assertFalse(GagPunctuation.needsSpace('.', 'a', ".", ""));
        assertFalse(GagPunctuation.needsSpace(',', 'a', "word,", null));
    }

    private static void assertUnchanged(String numericInput) {
        StringBuilder typed = new StringBuilder();
        char pending = 0;
        for (char next : numericInput.toCharArray()) {
            if (GagPunctuation.needsSpace(pending, next, typed, "")) typed.append(' ');
            typed.append(next);
            pending = next == '.' || next == ',' ? next : 0;
        }
        org.junit.Assert.assertEquals(numericInput, typed.toString());
    }
}
