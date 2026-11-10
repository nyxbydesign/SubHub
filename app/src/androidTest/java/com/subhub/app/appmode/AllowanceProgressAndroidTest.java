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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.overlay.CensorTapFixtureActivity;
import com.subhub.app.people.PersonSnapshot;
import com.subhub.app.people.PersonUi;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Attached framework accessibility and software Canvas contracts; no user data or settings changes. */
@RunWith(AndroidJUnit4.class)
public final class AllowanceProgressAndroidTest {
    @Test public void nativeRoundedMetersKeepExactValuesAndAccessibility() throws Exception {
        withAttachedParent(parent -> {
            Context context = parent.getContext();
            assertNull(AllowanceProgress.add(parent, value(-1)));
            assertEquals("Unknown usage has no meter", 0, parent.getChildCount());
            ProgressBar bar = AllowanceProgress.add(parent, value(15 * 60_000L));
            assertEquals(1000, bar.getMax());
            assertEquals(250, bar.getProgress());
            assertFalse(bar.isIndeterminate());
            Bitmap pixels = draw(bar, View.LAYOUT_DIRECTION_LTR);
            try {
                AccessibilityNodeInfo node = bar.createAccessibilityNodeInfo();
                assertNotNull("Attached meter provides a native accessibility node", node);
                try {
                    assertNotNull("Native node class is initialized", node.getClassName());
                    assertEquals("android.widget.ProgressBar", node.getClassName().toString());
                    assertNotNull("Native node description is initialized", node.getContentDescription());
                    assertEquals(context.getString(R.string.role_allowance_progress_description, "Synthetic app", 15L, 60L),
                            node.getContentDescription().toString());
                    AccessibilityNodeInfo.RangeInfo range = node.getRangeInfo();
                    assertNotNull(range);
                    assertEquals(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, range.getType());
                    assertEquals(0f, range.getMin(), 0f);
                    assertEquals(1000f, range.getMax(), 0f);
                    assertEquals(250f, range.getCurrent(), 0f);
                } finally { node.recycle(); }
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
            } catch (AssertionError failure) {
                retainFailure(context, pixels, "allowance-ltr", failure);
                throw failure;
            } finally { pixels.recycle(); }
            assertEquals(1000, AllowanceProgress.add(parent, value(Long.MAX_VALUE)).getProgress());
            assertEquals(0, AllowanceProgress.add(parent, value(0)).getProgress());
        });
    }

    @Test public void nativeRoundedMetersMirrorForRtlWithoutInflatingSmallUsage() throws Exception {
        withAttachedParent(parent -> {
            Context context = parent.getContext();
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
            } catch (AssertionError failure) {
                retainFailure(context, pixels, "allowance-rtl", failure);
                throw failure;
            } finally { pixels.recycle(); }
            ProgressBar tiny = AllowanceProgress.add(parent, value(3600L));
            assertEquals(1, tiny.getProgress());
            Bitmap small = draw(tiny, View.LAYOUT_DIRECTION_LTR);
            try {
                assertTrue("Known nonzero usage retains a nonzero fill", fillBounds(tiny).width() > 0);
                assertTrue("No minimum filled pill that exaggerates usage", fillBounds(tiny).width() <= Math.ceil(small.getWidth() / 1000d));
            } catch (AssertionError failure) {
                retainFailure(context, small, "allowance-tiny", failure);
                throw failure;
            } finally { small.recycle(); }
        });
    }

    private PersonSnapshot.Allowance value(long used) {
        return new PersonSnapshot.Allowance("synthetic.example", "Synthetic app", used, 60);
    }

    private Bitmap draw(ProgressBar bar, int direction) {
        Context context = bar.getContext();
        assertTrue("Meter must belong to an attached native window", bar.isAttachedToWindow());
        int width = PersonUi.dp(context, 200), height = PersonUi.dp(context, 10);
        bar.setLayoutDirection(direction);
        bar.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        bar.layout(0, 0, width, height);
        assertEquals(direction, bar.getLayoutDirection());
        assertTrue("Native layout resolves drawable direction", bar.isLayoutDirectionResolved());
        bar.jumpDrawablesToCurrentState();
        // Synchronous View-local pixels, not a screenshot of the composed Activity window.
        Bitmap pixels = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bar.draw(new Canvas(pixels));
        return pixels;
    }

    private static Rect fillBounds(ProgressBar bar) {
        LayerDrawable layers = (LayerDrawable) bar.getProgressDrawable();
        ScaleDrawable progress = (ScaleDrawable) layers.findDrawableByLayerId(android.R.id.progress);
        return new Rect(progress.getDrawable().getBounds());
    }

    private static void withAttachedParent(Consumer<LinearLayout> assertions) throws InterruptedException {
        try (ActivityScenario<CensorTapFixtureActivity> scenario =
                     ActivityScenario.launch(CensorTapFixtureActivity.class)) {
            AtomicReference<LinearLayout> fixture = new AtomicReference<>();
            CountDownLatch laidOut = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                LinearLayout parent = new LinearLayout(activity);
                parent.setOrientation(LinearLayout.VERTICAL);
                parent.addOnLayoutChangeListener((view, left, top, right, bottom,
                        oldLeft, oldTop, oldRight, oldBottom) -> {
                    if (right > left && bottom > top) laidOut.countDown();
                });
                ((FrameLayout) activity.findViewById(android.R.id.content)).addView(parent,
                        new FrameLayout.LayoutParams(-1, -1));
                fixture.set(parent);
            });
            assertTrue("Fixture parent completes native layout", laidOut.await(5, TimeUnit.SECONDS));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                LinearLayout parent = fixture.get();
                // View skips base node initialization while detached, even if it has a parent.
                assertTrue("Fixture parent is attached before adding meters", parent.isAttachedToWindow());
                assertNotNull(parent.getWindowToken());
                assertTrue("Fixture parent has completed native layout", parent.isLaidOut());
                assertions.accept(parent);
            });
        }
    }

    private static void retainFailure(Context context, Bitmap pixels, String label, AssertionError failure) {
        try {
            String run = InstrumentationRegistry.getArguments().getString("evidence_run", "allowance-native");
            assertTrue("Safe evidence run token", run.matches("[a-zA-Z0-9-]+"));
            File directory = new File(context.getFilesDir(), "ui-v3/" + run);
            assertTrue(directory.isDirectory() || directory.mkdirs());
            File target = File.createTempFile(label + "-failure-", ".png", directory);
            try (FileOutputStream output = new FileOutputStream(target)) {
                assertTrue(pixels.compress(Bitmap.CompressFormat.PNG, 100, output));
            }
            System.out.println("Allowance software Canvas failure pixels: " + target.getAbsolutePath());
        } catch (Exception | AssertionError exportFailure) { failure.addSuppressed(exportFailure); }
    }
}
