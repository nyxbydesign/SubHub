package com.subhub.app.atmosphere;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

import android.content.ClipDescription;
import android.graphics.Bitmap;
import android.graphics.Color;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.UUID;

public final class GagClipboardPinnedAndroidTest {
    @Test public void pinsSurviveExpiryRollingLimitAndReloadWhileClearOnlyRemovesRecents() {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File fixture = new File(context.getCacheDir(), "gag_pins_" + UUID.randomUUID());
        assertTrue(fixture.mkdir());
        try {
            GagClipboardPins store = new GagClipboardPins(fixture);
            GagClipboard clips = new GagClipboard(store);
            clips.remember("Keep me", 100, 100, 100);
            assertTrue(clips.pin(clips.entries(100).get(0), 100));
            long later = 100 + GagClipboard.LIFETIME_MS;
            for (int index = 0; index < 12; index++) {
                clips.remember("Recent " + index, later + index, later + index, later + index);
            }
            assertEquals(9, clips.entries(later + 12).size());
            assertEquals(8, clips.recentEntries(later + 12).size());
            assertTrue(clips.entries(later + 12).get(0).pinned());
            assertEquals("Keep me", clips.entries(later + 12).get(0).text());

            clips.clearThrough(later + 12);
            assertEquals(1, clips.entries(later + 12).size());
            GagClipboard reloaded = new GagClipboard(new GagClipboardPins(fixture));
            assertEquals(clips.entries(later + 12), reloaded.entries(0));
            assertTrue(reloaded.unpin(reloaded.entries(0).get(0), later + 20, 10));
            assertFalse(reloaded.entries(10).get(0).pinned());
            assertTrue(reloaded.entries(10 + GagClipboard.LIFETIME_MS).isEmpty());
            assertTrue(new GagClipboard(store).entries(0).isEmpty());
        } finally {
            new File(fixture, "gag_clipboard_pins.json").delete();
            fixture.delete();
        }
    }

    @Test public void editsPreserveExpiryAndPinnedEditsReplaceTheStoredValue() {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File fixture = new File(context.getCacheDir(), "gag_pins_" + UUID.randomUUID());
        assertTrue(fixture.mkdir());
        try {
            GagClipboardPins store = new GagClipboardPins(fixture);
            GagClipboard clips = new GagClipboard(store);
            clips.remember("Original", 20, 20, 20);
            GagClipboard.Entry original = clips.entries(20).get(0);
            assertFalse(clips.edit(original, "  ", 21, 21));
            assertTrue(clips.edit(original, "Edited\ntext", 21, 21));
            clips.remember("Original", 20, 22, 22);
            GagClipboard.Entry edited = clips.entries(22).get(0);
            assertEquals(1, clips.entries(22).size());
            assertEquals("Edited\ntext", edited.text());
            assertEquals(original.expiresAt(), edited.expiresAt());
            assertTrue(clips.pin(edited, 22));
            long later = original.expiresAt() + 1;
            assertTrue(clips.edit(clips.entries(later).get(0), "Pinned edit", later, later));
            assertEquals("Pinned edit", new GagClipboard(store).entries(0).get(0).text());
            assertTrue(clips.remove(clips.entries(later).get(0), later));
            assertTrue(new GagClipboard(store).entries(0).isEmpty());
        } finally {
            new File(fixture, "gag_clipboard_pins.json").delete();
            fixture.delete();
        }
    }

    @Test public void imagePinSurvivesRestartWhileUnpinGetsARecentLifetimeAndCleansItsCopy() {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File fixture = new File(context.getCacheDir(), "gag_image_pin_" + UUID.randomUUID());
        assertTrue(fixture.mkdir());
        try {
            GagClipboard pins = new GagClipboard(new GagClipboardPins(fixture));
            Bitmap bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.MAGENTA);
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            try {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded));
            } finally {
                bitmap.recycle();
            }
            byte[] bytes = encoded.toByteArray();
            GagClipboardImage image = GagClipboardImage.decode(bytes, new ClipDescription("", new String[]{"image/png"}));
            pins.remember(image, 100, 100, 100);
            assertTrue(pins.pin(pins.entries(100).get(0), 100));
            assertTrue(pins.recentEntries(100).isEmpty());
            pins.remember(image, 110, 110, 110);
            assertTrue(pins.recentEntries(110).isEmpty());

            GagClipboard restarted = new GagClipboard(new GagClipboardPins(fixture));
            long later = GagClipboard.LIFETIME_MS + 200;
            GagClipboard.Entry pinned = restarted.entries(later).get(0);
            assertTrue(pinned.pinned());
            assertArrayEquals(bytes, pinned.image().bytes());
            assertTrue(restarted.unpin(pinned, later, later));
            GagClipboard.Entry recent = restarted.entries(later).get(0);
            assertFalse(recent.pinned());
            assertArrayEquals(bytes, recent.image().bytes());
            assertTrue(new GagClipboard(new GagClipboardPins(fixture)).entries(later).isEmpty());
            assertTrue(restarted.entries(later + GagClipboard.LIFETIME_MS).isEmpty());
            assertEquals(1, java.util.Objects.requireNonNull(fixture.list()).length);
        } finally {
            for (File file : java.util.Objects.requireNonNull(fixture.listFiles())) file.delete();
            fixture.delete();
        }
    }

}
