package com.subhub.app.overlay;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;

public final class CensorLabelLayoutTest {
    @Test public void narrowBoxChoosesAvailableShortPhraseBeforeEllipsizing() {
        String selected = CensorLabelLayout.selectPhrase(
                Arrays.asList("TRIBUTE FIRST", "PAY TO PEEK", "EARN IT"),
                0, 7f, String::length);

        assertEquals("EARN IT", selected);
    }

    @Test public void wideBoxKeepsStableIdPhraseChoice() {
        String selected = CensorLabelLayout.selectPhrase(
                Arrays.asList("TRIBUTE FIRST", "PAY TO PEEK", "EARN IT"),
                1, 20f, String::length);

        assertEquals("PAY TO PEEK", selected);
    }

    @Test public void customPhraseUsesEllipsisOnlyAsLastResort() {
        assertEquals("CUS…", CensorLabelLayout.ellipsize(
                "CUSTOM PHRASE", 4f, String::length));
    }

    @Test public void narrowBoxDistributesStableIdsAcrossFittingLabels() {
        java.util.List<String> phrases = Arrays.asList("LONG LABEL ONE", "LONG LABEL TWO",
                "NO", "WAIT");
        for (int stableId = -8; stableId < 8; stableId++) {
            assertEquals(Math.floorMod(stableId, 2) == 0 ? "NO" : "WAIT",
                    CensorLabelLayout.selectPhrase(phrases, stableId, 4f, String::length));
        }
    }

    @Test public void noFitStillChoosesNarrowestBeforeEllipsis() {
        assertEquals("NO", CensorLabelLayout.selectPhrase(
                Arrays.asList("TOO LONG", "WAIT", "NO"), 0, 1f, String::length));
    }
}
