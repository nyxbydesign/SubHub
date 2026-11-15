package com.subhub.app.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import androidx.test.platform.app.InstrumentationRegistry;
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

    @Test public void editSavesPrivatelyWhileTapPastesAndHoldSlidesToPinOrDelete() throws Exception {
        openFixture(true);
        openClipboard();
        await(By.pkg(APP).text(label(R.string.gag_clipboard_edit))).click();
        await(By.pkg(APP).text(GagEditorFixtureActivity.CLIPBOARD_TEXT)).click();
        await(By.pkg(APP).clazz(android.widget.EditText.class)).setText(EDITED);
        await(By.pkg(APP).text(label(R.string.gag_clipboard_save))).click();
        assertEditor("");

        openClipboard();
        slideTo(EDITED, label(R.string.gag_clipboard_pin));
        await(By.pkg(APP).text(label(R.string.gag_clipboard_pinned)));
        await(By.pkg(APP).text(EDITED)).click();
        assertEditor(EDITED);

        openClipboard();
        slideTo(EDITED, label(R.string.gag_clipboard_unpin));
        assertTrue(device().wait(Until.gone(By.pkg(APP).text(label(R.string.gag_clipboard_pinned))), TIMEOUT));
        slideTo(EDITED, label(R.string.delete));
        assertTrue(device().wait(Until.gone(By.pkg(APP).text(EDITED)), TIMEOUT));
        assertEditor(EDITED);
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

    private static void openFixture(boolean seed) throws Exception {
        assertEquals(APP + "/.atmosphere.GagKeyboardService",
                shell("settings get secure default_input_method").trim());
        String started = shell("am start -W -f 0x10008000 -a " + GagEditorFixtureActivity.ACTION
                + " -n " + FIXTURE + "/" + GagEditorFixtureActivity.class.getName()
                + " --ez gag_seed_clipboard " + seed);
        assertTrue(started, started.contains("Status: ok"));
        await(By.pkg(APP).text("q"));
        assertEditor("");
    }

    private static void openClipboard() {
        BySelector tool = By.pkg(APP).desc(label(R.string.gag_keyboard_clipboard));
        if (!device().hasObject(tool)) await(editor()).click();
        await(tool).click();
        await(By.pkg(APP).text(label(R.string.gag_clipboard_title)));
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
