package com.subhub.app.journal;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class JournalStoreAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private JournalStore store;
    private String database;
    private static String identity(char c) { char[] value = new char[64]; java.util.Arrays.fill(value, c); return new String(value); }
    private final String a = identity('a') + "_first", b = identity('b') + "_second";
    @Before public void setup() { database = "journal-test-" + UUID.randomUUID(); store = new JournalStore(context, database); }
    @After public void cleanup() { store.close(); context.deleteDatabase(database); }
    private JournalEntry entry(String text) { return new JournalEntry("0123456789abcdef0123456789abcdef", 100, 101, text, null, List.of()); }

    @Test public void privateEntryHasNoOutgoingRecordAndRolesCannotReadOrOverwriteOneAnother() throws Exception {
        JournalEntry entry = entry("Private text"); store.save(true, entry, Set.of());
        assertEquals(0, store.outgoing(a, 0).length());
        assertNull(store.entry(false, null, entry.id));
        assertThrows(SecurityException.class, () -> store.save(false, entry, Set.of(a)));
        assertThrows(SecurityException.class, () -> store.delete(false, entry.id));
        assertEquals("Private text", store.entry(true, null, entry.id).text);
    }
    @Test public void withdrawingBeforeFirstDeliveryCoalescesPrivateTextIntoOnlyATombstone() throws Exception {
        JournalEntry entry = entry("Never send this after withdrawal");
        store.save(true, entry, Set.of(a)); store.save(true, entry, Set.of());
        JSONArray rows = store.outgoing(a, 0);
        assertEquals(1, rows.length()); assertTrue(rows.getJSONObject(0).getJSONObject("change").getBoolean("deleted"));
        assertFalse(rows.toString().contains(entry.text));
        assertEquals(0, store.outgoing(b, 0).length());
        assertEquals(0, store.outgoing(identity('a') + "_repaired", 0).length());
    }
    @Test public void receiveReconnectReplayAndWithdrawalAreAtomicAndGenerationScoped() throws Exception {
        JournalEntry entry = entry("Shared"); store.save(true, entry, Set.of(a));
        JSONArray page = store.outgoing(a, 0); long cursor = store.receive(b, page);
        assertEquals(cursor, store.receive(b, page)); assertEquals(1, store.shared(b, 0).size());
        JSONArray conflict = new JSONArray(page.toString()); conflict.getJSONObject(0).getJSONObject("change").getJSONObject("entry").put("text", "Altered");
        assertThrows(SecurityException.class, () -> store.receive(b, conflict));
        store.save(true, entry, Set.of()); store.receive(b, store.outgoing(a, cursor));
        assertTrue(store.shared(b, 0).isEmpty());
        assertNull(store.entry(false, identity('b') + "_repaired", entry.id));
    }
    @Test public void invalidLaterRecordRollsBackEarlierContentAndCursor() throws Exception {
        store.save(true, entry("Shared"), Set.of(a)); JSONArray rows = store.outgoing(a, 0);
        rows.put(new JSONObject().put("seq", 2).put("change", new JSONObject().put("id", "../bad").put("deleted", true)));
        assertThrows(Exception.class, () -> store.receive(b, rows));
        assertEquals(0, store.received(b)); assertTrue(store.shared(b, 0).isEmpty());
        assertThrows(SecurityException.class, () -> store.outgoing(a, 99));
    }
    @Test public void localOppositeRoleViewContainsOnlyEntriesExplicitlySharedWithIt() throws Exception {
        JournalEntry entry = entry("Private Sub entry"); store.save(false, entry, Set.of());
        assertTrue(store.localShared(true, 0).isEmpty());
        store.save(false, entry, Set.of("local-dom"));
        assertEquals(entry.id, store.localShared(true, 0).get(0).id);
        assertTrue(store.localShared(false, 0).isEmpty());
        assertEquals(0, store.outgoing(a, 0).length());
        store.save(false, entry, Set.of()); assertTrue(store.localShared(true, 0).isEmpty());
    }
}
