package com.subhub.app.runtime;

import static org.junit.Assert.*;
import static com.subhub.app.runtime.ActiveRuntimeCoordinator.Result.*;
import static com.subhub.app.runtime.RuntimeOwnerStore.Outcome.*;
import static com.subhub.app.runtime.RuntimeOwnerStore.Phase.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.Test;

/** Real module/owner separation; persistence failures never stand in for an application receipt. */
public final class RuntimeConfigurationDispatchTest {
    @Test public void prepareModulesAndAckRunOnTheirExplicitExecutors() {
        Queue owner = new Queue(), modules = new Queue(); Fixture f = new Fixture(owner);
        int writes = f.backend.writes;
        CompletableFuture<ActiveRuntimeCoordinator.Result> result = f.apply(3, modules);
        assertEquals(writes, f.backend.writes); assertFalse(result.isDone());
        assertFalse(f.execution.runEffect(() -> fail("Effect crossed pending configuration")));
        owner.next();
        assertEquals(3, f.backend.record.savedConfigurationRevision);
        assertEquals(2, f.backend.record.appliedConfigurationRevision); assertTrue(f.values.isEmpty());
        f.onModule = value -> {
            assertEquals(2, f.execution.appliedConfigurationRevision());
            assertEquals(2, f.backend.record.appliedConfigurationRevision);
            assertTrue(f.execution.runEffect(() -> f.values.add("nested-effect")));
        };
        modules.next(); assertFalse(result.isDone()); assertEquals("initial", f.execution.configuration());
        assertFalse(f.execution.runEffect(() -> fail("Effect preceded journal ACK")));
        owner.next(); assertEquals(OK, result.join());
        assertEquals(List.of("revision3", "nested-effect"), f.values);
        assertEquals("revision3", f.execution.configuration());
        assertEquals(3, f.execution.appliedConfigurationRevision());
        assertTrue(f.execution.runEffect(() -> { }));
    }

    @Test public void receiverCannotReenterEitherConfigurationPath() {
        Queue owner = new Queue(), modules = new Queue(); Fixture f = new Fixture(owner);
        f.onModule = value -> {
            assertEquals(NOT_READY, f.apply(4, modules).join());
            assertEquals(NOT_READY, f.execution.applyConfiguration("generation", 4, "nested"));
        };
        CompletableFuture<ActiveRuntimeCoordinator.Result> result = f.apply(3, modules);
        owner.next(); modules.next(); owner.next(); assertEquals(OK, result.join());
        assertEquals(List.of("revision3"), f.values); assertEquals(3, f.execution.appliedConfigurationRevision());
    }

    @Test public void ordinaryEffectCannotQueueNestedConfiguration() {
        Queue owner = new Queue(), modules = new Queue(); Fixture f = new Fixture(owner);
        assertTrue(f.execution.runEffect(() -> assertEquals(NOT_READY, f.apply(3, modules).join())));
        assertTrue(owner.empty()); assertTrue(modules.empty());
    }

    @Test public void competingRevisionIsRetainedForExplicitRetryAndOldRevisionIsRejected() {
        Queue owner = new Queue(), modules = new Queue(); Fixture f = new Fixture(owner);
        CompletableFuture<ActiveRuntimeCoordinator.Result> first = f.apply(3, modules);
        assertEquals(NOT_READY, f.apply(4, modules).join());
        owner.next(); modules.next(); owner.next(); assertEquals(OK, first.join());
        CompletableFuture<ActiveRuntimeCoordinator.Result> next = f.apply(4, modules);
        owner.next(); modules.next(); owner.next(); assertEquals(OK, next.join());
        assertEquals(CONFIGURATION_CHANGED, f.apply(3, modules).join());
        assertEquals(List.of("revision3", "revision4"), f.values);
    }

    @Test public void stopWhileModuleCallbackQueuedRetainsOccupancyUntilActualReturn() {
        Queue owner = new Queue(), modules = new Queue(); Fixture f = new Fixture(owner);
        CompletableFuture<ActiveRuntimeCoordinator.Result> result = f.apply(3, modules); owner.next();
        f.execution.requestStop(); owner.drain();
        assertEquals(STOPPING, f.backend.record.phase); assertNull(f.close.get());
        assertFalse(result.isDone()); assertEquals(OCCUPIED, f.admit().result);
        modules.next(); owner.drain();
        assertEquals(STALE_LEASE, result.join()); assertTrue(f.values.isEmpty()); assertNotNull(f.close.get());
        f.close.get().run(); owner.drain(); assertEquals(IDLE, f.backend.record.phase);
    }

    @Test public void partialModuleFailureStopsWithoutPublishingAppliedRevision() {
        Queue owner = new Queue(), modules = new Queue(); Fixture f = new Fixture(owner);
        f.onModule = value -> { throw new IllegalStateException("Actual module rejected update"); };
        CompletableFuture<ActiveRuntimeCoordinator.Result> result = f.apply(3, modules);
        owner.next(); modules.next(); owner.drain();
        assertThrows(CompletionException.class, result::join);
        assertEquals(2, f.execution.appliedConfigurationRevision()); assertEquals("initial", f.execution.configuration());
        assertEquals(2, f.backend.record.appliedConfigurationRevision); assertEquals(STOPPING, f.backend.record.phase);
        assertFalse(f.execution.runEffect(() -> fail("Partial configuration continued")));
        f.close.get().run(); owner.drain(); assertEquals(IDLE, f.backend.record.phase);
    }

    @Test public void uncertainAppliedCommitNeverReportsAppliedOrReleasesQuarantine() {
        Queue owner = new Queue(), modules = new Queue(); Fixture f = new Fixture(owner);
        CompletableFuture<ActiveRuntimeCoordinator.Result> result = f.apply(3, modules);
        owner.next(); modules.next(); f.backend.next = UNCERTAIN; owner.drain();
        assertEquals(QUARANTINED, result.join()); assertEquals(2, f.execution.appliedConfigurationRevision());
        assertTrue(f.coordinator.observe().quarantined);
        assertFalse(f.execution.runEffect(() -> fail("Unknown commit permitted effect")));
        f.close.get().run(); owner.drain(); assertEquals(QUARANTINED, f.admit().result);
    }

    @Test public void failedOfferNeverDispatchesModuleAndStops() {
        Queue owner = new Queue(), modules = new Queue(); Fixture f = new Fixture(owner);
        f.backend.next = NOT_COMMITTED;
        CompletableFuture<ActiveRuntimeCoordinator.Result> result = f.apply(3, modules); owner.drain();
        assertEquals(PERSISTENCE_FAILED, result.join()); assertTrue(modules.empty()); assertTrue(f.values.isEmpty());
        assertEquals(STOPPING, f.backend.record.phase); assertNotNull(f.close.get());
    }

    @Test public void stopBeforeOwnerPreparationNeverDispatchesModule() {
        Queue owner = new Queue(), modules = new Queue(); Fixture f = new Fixture(owner);
        CompletableFuture<ActiveRuntimeCoordinator.Result> result = f.apply(3, modules);
        f.execution.requestStop(); owner.drain();
        assertEquals(STALE_LEASE, result.join()); assertTrue(modules.empty());
        assertEquals(2, f.backend.record.savedConfigurationRevision); assertNotNull(f.close.get());
    }

    @Test public void uncertainOfferDoesNotDispatchModuleOrReleaseQuarantine() {
        Queue owner = new Queue(), modules = new Queue(); Fixture f = new Fixture(owner);
        f.backend.next = UNCERTAIN;
        CompletableFuture<ActiveRuntimeCoordinator.Result> result = f.apply(3, modules); owner.drain();
        assertEquals(QUARANTINED, result.join()); assertTrue(modules.empty());
        assertFalse(f.execution.runEffect(() -> fail("Uncertain preparation continued")));
        f.close.get().run(); owner.drain(); assertEquals(QUARANTINED, f.admit().result);
    }

    @Test public void definiteFailedAppliedCommitStopsWithoutClaimingNewRevision() {
        Queue owner = new Queue(), modules = new Queue(); Fixture f = new Fixture(owner);
        CompletableFuture<ActiveRuntimeCoordinator.Result> result = f.apply(3, modules);
        owner.next(); modules.next(); f.backend.next = NOT_COMMITTED; owner.drain();
        assertEquals(PERSISTENCE_FAILED, result.join());
        assertEquals(2, f.execution.appliedConfigurationRevision()); assertEquals("initial", f.execution.configuration());
        assertEquals(3, f.backend.record.savedConfigurationRevision); assertEquals(STOPPING, f.backend.record.phase);
        assertFalse(f.execution.runEffect(() -> fail("Unacknowledged settings continued")));
    }

    @Test public void wrongGenerationAndUnchangedRevisionNeverDispatchModules() {
        Queue owner = new Queue(), modules = new Queue(); Fixture f = new Fixture(owner);
        assertEquals(CONFIGURATION_CHANGED, f.execution.applyConfigurationAsync("removed", 3, "wrong", modules).join());
        assertTrue(owner.empty());
        CompletableFuture<ActiveRuntimeCoordinator.Result> unchanged = f.apply(2, modules); owner.next();
        assertEquals(OK, unchanged.join()); assertTrue(modules.empty());
    }

    @Test public void rejectedModuleDispatchIsNotInventedCompletion() {
        Queue owner = new Queue(); Fixture f = new Fixture(owner);
        CompletableFuture<ActiveRuntimeCoordinator.Result> result = f.apply(3, action -> {
            throw new RejectedExecutionException("Module executor unavailable");
        });
        owner.drain(); assertThrows(CompletionException.class, result::join);
        assertTrue(f.values.isEmpty()); assertEquals(2, f.execution.appliedConfigurationRevision());
        assertEquals(STOPPING, f.backend.record.phase); assertNotNull(f.close.get());
        f.close.get().run(); owner.drain(); assertEquals(IDLE, f.backend.record.phase);
    }

    @Test public void rejectedJournalFinalizationKeepsWorkPermitAndOccupancy() {
        Queue owner = new Queue(), modules = new Queue(); Fixture f = new Fixture(owner);
        CompletableFuture<ActiveRuntimeCoordinator.Result> result = f.apply(3, modules); owner.next();
        owner.reject = true; modules.next(); assertThrows(CompletionException.class, result::join);
        assertTrue(f.execution.lifecycleNeedsAttention()); assertNull(f.close.get());
        assertFalse(f.execution.runEffect(() -> fail("Rejected ACK resumed effects")));
        assertEquals(2, f.execution.appliedConfigurationRevision()); assertEquals(OCCUPIED, f.admit().result);
        owner.reject = false; f.execution.requestStop(); owner.drain();
        assertEquals(STOPPING, f.backend.record.phase); assertNull(f.close.get());
        assertEquals(OCCUPIED, f.admit().result); // Explicit recovery must reconcile this unresolved transaction.
    }

    @Test public void stopInsideReceiverPreventsRemainingModulesAndDefersClosure() {
        Queue owner = new Queue(), modules = new Queue(); Fixture f = new Fixture(owner, Set.of("one", "two"));
        f.onModule = value -> { f.execution.requestStop(); assertNull(f.close.get()); };
        CompletableFuture<ActiveRuntimeCoordinator.Result> result = f.apply(3, modules);
        owner.next(); modules.next(); owner.drain();
        assertEquals(List.of("revision3"), f.values); assertEquals(STALE_LEASE, result.join());
        assertEquals(2, f.execution.appliedConfigurationRevision()); assertNotNull(f.close.get());
    }

    @Test public void busyEffectMakesPreparationNonblockingAndRetryable() throws Exception {
        Queue owner = new Queue(), modules = new Queue(); Fixture f = new Fixture(owner);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        FutureTask<Boolean> effect = new FutureTask<>(() -> f.execution.runEffect(() -> {
            entered.countDown(); await(release);
        }));
        Thread thread = new Thread(effect); thread.start(); await(entered);
        try {
            CompletableFuture<ActiveRuntimeCoordinator.Result> result = f.apply(3, modules); owner.next();
            assertEquals(NOT_READY, result.join()); assertTrue(modules.empty());
        } finally { release.countDown(); }
        assertTrue(effect.get(2, TimeUnit.SECONDS));
        CompletableFuture<ActiveRuntimeCoordinator.Result> retry = f.apply(3, modules);
        owner.next(); modules.next(); owner.next(); assertEquals(OK, retry.join());
    }

    @Test public void blockedJournalAckDoesNotBlockMainOrStopAndCannotPublishAfterStop() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Queue modules = new Queue(); Fixture f = new Fixture(worker);
        try {
            CompletableFuture<ActiveRuntimeCoordinator.Result> result = f.apply(3, modules);
            worker.submit(() -> { }).get(2, TimeUnit.SECONDS); assertFalse(modules.empty());
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            f.backend.beforeWrite = update -> {
                if (update.appliedConfigurationRevision == 3 && update.phase == ACTIVE) {
                    entered.countDown(); await(release);
                }
            };
            modules.next(); await(entered);
            try {
                nonblocking(() -> {
                    assertFalse(f.execution.runEffect(() -> fail("Effect entered pending ACK")));
                    assertEquals(NOT_READY, f.execution.requestStop());
                });
                assertEquals(2, f.execution.appliedConfigurationRevision()); assertNull(f.close.get());
            } finally { release.countDown(); }
            assertEquals(STALE_LEASE, result.get(2, TimeUnit.SECONDS));
            worker.submit(() -> { }).get(2, TimeUnit.SECONDS);
            assertFalse(f.execution.runEffect(() -> fail("Stopped configuration resumed")));
            assertNotNull(f.close.get()); assertEquals(2, f.execution.appliedConfigurationRevision());
        } finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test public void blockedPrepareCanBeCancelledWithoutMainWaitingForJournal() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor(); Queue modules = new Queue(); Fixture f = new Fixture(worker);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        f.backend.beforeWrite = update -> {
            if (update.savedConfigurationRevision == 3 && update.phase == ACTIVE) {
                entered.countDown(); await(release);
            }
        };
        try {
            CompletableFuture<ActiveRuntimeCoordinator.Result> result = f.apply(3, modules); await(entered);
            nonblocking(() -> {
                assertFalse(f.execution.runEffect(() -> fail("Preparing owner admitted another effect")));
                assertEquals(NOT_READY, f.execution.requestStop());
            });
            assertTrue(modules.empty()); release.countDown();
            assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            worker.submit(() -> { }).get(2, TimeUnit.SECONDS);
            assertTrue(modules.empty()); assertEquals(2, f.execution.appliedConfigurationRevision());
            assertNotNull(f.close.get()); assertEquals(STOPPING, f.backend.record.phase);
        } finally {
            release.countDown(); worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void actualRunningModuleMustReturnBeforeAnyClosure() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor(), main = Executors.newSingleThreadExecutor();
        Fixture f = new Fixture(worker); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        f.onModule = ignored -> { entered.countDown(); await(release); };
        try {
            CompletableFuture<ActiveRuntimeCoordinator.Result> result = f.apply(3, main); await(entered);
            nonblocking(() -> assertEquals(NOT_READY, f.execution.requestStop()));
            worker.submit(() -> { }).get(2, TimeUnit.SECONDS);
            assertEquals(STOPPING, f.backend.record.phase); assertNull(f.close.get()); assertFalse(result.isDone());
            release.countDown(); assertEquals(STALE_LEASE, result.get(2, TimeUnit.SECONDS));
            assertNotNull(f.close.get()); assertEquals(2, f.execution.appliedConfigurationRevision());
        } finally {
            release.countDown(); main.shutdownNow(); worker.shutdownNow();
            assertTrue(main.awaitTermination(5, TimeUnit.SECONDS)); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static final class Queue implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>(); volatile boolean reject;
        @Override public synchronized void execute(Runnable action) {
            if (reject) throw new RejectedExecutionException(); tasks.addLast(action);
        }
        synchronized boolean empty() { return tasks.isEmpty(); }
        void next() { Runnable action; synchronized (this) { assertFalse(tasks.isEmpty()); action = tasks.removeFirst(); } action.run(); }
        void drain() { while (!empty()) next(); }
    }
    private static final class Fixture {
        final RuntimeMutex mutex = new RuntimeMutex(new Object());
        final Backend backend = new Backend();
        final RuntimeAdmissionAuthority authority = new RuntimeAdmissionAuthority(mutex);
        final RuntimeTarget target = RuntimeTarget.internal("dummy_sub_preview");
        final ActiveRuntimeCoordinator coordinator = new ActiveRuntimeCoordinator(new RuntimeOwnerStore(mutex, backend),
                authority, () -> ActiveRuntimeCoordinator.Occupancy.CLEAR, UUID.randomUUID().toString());
        final AtomicReference<Runnable> close = new AtomicReference<>();
        final List<String> values = new ArrayList<>(); final RuntimeExecution<String> execution;
        final RuntimeLease lease; final Set<String> required;
        volatile Consumer<String> onModule = ignored -> { };
        Fixture(Executor worker) { this(worker, Set.of("renderer")); }
        Fixture(Executor worker, Set<String> required) {
            this.required = required;
            assertTrue(authority.completeAvailability(authority.beginAvailability(target), "generation", 2, true));
            assertTrue(authority.completeAuthorization(authority.beginAuthorization(target, RuntimeOrigin.LOCAL_UI), 1, true));
            assertEquals(OK, coordinator.reconcileStartup());
            ActiveRuntimeCoordinator.Admission admitted = admit(); assertEquals(OK, admitted.result); lease = admitted.lease;
            assertEquals(OK, coordinator.beginStarting(lease, ignored -> true));
            execution = new RuntimeExecution<>(coordinator, lease, "initial", worker);
            for (String name : required) {
                RuntimeExecution<String>.Component component = execution.registerComponent(name);
                component.installShutdown(close::set);
                component.installConfigurationReceiver(value -> { values.add(value); onModule.accept(value); });
                assertEquals(OK, component.ready());
            }
            assertEquals(OK, coordinator.activate(lease));
        }
        ActiveRuntimeCoordinator.Admission admit() { return coordinator.reserve(authority.capture(target, RuntimeOrigin.LOCAL_UI), required); }
        CompletableFuture<ActiveRuntimeCoordinator.Result> apply(long revision, Executor moduleExecutor) {
            return execution.applyConfigurationAsync("generation", revision, "revision" + revision, moduleExecutor);
        }
    }
    private static final class Backend implements RuntimeOwnerStore.Backend {
        volatile RuntimeOwnerStore.Record record = RuntimeOwnerStore.Record.idle(0);
        volatile RuntimeOwnerStore.Outcome next = COMMITTED;
        volatile Consumer<RuntimeOwnerStore.Record> beforeWrite = ignored -> { }; int writes;
        @Override public RuntimeOwnerStore.Record read() { return record; }
        @Override public RuntimeOwnerStore.Outcome compareAndSet(RuntimeOwnerStore.Record expected, RuntimeOwnerStore.Record update) {
            writes++; if (!record.equals(expected)) return CONFLICT; beforeWrite.accept(update);
            RuntimeOwnerStore.Outcome outcome = next; next = COMMITTED;
            if (outcome == COMMITTED) record = update; return outcome;
        }
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue("Deterministic boundary missing", latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    private static void nonblocking(Runnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(action, null); Thread main = new Thread(task, "simulated-main");
        main.setDaemon(true); main.start(); task.get(2, TimeUnit.SECONDS);
    }
}
