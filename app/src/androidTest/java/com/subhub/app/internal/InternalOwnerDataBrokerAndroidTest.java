package com.subhub.app.internal;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.people.InternalSubData;
import com.subhub.app.tasks.TaskNoticeLedger;
import com.subhub.app.runtime.*;
import com.subhub.app.stats.*;
import com.subhub.app.penance.InternalPenanceRules;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real Android/file adapter coverage. These fixtures own all bytes and registry preferences. */
@RunWith(AndroidJUnit4.class)
public final class InternalOwnerDataBrokerAndroidTest {
    @Test public void immutableProjectionAndRevisionHintWaitForOuterAck() throws Exception {
        try (Fixture f = new Fixture()) {
            assertEquals(-1L, f.broker.acknowledgedRevisionHint(f.data.acknowledged().generation));
            f.broker.initialize().get(10, TimeUnit.SECONDS); f.visible(true);
            String generation = f.data.acknowledged().generation;
            long before = f.broker.acknowledgedRevisionHint(generation); assertEquals(0L, before);
            Field opened = InternalOwnerDataBroker.class.getDeclaredField("observedHandle"); opened.setAccessible(true);
            Object handle = opened.get(f.broker); Field prefs = handle.getClass().getDeclaredField("preferences"); prefs.setAccessible(true);
            InternalRuntimePreferences preferences = (InternalRuntimePreferences) prefs.get(handle);
            java.util.concurrent.atomic.AtomicLong duringPublication = new java.util.concurrent.atomic.AtomicLong(-2);
            preferences.preferences("task_notices").registerOnSharedPreferenceChangeListener((ignored, key) ->
                    duringPublication.set(f.broker.acknowledgedRevisionHint(generation)));
            TaskNoticeLedger.Receipt notice = new TaskNoticeLedger.Receipt(generation,
                    TaskNoticeLedger.Audience.SUB_TASKS, "projection:receipt", System.currentTimeMillis());
            f.broker.submitTask(generation, access -> access.admitNotice(notice)).get(10, TimeUnit.SECONDS);
            assertEquals(before, duringPublication.get());
            assertEquals(before + 1, f.broker.acknowledgedRevisionHint(generation));
            OwnerObservation observed = OwnerObservation.capture(System.currentTimeMillis());
            Set<String> packages = new HashSet<>(Set.of("example.package"));
            CountDownLatch release = new CountDownLatch(1);
            f.data.execute(() -> { try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
            CompletableFuture<com.subhub.app.people.InternalRuntimePresentationSnapshot> pending;
            try {
                pending = f.broker.readProjection(generation, observed, packages);
                packages.clear(); packages.add("different.package");
            } finally { release.countDown(); }
            com.subhub.app.people.InternalRuntimePresentationSnapshot projection = pending.get(10, TimeUnit.SECONDS);
            assertSame(observed, projection.observed); assertEquals(f.data.acknowledged(), projection.record);
            assertEquals(before + 1, projection.runtime.revision); assertEquals(projection.runtime.revision, projection.accounting.revision);
            assertEquals(1, projection.runtime.namespaces().get("task_notices").size());
            assertTrue(projection.usage.containsKey("example.package"));
            assertThrows(UnsupportedOperationException.class, () -> projection.usage.clear());
            assertThrows(UnsupportedOperationException.class, () -> projection.runtime.namespaces().get("task_notices").clear());
            String differentZone = observed.zone.equals("UTC") ? "Europe/Berlin" : "UTC";
            assertThrows(ExecutionException.class, () -> f.broker.readProjection(generation,
                    new OwnerObservation(observed.at, differentZone), Set.of()).get(10, TimeUnit.SECONDS));
            assertEquals(before + 1, f.broker.acknowledgedRevisionHint(generation));
            f.visible(false); assertEquals(-1L, f.broker.acknowledgedRevisionHint(generation));
            assertThrows(ExecutionException.class, () -> f.broker.readProjection(generation, observed, Set.of()).get(10, TimeUnit.SECONDS));
        }
    }
    @Test public void explicitHiddenProvisionAndOneBrokerPreserveSingleAcknowledgedNoticeRecord() throws Exception {
        try (Fixture f = new Fixture()) {
            assertFalse(f.data.acknowledged().visible);
            f.broker.initialize().get(10, TimeUnit.SECONDS);
            assertEquals("ready", f.context.registry.getString("runtime_v1", ""));
            assertSame(f.broker, InternalOwnerDataBroker.attach(f.context, f.data,
                    OwnerDataProvisioning.Decision.OPEN_EXISTING, record -> record.visible));
            f.visible(true); String generation = f.data.acknowledged().generation;
            TaskNoticeLedger.Receipt expected = new TaskNoticeLedger.Receipt(generation,
                    TaskNoticeLedger.Audience.SUB_TASKS, "fixture:one", System.currentTimeMillis());
            TaskNoticeLedger.Receipt saved = f.broker.submitTask(generation, access -> {
                assertTrue(access.resumeAfterMillis() > 0); return access.admitNotice(expected);
            }).get(10, TimeUnit.SECONDS);
            assertEquals(expected, saved);
            long observed = f.broker.snapshot(generation, System.currentTimeMillis()).get(10, TimeUnit.SECONDS).revision;
            InternalRuntimeRecord disk = InternalRuntimeRecord.decode(Files.readAllBytes(f.root.resolve(
                    InternalStorageScope.TARGET_ID).resolve("runtime-data/data.record")));
            assertEquals(observed, disk.revision);
            assertEquals(1, disk.namespaces().get("task_notices").size());
            assertEquals(0, f.context.unrelatedPreferenceRequests.get());
        }
    }
    @Test public void hiddenGenerationCannotScheduleButRetiredExactNoticeRemainsForCancellation() throws Exception {
        try (Fixture f = new Fixture()) {
            f.broker.initialize().get(10, TimeUnit.SECONDS); f.visible(true);
            String generation = f.data.acknowledged().generation;
            TaskNoticeLedger.Receipt notice = new TaskNoticeLedger.Receipt(generation,
                    TaskNoticeLedger.Audience.DOM_REVIEW, "fixture:review", System.currentTimeMillis());
            f.broker.submitTask(generation, access -> access.admitNotice(notice)).get(10, TimeUnit.SECONDS);
            f.visible(false);
            assertThrows(ExecutionException.class, () -> f.broker.submitTask(generation, access -> {
                fail("Hidden task action"); return null;
            }).get(10, TimeUnit.SECONDS));
            List<TaskNoticeLedger.Receipt> retired = f.broker.submitRetiredTaskNotices(
                    InternalOwnerDataBroker.RetiredTaskNotices::list).get(10, TimeUnit.SECONDS);
            assertEquals(List.of(notice), retired);
            // This fixture verifies the typed ACK seam, not the platform notification cancellation adapter.
            f.broker.submitRetiredTaskNotices(access -> { access.removeAfterCancellation(retired.get(0)); return null; })
                    .get(10, TimeUnit.SECONDS);
            assertTrue(f.broker.submitRetiredTaskNotices(InternalOwnerDataBroker.RetiredTaskNotices::list)
                    .get(10, TimeUnit.SECONDS).isEmpty());
            assertEquals(0, f.context.unrelatedPreferenceRequests.get());
        }
    }
    @Test public void retiredCleanupRefusesCurrentNoticeAndEscapedTaskAccessHasNoAuthority() throws Exception {
        try (Fixture f = new Fixture()) {
            f.broker.initialize().get(10, TimeUnit.SECONDS); f.visible(true);
            String generation = f.data.acknowledged().generation;
            TaskNoticeLedger.Receipt notice = new TaskNoticeLedger.Receipt(generation,
                    TaskNoticeLedger.Audience.SUB_TASKS, "fixture:current", System.currentTimeMillis());
            InternalOwnerDataBroker.TaskAccess escaped = f.broker.submitTask(generation, access -> {
                access.admitNotice(notice); return access;
            }).get(10, TimeUnit.SECONDS);
            assertThrows(SecurityException.class, escaped::requireCurrent);
            assertThrows(ExecutionException.class, () -> f.broker.submitRetiredTaskNotices(access -> {
                access.removeAfterCancellation(notice); return null;
            }).get(10, TimeUnit.SECONDS));
            assertEquals(List.of(notice), f.broker.submitTask(generation, InternalOwnerDataBroker.TaskAccess::notices)
                    .get(10, TimeUnit.SECONDS));
        }
    }
    @Test public void staleQueuedGenerationCannotBorrowReaddedTaskAuthority() throws Exception {
        try (Fixture f = new Fixture()) {
            f.broker.initialize().get(10, TimeUnit.SECONDS); f.visible(true);
            String old = f.data.acknowledged().generation;
            CompletableFuture<Void> hidden = f.mutate(() -> f.data.store.setVisible(f.data.store.read(), false, 2000));
            CompletableFuture<Void> readded = f.mutate(() -> f.data.store.setVisible(f.data.store.read(), true, 3000));
            CompletableFuture<Long> stale = f.broker.submitTask(old, InternalOwnerDataBroker.TaskAccess::resumeAfterMillis);
            hidden.get(10, TimeUnit.SECONDS); readded.get(10, TimeUnit.SECONDS);
            assertThrows(ExecutionException.class, () -> stale.get(10, TimeUnit.SECONDS));
            assertEquals(3000L, (long) f.broker.submitTask(f.data.acknowledged().generation,
                    InternalOwnerDataBroker.TaskAccess::resumeAfterMillis).get(10, TimeUnit.SECONDS));
        }
    }
    @Test public void retainedSessionFacadeCannotEscapeIntoTaskCleanupOrBorrowAnotherStart() throws Exception {
        try (Fixture f = new Fixture()) {
            f.broker.initialize().get(10, TimeUnit.SECONDS); f.visible(true);
            RuntimeFixture runtime = new RuntimeFixture(f.data.acknowledged().generation, f.broker.runtimeWorker());
            InternalOwnerDataBroker.Session session = f.broker.bindRuntime(runtime.lease.identity, runtime.queue);
            assertThrows(IllegalStateException.class, session::acknowledgedSessionStamp);
            long now = System.currentTimeMillis();
            StatsRepository.NativeSession nativeSession = new StatsRepository.NativeSession(runtime.lease.identity.session, now, "UTC");
            java.util.concurrent.atomic.AtomicReference<InternalOwnerDataBroker.SessionObservation> start = new java.util.concurrent.atomic.AtomicReference<>();
            runtime.queue.submitStartup(runtime.lease.identity, () -> {
                start.set(session.beginSession(nativeSession));
                assertThrows(SecurityException.class, () -> session.recordWhisperImpression(new OwnerObservation(now + 1, "UTC")));
            }).get(10, TimeUnit.SECONDS);
            assertSame(start.get().stamp, session.acknowledgedSessionStamp());
            assertEquals(runtime.lease.identity, start.get().identity); runtime.activate();
            session.executeNormal(() -> {
                assertThrows(SecurityException.class, () -> session.beginSession(nativeSession));
                session.recordWhisperImpression(new OwnerObservation(now + 1, "UTC"));
            }).get(10, TimeUnit.SECONDS);
            // The former escaped-provider exploit is no longer expressible through the public API.
            for (Method method : InternalOwnerDataBroker.Session.class.getMethods()) {
                assertFalse(method.getName().equals("held") || method.getName().equals("submitNormal")
                        || method.getName().equals("observedRepository"));
                assertNotEquals(OwnerStatisticsStore.class, method.getReturnType());
                assertNotEquals(StatsRepository.class, method.getReturnType());
                assertNotEquals(SharedPreferences.class, method.getReturnType());
            }
            runtime.seal();
            assertThrows(OwnerWriteQueue.AdmissionRejected.class, () -> session.executeNormal(() -> fail("Stopped callback ran")));
            assertEquals(0, runtime.queue.unresolvedWrites());
            session.finishAndRelease(now + 2, runtime.component::close).get(10, TimeUnit.SECONDS);
            assertThrows(OwnerWriteQueue.AdmissionRejected.class, () -> session.executeNormal(() -> fail("Released callback ran")));
            assertEquals(0, runtime.queue.unresolvedWrites());
            f.visible(false);
            f.broker.submitRetiredTaskNotices(ignored -> {
                assertThrows(SecurityException.class, () -> session.recordWhisperImpression(new OwnerObservation(now + 3, "UTC")));
                return null;
            }).get(10, TimeUnit.SECONDS);
            f.visible(true);
            RuntimeFixture next = new RuntimeFixture(f.data.acknowledged().generation, f.broker.runtimeWorker());
            InternalOwnerDataBroker.Session later = f.broker.bindRuntime(next.lease.identity, next.queue);
            assertThrows(IllegalStateException.class, later::acknowledgedSessionStamp);
            next.queue.submitStartup(next.lease.identity, () -> later.beginSession(
                    new StatsRepository.NativeSession(next.lease.identity.session, now + 4, "UTC"))).get(10, TimeUnit.SECONDS);
            next.activate();
            later.executeNormal(() -> assertThrows(SecurityException.class,
                    () -> session.recordWhisperImpression(new OwnerObservation(now + 5, "UTC")))).get(10, TimeUnit.SECONDS);
            assertNotSame(start.get().stamp, later.acknowledgedSessionStamp());
            next.seal(); later.finishAndRelease(now + 6, next.component::close).get(10, TimeUnit.SECONDS);
        }
    }
    @Test public void cleanTaskCapacityRejectionPreservesExactLiveStampAndStillDrains() throws Exception {
        Composition fixture = new Composition(true); fixture.start();
        StatsRepository.SessionStamp stamp = fixture.handle.statistics.repository().observedSessionStamp();
        InternalRuntimeRecord before = fixture.memory.value; int commits = fixture.memory.commits;
        CompletableFuture<?> rejected = fixture.broker.submitTask("generation", handle -> handle.statistics.ledger(
                () -> handle.rules, () -> false).recordTaskFailure("new-occurrence", new OwnerObservation(fixture.now + 1000, "UTC"), () -> true));
        fixture.worker.all(); assertThrows(CompletionException.class, rejected::join);
        assertEquals(before, fixture.memory.value); assertEquals(commits, fixture.memory.commits);
        assertSame(stamp, fixture.handle.statistics.repository().observedSessionStamp()); assertTrue(stamp.isCurrent());
        assertEquals(0, fixture.runtime.failures.get());
        CompletableFuture<Void> normal = fixture.runtime.queue.submit(fixture.runtime.lease.identity, () -> fixture.binding.held(
                OwnedResourceRegistry.Effect.NORMAL, handle -> { handle.statistics.transaction(
                        () -> handle.statistics.repository().recordSubliminalImpression(new OwnerObservation(fixture.now + 1001, "UTC"))); return null; }));
        fixture.worker.all(); normal.join(); fixture.runtime.seal();
        CompletableFuture<Void> stop = fixture.binding.stop(handle -> { handle.statistics.finishSession(
                fixture.runtime.lease.identity.session, fixture.now + 2000); return null; }, fixture.runtime.component::close);
        fixture.worker.all(); stop.join(); CompletableFuture<Void> close = fixture.broker.closeAfterDrain();
        fixture.worker.all(); close.join(); assertTrue(fixture.memory.closed);
        assertNotNull(fixture.runtime.registry.drained(fixture.runtime.lease));
    }
    @Test public void genericUncertainPersistenceFencesProviderAndExactBoundRuntimeImmediately() throws Exception {
        Composition fixture = new Composition(false); fixture.start();
        StatsRepository.SessionStamp original = fixture.handle.statistics.repository().observedSessionStamp();
        fixture.memory.outcome = InternalRuntimePreferences.Outcome.UNCERTAIN;
        CompletableFuture<?> rejected = fixture.broker.submitTask("generation", handle -> handle.statistics.ledger(
                () -> handle.rules, () -> false).recordTaskFailure("uncertain-occurrence", new OwnerObservation(fixture.now + 1000, "UTC"), () -> true));
        fixture.worker.all(); assertThrows(CompletionException.class, rejected::join);
        assertEquals(1, fixture.runtime.failures.get()); assertFalse(original.isCurrent());
        assertThrows(OwnerWriteQueue.AdmissionRejected.class, () -> fixture.runtime.queue.submit(fixture.runtime.lease.identity, () -> fail("Fenced NORMAL")));
        CompletableFuture<?> later = fixture.broker.submitTask("generation", handle -> failValue());
        fixture.worker.all(); assertThrows(CompletionException.class, later::join);
        fixture.runtime.seal(); CompletableFuture<Void> stop = fixture.binding.stop(handle -> {
            fail("Cannot manufacture cleanup ACK after uncertain persistence"); return null;
        }, fixture.runtime.component::close); fixture.worker.all(); assertThrows(CompletionException.class, stop::join);
        CompletableFuture<Void> close = fixture.broker.closeAfterDrain(); fixture.worker.all(); assertFalse(close.isDone());
        assertFalse(fixture.memory.closed); assertNull(fixture.runtime.registry.drained(fixture.runtime.lease));
        assertEquals(1, fixture.runtime.failures.get());
    }
    private static Object failValue() { throw new AssertionError("Unavailable provider borrowed"); }
    private static final class TestQueue implements java.util.concurrent.Executor {
        final java.util.ArrayDeque<Runnable> work = new java.util.ArrayDeque<>();
        @Override public void execute(Runnable action) { work.addLast(action); }
        void all() { while (!work.isEmpty()) work.removeFirst().run(); }
    }
    private static final class Memory implements InternalRuntimePreferences.Backend {
        InternalRuntimeRecord value; int commits; boolean closed;
        InternalRuntimePreferences.Outcome outcome = InternalRuntimePreferences.Outcome.COMMITTED;
        Memory(boolean full) {
            Map<String, Object> ledger = new TreeMap<>(); ledger.put("assessed_events_v1", "");
            if (full) for (int i = 0; i < 99999; i++) ledger.put("task_failure:" + UUID.nameUUIDFromBytes(
                    ("retained:" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8)), true);
            value = new InternalRuntimeRecord(UUID.randomUUID().toString(), 0, Map.of("assessed_ledger", ledger));
            assertEquals(value, InternalRuntimeRecord.decode(InternalRuntimeRecord.encode(value)));
        }
        @Override public InternalRuntimeRecord read() { return value; }
        @Override public InternalRuntimePreferences.Outcome compareAndSet(InternalRuntimeRecord before, InternalRuntimeRecord next) {
            commits++; if (outcome == InternalRuntimePreferences.Outcome.COMMITTED) value = next; return outcome;
        }
        @Override public void close() { closed = true; }
    }
    private static final class Composition {
        final long now = 1744200000000L; final TestQueue worker = new TestQueue(); final Memory memory;
        final SerialOwnerDataBroker<MemoryHandle> broker; final RuntimeFixture runtime;
        final SerialOwnerDataBroker<MemoryHandle>.Binding binding; MemoryHandle handle;
        Composition(boolean full) throws Exception {
            memory = new Memory(full);
            broker = new SerialOwnerDataBroker<>(worker, new SerialOwnerDataBroker.Policy() {
                @Override public void task(String generation) { }
                @Override public void read(String generation) { }
                @Override public void editor(String generation, long revision) { }
            }, guards -> handle = new MemoryHandle(memory, guards));
            broker.initialize(); worker.all(); runtime = new RuntimeFixture("generation", broker.runtimeWorker());
            binding = broker.bindRuntime(runtime.lease.identity, runtime.queue);
        }
        void start() throws Exception {
            CompletableFuture<Void> start = runtime.queue.submitStartup(runtime.lease.identity, () -> binding.held(
                    OwnedResourceRegistry.Effect.STARTUP, handle -> { handle.statistics.beginSession(
                            new StatsRepository.NativeSession(runtime.lease.identity.session, now, "UTC")); return null; }));
            worker.all(); start.join(); runtime.activate();
        }
    }
    private static final class MemoryHandle implements AutoCloseable {
        final OwnerStatisticsStore statistics; final InternalPenanceRules rules;
        MemoryHandle(Memory memory, SerialOwnerDataBroker.Guards guards) {
            InternalRuntimePreferences preferences = new InternalRuntimePreferences(memory,
                    action -> { guards.requireData(); action.run(); guards.requireData(); }, () -> { });
            statistics = new OwnerStatisticsStore(preferences, guards::requireData, guards::requireSession, guards::dataUnavailable);
            rules = new InternalPenanceRules(preferences.preferences("rules"));
        }
        @Override public void close() { statistics.close(); }
    }
    /** Exact real lease/registry with isolated ownership; reflection only constructs package-private test capability. */
    private static final class RuntimeFixture {
        final RuntimeMutex mutex = new RuntimeMutex(new Object()); final RuntimeLease lease;
        final OwnedResourceRegistry registry; final OwnerWriteQueue queue;
        final OwnedResourceRegistry.Registration component; final AtomicInteger failures = new AtomicInteger();
        RuntimeFixture(String generation, java.util.concurrent.Executor worker) throws Exception {
            RuntimeLease.Identity identity = new RuntimeLease.Identity(RuntimeTarget.internal(InternalStorageScope.TARGET_ID),
                    RuntimeOrigin.LOCAL_UI, generation, UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), 0);
            Constructor<RuntimeLease> leaseConstructor = RuntimeLease.class.getDeclaredConstructor(RuntimeLease.Identity.class);
            leaseConstructor.setAccessible(true); lease = leaseConstructor.newInstance(identity);
            Constructor<OwnedResourceRegistry> registryConstructor = OwnedResourceRegistry.class.getDeclaredConstructor(
                    RuntimeMutex.class, RuntimeLease.class, Runnable.class); registryConstructor.setAccessible(true);
            registry = registryConstructor.newInstance(mutex, lease, (Runnable) () -> { });
            component = mutex.runtime(() -> {
                invoke("starting", new Class<?>[0]);
                return (OwnedResourceRegistry.Registration) invoke("register", new Class<?>[]{RuntimeLease.class, String.class, boolean.class}, lease, "owner-data", true);
            });
            queue = new OwnerWriteQueue(lease, registry, worker, ignored -> failures.incrementAndGet());
        }
        void activate() { mutex.runtime(() -> { assertEquals(true, invoke("prepareActivation", new Class<?>[0])); assertEquals(true, invoke("activate", new Class<?>[0])); return null; }); }
        void seal() { mutex.runtime(() -> { invoke("seal", new Class<?>[0]); return null; }); }
        private Object invoke(String name, Class<?>[] arguments, Object... values) {
            try { Method method = OwnedResourceRegistry.class.getDeclaredMethod(name, arguments); method.setAccessible(true); return method.invoke(registry, values); }
            catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }
    }
    private static final class Fixture implements AutoCloseable {
        final Context physical = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final Path root; final OwnedContext context; final InternalSubData data; final InternalOwnerDataBroker broker;
        Fixture() throws Exception {
            root = Files.createTempDirectory(physical.getCacheDir().toPath(), "owner-broker-fixture-");
            context = new OwnedContext(physical);
            FileInternalSubBackend backend = FileInternalSubBackend.createNew(root.toFile(),
                    new InternalProfile("Owner broker fixture", "", "", "", 0), InternalConfigurationStore.defaults());
            Constructor<InternalSubData> constructor = InternalSubData.class.getDeclaredConstructor(FileInternalSubBackend.class);
            constructor.setAccessible(true); data = constructor.newInstance(backend);
            broker = InternalOwnerDataBroker.attach(context, data, OwnerDataProvisioning.Decision.FIRST_INTERNAL_CREATE,
                    record -> record.visible);
        }
        CompletableFuture<Void> mutate(Runnable action) {
            CompletableFuture<Void> done = new CompletableFuture<>();
            data.execute(() -> { try { action.run(); done.complete(null); } catch (Throwable failure) { done.completeExceptionally(failure); } });
            return done;
        }
        void visible(boolean value) throws Exception {
            mutate(() -> data.store.setVisible(data.store.read(), value, System.currentTimeMillis())).get(10, TimeUnit.SECONDS);
        }
        @Override public void close() throws Exception {
            Throwable failure = null;
            try { broker.closeAfterDrain().get(10, TimeUnit.SECONDS); } catch (Throwable problem) { failure = problem; }
            try {
                Field field = InternalSubData.class.getDeclaredField("writes"); field.setAccessible(true);
                ExecutorService worker = (ExecutorService) field.get(data); worker.shutdown();
                assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
            } catch (Throwable problem) { if (failure == null) failure = problem; else failure.addSuppressed(problem); }
            try {
                if (failure == null) {
                    Field field = InternalOwnerDataBroker.class.getDeclaredField("OPEN"); field.setAccessible(true);
                    Map<?, ?> map = (Map<?, ?>) field.get(null); synchronized (map) { map.remove(data); }
                    assertTrue(context.registry.edit().clear().commit());
                    try (java.util.stream.Stream<Path> files = Files.walk(root)) {
                        for (Path path : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
                    }
                } else failure.addSuppressed(new IllegalStateException("Failed owned fixture retained at " + root));
            } catch (Throwable problem) { if (failure == null) failure = problem; else failure.addSuppressed(problem); }
            if (failure instanceof Exception) throw (Exception) failure;
            if (failure instanceof Error) throw (Error) failure;
        }
    }
    private static final class OwnedContext extends ContextWrapper {
        final SharedPreferences registry; final AtomicInteger unrelatedPreferenceRequests = new AtomicInteger();
        OwnedContext(Context physical) {
            super(physical); registry = physical.getSharedPreferences("owner_broker_fixture_" + UUID.randomUUID(), 0);
        }
        @Override public Context getApplicationContext() { return this; }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            if ("subhub_internal_owner_data_registry".equals(name)) return registry;
            unrelatedPreferenceRequests.incrementAndGet(); throw new AssertionError("Unrelated physical preferences: " + name);
        }
    }
}
