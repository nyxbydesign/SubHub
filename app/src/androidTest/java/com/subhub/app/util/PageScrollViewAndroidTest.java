package com.subhub.app.util;

import static org.junit.Assert.*;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

/** Actual Android measurement, clamping and gesture precedence, without timing-dependent posts. */
public final class PageScrollViewAndroidTest {
    @Test public void replacementRestoresAfterLayoutAndUsesTheLatestPosition() {
        onMain(() -> {
            PageScrollView scroll = page(); LinearLayout content = (LinearLayout) scroll.getChildAt(0);
            layout(scroll); scroll.scrollTo(0, 460);
            scroll.preserve(() -> fill(content, 2400));
            assertEquals(460, scroll.position());
            scroll.preserve(() -> fill(content, 2500));
            layout(scroll); assertEquals(460, scroll.getScrollY());
            scroll.scrollTo(0, 725);
            scroll.preserve(() -> fill(content, 2600));
            layout(scroll); assertEquals(725, scroll.getScrollY());
        });
    }

    @Test public void newerNavigationAndGestureCancelRestorationAndShortPagesClamp() {
        onMain(() -> {
            PageScrollView scroll = page(); layout(scroll); scroll.scrollTo(0, 460);
            scroll.restorePosition(460); scroll.scrollTo(0, 0);
            layout(scroll); assertEquals(0, scroll.getScrollY());
            scroll.restorePosition(460);
            MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 20, 20, 0);
            try { scroll.onInterceptTouchEvent(down); } finally { down.recycle(); }
            layout(scroll); assertEquals(0, scroll.getScrollY());
            scroll.scrollTo(0, 460);
            scroll.preserve(() -> fill((LinearLayout) scroll.getChildAt(0), 650));
            layout(scroll); assertEquals(150, scroll.getScrollY());
        });
    }

    @Test public void pendingPositionSurvivesMultipleReplacementsBeforeTheFirstFrame() {
        onMain(() -> {
            PageScrollView scroll = page();
            scroll.restorePosition(620);
            scroll.preserve(() -> fill((LinearLayout) scroll.getChildAt(0), 2200));
            assertEquals(620, scroll.position());
            layout(scroll); assertEquals(620, scroll.getScrollY());
        });
    }

    private static PageScrollView page() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PageScrollView scroll = new PageScrollView(context);
        LinearLayout content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content); fill(content, 2000); return scroll;
    }
    private static void fill(LinearLayout content, int height) {
        content.removeAllViews(); content.addView(new View(content.getContext()), new LinearLayout.LayoutParams(300, height));
    }
    private static void layout(PageScrollView scroll) {
        scroll.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY));
        scroll.layout(0, 0, 300, 500);
    }
    private static void onMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }
}
