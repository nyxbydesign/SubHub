package com.subhub.app.people;

import org.junit.Test;
import static org.junit.Assert.*;

public class ProfileNamesTest {
    @Test public void displayTextRemovesControlsWithoutSplittingSurrogatePairs() {
        assertEquals("Ada  Lovelace", ProfileNames.clean("  Ada\n\tLovelace  "));
        assertEquals("", ProfileNames.clean(null));
        String prefix = String.join("", java.util.Collections.nCopies(79, "a"));
        assertEquals(prefix, ProfileNames.clean(prefix + "\uD83D\uDE42"));
        assertEquals(80, ProfileNames.clean(prefix + "bc").length());
    }
}
