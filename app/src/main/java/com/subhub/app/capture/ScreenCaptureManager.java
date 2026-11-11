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
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/** Captures scaled RGBA frames from a user-approved MediaProjection session. */
public final class ScreenCaptureManager implements AutoCloseable {
    interface DisplaySession { void surface(Surface surface); void release(); }
    interface DisplayFactory { DisplaySession create(int width, int height, int densityDpi, Surface surface); }
    private final DisplayFactory displays;
    private final int densityDpi;
    private final int captureWidth;
    private final int captureHeight;
    private ImageReader imageReader;
    private DisplaySession virtualDisplay;
    private Bitmap paddedBitmap;
    private final Canvas cropCanvas = new Canvas();
    private final Rect cropSource = new Rect();
    private final Rect cropDestination = new Rect();
    private final ArrayDeque<Bitmap> framePool = new ArrayDeque<>();
    private boolean closed;
    private boolean paused;
    private volatile boolean submittedFramesSealed;
    private boolean terminalCloseAttempted;
    private RuntimeException terminalCloseFailure;
    private volatile boolean terminalResourcesDrained;

    public ScreenCaptureManager(
            MediaProjection projection,
            int screenWidth,
            int screenHeight,
            int densityDpi,
            int inferenceResolution,
            float captureScale) {
        this(screenWidth, screenHeight, densityDpi, inferenceResolution, captureScale, (width, height, density, surface) -> {
            VirtualDisplay display = projection.createVirtualDisplay("SubHubCapture", width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null);
            return new DisplaySession() {
                @Override public void surface(Surface value) { display.setSurface(value); }
                @Override public void release() { display.release(); }
            };
        });
    }

    /** Native ImageReader with an explicit display boundary for lifecycle contract fixtures. */
    ScreenCaptureManager(int screenWidth, int screenHeight, int densityDpi, int inferenceResolution,
            float captureScale, DisplayFactory displays) {
        this.displays = java.util.Objects.requireNonNull(displays);
        this.densityDpi = densityDpi;
        int maximum = Math.max(screenWidth, screenHeight);
        int targetMaximum = Math.min(
                maximum,
                Math.max(inferenceResolution, Math.round(maximum * captureScale)));
        float scale = (float) targetMaximum / maximum;
        captureWidth = Math.max(32, (int) (screenWidth * scale));
        captureHeight = Math.max(32, (int) (screenHeight * scale));
    }

    public void start() { start(false); }

    public synchronized void start(boolean initiallyPaused) {
        if (submittedFramesSealed) throw new IllegalStateException("Managed capture owner is sealed");
        if (virtualDisplay != null) return;
        if (closed) throw new IllegalStateException("Capture display is closed");
        closed = false;
        paused = initiallyPaused;
        if (!paused) imageReader = newReader();
        virtualDisplay = displays.create(captureWidth, captureHeight, densityDpi, paused ? null : imageReader.getSurface());
    }

    /** Retain the single projection/display grant while removing its active output surface. */
    public synchronized void pauseSurface(boolean value) {
        if (submittedFramesSealed || closed || virtualDisplay == null || paused == value) return;
        paused = value;
        if (value) {
            try { virtualDisplay.surface(null); }
            finally {
                // A late GPU buffer belongs to the old queue, which must never enter the resumed scope.
                if (imageReader != null) imageReader.close();
                imageReader = null;
            }
        } else {
            imageReader = newReader();
            try { virtualDisplay.surface(imageReader.getSurface()); }
            catch (RuntimeException failure) {
                paused = true; imageReader.close(); imageReader = null;
                throw failure;
            }
        }
    }

    private ImageReader newReader() {
        return ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2);
    }

    public synchronized Bitmap acquireLatestFrame() {
        if (submittedFramesSealed || imageReader == null || paused || closed) return null;
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
        if (submittedFramesSealed || frame == null || frame.isRecycled()) return;
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

    /**
     * Irreversible managed terminal fence; nonblocking even while an admitted capture is copying.
     * Future acquisitions/reissues are denied. Already admitted work still needs its actual return.
     * Pool-resident frames may have been submitted to a hardware View, so terminal retirement only
     * drops their references. This is not native/GPU drain and never permits successor pool reuse.
     */
    public void sealSubmittedFrames() { submittedFramesSealed = true; }
    /** Requires successful managed display/reader close, not just seal or garbage collection. */
    public boolean managedResourcesDrained() { return terminalResourcesDrained; }

    @Override
    public synchronized void close() {
        if (submittedFramesSealed) { closeManaged(); return; }
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
    private void closeManaged() {
        if (terminalCloseAttempted) {
            if (terminalCloseFailure != null) throw terminalCloseFailure;
            return;
        }
        terminalCloseAttempted = true; closed = true;
        RuntimeException failure = null;
        try { if (virtualDisplay != null) virtualDisplay.release(); virtualDisplay = null; }
        catch (RuntimeException | Error uncertain) { failure = rememberFailure(failure, uncertain); }
        try { if (imageReader != null) imageReader.close(); imageReader = null; }
        catch (RuntimeException | Error uncertain) { failure = rememberFailure(failure, uncertain); }
        // Crop scratch was never submitted to a View; actual CPU borrows must have returned first.
        try { if (paddedBitmap != null) paddedBitmap.recycle(); paddedBitmap = null; }
        catch (RuntimeException | Error uncertain) { failure = rememberFailure(failure, uncertain); }
        try { cropCanvas.setBitmap(null); }
        catch (RuntimeException | Error uncertain) { failure = rememberFailure(failure, uncertain); }
        framePool.clear(); // Reference-only for every potentially submitted terminal-owner frame.
        terminalCloseFailure = failure;
        if (failure != null) throw failure;
        terminalResourcesDrained = true;
    }
    private static RuntimeException rememberFailure(RuntimeException previous, Throwable cause) {
        if (previous == null) return new IllegalStateException("Managed projection source cleanup uncertain", cause);
        previous.addSuppressed(cause); return previous;
    }

}
