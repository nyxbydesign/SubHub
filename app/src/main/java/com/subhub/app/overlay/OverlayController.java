package com.subhub.app.overlay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.WindowManager;

import com.subhub.app.detection.TrackedObject;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.RenderSourceReference;
import com.subhub.app.settings.CensorAppearance;

import java.util.List;

/** Owns the non-interactive system overlay used to render censor regions. */
public final class OverlayController implements AutoCloseable {
    private final WindowManager windowManager;
    private final CensorOverlayView view;
    private final int windowType;
    private boolean attached, windowAttempted;
    private volatile boolean closureStarted, closureFailed, windowClosed;
    private final LegacyRenderDependencies dependencies;

    public OverlayController(Context context) {
        this(context, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
    }

    public OverlayController(Context context, int windowType) {
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        dependencies = LegacyRenderDependencies.device(context);
        view = new CensorOverlayView(context, dependencies);
        this.windowType = windowType;
    }

    private OverlayController(Context context, int windowType, LegacyRenderDependencies dependencies) {
        dependencies.requireHost(context);
        this.dependencies = dependencies;
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        view = new CensorOverlayView(context, dependencies);
        this.windowType = windowType;
        // STARTUP construction, not a NORMAL command or a new adoption/ACK transaction.
        // Install before this controller can escape. CUSTOM only records artwork demand here.
        try {
            com.subhub.app.settings.SettingsRepository initial = com.subhub.app.settings.SettingsRepository
                    .forPreferences(dependencies.initialConfiguration().main);
            view.setAppearance(initial.loadAppearance());
            view.setMaxExtrapolationMs(initial.loadDetectorConfig().getMaxExtrapolationMs());
        } catch (RuntimeException | Error failure) {
            // The pre-registered attachment still owns dependencies/executor even if new fails.
            // Partial View/platform work cannot become a successful cleanup receipt by retry.
            try { dependencies.cleanupUncertain(); }
            catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            try { view.release(); }
            catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /** Hidden until exact NORMAL admission. Constructor belongs under the attachment's STARTUP gate. */
    public static OverlayController owned(Context physicalHost, int windowType, LegacyRenderDependencies dependencies) {
        return new OverlayController(physicalHost, windowType, dependencies);
    }

    public void show() {
        dependencies.command(() -> {
            ensureShown();
        });
    }

    /** Cache-identified snapshots retained for rendering; not compositor-visible pixel counts. */
    public int admittedCachedRegionCount(List<Detection> candidates) {
        return dependencies.value(() -> {
            return view.admittedCachedRegionCount(candidates);
        });
    }

    public void dumpRenderLayout(java.io.PrintWriter writer) {
        dependencies.command(() -> {
            view.dumpRenderLayout(writer);
        });
    }

    /** Render-only optional coverage; publish raw tracks first. Main-thread calls only. */
    public long beginPersonCoverage(List<Detection> selected, List<Detection> support,
            long capturedAt, long cameraX, long cameraY, int viewportWidth, int viewportHeight,
            RenderSourceReference reference) {
        return dependencies.value(() -> {
            return view.beginPersonCoverage(selected, support, capturedAt, cameraX, cameraY,
                    viewportWidth, viewportHeight, reference);
        });
    }

    public boolean refinePersonCoverage(long token,
            List<com.subhub.app.detection.PersonBox> people) {
        return dependencies.value(() -> {
            return view.refinePersonCoverage(token, people);
        });
    }

    public void clearPersonCoverage() {
        dependencies.command(() -> {
            view.clearPersonCoverage();
        });
    }

    /**
     * Re-attaches a window Android removed behind the controller's back.
     *
     * <p>Accessibility overlay tokens can be rebuilt while the target application recreates its
     * window. The service and detector remain alive in that case, so trusting only our boolean
     * leaves valid censor scenes rendering into a detached View forever.</p>
     *
     * @return true when a new WindowManager attachment was created.
     */
    public boolean ensureShown() {
        return dependencies.value(() -> {
            if (attached && view.isAttachedToWindow()) return false;
            if (attached) {
                try {
                    windowManager.removeViewImmediate(view);
                } catch (IllegalArgumentException ignored) {
                    // WindowManager already forgot this root; addView below is the recovery path.
                }
                attached = false;
            }
            WindowManager.LayoutParams params = createLayoutParams(windowType);
            windowAttempted = true;
            try { windowManager.addView(view, params); }
            catch (RuntimeException | Error uncertain) {
                if (dependencies.internal()) { closureFailed = true; dependencies.fail(); }
                throw uncertain;
            }
            attached = true;
            return true;
        });
    }

    static WindowManager.LayoutParams createLayoutParams(int windowType) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                windowType,
                flags,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        // Detector boxes use full-display coordinates. Android 11+ otherwise fits overlay windows
        // below system bars even with FLAG_LAYOUT_IN_SCREEN, shifting every censor down by the
        // status-bar height. Keep the overlay origin at the physical display's top-left instead.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) params.setFitInsetsTypes(0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        return params;
    }

    public void update(
            List<TrackedObject> tracks,
            int captureWidth,
            int captureHeight,
            Bitmap frame) {
        dependencies.command(() -> {
            update(tracks, captureWidth, captureHeight, frame, 0, 0);
        });
    }

    /**
     * Publishes a detector frame while compensating for scroll motion that happened after the
     * screenshot was requested. Motion is expressed in screen pixels, not detector coordinates.
     */
    public void update(
            List<TrackedObject> tracks,
            int captureWidth,
            int captureHeight,
            Bitmap frame,
            int motionX,
            int motionY) {
        dependencies.command(() -> {
            view.setTracks(tracks, captureWidth, captureHeight, frame, motionX, motionY);
        });
    }

    /**
     * Publishes live-coordinate tracks with independent motion for events received after the
     * inference snapshot and for the older source frame used by image-based effects.
     */
    public void update(
            List<TrackedObject> tracks,
            int captureWidth,
            int captureHeight,
            Bitmap frame,
            int motionX,
            int motionY,
            int sourceMotionX,
            int sourceMotionY) {
        dependencies.command(() -> {
            view.setTracks(tracks, captureWidth, captureHeight, frame,
                    motionX, motionY, sourceMotionX, sourceMotionY);
        });
    }

    /** Publishes Accessibility geometry in stable content coordinates. */
    public void updateWorld(
            List<TrackedObject> tracks,
            int captureWidth,
            int captureHeight,
            Bitmap frame,
            long trackCameraX,
            long trackCameraY,
            long sourceCameraX,
            long sourceCameraY,
            int viewportWidth,
            int viewportHeight) {
        dependencies.command(() -> {
            view.setWorldTracks(tracks, captureWidth, captureHeight, frame,
                    trackCameraX, trackCameraY, sourceCameraX, sourceCameraY,
                    viewportWidth, viewportHeight, null);
        });
    }

    /** Publishes live tracks plus render-only cached regions as one immutable display scene. */
    public void updateWorldWithCache(
            List<TrackedObject> tracks,
            List<Detection> cachedRegions,
            int captureWidth,
            int captureHeight,
            Bitmap frame,
            long trackCameraX,
            long trackCameraY,
            long sourceCameraX,
            long sourceCameraY,
            int viewportWidth,
            int viewportHeight) {
        dependencies.command(() -> {
            updateWorldWithCache(tracks, cachedRegions, captureWidth, captureHeight, frame,
                    trackCameraX, trackCameraY, sourceCameraX, sourceCameraY,
                    viewportWidth, viewportHeight, RenderSourceReference.UNKNOWN);
        });
    }

    public void updateWorldWithCache(List<TrackedObject> tracks, List<Detection> cachedRegions,
            int captureWidth, int captureHeight, Bitmap frame,
            long trackCameraX, long trackCameraY, long sourceCameraX, long sourceCameraY,
            int viewportWidth, int viewportHeight, RenderSourceReference bitmapReference) {
        dependencies.command(() -> {
            view.setWorldTracksAndCache(tracks, cachedRegions,
                    captureWidth, captureHeight, frame,
                    trackCameraX, trackCameraY, sourceCameraX, sourceCameraY,
                    viewportWidth, viewportHeight, null, bitmapReference);
        });
    }

    /** Re-queries scroll memory while preserving live geometry and the current source frame. */
    public void updateWorldCache(
            List<Detection> cachedRegions,
            int captureWidth,
            int captureHeight,
            long cacheCameraX,
            long cacheCameraY,
            int viewportWidth,
            int viewportHeight) {
        dependencies.command(() -> {
            view.setWorldCacheRegions(cachedRegions, captureWidth, captureHeight,
                    cacheCameraX, cacheCameraY, viewportWidth, viewportHeight);
        });
    }

    /** Applies one motion observation and its cache re-entry before the next display draw. */
    public void offsetContentWithWorldCache(
            int deltaX,
            int deltaY,
            boolean authoritative,
            long effectiveUptimeMillis,
            List<Detection> cachedRegions,
            int captureWidth,
            int captureHeight,
            long cacheCameraX,
            long cacheCameraY,
            int viewportWidth,
            int viewportHeight) {
        dependencies.command(() -> {
            view.offsetContent(deltaX, deltaY, authoritative, effectiveUptimeMillis);
            view.setWorldCacheRegions(cachedRegions, captureWidth, captureHeight,
                    cacheCameraX, cacheCameraY, viewportWidth, viewportHeight);
        });
    }

    /** Publishes a pooled source frame whose release returns it to capture instead of recycling. */
    public void updatePooledFrame(
            List<TrackedObject> tracks,
            int captureWidth,
            int captureHeight,
            Bitmap frame,
            Runnable frameRelease) {
        dependencies.command(() -> {
            view.setTracks(tracks, captureWidth, captureHeight, frame,
                    0, 0, 0, 0, frameRelease);
        });
    }

    /** Publishes confirmed settled coverage while preserving any retained effect bitmap. */
    public void updateTracksOnly(
            List<TrackedObject> tracks,
            int captureWidth,
            int captureHeight,
            int motionX,
            int motionY) {
        dependencies.command(() -> {
            view.setTracksPreservingFrame(
                    tracks, captureWidth, captureHeight, motionX, motionY);
        });
    }

    /** Publishes settled Accessibility coverage without changing the canonical camera. */
    public void updateWorldTracksOnly(
            List<TrackedObject> tracks,
            int captureWidth,
            int captureHeight,
            long trackCameraX,
            long trackCameraY,
            int viewportWidth,
            int viewportHeight) {
        dependencies.command(() -> {
            view.setWorldTracksPreservingFrame(tracks, captureWidth, captureHeight,
                    trackCameraX, trackCameraY, viewportWidth, viewportHeight);
        });
    }

    /** Moves the current lightweight overlay immediately while the next inference is pending. */
    public void setNativeScrollSpline(boolean enabled) {
        dependencies.command(() -> {
            view.setNativeScrollSpline(enabled);
        });
    }

    public void offsetContent(int deltaX, int deltaY) {
        dependencies.command(() -> {
            offsetContent(deltaX, deltaY, true);
        });
    }

    /** Moves content, presenting trustworthy viewport observations without interpolation lag. */
    public void offsetContent(int deltaX, int deltaY, boolean authoritative) {
        dependencies.command(() -> {
            view.offsetContent(deltaX, deltaY, authoritative);
        });
    }

    /**
     * Applies a viewport observation. Event time is retained by the capture timeline; the view
     * begins presentation only when this call reaches the UI thread.
     */
    public void offsetContent(
            int deltaX,
            int deltaY,
            boolean authoritative,
            long effectiveUptimeMillis) {
        dependencies.command(() -> {
            view.offsetContent(deltaX, deltaY, authoritative, effectiveUptimeMillis);
        });
    }

    /** Applies presentation-only motion sampled from a refreshable Accessibility node. */
    public void offsetPresentation(int deltaX, int deltaY) {
        dependencies.command(() -> {
            view.offsetPresentation(deltaX, deltaY);
        });
    }

    public boolean measureViewport(float screenOffsetX, float screenOffsetY, long readStart, long sourceMillis) {
        return dependencies.value(() -> {
            return view.measureViewport(screenOffsetX, screenOffsetY, readStart, sourceMillis);
        });
    }

    public boolean measureViewport(float screenOffsetX, float screenOffsetY,
            long readStart, long sourceMillis, RenderSourceReference.Origin origin) {
        return dependencies.value(() -> {
            return view.measureViewport(screenOffsetX, screenOffsetY, readStart, sourceMillis, origin);
        });
    }

    public void clearMeasuredViewport() {
        dependencies.command(() -> {
            view.clearMeasuredViewport();
        });
    }

    /** Publishes stabilized Accessibility/OCR geometry without waiting behind visual inference. */
    public void updateText(
            List<Detection> detections,
            int captureWidth,
            int captureHeight,
            int motionX,
            int motionY) {
        dependencies.command(() -> {
            view.setTextDetections(detections, captureWidth, captureHeight, motionX, motionY);
        });
    }

    /** Publishes Accessibility/OCR text in the same world space as visual tracks. */
    public void updateWorldText(
            List<Detection> detections,
            int captureWidth,
            int captureHeight,
            long cameraX,
            long cameraY,
            int viewportWidth,
            int viewportHeight) {
        dependencies.command(() -> {
            view.setWorldTextDetections(detections, captureWidth, captureHeight,
                    cameraX, cameraY, viewportWidth, viewportHeight);
        });
    }

    /** Immediately removes rendered content while keeping the lightweight window ready. */
    public void clear() {
        dependencies.command(() -> {
            view.clearContent();
        });
    }

    public void setAppearance(CensorAppearance appearance) {
        dependencies.command(() -> {
            if (dependencies.internal()) throw new IllegalStateException("Use exact owned configuration adoption");
            view.setAppearance(appearance);
        });
    }

    /** Retains packet provenance independently of whether a Lab recording is still active. */
    public void setTapSourceLabOwned(boolean labOwned) {
        dependencies.command(() -> {
            view.setTapSourceLabOwned(labOwned);
        });
    }

    /** A source packet accepted before a scope change cannot rearm billing when drawn late. */
    public void setTapSource(boolean labOwned, long sourceScope) {
        dependencies.command(() -> {
            view.setTapSource(labOwned, sourceScope);
        });
    }

    public void setDiagnostics(String text) {
        dependencies.command(() -> {
            view.setDiagnostics(text);
        });
    }

    /** Caps display-rate motion prediction after the most recent detector observation. */
    public void setMaxExtrapolationMs(float value) {
        dependencies.command(() -> {
            view.setMaxExtrapolationMs(value);
        });
    }

    /** Main, under the registered adoption receiver. Old output is fenced before ACK/reconcile. */
    public void adoptOwnedConfiguration(com.subhub.app.runtime.RuntimeConfiguration configuration) {
        dependencies.adopt(configuration);
        view.clearContent();
        com.subhub.app.settings.SettingsRepository settings =
                com.subhub.app.settings.SettingsRepository.forPreferences(configuration.main);
        view.setAppearance(settings.loadAppearance());
        view.setMaxExtrapolationMs(settings.loadDetectorConfig().getMaxExtrapolationMs());
    }
    public void acknowledgedConfiguration(com.subhub.app.runtime.RuntimeConfiguration configuration) {
        dependencies.acknowledged(configuration);
    }
    public boolean reconcileArtwork() { return dependencies.reconcileArtwork(view::postInvalidate); }
    /** Caller retains its exact-owner wake while false, including ordinary gate contention. */
    public boolean reconcileRendering() {
        dependencies.requireMain();
        return dependencies.tryNormal(() -> {
            if (dependencies.takeRenderDemand()) view.reconcileRendering();
        }) && !dependencies.needsRenderReconciliation();
    }
    public void seal() { dependencies.seal(); }
    /**
     * Managed CPU/work/window bookkeeping only. The containing attachment must independently
     * qualify and drain native/frame consumers before it can release the sole runtime owner.
     */
    public boolean managedResourcesDrained() {
        return closureStarted && !closureFailed && windowClosed && dependencies.managedResourcesDrained();
    }
    public boolean cleanupFailed() { return closureFailed || dependencies.cleanupFailed(); }

    @Override
    public void close() {
        dependencies.requireMain();
        if (closureStarted) return;
        closureStarted = true;
        dependencies.seal();
        try {
            if (attached || dependencies.internal() && windowAttempted) {
                try {
                    windowManager.removeViewImmediate(view);
                    attached = false;
                } catch (IllegalArgumentException missing) {
                    if (dependencies.internal()) closureFailed = true;
                    else attached = false; // Existing DEVICE missing-window behavior.
                } catch (RuntimeException | Error uncertain) {
                    closureFailed = true;
                }
            }
        } finally {
            // Independent cleanup, even if native window removal failed. No failure is a receipt.
            try { view.release(); }
            catch (RuntimeException | Error uncertain) { closureFailed = true; }
            finally {
                try { dependencies.close(); }
                catch (RuntimeException | Error uncertain) { closureFailed = true; }
            }
            windowClosed = !attached && !view.isAttachedToWindow();
        }
        if (closureFailed) throw new IllegalStateException("Legacy renderer closure is uncertain");
    }
}
