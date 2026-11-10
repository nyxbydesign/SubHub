package com.subhub.app.runtime;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Each contract owns only a UUID-named test database and marker, never the application's journal. */
@RunWith(AndroidJUnit4.class)
public final class RuntimeOwnerDatabaseAndroidTest {
    @Test public void ordinaryOpenNeverProvisionsAnAbsentJournal() {
        try (Fixture fixture = new Fixture(false)) {
            refuses(fixture.first::read);
            refuses(() -> fixture.first.compareAndSet(RuntimeOwnerStore.Record.idle(0), reserved("internal_a", 1)));
            assertFalse(fixture.database().exists()); assertFalse(fixture.marker().exists());
        }
    }

    @Test public void ownerEvidenceSurvivesCloseAndReopenWithoutCreatingAuthority() {
        try (Fixture fixture = new Fixture()) {
            RuntimeOwnerStore.Record reserved = reserved("internal_a", 1);
            assertEquals(RuntimeOwnerStore.Outcome.COMMITTED,
                    fixture.first.compareAndSet(RuntimeOwnerStore.Record.idle(0), reserved));
            fixture.first.close();
            try (SQLiteRuntimeOwnerBackend reopened = fixture.reopen()) {
                RuntimeOwnerStore.Record observed = reopened.read();
                assertEquals(reserved, observed);
                assertEquals(RuntimeLease.Identity.class, observed.owner.getClass());
                assertFalse(reserved.owner == observed.owner);
            }
        }
    }

    @Test public void independentHandlesCannotReplaceAnAdmittedOwnerWithStaleEvidence() {
        try (Fixture fixture = new Fixture()) {
            RuntimeOwnerStore.Record oldFirst = fixture.first.read(), oldSecond = fixture.second.read();
            RuntimeOwnerStore.Record winner = reserved("internal_a", 1), loser = reserved("internal_b", 1);
            assertEquals(RuntimeOwnerStore.Outcome.COMMITTED, fixture.first.compareAndSet(oldFirst, winner));
            assertEquals(RuntimeOwnerStore.Outcome.CONFLICT, fixture.second.compareAndSet(oldSecond, loser));
            assertEquals(winner, fixture.second.read());
            RuntimeOwnerStore.Record forgedNext = loser.next(RuntimeOwnerStore.Phase.STOPPING, 3, 3, Set.of());
            assertEquals(RuntimeOwnerStore.Outcome.CONFLICT, fixture.second.compareAndSet(loser, forgedNext));
            assertEquals(winner, fixture.first.read());
        }
    }

    @Test public void missingExistingRowNeverReinitializesIdle() {
        try (Fixture fixture = new Fixture()) {
            fixture.edit(db -> db.execSQL("DELETE FROM physical_owner"));
            refuses(fixture.first::read);
            assertEquals(RuntimeOwnerStore.Outcome.UNCERTAIN,
                    fixture.first.compareAndSet(RuntimeOwnerStore.Record.idle(0), reserved("internal_a", 1)));
            fixture.first.close();
            try (SQLiteRuntimeOwnerBackend reopened = fixture.reopen()) { refuses(reopened::read); }
        }
    }

    @Test public void corruptBodyAndMismatchedRevisionStayFailClosed() {
        try (Fixture fixture = new Fixture()) {
            fixture.edit(db -> db.execSQL("UPDATE physical_owner SET revision=9"));
            refuses(fixture.first::read);
            assertEquals(RuntimeOwnerStore.Outcome.UNCERTAIN,
                    fixture.first.compareAndSet(RuntimeOwnerStore.Record.idle(0), reserved("internal_a", 1)));
            fixture.edit(db -> db.execSQL("UPDATE physical_owner SET revision=0,body=?", new Object[]{new byte[]{1, 2, 3}}));
            refuses(fixture.first::read);
            assertEquals(RuntimeOwnerStore.Outcome.UNCERTAIN,
                    fixture.first.compareAndSet(RuntimeOwnerStore.Record.idle(0), reserved("internal_a", 1)));
        }
    }

    @Test public void closedHandleCannotReadOrWrite() {
        try (Fixture fixture = new Fixture()) {
            fixture.first.close(); refuses(fixture.first::read);
            refuses(() -> fixture.first.compareAndSet(RuntimeOwnerStore.Record.idle(0), reserved("internal_a", 1)));
            assertEquals(RuntimeOwnerStore.Record.idle(0), fixture.second.read());
        }
    }

    @Test public void stoppedOwnerReleasesOnlyAfterItsAcknowledgedIdleCommit() {
        try (Fixture fixture = new Fixture()) {
            RuntimeOwnerStore.Record idle = fixture.first.read();
            RuntimeOwnerStore.Record reserved = reserved("internal_a", 1);
            RuntimeOwnerStore.Record starting = reserved.next(RuntimeOwnerStore.Phase.STARTING, 3, 3, Set.of());
            RuntimeOwnerStore.Record active = starting.next(RuntimeOwnerStore.Phase.ACTIVE, 3, 3, Set.of("capture"));
            RuntimeOwnerStore.Record stopping = active.next(RuntimeOwnerStore.Phase.STOPPING, 3, 3, Set.of("capture"));
            for (RuntimeOwnerStore.Record next : new RuntimeOwnerStore.Record[]{reserved, starting, active, stopping}) {
                assertEquals(RuntimeOwnerStore.Outcome.COMMITTED, fixture.first.compareAndSet(idle, next));
                assertEquals(next, fixture.second.read()); idle = next;
            }
            RuntimeOwnerStore.Record finalIdle = RuntimeOwnerStore.Record.idle(5);
            assertEquals(RuntimeOwnerStore.Outcome.COMMITTED, fixture.first.compareAndSet(stopping, finalIdle));
            assertEquals(finalIdle, fixture.second.read());
            assertEquals(RuntimeOwnerStore.Outcome.CONFLICT, fixture.first.compareAndSet(stopping, finalIdle));
        }
    }

    @Test public void closedReopenedMissingDatabaseCannotManufactureIdle() throws IOException {
        try (Fixture fixture = new Fixture()) {
            assertEquals(RuntimeOwnerStore.Outcome.COMMITTED,
                    fixture.first.compareAndSet(RuntimeOwnerStore.Record.idle(0), reserved("internal_a", 1)));
            byte[] marker = Files.readAllBytes(fixture.marker().toPath());
            fixture.first.close(); fixture.second.close();
            assertTrue(fixture.context.deleteDatabase(fixture.name));
            assertFalse(fixture.database().exists());
            try (SQLiteRuntimeOwnerBackend reopened = fixture.reopen()) { refuses(reopened::read); }
            refuses(() -> SQLiteRuntimeOwnerBackend.provisionNewJournal(fixture.context, fixture.name));
            assertFalse(fixture.database().exists());
            assertArrayEquals(marker, Files.readAllBytes(fixture.marker().toPath()));
        }
    }

    @Test public void actualCorruptDatabaseIsPreservedRatherThanDeletedAndRecreated() throws IOException {
        try (Fixture fixture = new Fixture()) {
            assertEquals(RuntimeOwnerStore.Outcome.COMMITTED,
                    fixture.first.compareAndSet(RuntimeOwnerStore.Record.idle(0), reserved("internal_a", 1)));
            fixture.first.close(); fixture.second.close();
            byte[] corrupt = new byte[4096];
            byte[] witness = "Not a SQLite database; owned corruption fixture".getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(witness, 0, corrupt, 0, witness.length);
            write(fixture.database(), corrupt);
            byte[] marker = Files.readAllBytes(fixture.marker().toPath());
            for (int attempt = 0; attempt < 2; attempt++) {
                try (SQLiteRuntimeOwnerBackend reopened = fixture.reopen()) { refuses(reopened::read); }
                assertTrue(fixture.database().exists());
                assertArrayEquals(corrupt, Files.readAllBytes(fixture.database().toPath()));
                assertArrayEquals(marker, Files.readAllBytes(fixture.marker().toPath()));
            }
            refuses(() -> SQLiteRuntimeOwnerBackend.provisionNewJournal(fixture.context, fixture.name));
        }
    }

    @Test public void missingOrCorruptMarkerDoesNotReinitializeExistingDatabase() throws IOException {
        try (Fixture fixture = new Fixture()) {
            fixture.first.close(); fixture.second.close();
            byte[] database = Files.readAllBytes(fixture.database().toPath());
            assertTrue(fixture.marker().delete());
            try (SQLiteRuntimeOwnerBackend reopened = fixture.reopen()) { refuses(reopened::read); }
            refuses(() -> SQLiteRuntimeOwnerBackend.provisionNewJournal(fixture.context, fixture.name));
            assertFalse(fixture.marker().exists());
            write(fixture.marker(), new byte[]{1, 2, 3});
            try (SQLiteRuntimeOwnerBackend reopened = fixture.reopen()) { refuses(reopened::read); }
            assertArrayEquals(database, Files.readAllBytes(fixture.database().toPath()));
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(fixture.marker().toPath()));
        }
    }

    @Test public void interruptedProvisioningCannotBeRetriedAsFresh() throws IOException {
        try (Fixture fixture = new Fixture(false)) {
            byte[] pending = ("SUBHUB_OWNER_V1\nPROVISIONING\n" + UUID.randomUUID() + "\n").getBytes(StandardCharsets.US_ASCII);
            write(fixture.marker(), pending);
            refuses(fixture.first::read);
            refuses(() -> SQLiteRuntimeOwnerBackend.provisionNewJournal(fixture.context, fixture.name));
            assertFalse(fixture.database().exists());
            assertArrayEquals(pending, Files.readAllBytes(fixture.marker().toPath()));
            // A crash after the database file was claimed must be just as non-retryable.
            write(fixture.database(), new byte[0]);
            refuses(fixture.second::read);
            refuses(() -> SQLiteRuntimeOwnerBackend.provisionNewJournal(fixture.context, fixture.name));
            assertEquals(0, fixture.database().length());
            assertArrayEquals(pending, Files.readAllBytes(fixture.marker().toPath()));
        }
    }

    @Test public void validDatabaseWithPendingMarkerCannotPublishIdle() throws IOException {
        try (Fixture fixture = new Fixture()) {
            String ready = new String(Files.readAllBytes(fixture.marker().toPath()), StandardCharsets.US_ASCII);
            write(fixture.marker(), ready.replace("\nREADY\n", "\nPROVISIONING\n").getBytes(StandardCharsets.US_ASCII));
            refuses(fixture.first::read);
            refuses(() -> SQLiteRuntimeOwnerBackend.provisionNewJournal(fixture.context, fixture.name));
        }
    }

    @Test public void journalIdentitySchemaAndVersionMustRemainBound() {
        try (Fixture fixture = new Fixture()) {
            fixture.edit(db -> db.execSQL("UPDATE owner_journal SET identity=?", new Object[]{UUID.randomUUID().toString()}));
            refuses(fixture.first::read);
            fixture.edit(db -> db.execSQL("DROP TABLE owner_journal"));
            refuses(fixture.second::read);
        }
        try (Fixture fixture = new Fixture()) {
            fixture.edit(db -> db.setVersion(0));
            refuses(fixture.first::read);
            refuses(() -> SQLiteRuntimeOwnerBackend.provisionNewJournal(fixture.context, fixture.name));
        }
    }

    @Test public void explicitProvisioningCannotResetExistingOwner() {
        try (Fixture fixture = new Fixture()) {
            RuntimeOwnerStore.Record owner = reserved("internal_a", 1);
            assertEquals(RuntimeOwnerStore.Outcome.COMMITTED, fixture.first.compareAndSet(RuntimeOwnerStore.Record.idle(0), owner));
            refuses(() -> SQLiteRuntimeOwnerBackend.provisionNewJournal(fixture.context, fixture.name));
            assertEquals(owner, fixture.second.read());
        }
    }

    @Test public void simultaneousProvisionersHaveOnlyOneWinner() throws InterruptedException {
        try (Fixture fixture = new Fixture(false)) {
            CountDownLatch start = new CountDownLatch(1), finished = new CountDownLatch(2);
            AtomicInteger winners = new AtomicInteger(), refusals = new AtomicInteger();
            AtomicReference<Throwable> unexpected = new AtomicReference<>();
            Runnable attempt = () -> {
                try {
                    if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Provisioning gate timed out");
                    try (SQLiteRuntimeOwnerBackend created = SQLiteRuntimeOwnerBackend.provisionNewJournal(fixture.context, fixture.name)) {
                        assertEquals(RuntimeOwnerStore.Record.idle(0), created.read()); winners.incrementAndGet();
                    } catch (IllegalStateException refused) { refusals.incrementAndGet(); }
                } catch (Throwable failed) { unexpected.compareAndSet(null, failed); }
                finally { finished.countDown(); }
            };
            Thread first = new Thread(attempt), second = new Thread(attempt);
            fixture.workersDrained = false;
            first.start(); second.start(); start.countDown();
            try {
                assertTrue("Provisioning workers did not finish", finished.await(10, TimeUnit.SECONDS));
                if (unexpected.get() != null) throw new AssertionError(unexpected.get());
                assertEquals(1, winners.get()); assertEquals(1, refusals.get());
                assertEquals(RuntimeOwnerStore.Record.idle(0), fixture.first.read());
            } finally {
                first.join(5000); second.join(5000);
                fixture.workersDrained = !first.isAlive() && !second.isAlive();
                assertFalse("First provisioning worker still owns fixture", first.isAlive());
                assertFalse("Second provisioning worker still owns fixture", second.isAlive());
            }
        }
    }

    @Test public void scopedApplicationContextCannotCreateAnotherPhysicalJournal() {
        try (Fixture fixture = new Fixture(false)) {
            Context scoped = new ContextWrapper(fixture.context) {
                @Override public Context getApplicationContext() { return this; }
            };
            refuses(() -> new SQLiteRuntimeOwnerBackend(scoped, fixture.name));
            refuses(() -> SQLiteRuntimeOwnerBackend.provisionNewJournal(scoped, fixture.name));
            assertFalse(fixture.marker().exists()); assertFalse(fixture.database().exists());
        }
    }

    private static RuntimeOwnerStore.Record reserved(String id, long revision) {
        RuntimeLease.Identity owner = new RuntimeLease.Identity(RuntimeTarget.internal(id), RuntimeOrigin.LOCAL_UI,
                "profile_generation", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), 3);
        return new RuntimeOwnerStore.Record(revision, RuntimeOwnerStore.Phase.RESERVED,
                owner, 3, 3, Set.of("capture"), Set.of());
    }
    private static void refuses(Runnable operation) {
        try { operation.run(); fail("Invalid owner evidence was accepted"); }
        catch (IllegalArgumentException | IllegalStateException | SQLiteException expected) { /* Fail closed. */ }
    }
    private static void write(File file, byte[] bytes) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes); output.getFD().sync();
        }
    }
    private interface Edit { void run(SQLiteDatabase database); }
    private static final class Fixture implements AutoCloseable {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final String name = "subhub_runtime_test_" + UUID.randomUUID().toString().replace("-", "") + ".db";
        final SQLiteRuntimeOwnerBackend first, second;
        boolean workersDrained = true;
        Fixture() { this(true); }
        Fixture(boolean provision) {
            try {
                first = provision ? SQLiteRuntimeOwnerBackend.provisionNewJournal(context, name)
                        : new SQLiteRuntimeOwnerBackend(context, name);
                second = new SQLiteRuntimeOwnerBackend(context, name);
            } catch (RuntimeException | AssertionError failed) { cleanFiles(); throw failed; }
        }
        File database() { return context.getDatabasePath(name); }
        File marker() { return SQLiteRuntimeOwnerBackend.markerFile(context, name); }
        SQLiteRuntimeOwnerBackend reopen() { return new SQLiteRuntimeOwnerBackend(context, name); }
        void edit(Edit edit) {
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(database().getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
                edit.run(db);
            }
        }
        private void cleanFiles() {
            // Names are owned by this fixture. Never enumerate or clear another database/marker.
            try {
                if (database().exists()) assertTrue(context.deleteDatabase(name));
                assertFalse(database().exists());
                for (String suffix : new String[]{"-journal", "-wal", "-shm"}) {
                    File sidecar = new File(database().getPath() + suffix);
                    if (sidecar.exists()) assertTrue(sidecar.delete());
                    assertFalse(sidecar.exists());
                }
            } finally {
                if (marker().exists()) assertTrue(marker().delete());
                assertFalse(marker().exists());
            }
        }
        @Override public void close() {
            if (!workersDrained) throw new AssertionError("Retained owned fixture while worker is active: " + name);
            try { first.close(); }
            finally { try { second.close(); } finally { cleanFiles(); } }
        }
    }
}
