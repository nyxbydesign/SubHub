package com.subhub.app.tasks;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.*;
import com.subhub.app.remote.RemoteProtocol;
import org.json.*;
import java.util.*;

/** A projection, its outgoing journal and its receive cursor commit in one SQLite transaction. */
public final class TaskStore extends SQLiteOpenHelper {
    public static final int PAGE_SIZE = 24;
    private static final Object LOCK = new Object();
    public TaskStore(Context context) { super(context, "subhub_tasks.db", null, 1); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE projections(scope TEXT PRIMARY KEY,body TEXT NOT NULL)");
        db.execSQL("CREATE TABLE journal(scope TEXT NOT NULL,seq INTEGER NOT NULL,body TEXT NOT NULL,PRIMARY KEY(scope,seq))");
        db.execSQL("CREATE TABLE receipts(scope TEXT NOT NULL,seq INTEGER NOT NULL,body TEXT NOT NULL,PRIMARY KEY(scope,seq))");
        db.execSQL("CREATE TABLE cursors(scope TEXT PRIMARY KEY,received INTEGER NOT NULL DEFAULT 0,acked INTEGER NOT NULL DEFAULT 0,offered INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE effects(scope TEXT NOT NULL,id TEXT NOT NULL,kind TEXT NOT NULL,body TEXT NOT NULL,done INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(scope,id,kind))");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported Tasks database version");
    }
    public TaskEngine load(String scope) throws JSONException {
        synchronized (LOCK) { return load(getReadableDatabase(), scope); }
    }
    private TaskEngine load(SQLiteDatabase db, String scope) throws JSONException {
        try (Cursor c = db.rawQuery("SELECT body FROM projections WHERE scope=?", new String[]{scope})) {
            return c.moveToFirst() ? TaskEngine.parse(new JSONObject(c.getString(0))) : new TaskEngine();
        }
    }
    public boolean append(String scope, TaskAction action, boolean actorDom, boolean notifyDom) throws JSONException {
        synchronized (LOCK) {
            SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
            try {
                TaskEngine engine = load(db, scope);
                if (!engine.apply(action, actorDom)) { db.setTransactionSuccessful(); return false; }
                long seq = 1;
                try (Cursor c = db.rawQuery("SELECT MAX(seq) FROM journal WHERE scope=?", new String[]{scope})) {
                    if (c.moveToFirst() && !c.isNull(0)) seq = Math.addExact(c.getLong(0), 1);
                }
                ContentValues row = new ContentValues(); row.put("scope", scope); row.put("seq", seq);
                row.put("body", action.json().toString()); db.insertOrThrow("journal", null, row);
                save(db, scope, engine); effects(db, scope, action, engine, notifyDom);
                db.setTransactionSuccessful(); return true;
            } finally { db.endTransaction(); }
        }
    }
    /** Strict sequence and retry-content checks apply before acknowledging a peer page. */
    public long receive(String scope, JSONArray rows, boolean actorDom, boolean notifyDom) throws JSONException {
        if (rows.length() > PAGE_SIZE) throw new JSONException("Too many task actions");
        synchronized (LOCK) {
            SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
            try {
                long through = cursor(db, scope, "received");
                TaskEngine engine = load(db, scope);
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.getJSONObject(i);
                    long seq = RemoteProtocol.integer(row, "seq");
                    String canonical = RemoteProtocol.canonical(row.getJSONObject("action"));
                    if (seq > 0 && seq <= through) {
                        try (Cursor c = db.rawQuery("SELECT body FROM receipts WHERE scope=? AND seq=?", new String[]{scope, Long.toString(seq)})) {
                            if (!c.moveToFirst() || !canonical.equals(c.getString(0))) throw new SecurityException("Conflicting task replay");
                        }
                        continue;
                    }
                    if (seq != through + 1) throw new SecurityException("Task sequence gap or unrequested replay");
                    TaskAction action = TaskAction.parse(row.getJSONObject("action"));
                    if (engine.apply(action, actorDom)) effects(db, scope, action, engine, notifyDom);
                    db.execSQL("INSERT INTO receipts(scope,seq,body) VALUES(?,?,?)", new Object[]{scope, seq, canonical});
                    through = seq;
                }
                save(db, scope, engine); ensureCursor(db, scope);
                db.execSQL("UPDATE cursors SET received=? WHERE scope=?", new Object[]{through, scope});
                db.setTransactionSuccessful(); return through;
            } finally { db.endTransaction(); }
        }
    }
    private static void save(SQLiteDatabase db, String scope, TaskEngine engine) throws JSONException {
        ContentValues row = new ContentValues(); row.put("scope", scope); row.put("body", engine.json().toString());
        db.insertWithOnConflict("projections", null, row, SQLiteDatabase.CONFLICT_REPLACE);
    }
    private static void effects(SQLiteDatabase db, String scope, TaskAction action, TaskEngine engine, boolean notifyDom) throws JSONException {
        if (notifyDom && (action.kind == TaskAction.Kind.SUBMIT || action.kind == TaskAction.Kind.OUTSIDE))
            effect(db, scope, action.id, "notification", action.json());
        if (action.date != null) {
            TaskEngine.Occurrence occurrence = engine.occurrence(action.taskId, action.date, action.at);
            if (occurrence.failed() && occurrence.task.failureTribute)
                effect(db, scope, occurrence.id, "failure", action.json());
        }
    }
    private static void effect(SQLiteDatabase db, String scope, String id, String kind, JSONObject body) {
        ContentValues row = new ContentValues(); row.put("scope", scope); row.put("id", id);
        row.put("kind", kind); row.put("body", body.toString());
        db.insertWithOnConflict("effects", null, row, SQLiteDatabase.CONFLICT_IGNORE);
    }
    public JSONArray outgoing(String scope, long remoteThrough) throws JSONException {
        synchronized (LOCK) {
            SQLiteDatabase db = getWritableDatabase();
            long max = 0;
            try (Cursor c = db.rawQuery("SELECT MAX(seq) FROM journal WHERE scope=?", new String[]{scope})) {
                if (c.moveToFirst() && !c.isNull(0)) max = c.getLong(0);
            }
            if (remoteThrough < cursor(db, scope, "acked") || remoteThrough > cursor(db, scope, "offered") || remoteThrough > max)
                throw new SecurityException("Invalid task receipt");
            ensureCursor(db, scope);
            db.execSQL("UPDATE cursors SET acked=? WHERE scope=?", new Object[]{remoteThrough, scope});
            JSONArray rows = new JSONArray(); long offered = remoteThrough;
            try (Cursor c = db.rawQuery("SELECT seq,body FROM journal WHERE scope=? AND seq>? ORDER BY seq LIMIT " + PAGE_SIZE,
                    new String[]{scope, Long.toString(remoteThrough)})) {
                while (c.moveToNext()) {
                    offered = c.getLong(0);
                    rows.put(new JSONObject().put("seq", offered).put("action", new JSONObject(c.getString(1))));
                    if (RemoteProtocol.canonical(rows).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 512 * 1024) {
                        rows.remove(rows.length() - 1);
                        offered = rows.length() == 0 ? remoteThrough : RemoteProtocol.integer(rows.getJSONObject(rows.length() - 1), "seq");
                        break;
                    }
                }
            }
            db.execSQL("UPDATE cursors SET offered=MAX(offered,?) WHERE scope=?", new Object[]{offered, scope});
            return rows;
        }
    }
    public long received(String scope) { synchronized (LOCK) { return cursor(getReadableDatabase(), scope, "received"); } }
    private static long cursor(SQLiteDatabase db, String scope, String field) {
        try (Cursor c = db.rawQuery("SELECT " + field + " FROM cursors WHERE scope=?", new String[]{scope})) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }
    private static void ensureCursor(SQLiteDatabase db, String scope) {
        db.execSQL("INSERT OR IGNORE INTO cursors(scope) VALUES(?)", new Object[]{scope});
    }
    public static final class Effect {
        public final String id, kind;
        public final TaskAction action;
        Effect(String id, String kind, TaskAction action) { this.id = id; this.kind = kind; this.action = action; }
    }
    public List<Effect> pending(String scope) throws JSONException {
        synchronized (LOCK) {
            List<Effect> result = new ArrayList<>();
            try (Cursor c = getReadableDatabase().rawQuery("SELECT id,kind,body FROM effects WHERE scope=? AND done=0 ORDER BY rowid", new String[]{scope})) {
                while (c.moveToNext()) result.add(new Effect(c.getString(0), c.getString(1), TaskAction.parse(new JSONObject(c.getString(2)))));
            }
            return result;
        }
    }
    public void done(String scope, Effect effect) {
        synchronized (LOCK) {
            getWritableDatabase().execSQL("UPDATE effects SET done=1 WHERE scope=? AND id=? AND kind=?",
                    new Object[]{scope, effect.id, effect.kind});
        }
    }
}
