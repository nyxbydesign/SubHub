package com.subhub.app.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.subhub.app.R;
import com.subhub.app.help.HelpActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class GagEmojiPickerAndroidTest {
    @Test public void commaTapAndHoldHaveDistinctActions() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.getUiAutomation();
        List<String> taps = new ArrayList<>();
        AtomicReference<Button> reference = new AtomicReference<>();
        CountDownLatch opened = new CountDownLatch(1);
        try (ActivityScenario<HelpActivity> host = ActivityScenario.launch(HelpActivity.class)) {
            host.onActivity(activity -> {
                Button comma = new Button(activity);
                comma.setText(",");
                comma.setOnClickListener(view -> taps.add(","));
                comma.setOnTouchListener(new GagKeyPress(new Handler(Looper.getMainLooper()), null));
                GagEmojiPicker.bindComma(comma, opened::countDown);
                activity.setContentView(comma, new ViewGroup.LayoutParams(64, 64));
                reference.set(comma);
            });
            instrumentation.waitForIdleSync();
            Button comma = reference.get();
            instrumentation.runOnMainSync(() -> {
                assertTrue(comma.isAttachedToWindow());
                assertTrue(comma.getWidth() > 0);
                long time = SystemClock.uptimeMillis();
                touch(comma, time, MotionEvent.ACTION_DOWN, 12);
                touch(comma, time, MotionEvent.ACTION_UP, 12);
            });
            instrumentation.waitForIdleSync();
            assertEquals(List.of(","), taps);
            assertEquals(1L, opened.getCount());

            long cancelledTime = SystemClock.uptimeMillis();
            instrumentation.runOnMainSync(() -> {
                touch(comma, cancelledTime, MotionEvent.ACTION_DOWN, 12);
                touch(comma, cancelledTime, MotionEvent.ACTION_MOVE, comma.getWidth() + 1);
                touch(comma, cancelledTime, MotionEvent.ACTION_UP, 12);
            });
            assertFalse(opened.await(ViewConfiguration.getLongPressTimeout() + 100L, TimeUnit.MILLISECONDS));

            long heldTime = SystemClock.uptimeMillis();
            instrumentation.runOnMainSync(() -> touch(comma, heldTime, MotionEvent.ACTION_DOWN, 12));
            assertTrue(opened.await(3, TimeUnit.SECONDS));
            instrumentation.runOnMainSync(() -> touch(comma, heldTime, MotionEvent.ACTION_UP, 12));
            instrumentation.waitForIdleSync();
            assertEquals(List.of(","), taps);
        }
    }

    @Test public void recentOpensFirstAndPersistsOnlySuccessfulInsertionsNewestFirst() {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context target = instrumentation.getTargetContext();
        String prefsName = "gag_emoji_fixture_" + UUID.randomUUID();
        SharedPreferences preferences = target.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        List<String> inserted = new ArrayList<>();
        List<Boolean> closed = new ArrayList<>();
        String first = "😀";
        String second = "😃";
        String rejected = "😄";
        AtomicReference<GagEmojiPicker> reference = new AtomicReference<>();
        try (ActivityScenario<HelpActivity> host = ActivityScenario.launch(HelpActivity.class)) {
            for (int pass = 0; pass < 2; pass++) {
                int stage = pass;
                host.onActivity(activity -> {
                    GagEmojiPicker picker = new GagEmojiPicker(activity, preferences, false,
                            emoji -> !rejected.equals(emoji) && inserted.add(emoji), () -> closed.add(true));
                    activity.setContentView(picker, new LinearLayout.LayoutParams(320, 256));
                    reference.set(picker);
                });
                instrumentation.waitForIdleSync();
                host.onActivity(activity -> {
                    GagEmojiPicker picker = reference.get();
                    Button all = button(picker, target.getString(R.string.gag_emoji_tab));
                    Button recent = button(picker, target.getString(R.string.gag_emoji_recent));
                    assertTrue(recent.isSelected());
                    assertFalse(all.isSelected());
                    if (stage == 0) {
                        assertTrue(emojiTiles(picker).isEmpty());
                        assertTrue(all.performClick());
                        assertTrue(all.isSelected());
                        assertTrue(button(picker, first).performClick());
                        assertTrue(button(picker, second).performClick());
                        Button rejectedTile = button(picker, rejected);
                        assertFalse(rejectedTile.isLongClickable());
                        assertTrue(rejectedTile.performClick());
                        assertTrue(recent.performClick());
                        assertEquals(List.of(second, first), emojiTiles(picker));
                        assertTrue(button(picker, first).performClick());
                        assertEquals(List.of(first, second), emojiTiles(picker));
                        assertEquals(List.of(first, second, first), inserted);
                    } else {
                        assertEquals(List.of(first, second), emojiTiles(picker));
                        Button back = (Button) ((ViewGroup) picker.getChildAt(0)).getChildAt(0);
                        assertTrue(back.performClick());
                        assertEquals(List.of(true), closed);
                    }
                });
            }
        } finally {
            target.deleteSharedPreferences(prefsName);
        }
    }

    @Test public void recentHistoryDropsTheOldestEntryAtCapacity() {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context target = instrumentation.getTargetContext();
        String prefsName = "gag_emoji_fixture_" + UUID.randomUUID();
        SharedPreferences preferences = target.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        try (ActivityScenario<HelpActivity> host = ActivityScenario.launch(HelpActivity.class)) {
            AtomicReference<GagEmojiPicker> reference = new AtomicReference<>();
            host.onActivity(activity -> {
                GagEmojiPicker picker = new GagEmojiPicker(activity, preferences, false, emoji -> true, () -> {});
                activity.setContentView(picker, new LinearLayout.LayoutParams(320, 256));
                reference.set(picker);
            });
            instrumentation.waitForIdleSync();
            host.onActivity(activity -> {
                GagEmojiPicker picker = reference.get();
                assertTrue(button(picker, target.getString(R.string.gag_emoji_tab)).performClick());
                List<String> catalog = emojiTiles(picker);
                for (String emoji : catalog.subList(0, 33)) assertTrue(button(picker, emoji).performClick());
                assertTrue(button(picker, target.getString(R.string.gag_emoji_recent)).performClick());
                List<String> expected = new ArrayList<>(catalog.subList(1, 33));
                java.util.Collections.reverse(expected);
                assertEquals(expected, emojiTiles(picker));
            });
        } finally {
            target.deleteSharedPreferences(prefsName);
        }
    }

    private static List<String> emojiTiles(GagEmojiPicker picker) {
        List<String> labels = new ArrayList<>();
        ViewGroup scroll = (ViewGroup) picker.getChildAt(1);
        ViewGroup grid = (ViewGroup) scroll.getChildAt(0);
        for (int rowIndex = 0; rowIndex < grid.getChildCount(); rowIndex++) {
            if (!(grid.getChildAt(rowIndex) instanceof ViewGroup row)) continue;
            for (int column = 0; column < row.getChildCount(); column++) {
                labels.add(((Button) row.getChildAt(column)).getText().toString());
            }
        }
        return labels;
    }

    private static Button button(View view, String text) {
        if (view instanceof Button button && text.contentEquals(button.getText())) return button;
        if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                Button found = button(group.getChildAt(index), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void touch(View view, long downTime, int action, float x) {
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, 12, 0);
        try {
            view.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }
}
