package com.subhub.app.overlay;

import static org.junit.Assert.assertEquals;

import com.subhub.app.detection.BBox;

import org.junit.Test;

public final class ContentSpaceCoordinatesTest {
    @Test public void observationsFromDifferentScrollPhasesShareOneWorldBox() {
        BBox first = ContentSpaceCoordinates.toWorld(
                new BBox(120, 700, 160, 180), 0L, 300L,
                1080, 2400, 1080, 2400);
        BBox second = ContentSpaceCoordinates.toWorld(
                new BBox(120, 420, 160, 180), 0L, 580L,
                1080, 2400, 1080, 2400);

        assertEquals(first, second);
    }

    @Test public void midScrollBirthAndEstablishedTrackRenderAtTheSamePhase() {
        BBox establishedWorld = ContentSpaceCoordinates.toWorld(
                new BBox(200, 900, 180, 220), 0L, 100L,
                1080, 2400, 1080, 2400);
        BBox bornWorld = ContentSpaceCoordinates.toWorld(
                new BBox(200, 540, 180, 220), 0L, 460L,
                1080, 2400, 1080, 2400);

        BBox establishedAtPresentation = ContentSpaceCoordinates.toScreen(
                establishedWorld, 0L, 525L, 1080, 2400, 1080, 2400);
        BBox bornAtPresentation = ContentSpaceCoordinates.toScreen(
                bornWorld, 0L, 525L, 1080, 2400, 1080, 2400);

        assertEquals(establishedWorld, bornWorld);
        assertEquals(establishedAtPresentation, bornAtPresentation);
        assertEquals(475, bornAtPresentation.getY());
    }

    @Test public void cameraScalingPreservesDetectorAndDisplayCoordinateSystems() {
        BBox world = ContentSpaceCoordinates.toWorld(
                new BBox(40, 100, 50, 60), 0L, 600L,
                320, 711, 1080, 2400);

        assertEquals(new BBox(40, 278, 50, 60), world);
        assertEquals(new BBox(40, 100, 50, 60), ContentSpaceCoordinates.toScreen(
                world, 0L, 600L, 320, 711, 1080, 2400));
    }

    @Test public void nonSquarePixelViewportRoundTripsNegativeAndLargeCameras() {
        int sourceWidth = 320;
        int sourceHeight = 714;
        int viewportWidth = 1344;
        int viewportHeight = 2992;
        BBox nearEdge = new BBox(271, 663, 47, 49);

        for (long cameraX : new long[] {-2_400L, -31L, 0L, 1_337L, 18_000L}) {
            for (long cameraY : new long[] {-7_000L, -17L, 0L, 2_913L, 41_000L}) {
                BBox world = ContentSpaceCoordinates.toWorld(
                        nearEdge, cameraX, cameraY,
                        sourceWidth, sourceHeight, viewportWidth, viewportHeight);
                assertEquals(nearEdge, ContentSpaceCoordinates.toScreen(
                        world, cameraX, cameraY,
                        sourceWidth, sourceHeight, viewportWidth, viewportHeight));
            }
        }
    }
}
