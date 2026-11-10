package com.subhub.app.runtime;

import android.app.Application;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabaseCorruptException;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;

/**
 * Unconnected physical-owner journal. Ordinary opens never create a database or provisioning
 * marker. Only an explicit trusted first-install/migration decision may call provisionNewJournal;
 * missing evidence or a failed open is NEVER permission to provision. Existing legacy runtime
 * reconciliation is a separate prerequisite before that future caller may admit a session.
 */
public final class SQLiteRuntimeOwnerBackend implements RuntimeOwnerStore.Backend, AutoCloseable {
    private static final String DATABASE = "subhub_runtime_owner.db";
    private static final String TABLE = "physical_owner";
    private static final String OWNER_SCHEMA = "CREATE TABLE physical_owner (slot INTEGER PRIMARY KEY CHECK(slot=1), "
            + "revision INTEGER NOT NULL CHECK(revision>=0), body BLOB NOT NULL)";
    private static final String JOURNAL_SCHEMA = "CREATE TABLE owner_journal (slot INTEGER PRIMARY KEY CHECK(slot=1), "
            + "identity TEXT NOT NULL)";
    private static final String PLATFORM_SCHEMA = "CREATE TABLE android_metadata (locale TEXT)";
    private static final String MARKER_MAGIC = "SUBHUB_OWNER_V1";
    private static final Object IO_LOCK = new Object();
    private static final DatabaseErrorHandler PRESERVE_CORRUPTION = database -> {
        // Android's default handler deletes the file. Losing owner evidence must never look fresh.
        throw new SQLiteDatabaseCorruptException("Runtime owner corruption requires reconciliation; database preserved");
    };

    private final File databaseFile, markerFile;
    private boolean closed;

    public SQLiteRuntimeOwnerBackend(Context context) { this(context, DATABASE); }
    SQLiteRuntimeOwnerBackend(Context context, String database) {
        Context application = physicalApplication(context);
        validateName(database);
        databaseFile = application.getDatabasePath(database);
        markerFile = markerFile(application, database);
    }

    /**
     * Explicit first provisioning, not an open/recovery fallback. A partial attempt is permanently
     * fenced until a separate evidence-based reconciliation exists. No failure deletes its files.
     * If all installation evidence has been erased, only the caller's independent fresh-install
     * decision can distinguish that from a genuinely new installation; this method makes no such claim.
     */
    public static SQLiteRuntimeOwnerBackend provisionNewJournal(Context context) {
        return provisionNewJournal(context, DATABASE);
    }
    static SQLiteRuntimeOwnerBackend provisionNewJournal(Context context, String database) {
        SQLiteRuntimeOwnerBackend backend = new SQLiteRuntimeOwnerBackend(context, database);
        synchronized (IO_LOCK) {
            backend.requireNoDatabaseEvidence();
            String identity = UUID.randomUUID().toString();
            // CREATE_NEW is the one-use provisioning claim across processes. The retained marker
            // is also the lock inode for every later operation; ordinary operations never create it.
            try (FileChannel marker = FileChannel.open(backend.markerFile.toPath(),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE);
                    FileLock ignored = marker.lock()) {
                if (!ignored.isValid()) throw new IllegalStateException("Lost owner provisioning lock");
                writeMarker(marker, "PROVISIONING", identity);
                syncDirectory(backend.markerFile.getParentFile());
                syncDirectory(backend.markerFile.getParentFile().getParentFile());
                backend.requireNoDatabaseEvidence();
                ensureDirectory(backend.databaseFile.getParentFile());
                // Claim the empty file exclusively too. Even provisioning does not use SQLite's
                // CREATE_IF_NECESSARY or SQLiteOpenHelper's destructive corruption/create fallback.
                try (FileChannel created = FileChannel.open(backend.databaseFile.toPath(),
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) { created.force(true); }
                syncDirectory(backend.databaseFile.getParentFile());
                try (SQLiteDatabase db = backend.openExistingDatabase()) {
                    if (db.getVersion() != 0) throw new IllegalStateException("Unexpected owner provisioning database");
                    db.beginTransaction();
                    try {
                        db.execSQL(OWNER_SCHEMA); db.execSQL(JOURNAL_SCHEMA);
                        ContentValues journal = new ContentValues(); journal.put("slot", 1); journal.put("identity", identity);
                        db.insertOrThrow("owner_journal", null, journal);
                        ContentValues row = values(RuntimeOwnerStore.Record.idle(0)); row.put("slot", 1);
                        db.insertOrThrow(TABLE, null, row); db.setVersion(1);
                        db.setTransactionSuccessful();
                    } finally { db.endTransaction(); }
                    validateDatabase(db, identity);
                    if (!RuntimeOwnerStore.Record.idle(0).equals(read(db)))
                        throw new IllegalStateException("Unacknowledged owner provisioning");
                }
                try (FileChannel committed = FileChannel.open(backend.databaseFile.toPath(), StandardOpenOption.WRITE)) {
                    committed.force(true);
                }
                syncDirectory(backend.databaseFile.getParentFile());
                writeMarker(marker, "READY", identity);
                syncDirectory(backend.markerFile.getParentFile());
            } catch (IOException failed) {
                throw new IllegalStateException("Owner journal provisioning was not acknowledged", failed);
            }
        }
        return backend;
    }

    private static Context physicalApplication(Context context) {
        Context application = Objects.requireNonNull(context).getApplicationContext();
        if (!(application instanceof Application))
            throw new IllegalArgumentException("Physical runtime owner requires the application context");
        return application;
    }
    private static void validateName(String database) {
        if (database == null || !database.matches("[A-Za-z0-9_-]+\\.db"))
            throw new IllegalArgumentException("Invalid owner database name");
    }
    static File markerFile(Context context, String database) {
        validateName(database);
        // Excluded from Android backup: a restored database alone cannot revive physical ownership.
        return new File(physicalApplication(context).getNoBackupFilesDir(), database + ".owner");
    }

    @Override public RuntimeOwnerStore.Record read() { return withJournal(SQLiteRuntimeOwnerBackend::read); }

    @Override public RuntimeOwnerStore.Outcome compareAndSet(RuntimeOwnerStore.Record expected,
            RuntimeOwnerStore.Record next) {
        Objects.requireNonNull(expected); Objects.requireNonNull(next);
        if (next.revision != Math.addExact(expected.revision, 1))
            throw new IllegalArgumentException("Owner CAS must advance exactly one revision");
        ContentValues replacement = values(next);
        return withJournal(db -> {
            db.beginTransaction();
            RuntimeOwnerStore.Outcome outcome = RuntimeOwnerStore.Outcome.UNCERTAIN;
            try {
                if (!expected.equals(read(db))) outcome = RuntimeOwnerStore.Outcome.CONFLICT;
                else {
                    int rows = db.update(TABLE, replacement, "slot=1 AND revision=?",
                            new String[]{Long.toString(expected.revision)});
                    if (rows != 1) throw new IllegalStateException("Runtime owner row disappeared");
                    db.setTransactionSuccessful(); outcome = RuntimeOwnerStore.Outcome.COMMITTED;
                }
            } catch (RuntimeException failed) {
                outcome = RuntimeOwnerStore.Outcome.UNCERTAIN;
            } finally {
                try { db.endTransaction(); }
                catch (RuntimeException uncertain) { outcome = RuntimeOwnerStore.Outcome.UNCERTAIN; }
            }
            return outcome;
        });
    }

    private interface Operation<T> { T run(SQLiteDatabase database); }
    private <T> T withJournal(Operation<T> operation) {
        synchronized (IO_LOCK) {
            if (closed) throw new IllegalStateException("Owner journal is closed");
            // Opening afresh avoids retaining an unlinked SQLite inode after external file loss.
            // The process monitor avoids overlapping Java file locks; the file lock serializes processes.
            try (FileChannel marker = FileChannel.open(markerFile.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE);
                    FileLock ignored = marker.lock()) {
                if (!ignored.isValid()) throw new IllegalStateException("Lost owner journal lock");
                String identity = readReadyMarker(marker);
                if (!databaseFile.isFile() || databaseFile.length() == 0)
                    throw new IllegalStateException("Previously provisioned owner database is missing");
                try (SQLiteDatabase db = openExistingDatabase()) {
                    validateDatabase(db, identity);
                    return operation.run(db);
                }
            } catch (IOException failed) {
                throw new IllegalStateException("Owner journal evidence is unavailable", failed);
            }
        }
    }
    private SQLiteDatabase openExistingDatabase() {
        SQLiteDatabase db = SQLiteDatabase.openDatabase(databaseFile.getPath(), null,
                SQLiteDatabase.OPEN_READWRITE, PRESERVE_CORRUPTION);
        try {
            // Disable platform compatibility-WAL defaults too: the provisioning receipt must not
            // depend on an unacknowledged sidecar/checkpoint. Ordinary commits use a full-sync journal.
            try (Cursor mode = db.rawQuery("PRAGMA journal_mode=DELETE", null)) {
                if (!mode.moveToFirst() || !"delete".equalsIgnoreCase(mode.getString(0)))
                    throw new IllegalStateException("Owner database requires rollback-journal mode");
            }
            db.execSQL("PRAGMA synchronous=FULL");
            return db;
        } catch (RuntimeException failed) { db.close(); throw failed; }
    }
    @Override public void close() { synchronized (IO_LOCK) { closed = true; } }

    private void requireNoDatabaseEvidence() {
        for (String suffix : new String[]{"", "-journal", "-wal", "-shm"}) {
            if (new File(databaseFile.getPath() + suffix).exists())
                throw new IllegalStateException("Existing owner database evidence forbids provisioning");
        }
    }
    private static void ensureDirectory(File directory) throws IOException {
        if (directory.isDirectory()) return;
        if (!directory.mkdir() && !directory.isDirectory()) throw new IOException("Cannot create owner database directory");
        syncDirectory(directory.getParentFile());
    }
    private static void syncDirectory(File directory) throws IOException {
        FileDescriptor descriptor = null;
        try {
            descriptor = Os.open(directory.getPath(), OsConstants.O_RDONLY | OsConstants.O_DIRECTORY | OsConstants.O_CLOEXEC, 0);
            Os.fsync(descriptor);
        } catch (ErrnoException failed) { throw new IOException("Owner directory synchronization failed", failed); }
        finally {
            if (descriptor != null) {
                try { Os.close(descriptor); }
                catch (ErrnoException failed) { throw new IOException("Owner directory close failed", failed); }
            }
        }
    }
    private static void writeMarker(FileChannel marker, String state, String identity) throws IOException {
        byte[] bytes = (MARKER_MAGIC + "\n" + state + "\n" + identity + "\n").getBytes(StandardCharsets.US_ASCII);
        marker.position(0); marker.truncate(0);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) marker.write(buffer);
        marker.force(true);
    }
    private static String readReadyMarker(FileChannel marker) throws IOException {
        long size = marker.size();
        if (size <= 0 || size > 128) throw new IllegalStateException("Invalid owner provisioning marker");
        ByteBuffer buffer = ByteBuffer.allocate((int) size);
        while (buffer.hasRemaining()) if (marker.read(buffer) < 0) throw new IllegalStateException("Incomplete owner marker");
        String[] lines = new String(buffer.array(), StandardCharsets.US_ASCII).split("\n", -1);
        if (lines.length != 4 || !MARKER_MAGIC.equals(lines[0]) || !"READY".equals(lines[1]) || !lines[3].isEmpty())
            throw new IllegalStateException("Owner provisioning is incomplete or corrupt");
        return RuntimeOrigin.uuid(lines[2]);
    }
    private static void validateDatabase(SQLiteDatabase db, String identity) {
        if (db.getVersion() != 1) throw new IllegalStateException("Unsupported owner database version");
        try (Cursor schema = db.rawQuery("SELECT name,sql FROM sqlite_master WHERE name NOT GLOB 'sqlite_*' ORDER BY name", null)) {
            boolean row = schema.moveToFirst();
            // Android SQLiteConnection maintains this exact locale table, including during open.
            // It is platform metadata, never owner evidence. No arbitrary extra object is ignored.
            if (row && "android_metadata".equals(schema.getString(0))) {
                if (!PLATFORM_SCHEMA.equals(schema.getString(1)))
                    throw new IllegalStateException("Invalid platform locale schema");
                validatePlatformLocale(db);
                row = schema.moveToNext();
            }
            if (!row || !"owner_journal".equals(schema.getString(0)) || !JOURNAL_SCHEMA.equals(schema.getString(1))
                    || !schema.moveToNext() || !TABLE.equals(schema.getString(0)) || !OWNER_SCHEMA.equals(schema.getString(1))
                    || schema.moveToNext()) throw new IllegalStateException("Invalid owner database schema");
        }
        try (Cursor journal = db.rawQuery("SELECT slot,identity FROM owner_journal", null)) {
            if (!journal.moveToFirst() || journal.getType(0) != Cursor.FIELD_TYPE_INTEGER || journal.getInt(0) != 1
                    || journal.getType(1) != Cursor.FIELD_TYPE_STRING || !identity.equals(journal.getString(1)) || journal.moveToNext())
                throw new IllegalStateException("Owner journal identity does not match provisioning evidence");
        }
    }
    private static void validatePlatformLocale(SQLiteDatabase db) {
        try (Cursor locale = db.rawQuery("SELECT locale FROM android_metadata", null)) {
            if (!locale.moveToFirst() || locale.getType(0) != Cursor.FIELD_TYPE_STRING)
                throw new IllegalStateException("Invalid platform locale metadata");
            String value = locale.getString(0);
            if (value.length() > 512 || value.indexOf('\0') >= 0 || locale.moveToNext())
                throw new IllegalStateException("Invalid platform locale metadata");
        }
    }
    private static ContentValues values(RuntimeOwnerStore.Record record) {
        ContentValues values = new ContentValues();
        values.put("revision", record.revision); values.put("body", RuntimeOwnerRecordCodec.encode(record));
        return values;
    }
    private static RuntimeOwnerStore.Record read(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("SELECT slot,revision,CASE WHEN length(body)<="
                + RuntimeOwnerRecordCodec.MAX_BYTES + " THEN body ELSE NULL END,length(body) FROM " + TABLE, null)) {
            if (!cursor.moveToFirst() || cursor.getType(0) != Cursor.FIELD_TYPE_INTEGER || cursor.getInt(0) != 1
                    || cursor.getType(1) != Cursor.FIELD_TYPE_INTEGER || cursor.getType(2) != Cursor.FIELD_TYPE_BLOB)
                throw new IllegalStateException("Missing runtime owner record");
            if (cursor.getLong(3) <= 0 || cursor.getLong(3) > RuntimeOwnerRecordCodec.MAX_BYTES)
                throw new IllegalStateException("Invalid runtime owner record size");
            RuntimeOwnerStore.Record record = RuntimeOwnerRecordCodec.decode(cursor.getBlob(2));
            if (record.revision != cursor.getLong(1) || cursor.moveToNext())
                throw new IllegalStateException("Inconsistent runtime owner record");
            return record;
        }
    }
}
