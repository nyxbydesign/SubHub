package com.subhub.app.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;

import com.subhub.app.help.HelpActivity;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class GagKeyAlternativesAndroidTest {
    @Test public void tapDefaultsAndNineHoldOptionsRemainReachable() {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        UiAutomation automation = instrumentation.getUiAutomation();
        UiDevice device = UiDevice.getInstance(instrumentation);
        List<String> output = new ArrayList<>();
        try (ActivityScenario<HelpActivity> host = ActivityScenario.launch(HelpActivity.class)) {
            host.onActivity(activity -> {
                LinearLayout page = new LinearLayout(activity);
                page.setGravity(android.view.Gravity.BOTTOM | android.view.Gravity.RIGHT);
                activity.setContentView(page);
                Button button = new Button(activity);
                button.setText("a");
                page.addView(button, new LinearLayout.LayoutParams(70, 64));
                GagKeyAlternativesPopup popup = new GagKeyAlternativesPopup(page);
                GagKeyPreview preview = new GagKeyPreview();
                GagKeyPress touch = new GagKeyPress(new Handler(Looper.getMainLooper()), null, preview);
                touch.setAlternatives(popup);
                button.setOnTouchListener(touch);
                button.setOnClickListener(view -> output.add("a"));
                button.setOnLongClickListener(view -> {
                    preview.dismiss();
                    return popup.show(view, GagKeyAlternatives.forLetter("a", "@", false), output::add);
                });
            });
            var key = device.wait(Until.findObject(By.text("a")), 5_000L);
            assertNotNull(key);
            key.click();
            host.onActivity(activity -> assertEquals(List.of("a"), output));

            Rect keyBounds = key.getVisibleBounds();
            long down = hold(automation, keyBounds);
            var last = device.wait(Until.findObject(By.text("ā")), 5_000L);
            assertNotNull("last option is still visible at the edge", last);
            Rect edgeOption = last.getVisibleBounds();
            assertTrue(edgeOption.width() > 0 && edgeOption.right <= device.getDisplayWidth());
            assertTrue("The panel stays above the anchor", edgeOption.bottom <= keyBounds.top);
            var middle = device.findObject(By.text("ä"));
            assertNotNull(middle);
            Rect middleOption = middle.getVisibleBounds();
            touch(automation, down, MotionEvent.ACTION_MOVE, middleOption.centerX(), middleOption.centerY());
            assertNotNull(device.wait(Until.findObject(By.text("ä").selected(true)), 5_000L));
            touch(automation, down, MotionEvent.ACTION_MOVE, edgeOption.centerX(), edgeOption.centerY());
            assertNotNull("A held slide highlights the option", device.wait(
                    Until.findObject(By.text("ā").selected(true)), 5_000L));
            assertNotNull(device.wait(Until.findObject(By.text("ä").selected(false)), 5_000L));
            host.onActivity(activity -> assertEquals(List.of("a"), output));
            touch(automation, down, MotionEvent.ACTION_UP, edgeOption.centerX(), edgeOption.centerY());
            assertTrue(device.wait(Until.gone(By.text("ā")), 5_000L));
            host.onActivity(activity -> assertEquals(List.of("a", "ā"), output));

            down = hold(automation, keyBounds);
            var cancelled = device.wait(Until.findObject(By.text("ä")), 5_000L);
            assertNotNull(cancelled);
            Rect choice = cancelled.getVisibleBounds();
            touch(automation, down, MotionEvent.ACTION_MOVE, choice.centerX(), choice.centerY());
            assertNotNull(device.wait(Until.findObject(By.text("ä").selected(true)), 5_000L));
            touch(automation, down, MotionEvent.ACTION_CANCEL, choice.centerX(), choice.centerY());
            assertTrue(device.wait(Until.gone(By.text("ä")), 5_000L));
            host.onActivity(activity -> assertEquals(List.of("a", "ā"), output));

            down = hold(automation, keyBounds);
            var leaving = device.wait(Until.findObject(By.text("ä")), 5_000L);
            assertNotNull(leaving);
            Rect option = leaving.getVisibleBounds();
            touch(automation, down, MotionEvent.ACTION_MOVE, option.centerX(), option.centerY());
            assertNotNull(device.wait(Until.findObject(By.text("ä").selected(true)), 5_000L));
            touch(automation, down, MotionEvent.ACTION_MOVE, device.getDisplayWidth() / 4f, keyBounds.centerY());
            touch(automation, down, MotionEvent.ACTION_UP, device.getDisplayWidth() / 4f, keyBounds.centerY());
            assertTrue(device.wait(Until.gone(By.text("ä")), 5_000L));
            host.onActivity(activity -> assertEquals(List.of("a", "ā"), output));

            down = hold(automation, keyBounds);
            var umlaut = device.wait(Until.findObject(By.text("ä")), 5_000L);
            assertNotNull(umlaut);
            touch(automation, down, MotionEvent.ACTION_UP, keyBounds.centerX(), keyBounds.centerY());
            umlaut.click();
            host.onActivity(activity -> assertEquals(List.of("a", "ā", "ä"), output));
        }
    }

    private static long hold(UiAutomation automation, Rect key) {
        long down = SystemClock.uptimeMillis();
        touch(automation, down, MotionEvent.ACTION_DOWN, key.centerX(), key.centerY());
        return down;
    }

    private static void touch(UiAutomation automation, long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { assertTrue(automation.injectInputEvent(event, true)); } finally { event.recycle(); }
    }
}
