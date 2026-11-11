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
    private final Object lock;
    private final InternalTaskDatabase internalDatabase;
    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> observers =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.concurrent.atomic.AtomicBoolean notification = new java.util.concurrent.atomic.AtomicBoolean();
    public TaskStore(Context context) { super(context, "subhub_tasks.db", null, 1); lock = LOCK; internalDatabase = null; }

    TaskStore(Context physical, com.subhub.app.internal.InternalStorageScope internal,
            android.content.SharedPreferences evidence, Runnable initialAdmission) throws java.io.IOException {
        super(physical, null, null, 1);
        lock = new Object();
        internalDatabase = InternalTaskDatabase.open(internal, evidence, initialAdmission, this::onCreate);
    }
    @Override public SQLiteDatabase getWritableDatabase() {
        return internalDatabase == null ? super.getWritableDatabase() : internalDatabase.database();
    }
    @Override public SQLiteDatabase getReadableDatabase() {
        return internalDatabase == null ? super.getReadableDatabase() : internalDatabase.database();
    }
    @Override public synchronized void close() {
        if (internalDatabase == null) super.close(); else internalDatabase.close();
    }

    interface Observation extends AutoCloseable { @Override void close(); }

    Observation observe(Runnable observer) {
        observers.add(Objects.requireNonNull(observer));
        return () -> observers.remove(observer);
    }

    /** Coalesce committed ledger, peer status and attachment changes into one UI invalidation. */
    void changed() {
        if (observers.isEmpty() || !notification.compareAndSet(false, true)) return;
        main.post(() -> {
            notification.set(false);
            for (Runnable observer : observers) observer.run();
        });
    }
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
        synchronized (lock) { return load(getReadableDatabase(), scope); }
    }
    private TaskEngine load(SQLiteDatabase db, String scope) throws JSONException {
        try (Cursor c = db.rawQuery("SELECT body FROM projections WHERE scope=?", new String[]{scope})) {
            if (c.moveToFirst()) return TaskEngine.parse(new JSONObject(c.getString(0)));
        }
        if (internalDatabase != null) requireUnusedInternalScope(db, scope);
        return new TaskEngine();
    }
    private static void requireUnusedInternalScope(SQLiteDatabase db, String scope) {
        // A missing owned projection is only an empty initial state when no durable history
        // or cursor progress exists. Never rebuild it from a later CREATE and lose prior tasks.
        try (Cursor rows = db.rawQuery("SELECT 1 FROM journal WHERE scope=? "
                + "UNION ALL SELECT 1 FROM receipts WHERE scope=? "
                + "UNION ALL SELECT 1 FROM effects WHERE scope=? "
                + "UNION ALL SELECT 1 FROM cursors WHERE scope=? AND (received<>0 OR acked<>0 OR offered<>0) LIMIT 1",
                new String[]{scope, scope, scope, scope})) {
            if (rows.moveToFirst()) throw new SQLiteDatabaseCorruptException("Internal Tasks projection is missing");
        }
    }
    public boolean append(String scope, TaskAction action, boolean actorDom, boolean notifyDom) throws JSONException {
        synchronized (lock) {
            SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
            boolean committed = false;
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
                db.setTransactionSuccessful(); committed = true; return true;
            } finally {
                db.endTransaction();
                if (committed) changed();
            }
        }
    }
    /** Strict sequence and retry-content checks apply before acknowledging a peer page. */
    public long receive(String scope, JSONArray rows, boolean actorDom, boolean notifyDom) throws JSONException {
        if (rows.length() > PAGE_SIZE) throw new JSONException("Too many task actions");
        synchronized (lock) {
            SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
            boolean committed = false;
            try {
                long through = cursor(db, scope, "received");
                long previous = through;
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
                db.setTransactionSuccessful(); committed = through != previous; return through;
            } finally {
                db.endTransaction();
                if (committed) changed();
            }
        }
    }
    private static void save(SQLiteDatabase db, String scope, TaskEngine engine) throws JSONException {
        ContentValues row = new ContentValues(); row.put("scope", scope); row.put("body", engine.json().toString());
        db.insertWithOnConflict("projections", null, row, SQLiteDatabase.CONFLICT_REPLACE);
    }
    private static void effects(SQLiteDatabase db, String scope, TaskAction action, TaskEngine engine, boolean notifyDom) throws JSONException {
        if (action.cancellation()) {
            List<String> retired = new ArrayList<>();
            try (Cursor rows = db.rawQuery("SELECT id,body FROM effects WHERE scope=? AND done=0", new String[]{scope})) {
                while (rows.moveToNext()) {
                    TaskAction pending = TaskAction.parse(new JSONObject(rows.getString(1)));
                    if (engine.cancelled(pending.taskId, pending.date)) retired.add(rows.getString(0));
                }
            }
            for (String id : retired) db.execSQL("UPDATE effects SET done=1 WHERE scope=? AND id=?", new Object[]{scope, id});
        }
        if (notifyDom && (action.kind == TaskAction.Kind.SUBMIT || action.kind == TaskAction.Kind.OUTSIDE)) {
            TaskEngine.Occurrence current = engine.occurrence(action.taskId, action.date, action.at);
            if (current.status == TaskEngine.Status.PENDING && current.submission != null && current.submission.id.equals(action.id))
                effect(db, scope, action.id, "notification", action.json());
        }
        if (!notifyDom && (action.kind == TaskAction.Kind.CREATE || action.cancellation()
                || action.kind == TaskAction.Kind.ACCEPT || action.kind == TaskAction.Kind.DENY
                || action.kind == TaskAction.Kind.TIMEOUT && engine.occurrence(action.taskId, action.date, action.at).status == TaskEngine.Status.TIMED_OUT))
            effect(db, scope, action.id, "sub_notification", action.json());
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
    /** A started occurrence has one durable local effect, independent of refreshes and repeated sweeps. */
    public void started(String scope, TaskEngine.Occurrence row, long now) throws JSONException {
        synchronized (lock) {
            if (row.status != TaskEngine.Status.ACTIVE || now >= row.task.end(row.date).toEpochMilli()) return;
            TaskAction marker = new TaskAction("start_" + UUID.nameUUIDFromBytes(row.id.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    row.task.id, TaskAction.Kind.TIMEOUT, row.date, now, "", "", Collections.emptyList(), null);
            effect(getWritableDatabase(), scope, row.id, "sub_started", marker.json());
        }
    }
    public JSONArray outgoing(String scope, long remoteThrough) throws JSONException {
        synchronized (lock) {
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
    public long received(String scope) { synchronized (lock) { return cursor(getReadableDatabase(), scope, "received"); } }
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
    public List<Effect> pending(String scope) throws JSONException { return pending(scope, false); }
    public List<Effect> pendingNotices(String scope) throws JSONException { return pending(scope, true); }
    private List<Effect> pending(String scope, boolean notices) throws JSONException {
        synchronized (lock) {
            List<Effect> result = new ArrayList<>();
            try (Cursor c = getReadableDatabase().rawQuery("SELECT id,kind,body FROM effects WHERE scope=? AND done=0 AND kind " + (notices ? "LIKE" : "NOT LIKE") + " 'sub_%' ORDER BY rowid", new String[]{scope})) {
                while (c.moveToNext()) result.add(new Effect(c.getString(0), c.getString(1), TaskAction.parse(new JSONObject(c.getString(2)))));
            }
            return result;
        }
    }
    public void done(String scope, Effect effect) {
        synchronized (lock) {
            getWritableDatabase().execSQL("UPDATE effects SET done=1 WHERE scope=? AND id=? AND kind=?",
                    new Object[]{scope, effect.id, effect.kind});
        }
    }
}
