package com.subhub.app.capture;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import com.subhub.app.detection.DetectionPreset;
import com.subhub.app.settings.SettingsRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Keeps decoded custom images and center-crop geometry stable per tracked object. */
public final class CustomImagePool implements AutoCloseable {
    private static final int ASPECT_BUCKETS = 129;
    private static final float MIN_ASPECT = 0.25f;
    private static final float MAX_ASPECT = 4f;

    private final CustomImageManager manager;
    private final SettingsRepository settings;
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger();
    private final Map<Integer, Integer> assignments = new HashMap<>();
    private List<PreparedImage> images = new ArrayList<>();
    private boolean closed;

    public CustomImagePool(Context context) {
        manager = new CustomImageManager(context);
        settings = new SettingsRepository(context);
    }

    /** Takes ownership of job-local bitmaps; no live image-store reads occur. */
    public CustomImagePool(Context context, List<Bitmap> frozenImages) {
        this(context);
        for (Bitmap image : frozenImages) images.add(new PreparedImage(image));
    }

    public synchronized boolean isEmpty() { return images.isEmpty(); }

    /** Synchronous load for one-shot export rendering, which already runs off the UI thread. */
    public synchronized void reload() {
        int ticket = generation.incrementAndGet();
        List<PreparedImage> prepared = loadPrepared(settings.loadDetectionPreset());
        if (closed || ticket != generation.get()) {
            release(prepared);
            return;
        }
        releaseImages();
        images = prepared;
        assignments.clear();
    }

    /** Decodes and precomputes away from the overlay/UI thread, retaining the old set until ready. */
    public void reloadAsync(Runnable readyCallback) {
        int ticket = generation.incrementAndGet();
        DetectionPreset preset = settings.loadDetectionPreset();
        loader.execute(() -> {
            List<PreparedImage> prepared = loadPrepared(preset);
            boolean accepted;
            synchronized (CustomImagePool.this) {
                accepted = !closed && ticket == generation.get();
                if (accepted) {
                    releaseImages();
                    images = prepared;
                    assignments.clear();
                }
            }
            if (!accepted) release(prepared);
            else if (readyCallback != null) readyCallback.run();
        });
    }

    public synchronized PreparedImage imageFor(int trackId) {
        if (images.isEmpty()) return null;
        Integer index = assignments.get(trackId);
        if (index == null || index >= images.size()) {
            index = Math.floorMod(trackId, images.size());
            assignments.put(trackId, index);
        }
        return images.get(index);
    }

    public synchronized void retainAssignments(Set<Integer> activeIds) {
        assignments.keySet().retainAll(activeIds);
    }

    @Override
    public synchronized void close() {
        closed = true;
        generation.incrementAndGet();
        loader.shutdownNow();
        releaseImages();
        assignments.clear();
    }

    private List<PreparedImage> loadPrepared(DetectionPreset preset) {
        List<Bitmap> decoded = manager.loadEnabledBitmaps(
                preset.getCustomImageDimension(), preset.getCustomImageCount());
        List<PreparedImage> prepared = new ArrayList<>(decoded.size());
        for (Bitmap bitmap : decoded) prepared.add(new PreparedImage(bitmap));
        return prepared;
    }

    private void releaseImages() {
        release(images);
        images = new ArrayList<>();
    }

    private static void release(List<PreparedImage> values) {
        for (PreparedImage image : values) image.release();
    }

    /** Immutable precomputed crop table; live draws only select a bucket and issue drawBitmap. */
    public static final class PreparedImage {
        private final Bitmap bitmap;
        private final Rect[] centerCrops = new Rect[ASPECT_BUCKETS];

        PreparedImage(Bitmap bitmap) {
            this.bitmap = bitmap;
            for (int index = 0; index < ASPECT_BUCKETS; index++) {
                float fraction = index / (float) (ASPECT_BUCKETS - 1);
                float aspect = (float) (MIN_ASPECT
                        * Math.pow(MAX_ASPECT / MIN_ASPECT, fraction));
                centerCrops[index] = centerCrop(bitmap, aspect);
            }
        }

        public Bitmap bitmap() { return bitmap; }

        public Rect cropFor(float requestedAspect) {
            float aspect = Math.max(MIN_ASPECT, Math.min(MAX_ASPECT, requestedAspect));
            double fraction = Math.log(aspect / MIN_ASPECT) / Math.log(MAX_ASPECT / MIN_ASPECT);
            int bucket = Math.max(0, Math.min(ASPECT_BUCKETS - 1,
                    Math.round((float) fraction * (ASPECT_BUCKETS - 1))));
            return centerCrops[bucket];
        }

        private void release() {
            if (!bitmap.isRecycled()) bitmap.recycle();
        }

        private static Rect centerCrop(Bitmap bitmap, float targetRatio) {
            float sourceRatio = (float) bitmap.getWidth() / bitmap.getHeight();
            if (sourceRatio > targetRatio) {
                int width = Math.max(1, Math.round(bitmap.getHeight() * targetRatio));
                int left = (bitmap.getWidth() - width) / 2;
                return new Rect(left, 0, left + width, bitmap.getHeight());
            }
            int height = Math.max(1, Math.round(bitmap.getWidth() / targetRatio));
            int top = (bitmap.getHeight() - height) / 2;
            return new Rect(0, top, bitmap.getWidth(), top + height);
        }
    }
}
