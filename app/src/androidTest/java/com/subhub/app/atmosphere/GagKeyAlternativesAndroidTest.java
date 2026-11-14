package com.subhub.app.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
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
import java.util.concurrent.atomic.AtomicReference;

public final class GagKeyAlternativesAndroidTest {
    @Test public void tapDefaultsAndNineHoldOptionsRemainReachable() {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.getUiAutomation();
        UiDevice device = UiDevice.getInstance(instrumentation);
        List<String> output = new ArrayList<>();
        AtomicReference<Button> anchor = new AtomicReference<>();
        try (ActivityScenario<HelpActivity> host = ActivityScenario.launch(HelpActivity.class)) {
            host.onActivity(activity -> {
                LinearLayout page = new LinearLayout(activity);
                page.setGravity(android.view.Gravity.BOTTOM | android.view.Gravity.RIGHT);
                activity.setContentView(page);
                Button button = new Button(activity);
                button.setText("a");
                page.addView(button, new LinearLayout.LayoutParams(70, 64));
                GagKeyAlternativesPopup popup = new GagKeyAlternativesPopup(page);
                GagKeyPress touch = new GagKeyPress(new Handler(Looper.getMainLooper()), null);
                touch.setAlternatives(popup);
                button.setOnTouchListener(touch);
                button.setOnClickListener(view -> output.add("a"));
                button.setOnLongClickListener(view ->
                        popup.show(view, GagKeyAlternatives.forLetter("a", "@", false), output::add));
                anchor.set(button);
            });
            var key = device.wait(Until.findObject(By.text("a")), 5_000L);
            assertNotNull(key);
            key.click();
            host.onActivity(activity -> assertEquals(List.of("a"), output));

            long down = SystemClock.uptimeMillis();
            host.onActivity(activity -> touch(anchor.get(), down, MotionEvent.ACTION_DOWN));
            var last = device.wait(Until.findObject(By.text("ā")), 5_000L);
            assertNotNull("last option is still visible at the edge", last);
            assertTrue(last.getVisibleBounds().width() > 0);
            host.onActivity(activity -> touch(anchor.get(), down, MotionEvent.ACTION_UP));
            var umlaut = device.wait(Until.findObject(By.text("ä")), 5_000L);
            assertNotNull(umlaut);
            umlaut.click();
            host.onActivity(activity -> assertEquals(List.of("a", "ä"), output));
        }
    }

    private static void touch(View view, long down, int action) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                view.getWidth() / 2f, view.getHeight() / 2f, 0);
        try { view.dispatchTouchEvent(event); } finally { event.recycle(); }
    }
}
