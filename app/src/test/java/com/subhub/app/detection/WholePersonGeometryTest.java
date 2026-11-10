package com.subhub.app.detection;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class WholePersonGeometryTest {
    private final WholePersonGeometry geometry = new WholePersonGeometry();

    private static Detection part(String category, int x, int y, int w, int h) {
        return new Detection(category, category, .9f, new BBox(x, y, w, h), false, false);
    }

    @Test public void headAndChestGiveImmediateBoundedProvisionalCoverage() {
        Detection head = part("face", 80, 20, 40, 40);
        Detection chest = part("breasts", 65, 80, 70, 45);
        List<WholePersonGeometry.Coverage> result = geometry.resolve(Collections.singletonList(chest),
                Arrays.asList(head, chest), Collections.emptyList(), 200, 300);
        assertEquals(new BBox(57, 12, 86, 121), result.get(0).person);
        assertFalse(result.get(0).refined);
        assertEquals(new BBox(65, 80, 70, 45), chest.getBox());
    }

    @Test public void refinedBoxCanShrinkProvisionalButAlwaysPreservesTrigger() {
        Detection chest = part("breasts", 65, 80, 70, 45);
        WholePersonGeometry.Coverage coverage = geometry.resolve(Collections.singletonList(chest),
                Collections.emptyList(), Collections.singletonList(
                        new PersonBox(new BBox(60, 10, 90, 190), .9f)), 200, 300).get(0);
        assertTrue(coverage.refined);
        assertEquals(new BBox(60, 10, 90, 190), coverage.person);
    }

    @Test public void croppedPortraitDoesNotInventLegsAcrossTheNextImageTile() {
        Detection head = part("face", 100, 100, 80, 80);
        Detection chest = part("breasts_covered", 80, 210, 130, 90);
        WholePersonGeometry.Coverage coverage = geometry.resolve(Collections.singletonList(head),
                Arrays.asList(head, chest), Collections.emptyList(), 400, 900).get(0);
        assertEquals(new BBox(64, 84, 162, 232), coverage.person);
        assertTrue(coverage.person.getBottom() <= 320);
        // Full-body evidence, when available, is not constrained to the provisional envelope.
        WholePersonGeometry.Coverage confirmed = geometry.resolve(Collections.singletonList(head),
                Arrays.asList(head, chest), Collections.singletonList(new PersonBox(
                        new BBox(60, 80, 170, 600), .9f)), 400, 900).get(0);
        assertTrue(confirmed.refined);
        assertEquals(680, confirmed.person.getBottom());
    }

    @Test public void overlappingModelPeopleFallBackToOriginalPart() {
        Detection chest = part("breasts", 65, 80, 70, 45);
        WholePersonGeometry.Coverage coverage = geometry.resolve(Collections.singletonList(chest),
                Arrays.asList(part("face", 80, 20, 40, 40), chest), Arrays.asList(
                        new PersonBox(new BBox(50, 10, 100, 200), .9f),
                        new PersonBox(new BBox(55, 10, 100, 200), .8f)), 200, 300).get(0);
        assertEquals(chest.getBox(), coverage.person);
        assertFalse(coverage.refined);
    }

    @Test public void neighboringHeadPreventsProvisionalBridgeAndTextNeverExpands() {
        Detection chest = part("breasts", 65, 80, 70, 45);
        List<Detection> cues = Arrays.asList(part("face", 80, 20, 40, 40), chest,
                part("face", 80, 90, 40, 40));
        assertEquals(chest.getBox(), geometry.resolve(Collections.singletonList(chest), cues,
                Collections.emptyList(), 200, 300).get(0).person);
        assertTrue(geometry.resolve(Collections.singletonList(part("text_smut", 80, 90, 10, 10)),
                cues, Collections.emptyList(), 200, 300).isEmpty());
    }

    @Test public void untriggeredPeopleAreNotCovered() {
        assertTrue(geometry.resolve(Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(new PersonBox(new BBox(0, 0, 200, 300), .9f)),
                200, 300).isEmpty());
    }
}
