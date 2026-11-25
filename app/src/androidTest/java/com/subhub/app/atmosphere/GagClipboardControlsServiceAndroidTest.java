package com.subhub.app.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import com.subhub.app.R;
import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class GagClipboardControlsServiceAndroidTest {
    private static final long TIMEOUT = 8_000L;
    private static final String APP = "com.subhub.app";
    private static final String FIXTURE = "com.subhub.app.test";
    private static final String EDITED = "Synthetic edited clipboard text";

    @Test public void screenshotsIncludeClipboardSuggestionAndKeyboardPanel() throws Exception {
        var target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("Grant full photo access in the synthetic guest to avoid its permission prompt",
                GagScreenshotClipboard.Access.FULL, GagScreenshotClipboard.access(target));
        openFixture(true);
        assertCapturedLabel(await(By.pkg(APP).desc(GagEditorFixtureActivity.CLIPBOARD_TEXT)),
                "clipboard suggestion");

        openClipboard();
        assertCapturedLabel(await(By.pkg(APP).text(label(R.string.gag_clipboard_title))), "clipboard panel");
    }

    @Test
    @SdkSuppress(minSdkVersion = 33)
    public void clipboardSharesTheSuggestionRowAndDragDoesNotPaste() throws Exception {
        var target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals(GagScreenshotClipboard.Access.FULL, GagScreenshotClipboard.access(target));
        openFixture(false);
        BySelector fixedTool = By.pkg(APP).desc(label(R.string.gag_keyboard_clipboard));
        Rect toolBounds = await(fixedTool).getVisibleBounds();
        shell("input text thanks%s");
        assertEditor("thanks ");
        await(By.pkg(APP).text("and"));
        shell("input keycombination " + KeyEvent.KEYCODE_CTRL_LEFT + " " + KeyEvent.KEYCODE_A);
        shell("input keycombination " + KeyEvent.KEYCODE_CTRL_LEFT + " " + KeyEvent.KEYCODE_C);
        shell("input keyevent " + KeyEvent.KEYCODE_MOVE_END);

        BySelector clip = By.pkg(APP).desc("thanks ");
        Rect clipBounds = await(clip).getVisibleBounds();
        Rect wordBounds = await(By.pkg(APP).text("for")).getVisibleBounds();
        await(By.pkg(APP).text("again"));
        assertTrue(Math.abs(clipBounds.width() - wordBounds.width()) <= 1);
        assertEquals(wordBounds.right, clipBounds.left);
        BySelector trailingWord = By.pkg(APP).text("and");
        assertFalse(device().hasObject(trailingWord));

        int travel = 2 * clipBounds.width();
        shell("input swipe " + clipBounds.centerX() + " " + clipBounds.centerY() + " "
                + (clipBounds.centerX() - travel) + " " + clipBounds.centerY() + " 380");
        assertEditor("thanks ");
        Rect revealed = await(trailingWord).getVisibleBounds();
        assertTrue("The trailing word did not reach full width: " + revealed,
                revealed.width() >= wordBounds.width() - 1);
        assertEquals(toolBounds, await(fixedTool).getVisibleBounds());

        await(clip).click();
        await(editor().text("thanks thanks "));
        assertTrue(device().wait(Until.gone(clip), TIMEOUT));
        Rect left = await(By.pkg(APP).text("again")).getVisibleBounds();
        Rect middle = await(By.pkg(APP).text("for")).getVisibleBounds();
        Rect right = await(trailingWord).getVisibleBounds();
        assertEquals(left.right, middle.left);
        assertEquals(middle.right, right.left);
        assertEquals(clipBounds.right, right.right);
        assertEquals(toolBounds, await(fixedTool).getVisibleBounds());
    }

    @Test public void editSavesPrivatelyWhileTapPastesAndHoldSlidesToPinOrDelete() throws Exception {
        var target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("Grant full photo access in the synthetic guest to isolate the controls from the permission prompt",
                GagScreenshotClipboard.Access.FULL, GagScreenshotClipboard.access(target));
        openFixture(true);
        BySelector freshClip = By.pkg(APP).desc(GagEditorFixtureActivity.CLIPBOARD_TEXT);
        await(freshClip);
        shell("input text Draft");
        assertEditor("Draft");
        await(freshClip);
        openClipboard();
        UiObject2 firstEdit = openEditPopup();
        assertPopupAboveKeyboard(firstEdit);
        assertPopupActionsFit();
        UiObject2 key = await(By.pkg(APP).text(java.util.regex.Pattern.compile("[qQ]")));
        String letter = key.getText();
        key.click();
        await(popupInput().text(GagEditorFixtureActivity.CLIPBOARD_TEXT + letter));
        await(By.pkg(APP).text(label(android.R.string.cancel))).click();
        assertPopupClosed();
        assertTrue(device().wait(Until.gone(freshClip), TIMEOUT));
        assertEditor("Draft");

        openClipboard();
        UiObject2 secondEdit = openEditPopup();
        assertEquals(GagEditorFixtureActivity.CLIPBOARD_TEXT, secondEdit.getText());
        secondEdit.setText(EDITED);
        await(By.pkg(APP).text(label(R.string.gag_clipboard_save))).click();
        assertPopupClosed();
        assertEditor("Draft");

        openClipboard();
        slideTo(EDITED, label(R.string.gag_clipboard_pin));
        await(By.pkg(APP).text(label(R.string.gag_clipboard_pinned)));
        await(By.pkg(APP).text(EDITED)).click();
        assertEditor("Draft" + EDITED);

        openClipboard();
        slideTo(EDITED, label(R.string.gag_clipboard_unpin));
        assertTrue(device().wait(Until.gone(By.pkg(APP).text(label(R.string.gag_clipboard_pinned))), TIMEOUT));
        slideTo(EDITED, label(R.string.delete));
        assertTrue(device().wait(Until.gone(By.pkg(APP).text(EDITED)), TIMEOUT));
        assertEditor("Draft" + EDITED);
    }

    @Test public void numberPadSpaceHasNoVisibleLabelAndStillInsertsSpace() throws Exception {
        openFixture(false);
        await(By.pkg(APP).desc(label(R.string.gag_keyboard_symbols))).click();
        await(By.pkg(APP).desc(label(R.string.gag_keyboard_numbers))).click();
        UiObject2 space = await(By.pkg(APP).desc(label(R.string.gag_keyboard_space)));
        String text = space.getText();
        assertTrue(text == null || text.isEmpty());
        space.click();
        assertEditor(" ");
    }

    @Test public void longClipboardCardPastesTheEntireEntry() throws Exception {
        var target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals(GagScreenshotClipboard.Access.FULL, GagScreenshotClipboard.access(target));
        var prefs = GagScreenshotClipboard.preferences(target);
        String legacyRetention = "retention";
        boolean hadSetting = prefs.contains(legacyRetention);
        boolean previous = prefs.getBoolean(legacyRetention, true);
        try {
            assertTrue(prefs.edit().putBoolean(legacyRetention, false).commit());
            openFixture(true, true);
            openClipboard();
            UiObject2 card = await(By.pkg(APP).textStartsWith(GagEditorFixtureActivity.LONG_CLIPBOARD_PREFIX));
            assertTrue(card.getText().length() <= 257);
            card.click();
            String expected = GagEditorFixtureActivity.longClipboardResult(GagEditorFixtureActivity.LONG_CLIPBOARD_TEXT);
            UiObject2 result = device().wait(Until.findObject(By.pkg(FIXTURE).desc(expected)), TIMEOUT);
            if (result == null) {
                UiObject2 editor = await(By.pkg(FIXTURE).descStartsWith(GagEditorFixtureActivity.EDITOR));
                assertEquals(expected, editor.getContentDescription());
            }
        } finally {
            var edit = prefs.edit();
            if (hadSetting) edit.putBoolean(legacyRetention, previous);
            else edit.remove(legacyRetention);
            assertTrue(edit.commit());
        }
    }

    private static void openFixture(boolean seed) throws Exception {
        openFixture(seed, false);
    }

    private static void openFixture(boolean seed, boolean longText) throws Exception {
        assertEquals(APP + "/.atmosphere.GagKeyboardService",
                shell("settings get secure default_input_method").trim());
        String started = shell("am start -W -f 0x10008000 -a " + GagEditorFixtureActivity.ACTION
                + " -n " + FIXTURE + "/" + GagEditorFixtureActivity.class.getName()
                + " --ez gag_seed_clipboard " + seed
                + " --ez " + GagEditorFixtureActivity.SEED_LONG_CLIPBOARD + " " + longText
                + " --ez " + GagEditorFixtureActivity.SUPPRESS_CLIPBOARD_OVERLAY + " " + seed);
        assertTrue(started, started.contains("Status: ok"));
        await(By.pkg(APP).text("q"));
        assertEditor("");
    }

    private static void openClipboard() {
        BySelector tool = By.pkg(APP).desc(label(R.string.gag_keyboard_clipboard));
        if (!device().hasObject(tool)) await(editor()).click();
        UiObject2 button = await(tool);
        boolean overlayBefore = systemClipboardOverlayPresent();
        Rect clickBounds = button.getVisibleBounds();
        button.click();
        if (!device().wait(Until.hasObject(By.pkg(APP).text(label(R.string.gag_clipboard_title))), TIMEOUT)) {
            throw new AssertionError("Clipboard panel missing after toolbar tap"
                    + " [buttonBounds=" + clickBounds + ", systemClipboardOverlayBefore=" + overlayBefore
                    + ", systemClipboardOverlayAfter=" + systemClipboardOverlayPresent()
                    + ", toolbarVisible=" + device().hasObject(tool)
                    + ", backToKeysVisible=" + device().hasObject(By.pkg(APP).desc(label(R.string.gag_keyboard_back_to_keys)))
                    + ", fixtureEditorVisible=" + device().hasObject(editor()) + "]");
        }
        assertFalse(device().hasObject(By.pkg(APP).clazz(android.widget.Switch.class)));
    }

    private static boolean systemClipboardOverlayPresent() {
        return InstrumentationRegistry.getInstrumentation().getUiAutomation().getWindows().stream()
                .anyMatch(window -> "ClipboardOverlay".equals(String.valueOf(window.getTitle())));
    }

    private static void assertCapturedLabel(UiObject2 label, String surface) throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Rect bounds = label.getVisibleBounds();
        long deadline = SystemClock.uptimeMillis() + 2_000;
        do {
            Bitmap screen = instrumentation.getUiAutomation().takeScreenshot();
            assertNotNull(screen);
            try {
                Rect sample = new Rect(bounds);
                assertTrue("Label bounds outside screenshot: " + bounds,
                        sample.intersect(0, 0, screen.getWidth(), screen.getHeight()));
                int labelPixels = 0;
                for (int y = sample.top; y < sample.bottom; y++) {
                    for (int x = sample.left; x < sample.right; x++) {
                        int pixel = screen.getPixel(x, y);
                        // Gag draws these labels in #f6f6f6. Their pixels must survive the capture.
                        if (Math.abs(Color.red(pixel) - 246) <= 2
                                && Math.abs(Color.green(pixel) - 246) <= 2
                                && Math.abs(Color.blue(pixel) - 246) <= 2
                                && ++labelPixels >= 8) return;
                    }
                }
                if (SystemClock.uptimeMillis() >= deadline) {
                    Bitmap crop = Bitmap.createBitmap(screen, sample.left, sample.top,
                            sample.width(), sample.height());
                    try {
                        try (var output = instrumentation.getTargetContext().openFileOutput(
                                "gag-keyboard-screenshot-failure.png", android.content.Context.MODE_PRIVATE)) {
                            crop.compress(Bitmap.CompressFormat.PNG, 100, output);
                        }
                    } finally {
                        crop.recycle();
                    }
                    throw new AssertionError("Screenshot captured " + labelPixels + " pixels from the "
                            + surface + " label at " + bounds);
                }
            } finally {
                screen.recycle();
            }
            SystemClock.sleep(50);
        } while (true);
    }

    private static UiObject2 openEditPopup() {
        await(By.pkg(APP).text(label(R.string.gag_clipboard_edit))).click();
        await(By.pkg(APP).text(GagEditorFixtureActivity.CLIPBOARD_TEXT)).click();
        await(By.pkg(APP).clazz(android.widget.TextView.class).text(label(R.string.gag_clipboard_edit)));
        return await(popupInput());
    }

    private static void assertPopupAboveKeyboard(UiObject2 input) throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var resources = instrumentation.getTargetContext().getResources();
        float density = resources.getDisplayMetrics().density;
        boolean enlargedText = resources.getConfiguration().fontScale > 1.3f;
        long deadline = SystemClock.uptimeMillis() + TIMEOUT;
        Rect panel;
        Rect keyboardBounds;
        do {
            panel = input.getParent().getVisibleBounds();
            keyboardBounds = inputMethodBounds();
            int gap = keyboardBounds.top - panel.bottom;
            if (input.isFocused() && !panel.isEmpty() && panel.width() <= Math.round(300 * density)
                    && (enlargedText || panel.height() <= Math.round(240 * density)) && !keyboardBounds.isEmpty()
                    && gap >= 0 && gap <= Math.round(24 * density)) return;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Edit must be a bounded popup immediately above a shown input method"
                + " [panel=" + panel + ", inputMethod=" + keyboardBounds + ", inputFocused=" + input.isFocused()
                + ", inputMethodState=" + inputMethodState() + "]");
    }

    private static void assertPopupActionsFit() {
        var cancel = await(By.pkg(APP).clazz(Button.class).text(label(android.R.string.cancel)));
        var save = await(By.pkg(APP).clazz(Button.class).text(label(R.string.gag_clipboard_save)));
        Rect cancelBounds = cancel.getVisibleBounds();
        Rect saveBounds = save.getVisibleBounds();
        assertTrue("Cancel and Save overlap", !Rect.intersects(cancelBounds, saveBounds));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            var activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .stream().filter(GagClipboardEditActivity.class::isInstance).findFirst().orElseThrow();
            var window = activity.getWindow();
            assertTrue(window.isFloating());
            assertTrue((window.getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            assertActionLabelFits(window.getDecorView(), activity.getString(android.R.string.cancel));
            assertActionLabelFits(window.getDecorView(), activity.getString(R.string.gag_clipboard_save));
        });
    }

    private static void assertActionLabelFits(View decor, String label) {
        var views = new java.util.ArrayList<View>();
        decor.findViewsWithText(views, label, View.FIND_VIEWS_WITH_TEXT);
        Button button = views.stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(view -> label.contentEquals(view.getText())).findFirst().orElseThrow();
        var layout = button.getLayout();
        int minTouch = Math.round(48 * button.getResources().getDisplayMetrics().density);
        assertTrue("Control is smaller than its touch target: " + label, button.getHeight() >= minTouch);
        assertNotNull(layout);
        assertEquals(label.length(), layout.getLineEnd(layout.getLineCount() - 1));
        for (int line = 0; line < layout.getLineCount(); line++) {
            assertEquals("Label is ellipsized: " + label, 0, layout.getEllipsisCount(line));
            assertTrue("Label is clipped: " + label, layout.getLineWidth(line) <= layout.getWidth() + 1);
        }
    }

    private static Rect inputMethodBounds() {
        var automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        Rect bounds = new Rect();
        for (var window : automation.getWindows()) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                window.getBoundsInScreen(bounds);
                if (!bounds.isEmpty()) break;
            }
        }
        return bounds;
    }

    private static String inputMethodState() throws Exception {
        var state = java.util.regex.Pattern.compile(
                "\\b(mInputShown|mShowRequested|mShowExplicitlyRequested|mShowForced|mImeWindowVis)"
                        + "=(true|false|0x[0-9a-fA-F]+|-?\\d+)(?=\\s|$)").matcher(shell("dumpsys input_method"));
        var selected = new java.util.StringJoiner(", ");
        for (int index = 0; index < 8 && state.find(); index++)
            selected.add(state.group());
        return selected.toString();
    }

    private static void assertPopupClosed() {
        assertTrue(device().wait(Until.gone(popupInput()), TIMEOUT));
    }

    private static BySelector popupInput() {
        return By.pkg(APP).clazz(android.widget.EditText.class);
    }

    private static void slideTo(String text, String action) {
        Rect card = await(By.pkg(APP).text(text)).getVisibleBounds();
        long start = SystemClock.uptimeMillis();
        touch(start, MotionEvent.ACTION_DOWN, card.centerX(), card.centerY());
        Rect target = card;
        try {
            BySelector option = By.pkg(APP).clazz(android.widget.Button.class).text(action);
            target = await(option).getVisibleBounds();
            touch(start, MotionEvent.ACTION_MOVE, target.centerX(), target.centerY());
            await(By.pkg(APP).clazz(android.widget.Button.class).text(action).selected(true));
        } finally {
            touch(start, MotionEvent.ACTION_UP, target.centerX(), target.centerY());
        }
    }

    private static void touch(long start, int action, int x, int y) {
        MotionEvent event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0);
        try {
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            assertTrue(InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent(event, true));
        } finally {
            event.recycle();
        }
    }

    private static void assertEditor(String text) {
        UiObject2 editor = await(editor());
        String actual = editor.getText();
        assertEquals(text, actual == null ? "" : actual);
    }

    private static BySelector editor() {
        return By.pkg(FIXTURE).desc(GagEditorFixtureActivity.EDITOR);
    }

    private static String label(int resource) {
        return InstrumentationRegistry.getInstrumentation().getTargetContext().getString(resource);
    }

    private static UiDevice device() {
        return UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    }

    private static UiObject2 await(BySelector selector) {
        UiObject2 result = device().wait(Until.findObject(selector), TIMEOUT);
        assertNotNull(selector.toString(), result);
        return result;
    }

    private static String shell(String command) throws Exception {
        var automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try (InputStream output = new ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            for (int count; (count = output.read(buffer)) != -1; ) bytes.write(buffer, 0, count);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
