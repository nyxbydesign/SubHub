package com.subhub.app.util;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import com.subhub.app.R;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Device-local icons for already known package names; never discovers or transfers applications. */
public final class AppIcons {
    private static final Engine SHARED = new Engine((context, name, pixels) -> {
        Drawable icon = context.getPackageManager().getApplicationIcon(name);
        Bitmap bitmap = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888);
        icon.setBounds(0, 0, pixels, pixels); icon.draw(new Canvas(bitmap));
        return bitmap;
    }, new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), task -> {
        Thread thread = new Thread(task, "subhub-app-icons"); thread.setDaemon(true); return thread;
    }, new ThreadPoolExecutor.AbortPolicy()));
    private AppIcons() { }

    public static void bind(ImageView view, String packageName, int fallback) {
        SHARED.bind(view, packageName, fallback, null);
    }

    /** Reuse an icon already loaded by the existing catalog worker, without a second package query. */
    public static void bind(ImageView view, String packageName, int fallback, Drawable known) {
        SHARED.bind(view, packageName, fallback, known);
    }

    interface Loader { Bitmap load(Context context, String name, int pixels) throws Exception; }

    static final class Engine {
        private static final long RETAIN_MILLIS = 300_000, MISSING_MILLIS = 30_000;
        private final Loader loader;
        private final Executor worker;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final LruCache<String, Cached> cache = new LruCache<String, Cached>(4 * 1024 * 1024) {
            @Override protected int sizeOf(String key, Cached value) { return value.bitmap.getAllocationByteCount(); }
        };
        private final LruCache<String, Long> missing = new LruCache<>(128);
        private final Map<String, List<Waiting>> pending = new HashMap<>();

        Engine(Loader loader, Executor worker) { this.loader = loader; this.worker = worker; }

        void bind(ImageView view, String name, int fallback, Drawable known) {
            if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Icon binding requires the UI thread");
            String packageName = name == null ? "" : name;
            int pixels = Math.max(48, Math.min(192, Math.round(48 * view.getResources().getDisplayMetrics().density)));
            String key = packageName + "@" + pixels;
            Binding old = binding(view);
            Long absent = missing.get(key);
            if (old != null && old.engine == this && !old.detached && !old.cancelled
                    && old.key.equals(key) && old.fallback == fallback && old.known == known
                    && (packageName.isEmpty() || old.resolved && (known != null || SystemClock.uptimeMillis() - old.resolvedAt < RETAIN_MILLIS)
                            || pending.containsKey(key) || absent != null && SystemClock.uptimeMillis() - absent < MISSING_MILLIS)) return;
            if (old != null) { old.cancelled = true; view.removeOnAttachStateChangeListener(old); }
            Binding token = new Binding(this, packageName, key, pixels, fallback, known);
            view.setTag(R.id.app_icon_request, token); view.addOnAttachStateChangeListener(token);
            view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            view.setImageResource(fallback);
            view.setImageTintList(ColorStateList.valueOf(view.getContext().getColor(R.color.accent_text)));
            if (known != null) {
                Drawable.ConstantState state = known.getConstantState();
                if (state != null) {
                    view.setImageDrawable(state.newDrawable(view.getResources()).mutate());
                    view.setImageTintList(null); token.resolved = true; token.resolvedAt = SystemClock.uptimeMillis(); return;
                }
            }
            if (packageName.isEmpty() || packageName.length() > 255
                    || !packageName.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")) return;
            Cached hit = cache.get(key);
            if (hit != null && SystemClock.uptimeMillis() - hit.at < RETAIN_MILLIS) {
                apply(view, token, hit.bitmap); return;
            }
            if (absent != null && SystemClock.uptimeMillis() - absent < MISSING_MILLIS) return;
            List<Waiting> waiters = pending.get(key);
            if (waiters != null) {
                if (waiters.size() < 256) waiters.add(new Waiting(view, token));
                return;
            }
            if (pending.size() >= 64) return;
            waiters = new ArrayList<>(); waiters.add(new Waiting(view, token)); pending.put(key, waiters);
            Context app = application(view.getContext());
            try {
                worker.execute(() -> {
                    Bitmap result = null;
                    try { result = loader.load(app, packageName, pixels); }
                    catch (Exception unavailable) { /* Unknown/invisible/removed apps keep the accessible row fallback. */ }
                    Bitmap loaded = result;
                    main.post(() -> finish(key, loaded));
                });
            } catch (RejectedExecutionException busy) { pending.remove(key); }
        }

        private void finish(String key, Bitmap bitmap) {
            List<Waiting> waiters = pending.remove(key);
            if (bitmap == null) missing.put(key, SystemClock.uptimeMillis());
            else { missing.remove(key); cache.put(key, new Cached(bitmap)); }
            if (waiters != null) for (Waiting waiter : waiters) {
                ImageView view = waiter.view.get();
                if (view != null && binding(view) == waiter.token && !waiter.token.cancelled && !waiter.token.detached)
                    apply(view, waiter.token, bitmap);
            }
        }

        private void apply(ImageView view, Binding token, Bitmap bitmap) {
            if (bitmap == null) return;
            view.setImageDrawable(new BitmapDrawable(view.getResources(), bitmap));
            view.setImageTintList(null); token.resolved = true; token.resolvedAt = SystemClock.uptimeMillis();
        }
    }

    static Binding binding(ImageView view) {
        Object value = view.getTag(R.id.app_icon_request); return value instanceof Binding ? (Binding) value : null;
    }

    static final class Binding implements View.OnAttachStateChangeListener {
        final Engine engine;
        final String packageName, key;
        final int pixels, fallback;
        final Drawable known;
        boolean resolved, detached, cancelled;
        long resolvedAt;
        Binding(Engine engine, String packageName, String key, int pixels, int fallback, Drawable known) {
            this.engine = engine; this.packageName = packageName; this.key = key; this.pixels = pixels;
            this.fallback = fallback; this.known = known;
        }
        @Override public void onViewAttachedToWindow(View view) {
            if (detached && !cancelled) engine.bind((ImageView) view, packageName, fallback, known);
        }
        @Override public void onViewDetachedFromWindow(View view) { detached = true; }
    }

    private static final class Waiting {
        final WeakReference<ImageView> view;
        final Binding token;
        Waiting(ImageView view, Binding token) { this.view = new WeakReference<>(view); this.token = token; }
    }

    private static final class Cached {
        final Bitmap bitmap;
        final long at = SystemClock.uptimeMillis();
        Cached(Bitmap bitmap) { this.bitmap = bitmap; }
    }

    private static Context application(Context source) {
        Context context = source.getApplicationContext();
        for (int i = 0; i < 32 && context instanceof ContextWrapper && !(context instanceof Application); i++) {
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == null || next == context) break;
            context = next.getApplicationContext();
        }
        return context;
    }
}
