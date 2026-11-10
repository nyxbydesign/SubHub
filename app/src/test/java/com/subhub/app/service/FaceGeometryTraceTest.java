package com.subhub.app.service;

import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.ObjectTracker;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class FaceGeometryTraceTest {
    @Test public void sourcePoseIsUnknownUnlessImageRegistrationHasAPose() {
        assertEquals("-", FaceGeometryTrace.sourcePose(null));
        SpatialRegionCache cache = new SpatialRegionCache();
        RowMotionObserver.Scope scope = new RowMotionObserver.Scope(1, 2, 3, 100, 200);
        SpatialRegionCache.Frame baseline = cache.register(new int[64 * 192], 64, 192, 0, 192,
                scope, 1, 100, false, 0, 0, 100, 200);
        assertEquals("1,2,3,1,0.0", FaceGeometryTrace.sourcePose(baseline));
        SpatialRegionCache.Frame unknown = cache.register(new int[64 * 192], 64, 192, 0, 192,
                scope, 2, 200, false, 0, 0, 100, 200);
        assertEquals("-", FaceGeometryTrace.sourcePose(unknown));
    }

    @Test public void keepsObservationRawAndSmoothedGeometrySeparate() {
        ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder().motionPrediction(false)
                .trackingSmoothing(.5f).build());
        tracker.update(List.of(face(100)), 1_000_000_000L);
        Detection moved = face(160);
        String result = FaceGeometryTrace.encode(List.of(moved),
                tracker.update(List.of(moved), 1_333_000_000L));
        assertTrue(result.contains("observations=1,0,20,160,100,100"));
        assertTrue(result.contains("tracks=1,1,0,0,20,160,100,100,20,130,100,100"));
    }

    @Test public void boundsEncodingAndDoesNotLogArbitraryClassText() {
        List<Detection> many = new ArrayList<>();
        for (int i = 0; i < 10; i++) many.add(face(100));
        many.add(new Detection("PRIVATE_CUSTOM_LABEL", "other", 1, new BBox(0, 0, 1, 1), true, false));
        String result = FaceGeometryTrace.encode(many, List.of());
        assertTrue(result.contains("obsTotal=10 obsEncoded=8"));
        assertFalse(result.contains("PRIVATE_CUSTOM_LABEL"));
        assertTrue(result.endsWith("tracksTotal=0 tracksEncoded=0 tracks=-"));
    }
    private static Detection face(int y) {
        return new Detection("FACE_FEMALE", "face", .95f, new BBox(20, y, 100, 100), true, false);
    }
}
