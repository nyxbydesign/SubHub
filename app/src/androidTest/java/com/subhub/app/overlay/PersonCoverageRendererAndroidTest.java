package com.subhub.app.overlay;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.View;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.ObjectTracker;
import com.subhub.app.detection.PersonBox;
import com.subhub.app.detection.RenderSourceReference;
import com.subhub.app.detection.TrackedObject;
import com.subhub.app.settings.CensorAppearance;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class PersonCoverageRendererAndroidTest {
    @Test public void sharedPersonMaskHasOneLabelAndAnIntactOuterBorder() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CensorOverlayView view = new CensorOverlayView(ApplicationProvider.getApplicationContext());
            Bitmap image = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888);
            try {
                view.setAppearance(new CensorAppearance(CensorAppearance.Type.BOX, 100, 0f,
                        true, false, CensorAppearance.BorderEffect.CLASSIC, true, Color.MAGENTA,
                        List.of("BLOCKED"), false, 100, "rectangle", "SubHub", "Blocked"));
                Detection head = new Detection("face", "face", 1f, new BBox(70, 30, 40, 40), false, false);
                Detection chest = new Detection("breasts", "breasts", 1f, new BBox(60, 90, 70, 40), true, true);
                ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder().build());
                List<TrackedObject> tracks = tracker.update(List.of(head, chest));
                assertEquals(2, tracks.size());
                view.setTracks(tracks, 200, 300, null);
                view.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY));
                view.layout(0, 0, 200, 300);
                long token = view.beginPersonCoverage(List.of(head, chest), List.of(head, chest),
                        SystemClock.uptimeMillis(), 0, 0, 200, 300, RenderSourceReference.UNKNOWN);
                assertTrue(view.refinePersonCoverage(token, List.of(new PersonBox(
                        new BBox(40, 10, 120, 240), .9f))));
                view.draw(new Canvas(image));
                java.lang.reflect.Field field = CensorOverlayView.class.getDeclaredField("labelPlacements");
                field.setAccessible(true);
                assertEquals(1, ((List<?>) field.get(view)).size());
                assertEquals(Color.BLACK, image.getPixel(60, 100));
                assertEquals(Color.MAGENTA, image.getPixel(40, 60));
                assertEquals(new BBox(70, 30, 40, 40), tracks.get(0).getBox());
            } catch (ReflectiveOperationException error) {
                throw new AssertionError(error);
            } finally {
                view.release();
                image.recycle();
            }
        });
    }

    @Test public void provisionalAndRefinedPixelsPreservePartsAndRejectReplacedFrame() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CensorOverlayView view = new CensorOverlayView(ApplicationProvider.getApplicationContext());
            Bitmap image = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888);
            try {
                view.setAppearance(new CensorAppearance(CensorAppearance.Type.BOX, 1, 0f,
                        false, false, CensorAppearance.BorderEffect.CLASSIC, false, Color.MAGENTA,
                        List.of(), false, 100, "rectangle", "SubHub", "Blocked"));
                Detection chest = new Detection("breasts", "breasts", 1f,
                        new BBox(65, 80, 70, 45), true, true);
                Detection head = new Detection("face", "face", 1f,
                        new BBox(80, 20, 40, 40), false, false);
                ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder().build());
                List<TrackedObject> tracks = tracker.update(List.of(chest));
                assertFalse(tracks.isEmpty());
                view.setTracks(tracks, 200, 300, null);
                view.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY));
                view.layout(0, 0, 200, 300);
                long token = view.beginPersonCoverage(List.of(chest), List.of(head, chest),
                        SystemClock.uptimeMillis(), 0, 0, 200, 300, RenderSourceReference.UNKNOWN);
                view.draw(new Canvas(image));
                assertEquals(Color.BLACK, image.getPixel(100, 30));
                assertEquals(Color.TRANSPARENT, image.getPixel(100, 250));
                assertEquals(Color.BLACK, image.getPixel(100, 100));
                assertTrue(view.refinePersonCoverage(token, List.of(new PersonBox(
                        new BBox(60, 10, 90, 190), .9f))));
                image.eraseColor(Color.TRANSPARENT);
                view.draw(new Canvas(image));
                assertEquals(Color.TRANSPARENT, image.getPixel(100, 250));
                assertEquals(Color.BLACK, image.getPixel(100, 170));
                assertEquals(Color.BLACK, image.getPixel(100, 100));
                view.setTracks(tracks, 200, 300, null);
                assertFalse(view.refinePersonCoverage(token, List.of()));
                image.eraseColor(Color.TRANSPARENT);
                view.draw(new Canvas(image));
                assertEquals(Color.TRANSPARENT, image.getPixel(100, 170));
                assertEquals(Color.BLACK, image.getPixel(100, 100));
                view.beginPersonCoverage(List.of(chest), List.of(head, chest),
                        SystemClock.uptimeMillis(), 0, 0, 200, 300, RenderSourceReference.UNKNOWN);
                image.eraseColor(Color.TRANSPARENT);
                view.draw(new Canvas(image));
                assertEquals(Color.BLACK, image.getPixel(100, 170));
                assertEquals(Color.TRANSPARENT, image.getPixel(100, 250));
                assertEquals(chest.getBox(), tracks.get(0).getBox());
            } finally {
                view.release();
                image.recycle();
            }
        });
    }
}
