package com.subhub.app.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public final class InferenceBitmapPreparerAndroidTest {
    private static final String TAG = "UltraPreprocessTest";

    @Test public void ultraReadsBackModelSizedHardwareFrame() {
        Bitmap software = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888);
        new Canvas(software).drawColor(Color.rgb(74, 20, 95));
        Bitmap hardware = software.copy(Bitmap.Config.HARDWARE, false);
        assertNotNull(hardware);

        long[] fullCopyNanos = new long[5];
        long[] preparedNanos = new long[5];
        Bitmap lastPrepared = null;
        try {
            for (int index = 0; index < fullCopyNanos.length; index++) {
                long started = SystemClock.elapsedRealtimeNanos();
                Bitmap full = hardware.copy(Bitmap.Config.ARGB_8888, false);
                fullCopyNanos[index] = SystemClock.elapsedRealtimeNanos() - started;
                assertNotNull(full);
                full.recycle();

                started = SystemClock.elapsedRealtimeNanos();
                InferenceBitmapPreparer.Prepared prepared =
                        InferenceBitmapPreparer.prepare(hardware, 512, false);
                preparedNanos[index] = SystemClock.elapsedRealtimeNanos() - started;
                assertNotNull(prepared);
                if (lastPrepared != null) lastPrepared.recycle();
                lastPrepared = prepared.bitmap;
            }
            assertNotNull(lastPrepared);
            assertFalse(lastPrepared.isRecycled());
            assertEquals(Bitmap.Config.ARGB_8888, lastPrepared.getConfig());
            assertEquals(230, lastPrepared.getWidth());
            assertEquals(512, lastPrepared.getHeight());
            long fullBytes = 1080L * 2400L * 4L;
            assertTrue(lastPrepared.getAllocationByteCount() * 8L < fullBytes);

            Arrays.sort(fullCopyNanos);
            Arrays.sort(preparedNanos);
            Log.i(TAG, "median full readback=" + fullCopyNanos[2] / 1_000_000f
                    + " ms, prepared=" + preparedNanos[2] / 1_000_000f
                    + " ms, bytes=" + fullBytes + " -> "
                    + lastPrepared.getAllocationByteCount());
        } finally {
            if (lastPrepared != null && !lastPrepared.isRecycled()) lastPrepared.recycle();
            hardware.recycle();
            software.recycle();
        }
    }

    @Test public void sourceEffectsRetainTheModelSizedFrame() {
        Bitmap software = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888);
        Bitmap hardware = software.copy(Bitmap.Config.HARDWARE, false);
        assertNotNull(hardware);
        InferenceBitmapPreparer.Prepared prepared = null;
        try {
            prepared = InferenceBitmapPreparer.prepare(hardware, 512, true);
            assertNotNull(prepared);
            assertTrue(prepared.retainedSourceFrame);
            assertEquals(230, prepared.bitmap.getWidth());
            assertEquals(512, prepared.bitmap.getHeight());
        } finally {
            if (prepared != null && !prepared.bitmap.isRecycled()) prepared.bitmap.recycle();
            hardware.recycle();
            software.recycle();
        }
    }

    @Test public void portraitQualityTilePreservesAdditionalHorizontalPixels() {
        Bitmap software = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888);
        new Canvas(software).drawColor(Color.rgb(35, 80, 120));
        Bitmap hardware = software.copy(Bitmap.Config.HARDWARE, false);
        assertNotNull(hardware);
        InferenceBitmapPreparer.Prepared prepared = null;
        try {
            QualityTilePlanner.Tile tile = QualityTilePlanner.select(1080, 2400, 0L);
            prepared = InferenceBitmapPreparer.prepareRegion(hardware, tile, 512);
            assertNotNull(prepared);
            assertEquals(281, prepared.bitmap.getWidth());
            assertEquals(512, prepared.bitmap.getHeight());
            assertTrue(prepared.bitmap.getWidth() > 230);
            assertEquals(Bitmap.Config.ARGB_8888, prepared.bitmap.getConfig());
        } finally {
            if (prepared != null && !prepared.bitmap.isRecycled()) prepared.bitmap.recycle();
            hardware.recycle();
            software.recycle();
        }
    }

    @Test public void comparesSingleTransformAndTwoStageQualityTileReadback() {
        Bitmap software = Bitmap.createBitmap(1344, 2992, Bitmap.Config.ARGB_8888);
        new Canvas(software).drawColor(Color.rgb(55, 40, 95));
        Bitmap hardware = software.copy(Bitmap.Config.HARDWARE, false);
        assertNotNull(hardware);
        QualityTilePlanner.Tile tile = QualityTilePlanner.select(1344, 2992, 0L);
        long[] singleTransform = new long[7];
        long[] twoStage = new long[7];
        try {
            for (int index = 0; index < singleTransform.length; index++) {
                long started = SystemClock.elapsedRealtimeNanos();
                InferenceBitmapPreparer.Prepared prepared =
                        InferenceBitmapPreparer.prepareRegion(hardware, tile, 512);
                singleTransform[index] = SystemClock.elapsedRealtimeNanos() - started;
                assertNotNull(prepared);
                prepared.bitmap.recycle();

                started = SystemClock.elapsedRealtimeNanos();
                Bitmap alternative = prepareRegionTwoStage(hardware, tile, 512);
                twoStage[index] = SystemClock.elapsedRealtimeNanos() - started;
                assertNotNull(alternative);
                alternative.recycle();
            }
            Arrays.sort(singleTransform);
            Arrays.sort(twoStage);
            Log.i(TAG, "quality tile median singleTransform="
                    + singleTransform[singleTransform.length / 2] / 1_000_000f
                    + " ms, twoStage="
                    + twoStage[twoStage.length / 2] / 1_000_000f + " ms");
        } finally {
            hardware.recycle();
            software.recycle();
        }
    }

    private static Bitmap prepareRegionTwoStage(
            Bitmap source,
            QualityTilePlanner.Tile tile,
            int inferenceResolution) {
        Bitmap cropped = null;
        Bitmap scaled = null;
        Bitmap readable = null;
        try {
            int[] dimensions = InferenceBitmapPreparer.targetDimensions(
                    tile.width(), tile.height(), inferenceResolution);
            cropped = Bitmap.createBitmap(
                    source, tile.left(), tile.top(), tile.width(), tile.height());
            scaled = Bitmap.createScaledBitmap(
                    cropped, dimensions[0], dimensions[1], true);
            readable = scaled.getConfig() == Bitmap.Config.HARDWARE
                    ? scaled.copy(Bitmap.Config.ARGB_8888, false) : scaled;
            if (readable == scaled) scaled = null;
            return readable;
        } finally {
            if (scaled != null && scaled != cropped && !scaled.isRecycled()) scaled.recycle();
            if (cropped != null && cropped != source && !cropped.isRecycled()) cropped.recycle();
        }
    }
}
