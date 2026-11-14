package com.subhub.app.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.UiAutomation;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

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

public final class GagSuggestionServiceAndroidTest {
    private static final String FIXTURE =
            "com.subhub.app.test/com.subhub.app.atmosphere.GagEditorFixtureActivity";
    private static final String IME = "com.subhub.app/.atmosphere.GagKeyboardService";
    private static final long TIMEOUT_MS = 8_000L;

    @Test public void explicitSuggestionsPreserveExistingSpaceAndOwnOnlyTheirNewSeparator() throws Exception {
        openEditor(true);
        correctHello();
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

    private static void acceptHello() throws Exception {
        openEditor(false);
        correctHello();
        assertText("hello ");
    }

    private static void correctHello() {
        for (char letter : "helllo".toCharArray()) key(String.valueOf(letter));
        key("hello");
    }

    private static void openEditor(boolean middle) throws Exception {
        assertEquals(IME, shell("settings get secure default_input_method").strip());
        String error = shell("am start -W -f 0x10008000 -a " + GagEditorFixtureActivity.ACTION
                + " -n " + FIXTURE + " --ez gag_spacing_middle " + middle);
        assertTrue(error, error.contains("Status: ok"));
        await(By.pkg("com.subhub.app.test").desc(GagEditorFixtureActivity.EDITOR));
        await(By.pkg("com.subhub.app").text("q"));
        assertText(middle ? " next" : "");
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

    private static UiDevice device() {
        return UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    }
}
