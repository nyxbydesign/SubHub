package com.subhub.app.tasks;

import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabaseCorruptException;
import com.subhub.app.internal.InternalStorageScope;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Explicit owned database provisioning. Established missing/corrupt data is never recreated or deleted. */
final class InternalTaskDatabase implements AutoCloseable {
    static final String REGISTRY = "subhub_internal_tasks_registry";
    static final int VERSION = 2;
    static final String FAILURE_INDEX = "internal_pending_failure";
    static final String NOTICE_INDEX = "internal_pending_notice";
    private static final Set<String> UNCERTAIN = new HashSet<>();
    private static final Set<String> TABLES = Set.of("projections", "journal", "receipts", "cursors", "effects");
    private final File file;
    private final Object fileIdentity;
    private final SQLiteDatabase database;
    private final AtomicBoolean failed;

    static String evidenceKey(File file) {
        return "tasks:" + UUID.nameUUIDFromBytes(file.getAbsolutePath().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    static InternalTaskDatabase open(InternalStorageScope scope, SharedPreferences registry,
            Runnable initialAdmission, Consumer<SQLiteDatabase> createSchema) throws IOException {
        File file = scope.file(InternalStorageScope.Area.TASKS, "tasks.db");
        String key = evidenceKey(file);
        synchronized (UNCERTAIN) {
            if (UNCERTAIN.contains(key)) throw new IOException("Internal Tasks provisioning outcome is unavailable");
        }
        Object evidence = registry.getAll().get(key);
        boolean create = evidence == null;
        if (!create && !"ready".equals(evidence)) throw new IOException("Internal Tasks provisioning needs recovery");
        if (create) {
            if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Internal Tasks database has no provisioning acknowledgement");
            initialAdmission.run();
            synchronized (UNCERTAIN) {
                if (!UNCERTAIN.add(key)) throw new IOException("Internal Tasks provisioning is already pending");
            }
            if (!registry.edit().putString(key, "started").commit())
                throw new IOException("Internal Tasks provisioning was not acknowledged");
            Files.createFile(file.toPath()); // Atomic new-file claim; never truncate an existing database.
        }
        requireFile(file);
        AtomicBoolean failed = new AtomicBoolean();
        SQLiteDatabase db = null;
        try {
            // No CREATE_IF_NECESSARY, including during initial setup after our explicit file claim.
            // No platform corruption recovery/deletion and no implicit locale metadata migration.
            db = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.NO_LOCALIZED_COLLATORS, corrupt -> {
                        failed.set(true);
                        throw new SQLiteDatabaseCorruptException("Internal Tasks corruption requires explicit recovery");
                    });
            if (create) {
                db.beginTransaction();
                try { createSchema.accept(db); installIndexes(db); db.setVersion(VERSION); db.setTransactionSuccessful(); }
                finally { db.endTransaction(); }
            }
            validateBase(db);
            if(db.getVersion()==1) {
                // Only the existing admitted task/editor command may upgrade an owned database.
                // Never invoke its authority callback while holding a SQLite transaction/lock.
                initialAdmission.run();
                db.beginTransaction();
                try { installIndexes(db); db.setVersion(VERSION); db.setTransactionSuccessful(); }
                finally { db.endTransaction(); }
                // SQLite commits both indexes and user_version atomically. Verify the actual
                // committed outcome and seal it before exposing this owned handle.
                validateIndexes(db);
                try (FileChannel record = FileChannel.open(file.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { record.force(true); }
                initialAdmission.run();
            }
            validateIndexes(db);
            if (create) {
                try (FileChannel record = FileChannel.open(file.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { record.force(true); }
                try (FileChannel directory = FileChannel.open(file.getParentFile().toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) { directory.force(true); }
                try (FileChannel parent = FileChannel.open(file.getParentFile().getParentFile().toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) { parent.force(true); }
                if (!registry.edit().putString(key, "ready").commit())
                    throw new IOException("Internal Tasks creation was not acknowledged");
                synchronized (UNCERTAIN) { UNCERTAIN.remove(key); }
            }
            return new InternalTaskDatabase(file, db, failed);
        } catch (IOException | RuntimeException problem) {
            if (db != null) db.close(); // Close only. Never delete, rename, truncate or invoke recovery.
            throw problem;
        }
    }
    private InternalTaskDatabase(File file, SQLiteDatabase database, AtomicBoolean failed) throws IOException {
        this.file = file; this.database = database; this.failed = failed;
        fileIdentity = requireFile(file).fileKey();
    }
    SQLiteDatabase database() {
        try {
            if (failed.get() || !database.isOpen()) throw new IOException("Internal Tasks database unavailable");
            Object current = requireFile(file).fileKey();
            if (fileIdentity != null && !fileIdentity.equals(current)) throw new IOException("Internal Tasks database was replaced");
            return database;
        } catch (IOException unavailable) {
            failed.set(true); throw new IllegalStateException("Internal Tasks database unavailable", unavailable);
        }
    }
    private static BasicFileAttributes requireFile(File file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) throw new IOException("Internal Tasks database missing or invalid");
        return attributes;
    }
    private static void validateBase(SQLiteDatabase db) throws IOException {
        if (db.getVersion() != 1 && db.getVersion() != VERSION) throw new IOException("Internal Tasks schema is unavailable");
        try (Cursor rows = db.rawQuery("PRAGMA quick_check", null)) {
            if (!rows.moveToFirst() || !"ok".equals(rows.getString(0)) || rows.moveToNext())
                throw new IOException("Internal Tasks database is corrupt");
        }
        Set<String> tables = new HashSet<>();
        try (Cursor rows = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)) {
            while (rows.moveToNext()) tables.add(rows.getString(0));
        }
        if (!tables.containsAll(TABLES)) throw new IOException("Internal Tasks tables are missing");
    }
    private static String indexSql(String name) {
        return "CREATE INDEX "+name+" ON effects(scope) WHERE done=0 AND kind"
                +(FAILURE_INDEX.equals(name)?"=":"<>")+"'failure'";
    }
    private static String definition(SQLiteDatabase db,String name) throws IOException {
        try(Cursor rows=db.rawQuery("SELECT type,tbl_name,sql FROM sqlite_master WHERE name=?",new String[]{name})) {
            if(!rows.moveToFirst())return null;
            if(!"index".equals(rows.getString(0))||!"effects".equals(rows.getString(1)))
                throw new IOException("Internal Tasks index identity is unavailable");
            String sql=rows.getString(2);
            if(rows.moveToNext()||!indexSql(name).equals(sql))throw new IOException("Internal Tasks index definition is unavailable");
            return sql;
        }
    }
    private static void installIndexes(SQLiteDatabase db) throws IOException {
        // Check both existing names before creating either. Matching partial legacy upgrades are
        // idempotent; mismatched definitions are never dropped or silently replaced.
        String failure=definition(db,FAILURE_INDEX),notice=definition(db,NOTICE_INDEX);
        if(failure==null)db.execSQL(indexSql(FAILURE_INDEX));
        if(notice==null)db.execSQL(indexSql(NOTICE_INDEX));
        validateIndex(db,FAILURE_INDEX);validateIndex(db,NOTICE_INDEX);
    }
    private static void validateIndexes(SQLiteDatabase db) throws IOException {
        if(db.getVersion()!=VERSION)throw new IOException("Internal Tasks index upgrade was not acknowledged");
        validateIndex(db,FAILURE_INDEX);validateIndex(db,NOTICE_INDEX);
    }
    private static void validateIndex(SQLiteDatabase db,String name) throws IOException {
        if(definition(db,name)==null)throw new IOException("Internal Tasks pending index is missing");
        boolean found=false;
        try(Cursor rows=db.rawQuery("PRAGMA index_list('effects')",null)) {
            while(rows.moveToNext())if(name.equals(rows.getString(1))) {
                if(found||rows.getInt(2)!=0||!"c".equals(rows.getString(3))||rows.getInt(4)!=1)
                    throw new IOException("Internal Tasks pending index properties are unavailable");
                found=true;
            }
        }
        if(!found)throw new IOException("Internal Tasks pending index is unavailable");
        try(Cursor rows=db.rawQuery("PRAGMA index_xinfo('"+name+"')",null)) {
            if(!rows.moveToNext()||rows.getInt(0)!=0||rows.getInt(1)!=0||!"scope".equals(rows.getString(2))
                    ||rows.getInt(3)!=0||!"BINARY".equals(rows.getString(4))||rows.getInt(5)!=1
                    ||!rows.moveToNext()||rows.getInt(0)!=1||rows.getInt(1)!=-1||!rows.isNull(2)
                    ||rows.getInt(3)!=0||!"BINARY".equals(rows.getString(4))||rows.getInt(5)!=0||rows.moveToNext())
                throw new IOException("Internal Tasks pending index columns are unavailable");
        }
    }
    @Override public void close() { database.close(); }
}
