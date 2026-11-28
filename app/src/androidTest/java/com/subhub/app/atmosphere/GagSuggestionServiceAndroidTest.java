package com.subhub.app.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.UiAutomation;
import android.content.Context;
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
import com.subhub.app.UiAutomationStateRule;
import com.subhub.app.settings.SharedPreferenceTestRestore;

import org.junit.Rule;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class GagSuggestionServiceAndroidTest {
    private static final String FIXTURE =
            "com.subhub.app.test/com.subhub.app.atmosphere.GagEditorFixtureActivity";
    private static final String IME = "com.subhub.app/.atmosphere.GagKeyboardService";
    private static final long TIMEOUT_MS = 8_000L;
    @Rule public final UiAutomationStateRule automationState = new UiAutomationStateRule();

    @Test public void emojiPickerKeepsTypingAcrossTabsAndAbcReturnsToLetters() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var preferences = context.getSharedPreferences(GagEmojiUsage.PREFS, Context.MODE_PRIVATE);
        var original = preferences.getAll();
        assertTrue("Enable learning on the synthetic guest", new GagPersonalDictionary(context).isLearningEnabled());
        try {
            assertTrue(preferences.edit().clear().commit());
            openEditor(false);
            key("?123");
            await(By.pkg("com.subhub.app").desc(label(R.string.gag_keyboard_comma_hint))).longClick();
            await(By.pkg("com.subhub.app").desc("grinning face")).click();
            await(By.pkg("com.subhub.app").desc("grinning face")).click();
            await(By.pkg("com.subhub.app").desc("grinning face with big eyes")).click();
            assertText("😀😀😃");

            await(By.pkg("com.subhub.app").desc("People & Body")).click();
            await(By.pkg("com.subhub.app").text("People & Body"));
            await(By.pkg("com.subhub.app").desc(label(R.string.gag_emoji_frequently_used))).click();
            UiObject2 first = await(By.pkg("com.subhub.app").desc("grinning face"));
            UiObject2 second = await(By.pkg("com.subhub.app").desc("grinning face with big eyes"));
            assertTrue("Higher count should outrank the latest insertion",
                    first.getVisibleCenter().x < second.getVisibleCenter().x);
            first.click();
            assertText("😀😀😃😀");
            await(By.pkg("com.subhub.app").desc(label(R.string.gag_emoji_back_to_keyboard))
                    .text("ABC")).click();
            await(By.pkg("com.subhub.app").text("q"));
        } finally {
            SharedPreferenceTestRestore.restore(preferences, original);
        }
    }

    @Test public void explicitSuggestionsPreserveExistingSpaceAndDeferNewSeparators() throws Exception {
        openEditor(true);
        correctHello(" next");
        assertText("hello next");
        for (char letter : "new".toCharArray()) key(String.valueOf(letter));
        assertText("hello new next");

        acceptHello();
        key(",");
        assertText("hello,");
        key("x");
        assertText("hello, x");

        acceptHello();
        keyDescription(R.string.gag_keyboard_symbols);
        key("(");
        assertText("hello()");

        acceptHello();
        keyDescription(R.string.gag_keyboard_symbols);
        key(")");
        assertText("hello)");

        acceptHello();
        keyDescription(R.string.gag_keyboard_space);
        assertText("hello ");
        key("x");
        assertText("hello x");

        openEditor(false, false, false, true);
        correctHello("");
        assertText("hello");
        key("d");
        await(By.pkg("com.subhub.app.test").desc(GagEditorFixtureActivity.BATCHED_SEPARATOR));
        assertText("hello d");
        for (char letter : "efinately".toCharArray()) key(String.valueOf(letter));
        await(By.pkg("com.subhub.app").text("definitely"));
        assertText("hello definately");
        key("definitely");
        assertText("hello definitely");
    }

    @Test public void movingTheCaretOrDeletingCancelsThePendingSeparator() throws Exception {
        acceptHello();
        assertSuggestions("there", "everyone", "again");
        shell("input keyevent KEYCODE_DPAD_LEFT");
        assertTrue(device().wait(Until.gone(By.pkg("com.subhub.app").text("there")), TIMEOUT_MS));
        shell("input keyevent KEYCODE_DPAD_RIGHT");
        key("x");
        assertText("hellox");

        acceptHello();
        keyDescription(R.string.gag_keyboard_delete);
        assertText("hell");
        key("x");
        assertText("hellx");
    }

    @Test public void openingBracketsInsertMatchingClosersAfterTheCursor() throws Exception {
        for (String pair : new String[]{"()", "[]", "{}"}) {
            acceptHello();
            keyDescription(R.string.gag_keyboard_symbols);
            if (!pair.equals("()")) keyDescription(R.string.gag_keyboard_more_symbols);
            String opener = pair.substring(0, 1);
            key(opener);
            assertText("hello" + pair);
            keyDescription(R.string.gag_keyboard_delete);
            assertText("hello");
            key(opener);
            keyDescription(R.string.gag_keyboard_letters);
            key("x");
            assertText("hello" + opener + "x" + pair.substring(1));
        }

        openEditor(false);
        keyDescription(R.string.gag_keyboard_symbols);
        key(")");
        assertText(")");
        assertTrue(device().pressKeyCode(android.view.KeyEvent.KEYCODE_DPAD_LEFT));
        key("(");
        assertText("())");
        keyDescription(R.string.gag_keyboard_delete);
        assertText(")");
    }

    @Test public void emptyBackspaceRestoresTheIdleSuggestionStrip() throws Exception {
        openEditor(false);
        assertSuggestions("I", "You", "Ich");
        keyDescription(R.string.gag_keyboard_delete);
        device().waitForIdle();
        assertText("");
        assertSuggestions("I", "You", "Ich");
    }

    @Test public void committedBackspaceShowsTheCorrectionWithoutApplyingIt() throws Exception {
        openEditor(false);
        shell("input text definatelyx");
        assertText("definatelyx");
        keyDescription(R.string.gag_keyboard_delete);
        assertText("definately");
        key("definitely");
        assertText("definitely");
    }

    @Test public void backspaceAfterAHeartClusterRefreshesSuggestionsBeforeFollowingText() throws Exception {
        assertCorrectionAfterClusterDeletion("heart", GagEditorFixtureActivity.GRAPHEME_PREFIX);
    }

    @Test public void backspaceAfterAFamilyClusterRefreshesSuggestionsBeforeFollowingText() throws Exception {
        assertCorrectionAfterClusterDeletion("family", GagEditorFixtureActivity.FAMILY_PREFIX);
    }

    private static void assertCorrectionAfterClusterDeletion(String cluster, String beforeCaret) throws Exception {
        openEditor(" --es " + GagEditorFixtureActivity.GRAPHEME_DELETE + " " + cluster, beforeCaret + " tail");
        keyDescription(R.string.gag_keyboard_delete);
        assertText("definately tail");
        key("definitely");
        assertText("definitely tail");
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
        assertText("hello");
        key("there");
        assertText("hello there");
        assertSuggestions("how", "I", "again");
        key(",");
        assertText("hello there,");

        openEditor(true);
        correctHello(" next");
        assertText("hello next");
        assertTrue(device().wait(Until.gone(By.pkg("com.subhub.app").text("there")), TIMEOUT_MS));
    }

    @Test public void explicitSpaceShowsThreeWordsAndTheClipboardKeepsStarterSuggestions() throws Exception {
        openEditor(false, true);
        await(By.pkg("com.subhub.app").text(GagEditorFixtureActivity.CLIPBOARD_TEXT));
        await(By.pkg("com.subhub.app").text("You"));
        await(By.pkg("com.subhub.app").text("I"));
        assertText("");

        keyDescription(R.string.gag_keyboard_clipboard);
        keyDescription(R.string.gag_keyboard_back_to_keys);
        assertTrue(device().wait(Until.gone(By.pkg("com.subhub.app").text(GagEditorFixtureActivity.CLIPBOARD_TEXT)),
                TIMEOUT_MS));
        for (char letter : "thanks".toCharArray()) key(String.valueOf(letter));
        keyDescription(R.string.gag_keyboard_space);
        assertSuggestions("for", "again", "and");
        assertText("thanks ");
        key("again");
        assertText("thanks again");
        assertSuggestions("and", "but", "or");
    }

    @Test public void repeatedTypedWordPairPromotesItsContinuationInTheStrip() throws Exception {
        for (String starter : new String[]{"a", "b"}) {
            openEditor(false);
            typeWordAndSpace(starter);
            typeWordAndSpace("dolvak");
            typeWordAndSpace("zembok");
            assertText(starter + " dolvak zembok ");
        }

        openEditor(false);
        typeWordAndSpace("dolvak");
        assertText("dolvak ");
        var resources = InstrumentationRegistry.getInstrumentation().getTargetContext().getResources();
        String language = resources.getConfiguration().getLocales().get(0).getLanguage();
        var baseline = GagNextWords.predict("dolvak ", language);
        var learned = await(By.pkg("com.subhub.app").text("zembok"));
        int first = await(By.pkg("com.subhub.app").text(baseline.get(0))).getVisibleCenter().x;
        int second = await(By.pkg("com.subhub.app").text(baseline.get(1))).getVisibleCenter().x;
        int learnedCenter = learned.getVisibleCenter().x;
        assertTrue("The highest ranked suggestion occupies the middle slot",
                Math.min(first, second) < learnedCenter && learnedCenter < Math.max(first, second));
        learned.click();
        assertText("dolvak zembok");
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
        holdAndRelease("a", "ä", true);
        assertText("ä");
        keyDescription(R.string.gag_keyboard_symbols);
        holdAndRelease("1", "⅒", true);
        assertText("ä⅒");
        keyDescription(R.string.gag_keyboard_more_symbols);
        holdAndRelease("•", "·", true);
        assertText("ä⅒·");
    }

    @Test public void oneOptionHoldCancelsInPlaceAndOnlyAReleasedSlideInsertsIt() throws Exception {
        openEditor(false);
        holdAndRelease("q", "1", false);
        assertText("");
        key("q");
        assertText("q");
        holdAndRelease("q", "1", true);
        assertText("q1");
    }

    private static void holdAndRelease(String key, String option, boolean slide) {
        Rect anchor = await(By.pkg("com.subhub.app").text(key)).getVisibleBounds();
        // The same character can appear as a passive hint on the letter key.
        var choice = By.clazz(android.widget.Button.class).pkg("com.subhub.app").text(option);
        long down = SystemClock.uptimeMillis();
        touch(down, MotionEvent.ACTION_DOWN, anchor.centerX(), anchor.centerY());
        Rect target = anchor;
        try {
            Rect optionBounds = await(choice).getVisibleBounds();
            if (slide) {
                target = optionBounds;
                touch(down, MotionEvent.ACTION_MOVE, target.centerX(), target.centerY());
                await(By.clazz(android.widget.Button.class).pkg("com.subhub.app").text(option).selected(true));
            }
        } finally {
            touch(down, MotionEvent.ACTION_UP, target.centerX(), target.centerY());
        }
        assertTrue(device().wait(Until.gone(choice), TIMEOUT_MS));
    }

    @Test public void characterPreviewTracksTouchAndDoesNotCommitOnSlideOrCancel() throws Exception {
        Path failedCapture = GagEditorFixtureActivity.reserveFailureCapture("gag-key-preview");
        openEditor(false);
        keyDescription(R.string.gag_keyboard_symbols);
        Rect key = await(By.pkg("com.subhub.app").text("@")).getVisibleBounds();
        assertPreviewPixels(key, false, failedCapture);
        long down = SystemClock.uptimeMillis();
        touch(down, MotionEvent.ACTION_DOWN, key.centerX(), key.centerY());
        try {
            assertPreviewPixels(key, true, failedCapture);
            assertText("");
            touch(down, MotionEvent.ACTION_MOVE, key.right + 1, key.centerY());
            assertPreviewPixels(key, false, failedCapture);
        } finally {
            touch(down, MotionEvent.ACTION_UP, key.right + 1, key.centerY());
        }
        assertText("");

        down = SystemClock.uptimeMillis();
        touch(down, MotionEvent.ACTION_DOWN, key.centerX(), key.centerY());
        try {
            assertPreviewPixels(key, true, failedCapture);
        } finally {
            touch(down, MotionEvent.ACTION_CANCEL, key.centerX(), key.centerY());
        }
        assertPreviewPixels(key, false, failedCapture);
        assertText("");
        key("@");
        assertPreviewPixels(key, false, failedCapture);
        assertText("@");
    }

    @Test public void heldBackspaceShowsUndoAfterTwoWordsAndRedoOnlyAfterRestoring() throws Exception {
        openEditor(false);
        shell("input text one%stwo");
        assertText("one two");
        var delete = await(By.pkg("com.subhub.app").desc(label(R.string.gag_keyboard_delete))).getVisibleCenter();
        shell("input swipe " + delete.x + " " + delete.y + " " + delete.x + " " + delete.y + " 1550");
        assertText("");
        deletionAction(R.string.gag_keyboard_redo, false);
        deletionAction(R.string.gag_keyboard_undo, true).click();
        assertText("one two");
        deletionAction(R.string.gag_keyboard_redo, true).click();
        assertText("");
        deletionAction(R.string.gag_keyboard_undo, true).click();
        assertText("one two");

        shell("input keyevent KEYCODE_DPAD_LEFT");
        assertTrue(device().wait(Until.gone(By.pkg("com.subhub.app").text(label(R.string.gag_keyboard_redo))),
                TIMEOUT_MS));
        assertText("one two");
    }

    @Test public void selectedTwoWordBackspacePreservesUnicodeSpacesAndSelectionThroughUndoAndRedo() throws Exception {
        openEditor(false, false, true);
        keyDescription(R.string.gag_keyboard_delete);
        assertText("keep  tail");
        deletionAction(R.string.gag_keyboard_undo, true).click();
        assertText(GagEditorFixtureActivity.DELETION_TEXT);
        deletionAction(R.string.gag_keyboard_redo, true).click();
        assertText("keep  tail");
    }

    @Test public void commaHintAndWallpaperAreVisibleOnTheTypingKeyboard() throws Exception {
        Path failedCapture = GagEditorFixtureActivity.reserveFailureCapture("gag-comma-hint");
        openEditor(false);
        UiObject2 comma = await(By.pkg("com.subhub.app").desc(label(R.string.gag_keyboard_comma_hint)));
        Rect bounds = comma.getVisibleBounds();
        String[] labels = {"q", "t", "p", "s", "g", "k", "v", "n"};
        Rect[] letterBounds = new Rect[labels.length];
        for (int i = 0; i < labels.length; i++) {
            letterBounds[i] = await(By.pkg("com.subhub.app").text(labels[i])).getVisibleBounds();
        }
        assertKeyboardArtworkPixels(bounds, letterBounds, failedCapture);
        assertTrue(device().click(bounds.centerX(), bounds.top + bounds.height() / 4));
        assertText(",");

        await(By.pkg("com.subhub.app").desc(label(R.string.gag_keyboard_comma_hint))).longClick();
        await(By.pkg("com.subhub.app").desc(label(R.string.gag_emoji_back_to_keyboard)));
        assertText(",");
    }

    private static void assertKeyboardArtworkPixels(Rect bounds, Rect[] letterBounds, Path failedCapture)
            throws IOException {
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        long deadline = SystemClock.uptimeMillis() + 2_000;
        int radius = Math.min(bounds.width(), bounds.height()) / 4;
        while (true) {
            Bitmap screen = automation.takeScreenshot();
            assertNotNull(screen);
            try {
                // The stroke is above the comma; the dim wallpaper and key face stay below this range.
                int lightPixels = 0;
                for (int y = bounds.top + bounds.height() / 16; y < bounds.top + bounds.height() * 7 / 16; y++) {
                    for (int x = bounds.centerX() - radius; x < bounds.centerX() + radius; x++) {
                        int pixel = screen.getPixel(x, y);
                        if (android.graphics.Color.red(pixel) >= 175
                                && android.graphics.Color.green(pixel) >= 175
                                && android.graphics.Color.blue(pixel) >= 175) lightPixels++;
                    }
                }
                int artworkRange = keyFaceArtworkRange(screen, letterBounds);
                if (lightPixels >= 5 && artworkRange >= 11) return;
                if (SystemClock.uptimeMillis() >= deadline) {
                    try (var output = Files.newOutputStream(failedCapture, StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE)) {
                        assertTrue(screen.compress(Bitmap.CompressFormat.PNG, 100, output));
                    }
                    throw new AssertionError("Typing keyboard should show the emoji hint and wallpaper through keys"
                            + " (hint pixels=" + lightPixels + ", face range=" + artworkRange + "). Evidence: "
                            + failedCapture);
                }
            } finally {
                screen.recycle();
            }
            SystemClock.sleep(50);
        }
    }

    private static int keyFaceArtworkRange(Bitmap screen, Rect[] letterBounds) {
        int darkest = 255;
        int lightest = 0;
        for (Rect key : letterBounds) {
            // Sample the plain face above each centered glyph and away from the upper-right hint.
            int halfWidth = key.width() / 10;
            for (int y = key.top + key.height() / 7; y < key.top + key.height() / 4; y++) {
                for (int x = key.centerX() - halfWidth; x < key.centerX() + halfWidth; x++) {
                    int red = android.graphics.Color.red(screen.getPixel(x, y));
                    darkest = Math.min(darkest, red);
                    lightest = Math.max(lightest, red);
                }
            }
        }
        return lightest - darkest;
    }

    private static UiObject2 deletionAction(int resource, boolean enabled) {
        return await(By.pkg("com.subhub.app").text(label(resource)).enabled(enabled));
    }

    private static void acceptHello() throws Exception {
        openEditor(false);
        correctHello("");
        assertText("hello");
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
        openEditor(middle, seedClipboard, false);
    }

    private static void openEditor(boolean middle, boolean seedClipboard, boolean selectDeletionText) throws Exception {
        openEditor(middle, seedClipboard, selectDeletionText, false);
    }

    private static void openEditor(boolean middle, boolean seedClipboard, boolean selectDeletionText,
            boolean watchSeparatorBatch) throws Exception {
        openEditor(" --ez gag_spacing_middle " + middle
                + " --ez gag_seed_clipboard " + seedClipboard
                + " --ez gag_deletion_selection " + selectDeletionText
                + " --ez " + GagEditorFixtureActivity.WATCH_SEPARATOR_BATCH + " " + watchSeparatorBatch,
                selectDeletionText ? GagEditorFixtureActivity.DELETION_TEXT : middle ? " next" : "");
    }

    private static void openEditor(String extras, String expectedText) throws Exception {
        assertEquals(IME, shell("settings get secure default_input_method").strip());
        String error = shell("am start -W -f 0x10008000 -a " + GagEditorFixtureActivity.ACTION
                + " -n " + FIXTURE + extras);
        assertTrue(error, error.contains("Status: ok"));
        await(By.pkg("com.subhub.app.test").desc(GagEditorFixtureActivity.EDITOR));
        assertNotNull("The fixture editor opened, but Gag did not show its letter keyboard",
                device().wait(Until.findObject(By.pkg("com.subhub.app").text("q")), TIMEOUT_MS));
        assertText(expectedText);
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

    private static void typeWordAndSpace(String word) {
        for (char letter : word.toCharArray()) key(String.valueOf(letter));
        keyDescription(R.string.gag_keyboard_space);
    }

    private static void keyDescription(int stringResource) {
        await(By.pkg("com.subhub.app").desc(label(stringResource))).click();
    }

    private static String label(int resource) {
        return InstrumentationRegistry.getInstrumentation().getTargetContext().getString(resource);
    }

    private static UiObject2 await(BySelector selector) {
        UiObject2 object = device().wait(Until.findObject(selector), TIMEOUT_MS);
        assertNotNull(selector.toString(), object);
        return object;
    }

    private static void assertText(String expected) {
        BySelector editor = By.pkg("com.subhub.app.test").descStartsWith(GagEditorFixtureActivity.EDITOR);
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

    private static void assertPreviewPixels(Rect key, boolean expected, Path failedCapture) {
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
                    try (var output = Files.newOutputStream(failedCapture, StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE)) {
                        assertTrue("Could not encode " + failedCapture,
                                screen.compress(Bitmap.CompressFormat.PNG, 100, output));
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
        assertEquals("Key preview above the active symbol. Evidence: " + failedCapture, expected, shown);
    }

    private static UiDevice device() {
        return UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    }
}
