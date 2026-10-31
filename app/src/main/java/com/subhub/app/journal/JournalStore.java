package com.subhub.app.journal;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import com.subhub.app.remote.RemoteProtocol;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Visibility, coalesced delivery records and receive receipts commit atomically. */
public final class JournalStore extends SQLiteOpenHelper {
    public static final int PAGE_SIZE = 24, SYNC_PAGE = 16, MAX_ENTRIES = 1000;

    public JournalStore(Context context) { this(context, "profile_journal_v1.db"); }
    public JournalStore(Context context, String name) { super(context.getApplicationContext(), name, null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE entries(id TEXT PRIMARY KEY,role TEXT NOT NULL,created INTEGER NOT NULL,body TEXT NOT NULL)");
        db.execSQL("CREATE TABLE audiences(id TEXT NOT NULL,scope TEXT NOT NULL,PRIMARY KEY(id,scope))");
        db.execSQL("CREATE TABLE outgoing(seq INTEGER PRIMARY KEY AUTOINCREMENT,scope TEXT NOT NULL,id TEXT NOT NULL,body TEXT NOT NULL,UNIQUE(scope,id))");
        db.execSQL("CREATE TABLE incoming(scope TEXT NOT NULL,id TEXT NOT NULL,created INTEGER NOT NULL,body TEXT NOT NULL,PRIMARY KEY(scope,id))");
        db.execSQL("CREATE TABLE cursors(scope TEXT PRIMARY KEY,received INTEGER NOT NULL DEFAULT 0,offered INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE receipts(scope TEXT NOT NULL,seq INTEGER NOT NULL,digest TEXT NOT NULL,PRIMARY KEY(scope,seq))");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported journal database version");
    }

    private static String role(boolean dom) { return dom ? "DOM" : "SUB"; }
    static String scope(String value) {
        if (value == null || !(value.equals("local-dom") || value.equals("local-sub")
                || value.matches("[0-9a-f]{64}_[A-Za-z0-9_-]{1,128}")))
            throw new IllegalArgumentException("Invalid journal relationship");
        return value;
    }

    public synchronized List<JournalEntry> own(boolean dom, int offset) throws JSONException {
        return list("entries", "role=?", new String[]{role(dom)}, offset);
    }

    public synchronized List<JournalEntry> shared(String relationship, int offset) throws JSONException {
        return list("incoming", "scope=?", new String[]{scope(relationship)}, offset);
    }

    public synchronized List<JournalEntry> localShared(boolean viewerDom, int offset) throws JSONException {
        if (offset < 0 || offset > MAX_ENTRIES) throw new IllegalArgumentException("Invalid journal page");
        List<JournalEntry> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT e.body FROM entries e JOIN audiences a ON e.id=a.id "
                + "WHERE e.role=? AND a.scope=? ORDER BY e.created DESC,e.id DESC LIMIT ? OFFSET ?",
                new String[]{role(!viewerDom), viewerDom ? "local-dom" : "local-sub", Integer.toString(PAGE_SIZE + 1), Integer.toString(offset)})) {
            while (c.moveToNext()) result.add(JournalEntry.parse(new JSONObject(c.getString(0))));
        }
        return result;
    }

    private List<JournalEntry> list(String table, String where, String[] args, int offset) throws JSONException {
        if (offset < 0 || offset > MAX_ENTRIES) throw new IllegalArgumentException("Invalid journal page");
        List<JournalEntry> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(table, new String[]{"body"}, where, args, null, null,
                "created DESC,id DESC", (PAGE_SIZE + 1) + " OFFSET " + offset)) {
            while (c.moveToNext()) result.add(JournalEntry.parse(new JSONObject(c.getString(0))));
        }
        return result;
    }

    public synchronized JournalEntry entry(boolean dom, String relationship, String id) throws JSONException {
        boolean own = relationship == null;
        try (Cursor c = getReadableDatabase().query(own ? "entries" : "incoming", new String[]{"body"},
                own ? "role=? AND id=?" : "scope=? AND id=?",
                new String[]{own ? role(dom) : scope(relationship), id}, null, null, null)) {
            return c.moveToFirst() ? JournalEntry.parse(new JSONObject(c.getString(0))) : null;
        }
    }

    public synchronized Set<String> audience(boolean dom, String id) throws JSONException {
        if (entry(dom, null, id) == null) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        try (Cursor c = getReadableDatabase().query("audiences", new String[]{"scope"}, "id=?",
                new String[]{id}, null, null, null)) {
            while (c.moveToNext()) result.add(c.getString(0));
        }
        return result;
    }

    public synchronized void save(boolean dom, JournalEntry value, Set<String> selected) throws JSONException {
        for (String audience : selected) scope(audience);
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            requireOwner(db, dom, value.id);
            if (entry(dom, null, value.id) == null && count(db, "entries", "role", role(dom)) >= MAX_ENTRIES)
                throw new IllegalStateException("Journal entry limit reached");
            Set<String> previous = audience(dom, value.id);
            ContentValues row = new ContentValues(); row.put("id", value.id); row.put("role", role(dom));
            row.put("created", value.created); row.put("body", value.json().toString());
            if (db.insertWithOnConflict("entries", null, row, SQLiteDatabase.CONFLICT_REPLACE) < 0)
                throw new android.database.sqlite.SQLiteException("Journal entry could not be saved");
            db.delete("audiences", "id=?", new String[]{value.id});
            for (String audience : selected) {
                row = new ContentValues(); row.put("id", value.id); row.put("scope", audience);
                db.insertOrThrow("audiences", null, row);
            }
            Set<String> affected = new LinkedHashSet<>(previous); affected.addAll(selected);
            for (String audience : affected) if (!audience.startsWith("local-"))
                enqueue(db, audience, value.id, selected.contains(audience) ? value : null);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized void delete(boolean dom, String id) throws JSONException {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            requireOwner(db, dom, id);
            for (String audience : audience(dom, id)) if (!audience.startsWith("local-")) enqueue(db, audience, id, null);
            db.delete("audiences", "id=?", new String[]{id});
            db.delete("entries", "role=? AND id=?", new String[]{role(dom), id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private static void requireOwner(SQLiteDatabase db, boolean dom, String id) {
        try (Cursor c = db.query("entries", new String[]{"role"}, "id=?", new String[]{id}, null, null, null)) {
            if (c.moveToFirst() && !role(dom).equals(c.getString(0))) throw new SecurityException("Journal belongs to another role");
        }
    }

    private static void enqueue(SQLiteDatabase db, String audience, String id, JournalEntry value) throws JSONException {
        JSONObject body = new JSONObject().put("id", id).put("deleted", value == null);
        if (value != null) body.put("entry", value.json());
        db.delete("outgoing", "scope=? AND id=?", new String[]{audience, id});
        ContentValues row = new ContentValues(); row.put("scope", audience); row.put("id", id); row.put("body", body.toString());
        db.insertOrThrow("outgoing", null, row);
    }

    public synchronized long received(String relationship) { return cursor(scope(relationship), "received"); }
    private long cursor(String relationship, String key) {
        try (Cursor c = getReadableDatabase().query("cursors", new String[]{key}, "scope=?",
                new String[]{relationship}, null, null, null)) { return c.moveToFirst() ? c.getLong(0) : 0; }
    }

    public synchronized JSONArray outgoing(String relationship, long after) throws JSONException {
        scope(relationship);
        if (after < 0 || after > cursor(relationship, "offered")) throw new SecurityException("Unmatched journal cursor");
        JSONArray result = new JSONArray(); long through = after;
        try (Cursor c = getReadableDatabase().query("outgoing", new String[]{"seq", "body"}, "scope=? AND seq>?",
                new String[]{relationship, Long.toString(after)}, null, null, "seq", Integer.toString(SYNC_PAGE))) {
            while (c.moveToNext()) {
                through = c.getLong(0);
                result.put(new JSONObject().put("seq", through).put("change", new JSONObject(c.getString(1))));
            }
        }
        getWritableDatabase().execSQL("INSERT OR IGNORE INTO cursors(scope) VALUES(?)", new Object[]{relationship});
        getWritableDatabase().execSQL("UPDATE cursors SET offered=MAX(offered,?) WHERE scope=?", new Object[]{through, relationship});
        return result;
    }

    public synchronized long receive(String relationship, JSONArray rows) throws Exception {
        scope(relationship);
        if (rows.length() > SYNC_PAGE) throw new JSONException("Too many journal changes");
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            long through = received(relationship), previous = 0;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i); JournalEntry.keys(row, Set.of("seq", "change"));
                long seq = RemoteProtocol.integer(row, "seq");
                if (seq <= previous) throw new SecurityException("Unordered journal changes");
                previous = seq;
                String digest = hash(RemoteProtocol.canonical(row));
                if (seq <= through) {
                    try (Cursor receipt = db.query("receipts", new String[]{"digest"}, "scope=? AND seq=?",
                            new String[]{relationship, Long.toString(seq)}, null, null, null)) {
                        if (!receipt.moveToFirst() || !digest.equals(receipt.getString(0)))
                            throw new SecurityException("Conflicting journal replay");
                    }
                    continue;
                }
                JSONObject change = row.getJSONObject("change");
                Object deleted = change.get("deleted");
                if (!(deleted instanceof Boolean)) throw new JSONException("Invalid journal deletion");
                JournalEntry.keys(change, (Boolean) deleted ? Set.of("id", "deleted") : Set.of("id", "deleted", "entry"));
                String id = JournalEntry.text(change, "id");
                if (!id.matches("[0-9a-f]{32}")) throw new JSONException("Invalid journal identity");
                db.delete("incoming", "scope=? AND id=?", new String[]{relationship, id});
                if (!(Boolean) deleted) {
                    JournalEntry entry = JournalEntry.parse(change.getJSONObject("entry"));
                    if (!id.equals(entry.id)) throw new SecurityException("Journal identity mismatch");
                    if (count(db, "incoming", "scope", relationship) >= MAX_ENTRIES)
                        throw new SecurityException("Journal entry limit reached");
                    ContentValues value = new ContentValues(); value.put("scope", relationship); value.put("id", id);
                    value.put("created", entry.created); value.put("body", entry.json().toString());
                    db.insertOrThrow("incoming", null, value);
                }
                ContentValues receipt = new ContentValues(); receipt.put("scope", relationship);
                receipt.put("seq", seq); receipt.put("digest", digest); db.insertOrThrow("receipts", null, receipt);
                through = seq;
            }
            db.execSQL("INSERT OR IGNORE INTO cursors(scope) VALUES(?)", new Object[]{relationship});
            db.execSQL("UPDATE cursors SET received=? WHERE scope=?", new Object[]{through, relationship});
            db.setTransactionSuccessful(); return through;
        } finally { db.endTransaction(); }
    }

    public synchronized Set<String> media(boolean dom, String relationship) throws JSONException {
        Set<String> hashes = new HashSet<>();
        try (Cursor c = getReadableDatabase().query(relationship == null ? "entries" : "incoming", new String[]{"body"},
                relationship == null ? "role=?" : "scope=?", new String[]{relationship == null ? role(dom) : scope(relationship)},
                null, null, null)) {
            while (c.moveToNext()) for (JournalAttachment item : JournalEntry.parse(new JSONObject(c.getString(0))).media()) hashes.add(item.hash);
        }
        return hashes;
    }

    private static int count(SQLiteDatabase db, String table, String key, String value) {
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + table + " WHERE " + key + "=?", new String[]{value})) {
            c.moveToFirst(); return c.getInt(0);
        }
    }
    private static String hash(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte b : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
}
