package com.subhub.app.service;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Paint;
import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Arrays;

/** Timing must preserve dimensions, source ownership and software output. */
@RunWith(AndroidJUnit4.class)
public final class InferencePreparationTimingAndroidTest {
    @Test public void compareSingleReadbackWithHardwareScaleRoundTrip() {
        Bitmap software = Bitmap.createBitmap(1344, 2992, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(software);
        Paint paint = new Paint();
        for (int y = 0; y < 2992; y += 23) {
            for (int x = 0; x < 1344; x += 19) {
                paint.setColor(Color.rgb((x * 7 + y) & 255, (x + y * 3) & 255,
                        (x * 5 + y * 11) & 255));
                canvas.drawRect(x, y, x + 19, y + 23, paint);
            }
        }
        Bitmap hardware = software.copy(Bitmap.Config.HARDWARE, false);
        assertNotNull(hardware);
        try {
            for (int resolution : new int[]{320, 512}) {
                long[] original = new long[9];
                long[] single = new long[9];
                long[] scale = new long[9];
                long[] readback = new long[9];
                for (int i = -3; i < 9; i++) {
                    // Alternate order; discard three warmups for each resolution.
                    Bitmap candidate = null;
                    InferenceBitmapPreparer.Prepared baseline = null;
                    long candidateNanos;
                    long baselineNanos;
                    try {
                        if ((i & 1) == 0) {
                            long started = System.nanoTime();
                            candidate = singleReadback(hardware, resolution);
                            candidateNanos = System.nanoTime() - started;
                            started = System.nanoTime();
                            baseline = hardwareRoundTrip(hardware, resolution);
                            baselineNanos = System.nanoTime() - started;
                        } else {
                            long started = System.nanoTime();
                            baseline = hardwareRoundTrip(hardware, resolution);
                            baselineNanos = System.nanoTime() - started;
                            started = System.nanoTime();
                            candidate = singleReadback(hardware, resolution);
                            candidateNanos = System.nanoTime() - started;
                        }
                        assertNotNull(baseline);
                        assertNotNull(candidate);
                        assertTrue("Pixel parity at " + resolution,
                                baseline.bitmap.sameAs(candidate));
                        assertFalse(hardware.isRecycled());
                        if (i >= 0) {
                            original[i] = baselineNanos;
                            single[i] = candidateNanos;
                            scale[i] = baseline.scaleNanos;
                            readback[i] = baseline.readbackNanos;
                        }
                    } finally {
                        if (candidate != null) candidate.recycle();
                        if (baseline != null) baseline.bitmap.recycle();
                    }
                }
                Arrays.sort(original);
                Arrays.sort(single);
                Arrays.sort(scale);
                Arrays.sort(readback);
                Bundle report = new Bundle();
                report.putString("stream", "\nPREPARATION_AB resolution=" + resolution
                        + " samples=9 originalMedianUs=" + original[4] / 1000
                        + " originalMaxUs=" + original[8] / 1000
                        + " singleMedianUs=" + single[4] / 1000
                        + " singleMaxUs=" + single[8] / 1000
                        + " scaleMedianUs=" + scale[4] / 1000
                        + " readbackMedianUs=" + readback[4] / 1000 + "\n");
                InstrumentationRegistry.getInstrumentation().sendStatus(0, report);
            }
        } finally {
            hardware.recycle();
            software.recycle();
        }
    }

    private static Bitmap singleReadback(Bitmap hardware, int resolution) {
        InferenceBitmapPreparer.Prepared prepared =
                InferenceBitmapPreparer.prepare(hardware, resolution, false);
        assertNotNull(prepared);
        return prepared.bitmap;
    }

    private static InferenceBitmapPreparer.Prepared hardwareRoundTrip(
            Bitmap hardware, int resolution) {
        int[] dimensions = InferenceBitmapPreparer.targetDimensions(
                hardware.getWidth(), hardware.getHeight(), resolution);
        long start = System.nanoTime();
        Bitmap scaled = Bitmap.createScaledBitmap(hardware, dimensions[0], dimensions[1], true);
        long scaleEnd = System.nanoTime();
        Bitmap readable = null;
        try {
            readable = scaled.copy(Bitmap.Config.ARGB_8888, false);
            assertNotNull(readable);
            return new InferenceBitmapPreparer.Prepared(readable,
                    hardware.getWidth(), hardware.getHeight(), false,
                    scaleEnd - start, System.nanoTime() - scaleEnd, true);
        } finally {
            if (scaled != hardware && scaled != readable) scaled.recycle();
        }
    }

    @Test public void unscaledHardwareResultOwnsItsReadback() {
        Bitmap software = Bitmap.createBitmap(80, 160, Bitmap.Config.ARGB_8888);
        Bitmap hardware = software.copy(Bitmap.Config.HARDWARE, false);
        assertNotNull(hardware);
        InferenceBitmapPreparer.Prepared prepared = null;
        try {
            prepared = InferenceBitmapPreparer.prepare(hardware, 160, true);
            assertNotNull(prepared);
            assertTrue(prepared.hardwareReadback);
            assertFalse(prepared.bitmap.isRecycled());
            assertNotSame(hardware, prepared.bitmap);
            assertTrue(prepared.retainedSourceFrame);
            assertTrue(software.sameAs(prepared.bitmap));
        } finally {
            if (prepared != null) prepared.bitmap.recycle();
            hardware.recycle();
            software.recycle();
        }
    }

    @Test public void softwarePreparationDoesNotClaimHardwareReadback() {
        verify(false);
    }

    @Test public void alphaAndWideGamutKeepReferencePixels() {
        for (ColorSpace.Named space : new ColorSpace.Named[]{
                ColorSpace.Named.SRGB, ColorSpace.Named.DISPLAY_P3}) {
            Bitmap software = Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888,
                    true, ColorSpace.get(space));
            Canvas canvas = new Canvas(software);
            Paint paint = new Paint();
            for (int y = 0; y < 640; y += 17) {
                paint.setColor(Color.argb((y * 3) & 255, (y * 5) & 255, 90, 230));
                canvas.drawRect(0, y, 320, y + 17, paint);
            }
            Bitmap hardware = software.copy(Bitmap.Config.HARDWARE, false);
            assertNotNull(hardware);
            InferenceBitmapPreparer.Prepared actual = null;
            InferenceBitmapPreparer.Prepared expected = null;
            try {
                actual = InferenceBitmapPreparer.prepare(hardware, 160, false);
                expected = hardwareRoundTrip(hardware, 160);
                assertNotNull(actual);
                assertTrue("Pixel parity for " + space, actual.bitmap.sameAs(expected.bitmap));
                assertEquals(expected.bitmap.getColorSpace(), actual.bitmap.getColorSpace());
                assertFalse(hardware.isRecycled());
            } finally {
                if (actual != null) actual.bitmap.recycle();
                if (expected != null) expected.bitmap.recycle();
                hardware.recycle();
                software.recycle();
            }
        }
    }

    @Test public void hardwarePreparationReportsNonnegativeStageTimes() {
        verify(true);
    }

    private void verify(boolean hardware) {
        Bitmap software = Bitmap.createBitmap(160, 320, Bitmap.Config.ARGB_8888);
        Bitmap source = hardware ? software.copy(Bitmap.Config.HARDWARE, false) : software;
        assertNotNull(source);
        InferenceBitmapPreparer.Prepared result = null;
        try {
            result = InferenceBitmapPreparer.prepare(source, 160, false);
            assertNotNull(result);
            assertEquals(80, result.bitmap.getWidth());
            assertEquals(160, result.bitmap.getHeight());
            assertEquals(Bitmap.Config.ARGB_8888, result.bitmap.getConfig());
            assertFalse(source.isRecycled());
            assertTrue(result.scaleNanos >= 0L);
            assertTrue(result.readbackNanos >= 0L);
            if (!hardware) assertFalse(result.hardwareReadback);
        } finally {
            if (result != null) result.bitmap.recycle();
            if (source != software) source.recycle();
            software.recycle();
        }
    }
}
