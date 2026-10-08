package com.subhub.app.settings;

import static org.junit.Assert.*;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class DetectionCategorySelectionTest {
    @Test public void eitherLegacyAssCategoryAppearsSelectedWithoutMutatingStorage() {
        for (String legacy : List.of("buttocks", "anus")) {
            Set<String> selected = new LinkedHashSet<>(Set.of(legacy, "face"));
            assertTrue(DetectionCategorySelection.isSelected(selected, "buttocks"));
            assertEquals(Set.of(legacy, "face"), selected);
        }
    }

    @Test public void assChangesBothInternalClassesAndPreservesOtherChoices() {
        Set<String> selected = new LinkedHashSet<>(Set.of("face", "breasts"));
        DetectionCategorySelection.setSelected(selected, "buttocks", true);
        assertEquals(Set.of("face", "breasts", "buttocks", "anus"), selected);
        DetectionCategorySelection.setSelected(selected, "buttocks", false);
        assertEquals(Set.of("face", "breasts"), selected);
    }

    @Test public void coveredAssIsPairedButIndependentOfExposedAss() {
        Set<String> selected = new LinkedHashSet<>(Set.of("anus_covered", "anus"));
        assertTrue(DetectionCategorySelection.isSelected(selected, "buttocks_covered"));
        DetectionCategorySelection.setSelected(selected, "buttocks_covered", true);
        assertEquals(Set.of("buttocks_covered", "anus_covered", "anus"), selected);
        DetectionCategorySelection.setSelected(selected, "buttocks_covered", false);
        assertEquals(Set.of("anus"), selected);
    }

    @Test public void displayChoicesDeduplicateBothPairsWithoutChangingDetectorIds() {
        List<String> raw = List.of("genitals_female", "genitals_male", "breasts", "buttocks", "anus",
                "buttocks_covered", "anus_covered", "face");
        assertEquals(List.of("genitals_female", "genitals_male", "breasts", "buttocks", "buttocks_covered", "face"),
                DetectionCategorySelection.choicesForDisplay(raw));
        assertEquals(8, raw.size());
        assertEquals(List.of("buttocks"), DetectionCategorySelection.choicesForDisplay(List.of("anus")));
    }
}
