package com.subhub.app.capture.export;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import com.subhub.app.capture.CustomImageManager;
import org.json.JSONObject;
import java.io.*;
import java.util.*;

/** Durable queue. Completion is recorded before offering optional original deletion. */
public final class ExportJobStore extends SQLiteOpenHelper {
    public enum State { DRAFT, PENDING, RUNNING, SAVED, FAILED, CANCELLED, INTERRUPTED }
    private static boolean processRecovered;
    public static final class Item {
        public final long id;
        public final String job, source, output, message;
        public final State state;
        public final int progress;
        Item(Cursor c) {
            id = c.getLong(0); job = c.getString(1); source = c.getString(2); state = State.valueOf(c.getString(3));
            output = c.getString(4); message = c.getString(5); progress = c.getInt(6);
        }
    }
    private final Context context;
    public ExportJobStore(Context context) {
        super(context.getApplicationContext(), "export_jobs.db", null, 1);
        this.context = context.getApplicationContext();
        setWriteAheadLoggingEnabled(true);
    }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE jobs(id TEXT PRIMARY KEY, created INTEGER NOT NULL, config TEXT NOT NULL, quality TEXT NOT NULL, detect_every INTEGER NOT NULL, mute INTEGER NOT NULL, delete_originals INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE items(id INTEGER PRIMARY KEY AUTOINCREMENT, job TEXT NOT NULL, source TEXT NOT NULL, state TEXT NOT NULL, output TEXT, message TEXT NOT NULL DEFAULT '', progress INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX items_job ON items(job,id)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { throw new IllegalStateException("Unsupported export schema"); }
    public String create(List<Uri> sources, JSONObject snapshot, ExportOptions options) throws IOException {
        return create(sources, snapshot, options, State.PENDING);
    }
    public String createDraft(List<Uri> sources, JSONObject snapshot, ExportOptions options) throws IOException {
        return create(sources, snapshot, options, State.DRAFT);
    }
    private String create(List<Uri> sources, JSONObject snapshot, ExportOptions options, State initial) throws IOException {
        if (sources.isEmpty()) throw new IllegalArgumentException("Select media first");
        String id = UUID.randomUUID().toString(); File assets = new File(directory(id), "assets");
        if (!assets.mkdirs()) throw new IOException("Cannot prepare export storage");
        int count = 0;
        try {
            for (File input : new CustomImageManager(context).enabledFilesForPackExport()) {
                if (++count > 64) break;
                try (InputStream from = new FileInputStream(input);
                     OutputStream to = new FileOutputStream(new File(assets, count + ".img"))) {
                    byte[] buffer = new byte[65536]; int size;
                    while ((size = from.read(buffer)) != -1) to.write(buffer, 0, size);
                }
            }
            SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
            try {
                ContentValues job = new ContentValues(); job.put("id", id); job.put("created", System.currentTimeMillis());
                job.put("config", snapshot.toString()); job.put("quality", options.quality.name());
                job.put("detect_every", options.detectEvery); job.put("mute", options.mute ? 1 : 0);
                job.put("delete_originals", options.deleteOriginals ? 1 : 0); db.insertOrThrow("jobs", null, job);
                for (Uri source : sources) {
                    ContentValues item = new ContentValues(); item.put("job", id); item.put("source", source.toString());
                    item.put("state", initial.name()); db.insertOrThrow("items", null, item);
                }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
            return id;
        } catch (RuntimeException | IOException error) { removeFiles(directory(id)); throw error; }
    }
    public File directory(String job) {
        UUID.fromString(job);
        return new File(new File(context.getFilesDir(), "exports"), job);
    }
    public String latest() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id FROM jobs ORDER BY created DESC LIMIT 1", null)) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }
    public List<Item> items(String job) {
        List<Item> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,job,source,state,output,message,progress FROM items WHERE job=? ORDER BY id", new String[] {job})) {
            while (c.moveToNext()) result.add(new Item(c));
        }
        return Collections.unmodifiableList(result);
    }
    public JSONObject snapshot(String job) throws Exception {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT config FROM jobs WHERE id=?", new String[] {job})) {
            if (!c.moveToFirst()) throw new IOException("Export job unavailable"); return new JSONObject(c.getString(0));
        }
    }
    public ExportOptions options(String job) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT quality,detect_every,mute,delete_originals FROM jobs WHERE id=?", new String[] {job})) {
            if (!c.moveToFirst()) throw new IllegalArgumentException("Export job unavailable");
            return new ExportOptions(ExportOptions.Quality.valueOf(c.getString(0)), c.getInt(1), c.getInt(2) == 1, c.getInt(3) == 1);
        }
    }
    public void update(long id, State state, Uri output, String message, int progress) {
        ContentValues v = new ContentValues(); v.put("state", state.name());
        v.put("output", output == null ? null : output.toString()); v.put("message", message);
        v.put("progress", Math.max(0, Math.min(100, progress)));
        getWritableDatabase().update("items", v, "id=?", new String[] {Long.toString(id)});
    }
    public void progress(long id, int percent) {
        ContentValues v = new ContentValues(); v.put("progress", Math.max(0, Math.min(99, percent)));
        getWritableDatabase().update("items", v, "id=? AND state='RUNNING'", new String[] {Long.toString(id)});
    }
    /** Called once per new app process, never while an export worker is alive. */
    public void recover() {
        List<Item> interrupted = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,job,source,state,output,message,progress FROM items WHERE state IN ('RUNNING','PENDING')", null)) {
            while (c.moveToNext()) interrupted.add(new Item(c));
        }
        for (Item item : interrupted) {
            boolean published = false;
            if (item.output != null) {
                Uri uri = Uri.parse(item.output);
                try {
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        try (Cursor c = context.getContentResolver().query(uri,
                                new String[] {android.provider.MediaStore.MediaColumns.IS_PENDING, android.provider.MediaStore.MediaColumns.SIZE}, null, null, null)) {
                            published = c != null && c.moveToFirst() && c.getInt(0) == 0 && c.getLong(1) > 0;
                        }
                    } else try (android.os.ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "r")) {
                        published = fd != null && fd.getStatSize() > 0;
                    }
                    if (!published) context.getContentResolver().delete(uri, null, null);
                } catch (IOException | RuntimeException ignored) { /* Retry keeps the original. */ }
            }
            update(item.id, published ? State.SAVED : State.INTERRUPTED,
                    published ? Uri.parse(item.output) : null,
                    published ? "Saved" : "Export interrupted. Retry to continue.", published ? 100 : 0);
            File partial = new File(directory(item.job), item.id + ".partial");
            if (partial.exists() && !partial.delete()) partial.deleteOnExit();
        }
    }
    public static synchronized void recoverProcess(Context context) {
        if (processRecovered) return;
        try (ExportJobStore store = new ExportJobStore(context)) { store.recover(); }
        processRecovered = true;
    }
    public void enqueue(String job) {
        getWritableDatabase().execSQL("UPDATE items SET state='PENDING', message='' WHERE job=? AND state='DRAFT'", new Object[] {job});
    }
    public void discardDraft(String job) {
        SQLiteDatabase db = getWritableDatabase();
        try (Cursor c = db.rawQuery("SELECT count(*) FROM items WHERE job=? AND state!='DRAFT'", new String[] {job})) {
            if (!c.moveToFirst() || c.getInt(0) != 0) return;
        }
        db.beginTransaction();
        try { db.delete("items", "job=?", new String[] {job}); db.delete("jobs", "id=?", new String[] {job}); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
        removeFiles(directory(job));
    }
    public void retry(String job) {
        getWritableDatabase().execSQL("UPDATE items SET state='PENDING', message='', progress=0 WHERE job=? AND state IN ('FAILED','CANCELLED','INTERRUPTED')", new Object[] {job});
    }
    public void cancelPending(String job) {
        getWritableDatabase().execSQL("UPDATE items SET state='CANCELLED', message='Cancelled' WHERE job=? AND state='PENDING'", new Object[] {job});
    }
    private static void removeFiles(File root) {
        File[] files = root.listFiles(); if (files != null) for (File file : files) {
            if (file.isDirectory()) removeFiles(file); else if (!file.delete()) file.deleteOnExit();
        }
        if (!root.delete()) root.deleteOnExit();
    }
}
