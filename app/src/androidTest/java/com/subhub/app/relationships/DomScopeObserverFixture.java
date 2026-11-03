package com.subhub.app.relationships;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.people.PersonId;
import com.subhub.app.people.PersonSnapshot;
import com.subhub.app.remote.RemotePersonRepository;
import com.subhub.app.remote.RemoteProtocol;
import com.subhub.app.remote.RemoteStore;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Model/query scope only; real LOCAL approval is covered by DomSubjectScopeActivityAndroidTest. */
public final class DomScopeObserverFixture implements AutoCloseable {
    private static final String JOURNAL = "subhub_relationship_presentation";
    private final RelationshipUi.Provider previous = RelationshipUi.provider();
    private final SharedPreferences preferences;
    private final Map<String, ?> savedPreferences;
    private final Object journalLock, savedState;
    private final Map<String, Object> journalStates;
    private final String journalKey;
    private final boolean entryPresent;
    private final Projection projection = new Projection();

    @SuppressWarnings("unchecked")
    public DomScopeObserverFixture(Context context) throws Exception {
        preferences = context.getSharedPreferences(JOURNAL, 0);
        savedPreferences = new LinkedHashMap<>(preferences.getAll());
        Field lock = RelationshipPanel.class.getDeclaredField("JOURNAL_LOCK");
        Field states = RelationshipPanel.class.getDeclaredField("JOURNAL_STATES");
        lock.setAccessible(true); states.setAccessible(true);
        journalLock = lock.get(null);
        journalStates = (Map<String, Object>) states.get(null);
        journalKey = context.getApplicationInfo().dataDir + "/" + JOURNAL;
        synchronized (journalLock) {
            entryPresent = journalStates.containsKey(journalKey);
            savedState = journalStates.remove(journalKey);
            try { assertTrue(preferences.edit().clear().commit()); }
            catch (RuntimeException | Error failure) {
                try { SharedPreferenceTestRestore.restore(preferences, savedPreferences); }
                finally { if (entryPresent) journalStates.put(journalKey, savedState); }
                throw failure;
            }
        }
        outgoing();
        RelationshipUi.install(projection);
    }

    public void outgoing(RelationshipUi.Edge... edges) { scope(null, edges); }

    public void scope(RelationshipUi.Edge governingDom, RelationshipUi.Edge... edges) {
        for (RelationshipUi.Edge edge : edges)
            if (governingDom != null && edge.personId.isLocal())
                throw new AssertionError("An incoming Dom already governs this Sub");
        projection.value = new RelationshipUi.Snapshot(true, UUID.randomUUID().toString(),
                Collections.emptyList(), Arrays.asList(edges), governingDom, Collections.emptyList());
    }

    public static RelationshipUi.Edge local(String generation, long order) {
        return new RelationshipUi.Edge("observer-local", generation, "local-dom", "local-sub",
                RelationshipUi.Delivery.LOCAL, "ACTIVE", order, PersonId.LOCAL,
                RelationshipUi.Availability.UNKNOWN, PersonSnapshot.Freshness.UNKNOWN);
    }

    public static RelationshipUi.Edge peer(RemoteStore.Peer peer, long order) {
        assertNotNull(peer); assertEquals(RemoteProtocol.Role.DOM, peer.localRole);
        assertEquals(RemoteStore.State.ACTIVE, peer.state);
        return peer(peer.identity, peer.generation, order);
    }

    /** Also permits an explicitly absent generation for negative query assertions. */
    public static RelationshipUi.Edge peer(String identity, String generation, long order) {
        return new RelationshipUi.Edge("observer-peer-" + identity, generation, "local-dom", "peer-sub",
                RelationshipUi.Delivery.PEER, "ACTIVE", order,
                new PersonId("remote_" + identity + "_" + generation),
                RelationshipUi.Availability.OFFLINE, PersonSnapshot.Freshness.STALE);
    }

    public static RelationshipUi.Edge incomingDom(RemoteStore.Peer peer) {
        assertNotNull(peer); assertEquals(RemoteProtocol.Role.SUB, peer.localRole);
        assertEquals(RemoteStore.State.ACTIVE, peer.state);
        return new RelationshipUi.Edge("observer-incoming-dom", peer.generation, "peer-dom", "local-sub",
                RelationshipUi.Delivery.PEER, "ACTIVE", 1, RemotePersonRepository.id(peer),
                RelationshipUi.Availability.OFFLINE, PersonSnapshot.Freshness.STALE);
    }

    /** Complete raw synthetic peer seeds through the real mapper, never a legacy-cutoff exception. */
    public static void mapOwnedPeer(Context context, RemoteStore store, String identity, String generation)
            throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertNotSame(target.getApplicationContext(), context);
        assertSame(context, context.getApplicationContext());
        String prefix = new File(context.getApplicationInfo().dataDir).getName();
        assertTrue("Only the owned History/Stats Scoped fixtures may seed peers",
                prefix.matches("(?:history|dom-scope)-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}-"));
        assertEquals(new File(target.getApplicationInfo().dataDir, prefix).getCanonicalFile(),
                new File(context.getApplicationInfo().dataDir).getCanonicalFile());
        File targetDatabase = target.getDatabasePath("remote_sync_v1.db").getCanonicalFile();
        File ownedDatabase = context.getDatabasePath("remote_sync_v1.db").getCanonicalFile();
        assertEquals(new File(targetDatabase.getParentFile(), prefix + "remote_sync_v1.db"), ownedDatabase);
        assertNotEquals(targetDatabase, ownedDatabase);
        synchronized (store) {
            SQLiteDatabase db = store.getWritableDatabase();
            assertEquals(ownedDatabase, new File(db.getPath()).getCanonicalFile());
            assertFalse("Mapping owns its transaction", db.inTransaction());
            RemoteStore.Peer peer = store.peer(identity, generation);
            assertNotNull(peer); assertEquals(identity, peer.identity); assertEquals(generation, peer.generation);
            assertEquals(RemoteStore.State.ACTIVE, peer.state);
            try (Cursor rows = db.rawQuery("SELECT 1 FROM formal_relationships WHERE remote_peer=? AND remote_generation=?",
                    new String[]{identity, generation})) {
                assertFalse("Only a newly seeded generation may be mapped", rows.moveToFirst());
            }
            long cutoff = android.database.DatabaseUtils.longForQuery(db,
                    "SELECT legacy_cutoff FROM formal_authority WHERE id=1", null);
            Method mapper = Class.forName("com.subhub.app.remote.FormalRelationshipStore").getDeclaredMethod(
                    "mapPeer", SQLiteDatabase.class, String.class, String.class, boolean.class);
            mapper.setAccessible(true);
            db.beginTransaction();
            try {
                mapper.invoke(null, db, identity, generation, true);
                try (Cursor rows = db.rawQuery("SELECT r.id,r.generation,r.dom_ref,r.sub_ref,r.addition_order,"
                        + "r.local_role,r.delivery,r.state,d.role,d.local,d.endpoint,s.role,s.local,s.endpoint,"
                        + "g.owner,g.dom_ref,g.relationship_id,g.generation FROM formal_relationships r "
                        + "JOIN formal_participants d ON d.ref=r.dom_ref JOIN formal_participants s ON s.ref=r.sub_ref "
                        + "JOIN formal_governing_slots g ON g.sub_ref=r.sub_ref "
                        + "WHERE r.remote_peer=? AND r.remote_generation=?", new String[]{identity, generation})) {
                    assertTrue(rows.moveToFirst());
                    String relationship = "peer:" + identity + ":" + generation;
                    boolean localDom = peer.localRole == RemoteProtocol.Role.DOM;
                    assertEquals(relationship, rows.getString(0)); assertEquals(generation, rows.getString(1));
                    assertNotNull(rows.getString(2)); assertNotNull(rows.getString(3));
                    assertNotEquals(rows.getString(2), rows.getString(3)); assertTrue(rows.getLong(4) > 0);
                    assertEquals(rows.getLong(4), store.additionOrder(identity, generation));
                    assertEquals(peer.localRole.name(), rows.getString(5));
                    assertEquals("PEER", rows.getString(6)); assertEquals("ACTIVE", rows.getString(7));
                    assertEquals("DOM", rows.getString(8)); assertEquals(localDom ? 1 : 0, rows.getInt(9));
                    assertEquals(localDom ? "installation" : identity, rows.getString(10));
                    assertEquals("SUB", rows.getString(11)); assertEquals(localDom ? 0 : 1, rows.getInt(12));
                    assertEquals(localDom ? identity : "installation", rows.getString(13));
                    assertEquals(relationship, rows.getString(14)); assertEquals(rows.getString(2), rows.getString(15));
                    assertEquals(relationship, rows.getString(16)); assertEquals(generation, rows.getString(17));
                    assertFalse(rows.moveToNext());
                }
                assertEquals(cutoff, android.database.DatabaseUtils.longForQuery(db,
                        "SELECT legacy_cutoff FROM formal_authority WHERE id=1", null));
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
    }

    @Override public void close() {
        try { RelationshipUi.install(previous); }
        finally {
            synchronized (journalLock) {
                try { SharedPreferenceTestRestore.restore(preferences, savedPreferences); }
                finally {
                    journalStates.remove(journalKey);
                    if (entryPresent) journalStates.put(journalKey, savedState);
                }
                assertEquals(savedPreferences, preferences.getAll());
                assertSame(savedState, journalStates.get(journalKey));
                assertEquals(entryPresent, journalStates.containsKey(journalKey));
            }
        }
    }

    private static final class Projection implements RelationshipUi.Provider {
        volatile RelationshipUi.Snapshot value;
        public CompletableFuture<RelationshipUi.Snapshot> snapshot() {
            return CompletableFuture.completedFuture(value);
        }
        public CompletableFuture<RelationshipUi.LocalReview> prepareLocal(String k, String d, String s, String r) {
            throw new AssertionError("Query fixture cannot prepare a relationship");
        }
        public CompletableFuture<RelationshipUi.Operation> confirmLocal(String i, String d) {
            throw new AssertionError("Query fixture cannot approve a relationship");
        }
        public CompletableFuture<RelationshipUi.Operation> resume(String i) { throw new AssertionError("Query fixture"); }
        public CompletableFuture<RelationshipUi.Operation> cancel(String i) { throw new AssertionError("Query fixture"); }
    }
}
