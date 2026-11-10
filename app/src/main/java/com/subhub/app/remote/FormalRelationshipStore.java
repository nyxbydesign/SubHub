package com.subhub.app.remote;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.ArrayList;
import java.util.List;

/** Inert schema foundation. Every mutation belongs to the caller's RemoteStore transaction. */
final class FormalRelationshipStore {
    static final int MIGRATION_BATCH = 32, MAX_UNRESOLVED = 16, MAX_RECORDS = 128;
    private FormalRelationshipStore() { }

    static void create(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE formal_authority(id INTEGER PRIMARY KEY CHECK(id=1),incarnation TEXT NOT NULL,"
                + "phase TEXT NOT NULL CHECK(phase='INERT'),revision INTEGER NOT NULL CHECK(revision>=0),"
                + "sequence INTEGER NOT NULL CHECK(sequence>=0),legacy_cutoff INTEGER NOT NULL,"
                + "next_order INTEGER NOT NULL CHECK(next_order>0),migration_cursor INTEGER NOT NULL DEFAULT 0,"
                + "migration_complete INTEGER NOT NULL DEFAULT 0 CHECK(migration_complete IN(0,1)))");
        db.execSQL("INSERT INTO formal_authority(id,incarnation,phase,revision,sequence,legacy_cutoff,next_order) "
                + "SELECT 1,lower(hex(randomblob(16))),'INERT',0,0,COALESCE(MAX(rowid),0),"
                + "COALESCE(MAX(rowid),0)+1 FROM peers");
        db.execSQL("CREATE TABLE formal_participants(ref TEXT PRIMARY KEY,endpoint TEXT NOT NULL,"
                + "role TEXT NOT NULL CHECK(role IN('DOM','SUB')),local INTEGER NOT NULL CHECK(local IN(0,1)),"
                + "UNIQUE(endpoint,role))");
        db.execSQL("CREATE TABLE formal_relationships(id TEXT NOT NULL,generation TEXT NOT NULL,"
                + "dom_ref TEXT REFERENCES formal_participants(ref),sub_ref TEXT REFERENCES formal_participants(ref),"
                + "delivery TEXT NOT NULL CHECK(delivery IN('LOCAL','PEER')),"
                + "state TEXT NOT NULL CHECK(state IN('PREPARED','PENDING','ACTIVE','REVOKED')),"
                + "addition_order INTEGER NOT NULL UNIQUE CHECK(addition_order>0),"
                + "claimed_endpoint TEXT,local_role TEXT,remote_peer TEXT,remote_generation TEXT,operation_id TEXT,"
                + "scope_digest TEXT,PRIMARY KEY(id,generation),UNIQUE(remote_peer,remote_generation),"
                + "CHECK(state!='ACTIVE' OR (dom_ref IS NOT NULL AND sub_ref IS NOT NULL AND dom_ref!=sub_ref)),"
                + "CHECK((delivery='LOCAL' AND remote_peer IS NULL AND remote_generation IS NULL) OR "
                + "(delivery='PEER' AND remote_peer IS NOT NULL AND remote_generation IS NOT NULL)))");
        // Slot ownership outlives the optional operation journal; retirement cannot cascade into authority.
        db.execSQL("CREATE TABLE formal_governing_slots(sub_ref TEXT PRIMARY KEY REFERENCES formal_participants(ref),"
                + "dom_ref TEXT REFERENCES formal_participants(ref),owner TEXT NOT NULL UNIQUE,"
                + "relationship_id TEXT,generation TEXT,"
                + "CHECK((relationship_id IS NULL)=(generation IS NULL)))");
        db.execSQL("CREATE TABLE formal_operations(id TEXT PRIMARY KEY,operation_key TEXT NOT NULL UNIQUE,"
                + "original_revision TEXT NOT NULL,request_digest TEXT NOT NULL,scope_digest TEXT NOT NULL,"
                + "dom_ref TEXT NOT NULL REFERENCES formal_participants(ref),"
                + "sub_ref TEXT NOT NULL REFERENCES formal_participants(ref),"
                + "state TEXT NOT NULL CHECK(state IN('PREPARED','ACTIVE','CANCELLED','EXPIRED','REFUSED')),"
                + "created_wall INTEGER NOT NULL,created_elapsed INTEGER NOT NULL,created_boot INTEGER NOT NULL,"
                + "deadline_wall INTEGER NOT NULL,deadline_elapsed INTEGER NOT NULL,"
                + "terminal_wall INTEGER,terminal_elapsed INTEGER,terminal_boot INTEGER,"
                + "relationship_id TEXT,generation TEXT,approval_digest TEXT,"
                + "CHECK((relationship_id IS NULL)=(generation IS NULL)),"
                + "CHECK((state='ACTIVE')=(relationship_id IS NOT NULL)))");
    }

    static void transaction(SQLiteDatabase db) {
        if (!db.inTransaction()) throw new IllegalStateException("Formal parent transaction required");
    }

    static long number(SQLiteDatabase db, String column) {
        // Column names are internal constants, never caller or peer input.
        try (Cursor row = db.rawQuery("SELECT " + column + " FROM formal_authority WHERE id=1", null)) {
            if (!row.moveToFirst()) throw new IllegalStateException("Formal authority missing");
            return row.getLong(0);
        }
    }

    static String incarnation(SQLiteDatabase db) {
        try (Cursor row = db.rawQuery("SELECT incarnation,phase FROM formal_authority WHERE id=1", null)) {
            if (!row.moveToFirst() || !"INERT".equals(row.getString(1)))
                throw new IllegalStateException("Inert foundation required");
            return row.getString(0);
        }
    }

    /** This internal change counter is deliberately not a usable application snapshot revision. */
    static String internalRevision(SQLiteDatabase db) {
        return "internal:" + incarnation(db) + ":" + number(db, "revision");
    }

    static void changed(SQLiteDatabase db) {
        transaction(db);
        long revision = Math.addExact(number(db, "revision"), 1);
        db.execSQL("UPDATE formal_authority SET revision=? WHERE id=1", new Object[]{revision});
    }

    static long allocateOrder(SQLiteDatabase db) {
        transaction(db);
        long order = number(db, "next_order");
        db.execSQL("UPDATE formal_authority SET next_order=? WHERE id=1", new Object[]{Math.addExact(order, 1)});
        return order;
    }

    static String local(SQLiteDatabase db, String role) {
        transaction(db);
        if (!"DOM".equals(role) && !"SUB".equals(role)) throw new IllegalArgumentException("Role");
        String ref = "local_" + incarnation(db) + "_" + role;
        db.execSQL("INSERT OR IGNORE INTO formal_participants(ref,endpoint,role,local) VALUES(?,'installation',?,1)",
                new Object[]{ref, role});
        return ref;
    }

    private static String boundRemote(SQLiteDatabase db, String peer, String role) {
        String ref = "endpoint_" + peer + "_" + role;
        db.execSQL("INSERT OR IGNORE INTO formal_participants(ref,endpoint,role,local) VALUES(?,?,?,0)",
                new Object[]{ref, peer, role});
        return ref;
    }

    static String peerRelationship(String peer, String generation) { return "peer:" + peer + ":" + generation; }

    static void claim(SQLiteDatabase db, String sub, String dom, String owner, String id, String generation) {
        transaction(db);
        try (Cursor row = db.rawQuery("SELECT owner FROM formal_governing_slots WHERE sub_ref=?", new String[]{sub})) {
            if (row.moveToFirst() && !owner.equals(row.getString(0)))
                throw new SecurityException("A Dom is already assigned or pending");
        }
        db.execSQL("INSERT OR IGNORE INTO formal_governing_slots(sub_ref,dom_ref,owner,relationship_id,generation) VALUES(?,?,?,?,?)",
                new Object[]{sub, dom, owner, id, generation});
        db.execSQL("UPDATE formal_governing_slots SET dom_ref=?,relationship_id=?,generation=? WHERE sub_ref=? AND owner=?",
                new Object[]{dom, id, generation, sub, owner});
    }

    static void rejectOtherLegacyDom(SQLiteDatabase db, String peer, String generation) {
        try (Cursor rows = db.rawQuery("SELECT 1 FROM peers WHERE local_role='SUB' AND state IN('PENDING','ACTIVE') "
                + "AND NOT(peer=? AND generation=?) LIMIT 1", new String[]{peer, generation})) {
            if (rows.moveToFirst()) throw new SecurityException("A Dom is already assigned or pending");
        }
    }

    /** Map new peers immediately; only the captured pre-cutover inventory may inherit a rowid order. */
    static void mapPeer(SQLiteDatabase db, String peer, String generation, boolean newPeer) {
        transaction(db);
        long rowid; String role, state;
        try (Cursor row = db.rawQuery("SELECT rowid,local_role,state FROM peers WHERE peer=? AND generation=?",
                new String[]{peer, generation})) {
            if (!row.moveToFirst()) throw new SecurityException("Peer unavailable");
            rowid = row.getLong(0); role = row.getString(1); state = row.getString(2);
        }
        if (!"DOM".equals(role) && !"SUB".equals(role))
            throw new SecurityException("Unrecognized retained peer role");
        String id = peerRelationship(peer, generation), dom = null, sub = null;
        boolean mapped;
        try (Cursor row = db.rawQuery("SELECT dom_ref,sub_ref FROM formal_relationships WHERE id=? AND generation=?",
                new String[]{id, generation})) {
            mapped = row.moveToFirst();
            if (mapped) { dom = row.getString(0); sub = row.getString(1); }
        }
        if (!mapped && !newPeer && (rowid <= 0 || rowid > number(db, "legacy_cutoff")))
            throw new IllegalStateException("Unmapped post-cutover peer");
        if ("DOM".equals(role)) dom = local(db, "DOM"); else sub = local(db, "SUB");
        // A claimed invite endpoint does not authenticate a participant. Legacy ACTIVE is established wire state.
        if ("ACTIVE".equals(state)) {
            if ("DOM".equals(role)) sub = boundRemote(db, peer, "SUB");
            else dom = boundRemote(db, peer, "DOM");
        }
        if (!mapped) {
            long order = newPeer ? allocateOrder(db) : rowid;
            db.execSQL("INSERT INTO formal_relationships(id,generation,dom_ref,sub_ref,delivery,state,addition_order,"
                    + "claimed_endpoint,local_role,remote_peer,remote_generation) VALUES(?,?,?,?,'PEER',?,?,?,?,?,?)",
                    new Object[]{id, generation, dom, sub, state, order, peer, role, peer, generation});
        } else {
            db.execSQL("UPDATE formal_relationships SET dom_ref=?,sub_ref=?,state=? WHERE id=? AND generation=?",
                    new Object[]{dom, sub, state, id, generation});
        }
        String owner = "peer:" + peer + ":" + generation;
        if (!"REVOKED".equals(state)) {
            if ("SUB".equals(role)) rejectOtherLegacyDom(db, peer, generation);
            if (sub != null) claim(db, sub, dom, owner, id, generation);
        } else db.delete("formal_governing_slots", "owner=?", new String[]{owner});
        changed(db);
    }

    static int migrate(SQLiteDatabase db) {
        transaction(db);
        long cursor = number(db, "migration_cursor"), cutoff = number(db, "legacy_cutoff");
        List<String[]> peers = new ArrayList<>(); long through = cursor;
        try (Cursor rows = db.rawQuery("SELECT rowid,peer,generation FROM peers WHERE rowid>? AND rowid<=? ORDER BY rowid LIMIT "
                + MIGRATION_BATCH, new String[]{Long.toString(cursor), Long.toString(cutoff)})) {
            while (rows.moveToNext()) { through = rows.getLong(0); peers.add(new String[]{rows.getString(1), rows.getString(2)}); }
        }
        for (String[] peer : peers) mapPeer(db, peer[0], peer[1], false);
        db.execSQL("UPDATE formal_authority SET migration_cursor=? WHERE id=1", new Object[]{through});
        if (peers.size() < MIGRATION_BATCH) {
            try (Cursor row = db.rawQuery("SELECT 1 FROM peers p LEFT JOIN formal_relationships r ON "
                    + "r.remote_peer=p.peer AND r.remote_generation=p.generation WHERE r.id IS NULL LIMIT 1", null)) {
                if (row.moveToFirst()) throw new IllegalStateException("Incomplete peer adaptation");
            }
            db.execSQL("UPDATE formal_authority SET migration_complete=1 WHERE id=1");
        }
        return peers.size();
    }

    static long additionOrder(SQLiteDatabase db, String peer, String generation) {
        try (Cursor row = db.rawQuery("SELECT addition_order FROM formal_relationships WHERE remote_peer=? AND remote_generation=?",
                new String[]{peer, generation})) { if (row.moveToFirst()) return row.getLong(0); }
        try (Cursor row = db.rawQuery("SELECT rowid FROM peers WHERE peer=? AND generation=?", new String[]{peer, generation})) {
            if (!row.moveToFirst()) return 0;
            long order = row.getLong(0);
            if (order <= 0 || order > number(db, "legacy_cutoff"))
                throw new IllegalStateException("Unmapped post-cutover peer order");
            return order;
        }
    }
}
