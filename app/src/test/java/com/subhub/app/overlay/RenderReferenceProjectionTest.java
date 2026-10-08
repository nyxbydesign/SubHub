package com.subhub.app.overlay;

import com.subhub.app.detection.BBox;
import com.subhub.app.detection.RenderSourceReference;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RenderReferenceProjectionTest {
    private final RenderSourceReference.Origin origin =
            new RenderSourceReference.Origin(1, 2, 3, 1000, 2000, 4);

    @Test public void freshAndCarriedRegionsUseTheirOwnSourceBias() {
        RenderSourceReference carried = RenderSourceReference.known(origin, 10, 0, 0);
        RenderSourceReference fresh = RenderSourceReference.known(origin, 20, 0, 100);
        assertEquals(-200f, RenderCoordinates.offsetY(-200, -100, fresh, fresh), .001f);
        assertEquals(-100f, RenderCoordinates.offsetY(-200, -100, carried, fresh), .001f);
        assertEquals(-200f, RenderCoordinates.offsetY(-200, -100, RenderSourceReference.UNKNOWN, fresh), .001f);
        assertEquals(-180f, RenderCoordinates.offsetY(-200, -180, fresh, RenderSourceReference.UNKNOWN), .001f);
    }

    @Test public void incompatibleBitmapOriginsCannotBeSampled() {
        RenderSourceReference source = RenderSourceReference.known(origin, 10, 0, 40);
        RenderSourceReference bitmap = RenderSourceReference.known(origin, 20, 0, 100);
        assertTrue(RenderCoordinates.canSample(source, bitmap));
        assertFalse(RenderCoordinates.canSample(source, RenderSourceReference.UNKNOWN));
        assertEquals(160f, RenderCoordinates.source(200, -100, 200, 0,
                bitmap.biasY() - source.biasY()), .001f);
    }

    @Test public void steeringCanForgetOneIncompatibleBasisWithoutResettingOthers() {
        ContinuousTrackSteering steering = new ContinuousTrackSteering();
        steering.updateTarget(1, new BBox(10, 10, 20, 20), 100, 100, 100, false);
        steering.updateTarget(2, new BBox(20, 20, 20, 20), 100, 100, 100, false);
        steering.forget(1);
        assertNull(steering.position(1, 100, 100, 100));
        assertNotNull(steering.position(2, 100, 100, 100));
    }
}
