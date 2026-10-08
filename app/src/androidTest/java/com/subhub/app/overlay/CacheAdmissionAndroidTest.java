package com.subhub.app.overlay;

import androidx.test.core.app.ApplicationProvider;
import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.ObjectTracker;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class CacheAdmissionAndroidTest {
    @Test public void countsOnlyRequestedCacheEntriesThatSurviveLiveOverlapFiltering() {
        CensorOverlayView view = new CensorOverlayView(ApplicationProvider.getApplicationContext());
        BBox box = new BBox(20, 80, 30, 30);
        Detection cached = region(box, "world-cache:test");
        Detection quality = region(new BBox(70, 120, 20, 20), "current-quality:test");
        try {
            view.setWorldTracksAndCache(List.of(), List.of(cached, quality), 100, 200, null,
                    0, 0, 0, 0, 100, 200, null);
            assertEquals(1, view.admittedCachedRegionCount(List.of(cached)));
            assertEquals(2, view.admittedCachedRegionCount(List.of(cached, quality)));
            ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder().build());
            view.setWorldTracksAndCache(tracker.update(List.of(region(box, null))),
                    List.of(cached, quality), 100, 200, null, 0, 0, 0, 0, 100, 200, null);
            assertEquals(0, view.admittedCachedRegionCount(List.of(cached)));
            assertEquals(1, view.admittedCachedRegionCount(List.of(quality)));
            view.clearContent();
            assertEquals(0, view.admittedCachedRegionCount(List.of(cached, quality)));
        } finally { view.release(); }
    }

    private static Detection region(BBox box, String anchor) {
        return new Detection("FACE_FEMALE", "face", .95f, box, true, false,
                Detection.ObservationSource.VISUAL, Detection.GeometryQuality.MODEL, anchor);
    }
}
