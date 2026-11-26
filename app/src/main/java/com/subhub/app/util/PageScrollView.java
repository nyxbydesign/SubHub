package com.subhub.app.util;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.widget.ScrollView;
import java.util.function.BooleanSupplier;

/** Restore content replacements after the content is ready and measured. */
public final class PageScrollView extends ScrollView {
    private Integer retainedY;
    private BooleanSupplier restoreReady;
    private boolean layingOut;

    public PageScrollView(Context context) { super(context); }
    public PageScrollView(Context context, AttributeSet attrs) { super(context, attrs); }

    public int position() { return retainedY == null ? getScrollY() : retainedY; }

    public void restorePosition(int position) {
        restorePosition(position, null);
    }

    /** The content must request a layout when it becomes ready. */
    public void restorePosition(int position, BooleanSupplier ready) {
        retainedY = Math.max(0, position);
        restoreReady = ready;
        requestLayout();
    }

    /** Capture at delivery time, never before an asynchronous read while the user can scroll. */
    public void preserve(Runnable render) {
        int position = position();
        render.run();
        restorePosition(position);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        layingOut = true;
        try {
            super.onLayout(changed, left, top, right, bottom);
            if (retainedY != null && (restoreReady == null || restoreReady.getAsBoolean())) {
                int position = retainedY;
                cancelRestore();
                super.scrollTo(getScrollX(), position);
            }
        } finally { layingOut = false; }
    }

    /** Explicit navigation and a newer gesture supersede a pending restoration. */
    @Override public void scrollTo(int x, int y) {
        if (!layingOut) cancelRestore();
        super.scrollTo(x, y);
    }

    @Override protected void onScrollChanged(int x, int y, int oldX, int oldY) {
        super.onScrollChanged(x, y, oldX, oldY);
        if (!layingOut && restoreReady != null && y != oldY) cancelRestore();
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) cancelRestore();
        return super.onInterceptTouchEvent(event);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) cancelRestore();
        return super.onTouchEvent(event);
    }

    private void cancelRestore() {
        retainedY = null;
        restoreReady = null;
    }

    public static PageScrollView containing(View view) {
        for (ViewParent parent = view.getParent(); parent != null; parent = parent.getParent())
            if (parent instanceof PageScrollView) return (PageScrollView) parent;
        return null;
    }
}
