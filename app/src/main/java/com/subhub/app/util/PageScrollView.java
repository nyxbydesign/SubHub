package com.subhub.app.util;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.widget.ScrollView;

/** Restore synchronous content replacements after measurement, before the next drawn frame. */
public final class PageScrollView extends ScrollView {
    private Integer retainedY;
    private boolean layingOut;

    public PageScrollView(Context context) { super(context); }
    public PageScrollView(Context context, AttributeSet attrs) { super(context, attrs); }

    public int position() { return retainedY == null ? getScrollY() : retainedY; }

    public void restorePosition(int position) {
        retainedY = Math.max(0, position);
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
            if (retainedY != null) {
                int position = retainedY;
                retainedY = null;
                super.scrollTo(getScrollX(), position);
            }
        } finally { layingOut = false; }
    }

    /** Explicit navigation and a newer gesture supersede a pending restoration. */
    @Override public void scrollTo(int x, int y) {
        if (!layingOut) retainedY = null;
        super.scrollTo(x, y);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) retainedY = null;
        return super.onInterceptTouchEvent(event);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) retainedY = null;
        return super.onTouchEvent(event);
    }

    public static PageScrollView containing(View view) {
        for (ViewParent parent = view.getParent(); parent != null; parent = parent.getParent())
            if (parent instanceof PageScrollView) return (PageScrollView) parent;
        return null;
    }
}
