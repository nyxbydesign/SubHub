package com.subhub.app.util;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import com.subhub.app.R;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Scoped remote images only. Missing remote images never query this device's package catalog. */
public final class RemoteAppIcons implements AutoCloseable {
    /** A display projection of existing authority, never a new grant or persistence mechanism. */
    public static final class Authority {
        public static final Authority EMPTY = new Authority(Collections.emptySet());
        private final Set<RemoteAppIcon> current;

        public Authority(Collection<RemoteAppIcon> current) {
            Set<RemoteAppIcon> copy = new HashSet<>();
            for (RemoteAppIcon icon : Objects.requireNonNull(current)) copy.add(Objects.requireNonNull(icon));
            this.current = Collections.unmodifiableSet(copy);
        }

        public boolean permits(RemoteAppIcon identity) { return current.contains(identity); }
    }

    public interface Provider {
        /** Worker-only, bounded PNG bytes for this complete identity; null means unavailable/offline. */
        byte[] load(RemoteAppIcon identity) throws Exception;

        /** Cheap immutable snapshot read, e.g. a volatile reference. No I/O, SQLite, Binder or UI locks. */
        Authority currentAuthority();

        /** Memory-only subscription; notify after publishing changes/revocation, on any thread. */
        void addInvalidationListener(Runnable listener);
        void removeInvalidationListener(Runnable listener);
    }

    static final int MAX_PENDING = 64, MAX_BINDINGS = 256, MAX_MISSING = 128, CACHE_BYTES = 4 * 1024 * 1024;
    private static final long MISSING_MILLIS = 30_000;
    private final Provider provider;
    private final Executor worker;
    private final boolean ownsWorker;
    private final Decoder decoder;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicLong invalidations = new AtomicLong();
    private final AtomicBoolean invalidationPosted = new AtomicBoolean();
    private final AtomicBoolean completionPosted = new AtomicBoolean();
    private long observedInvalidation;
    private volatile boolean closed;
    private final Runnable invalidated = () -> {
        invalidations.incrementAndGet();
        if (invalidationPosted.compareAndSet(false, true)) main.post(() -> {
            invalidationPosted.set(false); reconcile();
        });
    };
    private final LruCache<Key, Bitmap> cache = new LruCache<Key, Bitmap>(CACHE_BYTES) {
        @Override protected int sizeOf(Key key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private final LruCache<Key, Long> missing = new LruCache<>(MAX_MISSING);
    private final Map<Key, Job> pending = new LinkedHashMap<>();
    private final Set<Binding> bindings = new LinkedHashSet<>();

    public RemoteAppIcons(Provider provider) { this(provider, workers(), true, RemoteAppIcons::decode); }

    interface Decoder { Bitmap decode(RemoteAppIcon identity, byte[] bytes, int pixels); }
    RemoteAppIcons(Provider provider, Executor worker) { this(provider, worker, false, RemoteAppIcons::decode); }
    RemoteAppIcons(Provider provider, Executor worker, Decoder decoder) { this(provider, worker, false, decoder); }
    private RemoteAppIcons(Provider provider, Executor worker, boolean ownsWorker, Decoder decoder) {
        requireMain();
        this.provider = Objects.requireNonNull(provider); this.worker = Objects.requireNonNull(worker);
        this.ownsWorker = ownsWorker; this.decoder = Objects.requireNonNull(decoder);
        provider.addInvalidationListener(invalidated);
    }

    private static Executor workers() {
        return new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(MAX_PENDING), task -> {
            Thread thread = new Thread(task, "subhub-remote-app-icons"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    /** Main-thread binding; the caller retains peer/generation/package even for a null descriptor. */
    public void bind(ImageView view, RemoteAppIcon identity, int fallback) {
        requireMain();
        if (closed) throw new IllegalStateException("Remote icon binder is closed");
        Objects.requireNonNull(view); Objects.requireNonNull(identity);
        reconcile();
        pruneBindings();
        int pixels = Math.max(48, Math.min(RemoteAppIcon.MAX_PIXELS,
                Math.round(48 * view.getResources().getDisplayMetrics().density)));
        Key key = new Key(identity, pixels);
        Binding old = binding(view);
        Long absent = missing.get(key);
        if (old != null && old.engine == this && !old.detached && !old.cancelled && old.key.equals(key)
                && old.fallback == fallback && current(identity)
                && (old.resolved || identity.descriptor == null || pending.containsKey(key)
                        || absent != null && SystemClock.uptimeMillis() - absent < MISSING_MILLIS)) return;
        Binding token = new Binding(this, view, key, fallback);
        AppIcons.replaceRequest(view, token);
        while (bindings.size() >= MAX_BINDINGS) {
            Binding oldest = bindings.iterator().next();
            ImageView oldView = oldest.view.get();
            if (oldView != null && oldView.getTag(R.id.app_icon_request) == oldest) placeholder(oldView, oldest.fallback);
            oldest.cancel(oldView);
        }
        bindings.add(token);
        placeholder(view, fallback);
        if (!token.detached) resolve(token);
    }

    private void resolve(Binding token) {
        if (!current(token.key.identity) || token.key.identity.descriptor == null) return;
        ImageView view = token.view.get();
        if (view == null || !eligible(view, token, token.key)) return;
        Bitmap hit = cache.get(token.key);
        if (hit != null) { apply(view, token, token.key, hit); return; }
        Long absent = missing.get(token.key);
        if (absent != null && SystemClock.uptimeMillis() - absent < MISSING_MILLIS) return;
        Job job = pending.get(token.key);
        if (job != null) { job.waiters.add(new Waiting(view, token)); return; }
        if (pending.size() >= MAX_PENDING) return;
        job = new Job(token.key, invalidations.get());
        job.waiters.add(new Waiting(view, token)); pending.put(token.key, job);
        Job request = job;
        try { worker.execute(() -> load(request)); }
        catch (RejectedExecutionException busy) { pending.remove(token.key); request.cancelled = true; }
    }

    private void load(Job job) {
        Bitmap bitmap = null;
        try {
            if (!job.cancelled && !closed && job.invalidation == invalidations.get() && current(job.key.identity)) {
                byte[] supplied = provider.load(job.key.identity);
                RemoteAppIcon.Descriptor descriptor = job.key.identity.descriptor;
                if (supplied != null && supplied.length <= RemoteAppIcon.MAX_BYTES && supplied.length == descriptor.byteLength
                        && !job.cancelled && !closed && job.invalidation == invalidations.get()) {
                    byte[] owned = supplied.clone();
                    job.key.identity.validatePng(owned);
                    bitmap = decoder.decode(job.key.identity, owned, job.key.pixels);
                    if (bitmap != null && (bitmap.getWidth() > job.key.pixels || bitmap.getHeight() > job.key.pixels
                            || bitmap.getAllocationByteCount() > RemoteAppIcon.MAX_PIXELS * RemoteAppIcon.MAX_PIXELS * 4))
                        bitmap = null;
                }
            }
        } catch (Exception unavailable) { /* Malformed, absent or unavailable images retain the generic icon. */ }
        job.result = bitmap; job.finished = true;
        if (!job.cancelled && !closed && completionPosted.compareAndSet(false, true)) main.post(() -> {
            completionPosted.set(false);
            for (Job completed : new ArrayList<>(pending.values()))
                if (completed.finished) finish(completed, completed.result);
        });
    }

    private void finish(Job job, Bitmap bitmap) {
        if (closed || job.cancelled || pending.get(job.key) != job) return;
        pending.remove(job.key);
        if (job.invalidation != invalidations.get() || !current(job.key.identity)) return;
        if (bitmap == null) missing.put(job.key, SystemClock.uptimeMillis());
        else { missing.remove(job.key); cache.put(job.key, bitmap); }
        for (Waiting waiter : job.waiters) {
            ImageView view = waiter.view.get();
            if (view != null) apply(view, waiter.token, job.key, bitmap);
        }
    }

    private void apply(ImageView view, Binding token, Key key, Bitmap bitmap) {
        if (bitmap == null || !eligible(view, token, key) || !current(key.identity)) return;
        view.setImageDrawable(new BitmapDrawable(view.getResources(), bitmap));
        view.setImageTintList(null); token.resolved = true;
    }

    private boolean eligible(ImageView view, Binding token, Key key) {
        return !closed && !token.cancelled && !token.detached && view.isAttachedToWindow()
                && view.getTag(R.id.app_icon_request) == token && bindings.contains(token) && token.key.equals(key);
    }

    private boolean current(RemoteAppIcon identity) {
        try { Authority authority = provider.currentAuthority(); return authority != null && authority.permits(identity); }
        catch (RuntimeException unavailable) { return false; }
    }

    private void reconcile() {
        requireMain();
        long revision = invalidations.get();
        if (closed || observedInvalidation == revision) return;
        observedInvalidation = revision;
        for (Job job : pending.values()) job.cancelled = true;
        pending.clear();
        for (Key key : cache.snapshot().keySet()) if (!current(key.identity)) cache.remove(key);
        missing.evictAll();
        pruneBindings();
        for (Binding token : new ArrayList<>(bindings)) {
            ImageView view = token.view.get();
            if (view == null || view.getTag(R.id.app_icon_request) != token) continue;
            placeholder(view, token.fallback); token.resolved = false;
            if (!token.detached) resolve(token);
        }
    }

    private void pruneBindings() {
        for (Binding token : new ArrayList<>(bindings)) {
            ImageView view = token.view.get();
            if (view == null || view.getTag(R.id.app_icon_request) != token) token.cancel(view);
        }
    }

    /** Main-thread disposal by the owning screen/provider lifecycle. Pending deliveries become inert. */
    @Override public void close() {
        requireMain();
        if (closed) return;
        closed = true; invalidations.incrementAndGet();
        try { provider.removeInvalidationListener(invalidated); }
        finally {
            for (Binding token : new ArrayList<>(bindings)) {
                ImageView view = token.view.get();
                if (view != null && view.getTag(R.id.app_icon_request) == token) placeholder(view, token.fallback);
                token.cancel(view);
            }
            for (Job job : pending.values()) job.cancelled = true;
            pending.clear(); cache.evictAll(); missing.evictAll();
            if (ownsWorker) ((ThreadPoolExecutor) worker).shutdownNow();
        }
    }

    private static void placeholder(ImageView view, int fallback) {
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.setImageResource(fallback);
        view.setImageTintList(ColorStateList.valueOf(view.getContext().getColor(R.color.accent_text)));
    }

    private static Bitmap decode(RemoteAppIcon identity, byte[] bytes, int pixels) {
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth != identity.descriptor.width || bounds.outHeight != identity.descriptor.height
                || !"image/png".equals(bounds.outMimeType)) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false; options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null) return null;
        if (bitmap.getWidth() != bounds.outWidth || bitmap.getHeight() != bounds.outHeight) { bitmap.recycle(); return null; }
        int edge = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (edge <= pixels) return bitmap;
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.max(1, bitmap.getWidth() * pixels / edge),
                Math.max(1, bitmap.getHeight() * pixels / edge), true);
        if (scaled != bitmap) bitmap.recycle();
        return scaled;
    }

    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Icon binding requires the UI thread");
    }

    static Binding binding(ImageView view) {
        Object value = view.getTag(R.id.app_icon_request); return value instanceof Binding ? (Binding) value : null;
    }
    int pendingCount() { return pending.size(); }
    int missingCount() { return missing.size(); }
    int cachedBytes() { return cache.size(); }
    int bindingCount() { return bindings.size(); }

    static final class Binding implements AppIcons.Request {
        final RemoteAppIcons engine;
        final WeakReference<ImageView> view;
        final Key key;
        final int fallback;
        boolean detached, cancelled, resolved;
        Binding(RemoteAppIcons engine, ImageView view, Key key, int fallback) {
            this.engine = engine; this.view = new WeakReference<>(view); this.key = key; this.fallback = fallback;
            detached = !view.isAttachedToWindow();
        }
        @Override public void cancel(ImageView target) {
            cancelled = true; engine.bindings.remove(this);
            Job job = engine.pending.get(key);
            if (job != null) {
                job.waiters.removeIf(waiter -> waiter.token == this);
                if (job.waiters.isEmpty()) { job.cancelled = true; engine.pending.remove(key); }
            }
            if (target != null) {
                target.removeOnAttachStateChangeListener(this);
                if (target.getTag(R.id.app_icon_request) == this) target.setTag(R.id.app_icon_request, null);
            }
        }
        @Override public void onViewDetachedFromWindow(View target) {
            detached = true; resolved = false;
            Job job = engine.pending.get(key);
            if (job != null) {
                job.waiters.removeIf(waiter -> waiter.token == this);
                if (job.waiters.isEmpty()) { job.cancelled = true; engine.pending.remove(key); }
            }
            if (target.getTag(R.id.app_icon_request) == this) placeholder((ImageView) target, fallback);
        }
        @Override public void onViewAttachedToWindow(View target) {
            if (!cancelled && target.getTag(R.id.app_icon_request) == this)
                engine.bind((ImageView) target, key.identity, fallback);
        }
    }

    private static final class Key {
        final RemoteAppIcon identity;
        final int pixels;
        Key(RemoteAppIcon identity, int pixels) { this.identity = identity; this.pixels = pixels; }
        @Override public boolean equals(Object other) {
            return other instanceof Key && pixels == ((Key) other).pixels && identity.equals(((Key) other).identity);
        }
        @Override public int hashCode() { return Objects.hash(identity, pixels); }
    }
    private static final class Job {
        final Key key;
        final long invalidation;
        final List<Waiting> waiters = new ArrayList<>();
        volatile boolean cancelled, finished;
        volatile Bitmap result;
        Job(Key key, long invalidation) { this.key = key; this.invalidation = invalidation; }
    }
    private static final class Waiting {
        final WeakReference<ImageView> view;
        final Binding token;
        Waiting(ImageView view, Binding token) { this.view = new WeakReference<>(view); this.token = token; }
    }
}
