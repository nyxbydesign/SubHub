package com.subhub.app.storage;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** The SQLite boundary accepts old text and versioned blobs; callers always see original text. */
public final class StoredPayloads {
    public static final int MAX_BATCH_BYTES = 1024 * 1024;
    private static final String[] TABLES = {"local_objects", "outbox", "remote_objects", "commands",
            "activity_source", "activity_receipts"};
    private static final int BATCH = 64;
    private StoredPayloads() { }

    public static String read(Cursor cursor, int column) {
        if (cursor.getType(column) == Cursor.FIELD_TYPE_STRING) return cursor.getString(column);
        if (cursor.getType(column) == Cursor.FIELD_TYPE_BLOB)
            return PayloadCodec.decompress(cursor.getBlob(column));
        throw new IllegalStateException("Stored payload is not text or an encoded blob");
    }

    public static Object bind(String text) {
        byte[] encoded = PayloadCodec.compressIfUseful(text);
        return encoded == null ? text : encoded;
    }

    public static void put(ContentValues values, String column, String text) {
        Object value = bind(text);
        if (value instanceof byte[]) values.put(column, (byte[]) value);
        else values.put(column, (String) value);
    }

    public static void createProgress(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE payload_maintenance(table_name TEXT PRIMARY KEY,last_id INTEGER NOT NULL)");
    }

    /** Idle-worker maintenance only: opening an old database never rewrites its history. */
    public static int compressBatch(SQLiteDatabase db, int maximumRows) {
        if (!db.inTransaction() || maximumRows < 1 || maximumRows > 256)
            throw new IllegalArgumentException("A bounded payload transaction is required");
        int visited = 0, bytes = 0;
        boolean byteLimit = false;
        for (String table : TABLES) {
            long after = 0;
            try (Cursor progress = db.rawQuery("SELECT last_id FROM payload_maintenance WHERE table_name=?", new String[]{table})) {
                if (progress.moveToFirst()) after = progress.getLong(0);
            }
            List<Row> batch = new ArrayList<>();
            try (Cursor rows = db.rawQuery("SELECT rowid,body FROM " + table
                    + " WHERE rowid>? ORDER BY rowid LIMIT " + (maximumRows - visited), new String[]{Long.toString(after)})) {
                while (rows.moveToNext()) {
                    String original = read(rows, 1);
                    int length = original.getBytes(StandardCharsets.UTF_8).length;
                    if (visited + batch.size() > 0 && bytes + length > MAX_BATCH_BYTES) { byteLimit = true; break; }
                    bytes += length;
                    Object replacement = rows.getType(1) == Cursor.FIELD_TYPE_STRING ? bind(original) : null;
                    boolean changed = replacement instanceof byte[];
                    batch.add(new Row(rows.getLong(0), changed ? replacement : null, changed));
                    if (bytes >= MAX_BATCH_BYTES) { byteLimit = true; break; }
                }
            }
            for (Row row : batch) {
                update(db, table, row);
                after = row.id; visited++;
            }
            if (!batch.isEmpty())
                db.execSQL("INSERT OR REPLACE INTO payload_maintenance VALUES(?,?)", new Object[]{table, after});
            if (visited == maximumRows || byteLimit) break;
        }
        return visited;
    }

    /** Explicit maintenance/rollback tooling only; callers supply the recovery transaction. */
    public static int compressLegacy(SQLiteDatabase db) { return rewrite(db, false); }

    /** Forward rollback builds can restore plain payloads in the same atomic transaction. */
    public static int restorePlaintext(SQLiteDatabase db) { return rewrite(db, true); }

    private static int rewrite(SQLiteDatabase db, boolean plain) {
        if (!db.inTransaction()) throw new IllegalStateException("Payload migration needs a transaction");
        int changed = 0;
        for (String table : TABLES) {
            long after = 0;
            while (true) {
                List<Row> batch = new ArrayList<>();
                int bytes = 0;
                try (Cursor rows = db.rawQuery("SELECT rowid,body FROM " + table
                        + " WHERE rowid>? ORDER BY rowid LIMIT " + BATCH,
                        new String[]{Long.toString(after)})) {
                    while (rows.moveToNext()) {
                        long id = rows.getLong(0);
                        String original = read(rows, 1);
                        int length = original.getBytes(StandardCharsets.UTF_8).length;
                        if (!batch.isEmpty() && bytes + length > MAX_BATCH_BYTES) break;
                        bytes += length;
                        boolean encoded = rows.getType(1) == Cursor.FIELD_TYPE_BLOB;
                        Object replacement = plain && encoded ? original : !plain && !encoded ? bind(original) : null;
                        boolean replace = plain ? encoded : replacement instanceof byte[];
                        batch.add(new Row(id, replace ? replacement : null, replace));
                        if (bytes >= MAX_BATCH_BYTES) break;
                    }
                }
                if (batch.isEmpty()) break;
                for (Row row : batch) {
                    after = row.id;
                    if (row.changed) { update(db, table, row); changed++; }
                }
            }
        }
        if (plain) db.delete("payload_maintenance", null, null);
        return changed;
    }

    private static void update(SQLiteDatabase db, String table, Row row) {
        if (!row.changed) return;
        ContentValues values = new ContentValues();
        if (row.value instanceof byte[]) values.put("body", (byte[]) row.value);
        else values.put("body", (String) row.value);
        if (db.update(table, values, "rowid=?", new String[]{Long.toString(row.id)}) != 1)
            throw new IllegalStateException("Payload migration lost a record");
    }

    private static final class Row {
        final long id;
        final Object value;
        final boolean changed;
        Row(long id, Object value, boolean changed) { this.id = id; this.value = value; this.changed = changed; }
    }
}
