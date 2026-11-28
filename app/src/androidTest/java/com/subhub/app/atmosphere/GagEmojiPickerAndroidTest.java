package com.subhub.app.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

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
                        assertTrue(sectionEmoji(picker, frequent).isEmpty());
                        assertTrue(smileys.performClick());
                        assertTrue(tile(picker, smileys, first).performClick());
                        assertTrue(tile(picker, smileys, first).performClick());
                        assertTrue(tile(picker, smileys, second).performClick());
                        Button rejectedTile = tile(picker, smileys, rejected);
                        assertFalse(rejectedTile.isLongClickable());
                        assertTrue(rejectedTile.performClick());
                        assertTrue(frequent.performClick());
                        assertEquals(List.of(first, second), sectionEmoji(picker, frequent));
                        assertTrue(tile(picker, frequent, second).performClick());
                        assertEquals(List.of(first, second), sectionEmoji(picker, frequent));
                        assertEquals(List.of(first, first, second, second), inserted);
                    } else {
                        assertTrue(frequent.isSelected());
                        assertEquals(List.of(second, first), sectionEmoji(picker, frequent));
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
                float density = activity.getResources().getDisplayMetrics().density;
                activity.setContentView(picker, new LinearLayout.LayoutParams(
                        Math.round(250 * density), Math.round(181 * density)));
                reference.set(picker);
            });
            instrumentation.waitForIdleSync();
            host.onActivity(activity -> {
                GagEmojiPicker picker = reference.get();
                assertTrue(button(picker, "People & Body").performClick());
            });
            instrumentation.waitForIdleSync();
            host.onActivity(activity -> {
                GagEmojiPicker picker = reference.get();
                ListView grid = grid(picker);
                BaseAdapter adapter = (BaseAdapter) grid.getAdapter();
                Button people = button(picker, "People & Body");
                int peopleHeader = sectionStart(grid, people);
                assertEquals(peopleHeader, grid.getFirstVisiblePosition());
                assertTrue(grid.getChildCount() > 0 && grid.getChildCount() < 96);
                LinearLayout recycled = (LinearLayout) adapter.getView(peopleHeader + 1, null, grid);
                int columns = recycled.getChildCount();
                assertTrue(recycled == adapter.getView(peopleHeader + 2, recycled, grid));
                String name = catalog.groups.get(1).entries().get(columns).name();
                assertEquals(name, recycled.getChildAt(0).getContentDescription());
                assertTrue(tile(picker, people, "🧑🏿‍💻").performClick());
                assertEquals(List.of("🧑🏿‍💻"), inserted);
                Button flags = button(picker, "Flags");
                assertTrue(flags.performClick());
                assertTrue(tile(picker, flags, "🇩🇪").performClick());
                assertEquals(List.of("🧑🏿‍💻", "🇩🇪"), inserted);
                Button frequent = button(picker, target.getString(R.string.gag_emoji_frequently_used));
                assertTrue(frequent.performClick());
                assertTrue(sectionEmoji(picker, frequent).isEmpty());
                assertTrue(preferences.getAll().isEmpty());
            });
        } finally {
            target.deleteSharedPreferences(prefsName);
        }
    }

    @Test public void tabsStayAboveTheGridAndFollowNativeScrollAcrossCategories() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        UiDevice device = UiDevice.getInstance(instrumentation);
        Context target = instrumentation.getTargetContext();
        GagEmojiCatalog catalog = GagEmojiCatalog.load(target);
        String prefsName = "gag_emoji_fixture_" + UUID.randomUUID();
        SharedPreferences preferences = target.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        AtomicReference<GagEmojiPicker> reference = new AtomicReference<>();
        AtomicReference<Rect> gesture = new AtomicReference<>();
        AtomicReference<Rect> footer = new AtomicReference<>();
        AtomicReference<Integer> header = new AtomicReference<>();
        CountDownLatch closed = new CountDownLatch(1);
        try (ActivityScenario<HelpActivity> host = ActivityScenario.launch(HelpActivity.class)) {
            host.onActivity(activity -> {
                GagEmojiPicker picker = new GagEmojiPicker(activity, preferences, catalog, true, () -> true,
                        emoji -> true, closed::countDown);
                float density = activity.getResources().getDisplayMetrics().density;
                activity.setContentView(picker, new LinearLayout.LayoutParams(
                        Math.round(250 * density), Math.round(181 * density)));
                reference.set(picker);
            });
            instrumentation.waitForIdleSync();
            host.onActivity(activity -> {
                GagEmojiPicker picker = reference.get();
                ListView grid = grid(picker);
                Button back = button(picker, "ABC");
                Rect viewport = screenBounds(grid);
                Rect backBounds = screenBounds(back);
                assertTrue(backBounds.top >= viewport.bottom);
                Rect visibleBack = new Rect();
                assertTrue(back.getGlobalVisibleRect(visibleBack));
                assertEquals(backBounds.width(), visibleBack.width());
                assertEquals(backBounds.height(), visibleBack.height());
                Button smileys = button(picker, "Smileys & Emotion");
                assertTrue(screenBounds(smileys).bottom <= viewport.top);
                ViewGroup tabs = (ViewGroup) smileys.getParent();
                List<String> symbols = new ArrayList<>();
                for (int index = 0; index < tabs.getChildCount(); index++)
                    symbols.add(((Button) tabs.getChildAt(index)).getText().toString());
                assertEquals(List.of("🕘", "☺️", "🧍", "🐝", "☕", "🚗", "🏆", "💡", "🔣", "🏁", "🏽"),
                        symbols);
                gesture.set(viewport);
                footer.set(backBounds);
                int people = sectionStart(grid, button(picker, "People & Body"));
                header.set(people);
                grid.setSelectionFromTop(people - 1, 0);
            });
            instrumentation.waitForIdleSync();
            host.onActivity(activity -> assertTrue(button(reference.get(), "Smileys & Emotion").isSelected()));
            Rect viewport = gesture.get();
            assertTrue(device.swipe(viewport.centerX(), viewport.bottom - 10,
                    viewport.centerX(), viewport.top + 10, 24));
            device.waitForIdle();
            host.onActivity(activity -> {
                GagEmojiPicker picker = reference.get();
                ListView grid = grid(picker);
                assertTrue(grid.getFirstVisiblePosition() >= header.get());
                assertTrue(button(picker, "People & Body").isSelected());
                assertEquals(viewport, screenBounds(grid));
                assertEquals(footer.get(), screenBounds(button(picker, "ABC")));
                grid.setSelectionFromTop(header.get(), 0);
            });
            instrumentation.waitForIdleSync();
            assertTrue(device.swipe(viewport.centerX(), viewport.top + 10,
                    viewport.centerX(), viewport.bottom - 10, 24));
            device.waitForIdle();
            AtomicReference<Rect> tab = new AtomicReference<>();
            host.onActivity(activity -> {
                GagEmojiPicker picker = reference.get();
                assertTrue(button(picker, "Smileys & Emotion").isSelected());
                tab.set(screenBounds(button(picker, "People & Body")));
            });
            assertTrue(device.click(tab.get().centerX(), tab.get().centerY()));
            device.waitForIdle();
            host.onActivity(activity -> {
                GagEmojiPicker picker = reference.get();
                assertEquals(header.get().intValue(), grid(picker).getFirstVisiblePosition());
                assertTrue(button(picker, "People & Body").isSelected());
                assertEquals(footer.get(), screenBounds(button(picker, "ABC")));
            });
            assertTrue(device.click(footer.get().centerX(), footer.get().centerY()));
            assertTrue(closed.await(3, TimeUnit.SECONDS));
        } finally {
            target.deleteSharedPreferences(prefsName);
        }
    }

    private static ListView grid(GagEmojiPicker picker) {
        return (ListView) picker.getChildAt(1);
    }

    private static int sectionStart(ListView grid, Button category) {
        BaseAdapter adapter = (BaseAdapter) grid.getAdapter();
        int headingType = adapter.getItemViewType(0);
        for (int position = 0; position < adapter.getCount(); position++) {
            if (adapter.getItemViewType(position) != headingType) continue;
            TextView heading = (TextView) adapter.getView(position, null, grid);
            if (heading.getText().equals(category.getContentDescription())) return position;
        }
        throw new AssertionError("Missing section for " + category.getContentDescription());
    }

    private static List<String> sectionEmoji(GagEmojiPicker picker, Button category) {
        List<String> labels = new ArrayList<>();
        visitSection(picker, category, button -> {
            labels.add(((GagEmojiCatalog.Entry) button.getTag()).sequence());
            return false;
        });
        return labels;
    }

    private static Button tile(GagEmojiPicker picker, Button category, String emoji) {
        Button found = visitSection(picker, category,
                button -> ((GagEmojiCatalog.Entry) button.getTag()).sequence().equals(emoji));
        if (found == null) throw new AssertionError("Missing emoji " + emoji);
        return found;
    }

    private static Button visitSection(GagEmojiPicker picker, Button category,
            java.util.function.Predicate<Button> visit) {
        ListView grid = grid(picker);
        BaseAdapter adapter = (BaseAdapter) grid.getAdapter();
        int headingType = adapter.getItemViewType(0);
        for (int position = sectionStart(grid, category) + 1; position < adapter.getCount(); position++) {
            if (adapter.getItemViewType(position) == headingType) break;
            if (!(adapter.getView(position, null, grid) instanceof LinearLayout row)) continue;
            for (int index = 0; index < row.getChildCount(); index++) {
                Button tile = (Button) row.getChildAt(index);
                if (tile.getVisibility() == View.VISIBLE && visit.test(tile)) return tile;
            }
        }
        return null;
    }

    private static Rect screenBounds(View view) {
        int[] origin = new int[2];
        view.getLocationOnScreen(origin);
        Rect result = new Rect(origin[0], origin[1], origin[0] + view.getWidth(), origin[1] + view.getHeight());
        assertTrue(view.isShown() && !result.isEmpty());
        return result;
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
