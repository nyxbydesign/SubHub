package com.subhub.app.remote;

import com.subhub.app.storage.StoredPayloads;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Per-relationship mirrors and acknowledgements never share the device's live preference stores. */
public final class RemoteStore extends SQLiteOpenHelper {
    static final int DATABASE_VERSION = 6;
    static final int MAX_SESSION_PENDING = 16, MAX_SESSION_RECORDS = 128, MAX_SESSION_GLOBAL = 4096;
    private static final String SESSION_NATIVE_PREFIX = "session_native_prepared:";
    private final Context context;
    public enum State { PENDING, ACTIVE, REVOKED }
    public static final class Peer {
        public final String identity, generation, name;
        public final String route;
        public final RemoteProtocol.Role localRole;
        public final State state;
        public final long lastSyncedAt;
        Peer(String identity, String generation, String name, RemoteProtocol.Role localRole,
                State state, long lastSyncedAt, String route) {
            this.identity = identity; this.generation = generation; this.name = name;
            this.localRole = localRole; this.state = state; this.lastSyncedAt = lastSyncedAt;
            this.route = route;
        }
        public RemoteProtocol.Role remoteRole() {
            return localRole == RemoteProtocol.Role.DOM ? RemoteProtocol.Role.SUB : RemoteProtocol.Role.DOM;
        }
    }

    private java.util.function.BiConsumer<String,String> revoked=(identity,generation)->{};
    private java.util.function.Consumer<Peer> activated=peer->{};
    private java.util.function.Consumer<Peer> activityReceived=peer->{};
    synchronized void onActivityReceived(java.util.function.Consumer<Peer> action){activityReceived=java.util.Objects.requireNonNull(action);}
    synchronized void onActivated(java.util.function.Consumer<Peer> action){activated=java.util.Objects.requireNonNull(action);}
    synchronized void onRevoked(java.util.function.BiConsumer<String,String> action){revoked=java.util.Objects.requireNonNull(action);}

    public RemoteStore(Context context) { this(context, "remote_sync_v1.db"); }
    public RemoteStore(Context context, String databaseName) {
        super(context.getApplicationContext(), databaseName, null, DATABASE_VERSION);
        this.context=context.getApplicationContext();
        setWriteAheadLoggingEnabled(true);
    }

    /** Local retained facts have no pairing prerequisite and are never marked read or re-published. */
    public synchronized void readRetainedLocalStatistics(java.util.function.Consumer<org.json.JSONObject> accept)
            throws org.json.JSONException {
        if (!com.subhub.app.security.DeviceRolePreferences.supportsSub(context))
            throw new SecurityException("Local Sub statistics unavailable");
        try (Cursor rows = getReadableDatabase().rawQuery("SELECT body FROM activity_source ORDER BY id", null)) {
            while (rows.moveToNext()) {
                org.json.JSONObject value = new org.json.JSONObject(StoredPayloads.read(rows,0));
                RemoteActivityWire.decode(value);
                accept.accept(value);
            }
        }
    }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE peers(peer TEXT NOT NULL,generation TEXT NOT NULL,name TEXT NOT NULL,"
                + "local_role TEXT NOT NULL,state TEXT NOT NULL,last_sync INTEGER NOT NULL DEFAULT 0,"
                + "route TEXT NOT NULL DEFAULT '',PRIMARY KEY(peer,generation))");
        db.execSQL("CREATE UNIQUE INDEX active_remote_identity ON peers(peer) WHERE state IN ('PENDING','ACTIVE')");
        db.execSQL("CREATE UNIQUE INDEX one_dom_per_sub ON peers(local_role) WHERE local_role='SUB' AND state IN ('PENDING','ACTIVE')");
        db.execSQL("CREATE TABLE invites(id TEXT PRIMARY KEY,token_hash TEXT NOT NULL,expires INTEGER NOT NULL,"
                + "candidate TEXT,candidate_name TEXT NOT NULL DEFAULT '',approved INTEGER NOT NULL DEFAULT 0,consumed INTEGER NOT NULL DEFAULT 0)");
        for (String table : new String[] {"local_objects", "outbox", "remote_objects"}) {
            db.execSQL("CREATE TABLE " + table + "(peer TEXT NOT NULL,generation TEXT NOT NULL,"
                    + "stream TEXT NOT NULL,item TEXT NOT NULL,seq INTEGER NOT NULL,body TEXT NOT NULL,"
                    + "deleted INTEGER NOT NULL,observed INTEGER NOT NULL,PRIMARY KEY(peer,generation,stream,item))");
        }
        db.execSQL("CREATE TABLE outgoing(peer TEXT NOT NULL,generation TEXT NOT NULL,stream TEXT NOT NULL,"
                + "next_seq INTEGER NOT NULL DEFAULT 0,acked INTEGER NOT NULL DEFAULT 0,"
                + "offered INTEGER NOT NULL DEFAULT 0,digest TEXT,PRIMARY KEY(peer,generation,stream))");
        db.execSQL("CREATE TABLE incoming(peer TEXT NOT NULL,generation TEXT NOT NULL,stream TEXT NOT NULL,"
                + "through_seq INTEGER NOT NULL,PRIMARY KEY(peer,generation,stream))");
        db.execSQL("CREATE TABLE commands(peer TEXT NOT NULL,generation TEXT NOT NULL,id TEXT NOT NULL,"
                + "revision INTEGER NOT NULL,status TEXT NOT NULL,body TEXT NOT NULL,"
                + "PRIMARY KEY(peer,generation,id))");
        createMetadata(db);
        RemoteActivityStore.create(db);
        StoredPayloads.createProgress(db);
        createSessionControls(db);
        createSessionClockJournal(db);
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 1 || oldVersion >= newVersion || newVersion > DATABASE_VERSION)
            throw new IllegalStateException("Unsupported remote database version");
        if (oldVersion < 2 && newVersion >= 2) {
            db.execSQL("ALTER TABLE peers ADD COLUMN route TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE invites ADD COLUMN candidate_name TEXT NOT NULL DEFAULT ''");
            createMetadata(db);
        }
        if (oldVersion < 3 && newVersion >= 3) RemoteActivityStore.create(db);
        if (oldVersion < 4 && newVersion >= 4) StoredPayloads.createProgress(db);
        if (oldVersion < 5 && newVersion >= 5) createSessionControls(db);
        if (oldVersion < 6 && newVersion >= 6) createSessionClockJournal(db);
    }

    private static void createSessionControls(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE session_controls(peer TEXT NOT NULL,generation TEXT NOT NULL,id TEXT NOT NULL,"
                + "direction TEXT NOT NULL,request_digest TEXT NOT NULL,request TEXT NOT NULL,result TEXT NOT NULL,"
                + "state TEXT NOT NULL,updated INTEGER NOT NULL,PRIMARY KEY(peer,generation,id,direction))");
        db.execSQL("CREATE INDEX pending_session_controls ON session_controls(peer,generation,direction,state,updated)");
    }

    private static void createSessionClockJournal(SQLiteDatabase db) {
        // Preserve the inactive v5 journal verbatim; clockless entries can never acquire new authority.
        db.execSQL("CREATE TABLE session_control_journal(peer TEXT NOT NULL,generation TEXT NOT NULL,id TEXT NOT NULL,"
                + "direction TEXT NOT NULL,request_digest TEXT NOT NULL,request TEXT NOT NULL,result TEXT NOT NULL,"
                + "state TEXT NOT NULL,updated INTEGER NOT NULL,sequence INTEGER NOT NULL,received_wall INTEGER NOT NULL,"
                + "received_elapsed INTEGER NOT NULL,received_boot INTEGER NOT NULL,deadline_elapsed INTEGER NOT NULL,"
                + "PRIMARY KEY(peer,generation,id,direction),UNIQUE(peer,generation,direction,sequence))");
        db.execSQL("CREATE INDEX pending_session_journal ON session_control_journal(peer,generation,direction,state,updated)");
    }

    synchronized long allocateSessionSequence(String identity, String generation) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            if (active(db, identity, generation).localRole != RemoteProtocol.Role.DOM)
                throw new SecurityException("Only the paired Dom issues session commands");
            long sequence = Math.addExact(Math.max(Long.parseLong(metadata(identity, generation, "session_control_allocated", "0")),
                    Long.parseLong(metadata(identity, generation, "session_control_OUTGOING_floor", "0"))), 1);
            putMetadata(identity, generation, "session_control_allocated", Long.toString(sequence));
            db.setTransactionSuccessful(); return sequence;
        } finally { db.endTransaction(); }
    }

    public synchronized void clearHardcoreStartAcknowledgements() {
        getWritableDatabase().delete("remote_metadata", "name=?", new String[]{"hardcore_start_review_ack"});
    }

    public synchronized void acknowledgeHardcoreStartReview(String identity, String generation, String reviewId) {
        if (!Thread.holdsLock(com.subhub.app.security.SubFeaturePolicy.LOCK)
                || active(getWritableDatabase(), identity, generation).localRole != RemoteProtocol.Role.SUB)
            throw new SecurityException("Receiver consent transaction required");
        RemoteProtocol.identifier(reviewId);
        putMetadata(identity, generation, "hardcore_start_review_ack", reviewId);
    }

    private void requireSessionCapacity(SQLiteDatabase db, String identity, String generation,
            RemoteSessionRuntime.Direction direction) throws JSONException {
        try (Cursor rows = db.rawQuery("SELECT COUNT(*) FROM session_control_journal WHERE peer=? AND generation=? AND direction=?",
                new String[]{identity, generation, direction.name()})) {
            if (!rows.moveToFirst() || rows.getInt(0) >= MAX_SESSION_RECORDS) throw new IllegalStateException("Session journal full");
        }
        requireSessionOutstandingCapacity(db, identity, generation, direction);
        try (Cursor rows = db.rawQuery("SELECT COUNT(*) FROM session_control_journal", null)) {
            if (!rows.moveToFirst() || rows.getInt(0) >= MAX_SESSION_GLOBAL)
                throw new IllegalStateException("Session journal full");
        }
    }
    private void requireSessionOutstandingCapacity(SQLiteDatabase db, String identity, String generation,
            RemoteSessionRuntime.Direction direction) throws JSONException {
        int outstanding = 0;
        try (Cursor rows = db.rawQuery("SELECT id,state FROM session_control_journal WHERE peer=? AND generation=? AND direction=?",
                new String[]{identity, generation, direction.name()})) {
            while (rows.moveToNext()) {
                RemoteSessionCommands.State state = RemoteSessionCommands.State.valueOf(rows.getString(1));
                String id = rows.getString(0);
                if (!state.terminal() || wireAwaitingAck(identity, generation, direction, id)) outstanding++;
            }
        }
        if (outstanding >= MAX_SESSION_PENDING)
            throw new IllegalStateException("Session command queue full");
    }

    private void pruneSessionControls(SQLiteDatabase db, String identity, String generation,
            RemoteSessionRuntime.Direction direction, RemoteSessionClock.Sample now) throws JSONException {
        List<String> expired = new ArrayList<>();
        try (Cursor rows = db.rawQuery("SELECT id,received_wall,received_elapsed,received_boot,deadline_elapsed"
                        + " FROM session_control_journal WHERE peer=? AND generation=? AND direction=?"
                        + " AND state NOT IN ('QUEUED','ACKNOWLEDGED','APPLYING')",
                new String[]{identity, generation, direction.name()})) {
            while (rows.moveToNext()) {
                String id = rows.getString(0);
                if (wireAwaitingAck(identity, generation, direction, id)) continue;
                RemoteSessionClock.Bounds lifetime = new RemoteSessionClock.Bounds(rows.getLong(1), rows.getLong(2), rows.getInt(3), rows.getLong(4));
                if (lifetime.retentionElapsed(now)) expired.add(rows.getString(0));
            }
        }
        for (String id : expired) {
            for (String field : new String[]{"protected", "acked", "ackPending", "timeout"})
                db.delete("remote_metadata", "peer=? AND generation=? AND name=?",
                        new String[]{identity, generation, wireKey(direction, id, field)});
            db.delete("session_control_journal", "peer=? AND generation=? AND direction=? AND id=?",
                    new String[]{identity, generation, direction.name(), id});
            if (direction == RemoteSessionRuntime.Direction.INCOMING)
                db.delete("remote_metadata", "peer=? AND generation=? AND name=?",
                        new String[]{identity, generation, SESSION_NATIVE_PREFIX + id});
        }
    }

    synchronized RemoteSessionRuntime.Record checkSessionControlClock(String identity, String generation,
            RemoteSessionRuntime.Direction direction, String id, RemoteSessionClock.Sample now) throws JSONException {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            RemoteSessionRuntime.Record current = sessionControl(db, identity, generation, direction, id);
            if (current == null) throw new SecurityException("Unknown session command");
            requireSessionControlPeer(db, identity, generation, direction, current.request);
            // APPLYING may already have a committed native effect; reconcile its marker before classifying it.
            if (!current.result.state.terminal() && current.result.state != RemoteSessionCommands.State.APPLYING) {
                RemoteSessionCommands.Reason reason = current.lifetime.check(current.request, now);
                if (reason != RemoteSessionCommands.Reason.NONE) {
                    if (direction == RemoteSessionRuntime.Direction.OUTGOING && wireProtected(identity, generation, direction, id)) {
                        // Delivery uncertainty is not an authoritative receiver outcome.
                        putMetadata(identity, generation, wireKey(direction, id, "timeout"), reason.name());
                        db.setTransactionSuccessful(); return current;
                    }
                    RemoteSessionCommands.Result expired = new RemoteSessionCommands.Result(current.request,
                            reason == RemoteSessionCommands.Reason.EXPIRED ? RemoteSessionCommands.State.EXPIRED
                                    : RemoteSessionCommands.State.REJECTED, reason, now.wall, null);
                    current = advanceSessionControl(identity, generation, direction, id, expired.json());
                }
            }
            db.setTransactionSuccessful(); return current;
        } finally { db.endTransaction(); }
    }

    /** Durable receipt only. Neither inserting nor reading this journal applies a runtime operation. */
    synchronized RemoteSessionRuntime.Record rememberSessionControl(String identity, String generation,
            RemoteSessionRuntime.Direction direction, RemoteSessionCommands.Request request, RemoteSessionClock.Sample clock)
            throws JSONException {
        java.util.Objects.requireNonNull(clock);
        long now = clock.wall;
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            requireSessionControlPeer(db, identity, generation, direction, request);
            RemoteSessionRuntime.Record previous = sessionControl(db, identity, generation, direction, request.id);
            if (previous != null) {
                if (!previous.request.digest().equals(request.digest()))
                    throw new SecurityException("Session command identity reused with different content");
                if (direction == RemoteSessionRuntime.Direction.INCOMING && !previous.result.state.terminal()
                        && "1".equals(metadata(identity, generation, "session_control_version", "0"))
                        && !wireProtected(identity, generation, direction, request.id))
                    throw new SecurityException("Legacy receipt has no negotiated provenance");
                db.setTransactionSuccessful(); return previous;
            }
            try (Cursor legacy = db.rawQuery("SELECT 1 FROM session_controls WHERE peer=? AND generation=? AND id=? AND direction=?",
                    new String[]{identity, generation, request.id, direction.name()})) {
                if (legacy.moveToFirst()) throw new SecurityException("Clockless legacy session command cannot be replayed");
            }
            String floorKey = "session_control_" + direction.name() + "_floor";
            long floor = Long.parseLong(metadata(identity, generation, floorKey, "0"));
            if (request.sequence <= floor) throw new SecurityException("Session command sequence already retired");
            List<String> pending = new ArrayList<>();
            try (Cursor rows = db.rawQuery("SELECT id FROM session_control_journal WHERE peer=? AND generation=? AND direction=?"
                            + " AND state IN ('QUEUED','ACKNOWLEDGED')",
                    new String[]{identity, generation, direction.name()})) {
                while (rows.moveToNext()) pending.add(rows.getString(0));
            }
            for (String id : pending) checkSessionControlClock(identity, generation, direction, id, clock);
            pruneSessionControls(db, identity, generation, direction, clock);
            requireSessionCapacity(db, identity, generation, direction);
            String requestBody = RemoteProtocol.canonical(request.json());
            if (requestBody.getBytes(StandardCharsets.UTF_8).length > 2048)
                throw new IllegalArgumentException("Session command too large");
            RemoteSessionClock.Bounds lifetime = RemoteSessionClock.Bounds.begin(request, clock);
            RemoteSessionCommands.Reason clockReason = lifetime.check(request, clock);
            RemoteSessionCommands.State initial = clockReason == RemoteSessionCommands.Reason.NONE
                    ? direction == RemoteSessionRuntime.Direction.OUTGOING ? RemoteSessionCommands.State.QUEUED
                            : RemoteSessionCommands.State.ACKNOWLEDGED
                    : clockReason == RemoteSessionCommands.Reason.EXPIRED ? RemoteSessionCommands.State.EXPIRED
                            : RemoteSessionCommands.State.REJECTED;
            RemoteSessionCommands.Result result = new RemoteSessionCommands.Result(request, initial, clockReason, now, null);
            db.execSQL("INSERT INTO session_control_journal(peer,generation,id,direction,request_digest,request,result,state,updated,"
                            + "sequence,received_wall,received_elapsed,received_boot,deadline_elapsed) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    new Object[]{identity, generation, request.id, direction.name(), request.digest(), StoredPayloads.bind(requestBody),
                    StoredPayloads.bind(RemoteProtocol.canonical(result.json())), result.state.name(), now, request.sequence,
                    lifetime.receivedWall, lifetime.receivedElapsed, lifetime.boot, lifetime.deadlineElapsed});
            if ("1".equals(metadata(identity, generation, "session_control_version", "0"))
                    && (direction == RemoteSessionRuntime.Direction.INCOMING || !result.state.terminal()))
                putMetadata(identity, generation, wireKey(direction, request.id, "protected"), request.digest());
            putMetadata(identity, generation, floorKey, Long.toString(request.sequence));
            db.setTransactionSuccessful(); return new RemoteSessionRuntime.Record(direction, request, result, lifetime);
        } finally { db.endTransaction(); }
    }

    synchronized RemoteSessionRuntime.Record sessionControl(String identity, String generation,
            RemoteSessionRuntime.Direction direction, String id) throws JSONException {
        active(getReadableDatabase(), identity, generation);
        return sessionControl(getReadableDatabase(), identity, generation, direction, id);
    }

    private static RemoteSessionRuntime.Record sessionControl(SQLiteDatabase db, String identity, String generation,
            RemoteSessionRuntime.Direction direction, String id) throws JSONException {
        RemoteProtocol.identifier(id); java.util.Objects.requireNonNull(direction);
        try (Cursor rows = db.rawQuery("SELECT request,result,request_digest,state,sequence,received_wall,received_elapsed,received_boot,deadline_elapsed FROM session_control_journal"
                        + " WHERE peer=? AND generation=? AND direction=? AND id=?",
                new String[]{identity, generation, direction.name(), id})) {
            if (!rows.moveToFirst()) return null;
            RemoteSessionCommands.Request request = RemoteSessionCommands.Request.parse(new JSONObject(StoredPayloads.read(rows, 0)));
            RemoteSessionCommands.Result result = RemoteSessionCommands.Result.parse(request, new JSONObject(StoredPayloads.read(rows, 1)));
            if (!id.equals(request.id) || !generation.equals(request.generation)
                    || !request.digest().equals(rows.getString(2)) || !result.state.name().equals(rows.getString(3))
                    || request.sequence != rows.getLong(4))
                throw new SecurityException("Session journal content mismatch");
            return new RemoteSessionRuntime.Record(direction, request, result,
                    new RemoteSessionClock.Bounds(rows.getLong(5), rows.getLong(6), rows.getInt(7), rows.getLong(8)));
        }
    }

    synchronized RemoteSessionRuntime.Record advanceSessionControl(String identity, String generation,
            RemoteSessionRuntime.Direction direction, String id, JSONObject value) throws JSONException {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            RemoteSessionRuntime.Record previous = sessionControl(db, identity, generation, direction, id);
            if (previous == null) throw new SecurityException("Unknown session command");
            requireSessionControlPeer(db, identity, generation, direction, previous.request);
            if (RemoteProtocol.canonical(value).getBytes(StandardCharsets.UTF_8).length > 2048)
                throw new IllegalArgumentException("Session result too large");
            RemoteSessionCommands.Result result = RemoteSessionCommands.Result.parse(previous.request, value);
            if (!result.mayFollow(previous.result)) throw new SecurityException("Invalid session result transition");
            ContentValues values = new ContentValues();
            StoredPayloads.put(values, "result", RemoteProtocol.canonical(result.json()));
            values.put("state", result.state.name()); values.put("updated", result.observedAt);
            if (db.update("session_control_journal", values,
                    "peer=? AND generation=? AND direction=? AND id=? AND state=?",
                    new String[]{identity, generation, direction.name(), id, previous.result.state.name()}) != 1)
                throw new IllegalStateException("Session journal changed during transaction");
            db.setTransactionSuccessful(); return new RemoteSessionRuntime.Record(direction, previous.request, result, previous.lifetime);
        } finally { db.endTransaction(); }
    }

    /** Immutable preparation and APPLYING share SQLite durability; no Android runtime effect occurs here. */
    synchronized RemoteSessionRuntime.Record prepareNativeSessionControl(String identity, String generation,
            String id, JSONObject prepared, RemoteSessionClock.Sample now) throws JSONException {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            RemoteSessionRuntime.Record current = checkSessionControlClock(identity, generation,
                    RemoteSessionRuntime.Direction.INCOMING, id, now);
            if (current.result.state.terminal()) { db.setTransactionSuccessful(); return current; }
            String body = nativePreparation(current.request, prepared);
            String previous = metadata(identity, generation, SESSION_NATIVE_PREFIX + id, "");
            if (!previous.isEmpty() && !body.equals(previous)) throw new SecurityException("Native preparation changed");
            if (current.result.state == RemoteSessionCommands.State.APPLYING) {
                if (previous.isEmpty()) throw new SecurityException("Applying command has no native preparation");
                db.setTransactionSuccessful(); return current;
            }
            if (current.result.state != RemoteSessionCommands.State.ACKNOWLEDGED)
                throw new SecurityException("Native preparation requires an incoming receipt");
            putMetadata(identity, generation, SESSION_NATIVE_PREFIX + id, body);
            RemoteSessionCommands.Result applying = new RemoteSessionCommands.Result(current.request,
                    RemoteSessionCommands.State.APPLYING, RemoteSessionCommands.Reason.NONE, now.wall, null);
            RemoteSessionRuntime.Record result = advanceSessionControl(identity, generation,
                    RemoteSessionRuntime.Direction.INCOMING, id, applying.json());
            db.setTransactionSuccessful(); return result;
        } finally { db.endTransaction(); }
    }

    synchronized JSONObject nativeSessionPreparation(String identity, String generation, String id) throws JSONException {
        RemoteSessionRuntime.Record current = sessionControl(identity, generation, RemoteSessionRuntime.Direction.INCOMING, id);
        if (current == null) throw new SecurityException("Unknown native command");
        requireSessionControlPeer(getReadableDatabase(), identity, generation, RemoteSessionRuntime.Direction.INCOMING, current.request);
        String body = metadata(identity, generation, SESSION_NATIVE_PREFIX + id, "");
        if (body.isEmpty()) {
            if (current.result.state == RemoteSessionCommands.State.APPLYING)
                throw new SecurityException("Applying command has no native preparation");
            return null;
        }
        if (current.result.state == RemoteSessionCommands.State.ACKNOWLEDGED
                || body.length() > 8192 || body.getBytes(StandardCharsets.UTF_8).length > 8192)
            throw new SecurityException("Invalid stored native preparation");
        JSONObject prepared = new JSONObject(body);
        if (!body.equals(nativePreparation(current.request, prepared)))
            throw new SecurityException("Stored native preparation changed");
        return prepared;
    }

    private static String nativePreparation(RemoteSessionCommands.Request request, JSONObject prepared) throws JSONException {
        String body = RemoteProtocol.canonical(prepared);
        if (body.getBytes(StandardCharsets.UTF_8).length > 8192) throw new IllegalArgumentException("Native preparation too large");
        String digest = request.action == RemoteSessionCommands.Action.START
                ? RemoteSessionNative.PreparedStart.parse(prepared).requestDigest
                : RemoteSessionNative.PreparedStop.parse(prepared).requestDigest;
        if (!request.digest().equals(digest)) throw new SecurityException("Native preparation request mismatch");
        return body;
    }

    private static String wireKey(RemoteSessionRuntime.Direction direction, String id, String field) {
        RemoteProtocol.identifier(id);
        return "session_control_wire:" + direction.name() + ":" + id + ":" + field;
    }
    synchronized void protectSessionControlOffer(String identity, String generation, String id) throws JSONException {
        SQLiteDatabase db = getWritableDatabase();
        if (!db.inTransaction()) throw new IllegalStateException("Offer provenance requires transaction");
        RemoteSessionRuntime.Record record = sessionControl(db, identity, generation, RemoteSessionRuntime.Direction.OUTGOING, id);
        if (record == null || record.result.state.terminal()) throw new SecurityException("Pending offer required");
        requireSessionControlPeer(db, identity, generation, record.direction, record.request);
        if (!wireProtected(identity, generation, record.direction, id))
            putMetadata(identity, generation, wireKey(record.direction, id, "protected"), record.request.digest());
    }

    /** Queries cannot create a first delivery or initiate application of a known unprepared intent. */
    synchronized RemoteSessionRuntime.Record reconcileSessionControlReceipt(String identity, String generation,
            RemoteSessionCommands.Request request, RemoteSessionClock.Sample clock) throws JSONException {
        if (!getWritableDatabase().inTransaction()) throw new IllegalStateException("Reconciliation requires transaction");
        RemoteSessionRuntime.Direction direction = RemoteSessionRuntime.Direction.INCOMING;
        RemoteSessionRuntime.Record previous = sessionControl(getWritableDatabase(), identity, generation, direction, request.id);
        if (previous == null) throw new SecurityException("Original delivery result unavailable");
        RemoteSessionRuntime.Record record = rememberSessionControl(identity, generation, direction, request, clock);
        if (record.result.state.terminal() || record.result.state == RemoteSessionCommands.State.APPLYING) return record;
        return advanceSessionControl(identity, generation, direction, request.id,
                new RemoteSessionCommands.Result(request, RemoteSessionCommands.State.EXPIRED,
                        RemoteSessionCommands.Reason.EXPIRED, clock.wall, null).json());
    }
    private String wireValue(String identity, String generation, RemoteSessionRuntime.Direction direction, String id, String field) {
        return metadata(identity, generation, wireKey(direction, id, field), "");
    }
    private boolean wireProtected(String identity, String generation, RemoteSessionRuntime.Direction direction, String id) {
        String value = wireValue(identity, generation, direction, id, "protected");
        if (value.isEmpty()) return false;
        RemoteSessionCommands.digest(value);
        try (Cursor rows = getReadableDatabase().rawQuery("SELECT request_digest FROM session_control_journal WHERE peer=? AND generation=? AND direction=? AND id=?",
                new String[]{identity, generation, direction.name(), id})) {
            if (!rows.moveToFirst() || !value.equals(rows.getString(0))) throw new SecurityException("Changed wire provenance");
        }
        return true;
    }
    private boolean wireAwaitingAck(String identity, String generation, RemoteSessionRuntime.Direction direction, String id) throws JSONException {
        if (!wireProtected(identity, generation, direction, id)) return false;
        RemoteSessionRuntime.Record record = sessionControl(getReadableDatabase(), identity, generation, direction, id);
        if (record == null) throw new SecurityException("Missing protected command");
        if (!record.result.state.terminal()) return true;
        boolean incoming = direction == RemoteSessionRuntime.Direction.INCOMING;
        String digest = wireValue(identity, generation, direction, id, incoming ? "acked" : "ackPending");
        if (digest.isEmpty()) return incoming;
        if (!digest.equals(RemoteSessionControlAck.digest(record.result))) throw new SecurityException("Changed acknowledgment retention");
        return !incoming;
    }

    /** Called inside the sender's result transaction, before any result acknowledgment is emitted. */
    synchronized void retainSessionControlAck(String identity, String generation, String id) throws JSONException {
        SQLiteDatabase db = getWritableDatabase();
        if (!db.inTransaction()) throw new IllegalStateException("Result acknowledgment requires transaction");
        RemoteSessionRuntime.Direction direction = RemoteSessionRuntime.Direction.OUTGOING;
        RemoteSessionRuntime.Record record = sessionControl(db, identity, generation, direction, id);
        if (record == null) throw new SecurityException("Unknown terminal command");
        requireSessionControlPeer(db, identity, generation, direction, record.request);
        if (!wireProtected(identity, generation, direction, id)) throw new SecurityException("Unnegotiated terminal command");
        RemoteSessionControlAck ack = RemoteSessionControlAck.of(record);
        String pending = wireValue(identity, generation, direction, id, "ackPending");
        if (!pending.isEmpty()) {
            if (!pending.equals(ack.resultDigest)) throw new SecurityException("Changed pending terminal acknowledgment");
            return;
        }
        requireSessionOutstandingCapacity(db, identity, generation, direction);
        putMetadata(identity, generation, wireKey(direction, id, "ackPending"), ack.resultDigest);
    }

    synchronized List<RemoteSessionControlAck> pendingSessionControlAcks(String identity, String generation) throws JSONException {
        RemoteSessionRuntime.Direction direction = RemoteSessionRuntime.Direction.OUTGOING;
        if (active(getReadableDatabase(), identity, generation).localRole != RemoteProtocol.Role.DOM)
            throw new SecurityException("Only the sender acknowledges results");
        List<RemoteSessionControlAck> result = new ArrayList<>();
        try (Cursor rows = getReadableDatabase().rawQuery("SELECT id FROM session_control_journal WHERE peer=? AND generation=? AND direction=? ORDER BY sequence",
                new String[]{identity, generation, direction.name()})) {
            while (rows.moveToNext()) {
                String id = rows.getString(0), pending = wireValue(identity, generation, direction, id, "ackPending");
                if (pending.isEmpty()) continue;
                RemoteSessionRuntime.Record record = sessionControl(getReadableDatabase(), identity, generation, direction, id);
                RemoteSessionControlAck ack = RemoteSessionControlAck.of(record);
                if (!wireProtected(identity, generation, direction, id) || !ack.resultDigest.equals(pending))
                    throw new SecurityException("Changed terminal acknowledgment");
                result.add(ack);
                if (result.size() > MAX_SESSION_PENDING) throw new SecurityException("Too many terminal acknowledgments");
            }
        }
        return result;
    }

    /** The authenticated batch caller supplies the current capability/generation fence in the same transaction. */
    synchronized void acknowledgeSessionControl(String identity, String generation, RemoteSessionControlAck ack) throws JSONException {
        SQLiteDatabase db = getWritableDatabase();
        if (!db.inTransaction()) throw new IllegalStateException("Acknowledgment requires transaction");
        RemoteSessionRuntime.Direction direction = RemoteSessionRuntime.Direction.INCOMING;
        if (active(db, identity, generation).localRole != RemoteProtocol.Role.SUB)
            throw new SecurityException("Only the receiver retires results");
        RemoteSessionRuntime.Record record = sessionControl(db, identity, generation, direction, ack.id);
        if (record == null) {
            long floor = Long.parseLong(metadata(identity, generation, "session_control_INCOMING_floor", "0"));
            if (ack.sequence > floor) throw new SecurityException("Unknown terminal acknowledgment");
            // Receipt-only idempotence. No result, proof or execution authority is reconstructed.
            return;
        }
        requireSessionControlPeer(db, identity, generation, direction, record.request);
        if (record.request.sequence != ack.sequence || !record.request.digest().equals(ack.requestDigest)
                || !RemoteSessionControlAck.of(record).resultDigest.equals(ack.resultDigest))
            throw new SecurityException("Terminal acknowledgment mismatch");
        // Legacy first-receipt provenance is immutable. Its known result can be received, never upgraded.
        if (wireProtected(identity, generation, direction, ack.id))
            putMetadata(identity, generation, wireKey(direction, ack.id, "acked"), ack.resultDigest);
    }

    synchronized void clearSessionControlAck(String identity, String generation, RemoteSessionControlAck ack) throws JSONException {
        SQLiteDatabase db = getWritableDatabase();
        if (!db.inTransaction()) throw new IllegalStateException("Acknowledgment echo requires transaction");
        RemoteSessionRuntime.Direction direction = RemoteSessionRuntime.Direction.OUTGOING;
        RemoteSessionRuntime.Record record = sessionControl(db, identity, generation, direction, ack.id);
        if (record == null) throw new SecurityException("Missing local terminal evidence");
        requireSessionControlPeer(db, identity, generation, direction, record.request);
        RemoteSessionControlAck known = RemoteSessionControlAck.of(record);
        if (known.sequence != ack.sequence || !known.requestDigest.equals(ack.requestDigest)
                || !known.resultDigest.equals(ack.resultDigest)
                || !ack.resultDigest.equals(wireValue(identity, generation, direction, ack.id, "ackPending")))
            throw new SecurityException("Changed acknowledgment echo");
        db.delete("remote_metadata", "peer=? AND generation=? AND name=?",
                new String[]{identity, generation, wireKey(direction, ack.id, "ackPending")});
    }

    synchronized List<RemoteSessionRuntime.Record> pendingSessionControls(String identity, String generation,
            RemoteSessionRuntime.Direction direction, int maximum) throws JSONException {
        if (maximum < 1 || maximum > 128) throw new IllegalArgumentException("Invalid session command bound");
        active(getReadableDatabase(), identity, generation); java.util.Objects.requireNonNull(direction);
        List<RemoteSessionRuntime.Record> result = new ArrayList<>();
        try (Cursor rows = getReadableDatabase().rawQuery("SELECT id FROM session_control_journal"
                        + " WHERE peer=? AND generation=? AND direction=? AND state IN ('QUEUED','ACKNOWLEDGED','APPLYING')"
                        + " ORDER BY updated,id LIMIT ?", new String[]{identity, generation, direction.name(), Integer.toString(maximum)})) {
            while (rows.moveToNext()) result.add(sessionControl(getReadableDatabase(), identity, generation, direction, rows.getString(0)));
        }
        return result;
    }

    private static void requireSessionControlPeer(SQLiteDatabase db, String identity, String generation,
            RemoteSessionRuntime.Direction direction, RemoteSessionCommands.Request request) {
        java.util.Objects.requireNonNull(direction); java.util.Objects.requireNonNull(request);
        Peer peer = active(db, identity, generation);
        if (!generation.equals(request.generation)
                || (direction == RemoteSessionRuntime.Direction.OUTGOING
                        ? peer.localRole != RemoteProtocol.Role.DOM || !identity.equals(request.recipient)
                        : peer.localRole != RemoteProtocol.Role.SUB || !identity.equals(request.sender)))
            throw new SecurityException("Wrong session command authority");
    }

    private static void createMetadata(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE remote_metadata(peer TEXT NOT NULL,generation TEXT NOT NULL,name TEXT NOT NULL,value TEXT NOT NULL,PRIMARY KEY(peer,generation,name))");
    }

    /** Optional idle-worker maintenance. Old text stays readable without an opening-time rewrite. */
    public synchronized int compactStoredPayloads(int maximumRows) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            throw new IllegalStateException("Payload maintenance must run off the main thread");
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            int visited = StoredPayloads.compressBatch(db, maximumRows);
            db.setTransactionSuccessful(); return visited;
        } finally { db.endTransaction(); }
    }

    public synchronized List<Peer> peers(boolean includeArchived) {
        List<Peer> peers = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT peer,generation,name,local_role,state,last_sync,route FROM peers"
                + (includeArchived ? "" : " WHERE state!='REVOKED'") + " ORDER BY name,peer", null)) {
            while (c.moveToNext()) peers.add(readPeer(c));
        }
        return peers;
    }

    synchronized void updateDisplayName(String identity, String generation, String name) {
        name = com.subhub.app.people.ProfileNames.clean(name);
        if (name.isEmpty()) return;
        active(getWritableDatabase(), identity, generation);
        getWritableDatabase().execSQL("UPDATE peers SET name=? WHERE peer=? AND generation=?",
                new Object[]{name, identity, generation});
    }

    synchronized void updateProfile(String identity, String generation, JSONObject value, String name)
            throws JSONException {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            Peer peer = active(db, identity, generation);
            RemoteProfiles.parse(value, peer.remoteRole());
            db.execSQL("INSERT OR REPLACE INTO remote_metadata(peer,generation,name,value) VALUES(?,?,'profile',?)",
                    new Object[]{identity, generation, StoredPayloads.bind(RemoteProtocol.canonical(value))});
            updateDisplayName(identity, generation, name);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    synchronized String profile(String identity, String generation) {
        ProfileState state = profileState(identity, generation);
        return state == null ? "" : state.record;
    }

    static final class ProfileState {
        final String name, record;
        ProfileState(String name, String record) { this.name = name; this.record = record; }
    }

    synchronized ProfileState profileState(String identity, String generation) {
        try (Cursor rows = getReadableDatabase().rawQuery(
                "SELECT p.name,m.value FROM peers p LEFT JOIN remote_metadata m ON p.peer=m.peer"
                        + " AND p.generation=m.generation AND m.name='profile'"
                        + " WHERE p.peer=? AND p.generation=? AND p.state='ACTIVE'",
                new String[]{identity, generation})) {
            return rows.moveToFirst() ? new ProfileState(rows.getString(0),
                    rows.isNull(1) ? "" : StoredPayloads.read(rows, 1)) : null;
        }
    }

    public synchronized Peer peer(String identity, String generation) {
        RemoteProtocol.peerId(identity); RemoteProtocol.identifier(generation);
        return peer(getReadableDatabase(), identity, generation);
    }

    public synchronized boolean isRemotelyControlled() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM peers WHERE local_role='SUB' AND state='ACTIVE' LIMIT 1", null)) {
            return c.moveToFirst();
        }
    }
    public synchronized void taskConsentReceived(String identity, String generation, boolean allowed) {
        Peer current = active(getWritableDatabase(), identity, generation);
        if (current.localRole != RemoteProtocol.Role.DOM) throw new SecurityException("Only the paired Sub advertises task consent");
        putMetadata(identity, generation, "tasks_allowed", Boolean.toString(allowed));
    }

    public synchronized void taskTimezoneReceived(String identity, String generation, String zone, long received) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            Peer current = active(db, identity, generation);
            if (current.localRole != RemoteProtocol.Role.DOM)
                throw new SecurityException("Only the paired Sub advertises its timezone");
            if (!zone.isEmpty()) com.subhub.app.tasks.TaskTimezone.require(zone, received, received);
            else if (received != 0) throw new IllegalArgumentException("Invalid timezone receipt");
            putMetadata(identity, generation, "tasks_timezone", zone);
            putMetadata(identity, generation, "tasks_timezone_received", Long.toString(received));
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    synchronized void saveInvite(PairingInvite invite) {
        if(!com.subhub.app.security.DeviceRolePreferences.supportsDom(context))throw new SecurityException("Dom capability unavailable");
        ContentValues values = new ContentValues();
        values.put("id", invite.id); values.put("token_hash", invite.tokenHash());
        values.put("expires", invite.expiresAt);
        getWritableDatabase().insertOrThrow("invites", null, values);
    }

    synchronized String bindInvite(String id, String candidate, String tokenHash, long now) {
        RemoteProtocol.identifier(id); RemoteProtocol.peerId(candidate);
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try (Cursor c = db.rawQuery("SELECT token_hash,expires,candidate,consumed FROM invites WHERE id=?", new String[]{id})) {
            if (!c.moveToFirst() || now <= 0 || now >= c.getLong(1) || c.getInt(3) != 0
                    || !java.security.MessageDigest.isEqual(c.getString(0).getBytes(StandardCharsets.US_ASCII),
                    tokenHash.getBytes(StandardCharsets.US_ASCII))
                    || (!c.isNull(2) && !candidate.equals(c.getString(2)))) {
                throw new SecurityException("Invitation unavailable");
            }
            db.execSQL("UPDATE invites SET candidate=? WHERE id=?", new Object[]{candidate, id});
            db.setTransactionSuccessful();
            return id;
        } finally { db.endTransaction(); }
    }

    synchronized void describeCandidate(String id,String candidate,String name,String route) {
        name=RemoteProtocol.deviceName(name);
        if (route == null || route.length()>4096) {
            throw new IllegalArgumentException("Invalid device details");
        }
        getWritableDatabase().execSQL("UPDATE invites SET candidate_name=? WHERE id=? AND candidate=? AND approved=0 AND consumed=0",
                new Object[]{name.trim(),id,candidate});
        setRoute(candidate,id,route);
    }

    public static final class Invitation {
        public final String id,candidate,name;
        public final long expiresAt;
        public final boolean approved;
        Invitation(String id,String candidate,String name,long expiresAt,boolean approved) {
            this.id=id;this.candidate=candidate;this.name=name;this.expiresAt=expiresAt;this.approved=approved;
        }
    }

    public synchronized List<Invitation> invitations(long now) {
        List<Invitation> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,candidate,candidate_name,expires,approved FROM invites WHERE expires>? AND consumed=0 ORDER BY expires DESC LIMIT 20",new String[]{Long.toString(now)})) {
            while(c.moveToNext()) result.add(new Invitation(c.getString(0),c.getString(1),c.getString(2),c.getLong(3),c.getInt(4)!=0));
        }
        return result;
    }

    synchronized boolean approvedInvite(String id,String candidate,String tokenHash) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT token_hash FROM invites WHERE id=? AND candidate=? AND approved=1 AND consumed=1",new String[]{id,candidate})) {
            Peer peer=peer(getReadableDatabase(),candidate,id);
            return c.moveToFirst() && peer!=null && peer.localRole==RemoteProtocol.Role.DOM && peer.state!=State.REVOKED
                    && java.security.MessageDigest.isEqual(c.getString(0).getBytes(StandardCharsets.US_ASCII),tokenHash.getBytes(StandardCharsets.US_ASCII));
        }
    }

    synchronized void approveInvite(String id, String candidate, String name, long now) {
        if(!com.subhub.app.security.DeviceRolePreferences.supportsDom(context))throw new SecurityException("Dom capability unavailable");
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try (Cursor c = db.rawQuery("SELECT expires,candidate,consumed FROM invites WHERE id=?", new String[]{id})) {
            if (!c.moveToFirst() || now <= 0 || now >= c.getLong(0) || !candidate.equals(c.getString(1))
                    || c.getInt(2) != 0) throw new SecurityException("Invitation unavailable");
            putPending(db, candidate, id, name, RemoteProtocol.Role.DOM);
            db.execSQL("UPDATE invites SET approved=1,consumed=1 WHERE id=?", new Object[]{id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    synchronized void acceptInvite(PairingInvite invite, String name) {
        if(!com.subhub.app.security.DeviceRolePreferences.supportsSub(context))throw new SecurityException("Sub capability unavailable");
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            if (peer(db, invite.domIdentity, invite.id) != null) throw new SecurityException("Invitation already used");
            putPending(db, invite.domIdentity, invite.id, name, RemoteProtocol.Role.SUB);
            db.execSQL("UPDATE peers SET route=? WHERE peer=? AND generation=?",new Object[]{invite.route,invite.domIdentity,invite.id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    synchronized void activate(String identity, String generation) {
        ContentValues values = new ContentValues(); values.put("state", State.ACTIVE.name());
        SQLiteDatabase db = getWritableDatabase();
        Peer peer = peer(db, identity, generation);
        if (peer == null || peer.state == State.REVOKED) throw new SecurityException("Pairing unavailable");
        if(peer.localRole==RemoteProtocol.Role.DOM?!com.subhub.app.security.DeviceRolePreferences.supportsDom(context):!com.subhub.app.security.DeviceRolePreferences.supportsSub(context))throw new SecurityException("Device role unavailable");
        db.update("peers", values, "peer=? AND generation=? AND state='PENDING'", new String[]{identity,generation});
        activated.accept(peer(db,identity,generation));
    }

    public synchronized void cancelInvite(String id) {
        id=RemoteProtocol.identifier(id);SQLiteDatabase db=getWritableDatabase();List<Peer> cancelled=new ArrayList<>();
        db.beginTransaction();
        try {
            db.execSQL("UPDATE invites SET consumed=1,approved=0 WHERE id=?",new Object[]{id});
            try(Cursor cursor=db.rawQuery("SELECT peer,generation,name,local_role,state,last_sync,route FROM peers WHERE generation=? AND state!='REVOKED'",new String[]{id})){
                while(cursor.moveToNext())cancelled.add(readPeer(cursor));
            }
            for(Peer peer:cancelled)revokeRows(db,peer.identity,peer.generation);
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        for(Peer peer:cancelled)revoked.accept(peer.identity,peer.generation);
    }

    synchronized void setRoute(String identity,String generation,String route) {
        if (route == null || route.length()>4096) throw new IllegalArgumentException("Invalid peer route");
        getWritableDatabase().execSQL("UPDATE peers SET route=? WHERE peer=? AND generation=?",new Object[]{route,identity,generation});
    }

    public synchronized String metadata(String identity,String generation,String name,String fallback) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT value FROM remote_metadata WHERE peer=? AND generation=? AND name=?",new String[]{identity,generation,name})) {
            return c.moveToFirst()?c.getString(0):fallback;
        }
    }

    synchronized void putMetadata(String identity,String generation,String name,String value) {
        getWritableDatabase().execSQL("INSERT OR REPLACE INTO remote_metadata(peer,generation,name,value) VALUES(?,?,?,?)",new Object[]{identity,generation,name,value});
    }

    synchronized void revoke(String identity, String generation) {
        RemoteProtocol.peerId(identity);RemoteProtocol.identifier(generation);
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{revokeRows(db,identity,generation);db.setTransactionSuccessful();}finally{db.endTransaction();}
        revoked.accept(identity,generation);
    }
    private static void revokeRows(SQLiteDatabase db,String identity,String generation){
        db.execSQL("UPDATE peers SET state='REVOKED' WHERE peer=? AND generation=?",new Object[]{identity,generation});
        db.delete("outbox","peer=? AND generation=?",new String[]{identity,generation});
        db.execSQL("UPDATE outgoing SET offered=0,digest=NULL WHERE peer=? AND generation=?",new Object[]{identity,generation});
        db.execSQL("UPDATE commands SET status='REJECTED' WHERE peer=? AND generation=? AND status IN ('QUEUED','SENT','APPLYING')",new Object[]{identity,generation});
    }

    public synchronized long publish(String identity, String generation, RemoteProtocol.Stream stream,
            String key, JSONObject body, boolean deleted, long observedAt) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            Peer peer = active(db, identity, generation);
            if (stream.writer != peer.localRole) throw new SecurityException("Not this stream's authority");
            String encoded = deleted ? "{}" : RemoteProtocol.canonical(body);
            String[] itemArgs = {identity,generation,stream.name(),key};
            try (Cursor c = db.rawQuery("SELECT seq,body,deleted,observed FROM local_objects WHERE peer=? AND generation=? AND stream=? AND item=?", itemArgs)) {
                if (c.moveToFirst() && encoded.equals(StoredPayloads.read(c,1)) && deleted == (c.getInt(2) != 0)
                        && (stream != RemoteProtocol.Stream.RUNTIME || observedAt == c.getLong(3))) {
                    db.setTransactionSuccessful(); return c.getLong(0);
                }
            }
            ensureOutgoing(db, identity, generation, stream);
            long next;
            try (Cursor c = db.rawQuery("SELECT next_seq FROM outgoing WHERE peer=? AND generation=? AND stream=?", streamArgs(identity,generation,stream))) {
                if (!c.moveToFirst() || c.getLong(0) == Long.MAX_VALUE) throw new IllegalStateException("Revision exhausted");
                next = c.getLong(0) + 1;
            }
            RemoteProtocol.Change change = new RemoteProtocol.Change(next,key,encoded,deleted,observedAt);
            putObject(db,"local_objects",identity,generation,stream,change);
            putObject(db,"outbox",identity,generation,stream,change);
            db.execSQL("UPDATE outgoing SET next_seq=? WHERE peer=? AND generation=? AND stream=?", new Object[]{next,identity,generation,stream.name()});
            db.setTransactionSuccessful(); return next;
        } finally { db.endTransaction(); }
    }

    public synchronized RemoteProtocol.Batch nextBatch(String identity, String generation, RemoteProtocol.Stream stream) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            Peer peer = active(db,identity,generation);
            if (stream.writer != peer.localRole) throw new SecurityException("Not this stream's authority");
            ensureOutgoing(db,identity,generation,stream);
            long after;
            try (Cursor c = db.rawQuery("SELECT acked FROM outgoing WHERE peer=? AND generation=? AND stream=?",streamArgs(identity,generation,stream))) {
                c.moveToFirst(); after = c.getLong(0);
            }
            List<RemoteProtocol.Change> changes = new ArrayList<>();
            long through = after;
            int bytes = 0;
            try (Cursor c = db.rawQuery("SELECT seq,item,body,deleted,observed FROM outbox WHERE peer=? AND generation=? AND stream=? AND seq>? ORDER BY seq LIMIT "
                    + RemoteProtocol.MAX_BATCH_RECORDS, new String[]{identity,generation,stream.name(),Long.toString(after)})) {
                while (c.moveToNext()) {
                    String body = StoredPayloads.read(c,2);
                    int size = body.getBytes(StandardCharsets.UTF_8).length;
                    if (!changes.isEmpty() && bytes + size > 512 * 1024) break;
                    changes.add(new RemoteProtocol.Change(c.getLong(0),c.getString(1),body,c.getInt(3)!=0,c.getLong(4)));
                    through = c.getLong(0); bytes += size;
                }
            }
            RemoteProtocol.Batch batch = new RemoteProtocol.Batch(generation,stream,after,through,changes);
            db.execSQL("UPDATE outgoing SET offered=?,digest=? WHERE peer=? AND generation=? AND stream=?",
                    new Object[]{through,batch.digest(),identity,generation,stream.name()});
            db.setTransactionSuccessful(); return batch;
        } finally { db.endTransaction(); }
    }

    public synchronized void acknowledge(String identity, RemoteProtocol.Ack ack) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            active(db,identity,ack.generation);
            try (Cursor c = db.rawQuery("SELECT offered,digest,acked FROM outgoing WHERE peer=? AND generation=? AND stream=?",
                    streamArgs(identity,ack.generation,ack.stream))) {
                if (!c.moveToFirst() || ack.through != c.getLong(0) || !ack.digest.equals(c.getString(1))) {
                    throw new SecurityException("Unmatched acknowledgement");
                }
                if (ack.through < c.getLong(2)) throw new SecurityException("Regressing acknowledgement");
            }
            db.execSQL("UPDATE outgoing SET acked=? WHERE peer=? AND generation=? AND stream=?",new Object[]{ack.through,identity,ack.generation,ack.stream.name()});
            db.delete("outbox","peer=? AND generation=? AND stream=? AND seq<=?",new String[]{identity,ack.generation,ack.stream.name(),Long.toString(ack.through)});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized RemoteProtocol.Ack receive(String identity, RemoteProtocol.Batch batch) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        RemoteProtocol.Ack ack;Peer receivedPeer=null;
        try {
            Peer peer = active(db,identity,batch.generation);
            if (peer.remoteRole() != batch.stream.writer) throw new SecurityException("Peer cannot write this stream");
            if(batch.stream==RemoteProtocol.Stream.EVENTS)RemoteActivityStore.validate(db,peer,batch);
            long current = 0;
            try (Cursor c = db.rawQuery("SELECT through_seq FROM incoming WHERE peer=? AND generation=? AND stream=?",streamArgs(identity,batch.generation,batch.stream))) {
                if (c.moveToFirst()) current = c.getLong(0);
            }
            if (batch.after > current) throw new IllegalStateException("Sync checkpoint gap");
            if (batch.through > current) {
                for (RemoteProtocol.Change change : batch.changes) {
                    if (change.sequence > current) {
                        boolean retired=batch.stream==RemoteProtocol.Stream.CONTRACTS
                                ||batch.stream==RemoteProtocol.Stream.BASELINE&&change.key.startsWith("saved:");
                        RemoteProtocol.Change retained=retired?new RemoteProtocol.Change(change.sequence,change.key,"{}",true,change.observedAt):change;
                        putObject(db,"remote_objects",identity,batch.generation,batch.stream,retained);
                        if(batch.stream==RemoteProtocol.Stream.EVENTS){RemoteActivityStore.accept(db,peer,change,System.currentTimeMillis());receivedPeer=peer;}
                    }
                }
                db.execSQL("INSERT OR REPLACE INTO incoming(peer,generation,stream,through_seq) VALUES(?,?,?,?)",
                        new Object[]{identity,batch.generation,batch.stream.name(),batch.through});
            }
            ack = new RemoteProtocol.Ack(batch);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        if(receivedPeer!=null)activityReceived.accept(receivedPeer);
        return ack;
    }

    public synchronized List<RemoteProtocol.Change> mirror(String identity, String generation, RemoteProtocol.Stream stream) {
        List<RemoteProtocol.Change> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT seq,item,body,deleted,observed FROM remote_objects WHERE peer=? AND generation=? AND stream=? ORDER BY seq",streamArgs(identity,generation,stream))) {
            while (c.moveToNext()) result.add(new RemoteProtocol.Change(c.getLong(0),c.getString(1),StoredPayloads.read(c,2),c.getInt(3)!=0,c.getLong(4)));
        }
        return result;
    }

    public synchronized JSONObject mirrored(String identity, String generation, RemoteProtocol.Stream stream, String key) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT body,deleted FROM remote_objects WHERE peer=? AND generation=? AND stream=? AND item=?",new String[]{identity,generation,stream.name(),key})) {
            if (!c.moveToFirst() || c.getInt(1)!=0) return null;
            try { return new JSONObject(StoredPayloads.read(c,0)); }
            catch (JSONException invalid) { throw new IllegalStateException("Invalid local mirror",invalid); }
        }
    }

    public synchronized void synced(String identity, String generation, long now) {
        active(getWritableDatabase(),identity,generation);
        getWritableDatabase().execSQL("UPDATE peers SET last_sync=? WHERE peer=? AND generation=?",new Object[]{now,identity,generation});
    }

    public synchronized JSONObject outgoingHeads(String identity,String generation) throws JSONException {
        return outgoingHeads(identity,generation,false);
    }

    /** Peer rows are inserted once and archived in place, so this also orders historical additions. */
    public synchronized long additionOrder(String identity,String generation) {
        RemoteProtocol.peerId(identity);RemoteProtocol.identifier(generation);
        try(Cursor row=getReadableDatabase().rawQuery("SELECT rowid FROM peers WHERE peer=? AND generation=?",new String[]{identity,generation})){
            return row.moveToFirst()?row.getLong(0):0;
        }
    }
    synchronized JSONObject outgoingHeads(String identity,String generation,boolean activity) throws JSONException {
        Peer peer=active(getReadableDatabase(),identity,generation);JSONObject result=new JSONObject();
        for(RemoteProtocol.Stream stream:RemoteProtocol.Stream.values()) {
            if(stream==RemoteProtocol.Stream.EVENTS&&!activity)continue;
            if(stream.writer!=peer.localRole)continue;long head=0;
            try(Cursor c=getReadableDatabase().rawQuery("SELECT next_seq FROM outgoing WHERE peer=? AND generation=? AND stream=?",streamArgs(identity,generation,stream))) {
                if(c.moveToFirst())head=c.getLong(0);
            }
            result.put(stream.name(),stream==RemoteProtocol.Stream.CONTRACTS||"true".equals(metadata(identity,generation,"unavailable_"+stream.name(),""))?-1:head);
        }
        return result;
    }

    synchronized void confirmHeads(String identity,String generation,JSONObject heads) throws JSONException {
        confirmHeads(identity,generation,heads,false);
    }
    synchronized void confirmHeads(String identity,String generation,JSONObject heads,boolean activity) throws JSONException {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            Peer peer=active(db,identity,generation);int expected=0;
            for(RemoteProtocol.Stream stream:RemoteProtocol.Stream.values()) {
                if(stream==RemoteProtocol.Stream.EVENTS&&!activity)continue;
                if(stream.writer!=peer.remoteRole())continue;expected++;
                long head=RemoteProtocol.integer(heads,stream.name()),received=0;
                if(head==-1){putMetadata(identity,generation,"confirmed_"+stream.name(),"0");continue;}
                try(Cursor c=db.rawQuery("SELECT through_seq FROM incoming WHERE peer=? AND generation=? AND stream=?",streamArgs(identity,generation,stream))) {
                    if(c.moveToFirst())received=c.getLong(0);
                }
                if(head<received || head<0)throw new SecurityException("Regressing source checkpoint");
                if(head==received)putMetadata(identity,generation,"confirmed_"+stream.name(),Long.toString(System.currentTimeMillis()));
            }
            if(heads.length()!=expected)throw new JSONException("Unsupported source streams");
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }

    /** Retired library payloads never consume configuration revisions or active artwork. */
    synchronized void retireLibraries() {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            for(Peer peer:peers(true)) {
                if(!"true".equals(metadata(peer.identity,peer.generation,"library_retired",""))) {
                    List<String> saved=new ArrayList<>();
                    try(Cursor rows=db.rawQuery("SELECT item FROM local_objects WHERE peer=? AND generation=? AND stream='BASELINE' AND item LIKE 'saved:%' AND deleted=0",new String[]{peer.identity,peer.generation})){
                        while(rows.moveToNext())saved.add(rows.getString(0));
                    }
                    db.delete("outbox","peer=? AND generation=? AND stream='BASELINE' AND item LIKE 'saved:%'",new String[]{peer.identity,peer.generation});
                    if(peer.localRole==RemoteProtocol.Role.SUB&&peer.state==State.ACTIVE)
                        for(String key:saved)publish(peer.identity,peer.generation,RemoteProtocol.Stream.BASELINE,key,new JSONObject(),true,System.currentTimeMillis());
                    else db.delete("local_objects","peer=? AND generation=? AND stream='BASELINE' AND item LIKE 'saved:%'",new String[]{peer.identity,peer.generation});
                    putMetadata(peer.identity,peer.generation,"library_retired","true");
                }
            }
            for(String table:new String[]{"outbox","local_objects","remote_objects"})
                db.delete(table,"stream='CONTRACTS'",null);
            db.delete("remote_objects","stream='BASELINE' AND item LIKE 'saved:%'",null);
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }

    public synchronized List<RemoteProtocol.Change> published(String identity,String generation,RemoteProtocol.Stream stream) {
        List<RemoteProtocol.Change> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT seq,item,body,deleted,observed FROM local_objects WHERE peer=? AND generation=? AND stream=? ORDER BY seq",streamArgs(identity,generation,stream))) {
            while(c.moveToNext())result.add(new RemoteProtocol.Change(c.getLong(0),c.getString(1),StoredPayloads.read(c,2),c.getInt(3)!=0,c.getLong(4)));
        }
        return result;
    }

    public enum CommandState { QUEUED,SENT,APPLYING,APPLIED,REJECTED,CONFLICT }
    public static final class Command {
        public final String id,body;
        public final long revision;
        public final CommandState state;
        Command(String id,long revision,CommandState state,String body){this.id=id;this.revision=revision;this.state=state;this.body=body;}
        public boolean pending(){return state==CommandState.QUEUED || state==CommandState.SENT || state==CommandState.APPLYING;}
    }

    public synchronized Command latestCommand(String identity,String generation) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,revision,status,body FROM commands WHERE peer=? AND generation=? ORDER BY revision DESC LIMIT 1",new String[]{identity,generation})) {
            return c.moveToFirst()?readCommand(c):null;
        }
    }
    synchronized Command command(String identity,String generation,String id) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,revision,status,body FROM commands WHERE peer=? AND generation=? AND id=?",new String[]{identity,generation,id})) {
            return c.moveToFirst()?readCommand(c):null;
        }
    }

    public synchronized Command queueConfiguration(String identity,String generation,long expectedDesired,long expectedApplied,JSONObject configuration) throws JSONException {
        if(!com.subhub.app.security.DeviceRolePreferences.supportsDom(context))throw new SecurityException("Dom capability unavailable");
        if(!com.subhub.app.configuration.LegacyPackMigration.isReady(context))throw new SecurityException("Configuration recovery is incomplete");
        JSONObject doc=RemoteConfiguration.active(configuration);SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            Peer peer=active(db,identity,generation);if(peer.localRole!=RemoteProtocol.Role.DOM)throw new SecurityException("Only the paired Dom controls configuration");
            doc=com.subhub.app.tasks.TaskContractFields.forPeer(doc,
                    "1".equals(metadata(identity,generation,"tasks_version","0")), true);
            long previous=Long.parseLong(metadata(identity,generation,"desired_revision","0"));
            if(previous!=expectedDesired || previous==Long.MAX_VALUE || expectedApplied<0)throw new IllegalStateException("Configuration changed; reload before saving");
            Command pending=latestCommand(identity,generation);if(pending!=null && pending.pending())throw new IllegalStateException("A configuration change is pending");
            String id=java.util.UUID.randomUUID().toString();long revision=previous+1;
            JSONObject body=new JSONObject().put("id",id).put("revision",revision).put("base",expectedApplied).put("configuration",doc);
            insertCommand(db,identity,generation,id,revision,CommandState.QUEUED,RemoteProtocol.canonical(body));
            putMetadata(identity,generation,"desired_revision",Long.toString(revision));
            publish(identity,generation,RemoteProtocol.Stream.CONFIGURATION,"desired",body,false,System.currentTimeMillis());
            db.setTransactionSuccessful();return command(identity,generation,id);
        } finally {db.endTransaction();}
    }

    synchronized Command stageCommand(String identity,String generation,JSONObject body) throws JSONException {
        Peer peer=active(getWritableDatabase(),identity,generation);if(peer.localRole!=RemoteProtocol.Role.SUB)throw new SecurityException("Not a managed device");
        RemoteConfiguration.keys(body,"id","revision","base","configuration");String id=RemoteProtocol.identifier(body.getString("id"));
        long revision=RemoteProtocol.integer(body,"revision"),base=RemoteProtocol.integer(body,"base");
        if(base<0 || revision<=base)throw new IllegalArgumentException("Invalid configuration revision");
        body.getJSONObject("configuration");String encoded=RemoteProtocol.canonical(body);
        Command old=command(identity,generation,id);
        if(old!=null) {if(!old.body.equals(encoded))throw new SecurityException("Command identity reused");return old;}
        insertCommand(getWritableDatabase(),identity,generation,id,revision,CommandState.QUEUED,encoded);
        return command(identity,generation,id);
    }

    synchronized void markApplying(String identity,String generation,String id) {
        active(getWritableDatabase(),identity,generation);
        getWritableDatabase().execSQL("UPDATE commands SET status='APPLYING' WHERE peer=? AND generation=? AND id=? AND status='QUEUED'",new Object[]{identity,generation,id});
    }

    synchronized void commandResult(String identity,String generation,Command command,CommandState state,JSONObject applied,String reason) throws JSONException {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            Peer peer=active(db,identity,generation);if(peer.localRole!=RemoteProtocol.Role.SUB)throw new SecurityException("Not a managed device");
            if(state!=CommandState.APPLIED && state!=CommandState.CONFLICT && state!=CommandState.REJECTED)throw new IllegalArgumentException("Not a terminal command result");
            Command current=command(identity,generation,command.id);
            if(current==null || !current.body.equals(command.body))throw new SecurityException("Unknown configuration command");
            if(!current.pending()) {
                if(current.state!=state)throw new SecurityException("Configuration result changed");
                db.setTransactionSuccessful();return;
            }
            if(state==CommandState.APPLIED) {
                putMetadata(identity,generation,"applied_revision",Long.toString(command.revision));
                putMetadata(identity,generation,"applied_configuration",RemoteProtocol.canonical(RemoteConfiguration.active(applied)));
            }
            db.execSQL("UPDATE commands SET status=? WHERE peer=? AND generation=? AND id=?",new Object[]{state.name(),identity,generation,command.id});
            JSONObject result=new JSONObject().put("schema",1).put("id",command.id).put("revision",command.revision).put("state",state.name())
                    .put("appliedRevision",Long.parseLong(metadata(identity,generation,"applied_revision","0"))).put("reason",reason);
            publish(identity,generation,RemoteProtocol.Stream.COMMAND_RESULT,command.id,result,false,System.currentTimeMillis());
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }

    synchronized void markSent(String identity,RemoteProtocol.Batch batch) throws JSONException {
        if(batch.stream!=RemoteProtocol.Stream.CONFIGURATION)return;
        for(RemoteProtocol.Change row:batch.changes) {
            String id=new JSONObject(row.body).optString("id","");
            if(row.key.startsWith("end:"))RemoteEndCommands.markSent(this,identity,batch.generation,id);
            getWritableDatabase().execSQL("UPDATE commands SET status='SENT' WHERE peer=? AND generation=? AND id=? AND status='QUEUED'",new Object[]{identity,batch.generation,id});
        }
    }

    synchronized void acceptResult(String identity,String generation,JSONObject result) throws JSONException {
        Peer peer=active(getWritableDatabase(),identity,generation);if(peer.localRole!=RemoteProtocol.Role.DOM)throw new SecurityException("Unexpected command result");
        if(RemoteProtocol.integer(result,"schema")!=1)throw new IllegalArgumentException("Unsupported command result");
        Command old=command(identity,generation,result.getString("id"));if(old==null)return;
        CommandState state=CommandState.valueOf(result.getString("state"));long applied=RemoteProtocol.integer(result,"appliedRevision");
        if(RemoteProtocol.integer(result,"revision")!=old.revision || applied<0 || state==CommandState.APPLIED && applied!=old.revision
                || state!=CommandState.APPLIED && state!=CommandState.REJECTED && state!=CommandState.CONFLICT)throw new SecurityException("Invalid command result");
        if(!old.pending() && old.state!=state)throw new SecurityException("Configuration result changed");
        getWritableDatabase().execSQL("UPDATE commands SET status=? WHERE peer=? AND generation=? AND id=?",new Object[]{state.name(),identity,generation,old.id});
        long previous=Long.parseLong(metadata(identity,generation,"applied_revision","0"));
        if(applied>=previous)putMetadata(identity,generation,"applied_revision",Long.toString(applied));
        putMetadata(identity,generation,"last_command_reason",result.optString("reason",""));
    }

    private static Command readCommand(Cursor c){return new Command(c.getString(0),c.getLong(1),CommandState.valueOf(c.getString(2)),StoredPayloads.read(c,3));}
    private static void insertCommand(SQLiteDatabase db,String peer,String generation,String id,long revision,CommandState state,String body) {
        db.execSQL("INSERT INTO commands(peer,generation,id,revision,status,body) VALUES(?,?,?,?,?,?)",new Object[]{peer,generation,id,revision,state.name(),StoredPayloads.bind(body)});
    }

    private static void putPending(SQLiteDatabase db,String identity,String generation,String name,RemoteProtocol.Role role) {
        RemoteProtocol.peerId(identity); RemoteProtocol.identifier(generation);
        name=RemoteProtocol.deviceName(name);
        db.execSQL("INSERT INTO peers(peer,generation,name,local_role,state) VALUES(?,?,?,?,'PENDING')",new Object[]{identity,generation,name.trim(),role.name()});
    }
    private static Peer active(SQLiteDatabase db,String identity,String generation) {
        Peer peer = peer(db,identity,generation);
        if (peer == null || peer.state != State.ACTIVE) throw new SecurityException("Pairing not active");
        return peer;
    }
    private static Peer peer(SQLiteDatabase db,String identity,String generation) {
        try (Cursor c = db.rawQuery("SELECT peer,generation,name,local_role,state,last_sync,route FROM peers WHERE peer=? AND generation=?",new String[]{identity,generation})) {
            return c.moveToFirst() ? readPeer(c) : null;
        }
    }
    private static Peer readPeer(Cursor c) {
        return new Peer(c.getString(0),c.getString(1),c.getString(2),RemoteProtocol.Role.valueOf(c.getString(3)),State.valueOf(c.getString(4)),c.getLong(5),c.getString(6));
    }
    private static String[] streamArgs(String identity,String generation,RemoteProtocol.Stream stream) {
        return new String[]{identity,generation,stream.name()};
    }
    private static void ensureOutgoing(SQLiteDatabase db,String identity,String generation,RemoteProtocol.Stream stream) {
        db.execSQL("INSERT OR IGNORE INTO outgoing(peer,generation,stream) VALUES(?,?,?)",new Object[]{identity,generation,stream.name()});
    }
    private static void putObject(SQLiteDatabase db,String table,String identity,String generation,RemoteProtocol.Stream stream,RemoteProtocol.Change change) {
        ContentValues v = new ContentValues();
        v.put("peer",identity);v.put("generation",generation);v.put("stream",stream.name());v.put("item",change.key);
        v.put("seq",change.sequence);StoredPayloads.put(v,"body",change.body);v.put("deleted",change.deleted ? 1 : 0);v.put("observed",change.observedAt);
        if (db.insertWithOnConflict(table,null,v,SQLiteDatabase.CONFLICT_REPLACE) == -1) {
            throw new android.database.sqlite.SQLiteException("Could not persist sync record");
        }
    }
}
