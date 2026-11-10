package com.subhub.app.appmode;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ScaleDrawable;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.people.PersonSnapshot;
import com.subhub.app.people.PersonUi;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real framework layout/draw and accessibility contracts; no app data or device state changes. */
@RunWith(AndroidJUnit4.class)
public final class AllowanceProgressAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test public void nativeRoundedMetersKeepExactValuesAndAccessibility() {
        main(() -> {
            LinearLayout parent = new LinearLayout(context);
            assertNull(AllowanceProgress.add(parent, value(-1)));
            assertEquals("Unknown usage has no meter", 0, parent.getChildCount());
            ProgressBar bar = AllowanceProgress.add(parent, value(15 * 60_000L));
            assertEquals(1000, bar.getMax());
            assertEquals(250, bar.getProgress());
            assertFalse(bar.isIndeterminate());
            AccessibilityNodeInfo node = bar.createAccessibilityNodeInfo();
            assertEquals("android.widget.ProgressBar", node.getClassName().toString());
            assertEquals(context.getString(R.string.role_allowance_progress_description, "Synthetic app", 15L, 60L),
                    node.getContentDescription().toString());
            assertNotNull(node.getRangeInfo());
            assertEquals(250f, node.getRangeInfo().getCurrent(), 0f);
            node.recycle();
            Bitmap pixels = draw(bar, View.LAYOUT_DIRECTION_LTR);
            try {
                assertEquals("The track has rounded transparent corners", 0, Color.alpha(pixels.getPixel(0, 0)));
                assertEquals("The track center is visible", 255,
                        Color.alpha(pixels.getPixel(pixels.getWidth() / 2, pixels.getHeight() / 2)));
                assertNotEquals("The fill has a soft gradient",
                        pixels.getPixel(pixels.getWidth() / 16, pixels.getHeight() / 2),
                        pixels.getPixel(pixels.getWidth() * 3 / 16, pixels.getHeight() / 2));
                assertEquals(context.getColor(R.color.surface_card_raised),
                        pixels.getPixel(pixels.getWidth() / 2, pixels.getHeight() / 2));
                Rect fill = fillBounds(bar);
                assertEquals(0, fill.left);
                assertEquals(pixels.getWidth() / 4f, fill.width(), 1f);
            } finally { pixels.recycle(); }
            assertEquals(1000, AllowanceProgress.add(parent, value(Long.MAX_VALUE)).getProgress());
            assertEquals(0, AllowanceProgress.add(parent, value(0)).getProgress());
        });
    }

    @Test public void nativeRoundedMetersMirrorForRtlWithoutInflatingSmallUsage() {
        main(() -> {
            LinearLayout parent = new LinearLayout(context);
            ProgressBar bar = AllowanceProgress.add(parent, value(15 * 60_000L));
            Bitmap pixels = draw(bar, View.LAYOUT_DIRECTION_RTL);
            try {
                Rect fill = fillBounds(bar);
                assertEquals(pixels.getWidth(), fill.right);
                assertEquals(pixels.getWidth() / 4f, fill.width(), 1f);
                assertEquals(context.getColor(R.color.surface_card_raised),
                        pixels.getPixel(pixels.getWidth() / 4, pixels.getHeight() / 2));
                assertNotEquals(context.getColor(R.color.surface_card_raised),
                        pixels.getPixel(pixels.getWidth() * 7 / 8, pixels.getHeight() / 2));
            } finally { pixels.recycle(); }
            ProgressBar tiny = AllowanceProgress.add(parent, value(3600L));
            assertEquals(1, tiny.getProgress());
            Bitmap small = draw(tiny, View.LAYOUT_DIRECTION_LTR);
            try {
                assertTrue("No minimum filled pill that exaggerates usage", fillBounds(tiny).width() <= Math.ceil(small.getWidth() / 1000d));
            } finally { small.recycle(); }
        });
    }

    private PersonSnapshot.Allowance value(long used) {
        return new PersonSnapshot.Allowance("synthetic.example", "Synthetic app", used, 60);
    }

    private Bitmap draw(ProgressBar bar, int direction) {
        int width = PersonUi.dp(context, 200), height = PersonUi.dp(context, 10);
        bar.setLayoutDirection(direction);
        bar.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        bar.layout(0, 0, width, height);
        bar.jumpDrawablesToCurrentState();
        Bitmap pixels = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bar.draw(new Canvas(pixels));
        return pixels;
    }

    private static Rect fillBounds(ProgressBar bar) {
        LayerDrawable layers = (LayerDrawable) bar.getProgressDrawable();
        ScaleDrawable progress = (ScaleDrawable) layers.findDrawableByLayerId(android.R.id.progress);
        return progress.getDrawable().getBounds();
    }

    private static void main(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }
}
