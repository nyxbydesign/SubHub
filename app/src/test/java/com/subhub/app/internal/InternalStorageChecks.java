package com.subhub.app.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Pure contracts shared by canonical JUnit and the SDK-independent manual runner. */
public final class InternalStorageChecks {
    private InternalStorageChecks() { }
    private interface Check { void run() throws Exception; }
    private static final InternalProfile PROFILE = new InternalProfile("Internal Sub", "Independent", "", "", 0);
    private static final Map<String, Map<String, Object>> CONFIG = Map.of("censor", Map.of(
            "enabled", true, "integer", 4, "long", 5L, "float", 0.25f, "text", "hello", "set", Set.of("a", "b")));

    public static void targetNeverAliasesLocalOrPeer() {
        check(!"local".equals(InternalStorageScope.TARGET_ID), "Internal identity aliases LOCAL");
        expect(IllegalArgumentException.class, () -> new InternalSubRecord("local", UUID.randomUUID().toString(),
                0, 0, false, PROFILE, CONFIG));
        expect(IllegalArgumentException.class, () -> new InternalSubRecord("peer", UUID.randomUUID().toString(),
                0, 0, false, PROFILE, CONFIG));
    }
    public static void binaryCodecPreservesAllPreferenceTypes() {
        InternalSubRecord source = InternalSubStore.initial(PROFILE, CONFIG);
        InternalSubRecord result = InternalRecordCodec.decode(InternalRecordCodec.encode(source));
        check(result.equals(source), "Typed round trip mismatch");
        expect(UnsupportedOperationException.class, () -> result.configuration().get("censor").put("text", "changed"));
        @SuppressWarnings("unchecked") Set<String> values = (Set<String>) result.configuration().get("censor").get("set");
        expect(UnsupportedOperationException.class, () -> values.add("changed"));
    }
    public static void binaryCodecRejectsCorruptionTruncationAndExtraBytes() {
        byte[] bytes = InternalRecordCodec.encode(InternalSubStore.initial(PROFILE, CONFIG));
        byte[] corrupt = bytes.clone(); corrupt[corrupt.length / 2] ^= 1;
        expect(IllegalArgumentException.class, () -> InternalRecordCodec.decode(corrupt));
        for (int length : new int[]{0, 5, 40, bytes.length - 1})
            expect(IllegalArgumentException.class, () -> InternalRecordCodec.decode(Arrays.copyOf(bytes, length)));
        expect(IllegalArgumentException.class, () -> InternalRecordCodec.decode(Arrays.copyOf(bytes, bytes.length + 1)));
        expect(IllegalArgumentException.class, () -> InternalRecordCodec.decode(new byte[InternalRecordCodec.MAX_BYTES + 1]));
    }
    public static void invalidFieldsAndUnicodeFailBeforeAcknowledgement() {
        expect(IllegalArgumentException.class, () -> new InternalProfile("x", "", "", "", 131));
        expect(IllegalArgumentException.class, () -> InternalSubStore.initial(PROFILE,
                Map.of("censor", Map.of("bad", Double.NaN))));
        expect(IllegalArgumentException.class, () -> InternalSubStore.initial(PROFILE,
                Map.of("censor", Map.of("../escape", true))));
        expect(IllegalArgumentException.class, () -> InternalRecordCodec.encode(InternalSubStore.initial(
                new InternalProfile("\ud800", "", "", "", 0), CONFIG)));
    }
    public static void visibilityRotatesGenerationAndRetainsData() {
        Fake backend = new Fake(); InternalSubStore store = new InternalSubStore(backend);
        InternalSubRecord initial = store.read(), shown = store.setVisible(initial, true), hidden = store.setVisible(shown, false);
        InternalSubRecord restored = store.setVisible(hidden, true);
        check(!initial.visible && shown.visible && !hidden.visible && restored.visible, "Visibility mismatch");
        check(Set.of(initial.generation, shown.generation, hidden.generation, restored.generation).size() == 4,
                "Generation reused");
        check(restored.profile.equals(initial.profile) && restored.configuration().equals(initial.configuration()),
                "Visibility changed owned data");
        check(restored.configurationRevision == 0 && restored.revision == 3, "Visibility changed config revision");
    }
    public static void configurationSavesAdvanceWithoutWaitingForNextSession() {
        Fake backend = new Fake(); InternalSubStore store = new InternalSubStore(backend);
        InternalSubRecord initial = store.read();
        Map<String, Map<String, Object>> next = Map.of("censor", Map.of("enabled", false));
        InternalSubRecord saved = store.saveConfiguration(initial, next);
        check(saved.configurationRevision == 1 && saved.revision == 1, "Configuration not acknowledged");
        check(saved.generation.equals(initial.generation), "Config rotated profile generation");
        check(store.read().configuration().equals(next), "Configuration waits for a session");
    }
    public static void staleGenerationAndFullRecordWritesConflict() {
        Fake backend = new Fake(); InternalSubStore store = new InternalSubStore(backend);
        InternalSubRecord initial = store.read(); store.setVisible(initial, true);
        expect(InternalSubStore.PersistenceException.class, () -> store.saveProfile(initial,
                new InternalProfile("stale", "", "", "", 0)));
        InternalSubRecord current = store.read();
        InternalSubRecord forged = new InternalSubRecord(current.targetId, current.generation,
                current.revision, current.configurationRevision, current.visible, PROFILE, Map.of());
        expect(InternalSubStore.PersistenceException.class, () -> store.setVisible(forged, false));
        check(store.read().equals(current), "Stale snapshot changed data");
    }
    public static void uncertainCommitNeverPublishesEvenIfBackendMemoryChanged() {
        Fake backend = new Fake(); InternalSubStore store = new InternalSubStore(backend);
        AtomicInteger published = new AtomicInteger(); store.addListener((a, b) -> published.incrementAndGet());
        InternalSubRecord initial = store.read(); backend.outcome = InternalSubStore.Outcome.UNCERTAIN;
        expect(InternalSubStore.PersistenceException.class, () -> store.setVisible(initial, true));
        check(backend.record.visible, "Fixture did not expose failed-write memory");
        check(published.get() == 0, "Uncertain revision was published");
        expect(InternalSubStore.PersistenceException.class, store::read);
        expect(InternalSubStore.PersistenceException.class, () -> store.setVisible(initial, false));
    }
    public static void definiteFailurePublishesNothingAndPreservesReadableState() {
        Fake backend = new Fake(); InternalSubStore store = new InternalSubStore(backend);
        AtomicInteger published = new AtomicInteger(); store.addListener((a, b) -> published.incrementAndGet());
        InternalSubRecord initial = store.read(); backend.outcome = InternalSubStore.Outcome.NOT_COMMITTED;
        expect(InternalSubStore.PersistenceException.class, () -> store.setVisible(initial, true));
        check(store.read().equals(initial) && published.get() == 0, "Known failed write was applied");
    }
    public static void listenersRunAfterTransactionsAndDoNotReclassifySavedData() {
        Fake backend = new Fake(); InternalSubStore store = new InternalSubStore(backend);
        AtomicInteger published = new AtomicInteger();
        store.addListener((a, b) -> {
            check(!Thread.holdsLock(store) && !backend.writing, "Listener under storage lock");
            check(store.read().equals(b), "Listener before committed record"); published.incrementAndGet();
        });
        store.addListener((a, b) -> { throw new IllegalStateException("Fixture observer failure"); });
        InternalSubRecord saved = store.setVisible(store.read(), true);
        check(saved.visible && published.get() == 1, "Observer failure hid acknowledged save");
    }
    public static void exhaustedAndInvalidTransitionsFailClosed() {
        InternalSubRecord max = new InternalSubRecord(InternalStorageScope.TARGET_ID, UUID.randomUUID().toString(),
                Long.MAX_VALUE, 0, false, PROFILE, CONFIG);
        Fake backend = new Fake(); backend.record = max; InternalSubStore store = new InternalSubStore(backend);
        expect(ArithmeticException.class, () -> store.setVisible(max, true));
        check(store.read().equals(max), "Exhausted revision changed data");
        InternalSubRecord old = InternalSubStore.initial(PROFILE, CONFIG);
        InternalSubRecord bad = new InternalSubRecord(old.targetId, old.generation, 1, 1, false, PROFILE, CONFIG);
        expect(IllegalArgumentException.class, () -> InternalSubRecord.requireSuccessor(old, bad));
    }
    public static void semanticValidatorPreventsVisibilityOverCorruptConfiguration() {
        Fake backend = new Fake();
        InternalSubStore store = new InternalSubStore(backend, record -> {
            if (!record.configuration().equals(CONFIG)) throw new IllegalArgumentException("Invalid fixture schema");
        });
        InternalSubRecord good = store.read();
        backend.record = new InternalSubRecord(good.targetId, good.generation, 0, 0, false, PROFILE, Map.of());
        expect(InternalSubStore.PersistenceException.class, () -> store.setVisible(good, true));
        check(!backend.record.visible, "Corrupt data was made available");
        expect(InternalSubStore.PersistenceException.class, store::read);
    }
    public static void noOpWritesDoNotInventAcknowledgedRevisions() {
        Fake backend = new Fake(); InternalSubStore store = new InternalSubStore(backend);
        AtomicInteger acknowledgements = new AtomicInteger(); store.addListener((a, b) -> acknowledgements.incrementAndGet());
        InternalSubRecord initial = store.read();
        check(store.setVisible(initial, false).equals(initial), "No-op visibility changed");
        check(store.saveProfile(initial, PROFILE).equals(initial), "No-op profile changed");
        check(store.saveConfiguration(initial, CONFIG).equals(initial), "No-op config changed");
        check(acknowledgements.get() == 0, "No-op published revision");
    }
    public static void totalEncodingSizeIsBoundedDuringWrites() {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < 3; i++) values.put("large" + i, "x".repeat(500_000));
        InternalSubRecord oversized = InternalSubStore.initial(PROFILE, Map.of("fixture", values));
        expect(IllegalArgumentException.class, () -> InternalRecordCodec.encode(oversized));
    }
    public static void diskRoundTripRetainsOwnedNamespacesAndNeverTouchesSibling() throws Exception {
        withDirectory(parent -> {
            Path sibling = parent.resolve("physical-sentinel"); Files.writeString(sibling, "untouched");
            FileInternalSubBackend backend = FileInternalSubBackend.createNew(parent.toFile(), PROFILE, CONFIG);
            InternalSubStore store = new InternalSubStore(backend);
            for (InternalStorageScope.Area area : InternalStorageScope.Area.values())
                Files.writeString(backend.scope().file(area, "owned").toPath(), area.name());
            InternalSubRecord shown = store.setVisible(store.read(), true);
            InternalSubRecord hidden = store.setVisible(shown, false);
            InternalSubRecord restored = store.setVisible(hidden, true);
            FileInternalSubBackend reopened = FileInternalSubBackend.openExisting(parent.toFile());
            check(reopened.read().equals(restored), "Disk round trip mismatch");
            for (InternalStorageScope.Area area : InternalStorageScope.Area.values())
                check(Files.readString(reopened.scope().file(area, "owned").toPath()).equals(area.name()), "Namespace erased");
            check(Files.readString(sibling).equals("untouched"), "Physical sibling changed");
        });
    }
    public static void missingEstablishedRecordAndLockNeverReseed() throws Exception {
        withDirectory(parent -> {
            FileInternalSubBackend backend = FileInternalSubBackend.createNew(parent.toFile(), PROFILE, CONFIG);
            Files.delete(backend.scope().root().resolve("profile.configuration"));
            expect(IOException.class, backend::read);
            expect(IOException.class, () -> FileInternalSubBackend.openExisting(parent.toFile()));
            expect(IOException.class, () -> FileInternalSubBackend.createNew(parent.toFile(), PROFILE, CONFIG));
        });
        withDirectory(parent -> {
            FileInternalSubBackend backend = FileInternalSubBackend.createNew(parent.toFile(), PROFILE, CONFIG);
            Files.delete(backend.scope().root().resolve("storage.lock"));
            expect(IOException.class, backend::read);
        });
    }
    public static void corruptEstablishedRecordNeverReseeds() throws Exception {
        withDirectory(parent -> {
            FileInternalSubBackend backend = FileInternalSubBackend.createNew(parent.toFile(), PROFILE, CONFIG);
            Files.write(backend.scope().root().resolve("profile.configuration"), new byte[]{1, 2, 3});
            expect(IOException.class, backend::read);
            expect(IOException.class, () -> FileInternalSubBackend.openExisting(parent.toFile()));
            expect(IOException.class, () -> FileInternalSubBackend.createNew(parent.toFile(), PROFILE, CONFIG));
        });
    }
    public static void pathsAndSymlinksCannotAliasSiblingData() throws Exception {
        withDirectory(parent -> {
            FileInternalSubBackend backend = FileInternalSubBackend.createNew(parent.toFile(), PROFILE, CONFIG);
            InternalStorageScope scope = backend.scope();
            expect(IllegalArgumentException.class, () -> scope.file(InternalStorageScope.Area.ASSETS, "../physical"));
            expect(IllegalArgumentException.class, () -> scope.file(InternalStorageScope.Area.ASSETS, "/physical"));
            Path sibling = Files.createDirectory(parent.resolve("physical"));
            Files.createSymbolicLink(scope.root().resolve("history"), sibling);
            expect(IOException.class, () -> scope.directory(InternalStorageScope.Area.HISTORY));
            Files.createSymbolicLink(scope.directory(InternalStorageScope.Area.ASSETS).toPath().resolve("link"), sibling);
            expect(IOException.class, () -> scope.file(InternalStorageScope.Area.ASSETS, "link"));
        });
    }
    public static void concurrentBackendsCompareTheEntireExpectedRecord() throws Exception {
        withDirectory(parent -> {
            FileInternalSubBackend first = FileInternalSubBackend.createNew(parent.toFile(), PROFILE, CONFIG);
            FileInternalSubBackend second = FileInternalSubBackend.openExisting(parent.toFile());
            InternalSubRecord initial = first.read();
            InternalSubRecord a = new InternalSubRecord(initial.targetId, UUID.randomUUID().toString(), 1, 0, true, PROFILE, CONFIG);
            InternalSubRecord b = new InternalSubRecord(initial.targetId, initial.generation, 1, 0, false,
                    new InternalProfile("Changed", "", "", "", 0), CONFIG);
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<InternalSubStore.Outcome> one = new AtomicReference<>(), two = new AtomicReference<>();
            Thread t1 = new Thread(() -> { await(start); one.set(first.compareAndSet(initial, a)); });
            Thread t2 = new Thread(() -> { await(start); two.set(second.compareAndSet(initial, b)); });
            t1.start(); t2.start(); start.countDown(); t1.join(10000); t2.join(10000);
            check(!t1.isAlive() && !t2.isAlive(), "Storage CAS did not finish");
            check(Set.of(one.get(), two.get()).equals(Set.of(InternalSubStore.Outcome.COMMITTED,
                    InternalSubStore.Outcome.CONFLICT)), "CAS was not exclusive");
            InternalSubRecord saved = first.read(); check(saved.equals(a) || saved.equals(b), "Mixed transaction");
        });
    }
    public static void trustedPhysicalParentAliasIsCanonicalizedOnlyAtBoundary() throws Exception {
        withDirectory(fixture -> {
            Path physical = Files.createDirectory(fixture.resolve("physical"));
            Path alias = Files.createSymbolicLink(fixture.resolve("trusted-platform-alias"), physical);
            FileInternalSubBackend backend = FileInternalSubBackend.createNew(alias.toFile(), PROFILE, CONFIG);
            check(backend.scope().root().equals(physical.resolve(InternalStorageScope.TARGET_ID)), "Physical parent was not canonicalized");
            check(FileInternalSubBackend.openExisting(physical.toFile()).read().equals(backend.read()), "Alias opened different data");
            check(FileInternalSubBackend.openExisting(alias.toFile()).read().equals(backend.read()), "Trusted alias could not reopen");
            Files.writeString(backend.scope().file(InternalStorageScope.Area.TASKS, "owned").toPath(), "safe");
        });
    }
    public static void ownedRootSymlinkIsNeverCanonicalizedIntoSiblingData() throws Exception {
        withDirectory(fixture -> {
            Path trusted = Files.createDirectory(fixture.resolve("trusted"));
            Path sibling = Files.createDirectory(fixture.resolve("sibling"));
            FileInternalSubBackend real = FileInternalSubBackend.createNew(sibling.toFile(), PROFILE, CONFIG);
            Files.createSymbolicLink(trusted.resolve(InternalStorageScope.TARGET_ID), real.scope().root());
            expect(IOException.class, () -> FileInternalSubBackend.openExisting(trusted.toFile()));
            expect(IOException.class, () -> FileInternalSubBackend.createNew(trusted.toFile(), PROFILE, CONFIG));
            check(real.read().revision == 0, "Owned symlink changed sibling data");
        });
    }
    public static void lazyOwnedDirectoryCreationIsConcurrentAndRejectsNonDirectories() throws Exception {
        withDirectory(parent -> {
            FileInternalSubBackend backend = FileInternalSubBackend.createNew(parent.toFile(), PROFILE, CONFIG);
            InternalStorageScope scope = backend.scope();
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread[] workers = new Thread[48];
            for (int i = 0; i < workers.length; i++) {
                workers[i] = new Thread(() -> {
                    await(start);
                    try {
                        for (int j = 0; j < 8; j++) {
                            Path tasks = scope.directory(InternalStorageScope.Area.TASKS).toPath();
                            check(tasks.equals(scope.root().resolve("tasks")), "Wrong owned task path");
                        }
                    } catch (Throwable error) { failure.compareAndSet(null, error); }
                });
                workers[i].start();
            }
            start.countDown();
            for (Thread worker : workers) { worker.join(10000); check(!worker.isAlive(), "Lazy directory creation hung"); }
            if (failure.get() != null) throw new AssertionError("Concurrent directory creation failed", failure.get());
            Files.writeString(scope.root().resolve("history"), "not a directory");
            expect(IOException.class, () -> scope.directory(InternalStorageScope.Area.HISTORY));
        });
    }

    private static final class Fake implements InternalSubStore.Backend {
        InternalSubRecord record = InternalSubStore.initial(PROFILE, CONFIG);
        InternalSubStore.Outcome outcome = InternalSubStore.Outcome.COMMITTED;
        boolean writing;
        @Override public InternalSubRecord read() { return record; }
        @Override public InternalSubStore.Outcome compareAndSet(InternalSubRecord expected, InternalSubRecord next) {
            writing = true;
            try {
                if (!record.equals(expected)) return InternalSubStore.Outcome.CONFLICT;
                if (outcome == InternalSubStore.Outcome.COMMITTED || outcome == InternalSubStore.Outcome.UNCERTAIN) record = next;
                return outcome;
            } finally { writing = false; }
        }
    }
    private interface DirectoryCheck { void run(Path root) throws Exception; }
    private static void withDirectory(DirectoryCheck check) throws Exception {
        Path root = Files.createTempDirectory("subhub-internal-storage-");
        try { check.run(root); }
        finally {
            try (java.util.stream.Stream<Path> files = Files.walk(root)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
    private static void await(CountDownLatch start) {
        try { start.await(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private static void expect(Class<? extends Throwable> type, Check check) {
        try { check.run(); }
        catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("Expected " + type.getSimpleName() + ", got " + failure, failure);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    public static void main(String[] args) throws Exception {
        int count = 0;
        for (java.lang.reflect.Method method : InternalStorageChecks.class.getDeclaredMethods())
            if (java.lang.reflect.Modifier.isPublic(method.getModifiers()) && !method.getName().equals("main")) {
                try { method.invoke(null); }
                catch (java.lang.reflect.InvocationTargetException failed) { throw new AssertionError(method.getName(), failed.getCause()); }
                System.out.println("PASS " + method.getName()); count++;
            }
        System.out.println("PASS " + count + " internal storage checks");
    }
}
