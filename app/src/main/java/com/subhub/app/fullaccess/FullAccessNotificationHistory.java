package com.subhub.app.fullaccess;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.io.File;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Local-only notification history with bounded public previews. */
final class FullAccessNotificationHistory extends SQLiteOpenHelper {
    static final long RETENTION_MILLIS = 7 * 24 * 60 * 60_000L;
    private static final int MAX_ROWS = 5000;
    private static final String TABLE = "posted_notifications";
    private static final String FILE = "full-access-notifications.db";

    FullAccessNotificationHistory(Context context) {
        super(context, path(context).getAbsolutePath(), null, 2);
        setWriteAheadLoggingEnabled(false);
    }

    private static File path(Context context) {
        return new File(context.getNoBackupFilesDir(), FILE);
    }

    static void clear(Context context) {
        File file = path(context);
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
        SQLiteDatabase db = getWritableDatabase();
        prune(db, now);
        db.delete(TABLE, "epoch<>?", new String[]{Long.toString(epoch)});
        JSONArray rows = new JSONArray();
        long last = -1;
        String next = null;
        try (Cursor cursor = db.query(TABLE, new String[]{"_id", "pkg", "category", "posted_at", "public_title", "public_text"},
                "epoch=? AND _id<? AND posted_at>=?",
                new String[]{Long.toString(epoch), Long.toString(beforeId), Long.toString(since)}, null, null,
                "_id DESC", Integer.toString(limit + 1))) {
            while (cursor.moveToNext()) {
                if (rows.length() == limit) {
                    next = Long.toString(last);
                    break;
                }
                last = cursor.getLong(0);
                String packageName = cursor.getString(1);
                JSONObject row = new JSONObject().put("id", Long.toString(last))
                        .put("title", FullAccessDataSources.appLabel(context, packageName))
                        .put("detail", cursor.getString(2)).put("time", cursor.getLong(3));
                String title = cursor.getString(4);
                String text = cursor.getString(5);
                if (!title.isEmpty()) row.put("notificationTitle", title);
                if (!text.isEmpty()) row.put("notificationText", text);
                rows.put(row);
            }
        }
        JSONObject result = FullAccessDataSources.response("ok", rows).put("since", since);
        if (next != null) result.put("nextCursor", next);
        return result;
    }

    private static void prune(SQLiteDatabase database, long now) {
        database.delete(TABLE, "posted_at<? OR posted_at>?",
                new String[]{Long.toString(now - RETENTION_MILLIS), Long.toString(now)});
    }
}
