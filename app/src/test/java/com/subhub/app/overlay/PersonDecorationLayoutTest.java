package com.subhub.app.overlay;

import com.subhub.app.detection.BBox;
import com.subhub.app.detection.RenderSourceReference;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PersonDecorationLayoutTest {
    private static RenderTrackSnapshot track(int id, String category, BBox box) {
        return new RenderTrackSnapshot(id, category, box, 0, 0, false);
    }
    @Test public void containedPartsLoseOnlyDecorationsNotRawMasks() {
        BBox head = new BBox(40, 10, 20, 20), chest = new BBox(30, 40, 40, 30);
        BBox person = new BBox(20, 0, 60, 100);
        RenderTrackSnapshot first = track(1, "face", head), second = track(2, "breasts", chest);
        assertEquals(Collections.singleton(2), PersonDecorationLayout.suppressed(
                Arrays.asList(first, second), Arrays.asList(head, chest), Arrays.asList(person, chest)));
        assertEquals(head, first.box());
        assertEquals(chest, second.box());
    }
    @Test public void equalExpansionsHaveOneStableOwnerIndependentOfOrder() {
        BBox part = new BBox(40, 20, 20, 20), person = new BBox(20, 0, 60, 100);
        assertEquals(Collections.singleton(9), PersonDecorationLayout.suppressed(
                Arrays.asList(track(9, "face", part), track(3, "breasts", part)),
                Arrays.asList(part, part), Arrays.asList(person, person)));
    }
    @Test public void unrelatedPartialOverlapsAndTextKeepDecorations() {
        BBox part = new BBox(40, 20, 20, 20), person = new BBox(20, 0, 60, 100);
        BBox other = new BBox(70, 20, 40, 40);
        assertTrue(PersonDecorationLayout.suppressed(
                Arrays.asList(track(1, "face", part), track(2, "face", other), track(3, "text_smut", part)),
                Arrays.asList(part, other, part), Arrays.asList(person, other, part)).isEmpty());
    }
    @Test public void noExpansionAndIncompatibleSourceBasisNeverSuppress() {
        BBox part = new BBox(40, 20, 20, 20), person = new BBox(20, 0, 60, 100);
        RenderSourceReference reference = RenderSourceReference.known(
                new RenderSourceReference.Origin(1, 1, 1, 100, 100, 1), 10, 0, 0);
        assertTrue(PersonDecorationLayout.suppressed(
                Arrays.asList(track(1, "face", part), new RenderTrackSnapshot(2, "face", part,
                        0, 0, false, reference)), Arrays.asList(part, part), Arrays.asList(person, part)).isEmpty());
        assertTrue(PersonDecorationLayout.suppressed(
                Arrays.asList(track(1, "face", part), track(2, "face", part)),
                Arrays.asList(part, part), Arrays.asList(part, part)).isEmpty());
    }
}
