package com.subhub.app.overlay;

import com.subhub.app.detection.BBox;
import com.subhub.app.detection.RenderSourceReference;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BodyOverlapHeuristicsTest {
    @Test public void contradictoryDuplicateLabelIsNotASecondPersonHint() {
        RenderTrackSnapshot upper = box(1, "face", 0, 0, 100, 100);
        RenderTrackSnapshot ambiguous = box(2, "face", 0, 180, 100, 100);
        RenderTrackSnapshot duplicate = box(3, "breasts", 0, 180, 100, 100);
        RenderTrackSnapshot body = box(4, "belly", 0, 270, 100, 100);
        BodyOverlapHeuristics hints = new BodyOverlapHeuristics(List.of(upper, ambiguous, duplicate, body));
        assertTrue(hints.oneBody(List.of(duplicate), List.of(body)));
    }

    @Test public void partialBodyOverlapDoesNotDiscardDistinctHead() {
        RenderTrackSnapshot upper = box(1, "face", 0, 0, 100, 100);
        RenderTrackSnapshot lower = box(2, "face", 0, 180, 100, 100);
        RenderTrackSnapshot chest = box(3, "breasts", 0, 240, 100, 100);
        RenderTrackSnapshot upperBody = box(4, "belly", 0, 90, 100, 100);
        BodyOverlapHeuristics hints = new BodyOverlapHeuristics(List.of(upper, lower, chest, upperBody));
        assertFalse(hints.oneBody(List.of(upperBody), List.of(chest)));
    }

    @Test public void differentSourceBasisCannotContradictHead() {
        RenderTrackSnapshot upper = box(1, "face", 0, 0, 100, 100);
        RenderTrackSnapshot lower = box(2, "face", 0, 180, 100, 100);
        RenderSourceReference.Origin origin = new RenderSourceReference.Origin(1, 1, 1, 1000, 2000, 1);
        RenderTrackSnapshot foreign = new RenderTrackSnapshot(3, "breasts", new BBox(0, 180, 100, 100),
                0, 0, true, RenderSourceReference.known(origin, 10, 0, 0));
        RenderTrackSnapshot upperBody = box(4, "belly", 0, 90, 100, 100);
        RenderTrackSnapshot lowerBody = box(5, "belly", 0, 280, 100, 100);
        BodyOverlapHeuristics hints = new BodyOverlapHeuristics(List.of(upper, lower, foreign, upperBody, lowerBody));
        assertFalse(hints.oneBody(List.of(upperBody), List.of(lowerBody)));
    }

    private static RenderTrackSnapshot box(int id, String label, int x, int y, int w, int h) {
        return new RenderTrackSnapshot(id, label, new BBox(x, y, w, h), 0, 0, false);
    }
}
