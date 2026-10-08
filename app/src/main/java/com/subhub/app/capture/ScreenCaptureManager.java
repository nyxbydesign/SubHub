package com.subhub.app.capture;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/** Captures scaled RGBA frames from a user-approved MediaProjection session. */
public final class ScreenCaptureManager implements AutoCloseable {
    private final MediaProjection projection;
    private final int densityDpi;
    private final int captureWidth;
    private final int captureHeight;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;
    private Bitmap paddedBitmap;
    private final Canvas cropCanvas = new Canvas();
    private final Rect cropSource = new Rect();
    private final Rect cropDestination = new Rect();
    private final ArrayDeque<Bitmap> framePool = new ArrayDeque<>();
    private boolean closed;

    public ScreenCaptureManager(
            MediaProjection projection,
            int screenWidth,
            int screenHeight,
            int densityDpi,
            int inferenceResolution,
            float captureScale) {
        this.projection = projection;
        this.densityDpi = densityDpi;
        int maximum = Math.max(screenWidth, screenHeight);
        int targetMaximum = Math.min(
                maximum,
                Math.max(inferenceResolution, Math.round(maximum * captureScale)));
        float scale = (float) targetMaximum / maximum;
        captureWidth = Math.max(32, (int) (screenWidth * scale));
        captureHeight = Math.max(32, (int) (screenHeight * scale));
    }

    public void start() {
        if (virtualDisplay != null) return;
        closed = false;
        imageReader = ImageReader.newInstance(
                captureWidth, captureHeight, PixelFormat.RGBA_8888, 2);
        virtualDisplay = projection.createVirtualDisplay(
                "SubHubCapture",
                captureWidth,
                captureHeight,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                null);
    }

    public Bitmap acquireLatestFrame() {
        if (imageReader == null) return null;
        try (Image image = imageReader.acquireLatestImage()) {
            if (image == null) return null;
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int paddedWidth = captureWidth + (rowStride - pixelStride * captureWidth) / pixelStride;
            buffer.rewind();
            Bitmap output = obtainFrame();
            if (paddedWidth == captureWidth) {
                output.copyPixelsFromBuffer(buffer);
                return output;
            }
            if (paddedBitmap == null
                    || paddedBitmap.getWidth() != paddedWidth
                    || paddedBitmap.getHeight() != captureHeight) {
                if (paddedBitmap != null) paddedBitmap.recycle();
                paddedBitmap = Bitmap.createBitmap(
                        paddedWidth, captureHeight, Bitmap.Config.ARGB_8888);
            }
            paddedBitmap.copyPixelsFromBuffer(buffer);
            cropCanvas.setBitmap(output);
            cropSource.set(0, 0, captureWidth, captureHeight);
            cropDestination.set(0, 0, captureWidth, captureHeight);
            cropCanvas.drawBitmap(paddedBitmap, cropSource, cropDestination, null);
            cropCanvas.setBitmap(null);
            return output;
        }
    }

    /** Returns a processed frame to the bounded capture pool instead of allocating next tick. */
    public synchronized void releaseFrame(Bitmap frame) {
        if (frame == null || frame.isRecycled()) return;
        if (closed || framePool.size() >= 3
                || frame.getWidth() != captureWidth || frame.getHeight() != captureHeight) {
            frame.recycle();
            return;
        }
        framePool.addLast(frame);
    }

    private synchronized Bitmap obtainFrame() {
        Bitmap frame = framePool.pollFirst();
        if (frame != null && !frame.isRecycled()) return frame;
        return Bitmap.createBitmap(captureWidth, captureHeight, Bitmap.Config.ARGB_8888);
    }

    public int getCaptureWidth() { return captureWidth; }
    public int getCaptureHeight() { return captureHeight; }

    @Override
    public void close() {
        closed = true;
        if (virtualDisplay != null) virtualDisplay.release();
        virtualDisplay = null;
        if (imageReader != null) imageReader.close();
        imageReader = null;
        if (paddedBitmap != null) paddedBitmap.recycle();
        paddedBitmap = null;
        cropCanvas.setBitmap(null);
        synchronized (this) {
            while (!framePool.isEmpty()) {
                Bitmap frame = framePool.removeFirst();
                if (!frame.isRecycled()) frame.recycle();
            }
        }
    }
}
