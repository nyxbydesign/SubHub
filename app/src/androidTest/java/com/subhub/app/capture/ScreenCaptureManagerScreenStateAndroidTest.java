package com.subhub.app.capture;

import android.view.Surface;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Real ImageReader, injected display boundary; no claim of a user-approved MediaProjection run. */
@RunWith(AndroidJUnit4.class)
public final class ScreenCaptureManagerScreenStateAndroidTest {
    @Test public void suspendedDisplayHasOneCreationAndRejectsTheRetiredReaderSurface() throws Exception {
        AtomicInteger creates = new AtomicInteger(), releases = new AtomicInteger();
        AtomicReference<Surface> output = new AtomicReference<>();
        ScreenCaptureManager capture = new ScreenCaptureManager(320, 480, 160, 320, 1f,
                (width, height, density, surface) -> {
                    assertEquals(320, width); assertEquals(480, height); assertEquals(160, density);
                    creates.incrementAndGet(); output.set(surface);
                    return new ScreenCaptureManager.DisplaySession() {
                        @Override public void surface(Surface value) { output.set(value); }
                        @Override public void release() { releases.incrementAndGet(); }
                    };
                });
        try {
            capture.start(true); assertEquals(1, creates.get()); assertNull(output.get());
            assertNull(capture.acquireLatestFrame());
            capture.pauseSurface(false); Surface original = output.get(); assertNotNull(original); assertTrue(original.isValid());
            capture.pauseSurface(true); assertNull(output.get()); assertNull(capture.acquireLatestFrame());
            assertFalse("Retired producer cannot publish a late pre-suspension image", original.isValid());
            capture.start(true); assertEquals(1, creates.get()); assertNull(output.get());
            capture.pauseSurface(false); assertNotSame(original, output.get()); assertTrue(output.get().isValid()); assertEquals(1, creates.get());
            try {
                android.graphics.Canvas stale = original.lockCanvas(null); original.unlockCanvasAndPost(stale);
                fail("The retired producer must reject late old pixels");
            } catch (IllegalStateException | IllegalArgumentException | Surface.OutOfResourcesException expected) { }
            assertNull("No old reader image crosses the resumed queue", capture.acquireLatestFrame());
        } finally { capture.close(); }
        assertEquals(1, releases.get()); capture.close(); assertEquals(1, releases.get());
        try { capture.start(); fail("A closed display cannot reuse the consumed projection grant"); }
        catch (IllegalStateException expected) { assertEquals(1, creates.get()); }
    }
}
