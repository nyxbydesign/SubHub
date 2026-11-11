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

/** Keeps decoded custom images and center-crop geometry stable per tracked object. */
public final class CustomImagePool implements AutoCloseable {
    private static final int ASPECT_BUCKETS = 129;
    private static final float MIN_ASPECT = 0.25f;
    private static final float MAX_ASPECT = 4f;

    /** Immutable load descriptor. The caller owns admission; successful output transfers here. */
    public interface ImageLoad { List<Bitmap> load(java.util.function.BooleanSupplier obsolete); }
    /** Only an exact pre-invocation admission refusal may use this signal; no work has run. */
    public static final class Deferred extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public Deferred() { super("Artwork admission deferred"); }
    }

    private final Legacy device;
    private final State<PreparedImage> state;
    private volatile boolean abandonSubmitted;

    public CustomImagePool(Context context) { device = new Legacy(context); state = null; }
    /** Preserves the existing export constructor and its DEVICE dependency behavior. */
    public CustomImagePool(Context context, List<Bitmap> frozenImages) {
        device = new Legacy(context, frozenImages); state = null;
    }
    private CustomImagePool(ExecutorService ownedLoader) {
        device = null;
        state = new State<>(java.util.Objects.requireNonNull(ownedLoader), image -> { if (!abandonSubmitted) image.release(); });
    }
    /** No Context, SettingsRepository, physical image library or implicit executor is constructed. */
    public static CustomImagePool owned(ExecutorService ownedLoader) { return new CustomImagePool(ownedLoader); }

    public boolean isEmpty() { return device == null ? state.isEmpty() : device.isEmpty(); }
    public void reload() { requireDevice(); device.reload(); }
    public void reloadAsync(Runnable readyCallback) { requireDevice(); device.reloadAsync(readyCallback); }
    /** Called only after the exact immutable configuration's accounting acknowledgement. */
    public void loadOwnedAsync(ImageLoad load, Runnable readyCallback) {
        if (state == null) throw new IllegalStateException("Explicit owned pool required");
        java.util.Objects.requireNonNull(load);
        state.request(obsolete -> prepare(load.load(obsolete)), readyCallback);
    }
    private void requireDevice() {
        if (device == null) throw new IllegalStateException("Owned artwork requires an explicit immutable load");
    }
    /** Existing synchronous export API only. Live/owned drawing uses draw(). */
    public PreparedImage imageFor(int trackId) { requireDevice(); return device.imageFor(trackId); }

    /** A consistent image set remains borrowed until the entire actual draw body returns. */
    public void draw(java.util.function.Consumer<Drawing> draw) {
        java.util.Objects.requireNonNull(draw);
        if (device != null) {
            // The old DEVICE loader already publishes/recycles under this monitor. Retain it for
            // the full draw, rather than exposing a bitmap while a worker replaces its set.
            synchronized (device) { draw.accept(new Drawing(null)); }
        } else try (State<PreparedImage>.Read read = state.borrow()) { draw.accept(new Drawing(read)); }
    }
    public final class Drawing {
        private final State<PreparedImage>.Read read;
        private Drawing(State<PreparedImage>.Read read) { this.read = read; }
        /** This object and its images may not escape the synchronous draw() scope. */
        public PreparedImage imageFor(int stableId) { return read == null ? device.imageFor(stableId) : read.image(stableId); }
    }
    public void retainAssignments(Set<Integer> activeIds) {
        if (device == null) state.retainAssignments(activeIds); else device.retainAssignments(activeIds);
    }
    public void invalidate() {
        if (state == null) throw new IllegalStateException("Explicit owned artwork required");
        state.invalidate();
    }
    /** Obsoletes queued/in-flight output without retiring the last published DEVICE image set. */
    public void supersedePending() {
        if (state == null) throw new IllegalStateException("Explicit owned artwork required");
        state.supersedePending();
    }
    /** Terminal managed ownership only: drop old references, never recycle/reissue View inputs. */
    public void abandonSubmittedImages() {
        if (state == null) throw new IllegalStateException("Explicit owned artwork required");
        abandonSubmitted = true;
    }
    public boolean pending() { return state != null && state.pending(); }
    public boolean needsRetry() { return state != null && state.needsRetry(); }
    public boolean cleanupFailed() { return state != null && state.failed(); }
    /** The legacy DEVICE constructor has no native drain contract and must never supply a receipt. */
    public boolean managedResourcesDrained() { return state != null && state.drained(); }
    @Override public void close() { if (device == null) state.close(); else device.close(); }

    private static List<PreparedImage> prepare(List<Bitmap> decoded) {
        java.util.Objects.requireNonNull(decoded);
        List<PreparedImage> result = new ArrayList<>(decoded.size());
        try {
            for (Bitmap image : decoded) result.add(new PreparedImage(java.util.Objects.requireNonNull(image)));
            return result;
        } catch (RuntimeException | Error failure) {
            for (Bitmap image : decoded) {
                try { if (image != null && !image.isRecycled()) image.recycle(); }
                catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    /** Unbound DEVICE/export compatibility path. It is never an owned drain receipt. */
    private static final class Legacy implements AutoCloseable {
        private final CustomImageManager manager;
        private final SettingsRepository settings;
        private final ExecutorService loader = Executors.newSingleThreadExecutor();
        private final java.util.concurrent.atomic.AtomicInteger generation = new java.util.concurrent.atomic.AtomicInteger();
        private final Map<Integer, Integer> assignments = new HashMap<>();
        private List<PreparedImage> images = new ArrayList<>();
        private boolean closed;

        Legacy(Context context) {
            manager = new CustomImageManager(context);
            settings = new SettingsRepository(context);
        }

        /** Takes ownership of job-local bitmaps; no live image-store reads occur. */
        Legacy(Context context, List<Bitmap> frozenImages) {
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
                synchronized (Legacy.this) {
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
    }

    /** Pure lifetime controller. One accepted runner and one replaceable descriptor, never cancellation. */
    static final class State<T> implements AutoCloseable {
        interface Load<T> { List<T> load(java.util.function.BooleanSupplier obsolete); }
        private static final class Request<T> {
            final long revision; final Load<T> load; final Runnable ready; final boolean synchronous;
            Request(long revision, Load<T> load, Runnable ready, boolean synchronous) {
                this.revision = revision; this.load = load; this.ready = ready; this.synchronous = synchronous;
            }
        }
        private static final class Batch<T> {
            final List<T> images; final Map<Integer, Integer> assignments = new HashMap<>();
            int readers; boolean retired, disposing;
            Batch(List<T> images) { this.images = java.util.Collections.unmodifiableList(new ArrayList<>(images)); }
            T image(int id) {
                if (images.isEmpty()) return null;
                int index = assignments.computeIfAbsent(id, key -> Math.floorMod(key, images.size()));
                return images.get(index);
            }
        }
        private static final class Task {
            final java.util.concurrent.atomic.AtomicBoolean entered = new java.util.concurrent.atomic.AtomicBoolean();
            boolean submissionReturned, bodyReturned;
        }
        private final ExecutorService loader;
        private final java.util.function.Consumer<T> dispose;
        private final java.util.Set<Batch<T>> batches = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        private Batch<T> current;
        private Request<T> pending;
        private Task task;
        private long revision;
        private boolean closed, failed, retry;
        State(ExecutorService loader, java.util.function.Consumer<T> dispose) {
            this.loader = java.util.Objects.requireNonNull(loader); this.dispose = java.util.Objects.requireNonNull(dispose);
        }
        synchronized void seed(List<T> values) {
            if (closed || current != null || task != null) throw new IllegalStateException("Pool already used");
            current = new Batch<>(values); batches.add(current);
        }
        synchronized boolean isEmpty() { return current == null || current.images.isEmpty(); }
        synchronized boolean pending() { return pending != null || task != null; }
        synchronized boolean needsRetry() { return retry; }
        synchronized boolean failed() { return failed; }
        synchronized boolean drained() {
            return closed && !failed && task == null && batches.isEmpty() && loader.isTerminated();
        }
        synchronized T unleased(int id) { return closed || current == null ? null : current.image(id); }
        synchronized void retainAssignments(Set<Integer> active) {
            if (current != null) current.assignments.keySet().retainAll(active);
        }
        void request(Load<T> load, Runnable ready) { submit(load, ready, false); }
        void loadNow(Load<T> load, Runnable ready) { submit(load, ready, true); }
        private void submit(Load<T> load, Runnable ready, boolean inline) {
            java.util.Objects.requireNonNull(load); Task dispatch;
            synchronized (this) {
                if (closed) return;
                if (inline && (task != null || blockedByBorrow()))
                    throw new IllegalStateException("Synchronous load while work/borrow is active");
                if (revision == Long.MAX_VALUE) throw new IllegalStateException("Artwork revision exhausted");
                pending = new Request<>(++revision, load, ready, inline); retry = false;
                dispatch = reserve();
            }
            if (dispatch != null) launch(dispatch, inline);
        }
        private boolean blockedByBorrow() {
            for (Batch<T> batch : batches) if (batch != current) return true;
            return false;
        }
        private Task reserve() {
            if (closed || failed || task != null || pending == null || blockedByBorrow()) return null;
            return task = new Task(); // Count before execute, including enqueue-then-throw.
        }
        private void launch(Task dispatch, boolean inline) {
            try {
                if (inline) run(dispatch); else loader.execute(() -> run(dispatch));
            } finally { settled(dispatch, true); }
        }
        private void settled(Task dispatch, boolean submission) {
            Task next;
            synchronized (this) {
                if (submission) dispatch.submissionReturned = true; else dispatch.bodyReturned = true;
                if (task != dispatch || !dispatch.bodyReturned || !dispatch.submissionReturned) return;
                task = null; next = reserve();
            }
            if (next != null) launch(next, false);
        }
        private void run(Task dispatch) {
            if (!dispatch.entered.compareAndSet(false, true)) return;
            try {
                while (true) {
                    Request<T> request;
                    synchronized (this) {
                        if (closed || failed || blockedByBorrow()) return;
                        request = pending; pending = null;
                        if (request == null) return;
                    }
                    Batch<T> prepared = null;
                    try { prepared = new Batch<>(request.load.load(() -> obsolete(request.revision))); }
                    catch (Deferred refusedBeforeWork) {
                        synchronized (this) { if (!closed && revision == request.revision) retry = true; }
                    } catch (RuntimeException | Error uncertainPreparation) {
                        // A throwing decoder may have created native outputs without returning them.
                        synchronized (this) { failed = true; }
                        if (request.synchronous) throw uncertainPreparation;
                    }
                    Batch<T> retired = null; boolean accepted = false;
                    if (prepared != null) {
                        synchronized (this) {
                            batches.add(prepared);
                            if (!closed && revision == request.revision && !failed) {
                                retired = current; current = prepared; accepted = true;
                                if (retired != null) retired.retired = true;
                            } else prepared.retired = true;
                        }
                        releaseIfUnused(retired); if (!accepted) releaseIfUnused(prepared);
                    }
                    if (accepted && request.ready != null) {
                        // This actual worker callback return is part of the same counted runner.
                        try { request.ready.run(); }
                        catch (RuntimeException | Error callbackFailure) { synchronized (this) { failed = true; } }
                    }
                }
            } finally { settled(dispatch, false); }
        }
        private synchronized boolean obsolete(long expected) { return closed || revision != expected; }
        final class Read implements AutoCloseable {
            private final Batch<T> batch;
            private boolean returned;
            Read(Batch<T> batch) { this.batch = batch; }
            T image(int id) {
                synchronized (State.this) {
                    if (returned) throw new IllegalStateException("Artwork draw borrow returned");
                    return batch == null ? null : batch.image(id);
                }
            }
            @Override public void close() {
                synchronized (State.this) {
                    if (returned) return;
                    returned = true; if (batch != null) batch.readers--;
                }
                releaseIfUnused(batch);
            }
        }
        synchronized Read borrow() {
            Batch<T> borrowed = closed ? null : current;
            if (borrowed != null) borrowed.readers++;
            return new Read(borrowed);
        }
        boolean read(int id, java.util.function.Consumer<T> reader) {
            java.util.Objects.requireNonNull(reader);
            try (Read borrowed = borrow()) {
                T image = borrowed.image(id); if (image == null) return false;
                reader.accept(image); return true;
            }
        }
        synchronized void supersedePending() {
            if (closed) return;
            if (revision == Long.MAX_VALUE) { failed = true; throw new IllegalStateException("Artwork revision exhausted"); }
            // Publication uses this same monitor/revision. Keep current and all actual borrowers;
            // the old runner remains counted and disposes any superseded output on return.
            revision++; pending = null; retry = false;
        }
        void invalidate() {
            Batch<T> retired;
            synchronized (this) {
                if (revision == Long.MAX_VALUE) { failed = true; throw new IllegalStateException("Artwork revision exhausted"); }
                revision++; pending = null; retry = false; retired = current; current = null;
                if (retired != null) retired.retired = true;
            }
            releaseIfUnused(retired);
        }
        @Override public void close() {
            Batch<T> retired;
            synchronized (this) {
                if (closed) return;
                closed = true; pending = null; retry = false; retired = current; current = null;
                if (retired != null) retired.retired = true;
            }
            try { releaseIfUnused(retired); }
            finally {
                try { loader.shutdown(); }
                catch (RuntimeException | Error uncertainShutdown) { synchronized (this) { failed = true; } }
            }
        }
        private void releaseIfUnused(Batch<T> batch) {
            if (batch == null) return;
            synchronized (this) {
                if (!batch.retired || batch.readers != 0 || batch.disposing) return;
                batch.disposing = true;
            }
            boolean released = true;
            for (T image : batch.images) {
                try { dispose.accept(image); }
                catch (RuntimeException | Error uncertain) { released = false; }
            }
            Task next;
            synchronized (this) {
                if (released) batches.remove(batch); else failed = true;
                next = reserve();
            }
            if (next != null) launch(next, false);
        }
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
