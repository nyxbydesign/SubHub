package com.subhub.app.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ClipData;
import android.content.ClipDescription;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputContentInfo;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class GagClipboardAndroidTest {
    @Test public void skipsSensitiveRichAndOversizedClips() {
        String plain = "  Hello world  ";
        assertEquals(plain, GagClipboard.plainText(ClipData.newPlainText("label", plain)));
        ClipData sensitive = ClipData.newPlainText("label", "secret");
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
        sensitive.getDescription().setExtras(extras);
        assertNull(GagClipboard.plainText(sensitive));
        assertNull(GagClipboard.plainText(ClipData.newHtmlText("label", "plain", "<i>rich</i>")));
        assertNull(GagClipboard.plainText(new ClipData("label",
                new String[]{ClipDescription.MIMETYPE_TEXT_PLAIN},
                new ClipData.Item(android.net.Uri.parse("content://example/clip")))));
        assertNull(GagClipboard.plainText(ClipData.newPlainText("label",
                "x".repeat(GagClipboard.MAX_TEXT_LENGTH + 1))));
    }

    @Test public void sessionHistoryCapsExpiresAndDoesNotRenewOnRead() {
        assertEquals(21_600_000L, GagClipboard.LIFETIME_MS);
        GagClipboard clips = new GagClipboard();
        for (int index = 0; index < 9; index++) clips.remember("clip " + index, 1000 + index,
                1000 + index, 2000 + index);
        assertEquals(8, clips.entries(2010).size());
        assertEquals("clip 8", clips.entries(2010).get(0).text());

        clips.remember("clip 1", 1001, 1012, 2012);
        assertEquals("clip 8", clips.entries(2012).get(0).text());
        clips.remember("clip 1", 1013, 1013, 2013);
        assertEquals("clip 1", clips.entries(2013).get(0).text());
        assertEquals(1, clips.entries(2008 + GagClipboard.LIFETIME_MS).size());
        assertTrue(clips.entries(2013 + GagClipboard.LIFETIME_MS).isEmpty());
        clips.remember("old", 1013, 1013 + GagClipboard.LIFETIME_MS, 9);
        assertTrue(clips.entries(9).isEmpty());
        clips.remember("current", 2000, 2000, 10);
        clips.clear();
        assertTrue(clips.entries(10).isEmpty());
    }

    @Test public void imagePreviewAndPasteUseTheCopiedImageOnlyWithCompatibleEditor() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        Uri source = Uri.parse("content://example/fixture");
        ClipData clip = new ClipData("Screenshot", new String[]{"image/png"}, new ClipData.Item(source));
        assertEquals(source, GagClipboard.imageUri(clip));
        ClipData wrongScheme = new ClipData("Screenshot", new String[]{"image/png"},
                new ClipData.Item(Uri.parse("file:///fixture.png")));
        assertNull(GagClipboard.imageUri(wrongScheme));
        Bitmap sourceBitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888);
        sourceBitmap.eraseColor(0xff755fa1);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        assertTrue(sourceBitmap.compress(Bitmap.CompressFormat.PNG, 100, png));
        sourceBitmap.recycle();
        GagClipboardImage image = GagClipboardImage.decode(png.toByteArray(), clip.getDescription());
        assertNotNull(image);
        assertEquals(160, image.thumbnail().getWidth());

        GagClipboard history = new GagClipboard();
        history.remember("Line one\nLine two", 2L, 2L, 20L);
        history.remember(image, 3L, 3L, 21L);
        assertEquals(image, history.entries(21L).get(0).image());
        instrumentation.runOnMainSync(() -> {
            var suggestion = GagKeyboardService.clipboardPreview(context, history.entries(21L).get(0),
                    "Image", true);
            assertNotNull(suggestion.findViewWithTag("gag_clipboard_thumbnail"));
            var text = GagKeyboardService.clipboardPreview(context, history.entries(21L).get(1),
                    "Line one\nLine two", false);
            TextView label = (TextView) text.getChildAt(0);
            assertEquals("Line one\nLine two", label.getText().toString());
            assertEquals(3, label.getMaxLines());
        });

        var file = GagClipboardFileProvider.prepare(context, image);
        Uri shared = GagClipboardFileProvider.publish(context, file, 30_000L);
        try {
            instrumentation.runOnMainSync(() -> {
                AtomicReference<InputContentInfo> committed = new AtomicReference<>();
                InputConnection editor = new BaseInputConnection(new View(context), true) {
                    @Override public boolean commitContent(InputContentInfo content, int flags, Bundle opts) {
                        assertEquals(INPUT_CONTENT_GRANT_READ_URI_PERMISSION, flags);
                        committed.set(content);
                        return true;
                    }
                };
                EditorInfo metadata = new EditorInfo();
                metadata.contentMimeTypes = new String[]{"image/jpeg"};
                assertFalse(image.commit(editor, metadata, shared));
                assertNull(committed.get());
                metadata.contentMimeTypes = new String[]{"image/*"};
                assertTrue(image.commit(editor, metadata, shared));
                assertEquals(shared, committed.get().getContentUri());
                assertTrue(committed.get().getDescription().hasMimeType("image/png"));
            });
            try (var content = context.getContentResolver().openInputStream(shared)) {
                assertNotNull(content);
                assertEquals(137, content.read());
            }
        } finally { GagClipboardFileProvider.retireAll(context); }
        assertFalse(file.exists());
        org.junit.Assert.assertThrows(FileNotFoundException.class,
                () -> context.getContentResolver().openInputStream(shared));
    }

    @Test public void retainedHistorySurvivesEditorSwitchButNotClearOrDetachedExpiry() {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> {
            var context = instrumentation.getTargetContext();
            var history = new GagClipboard();
            InputConnection first = new BaseInputConnection(new View(context), true);
            InputConnection second = new BaseInputConnection(new View(context), true);
            EditorInfo ordinary = new EditorInfo();
            ordinary.packageName = "example.editor";
            ordinary.inputType = InputType.TYPE_CLASS_TEXT;
            assertTrue(history.attach(first, ordinary, context.getPackageName()));
            assertTrue(history.allows(first));
            history.remember("Retained text", 1_000, 1_000, 10);

            history.detach();
            assertFalse(history.allows(first));
            EditorInfo protectedEditor = new EditorInfo();
            protectedEditor.packageName = ordinary.packageName;
            protectedEditor.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
            assertFalse(history.attach(second, protectedEditor, context.getPackageName()));
            assertFalse(history.allows(second));
            protectedEditor.inputType = InputType.TYPE_CLASS_TEXT;
            protectedEditor.imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
            assertFalse(history.attach(second, protectedEditor, context.getPackageName()));

            ClipData sensitive = ClipData.newPlainText("Synthetic", "Do not retain");
            PersistableBundle metadata = new PersistableBundle();
            metadata.putBoolean("android.content.extra.IS_SENSITIVE", true);
            sensitive.getDescription().setExtras(metadata);
            assertFalse(GagClipboard.accepts(sensitive.getDescription()));

            assertTrue(history.attach(second, ordinary, context.getPackageName()));
            assertFalse(history.allows(first));
            assertTrue(history.allows(second));
            assertEquals("Retained text", history.entries(12).get(0).text());
            history.clearThrough(1_002);
            history.detach();
            assertTrue(history.attach(first, ordinary, context.getPackageName()));
            assertFalse(history.eligibleTimestamp(1_000));
            history.remember("Retained text", 1_000, 1_003, 13);
            assertTrue(history.entries(13).isEmpty());

            history.remember("New text", 1_003, 1_003, 13);
            history.detach();
            assertEquals(1, history.entries(12 + GagClipboard.LIFETIME_MS).size());
            assertTrue(history.entries(13 + GagClipboard.LIFETIME_MS).isEmpty());
            assertFalse(history.allows(first));
        });
    }

    @Test public void pasteRunsWordRulesAndPreservesSeparators() {
        String text = "don't,  skip\tme\n192.168.1.14";
        assertEquals(text, GagClipboard.applyWords(text, word -> word));
        assertEquals("[don't],  [skip]\t[me]\n192.168.1.14",
                GagClipboard.applyWords(text, word -> "[" + word + "]"));
    }
}
