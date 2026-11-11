package com.subhub.app.overlay;

import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import java.util.IdentityHashMap;

/** Exact window receipts, not a GPU-idle or compositor-presentation fence. */
public final class OwnedWindowRetirement {
    private final IdentityHashMap<View, Witness> windows = new IdentityHashMap<>();
    private boolean failed;

    /** Register before addView, including attempts that may partially attach before throwing. */
    public void adding(View view) {
        requireMain();
        if (windows.containsKey(view)) throw new IllegalStateException("Window already tracked");
        Witness witness = new Witness();
        windows.put(view, witness);
        view.addOnAttachStateChangeListener(witness);
    }

    /**
     * Call only from a fresh main Handler dispatch, outside draw/layout/detach traversal.
     * A detach callback precedes renderer destruction. Successful remove return is required too.
     */
    public void removeFromFreshMainQueue(WindowManager manager, View view) {
        requireMain();
        Witness witness = windows.get(view);
        if (witness == null) throw new IllegalStateException("Unknown owned window");
        boolean attached = view.isAttachedToWindow();
        int before = witness.detaches;
        try {
            manager.removeViewImmediate(view);
            if (view.isAttachedToWindow() || view.getWindowToken() != null
                    || view.getParent() != null || attached && witness.detaches == before) {
                throw new IllegalStateException("Window retirement not witnessed");
            }
            view.removeOnAttachStateChangeListener(witness);
            windows.remove(view);
        } catch (RuntimeException | Error uncertain) {
            failed = true;
            throw uncertain;
        }
    }

    public boolean isEmpty() { requireMain(); return windows.isEmpty() && !failed; }
    public boolean failed() { requireMain(); return failed; }
    public int count() { requireMain(); return windows.size(); }

    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("Owned windows require main thread");
    }
    private static final class Witness implements View.OnAttachStateChangeListener {
        int detaches;
        @Override public void onViewAttachedToWindow(View view) { }
        @Override public void onViewDetachedFromWindow(View view) { detaches++; }
    }
}
