package com.subhub.app.tasks;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.subhub.app.internal.*;
import java.io.File;
import java.nio.file.Files;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Every database, evidence marker and intentionally damaged file belongs to this fixture. */
@RunWith(AndroidJUnit4.class)
public final class InternalTaskStoreAndroidTest {
    private Context context;
    private File parent, file;
    private InternalStorageScope scope;
    private SharedPreferences evidence;
    private TaskStore opened;
    private final AtomicInteger admissions = new AtomicInteger();

    @Before public void prepare() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        parent = Files.createTempDirectory(context.getCacheDir().toPath(), "internal-tasks-db-").toFile();
        scope = FileInternalSubBackend.createNew(parent, new InternalProfile("Task fixture", "", "", "", 0),
                InternalConfigurationStore.defaults()).scope();
        file = scope.file(InternalStorageScope.Area.TASKS, "tasks.db");
        evidence = context.getSharedPreferences("internal_task_evidence_fixture_" + UUID.randomUUID(), 0);
    }
    @After public void restore() throws Exception {
        try { if (opened != null) opened.close(); }
        finally {
            if (evidence != null) assertTrue(evidence.edit().clear().commit());
            if (file != null) {
                java.lang.reflect.Field field = InternalTaskDatabase.class.getDeclaredField("UNCERTAIN"); field.setAccessible(true);
                Set<?> uncertain = (Set<?>) field.get(null);
                synchronized (uncertain) { uncertain.remove(InternalTaskDatabase.evidenceKey(file)); }
            }
            if (parent != null && parent.exists()) try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(parent.toPath())) {
                for (java.nio.file.Path path : (Iterable<java.nio.file.Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
    }
    private TaskStore open() throws Exception {
        return new TaskStore(context, scope, evidence, admissions::incrementAndGet);
    }
    private void seed() throws Exception {
        opened = open();
        TaskDefinition task = new TaskDefinition("owned_db_task", "Owned task", "", LocalDateTime.now().withSecond(0).withNano(0),
                ZoneId.systemDefault(), 300_000, Set.of(), true, false, null);
        opened.append("internal_sub", new TaskAction("owned_db_create", task.id, TaskAction.Kind.CREATE,
                null, System.currentTimeMillis(), "", "", List.of(), task), true, false);
        assertEquals(1, opened.load("internal_sub").definitions().size());
        opened.close(); opened = null;
    }

    @Test public void firstProvisionAcknowledgesOnceAndReopenRetainsTaskJournal() throws Exception {
        seed();
        assertEquals(1, admissions.get());
        assertEquals("ready", evidence.getString(InternalTaskDatabase.evidenceKey(file), ""));
        opened = open();
        assertEquals(1, opened.load("internal_sub").definitions().size());
        assertEquals("Existing reads never perform new-create admission", 1, admissions.get());
    }

    @Test public void interruptedOrUnacknowledgedCreationPreservesOwnedBytes() throws Exception {
        byte[] partial = {1, 2, 3, 4};
        Files.write(file.toPath(), partial);
        assertTrue(evidence.edit().putString(InternalTaskDatabase.evidenceKey(file), "started").commit());
        assertThrows(Exception.class, this::open);
        assertArrayEquals(partial, Files.readAllBytes(file.toPath()));
        assertEquals(0, admissions.get());
        assertTrue(evidence.edit().clear().commit());
        assertThrows(Exception.class, this::open);
        assertArrayEquals(partial, Files.readAllBytes(file.toPath()));
        assertEquals(0, admissions.get());
    }

    @Test public void missingEstablishedDatabaseNeverRecreatesAnEmptyHistory() throws Exception {
        seed();
        File retained = new File(parent, "retained-tasks.db");
        byte[] original = Files.readAllBytes(file.toPath());
        Files.move(file.toPath(), retained.toPath());
        assertThrows(Exception.class, this::open);
        assertFalse(file.exists());
        assertArrayEquals(original, Files.readAllBytes(retained.toPath()));
        assertEquals("ready", evidence.getString(InternalTaskDatabase.evidenceKey(file), ""));
        assertEquals(1, admissions.get());
    }

    @Test public void missingEstablishedProjectionPreservesJournalAndRejectsNewCreate() throws Exception {
        seed(); opened = open();
        android.database.sqlite.SQLiteDatabase database = opened.getWritableDatabase();
        String journal = android.database.DatabaseUtils.stringForQuery(database,
                "SELECT body FROM journal WHERE scope='internal_sub' AND seq=1", null);
        database.execSQL("DELETE FROM projections WHERE scope='internal_sub'");
        opened.close(); opened = null;
        byte[] corrupted = Files.readAllBytes(file.toPath());
        opened = open();
        assertThrows(android.database.sqlite.SQLiteDatabaseCorruptException.class, () -> opened.load("internal_sub"));
        TaskDefinition later = new TaskDefinition("later_owned_task", "Later task", "",
                LocalDateTime.now().withSecond(0).withNano(0), ZoneId.systemDefault(), 300_000,
                Set.of(), true, false, null);
        TaskAction create = new TaskAction("later_owned_create", later.id, TaskAction.Kind.CREATE,
                null, System.currentTimeMillis(), "", "", List.of(), later);
        assertThrows(android.database.sqlite.SQLiteDatabaseCorruptException.class,
                () -> opened.append("internal_sub", create, true, false));
        database = opened.getReadableDatabase();
        assertEquals(0, android.database.DatabaseUtils.longForQuery(database, "SELECT COUNT(*) FROM projections", null));
        assertEquals(1, android.database.DatabaseUtils.longForQuery(database, "SELECT COUNT(*) FROM journal", null));
        assertEquals(journal, android.database.DatabaseUtils.stringForQuery(database,
                "SELECT body FROM journal WHERE scope='internal_sub' AND seq=1", null));
        opened.close(); opened = null;
        assertArrayEquals(corrupted, Files.readAllBytes(file.toPath()));
        assertEquals("ready", evidence.getString(InternalTaskDatabase.evidenceKey(file), ""));
        assertEquals(1, admissions.get());
    }

    @Test public void corruptEstablishedDatabaseHasNoDeletingRecoveryHandler() throws Exception {
        seed();
        byte[] corrupt = "This is intentionally not a SQLite database. Preserve it.".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(file.toPath(), corrupt);
        assertThrows(Exception.class, this::open);
        assertArrayEquals(corrupt, Files.readAllBytes(file.toPath()));
        // A zero-byte established file must not be mistaken for a new database either.
        Files.write(file.toPath(), new byte[0]);
        assertThrows(Exception.class, this::open);
        assertTrue(file.isFile()); assertEquals(0, file.length());
        assertEquals("ready", evidence.getString(InternalTaskDatabase.evidenceKey(file), ""));
        assertEquals(1, admissions.get());
    }
}
