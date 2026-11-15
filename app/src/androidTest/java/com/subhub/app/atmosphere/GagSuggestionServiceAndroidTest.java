package com.subhub.app.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.UiAutomation;
import android.graphics.Bitmap;
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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class GagSuggestionServiceAndroidTest {
    private static final String FIXTURE =
            "com.subhub.app.test/com.subhub.app.atmosphere.GagEditorFixtureActivity";
    private static final String IME = "com.subhub.app/.atmosphere.GagKeyboardService";
    private static final long TIMEOUT_MS = 8_000L;

    @Test public void explicitSuggestionsPreserveExistingSpaceAndOwnOnlyTheirNewSeparator() throws Exception {
        openEditor(true);
        correctHello(" next");
        assertText("hello next");

        acceptHello();
        key(",");
        assertText("hello,");

        acceptHello();
        keyDescription(R.string.gag_keyboard_symbols);
        key("(");
        assertText("hello(");

        acceptHello();
        keyDescription(R.string.gag_keyboard_symbols);
        key(")");
        assertText("hello)");

        acceptHello();
        keyDescription(R.string.gag_keyboard_space);
        key(",");
        assertText("hello  ,");
    }

    @Test public void multipleMissedSpacesRequireSuggestionTapAndPreserveTheFollowingText() throws Exception {
        openEditor(true);
        for (char letter : "yeahbsoblike".toCharArray()) key(String.valueOf(letter));
        await(By.pkg("com.subhub.app").text("yeah so like"));
        assertText("yeahbsoblike next");
        key("yeah so like");
        assertText("yeah so like next");

        openEditor(false);
        for (char letter : "hellobworld".toCharArray()) key(String.valueOf(letter));
        await(By.pkg("com.subhub.app").text("hello world"));
        keyDescription(R.string.gag_keyboard_space);
        assertText("hellobworld ");
    }

    @Test public void nextWordsRefreshAfterAnAcceptedSuggestionAndAppendOnlyOnTap() throws Exception {
        acceptHello();
        assertSuggestions("there", "everyone", "again");
        assertText("hello ");
        key("there");
        assertText("hello there ");
        assertSuggestions("how", "I", "again");
        key(",");
        assertText("hello there,");

        openEditor(true);
        correctHello(" next");
        assertText("hello next");
        assertTrue(device().wait(Until.gone(By.pkg("com.subhub.app").text("there")), TIMEOUT_MS));
    }

    @Test public void explicitSpaceShowsThreeWordsWhileTheEmptyEditorPrefersTheClipboard() throws Exception {
        openEditor(false, true);
        await(By.pkg("com.subhub.app").text(GagEditorFixtureActivity.CLIPBOARD_TEXT));
        assertTrue(device().wait(Until.gone(By.pkg("com.subhub.app").text("You")), TIMEOUT_MS));
        assertText("");

        for (char letter : "thanks".toCharArray()) key(String.valueOf(letter));
        keyDescription(R.string.gag_keyboard_space);
        assertSuggestions("for", "again", "and");
        assertTrue(device().wait(Until.gone(By.pkg("com.subhub.app").text(GagEditorFixtureActivity.CLIPBOARD_TEXT)),
                TIMEOUT_MS));
        assertText("thanks ");
        key("again");
        assertText("thanks again ");
        assertSuggestions("and", "but", "or");
    }

    @Test public void apostropheReturnsTheRenderedSymbolPageToLetters() throws Exception {
        openEditor(false);
        keyDescription(R.string.gag_keyboard_symbols);
        key("'");
        await(By.pkg("com.subhub.app").text("q"));
        assertText("'");
    }

    @Test public void spaceReturnsTheFirstSymbolPageToLetters() throws Exception {
        openEditor(false);
        keyDescription(R.string.gag_keyboard_symbols);
        keyDescription(R.string.gag_keyboard_space);
        await(By.pkg("com.subhub.app").text("q"));
        assertText(" ");
    }

    @Test public void spaceReturnsTheSecondSymbolPageToLetters() throws Exception {
        openEditor(false);
        keyDescription(R.string.gag_keyboard_symbols);
        keyDescription(R.string.gag_keyboard_more_symbols);
        keyDescription(R.string.gag_keyboard_space);
        await(By.pkg("com.subhub.app").text("q"));
        assertText(" ");
    }

    @Test public void longPressInsertsAlternativesFromLettersAndBothSymbolPages() throws Exception {
        openEditor(false);
        await(By.pkg("com.subhub.app").text("a")).longClick();
        key("ä");
        assertText("ä");
        keyDescription(R.string.gag_keyboard_symbols);
        await(By.pkg("com.subhub.app").text("1")).longClick();
        key("⅒");
        assertText("ä⅒");
        keyDescription(R.string.gag_keyboard_more_symbols);
        await(By.pkg("com.subhub.app").text("•")).longClick();
        key("·");
        assertText("ä⅒·");
    }

    @Test public void characterPreviewTracksTouchAndDoesNotCommitOnSlideOrCancel() throws Exception {
        openEditor(false);
        keyDescription(R.string.gag_keyboard_symbols);
        Rect key = await(By.pkg("com.subhub.app").text("@")).getVisibleBounds();
        assertPreviewPixels(key, false);
        long down = SystemClock.uptimeMillis();
        touch(down, MotionEvent.ACTION_DOWN, key.centerX(), key.centerY());
        try {
            assertPreviewPixels(key, true);
            assertText("");
            touch(down, MotionEvent.ACTION_MOVE, key.right + 1, key.centerY());
            assertPreviewPixels(key, false);
        } finally {
            touch(down, MotionEvent.ACTION_UP, key.right + 1, key.centerY());
        }
        assertText("");

        down = SystemClock.uptimeMillis();
        touch(down, MotionEvent.ACTION_DOWN, key.centerX(), key.centerY());
        try {
            assertPreviewPixels(key, true);
        } finally {
            touch(down, MotionEvent.ACTION_CANCEL, key.centerX(), key.centerY());
        }
        assertPreviewPixels(key, false);
        assertText("");
        key("@");
        assertPreviewPixels(key, false);
        assertText("@");
    }

    private static void acceptHello() throws Exception {
        openEditor(false);
        correctHello("");
        assertText("hello ");
    }

    private static void correctHello(String after) {
        key("h");
        assertText("h" + after);
        for (char letter : "elllo".toCharArray()) key(String.valueOf(letter));
        key("hello");
    }

    private static void openEditor(boolean middle) throws Exception {
        openEditor(middle, false);
    }

    private static void openEditor(boolean middle, boolean seedClipboard) throws Exception {
        assertEquals(IME, shell("settings get secure default_input_method").strip());
        String error = shell("am start -W -f 0x10008000 -a " + GagEditorFixtureActivity.ACTION
                + " -n " + FIXTURE + " --ez gag_spacing_middle " + middle
                + " --ez gag_seed_clipboard " + seedClipboard);
        assertTrue(error, error.contains("Status: ok"));
        await(By.pkg("com.subhub.app.test").desc(GagEditorFixtureActivity.EDITOR));
        await(By.pkg("com.subhub.app").text("q"));
        assertText(middle ? " next" : "");
    }

    private static void assertSuggestions(String first, String second, String third) {
        for (String candidate : new String[]{first, second, third}) await(By.pkg("com.subhub.app").text(candidate));
    }

    private static String shell(String command) throws Exception {
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try (InputStream output = new ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand(command))) {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            for (int count; (count = output.read(buffer)) != -1; ) result.write(buffer, 0, count);
            return new String(result.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void key(String text) {
        await(By.pkg("com.subhub.app").text(text)).click();
    }

    private static void keyDescription(int stringResource) {
        String label = InstrumentationRegistry.getInstrumentation().getTargetContext().getString(stringResource);
        await(By.pkg("com.subhub.app").desc(label)).click();
    }

    private static UiObject2 await(BySelector selector) {
        UiObject2 object = device().wait(Until.findObject(selector), TIMEOUT_MS);
        assertNotNull(selector.toString(), object);
        return object;
    }

    private static void assertText(String expected) {
        BySelector editor = By.pkg("com.subhub.app.test").desc(GagEditorFixtureActivity.EDITOR);
        long deadline = SystemClock.uptimeMillis() + TIMEOUT_MS;
        String observed;
        do {
            UiObject2 field = device().findObject(editor);
            observed = field == null ? null : field.getText();
            if (field != null && expected.equals(observed == null ? "" : observed)) return;
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        assertEquals(expected, observed);
    }

    private static void touch(long down, int action, int x, int y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            assertTrue(InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent(event, true));
        } finally {
            event.recycle();
        }
    }

    private static void assertPreviewPixels(Rect key, boolean expected) {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        UiAutomation automation = instrumentation.getUiAutomation();
        int height = Math.round(40 * instrumentation.getTargetContext().getResources().getDisplayMetrics().density);
        long deadline = SystemClock.uptimeMillis() + 2_000;
        boolean shown;
        do {
            Bitmap screen = automation.takeScreenshot();
            assertNotNull(screen);
            try {
                int matching = 0;
                for (int y = Math.max(0, key.top - height); y < Math.min(key.top, screen.getHeight()); y += 3) {
                    for (int x = key.left; x < Math.min(key.right, screen.getWidth()); x += 3) {
                        if (screen.getPixel(x, y) == 0xff444446) matching++;
                    }
                }
                shown = matching >= 16;
                if (shown == expected) return;
                if (SystemClock.uptimeMillis() >= deadline) {
                    try (FileOutputStream output = instrumentation.getTargetContext().openFileOutput(
                            "gag-key-preview-failure.png", android.content.Context.MODE_PRIVATE)) {
                        screen.compress(Bitmap.CompressFormat.PNG, 100, output);
                    } catch (IOException failure) {
                        throw new AssertionError("Could not preserve the failed preview screenshot", failure);
                    }
                    break;
                }
            } finally {
                screen.recycle();
            }
            SystemClock.sleep(25);
        } while (true);
        assertEquals("Key preview above the active symbol", expected, shown);
    }

    private static UiDevice device() {
        return UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    }
}
