package com.subhub.app.overlay;

import static org.junit.Assert.assertEquals;

import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;

import org.junit.Test;

public final class RenderTrackSnapshotTest {
    @Test public void unanchoredTextIdentityUsesWorldCoordinates() {
        Detection before = new Detection(
                "TEXT_EXPLICIT", "text_smut", 0.95f,
                new BBox(40, 310, 180, 42), true, true);
        Detection after = new Detection(
                "TEXT_EXPLICIT", "text_smut", 0.95f,
                new BBox(40, 190, 180, 42), true, true);

        RenderTrackSnapshot first = RenderTrackSnapshot.fromWorldTextDetection(
                before, 0L, 300L, 320, 714, 1344, 2992);
        RenderTrackSnapshot second = RenderTrackSnapshot.fromWorldTextDetection(
                after, 0L, 803L, 320, 714, 1344, 2992);

        assertEquals(first.box(), second.box());
        assertEquals(first.id(), second.id());
    }
}
