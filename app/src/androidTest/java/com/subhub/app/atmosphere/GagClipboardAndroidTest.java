package com.subhub.app.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ClipData;
import android.content.ClipDescription;
import android.os.PersistableBundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;

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

    @Test public void pasteRunsWordRulesAndPreservesSeparators() {
        String text = "don't,  skip\tme\n192.168.1.14";
        assertEquals(text, GagClipboard.applyWords(text, word -> word));
        assertEquals("[don't],  [skip]\t[me]\n192.168.1.14",
                GagClipboard.applyWords(text, word -> "[" + word + "]"));
    }
}
