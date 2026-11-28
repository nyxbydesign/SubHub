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
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.LinearLayout;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.subhub.app.R;
import com.subhub.app.UiAutomationStateRule;
import com.subhub.app.help.HelpActivity;

import org.junit.Rule;
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
    @Rule public final UiAutomationStateRule automationState = new UiAutomationStateRule();

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

    @Test public void frequentRanksSuccessfulSelectionsByCountAndKeepsTapPositionsStable() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context target = instrumentation.getTargetContext();
        GagEmojiCatalog catalog = GagEmojiCatalog.load(target);
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
                    GagEmojiPicker picker = new GagEmojiPicker(activity, preferences, catalog, true, () -> true,
                            emoji -> !rejected.equals(emoji) && inserted.add(emoji), () -> closed.add(true));
                    float density = activity.getResources().getDisplayMetrics().density;
                    activity.setContentView(picker, new LinearLayout.LayoutParams(
                            Math.round(250 * density), Math.round(181 * density)));
                    reference.set(picker);
                });
                instrumentation.waitForIdleSync();
                host.onActivity(activity -> {
                    GagEmojiPicker picker = reference.get();
                    Button frequent = button(picker, target.getString(R.string.gag_emoji_frequently_used));
                    Button smileys = button(picker, "Smileys & Emotion");
                    if (stage == 0) {
                        assertTrue(smileys.isSelected());
                        assertTrue(frequent.performClick());
                        assertTrue(emojiTiles(picker).isEmpty());
                        assertTrue(smileys.performClick());
                        assertTrue(tile(picker, first).performClick());
                        assertTrue(tile(picker, first).performClick());
                        assertTrue(tile(picker, second).performClick());
                        Button rejectedTile = tile(picker, rejected);
                        assertFalse(rejectedTile.isLongClickable());
                        assertTrue(rejectedTile.performClick());
                        assertTrue(frequent.performClick());
                        assertEquals(List.of(first, second), emojiTiles(picker));
                        assertTrue(tile(picker, second).performClick());
                        assertEquals(List.of(first, second), emojiTiles(picker));
                        assertEquals(List.of(first, first, second, second), inserted);
                    } else {
                        assertTrue(frequent.isSelected());
                        assertEquals(List.of(second, first), emojiTiles(picker));
                        Button back = button(picker, "ABC");
                        assertTrue(back.isShown());
                        assertTrue(frequent.isShown());
                        assertTrue(back.performClick());
                        assertEquals(List.of(true), closed);
                    }
                });
            }
        } finally {
            target.deleteSharedPreferences(prefsName);
        }
    }

    @Test public void savedRecentsSeedSingleUsesAndClearingResetsBothFormats() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context target = instrumentation.getTargetContext();
        GagEmojiCatalog catalog = GagEmojiCatalog.load(target);
        String prefsName = "gag_emoji_fixture_" + UUID.randomUUID();
        SharedPreferences preferences = target.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        try {
            assertTrue(preferences.edit().putString("recent", "😃 😀 😃 INVALID").commit());
            GagEmojiUsage usage = new GagEmojiUsage(preferences, catalog);
            assertEquals(List.of(catalog.entry("😃"), catalog.entry("😀")), usage.frequentlyUsed());
            usage.record("😀");
            usage.record("😃");
            usage.record("😀");
            GagEmojiUsage reloaded = new GagEmojiUsage(preferences, catalog);
            assertFalse(preferences.contains("recent"));
            assertEquals(List.of(catalog.entry("😀"), catalog.entry("😃")), reloaded.frequentlyUsed());
            var known = catalog.groups.get(0).entries();
            for (var entry : known.subList(2, 35)) usage.record(entry.sequence());
            assertEquals(32, reloaded.frequentlyUsed().size());
            assertEquals(known.subList(0, 2), reloaded.frequentlyUsed().subList(0, 2));
            GagEmojiUsage.clear(preferences);
            assertTrue(reloaded.frequentlyUsed().isEmpty());
        } finally {
            target.deleteSharedPreferences(prefsName);
        }
    }

    @Test public void unicodeCatalogKeepsAllGroupsAndRecyclesTheGrid() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context target = instrumentation.getTargetContext();
        GagEmojiCatalog catalog = GagEmojiCatalog.load(target);
        assertEquals(List.of(172, 2430, 9, 161, 132, 221, 85, 268, 224, 270),
                catalog.groups.stream().map(group -> group.entries().size()).collect(java.util.stream.Collectors.toList()));
        String england = new String(new int[]{0x1F3F4, 0xE0067, 0xE0062, 0xE0065, 0xE006E, 0xE0067, 0xE007F}, 0, 7);
        for (String emoji : List.of("🧑🏿‍💻", "🇩🇪", "🏿", "#️⃣", "🫫", england))
            assertEquals(emoji, catalog.entry(emoji).sequence());

        String prefsName = "gag_emoji_fixture_" + UUID.randomUUID();
        SharedPreferences preferences = target.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        AtomicReference<GagEmojiPicker> reference = new AtomicReference<>();
        List<String> inserted = new ArrayList<>();
        try (ActivityScenario<HelpActivity> host = ActivityScenario.launch(HelpActivity.class)) {
            host.onActivity(activity -> {
                GagEmojiPicker picker = new GagEmojiPicker(activity, preferences, catalog, false, () -> false,
                        inserted::add, () -> {});
                activity.setContentView(picker, new LinearLayout.LayoutParams(320, 256));
                reference.set(picker);
            });
            instrumentation.waitForIdleSync();
            host.onActivity(activity -> {
                GagEmojiPicker picker = reference.get();
                assertTrue(button(picker, "People & Body").performClick());
            });
            instrumentation.waitForIdleSync();
            host.onActivity(activity -> {
                GridView grid = grid(reference.get());
                BaseAdapter adapter = (BaseAdapter) grid.getAdapter();
                assertEquals(2430, adapter.getCount());
                assertTrue(grid.getChildCount() > 0 && grid.getChildCount() < 96);
                View recycled = adapter.getView(0, null, grid);
                assertTrue(recycled == adapter.getView(1, recycled, grid));
                String name = ((GagEmojiCatalog.Entry) adapter.getItem(1)).name();
                assertEquals(name, recycled.getContentDescription());
                assertTrue(tile(reference.get(), "🧑🏿‍💻").performClick());
                assertEquals(List.of("🧑🏿‍💻"), inserted);
                assertTrue(button(reference.get(), "Flags").performClick());
                assertEquals(270, adapter.getCount());
                assertTrue(tile(reference.get(), "🇩🇪").performClick());
                assertEquals(List.of("🧑🏿‍💻", "🇩🇪"), inserted);
                assertTrue(button(reference.get(), target.getString(R.string.gag_emoji_frequently_used)).performClick());
                assertTrue(emojiTiles(reference.get()).isEmpty());
                assertTrue(preferences.getAll().isEmpty());
            });
        } finally {
            target.deleteSharedPreferences(prefsName);
        }
    }

    private static GridView grid(GagEmojiPicker picker) {
        return (GridView) picker.getChildAt(1);
    }

    private static List<String> emojiTiles(GagEmojiPicker picker) {
        BaseAdapter adapter = (BaseAdapter) grid(picker).getAdapter();
        List<String> labels = new ArrayList<>();
        for (int position = 0; position < adapter.getCount(); position++)
            labels.add(((GagEmojiCatalog.Entry) adapter.getItem(position)).sequence());
        return labels;
    }

    private static Button tile(GagEmojiPicker picker, String emoji) {
        GridView grid = grid(picker);
        int position = emojiTiles(picker).indexOf(emoji);
        assertTrue(position >= 0);
        return (Button) grid.getAdapter().getView(position, null, grid);
    }

    private static Button button(View view, String text) {
        if (view instanceof Button button && (text.contentEquals(button.getText())
                || text.equals(button.getContentDescription()))) return button;
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
