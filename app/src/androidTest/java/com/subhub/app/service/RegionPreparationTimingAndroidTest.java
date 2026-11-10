package com.subhub.app.service;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public final class RegionPreparationTimingAndroidTest {
    @Test public void comparePortraitAndLandscapeCropReadback() {
        compare(1344, 2992, ColorSpace.Named.SRGB, true);
        compare(2992, 1344, ColorSpace.Named.SRGB, true);
    }

    @Test public void alphaAndWideGamutCropPixelsRemainIdentical() {
        compare(160, 320, ColorSpace.Named.DISPLAY_P3, false);
        compare(320, 160, ColorSpace.Named.SRGB, false);
    }

    @Test public void rejectsOverflowAndOutOfBoundsWithoutRecyclingSource() {
        Bitmap source = Bitmap.createBitmap(80, 160, Bitmap.Config.ARGB_8888);
        try {
            assertNull(InferenceBitmapPreparer.prepareRegion(source, 1, 1,
                    Integer.MAX_VALUE, 100, 160));
            assertNull(InferenceBitmapPreparer.prepareRegion(source, -1, 0, 80, 160, 160));
            assertNull(InferenceBitmapPreparer.prepareRegion(source, 0, 0, 0, 160, 160));
            assertFalse(source.isRecycled());
        } finally { source.recycle(); }
    }

    private void compare(int width, int height, ColorSpace.Named space, boolean benchmark) {
        Bitmap software = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888,
                true, ColorSpace.get(space));
        Canvas canvas = new Canvas(software);
        Paint paint = new Paint();
        for (int y = 0; y < height; y += 23) {
            for (int x = 0; x < width; x += 19) {
                paint.setColor(Color.argb(benchmark ? 255 : (x + y) & 255,
                        (x * 7 + y) & 255, (x + y * 3) & 255, (x * 5 + y * 11) & 255));
                canvas.drawRect(x, y, x + 19, y + 23, paint);
            }
        }
        Bitmap source = software.copy(Bitmap.Config.HARDWARE, false);
        assertNotNull(source);
        int left = width > height ? Math.round(width * .18f) : 0;
        int top = height > width ? Math.round(height * .18f) : 0;
        int cropWidth = width - left, cropHeight = height - top;
        int resolution = benchmark ? 512 : 80;
        int count = benchmark ? 9 : 1;
        long[] before = new long[count], after = new long[count];
        try {
            for (int i = benchmark ? -3 : 0; i < count; i++) {
                Bitmap old = null;
                InferenceBitmapPreparer.Prepared result = null;
                long oldNanos, newNanos;
                try {
                    if ((i & 1) == 0) {
                        long started = System.nanoTime();
                        old = legacy(source, left, top, cropWidth, cropHeight, resolution);
                        oldNanos = System.nanoTime() - started;
                        started = System.nanoTime();
                        result = InferenceBitmapPreparer.prepareRegion(source,
                                left, top, cropWidth, cropHeight, resolution);
                        newNanos = System.nanoTime() - started;
                    } else {
                        long started = System.nanoTime();
                        result = InferenceBitmapPreparer.prepareRegion(source,
                                left, top, cropWidth, cropHeight, resolution);
                        newNanos = System.nanoTime() - started;
                        started = System.nanoTime();
                        old = legacy(source, left, top, cropWidth, cropHeight, resolution);
                        oldNanos = System.nanoTime() - started;
                    }
                    assertNotNull(result);
                    assertTrue("Crop pixels for " + space, old.sameAs(result.bitmap));
                    assertEquals(cropWidth, result.sourceWidth);
                    assertEquals(cropHeight, result.sourceHeight);
                    assertFalse(source.isRecycled());
                    if (i >= 0) { before[i] = oldNanos; after[i] = newNanos; }
                } finally {
                    if (old != null) old.recycle();
                    if (result != null) result.bitmap.recycle();
                }
            }
            if (benchmark) {
                Arrays.sort(before); Arrays.sort(after);
                Bundle report = new Bundle();
                report.putString("stream", "\nREGION_AB source=" + width + 'x' + height
                        + " crop=" + cropWidth + 'x' + cropHeight + " samples=" + count
                        + " oldMedianUs=" + before[count / 2] / 1000
                        + " oldMaxUs=" + before[count - 1] / 1000
                        + " newMedianUs=" + after[count / 2] / 1000
                        + " newMaxUs=" + after[count - 1] / 1000 + "\n");
                InstrumentationRegistry.getInstrumentation().sendStatus(0, report);
            }
        } finally { source.recycle(); software.recycle(); }
    }

    private Bitmap legacy(Bitmap source, int left, int top, int width, int height, int resolution) {
        int[] dimensions = InferenceBitmapPreparer.targetDimensions(width, height, resolution);
        Matrix matrix = new Matrix();
        matrix.setScale(dimensions[0] / (float) width, dimensions[1] / (float) height);
        Bitmap transformed = Bitmap.createBitmap(source, left, top, width, height, matrix, true);
        Bitmap result = null;
        try {
            result = transformed.copy(Bitmap.Config.ARGB_8888, false);
            assertNotNull(result);
            return result;
        } finally {
            if (transformed != source && transformed != result) transformed.recycle();
        }
    }
}
