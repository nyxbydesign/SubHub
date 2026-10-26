package com.subhub.app.remote;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.subhub.app.activity.ActivityEvent;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

/** Activity receipts and the protocol checkpoint commit together in the existing peer database. */
final class RemoteActivityStore {
    private RemoteActivityStore() {}

    static void create(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE activity_receipts(id INTEGER PRIMARY KEY AUTOINCREMENT,entry_key TEXT NOT NULL UNIQUE,"
                + "peer TEXT NOT NULL,generation TEXT NOT NULL,event_id TEXT NOT NULL,body TEXT NOT NULL,"
                + "received INTEGER NOT NULL,is_read INTEGER NOT NULL DEFAULT 0,notified INTEGER NOT NULL DEFAULT 0,"
                + "UNIQUE(peer,generation,event_id),FOREIGN KEY(peer,generation) REFERENCES peers(peer,generation))");
        db.execSQL("CREATE INDEX activity_peer_receipts ON activity_receipts(peer,generation,id)");
        db.execSQL("CREATE TABLE activity_source(id INTEGER PRIMARY KEY AUTOINCREMENT,event_id TEXT NOT NULL UNIQUE,"
                + "source_key TEXT NOT NULL UNIQUE,body TEXT NOT NULL,observed INTEGER NOT NULL,peer TEXT,generation TEXT,published INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX activity_pending_source ON activity_source(peer,generation,published,id)");
        db.execSQL("CREATE TABLE activity_state(name TEXT PRIMARY KEY,value TEXT NOT NULL)");
    }

    static String entryKey(RemoteStore.Peer peer, String event) {
        return RemoteProtocol.sha256((peer.identity + ":" + peer.generation + ":" + event).getBytes(StandardCharsets.UTF_8));
    }

    static void validate(SQLiteDatabase db, RemoteStore.Peer peer, RemoteProtocol.Batch batch) {
        if (peer.localRole != RemoteProtocol.Role.DOM) throw new SecurityException("Not an activity source");
        for (RemoteProtocol.Change change : batch.changes) {
            try {
                ActivityEvent event = RemoteActivityWire.decode(new JSONObject(change.body));
                if (change.deleted || !event.id.equals(change.key) || event.observedAt != change.observedAt)
                    throw new SecurityException("Activity identity mismatch");
                try (Cursor old = db.rawQuery("SELECT body FROM activity_receipts WHERE peer=? AND generation=? AND event_id=?",
                        new String[]{peer.identity, peer.generation, event.id})) {
                    if (old.moveToFirst() && !old.getString(0).equals(change.body))
                        throw new SecurityException("Activity facts are immutable");
                }
            } catch (JSONException invalid) { throw new SecurityException("Invalid activity record", invalid); }
        }
    }

    static void accept(SQLiteDatabase db, RemoteStore.Peer peer, RemoteProtocol.Change change, long receivedAt) {
        ContentValues row = new ContentValues();
        row.put("entry_key", entryKey(peer, change.key));row.put("peer", peer.identity);row.put("generation", peer.generation);
        row.put("event_id", change.key);row.put("body", change.body);row.put("received", receivedAt);
        db.insertWithOnConflict("activity_receipts", null, row, SQLiteDatabase.CONFLICT_IGNORE);
    }

    static String readState(SQLiteDatabase db, String key, String fallback) {
        try (Cursor value = db.rawQuery("SELECT value FROM activity_state WHERE name=?", new String[]{key})) {
            return value.moveToFirst() ? value.getString(0) : fallback;
        }
    }
    static void writeState(SQLiteDatabase db, String key, String value) {
        ContentValues row = new ContentValues();row.put("name", key);row.put("value", value);
        db.insertWithOnConflict("activity_state", null, row, SQLiteDatabase.CONFLICT_REPLACE);
    }
}
