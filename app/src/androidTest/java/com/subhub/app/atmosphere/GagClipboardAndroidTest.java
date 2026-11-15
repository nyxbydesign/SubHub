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
    @Test public void acceptsOnlyNamedImagesInScreenshotDirectories() {
        assertTrue(GagScreenshotClipboard.screenshot("Screenshot_20261122-102021.png", "image/png",
                "Pictures/Screenshots/", true));
        assertTrue(GagScreenshotClipboard.screenshot("Screenshot_20261122-102021_edited.jpg", "image/jpeg",
                "DCIM/Screenshots/", true));
        assertTrue(GagScreenshotClipboard.screenshot("Screenshot-123.webp", "image/webp",
                "/storage/emulated/0/Pictures/Screenshots/Screenshot-123.webp", false));
        assertFalse(GagScreenshotClipboard.screenshot("Screenshot_20261122-102021.png", "image/png",
                "DCIM/Camera/", true));
        assertFalse(GagScreenshotClipboard.screenshot("Family.png", "image/png", "Pictures/Screenshots/", true));
        assertFalse(GagScreenshotClipboard.screenshot("Screenshot_1.png", "image/png",
                "Pictures/Screenshots/Private/", true));
        assertFalse(GagScreenshotClipboard.screenshot("Screenshot_1.png", "video/mp4",
                "Pictures/Screenshots/", true));
    }

    @Test public void savedScreenshotRevisionReplacesByMediaRowAndRevocationKeepsCopiedText() {
        GagClipboard history = new GagClipboard();
        history.remember("Explicitly copied", 10L, 10L, 15L);
        Uri row = Uri.parse("content://media/external_primary/images/media/42");
        GagClipboardImage original = screenshotImage(0xff000011);
        GagClipboardImage edited = screenshotImage(0xff9955cc);
        var first = new GagScreenshotClipboard.Candidate(row, "image/png", 11L,
                new GagScreenshotClipboard.Revision(1, original.bytes().length, 1));
        var second = new GagScreenshotClipboard.Candidate(row, "image/png", 12L,
                new GagScreenshotClipboard.Revision(1, edited.bytes().length, 2));
        history.rememberScreenshot(new GagScreenshotClipboard.Import(first, original), 13L, 15L);
        assertEquals(original, history.entries(15L).get(0).image());
        history.rememberScreenshot(new GagScreenshotClipboard.Import(second, edited), 13L, 15L);
        assertEquals(2, history.entries(15L).size());
        assertEquals(edited, history.entries(15L).get(0).image());
        assertEquals(second.revision(), history.screenshotRevisions(15L).get(row));
        assertTrue(history.retainScreenshots(java.util.Set.of()));
        assertEquals(1, history.entries(15L).size());
        assertEquals("Explicitly copied", history.entries(15L).get(0).text());
    }

    @Test public void screenshotCaptureRequiresSeparateConsentAndCurrentFullPhotoGrant() {
        var target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String fixturePrefs = "gag_screenshot_access_test";
        var grants = new java.util.HashSet<String>();
        var scoped = new android.content.ContextWrapper(target) {
            @Override public int checkSelfPermission(String permission) {
                return grants.contains(permission) ? android.content.pm.PackageManager.PERMISSION_GRANTED
                        : android.content.pm.PackageManager.PERMISSION_DENIED;
            }

            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                assertEquals(GagScreenshotClipboard.PREFS, name);
                return super.getSharedPreferences(fixturePrefs, mode);
            }
        };
        target.deleteSharedPreferences(fixturePrefs);
        try {
            GagScreenshotClipboard.enable(scoped);
            assertFalse(GagScreenshotClipboard.enabled(scoped));
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                grants.add(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
                assertEquals(GagScreenshotClipboard.Access.SELECTED, GagScreenshotClipboard.access(scoped));
                GagScreenshotClipboard.enable(scoped);
                assertFalse(GagScreenshotClipboard.enabled(scoped));
            }
            grants.add(GagScreenshotClipboard.requestedPermissions()[0]);
            assertFalse(GagScreenshotClipboard.enabled(scoped));
            GagScreenshotClipboard.enable(scoped);
            assertTrue(GagScreenshotClipboard.enabled(scoped));
            grants.clear();
            assertFalse(GagScreenshotClipboard.enabled(scoped));
            GagScreenshotClipboard.disable(scoped);
            grants.add(GagScreenshotClipboard.requestedPermissions()[0]);
            assertFalse(GagScreenshotClipboard.enabled(scoped));
        } finally { target.deleteSharedPreferences(fixturePrefs); }
    }

    @Test
    @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    public void mediaStorePublishesScreenshotAndReadsTheEditedBytesFromTheSameRow() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var resolver = context.getContentResolver();
        var collection = android.provider.MediaStore.Images.Media.getContentUri(
                android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY);
        long enabledSince = System.currentTimeMillis() - 2_000L;
        var values = new android.content.ContentValues();
        values.put("_display_name", "Screenshot_fixture_" + java.util.UUID.randomUUID() + ".png");
        values.put("mime_type", "image/png");
        values.put("relative_path", "Pictures/Screenshots/");
        values.put("is_pending", 1);
        Uri row = resolver.insert(collection, values);
        assertNotNull(row);
        try {
            GagClipboardImage original = screenshotImage(0xff229944);
            GagClipboardImage edited = screenshotImage(0xff443388);
            writeImage(resolver, row, original);
            var pending = scanMedia(resolver, enabledSince, java.util.Map.of(), java.util.Set.of());
            assertFalse("A pending image cannot enter the clipboard", pending.eligible().contains(row));
            var published = new android.content.ContentValues();
            published.put("is_pending", 0);
            assertEquals(1, resolver.update(row, published, null, null));
            var imported = scanMedia(resolver, enabledSince, java.util.Map.of(), java.util.Set.of());
            var first = mediaImage(imported, row);
            assertEquals(0xff229944, first.image().thumbnail().getPixel(6, 6));
            writeImage(resolver, row, edited);
            var revision = scanMedia(resolver, enabledSince,
                    java.util.Map.of(row, first.media().revision()),
                    java.util.Set.of(android.content.ContentUris.parseId(row)));
            var changed = mediaImage(revision, row);
            assertEquals(row, changed.media().uri());
            assertEquals(0xff443388, changed.image().thumbnail().getPixel(6, 6));
        } finally {
            resolver.delete(row, null, null);
        }
        assertFalse(scanMedia(resolver, enabledSince, java.util.Map.of(), java.util.Set.of()).eligible().contains(row));
    }

    private static GagScreenshotClipboard.Scan scanMedia(android.content.ContentResolver resolver,
            long enabledSince, java.util.Map<Uri, GagScreenshotClipboard.Revision> previous,
            java.util.Set<Long> changed) {
        var scan = GagScreenshotClipboard.scan(resolver, enabledSince, 0, System.currentTimeMillis(),
                previous, java.util.Map.of(), changed, new android.os.CancellationSignal());
        assertNotNull("Screenshot MediaStore query failed", scan);
        return scan;
    }

    private static GagScreenshotClipboard.Import mediaImage(GagScreenshotClipboard.Scan scan, Uri row) {
        var entry = scan.imported().stream().filter(item -> row.equals(item.media().uri())).findFirst();
        assertTrue("Missing published screenshot from MediaStore", entry.isPresent());
        return entry.get();
    }

    private static void writeImage(android.content.ContentResolver resolver, Uri row, GagClipboardImage image)
            throws java.io.IOException {
        try (var stream = resolver.openOutputStream(row, "rwt")) {
            assertNotNull(stream);
            stream.write(image.bytes());
        }
    }

    private static GagClipboardImage screenshotImage(int color) {
        Bitmap source = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        try {
            source.eraseColor(color);
            assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, data));
            GagClipboardImage result = GagClipboardImage.decode(data.toByteArray(), new ClipDescription("", new String[]{"image/png"}));
            assertNotNull(result);
            return result;
        } finally { source.recycle(); }
    }

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
