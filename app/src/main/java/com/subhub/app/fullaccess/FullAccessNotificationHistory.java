package com.subhub.app.fullaccess;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.io.File;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Local-only notification history with bounded public previews. */
final class FullAccessNotificationHistory extends SQLiteOpenHelper {
    static final long RETENTION_MILLIS = 7 * 24 * 60 * 60_000L;
    private static final int MAX_ROWS = 5000;
    private static final String TABLE = "posted_notifications";
    private static final String FILE = "full-access-notifications.db";
    private static final String[] FIELDS = {"_id", "pkg", "category", "posted_at", "public_title", "public_text"};
    private static final Pattern PACKAGE = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)*");

    FullAccessNotificationHistory(Context context) { this(context, false); }

    FullAccessNotificationHistory(Context context, boolean demo) {
        super(context, path(context, demo).getAbsolutePath(), null, 2);
        setWriteAheadLoggingEnabled(false);
    }

    private static File path(Context context, boolean demo) {
        return new File(context.getNoBackupFilesDir(), demo ? "demo-" + FILE : FILE);
    }

    static void clear(Context context) { clear(context, false); }

    static void clear(Context context, boolean demo) {
        File file = path(context, demo);
        if (file.exists()) SQLiteDatabase.deleteDatabase(file);
    }

    @Override public void onConfigure(SQLiteDatabase database) {
        try (Cursor setting = database.rawQuery("PRAGMA secure_delete = ON", null)) {
            setting.moveToFirst();
        }
    }

    @Override public void onCreate(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE " + TABLE + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, epoch INTEGER NOT NULL, "
                + "event_key TEXT NOT NULL, pkg TEXT NOT NULL, category TEXT NOT NULL, "
                + "posted_at INTEGER NOT NULL, public_title TEXT NOT NULL DEFAULT '', "
                + "public_text TEXT NOT NULL DEFAULT '', UNIQUE(epoch,event_key,posted_at))");
        database.execSQL("CREATE INDEX notification_history_time ON " + TABLE + "(posted_at)");
    }

    @Override public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        if (oldVersion != 1 || newVersion != 2)
            throw new IllegalStateException("Unsupported notification-history schema");
        database.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN public_title TEXT NOT NULL DEFAULT ''");
        database.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN public_text TEXT NOT NULL DEFAULT ''");
    }

    void record(long epoch, String eventKey, String packageName, String category, long time, long now,
            FullAccessNotificationListener.Preview preview) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            prune(db, now);
            ContentValues row = new ContentValues();
            row.put("epoch", epoch);
            row.put("event_key", eventKey);
            row.put("pkg", packageName);
            row.put("category", category);
            row.put("posted_at", time);
            row.put("public_title", preview.title());
            row.put("public_text", preview.text());
            db.insertWithOnConflict(TABLE, null, row, SQLiteDatabase.CONFLICT_REPLACE);
            db.execSQL("DELETE FROM " + TABLE + " WHERE _id IN (SELECT _id FROM " + TABLE
                    + " ORDER BY _id DESC LIMIT -1 OFFSET " + MAX_ROWS + ")");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    JSONObject page(Context context, long epoch, long beforeId, long since, long now, int limit) throws JSONException {
        SQLiteDatabase db = readDatabase(epoch, now);
        JSONArray rows = new JSONArray();
        long last = -1;
        String next = null;
        try (Cursor cursor = db.query(TABLE, FIELDS,
                "epoch=? AND _id<? AND posted_at>=?",
                new String[]{Long.toString(epoch), Long.toString(beforeId), Long.toString(since)}, null, null,
                "_id DESC", Integer.toString(limit + 1))) {
            while (cursor.moveToNext()) {
                if (rows.length() == limit) {
                    next = Long.toString(last);
                    break;
                }
                last = cursor.getLong(0);
                rows.put(item(context, cursor));
            }
        }
        return result(rows, since, next);
    }

    JSONObject groups(Context context, long epoch, String pageCursor, long since, long now, int limit)
            throws JSONException {
        long before = Long.MAX_VALUE;
        String afterPackage = "";
        if (pageCursor != null && !pageCursor.isEmpty()) {
            int separator = pageCursor.indexOf(':');
            if (separator < 1) throw new IllegalArgumentException("Invalid notification group cursor");
            before = FullAccessDataSources.cursor(pageCursor.substring(0, separator), Long.MAX_VALUE, Long.MAX_VALUE);
            afterPackage = requirePackage(pageCursor.substring(separator + 1));
        }
        String epochKey = Long.toString(epoch);
        String beforeKey = Long.toString(before);
        SQLiteDatabase db = readDatabase(epoch, now);
        JSONArray rows = new JSONArray();
        String last = null;
        String next = null;
        // The page is selected before retrieving previews. Timestamp ties sort by package, and the
        // latest row ID picks the preview deterministically when one app posts at the same time.
        String query = "WITH app_groups AS (SELECT pkg, COUNT(*) AS total, MAX(posted_at) AS latest "
                + "FROM " + TABLE + " WHERE epoch=? AND posted_at>=? GROUP BY pkg "
                + "HAVING latest<CAST(? AS INTEGER) OR (latest=CAST(? AS INTEGER) AND pkg>?) "
                + "ORDER BY latest DESC, pkg ASC LIMIT ?) "
                + "SELECT g.pkg, g.total, g.latest, n.category, n.public_title, n.public_text "
                + "FROM app_groups g JOIN " + TABLE + " n ON n._id=(SELECT MAX(_id) FROM " + TABLE
                + " WHERE epoch=? AND pkg=g.pkg AND posted_at=g.latest) ORDER BY g.latest DESC, g.pkg ASC";
        try (Cursor cursor = db.rawQuery(query, new String[]{epochKey, Long.toString(since), beforeKey, beforeKey,
                afterPackage, Integer.toString(limit + 1), epochKey})) {
            while (cursor.moveToNext()) {
                if (rows.length() == limit) {
                    next = last;
                    break;
                }
                String packageName = cursor.getString(0);
                long time = cursor.getLong(2);
                JSONObject row = new JSONObject().put("id", packageName)
                        .put("title", FullAccessDataSources.appLabel(context, packageName))
                        .put("count", cursor.getInt(1)).put("time", time).put("detail", cursor.getString(3));
                addPreview(row, cursor.getString(4), cursor.getString(5));
                rows.put(row);
                last = time + ":" + packageName;
            }
        }
        return result(rows, since, next);
    }

    JSONObject group(Context context, long epoch, String packageName, String pageCursor, long since, long now,
            int limit) throws JSONException {
        requirePackage(packageName);
        long before = Long.MAX_VALUE;
        long beforeId = Long.MAX_VALUE;
        if (pageCursor != null && !pageCursor.isEmpty()) {
            int separator = pageCursor.indexOf(':');
            if (separator < 1 || separator == pageCursor.length() - 1)
                throw new IllegalArgumentException("Invalid notification cursor");
            before = FullAccessDataSources.cursor(pageCursor.substring(0, separator), Long.MAX_VALUE, Long.MAX_VALUE);
            beforeId = FullAccessDataSources.cursor(pageCursor.substring(separator + 1), Long.MAX_VALUE, Long.MAX_VALUE);
        }
        SQLiteDatabase db = readDatabase(epoch, now);
        JSONArray rows = new JSONArray();
        String beforeKey = Long.toString(before);
        String last = null;
        String next = null;
        try (Cursor cursor = db.query(TABLE, FIELDS,
                "epoch=? AND pkg=? AND posted_at>=? AND (posted_at<? OR (posted_at=? AND _id<?))",
                new String[]{Long.toString(epoch), packageName, Long.toString(since), beforeKey, beforeKey,
                        Long.toString(beforeId)}, null, null, "posted_at DESC, _id DESC", Integer.toString(limit + 1))) {
            while (cursor.moveToNext()) {
                if (rows.length() == limit) {
                    next = last;
                    break;
                }
                rows.put(item(context, cursor));
                last = cursor.getLong(3) + ":" + cursor.getLong(0);
            }
        }
        return result(rows, since, next);
    }

    private SQLiteDatabase readDatabase(long epoch, long now) {
        SQLiteDatabase db = getWritableDatabase();
        prune(db, now);
        db.delete(TABLE, "epoch<>?", new String[]{Long.toString(epoch)});
        return db;
    }

    private static JSONObject item(Context context, Cursor cursor) throws JSONException {
        JSONObject row = new JSONObject().put("id", Long.toString(cursor.getLong(0)))
                .put("title", FullAccessDataSources.appLabel(context, cursor.getString(1)))
                .put("detail", cursor.getString(2)).put("time", cursor.getLong(3));
        addPreview(row, cursor.getString(4), cursor.getString(5));
        return row;
    }

    private static void addPreview(JSONObject row, String title, String text) throws JSONException {
        if (!title.isEmpty()) row.put("notificationTitle", title);
        if (!text.isEmpty()) row.put("notificationText", text);
    }

    private static JSONObject result(JSONArray rows, long since, String next) throws JSONException {
        JSONObject result = FullAccessDataSources.response("ok", rows).put("since", since);
        if (next != null) result.put("nextCursor", next);
        return result;
    }

    private static String requirePackage(String packageName) {
        if (packageName == null || packageName.length() > 255 || !PACKAGE.matcher(packageName).matches())
            throw new IllegalArgumentException("Invalid notification package");
        return packageName;
    }

    private static void prune(SQLiteDatabase database, long now) {
        database.delete(TABLE, "posted_at<? OR posted_at>?",
                new String[]{Long.toString(now - RETENTION_MILLIS), Long.toString(now)});
    }
}
