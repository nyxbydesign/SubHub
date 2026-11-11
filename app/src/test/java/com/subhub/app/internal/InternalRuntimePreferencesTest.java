package com.subhub.app.internal;

import android.content.SharedPreferences;
import org.junit.Test;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public final class InternalRuntimePreferencesTest {
    private static final Runnable WORKER = () -> { };
    private static final InternalRuntimePreferences.WriteGuard ADMITTED = Runnable::run;
    private interface Check { void run() throws Exception; }
    private interface DirectoryCheck { void run(InternalStorageScope scope, Path parent) throws Exception; }

    @Test public void typedPreferencesRoundTripAndDefensiveCopies() throws Exception {
        directory((scope, parent) -> {
            InternalRuntimePreferences store = store(provision(scope), ADMITTED);
            assertTrue(store.isAvailable());
            SharedPreferences prefs = store.preferences("stats"); Set<String> source = new HashSet<>(Set.of("x", "y"));
            SharedPreferences.Editor edit = prefs.edit().putBoolean("b", true).putInt("i", 3).putLong("l", 4L)
                    .putFloat("f", Float.NaN).putString("s", "text").putStringSet("set", source);
            source.add("not retained"); assertTrue(edit.commit());
            assertEquals(1, store.revision()); assertEquals(1, store.lastReceipt().revision);
            assertTrue(store.lastReceipt().changed); assertEquals(0, store.lastReceipt().previousRevision);
            Map<String, ?> all = prefs.getAll(); assertEquals(6, all.size());
            assertTrue(all.get("i") instanceof Integer); assertTrue(all.get("l") instanceof Long);
            assertTrue(all.get("f") instanceof Float); assertTrue(Float.isNaN(prefs.getFloat("f", 0)));
            assertEquals(Set.of("x", "y"), prefs.getStringSet("set", null));
            expect(UnsupportedOperationException.class, () -> all.remove("i"));
            expect(UnsupportedOperationException.class, () -> prefs.getStringSet("set", null).add("bad"));
            expect(ClassCastException.class, () -> prefs.getLong("i", 0));
            store.close(); assertFalse(store.isAvailable()); InternalRuntimePreferences reopened = store(open(scope), ADMITTED);
            assertEquals(all, reopened.preferences("stats").getAll());
            assertEquals(4L, reopened.preferences("stats").getLong("l", 0)); reopened.close();
        });
    }
    @Test public void commitAndApplyAreGuardedAndAcknowledgeActualDisk() throws Exception {
        directory((scope, parent) -> {
            AtomicBoolean permitted = new AtomicBoolean(); AtomicBoolean inGuard = new AtomicBoolean();
            AtomicReference<InternalRuntimePreferences> reference = new AtomicReference<>();
            AtomicInteger admissions = new AtomicInteger(); FileInternalRuntimeBackend disk = provision(scope);
            InternalRuntimePreferences.Backend checked = new Forward(disk) {
                @Override public InternalRuntimePreferences.Outcome compareAndSet(InternalRuntimeRecord expected, InternalRuntimeRecord next) throws Exception {
                    assertTrue(inGuard.get()); return super.compareAndSet(expected, next);
                }
            };
            InternalRuntimePreferences store = store(checked, operation -> {
                if (!permitted.get()) throw new SecurityException("No exact owner admission");
                assertFalse(inGuard.getAndSet(true)); admissions.incrementAndGet();
                try { operation.run(); assertEquals(disk.read().revision, reference.get().revision()); }
                catch (IOException problem) { throw new IllegalStateException(problem); }
                finally { inGuard.set(false); }
            });
            // Publication can be observed inside the admission after its operation returns.
            reference.set(store);
            SharedPreferences prefs = store.preferences("stats");
            expect(SecurityException.class, () -> prefs.edit().putInt("count", 1).commit());
            expect(SecurityException.class, () -> prefs.edit().putInt("count", 1).apply());
            assertFalse(prefs.contains("count")); assertEquals(0, disk.read().revision);
            permitted.set(true); prefs.edit().putInt("count", 1).apply();
            assertEquals(1, disk.read().namespaces().get("stats").get("count"));
            prefs.edit().putInt("count", 2).commit(); assertEquals(2, admissions.get());
            assertEquals(2, store.revision()); assertFalse(inGuard.get()); store.close();
        });
    }
    @Test public void nestedMultiNamespaceWorkIsOneDurableTransaction() {
        Fake backend = new Fake(); AtomicInteger guards = new AtomicInteger();
        InternalRuntimePreferences store = store(backend, operation -> { guards.incrementAndGet(); operation.run(); });
        SharedPreferences stats = store.preferences("stats"), usage = store.preferences("usage"), ledger = store.preferences("ledger");
        String result = store.transaction(() -> {
            stats.edit().putInt("count", 1).apply(); assertEquals(1, stats.getInt("count", 0));
            assertEquals(0, backend.writes); assertEquals(0, store.revision());
            store.transaction(() -> { usage.edit().putLong("millis", 1200L).commit(); ledger.edit().putString("operation", "one").apply(); });
            assertTrue(backend.record.namespaces().isEmpty()); return "saved";
        });
        assertEquals("saved", result); assertEquals(1, backend.writes); assertEquals(1, guards.get());
        assertEquals(Set.of("stats", "usage", "ledger"), backend.record.namespaces().keySet());
        assertEquals(1, store.lastReceipt().revision);
    }
    @Test public void nestedFailureCannotBeCaughtToPublishPartialData() {
        Fake backend = new Fake(); InternalRuntimePreferences store = store(backend, ADMITTED);
        SharedPreferences prefs = store.preferences("stats");
        expect(IllegalStateException.class, () -> store.transaction(() -> {
            prefs.edit().putInt("count", 1).apply();
            try { store.transaction((Runnable) () -> { throw new IllegalArgumentException("fixture"); }); }
            catch (IllegalArgumentException deliberatelyCaught) { }
        }));
        assertEquals(0, backend.writes); assertFalse(prefs.contains("count")); assertNull(store.lastReceipt()); assertTrue(store.isAvailable());
        prefs.edit().putInt("count", 2).apply(); assertEquals(2, prefs.getInt("count", 0));
    }
    @Test public void uncertainOrFailedWritesFenceWithoutPartialPublication() {
        for (InternalRuntimePreferences.Outcome outcome : List.of(InternalRuntimePreferences.Outcome.UNCERTAIN,
                InternalRuntimePreferences.Outcome.NOT_COMMITTED, InternalRuntimePreferences.Outcome.CONFLICT)) {
            Fake backend = new Fake(); backend.outcome = outcome; InternalRuntimePreferences store = store(backend, ADMITTED);
            SharedPreferences prefs = store.preferences("stats"); AtomicInteger notified = new AtomicInteger();
            prefs.registerOnSharedPreferenceChangeListener((p, k) -> notified.incrementAndGet());
            InternalRuntimePreferences.PersistenceException failure = expect(InternalRuntimePreferences.PersistenceException.class,
                    () -> store.transaction(() -> { prefs.edit().putInt("count", 1).apply(); store.preferences("ledger").edit().putLong("cents", 40).commit(); }));
            assertEquals(outcome, failure.outcome); assertFalse(store.isAvailable()); assertEquals(0, notified.get()); assertEquals(1, backend.writes);
            expect(InternalRuntimePreferences.PersistenceException.class, prefs::getAll);
            expect(InternalRuntimePreferences.PersistenceException.class, () -> prefs.edit().putInt("count", 2).apply());
            assertEquals(1, backend.writes); store.close();
        }
    }
    @Test public void lostEstablishedDataNeverBecomesEmptyOrProvisionsAgain() throws Exception {
        for (String target : List.of("record", "marker", "directory")) directory((scope, parent) -> {
            FileInternalRuntimeBackend backend = provision(scope); InternalRuntimePreferences store = store(backend, ADMITTED);
            store.preferences("stats").edit().putLong("total", 42L).apply();
            Path directory = scope.root().resolve(FileInternalRuntimeBackend.DIRECTORY);
            if (target.equals("marker")) Files.delete(scope.root().resolve(FileInternalRuntimeBackend.MARKER));
            else { Files.delete(directory.resolve(FileInternalRuntimeBackend.RECORD)); if (target.equals("directory")) Files.delete(directory); }
            expect(InternalRuntimePreferences.PersistenceException.class, store::validate);
            expect(InternalRuntimePreferences.PersistenceException.class, () -> store.preferences("stats").getLong("total", 0));
            expect(IOException.class, () -> open(scope)); expect(IOException.class, () -> provision(scope)); store.close();
        });
    }
    @Test public void openExistingNeverCreatesFilesAndPartialProvisionRemainsBlocked() throws Exception {
        directory((scope, parent) -> {
            expect(IOException.class, () -> open(scope)); assertEquals(0, countFiles(scope.root()));
            Files.write(scope.root().resolve(FileInternalRuntimeBackend.MARKER), "STARTED".getBytes(StandardCharsets.UTF_8));
            expect(IOException.class, () -> open(scope)); expect(IOException.class, () -> provision(scope));
            assertEquals("STARTED", new String(Files.readAllBytes(scope.root().resolve(FileInternalRuntimeBackend.MARKER)), StandardCharsets.UTF_8));
        });
    }
    @Test public void corruptOrForeignRecordNeverAcknowledgesDefaults() throws Exception {
        for (String damage : List.of("checksum", "truncated", "identity")) directory((scope, parent) -> {
            FileInternalRuntimeBackend backend = provision(scope); Path file = scope.root().resolve(FileInternalRuntimeBackend.DIRECTORY).resolve(FileInternalRuntimeBackend.RECORD);
            byte[] bytes = Files.readAllBytes(file);
            if (damage.equals("checksum")) bytes[bytes.length / 2] ^= 1;
            else if (damage.equals("truncated")) bytes = Arrays.copyOf(bytes, 15);
            else bytes = InternalRuntimeRecord.encode(new InternalRuntimeRecord(UUID.randomUUID().toString(), 0, Map.of()));
            Files.write(file, bytes); byte[] damaged = bytes;
            expect(IOException.class, backend::read); expect(IOException.class, () -> open(scope));
            assertArrayEquals(damaged, Files.readAllBytes(file)); expect(IOException.class, () -> provision(scope)); backend.close();
        });
    }
    @Test public void noSymlinkBoundaryAndReplacedMarkerAreRejected() throws Exception {
        for (String target : List.of("record", "marker", "directory", "root")) directory((scope, parent) -> {
            FileInternalRuntimeBackend backend = provision(scope);
            Path original = target.equals("root") ? scope.root() : target.equals("directory")
                    ? scope.root().resolve(FileInternalRuntimeBackend.DIRECTORY) : target.equals("marker")
                    ? scope.root().resolve(FileInternalRuntimeBackend.MARKER)
                    : scope.root().resolve(FileInternalRuntimeBackend.DIRECTORY).resolve(FileInternalRuntimeBackend.RECORD);
            Path other = parent.resolve("sibling-" + target); Files.move(original, other); Files.createSymbolicLink(original, other);
            expect(IOException.class, backend::read); expect(IOException.class, () -> open(scope));
            backend.close(); Files.delete(original); Files.move(other, original);
        });
        directory((scope, parent) -> {
            FileInternalRuntimeBackend backend = provision(scope); Path marker = scope.root().resolve(FileInternalRuntimeBackend.MARKER);
            byte[] sameContents = Files.readAllBytes(marker); Files.move(marker, parent.resolve("old-marker")); Files.write(marker, sameContents);
            expect(IOException.class, backend::read); backend.close();
        });
    }
    @Test public void concurrentBackendsUseFullRecordCasAndRefuseStaleCache() throws Exception {
        directory((scope, parent) -> {
            FileInternalRuntimeBackend a = provision(scope), b = open(scope);
            InternalRuntimePreferences first = store(a, ADMITTED), second = store(b, ADMITTED);
            first.preferences("stats").edit().putInt("count", 1).apply();
            expect(InternalRuntimePreferences.PersistenceException.class, () -> second.preferences("stats").edit().putInt("count", 2).apply());
            assertEquals(1, a.read().namespaces().get("stats").get("count")); first.close(); second.close();
        });
    }
    @Test public void readsDoNotBlockOnWriterAndNeverObserveStagedChanges() throws Exception {
        Fake backend = new Fake(); CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1);
        backend.beforeWrite = () -> { writing.countDown(); await(release); };
        InternalRuntimePreferences store = store(backend, ADMITTED); SharedPreferences prefs = store.preferences("stats");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> write = executor.submit(() -> store.transaction(() -> {
                prefs.edit().putInt("count", 8).apply(); assertEquals(8, prefs.getInt("count", 0));
            }));
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            Future<Map<String, ?>> read = executor.submit(prefs::getAll);
            assertTrue(read.get(1, TimeUnit.SECONDS).isEmpty()); assertEquals(0, store.revision());
            release.countDown(); write.get(5, TimeUnit.SECONDS); assertEquals(8, prefs.getInt("count", 0));
        } finally { release.countDown(); executor.shutdown(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    @Test public void serializedWritersNeverLoseUpdates() throws Exception {
        Fake backend = new Fake(); InternalRuntimePreferences store = store(backend, ADMITTED); SharedPreferences prefs = store.preferences("usage");
        ExecutorService executor = Executors.newFixedThreadPool(4); List<Future<?>> work = new ArrayList<>();
        try {
            for (int i = 0; i < 40; i++) work.add(executor.submit(() -> store.transaction(() -> prefs.edit().putInt("count", prefs.getInt("count", 0) + 1).commit())));
            for (Future<?> future : work) future.get(5, TimeUnit.SECONDS);
            assertEquals(40, prefs.getInt("count", 0)); assertEquals(40, backend.writes); assertEquals(40, store.revision());
        } finally { executor.shutdown(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    @Test public void closeWaitsForAdmittedWriteAndNeverDiscardsIt() throws Exception {
        Fake backend = new Fake(); CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1), closing = new CountDownLatch(1);
        backend.beforeWrite = () -> { writing.countDown(); await(release); };
        InternalRuntimePreferences store = store(backend, ADMITTED); SharedPreferences prefs = store.preferences("stats");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> write = executor.submit(() -> prefs.edit().putInt("count", 1).apply());
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            Future<?> close = executor.submit(() -> { closing.countDown(); store.close(); }); assertTrue(closing.await(5, TimeUnit.SECONDS));
            assertFalse(close.isDone()); assertFalse(backend.closed); release.countDown();
            write.get(5, TimeUnit.SECONDS); close.get(5, TimeUnit.SECONDS);
            assertTrue(backend.closed); assertEquals(1, backend.record.namespaces().get("stats").get("count"));
            expect(IllegalStateException.class, prefs::getAll); expect(IllegalStateException.class, () -> prefs.edit().clear().apply());
        } finally { release.countDown(); executor.shutdown(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    @Test public void workerChecksRejectBeforeAnyDiskWriteOrCacheChange() {
        Fake backend = new Fake(); AtomicBoolean worker = new AtomicBoolean(true);
        InternalRuntimePreferences store = new InternalRuntimePreferences(backend, ADMITTED, () -> {
            if (!worker.get()) throw new IllegalStateException("Main thread");
        });
        SharedPreferences prefs = store.preferences("stats"); worker.set(false);
        expect(IllegalStateException.class, () -> prefs.edit().putInt("count", 1).commit());
        expect(IllegalStateException.class, () -> prefs.edit().putInt("count", 1).apply());
        assertTrue(prefs.getAll().isEmpty()); assertEquals(0, backend.writes);
    }
    @Test public void badTypesEncodingBoundsAndRevisionOverflowFailBeforeMutation() {
        Fake backend = new Fake(); InternalRuntimePreferences store = store(backend, ADMITTED); SharedPreferences prefs = store.preferences("stats");
        expect(IllegalArgumentException.class, () -> store.preferences("../physical"));
        expect(IllegalArgumentException.class, () -> prefs.edit().putString("bad", "\ud800").apply());
        expect(IllegalArgumentException.class, () -> prefs.edit().putString("large", "x".repeat(InternalRuntimeRecord.MAX_BYTES)).commit());
        assertEquals(0, backend.writes); assertTrue(prefs.getAll().isEmpty());
        backend.record = new InternalRuntimeRecord(backend.record.identity, Long.MAX_VALUE, Map.of());
        InternalRuntimePreferences max = store(backend, ADMITTED);
        expect(ArithmeticException.class, () -> max.preferences("stats").edit().putBoolean("x", true).apply());
        assertEquals(Long.MAX_VALUE, max.revision()); assertEquals(0, backend.writes);
    }
    @Test public void listenerRunsAfterAckAndFailureCannotReclassifyCommit() {
        Fake backend = new Fake(); AtomicBoolean guarded = new AtomicBoolean();
        InternalRuntimePreferences store = store(backend, operation -> { guarded.set(true); try { operation.run(); } finally { guarded.set(false); } });
        SharedPreferences prefs = store.preferences("stats"); AtomicInteger notifications = new AtomicInteger();
        prefs.registerOnSharedPreferenceChangeListener((p, k) -> { assertFalse(guarded.get()); assertEquals(1, backend.record.revision); assertEquals(3, p.getInt(k, 0)); notifications.incrementAndGet(); });
        prefs.registerOnSharedPreferenceChangeListener((p, k) -> { throw new IllegalStateException("Observer only"); });
        assertTrue(prefs.edit().putInt("count", 3).commit()); assertEquals(1, notifications.get()); assertEquals(1, store.revision());
    }
    @Test public void removeClearNullAndNoopRetainSharedPreferenceTypes() {
        Fake backend = new Fake(); InternalRuntimePreferences store = store(backend, ADMITTED); SharedPreferences prefs = store.preferences("stats");
        prefs.edit().clear().remove("absent").apply(); assertEquals(0, store.revision()); assertFalse(store.lastReceipt().changed);
        prefs.edit().putString("s", "x").putStringSet("set", Set.of("x")).putInt("i", 1).commit();
        prefs.edit().putString("s", null).putStringSet("set", null).apply(); assertEquals(Map.of("i", 1), prefs.getAll());
        prefs.edit().clear().putBoolean("b", false).apply(); assertEquals(Map.of("b", false), prefs.getAll());
        long previous = store.revision(); prefs.edit().putBoolean("b", false).apply();
        assertEquals(previous, store.revision()); assertFalse(store.lastReceipt().changed);
        prefs.edit().remove("b").apply(); assertTrue(prefs.getAll().isEmpty());
    }
    @Test public void lanePublicationRunsOnlyAfterOuterAckUnderGuardBeforeListeners() {
        Fake backend = new Fake(); AtomicBoolean guarded = new AtomicBoolean();
        InternalRuntimePreferences store = store(backend, operation -> { guarded.set(true); try { operation.run(); } finally { guarded.set(false); } });
        SharedPreferences prefs = store.preferences("stats"); List<String> events = new ArrayList<>();
        expect(IllegalStateException.class, () -> store.afterCommit(() -> { }));
        prefs.registerOnSharedPreferenceChangeListener((p, k) -> { assertFalse(guarded.get()); assertEquals(List.of("first", "second"), events); events.add("listener"); });
        store.transaction(() -> {
            store.afterCommit(() -> {
                assertTrue(guarded.get()); assertEquals(1, backend.record.revision); assertEquals(1, store.revision());
                assertEquals(4, prefs.getInt("count", 0)); events.add("first");
            });
            prefs.edit().putInt("count", 4).apply();
            store.transaction(() -> store.afterCommit(() -> events.add("second")));
            assertTrue(events.isEmpty());
        });
        assertEquals(List.of("first", "second", "listener"), events);
    }
    @Test public void lanePublicationFailureRetainsCommittedReceiptAndFences() {
        Fake backend = new Fake(); InternalRuntimePreferences store = store(backend, ADMITTED); SharedPreferences prefs = store.preferences("stats");
        InternalRuntimePreferences.PersistenceException failure = expect(InternalRuntimePreferences.PersistenceException.class,
                () -> store.transaction(() -> {
                    prefs.edit().putInt("count", 1).apply();
                    store.afterCommit(() -> prefs.edit().putInt("count", 2).apply());
                }));
        assertEquals(InternalRuntimePreferences.Outcome.COMMITTED, failure.outcome);
        assertNotNull(failure.committedReceipt); assertEquals(1, failure.committedReceipt.revision);
        assertEquals(1, backend.record.namespaces().get("stats").get("count")); assertEquals(1, backend.writes);
        expect(InternalRuntimePreferences.PersistenceException.class, prefs::getAll);
    }
    @Test public void failedOuterTransactionDiscardsLanePublication() {
        Fake backend = new Fake(); backend.outcome = InternalRuntimePreferences.Outcome.UNCERTAIN;
        InternalRuntimePreferences store = store(backend, ADMITTED); AtomicInteger published = new AtomicInteger();
        expect(InternalRuntimePreferences.PersistenceException.class, () -> store.transaction(() -> {
            store.afterCommit(published::incrementAndGet); store.preferences("stats").edit().putInt("count", 1).apply();
        }));
        assertEquals(0, published.get());
    }
    @Test public void reusedEditorDoesNotReplayClearOverLaterAcknowledgedValues() {
        Fake backend = new Fake(); InternalRuntimePreferences store = store(backend, ADMITTED); SharedPreferences prefs = store.preferences("stats");
        SharedPreferences.Editor edit = prefs.edit().clear().putInt("first", 1); edit.apply();
        prefs.edit().putInt("second", 2).commit(); edit.putInt("third", 3).commit();
        assertEquals(Map.of("first", 1, "second", 2, "third", 3), prefs.getAll());
    }
    @Test public void crossNamespaceSnapshotIsCoherentAndStagedCopiesRemainImmutable() {
        Fake backend = new Fake(); InternalRuntimePreferences store = store(backend, ADMITTED);
        InternalRuntimeRecord before = store.snapshot();
        store.transaction(() -> {
            store.preferences("stats").edit().putInt("count", 1).apply();
            store.preferences("usage").edit().putLong("millis", 9L).apply();
            InternalRuntimeRecord staged = store.snapshot();
            assertEquals(0, staged.revision); assertEquals(0, before.revision); assertTrue(before.namespaces().isEmpty());
            assertEquals(Map.of("stats", Map.of("count", 1), "usage", Map.of("millis", 9L)), staged.namespaces());
            expect(UnsupportedOperationException.class, () -> staged.namespaces().get("stats").put("count", 4));
            store.preferences("stats").edit().putInt("count", 2).apply();
            assertEquals(1, staged.namespaces().get("stats").get("count"));
        });
        InternalRuntimeRecord after = store.snapshot(); assertEquals(1, after.revision);
        assertEquals(2, after.namespaces().get("stats").get("count")); assertTrue(before.namespaces().isEmpty());
    }
    private static InternalRuntimePreferences store(InternalRuntimePreferences.Backend backend, InternalRuntimePreferences.WriteGuard guard) {
        return new InternalRuntimePreferences(backend, guard, WORKER);
    }
    private static FileInternalRuntimeBackend provision(InternalStorageScope scope) throws IOException { return FileInternalRuntimeBackend.provisionNew(scope, WORKER); }
    private static FileInternalRuntimeBackend open(InternalStorageScope scope) throws IOException { return FileInternalRuntimeBackend.openExisting(scope, WORKER); }
    private static long countFiles(Path path) throws IOException { try (java.util.stream.Stream<Path> stream = Files.list(path)) { return stream.count(); } }
    private static void directory(DirectoryCheck check) throws Exception {
        Path parent = Files.createTempDirectory("internal-runtime-data-").toRealPath();
        try { check.run(new InternalStorageScope(Files.createDirectory(parent.resolve(InternalStorageScope.TARGET_ID))), parent); }
        finally { try (java.util.stream.Stream<Path> paths = Files.walk(parent)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); } }
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for fixture"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
    private static <T extends Throwable> T expect(Class<T> type, Check check) {
        try { check.run(); } catch (Throwable failure) { if (type.isInstance(failure)) return type.cast(failure); throw new AssertionError("Unexpected failure", failure); }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    private static class Forward implements InternalRuntimePreferences.Backend {
        final InternalRuntimePreferences.Backend backend;
        Forward(InternalRuntimePreferences.Backend backend) { this.backend = backend; }
        @Override public InternalRuntimeRecord read() throws Exception { return backend.read(); }
        @Override public InternalRuntimePreferences.Outcome compareAndSet(InternalRuntimeRecord a, InternalRuntimeRecord b) throws Exception { return backend.compareAndSet(a, b); }
        @Override public void close() { backend.close(); }
    }
    private static final class Fake implements InternalRuntimePreferences.Backend {
        InternalRuntimeRecord record = new InternalRuntimeRecord(UUID.randomUUID().toString(), 0, Map.of());
        InternalRuntimePreferences.Outcome outcome = InternalRuntimePreferences.Outcome.COMMITTED;
        Runnable beforeWrite = () -> { };
        int writes;
        volatile boolean closed;
        @Override public InternalRuntimeRecord read() { if (closed) throw new IllegalStateException("Closed"); return record; }
        @Override public InternalRuntimePreferences.Outcome compareAndSet(InternalRuntimeRecord expected, InternalRuntimeRecord next) {
            assertEquals(record, expected); writes++; beforeWrite.run();
            if (outcome == InternalRuntimePreferences.Outcome.COMMITTED || outcome == InternalRuntimePreferences.Outcome.UNCERTAIN) record = next;
            return outcome;
        }
        @Override public void close() { closed = true; }
    }
}
