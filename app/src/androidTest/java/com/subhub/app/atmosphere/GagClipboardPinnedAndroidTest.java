package com.subhub.app.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

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
}
