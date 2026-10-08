package com.subhub.app.subliminal;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Random;

public final class SubliminalPhraseSelectionTest {
    @Test public void everyNonPreviousMessageHasExactlyOneRandomRank() {
        for (int rank = 0; rank < 3; rank++) {
            assertEquals(rank < 1 ? rank : rank + 1,
                    SubliminalPhraseSelection.nextIndex(Arrays.asList("A", "B", "C", "D"),
                            "B", rankRandom(rank, 3)));
        }
    }

    @Test public void textExclusionSurvivesChangedOrderAndDuplicateEntries() {
        assertEquals(2, SubliminalPhraseSelection.nextIndex(Arrays.asList("B", "B", "A"),
                "B", rankRandom(0, 1)));
        assertEquals(1, SubliminalPhraseSelection.nextIndex(Arrays.asList("A", "C", "B"),
                "B", rankRandom(1, 2)));
    }

    @Test public void removedPreviousTextDoesNotExcludeAnUnrelatedIndex() {
        assertEquals(0, SubliminalPhraseSelection.nextIndex(Arrays.asList("A", "C"),
                "B", rankRandom(0, 2)));
    }

    @Test public void emptyAndSingleDistinctPoolsRemainUsable() {
        assertEquals(-1, SubliminalPhraseSelection.nextIndex(Collections.emptyList(),
                null, rankRandom(0, 1)));
        assertEquals(0, SubliminalPhraseSelection.nextIndex(Collections.singletonList("A"),
                "A", rankRandom(0, 1)));
        assertEquals(1, SubliminalPhraseSelection.nextIndex(Arrays.asList("A", "A"),
                "A", rankRandom(1, 2)));
    }

    private static Random rankRandom(int rank, int expectedBound) {
        return new Random(0) {
            @Override public int nextInt(int bound) {
                assertEquals(expectedBound, bound);
                return rank;
            }
        };
    }
}
