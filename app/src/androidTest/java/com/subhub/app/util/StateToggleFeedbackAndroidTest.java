package com.subhub.app.util;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.MotionEvent;
import android.view.View;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.studio.StudioActivity;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Pixel checks are in-memory only; no screen captures or device artifacts. */
@RunWith(AndroidJUnit4.class)
public final class StateToggleFeedbackAndroidTest {
    @Test public void pressAppearsAtTheTouchImmediatelyAndReleaseFinishesPromptly() {
        AtomicReference<StateToggle> control = new AtomicReference<>();
        try (ActivityScenario<StudioActivity> scenario = ActivityScenario.launch(StudioActivity.class)) {
        scenario.onActivity(activity -> {
            StateToggle toggle = makeToggle();
            activity.setContentView(toggle, new android.view.ViewGroup.LayoutParams(320, 64));
            assertTrue("Touch click needs an attached view/message queue", toggle.isAttachedToWindow());
            control.set(toggle);
            Bitmap before = draw(toggle);
            touch(toggle, MotionEvent.ACTION_DOWN, 20, 24);
            Bitmap pressed = draw(toggle);
            assertNotEquals(before.getPixel(20, 24), pressed.getPixel(20, 24));
            assertEquals("No dead-center ripple", before.getPixel(160, 24), pressed.getPixel(160, 24));
            touch(toggle, MotionEvent.ACTION_UP, 20, 24);
            before.recycle();
            pressed.recycle();
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                assertTrue("Native touch click must update on its normal UI dispatch", control.get().isChecked()));
        SystemClock.sleep(180);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Bitmap released = draw(control.get());
            assertEquals("Tap fade must finish within 180ms", 0, released.getPixel(20, 24));
            released.recycle();
        });
        }
    }

    @Test public void scrollCancellationClearsFeedbackWithoutChangingTheToggle() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            StateToggle toggle = makeToggle();
            touch(toggle, MotionEvent.ACTION_DOWN, 60, 24);
            touch(toggle, MotionEvent.ACTION_CANCEL, 60, 24);
            Bitmap cancelled = draw(toggle);
            assertEquals(0, cancelled.getPixel(60, 24));
            assertFalse(toggle.isChecked());
            cancelled.recycle();
            toggle.setEnabled(false);
            touch(toggle, MotionEvent.ACTION_DOWN, 60, 24);
            touch(toggle, MotionEvent.ACTION_UP, 60, 24);
            assertFalse(toggle.isChecked());
        });
    }

    private static StateToggle makeToggle() {
        Context context = new ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_SubHub);
        StateToggle toggle = new StateToggle(context);
        toggle.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(64, View.MeasureSpec.EXACTLY));
        toggle.layout(0, 0, 320, 64);
        return toggle;
    }

    private static void touch(StateToggle toggle, int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        try { toggle.onTouchEvent(event); } finally { event.recycle(); }
    }

    private static Bitmap draw(StateToggle toggle) {
        Bitmap pixels = Bitmap.createBitmap(toggle.getWidth(), toggle.getHeight(), Bitmap.Config.ARGB_8888);
        toggle.draw(new Canvas(pixels));
        return pixels;
    }
}
