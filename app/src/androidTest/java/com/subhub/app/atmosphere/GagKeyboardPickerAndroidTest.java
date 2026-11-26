package com.subhub.app.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.os.SystemClock;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.subhub.app.R;
import com.subhub.app.UiAutomationStateRule;

import org.junit.Rule;
import org.junit.Test;

@SdkSuppress(minSdkVersion = 28)
public final class GagKeyboardPickerAndroidTest {
    private static final long TIMEOUT = 5_000L;
    private static final String STOCK_PACKAGE = "com.google.android.inputmethod.latin";
    @Rule public final UiAutomationStateRule automation = new UiAutomationStateRule();

    @Test public void holdingSpaceOpensPickerAndOnlyAnotherChoiceSwitchesKeyboard() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        instrumentation.getUiAutomation();
        UiDevice device = UiDevice.getInstance(instrumentation);
        InputMethodManager manager = context.getSystemService(InputMethodManager.class);
        InputMethodInfo stockKeyboard = manager.getEnabledInputMethodList().stream()
                .filter(method -> STOCK_PACKAGE.equals(method.getPackageName())).findFirst().orElse(null);
        assertNotNull("The owned guest must leave stock Gboard enabled", stockKeyboard);
        var subtypes = manager.getEnabledInputMethodSubtypeList(stockKeyboard, true);
        assertTrue("Select one English (US) subtype in the owned guest", subtypes.size() == 1);
        InputMethodSubtype expectedSubtype = subtypes.get(0);
        assertEquals("en-US", expectedSubtype.getLanguageTag().isEmpty()
                ? expectedSubtype.getLocale().replace('_', '-') : expectedSubtype.getLanguageTag());

        String gagKeyboard = new ComponentName(context, GagKeyboardService.class).flattenToShortString();
        assertEquals("The owned runner must select Gagging first", gagKeyboard, selectedIme(device));
        String fixture = "com.subhub.app.test/" + GagEditorFixtureActivity.class.getName();
        device.executeShellCommand("am start -W -n " + fixture + " -a "
                + GagEditorFixtureActivity.ACTION + " --ez gag_spacing_middle true");
        String title = context.getString(R.string.gag_keyboard_change);
        String space = context.getString(R.string.gag_keyboard_space);
        String currentLabel = context.getString(R.string.gag_keyboard);
        CharSequence stockLabel = stockKeyboard.loadLabel(context.getPackageManager());
        String editorText = findEditor(device).getText();
        assertEquals(" next", editorText);

        try {
            openPicker(device, title, space);
            UiObject2 current = device.wait(Until.findObject(By.text(currentLabel).checked(true)), TIMEOUT);
            assertNotNull("Gagging should be the checked row", current);
            current.click();
            assertTrue(device.wait(Until.gone(By.text(title)), TIMEOUT));
            assertEquals(gagKeyboard, selectedIme(device));
            assertEquals(editorText, findEditor(device).getText());

            openPicker(device, title, space);
            device.executeShellCommand("input keyevent KEYCODE_BACK");
            assertTrue(device.wait(Until.gone(By.text(title)), TIMEOUT));
            assertEquals(gagKeyboard, selectedIme(device));
            assertEquals(editorText, findEditor(device).getText());

            openPicker(device, title, space);
            CharSequence language = expectedSubtype.getDisplayName(context, STOCK_PACKAGE,
                    stockKeyboard.getServiceInfo().applicationInfo);
            UiObject2 stock = device.wait(Until.findObject(By.text(language + "\n" + stockLabel)), TIMEOUT);
            assertNotNull("Show the enabled stock English subtype and keyboard name", stock);
            stock.click();
            long deadline = SystemClock.uptimeMillis() + TIMEOUT;
            while (!stockKeyboard.getId().equals(selectedIme(device)) && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(100L);
            }
            assertEquals(stockKeyboard.getId(), selectedIme(device));
            assertEquals(expectedSubtype, manager.getCurrentInputMethodSubtype());
            assertEquals(editorText, findEditor(device).getText());
        } finally {
            if (!gagKeyboard.equals(selectedIme(device))) device.executeShellCommand("ime set " + gagKeyboard);
        }
    }

    private static void openPicker(UiDevice device, String title, String space) throws Exception {
        UiObject2 spacebar = device.wait(Until.findObject(By.desc(space)), TIMEOUT);
        assertNotNull("Gagging spacebar missing", spacebar);
        var center = spacebar.getVisibleCenter();
        device.executeShellCommand("input swipe " + center.x + " " + center.y + " "
                + center.x + " " + center.y + " 900");
        assertNotNull("The IME should show its own keyboard picker",
                device.wait(Until.findObject(By.text(title)), TIMEOUT));
    }

    private static UiObject2 findEditor(UiDevice device) {
        UiObject2 editor = device.wait(Until.findObject(By.desc(GagEditorFixtureActivity.EDITOR)), TIMEOUT);
        assertNotNull("Synthetic editor missing", editor);
        return editor;
    }

    private static String selectedIme(UiDevice device) throws Exception {
        return device.executeShellCommand("settings get secure default_input_method").trim();
    }
}
