package com.subhub.app.journal;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class JournalEntryTest {
    @Test public void blankTitleUsesOriginalCreationTimeRatherThanLastEdit() {
        JournalEntry entry = new JournalEntry(ID, 0, 259200000, "", "Body", null, List.of());
        assertEquals(JournalEntry.defaultTitle(entry.created), entry.displayTitle());
        assertNotEquals(JournalEntry.defaultTitle(entry.updated), entry.displayTitle());
        assertTrue(entry.displayTitle().length() <= JournalEntry.MAX_TITLE);
    }
    @Test public void legacyProjectionCopiesOnlyWireTitleAndPreservesStoredTitleAndEntryMetadata() throws Exception {
        JournalEntry entry = new JournalEntry(ID, 100, 101, "Chosen title", "Body", null, List.of());
        org.json.JSONArray rows = new org.json.JSONArray().put(new JSONObject().put("seq", 3)
                .put("change", new JSONObject().put("id", ID).put("deleted", false).put("entry", entry.json())));
        org.json.JSONArray legacy = JournalPeer.projectedChanges(rows, false);
        JSONObject projected = legacy.getJSONObject(0).getJSONObject("change").getJSONObject("entry");
        JournalEntry.keys(projected, java.util.Set.of("schema", "id", "created", "updated", "text", "audio", "attachments"));
        assertEquals(entry.text, JournalEntry.parse(projected).text);
        assertEquals(entry.id, JournalEntry.parse(projected).id);
        assertEquals(entry.created, JournalEntry.parse(projected).created);
        assertEquals(entry.updated, JournalEntry.parse(projected).updated);
        assertEquals(entry.title, rows.getJSONObject(0).getJSONObject("change").getJSONObject("entry").getString("title"));
        assertEquals(entry.title, JournalPeer.projectedChanges(rows, true).getJSONObject(0)
                .getJSONObject("change").getJSONObject("entry").getString("title"));
    }
    @Test public void titlesRoundTripAndLegacyEntriesUseCreationTimeWithoutChangingBody() throws Exception {
        JournalEntry titled = new JournalEntry(ID, 100, 101, "Reflection 😀", "Original body\nSecond line", null, List.of());
        JournalEntry restored = JournalEntry.parse(titled.json());
        assertEquals(titled.title, restored.title); assertEquals(titled.text, restored.text);
        assertEquals(titled.title, restored.displayTitle());
        JSONObject legacy = titled.json(); legacy.remove("title");
        restored = JournalEntry.parse(legacy);
        assertEquals("", restored.title); assertEquals(titled.text, restored.text);
        assertEquals(JournalEntry.defaultTitle(100), restored.displayTitle());
        assertEquals(ID, restored.id); assertEquals(100, restored.created); assertEquals(101, restored.updated);
        assertFalse(restored.json().has("title"));
    }
    @Test public void optionalTitleStillRejectsWrongTypesUnknownFieldsAndUnboundedOrMultilineValues() throws Exception {
        JournalEntry entry = new JournalEntry(ID, 0, 0, "x".repeat(JournalEntry.MAX_TITLE), "Body", null, List.of());
        assertEquals(JournalEntry.MAX_TITLE, JournalEntry.parse(entry.json()).title.length());
        for (Object title : new Object[]{JSONObject.NULL, 4, "x".repeat(JournalEntry.MAX_TITLE + 1), "Two\nlines", " spaced "}) {
            JSONObject value = entry.json().put("title", title);
            assertThrows(Exception.class, () -> JournalEntry.parse(value));
        }
        JSONObject unknown = entry.json().put("other", "hidden");
        assertThrows(Exception.class, () -> JournalEntry.parse(unknown));
        assertEquals("Two lines", JournalEntry.cleanTitle(" Two\nlines\u0000 "));
    }
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
