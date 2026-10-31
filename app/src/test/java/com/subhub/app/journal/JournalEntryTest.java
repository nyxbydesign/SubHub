package com.subhub.app.journal;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class JournalEntryTest {
    private static final String ID = "0123456789abcdef0123456789abcdef";
    private static JournalAttachment file(int index, long bytes) {
        return new JournalAttachment(String.format(java.util.Locale.ROOT, "%064x", index), "Photo.png", "image/png", bytes);
    }
    @Test public void audioCanReplaceTextAndFiveFilesRoundTripWithoutLocalUris() throws Exception {
        JournalAttachment audio = new JournalAttachment("a".repeat(64), "Voice note.m4a", "audio/mp4", 100);
        List<JournalAttachment> files = new ArrayList<>();
        for (int i = 0; i < 5; i++) files.add(file(i, JournalAttachment.MAX_BYTES));
        JournalEntry entry = new JournalEntry(ID, 100, 101, "", audio, files);
        JournalEntry restored = JournalEntry.parse(entry.json());
        assertEquals(5, restored.attachments.size()); assertNotNull(restored.audio);
        assertEquals(6, restored.media().size());
        assertFalse(entry.json().toString().contains("content://"));
        assertThrows(UnsupportedOperationException.class, () -> restored.attachments.clear());
        files.add(file(6, 1));
        assertThrows(IllegalArgumentException.class, () -> new JournalEntry(ID, 100, 101, "", audio, files));
    }
    @Test public void textSizeEmptyContentAndFileSizeHaveExactBoundaries() {
        new JournalEntry(ID, 0, 0, "a".repeat(JournalEntry.MAX_TEXT), null, List.of());
        assertThrows(IllegalArgumentException.class, () -> new JournalEntry(ID, 0, 0, "a".repeat(JournalEntry.MAX_TEXT + 1), null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new JournalEntry(ID, 0, 0, "", null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> file(1, 0));
        assertThrows(IllegalArgumentException.class, () -> file(1, JournalAttachment.MAX_BYTES + 1));
        new JournalEntry(ID, 0, 0, "", null, List.of(file(1, 1)));
    }
    @Test public void voiceNotesAreCappedAtTenMebibytesAcrossConstructionAndParsing() throws Exception {
        JournalAttachment atLimit = new JournalAttachment("b".repeat(64), "Voice note.m4a", "audio/mp4", JournalEntry.MAX_RECORDING_BYTES);
        JournalEntry entry = new JournalEntry(ID, 0, 0, "", atLimit, List.of());
        assertEquals(JournalEntry.MAX_RECORDING_BYTES, JournalEntry.parse(entry.json()).audio.length);
        JournalAttachment tooLarge = new JournalAttachment("b".repeat(64), "Voice note.m4a", "audio/mp4", JournalEntry.MAX_RECORDING_BYTES + 1);
        assertThrows(IllegalArgumentException.class, () -> new JournalEntry(ID, 0, 0, "", tooLarge, List.of()));
        JSONObject untrusted = entry.json(); untrusted.getJSONObject("audio").put("length", JournalEntry.MAX_RECORDING_BYTES + 1);
        assertThrows(IllegalArgumentException.class, () -> JournalEntry.parse(untrusted));
        new JournalEntry(ID, 0, 0, "", null, List.of(tooLarge));
    }
    @Test public void untypedMetadataDuplicatesAndUnexpectedFieldsAreRejected() throws Exception {
        JSONObject value = new JournalEntry(ID, 0, 0, "Entry", null, List.of(file(1, 1))).json();
        value.put("created", 0.5);
        assertThrows(Exception.class, () -> JournalEntry.parse(value));
        value.put("created", 0).put("privateUri", "content://private");
        assertThrows(Exception.class, () -> JournalEntry.parse(value));
        assertThrows(IllegalArgumentException.class, () -> new JournalEntry(ID, 0, 0, "Entry", null, List.of(file(1, 1), file(1, 1))));
        assertThrows(IllegalArgumentException.class, () -> new JournalAttachment("../secret", "File", "text/plain", 1));
        assertThrows(IllegalArgumentException.class, () -> new JournalAttachment("a".repeat(64), "../File", "text/plain", 1));
    }
    @Test public void controlCharactersAndUnsafeDisplayNamesCannotReachStoredMetadata() {
        assertEquals("hello\nworld", JournalEntry.cleanText("  hello\nworld\u0000 "));
        assertEquals("File.pdf", JournalAttachment.cleanName("\u202eFile.pdf\u0000"));
        String name = JournalAttachment.cleanName("😀".repeat(150));
        assertTrue(name.length() <= JournalAttachment.MAX_NAME);
        assertFalse(Character.isHighSurrogate(name.charAt(name.length() - 1)));
        assertThrows(IllegalArgumentException.class, () -> new JournalEntry(ID, 2, 1, "Text", null, List.of()));
    }
}
