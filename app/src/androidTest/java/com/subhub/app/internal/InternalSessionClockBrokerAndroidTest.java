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

/** Owned file/provider clock lifecycle. Native execution remains a separate qualification. */
@RunWith(AndroidJUnit4.class)
public final class InternalSessionClockBrokerAndroidTest {
    private static final long HOUR = 3_600_000;
    private static InternalSessionClock.Sample sample(long wall, long elapsed) { return new InternalSessionClock.Sample(new OwnerObservation(wall, "UTC"), elapsed); }
    private static InternalSessionClock.State disk(Fixture f) throws Exception {
        InternalRuntimeRecord record = InternalRuntimeRecord.decode(Files.readAllBytes(f.root.resolve(InternalStorageScope.TARGET_ID).resolve("runtime-data/data.record")));
        return InternalSessionClock.decode(record.namespaces().getOrDefault(InternalSessionClock.NAMESPACE, Map.of()));
    }
    private static InternalOwnerDataBroker.Session begin(Fixture f, RuntimeFixture runtime, long wall, boolean clock) throws Exception {
        InternalOwnerDataBroker.Session session = f.broker.bindRuntime(runtime.lease.identity, runtime.queue);
        runtime.queue.submitStartup(runtime.lease.identity, () -> {
            session.beginSession(new StatsRepository.NativeSession(runtime.lease.identity.session, wall, "UTC"));
            if (clock) session.initializeSessionClock(runtime.execution, sample(wall, 100), new java.security.SecureRandom());
        }).get(10, TimeUnit.SECONDS);
        return session;
    }
    @Test public void startupClockAndPausePersistThenOneCleanupAcknowledgesBothLanes() throws Exception {
        try (Fixture f = new Fixture()) {
            f.broker.initialize().get(10, TimeUnit.SECONDS); f.visible(true);
            String generation = f.data.acknowledged().generation; long wall = System.currentTimeMillis();
            RuntimeFixture r = new RuntimeFixture(f);
            InternalOwnerDataBroker.Session s = begin(f, r, wall, true);
            assertEquals(HOUR, disk(f).selectedDuration);
            assertEquals(InternalSessionClock.Status.RECOVERY_REQUIRED, f.broker.readSessionClock(generation).get(10, TimeUnit.SECONDS).status);
            r.activate();
            s.executeNormal(() -> s.simulateSessionPause(r.configuration, sample(wall + 1, 101), 0, UUID.randomUUID().toString())).get(10, TimeUnit.SECONDS);
            assertEquals(500L, disk(f).simulatedTotals.get("EUR").longValue());
            long before = f.broker.acknowledgedRevisionHint(generation);
            r.seal(); s.finishAndRelease(new OwnerObservation(wall + 10, "Europe/Berlin"), r.component::close).get(10, TimeUnit.SECONDS);
            assertEquals(before + 1, f.broker.acknowledgedRevisionHint(generation));
            assertEquals(InternalSessionClock.Phase.STOPPED, disk(f).phase); assertNull(disk(f).pause);
            assertEquals(wall + 10, disk(f).endedAt); assertEquals("Europe/Berlin", disk(f).endedZone);
            assertEquals(0, f.context.unrelatedPreferenceRequests.get());
        }
    }
    @Test public void clockMutationsRefuseWrongPhaseAndEscapedCallsWithoutWriting() throws Exception {
        try (Fixture f = new Fixture()) {
            f.broker.initialize().get(10, TimeUnit.SECONDS); f.visible(true);
            String generation = f.data.acknowledged().generation; long wall = System.currentTimeMillis();
            RuntimeFixture r = new RuntimeFixture(f);
            InternalOwnerDataBroker.Session s = f.broker.bindRuntime(r.lease.identity, r.queue);
            r.queue.submitStartup(r.lease.identity, () -> {
                s.beginSession(new StatsRepository.NativeSession(r.lease.identity.session, wall, "UTC"));
                s.initializeSessionClock(r.execution, sample(wall, 100), new java.security.SecureRandom());
                assertThrows(SecurityException.class, () -> s.tickSessionClock(r.configuration, sample(wall, 100)));
            }).get(10, TimeUnit.SECONDS);
            assertThrows(SecurityException.class, () -> s.simulateSessionPause(r.configuration, sample(wall, 100), 0, UUID.randomUUID().toString()));
            r.activate();
            s.executeNormal(() -> {
                assertThrows(SecurityException.class, () -> s.initializeSessionClock(r.execution, sample(wall, 100), new java.security.SecureRandom()));
                assertThrows(IllegalStateException.class, () -> s.simulateSessionPause(r.configuration, sample(wall, 100), 9, UUID.randomUUID().toString()));
            }).get(10, TimeUnit.SECONDS);
            assertEquals(0, disk(f).pauseSequence);
            r.seal(); s.finishAndRelease(wall + 10, r.component::close).get(10, TimeUnit.SECONDS);
        }
    }
    @Test public void previousClosedClockDoesNotBlockStopBeforeNewClockStartup() throws Exception {
        try (Fixture f = new Fixture()) {
            f.broker.initialize().get(10, TimeUnit.SECONDS); f.visible(true);
            String generation = f.data.acknowledged().generation; long wall = System.currentTimeMillis();
            RuntimeFixture first = new RuntimeFixture(f);
            InternalOwnerDataBroker.Session old = begin(f, first, wall, true); first.activate(); first.seal();
            old.finishAndRelease(wall + 10, first.component::close).get(10, TimeUnit.SECONDS);
            Map<String,Object> previous = InternalSessionClock.encode(disk(f));
            RuntimeFixture next = new RuntimeFixture(f);
            InternalOwnerDataBroker.Session unprepared = begin(f, next, wall + 20, false);
            next.seal(); unprepared.finishAndRelease(wall + 30, next.component::close).get(10, TimeUnit.SECONDS);
            assertEquals(previous, InternalSessionClock.encode(disk(f)));
        }
    }
    @Test public void simultaneousSessionBindRefusesAndHiddenReadCannotRestoreClock() throws Exception {
        try (Fixture f = new Fixture()) {
            f.broker.initialize().get(10, TimeUnit.SECONDS); f.visible(true);
            String generation = f.data.acknowledged().generation; long wall = System.currentTimeMillis();
            RuntimeFixture r = new RuntimeFixture(f);
            InternalOwnerDataBroker.Session s = begin(f, r, wall, true);
            RuntimeFixture other = new RuntimeFixture(f);
            assertThrows(IllegalStateException.class, () -> f.broker.bindRuntime(other.lease.identity, other.queue));
            other.seal(); other.queue.closeAfterDrain(() -> {}, other.component::close).get(10, TimeUnit.SECONDS);
            r.activate(); r.seal(); s.finishAndRelease(wall + 10, r.component::close).get(10, TimeUnit.SECONDS);
            f.visible(false);
            assertThrows(ExecutionException.class, () -> f.broker.readSessionClock(generation).get(10, TimeUnit.SECONDS));
            assertEquals(InternalSessionClock.Phase.STOPPED, disk(f).phase);
        }
    }
    @Test public void onlyExactAcknowledgedConfigurationCanGrantAndLiveRulesNeverRedrawDuration() throws Exception {
        try (Fixture f = new Fixture()) {
            f.broker.initialize().get(10, TimeUnit.SECONDS); f.visible(true); long wall = System.currentTimeMillis();
            RuntimeFixture r = new RuntimeFixture(f); InternalOwnerDataBroker.Session session = begin(f, r, wall, true); r.activate();
            RuntimeConfiguration sameBytesDifferentSnapshot = RuntimeConfiguration.internal(r.lease, f.data.configuration, f.data.acknowledged());
            session.executeNormal(() -> assertThrows(SecurityException.class, () -> session.simulateSessionPause(
                    sameBytesDifferentSnapshot, sample(wall + 1, 101), 0, UUID.randomUUID().toString()))).get(10, TimeUnit.SECONDS);
            f.mutate(() -> {
                InternalSubRecord current = f.data.store.read();
                Map<String,Map<String,Object>> changed = new LinkedHashMap<>(current.configuration());
                Map<String,Object> duration = new LinkedHashMap<>(changed.get("duration")); duration.put("duration", 2 * HOUR); changed.put("duration", duration);
                Map<String,Object> wallet = new LinkedHashMap<>(changed.get("wallet")); wallet.put("paid_pause_price_cents", 900); changed.put("wallet", wallet);
                f.data.store.saveConfiguration(current, changed);
            }).get(10, TimeUnit.SECONDS);
            RuntimeConfiguration updated = RuntimeConfiguration.internal(r.lease, f.data.configuration, f.data.acknowledged());
            assertEquals(ActiveRuntimeCoordinator.Result.OK, r.execution.applyConfiguration(updated.owner.profileGeneration, updated.configRevision, updated));
            session.executeNormal(() -> {
                assertThrows(SecurityException.class, () -> session.simulateSessionPause(r.configuration, sample(wall + 2, 102), 0, UUID.randomUUID().toString()));
                session.simulateSessionPause(updated, sample(wall + 2, 102), 0, UUID.randomUUID().toString());
            }).get(10, TimeUnit.SECONDS);
            assertEquals(HOUR, disk(f).selectedDuration); assertEquals(900, disk(f).pause.cents);
            assertEquals(updated.configRevision, disk(f).pause.configurationRevision);
            r.seal(); session.finishAndRelease(wall + 10, r.component::close).get(10, TimeUnit.SECONDS);
        }
    }
    /** Actual coordinator/execution admission, isolated from every physical owner. */
    private static final class RuntimeFixture {
        final RuntimeMutex mutex = new RuntimeMutex(new Object()); final RuntimeLease lease;
        final OwnedResourceRegistry registry; final OwnerWriteQueue queue;
        final ActiveRuntimeCoordinator coordinator; final RuntimeExecution<RuntimeConfiguration> execution;
        final RuntimeConfiguration configuration; final OwnedResourceRegistry.Registration component;
        RuntimeFixture(Fixture f) throws Exception {
            RuntimeOwnerStore.Backend backend = new RuntimeOwnerStore.Backend() {
                RuntimeOwnerStore.Record record = RuntimeOwnerStore.Record.idle(0);
                public RuntimeOwnerStore.Record read() { return record; }
                public RuntimeOwnerStore.Outcome compareAndSet(RuntimeOwnerStore.Record expected, RuntimeOwnerStore.Record next) {
                    if (!record.equals(expected)) return RuntimeOwnerStore.Outcome.CONFLICT;
                    record = next; return RuntimeOwnerStore.Outcome.COMMITTED;
                }
            };
            RuntimeAdmissionAuthority authority = new RuntimeAdmissionAuthority(mutex);
            RuntimeTarget target = RuntimeTarget.internal(InternalStorageScope.TARGET_ID);
            InternalSubRecord record = f.data.acknowledged();
            assertTrue(authority.completeAvailability(authority.beginAvailability(target), record.generation, record.configurationRevision, true));
            assertTrue(authority.completeAuthorization(authority.beginAuthorization(target, RuntimeOrigin.LOCAL_UI), 1, true));
            coordinator = new ActiveRuntimeCoordinator(new RuntimeOwnerStore(mutex, backend), authority,
                    () -> ActiveRuntimeCoordinator.Occupancy.CLEAR, UUID.randomUUID().toString());
            assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.reconcileStartup());
            ActiveRuntimeCoordinator.Admission admission = coordinator.reserve(authority.capture(target, RuntimeOrigin.LOCAL_UI), Set.of("owner-data"));
            assertEquals(ActiveRuntimeCoordinator.Result.OK, admission.result); lease = admission.lease;
            assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.beginStarting(lease, ignored -> true));
            registry = coordinator.resources(lease); configuration = RuntimeConfiguration.internal(lease, f.data.configuration, record);
            Constructor<?> constructor = RuntimeExecution.class.getDeclaredConstructor(ActiveRuntimeCoordinator.class, RuntimeLease.class, Object.class, java.util.concurrent.Executor.class);
            constructor.setAccessible(true);
            @SuppressWarnings("unchecked") RuntimeExecution<RuntimeConfiguration> created = (RuntimeExecution<RuntimeConfiguration>) constructor.newInstance(
                    coordinator, lease, configuration, (java.util.concurrent.Executor) f.data::execute);
            execution = created;
            component = coordinator.registerComponent(lease, "owner-data");
            assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.componentReady(lease, component));
            queue = new OwnerWriteQueue(lease, registry, f.broker.runtimeWorker(), ignored -> execution.requestStop());
        }
        void activate() { assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.activate(lease)); }
        void seal() { registry.fence(lease); }
    }
    private static final class Fixture implements AutoCloseable {
        final Context physical = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final Path root; final OwnedContext context; final InternalSubData data; final InternalOwnerDataBroker broker;
        Fixture() throws Exception {
            root = Files.createTempDirectory(physical.getCacheDir().toPath(), "owner-broker-fixture-");
            context = new OwnedContext(physical);
            Map<String, Map<String, Object>> configuration = new LinkedHashMap<>(InternalConfigurationStore.defaults());
            Map<String,Object> duration = new LinkedHashMap<>(configuration.getOrDefault("duration", Map.of()));
            duration.put("duration", HOUR); configuration.put("duration", duration);
            Map<String,Object> wallet = new LinkedHashMap<>(configuration.getOrDefault("wallet", Map.of()));
            wallet.put("paid_pause_enabled", true); configuration.put("wallet", wallet);
            FileInternalSubBackend backend = FileInternalSubBackend.createNew(root.toFile(),
                    new InternalProfile("Owner broker fixture", "", "", "", 0), configuration);
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
