package com.subhub.app.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.SurroundingText;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.subhub.app.help.HelpActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public final class GagKeyPressAndroidTest {
    @Test public void slotBoundariesOwnTheVisualGapsAndRejectOutsideRelease() {
        List<Integer> clicks = new ArrayList<>();
        try (ActivityScenario<HelpActivity> host = ActivityScenario.launch(HelpActivity.class)) {
            for (int width : new int[]{317, 389, 721}) {
                host.onActivity(activity -> {
                    LinearLayout keyboard = new LinearLayout(activity);
                    keyboard.setOrientation(LinearLayout.VERTICAL);
                    LinearLayout first = row(keyboard, clicks);
                    LinearLayout second = row(keyboard, clicks);
                    activity.setContentView(keyboard, new LinearLayout.LayoutParams(width, 128));
                    assertTrue(keyboard.isAttachedToWindow());
                    assertTrue(first.getChildAt(0).isAttachedToWindow());
                    keyboard.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(128, View.MeasureSpec.EXACTLY));
                    keyboard.layout(0, 0, width, 128);
                    int boundary = first.getChildAt(1).getLeft();
                    assertEquals(boundary, first.getChildAt(0).getRight());
                    assertEquals(width, first.getChildAt(9).getRight());
                    assertEquals(first.getBottom(), second.getTop());
                    android.graphics.Bitmap frame = android.graphics.Bitmap.createBitmap(
                            width, 128, android.graphics.Bitmap.Config.ARGB_8888);
                    try {
                        keyboard.draw(new android.graphics.Canvas(frame));
                        InsetDrawable face = (InsetDrawable) first.getChildAt(0).getBackground();
                        InsetDrawable nextFace = (InsetDrawable) first.getChildAt(1).getBackground();
                        assertEquals(boundary - 3, face.getDrawable().getBounds().right);
                        assertEquals(3, nextFace.getDrawable().getBounds().left);
                    } finally {
                        frame.recycle();
                    }
                    gesture(keyboard, boundary - 1, 32, boundary - 1, 32);
                    gesture(keyboard, boundary, 32, boundary, 32);
                    gesture(keyboard, boundary - 1, 32, boundary, 32);
                    gesture(keyboard, 10, 63, 10, 64);
                    gesture(keyboard, 10, 64, 10, 64);
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                assertEquals(List.of(0, 1, 0), clicks);
                clicks.clear();
            }
        }
    }

    @Test public void highestRankedSuggestionStaysCenteredWithMissingSlots() {
        try (ActivityScenario<HelpActivity> host = ActivityScenario.launch(HelpActivity.class)) {
            host.onActivity(activity -> {
                LinearLayout strip = new LinearLayout(activity);
                strip.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
                for (int index = 0; index < 3; index++) {
                    TextView slot = new TextView(activity);
                    slot.setGravity(android.view.Gravity.CENTER);
                    strip.addView(slot, new LinearLayout.LayoutParams(0, -1, 1));
                }
                activity.setContentView(strip);
                assertTrue(strip.isAttachedToWindow());
                List<String> accepted = new ArrayList<>();
                for (int width : new int[]{317, 389, 721}) {
                    List<String> candidates = List.of("HELLO", "help", "held", "helm");
                    for (int count = 0; count <= candidates.size(); count++) {
                        GagKeyboardService.bindSuggestionSlots(strip, candidates.subList(0, count), accepted::add);
                        strip.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(43, View.MeasureSpec.EXACTLY));
                        strip.layout(0, 0, width, 43);
                        TextView left = (TextView) strip.getChildAt(0);
                        TextView center = (TextView) strip.getChildAt(1);
                        TextView right = (TextView) strip.getChildAt(2);
                        assertEquals(0, left.getLeft());
                        assertEquals(left.getRight(), center.getLeft());
                        assertEquals(center.getRight(), right.getLeft());
                        assertEquals(width, right.getRight());
                        assertTrue(Math.abs(width - center.getLeft() - center.getRight()) <= 2);
                        assertEquals(count >= 2 ? "help" : "", left.getText().toString());
                        assertEquals(count >= 1 ? "HELLO" : "", center.getText().toString());
                        assertEquals(count >= 3 ? "held" : "", right.getText().toString());
                        assertEquals(count >= 2 ? View.VISIBLE : View.INVISIBLE, left.getVisibility());
                        assertEquals(count >= 1 ? View.VISIBLE : View.INVISIBLE, center.getVisibility());
                        assertEquals(count >= 3 ? View.VISIBLE : View.INVISIBLE, right.getVisibility());
                        if (count > 0) {
                            assertTrue(center.performClick());
                            assertEquals(List.of("HELLO"), accepted);
                        } else assertTrue(accepted.isEmpty());
                        accepted.clear();
                    }
                }
            });
        }
    }

    @Test public void committedDeleteWitnessWorksWithoutSelectionCallback() {
        try (ActivityScenario<HelpActivity> host = ActivityScenario.launch(HelpActivity.class)) {
            host.onActivity(activity -> {
                EditableConnection input = new EditableConnection(new View(activity));
                GagDeleteSuggestion delete = GagDeleteSuggestion.capture(input);
                assertEquals(-1, delete.deletedCursor(input));
                input.text = "hello";
                input.cursor = 5;
                assertEquals(5, delete.deletedCursor(input));
                assertEquals(5, delete.selectionCursor(input, 5, 5, -1));
                assertEquals(-1, delete.selectionCursor(input, 4, 4, -1));
                assertEquals(-1, delete.selectionCursor(input, 5, 5, 0));
                assertEquals(-1, delete.deletedCursor(new EditableConnection(new View(activity))));
                input.text = "hexlo";
                assertEquals(-1, delete.deletedCursor(input));
                input.text = "hello!";
                assertEquals(-1, delete.deletedCursor(input));
            });
        }
    }

    private static final class EditableConnection extends BaseInputConnection {
        String text = "hellox";
        int cursor = text.length();

        EditableConnection(View view) { super(view, true); }

        @Override public SurroundingText getSurroundingText(int beforeLength, int afterLength, int flags) {
            int offset = Math.max(0, cursor - beforeLength);
            int end = Math.min(text.length(), cursor + afterLength);
            return new SurroundingText(text.substring(offset, end), cursor - offset, cursor - offset, offset);
        }

        @Override public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
            ExtractedText extracted = new ExtractedText();
            extracted.text = text;
            extracted.selectionStart = cursor;
            extracted.selectionEnd = cursor;
            return extracted;
        }
    }

    @Test public void releasingTheOwningPointerDoesNotGiveTheKeyToAnotherFinger() {
        List<Integer> clicks = new ArrayList<>();
        try (ActivityScenario<HelpActivity> host = ActivityScenario.launch(HelpActivity.class)) {
            host.onActivity(activity -> {
                LinearLayout keyboard = new LinearLayout(activity);
                LinearLayout row = row(keyboard, clicks);
                View key = row.getChildAt(0);
                activity.setContentView(keyboard, new LinearLayout.LayoutParams(400, 64));
                assertTrue(key.isAttachedToWindow());
                key.layout(0, 0, 40, 64);
                long time = SystemClock.uptimeMillis();
                pointerEvent(key, time, MotionEvent.ACTION_DOWN, 10);
                pointerEvent(key, time,
                        MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        10, 20);
                pointerEvent(key, time, MotionEvent.ACTION_POINTER_UP, 40, 20);
                pointerEvent(key, time, MotionEvent.ACTION_UP, 20);
                assertTrue(clicks.isEmpty());

                pointerEvent(key, time, MotionEvent.ACTION_DOWN, 10);
                pointerEvent(key, time,
                        MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        10, 20);
                pointerEvent(key, time, MotionEvent.ACTION_POINTER_UP, 39, 20);
                pointerEvent(key, time, MotionEvent.ACTION_UP, 20);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(List.of(0), clicks);
        }
    }

    @Test public void backspaceReleaseOutsideNeverFallsBackToSingleTap() {
        List<Integer> clicks = new ArrayList<>();
        try (ActivityScenario<HelpActivity> host = ActivityScenario.launch(HelpActivity.class)) {
            host.onActivity(activity -> {
                Button del = new Button(activity);
                activity.setContentView(del, new LinearLayout.LayoutParams(40, 64));
                assertTrue(del.isAttachedToWindow());
                del.layout(0, 0, 40, 64);
                del.setOnClickListener(ignored -> clicks.add(1));
                del.setOnTouchListener(new GagKeyPress(new Handler(Looper.getMainLooper()), () -> clicks.add(2)));
                gesture(del, 39, 32, 40, 32);
                gesture(del, 39, 32, 39, 32);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(List.of(1), clicks);
        }
    }

    private static LinearLayout row(LinearLayout keyboard, List<Integer> clicks) {
        LinearLayout row = new LinearLayout(keyboard.getContext());
        row.setBaselineAligned(false);
        row.setMotionEventSplittingEnabled(true);
        keyboard.addView(row, new LinearLayout.LayoutParams(-1, 64));
        Handler handler = new Handler(Looper.getMainLooper());
        for (int index = 0; index < 10; index++) {
            int slot = index;
            Button key = new Button(row.getContext());
            key.setBackground(new InsetDrawable(new ColorDrawable(0xff333333), 3, 6, 3, 6));
            key.setOnClickListener(ignored -> clicks.add(slot));
            key.setOnTouchListener(new GagKeyPress(handler, null));
            row.addView(key, new LinearLayout.LayoutParams(0, 64, 1));
        }
        return row;
    }

    private static void gesture(View view, int downX, int downY, int upX, int upY) {
        long time = SystemClock.uptimeMillis();
        dispatch(view, MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, downX, downY, 0));
        dispatch(view, MotionEvent.obtain(time, time, MotionEvent.ACTION_UP, upX, upY, 0));
    }

    private static void pointerEvent(View view, long time, int action, int... x) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[x.length];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[x.length];
        for (int index = 0; index < x.length; index++) {
            properties[index] = new MotionEvent.PointerProperties();
            properties[index].id = x.length == 1 && action == MotionEvent.ACTION_UP ? 1 : index;
            coordinates[index] = new MotionEvent.PointerCoords();
            coordinates[index].x = x[index];
            coordinates[index].y = 32;
        }
        dispatch(view, MotionEvent.obtain(time, time, action, x.length, properties, coordinates,
                0, 0, 1, 1, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0));
    }

    private static void dispatch(View view, MotionEvent event) {
        try {
            view.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }
}
