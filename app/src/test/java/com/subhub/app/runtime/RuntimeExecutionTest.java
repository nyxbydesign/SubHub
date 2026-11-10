package com.subhub.app.runtime;

import static org.junit.Assert.*;
import static com.subhub.app.runtime.ActiveRuntimeCoordinator.Result.*;
import static com.subhub.app.runtime.RuntimeOwnerStore.Outcome.*;
import static com.subhub.app.runtime.RuntimeOwnerStore.Phase.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Tests the execution core used by real Android bindings; the backend is a failure-injection fake. */
public final class RuntimeExecutionTest {
    @Test public void startupCannotRunNormalEffectsBeforeAcknowledgedActivation() {
        Fixture f = new Fixture(); f.reserve();
        AtomicBoolean ran = new AtomicBoolean();
        assertFalse(f.execution.runEffect(() -> ran.set(true)));
        assertTrue(f.execution.runStartupEffect(() -> ran.set(true)));
        assertTrue(ran.get()); f.bind(); f.activate();
        assertTrue(f.execution.runEffect(() -> ran.set(false))); assertFalse(ran.get());
    }
    @Test public void readinessRequiresCleanupAndExplicitConfigurationBinding() {
        Fixture f = new Fixture(); f.reserve();
        RuntimeExecution<String>.Component component = f.execution.registerComponent("renderer");
        assertThrows(IllegalStateException.class, component::ready);
        component.installShutdown(done -> f.pendingClose.set(done));
        assertThrows(IllegalStateException.class, component::ready);
        component.usesNoConfiguration(); assertEquals(OK, component.ready());
    }
    @Test public void queuedCloseIsNotADrainAcknowledgement() {
        Fixture f = active(); assertEquals(OK, f.execution.requestStop());
        assertEquals(STOPPING, f.backend.record.phase);
        assertNotNull(f.pendingClose.get());
        assertEquals(OCCUPIED, f.admit().result);
        f.pendingClose.get().run(); assertEquals(IDLE, f.backend.record.phase);
    }
    @Test public void reentrantStopDefersCloseUntilActualEffectReturns() {
        Fixture f = active(); List<String> order = new ArrayList<>();
        f.onClose = () -> order.add("close");
        assertTrue(f.execution.runEffect(() -> {
            order.add("effect"); assertEquals(OK, f.execution.requestStop());
            assertEquals(List.of("effect"), order);
            assertNull(f.pendingClose.get()); order.add("returned");
        }));
        assertEquals(List.of("effect", "returned", "close"), order);
        assertFalse(f.execution.runEffect(() -> fail("Late effect")));
    }
    @Test public void concurrentStopSealsBeforeWaitingForInFlightEffect() throws Exception {
        Fixture f = active(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch stopWrite = new CountDownLatch(1); AtomicReference<Throwable> failure = new AtomicReference<>();
        f.backend.stoppingWrite = stopWrite::countDown;
        Thread effect = thread(failure, () -> f.execution.runEffect(() -> { entered.countDown(); await(release); }));
        await(entered);
        Thread stop = thread(failure, f.execution::requestStop);
        await(stopWrite);
        assertEquals(STOPPING, f.coordinator.observe().effectivePhase());
        assertNull(f.pendingClose.get());
        assertEquals(OCCUPIED, f.admit().result);
        join(stop); // Stop must return while the effect is still waiting for its main-thread completion.
        assertNull(f.pendingClose.get());
        release.countDown(); join(effect); rethrow(failure);
        assertNotNull(f.pendingClose.get());
    }
    @Test public void failedStoppingWriteStillRejectsEffectsAndKeepsOccupancy() {
        Fixture f = active(); f.backend.next = NOT_COMMITTED;
        assertEquals(PERSISTENCE_FAILED, f.execution.requestStop());
        assertFalse(f.execution.runEffect(() -> fail("Failed stop re-enabled effect")));
        assertEquals(ACTIVE, f.backend.record.phase);
        f.pendingClose.get().run();
        assertEquals(STOPPING, f.coordinator.observe().effectivePhase());
        assertEquals(OCCUPIED, f.admit().result);
    }
    @Test public void cleanupFailureRetainsOwnerInsteadOfInventingDrain() {
        Fixture f = active(); f.onClose = () -> { throw new IllegalStateException("Owned closure failed"); };
        f.execution.requestStop(); assertTrue(f.execution.cleanupNeedsAttention());
        assertNull(f.pendingClose.get()); assertEquals(STOPPING, f.backend.record.phase);
        assertEquals(OCCUPIED, f.admit().result);
    }
    @Test public void duplicateOldAcknowledgementCannotCloseReplacement() {
        Fixture f = active(); RuntimeExecution<String> old = f.execution;
        old.requestStop(); Runnable oldDone = f.pendingClose.get(); oldDone.run();
        f.reserve(); f.bind(); f.activate(); RuntimeLease.Identity replacement = f.execution.identity();
        oldDone.run(); assertEquals(STALE_LEASE, old.requestStop());
        assertFalse(old.runEffect(() -> fail("Old effect reached replacement")));
        assertEquals(replacement, f.backend.record.owner); assertEquals(ACTIVE, f.backend.record.phase);
    }
    @Test public void acknowledgedConfigReplacesEveryModuleBeforeAppliedRevision() {
        Fixture f = active();
        f.onConfiguration = value -> {
            assertEquals(3, f.backend.record.savedConfigurationRevision);
            assertEquals(2, f.backend.record.appliedConfigurationRevision);
        };
        assertEquals(OK, f.execution.applyConfiguration("generation", 3, "updated"));
        assertEquals(List.of("updated"), f.configurations);
        assertEquals("updated", f.execution.configuration());
        assertEquals(3, f.execution.appliedConfigurationRevision());
        assertEquals(3, f.backend.record.appliedConfigurationRevision);
    }
    @Test public void delayedConfigOrWrongGenerationCannotReplaceNewerSnapshot() {
        Fixture f = active(); assertEquals(OK, f.execution.applyConfiguration("generation", 4, "latest"));
        assertEquals(CONFIGURATION_CHANGED, f.execution.applyConfiguration("generation", 3, "old"));
        assertEquals(CONFIGURATION_CHANGED, f.execution.applyConfiguration("removed", 5, "other"));
        assertEquals(List.of("latest"), f.configurations); assertEquals("latest", f.execution.configuration());
    }
    @Test public void partialModuleApplicationStopsAndDoesNotPublishAppliedRevision() {
        Fixture f = active(); f.onConfiguration = ignored -> { throw new IllegalStateException("Module failed"); };
        assertThrows(IllegalStateException.class, () -> f.execution.applyConfiguration("generation", 3, "new"));
        assertEquals(3, f.backend.record.savedConfigurationRevision);
        assertEquals(2, f.backend.record.appliedConfigurationRevision);
        assertEquals("initial", f.execution.configuration()); assertEquals(STOPPING, f.backend.record.phase);
        assertFalse(f.execution.runEffect(() -> fail("Partially updated owner still enforcing")));
    }
    @Test public void uncertainAppliedCommitQuarantinesAndNeverReportsSuccess() {
        Fixture f = active(); f.onConfiguration = ignored -> f.backend.next = UNCERTAIN;
        assertEquals(QUARANTINED, f.execution.applyConfiguration("generation", 3, "new"));
        assertEquals("initial", f.execution.configuration()); assertEquals(2, f.execution.appliedConfigurationRevision());
        assertTrue(f.coordinator.observe().quarantined);
        assertFalse(f.execution.runEffect(() -> fail("Uncertain owner still enforcing")));
        f.pendingClose.get().run(); assertEquals(QUARANTINED, f.admit().result);
    }
    @Test public void configBusyResultDoesNotBlockAndExactSnapshotCanBeRetried() throws Exception {
        Fixture f = active(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>(); AtomicBoolean returned = new AtomicBoolean();
        Thread effect = thread(failure, () -> f.execution.runEffect(() -> {
            entered.countDown(); await(release); returned.set(true);
        }));
        await(entered); f.onConfiguration = ignored -> assertTrue(returned.get());
        assertEquals(NOT_READY, f.execution.applyConfiguration("generation", 3, "new"));
        assertEquals("initial", f.execution.configuration());
        assertEquals(2, f.backend.record.appliedConfigurationRevision);
        release.countDown(); join(effect); rethrow(failure);
        assertEquals(OK, f.execution.applyConfiguration("generation", 3, "new"));
        assertEquals("new", f.execution.configuration());
    }
    @Test public void contendedEffectDoesNotWaitOrPretendToHaveExecuted() throws Exception {
        Fixture f = active(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread effect = thread(failure, () -> f.execution.runEffect(() -> { entered.countDown(); await(release); }));
        await(entered);
        assertFalse(f.execution.runEffect(() -> fail("Busy effect executed")));
        release.countDown(); join(effect); rethrow(failure);
        AtomicBoolean retried = new AtomicBoolean(); assertTrue(f.execution.runEffect(() -> retried.set(true)));
        assertTrue(retried.get());
    }
    @Test public void racingStopAndEffectNeverClosesWhileAdmittedWorkIsRunning() throws Exception {
        for (int iteration = 0; iteration < 100; iteration++) {
            Fixture f = active(); CountDownLatch together = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>(); AtomicBoolean effectActive = new AtomicBoolean();
            f.onClose = () -> assertFalse("Closure overlapped admitted effect", effectActive.get());
            Thread effect = thread(failure, () -> { await(together); f.execution.runEffect(() -> {
                effectActive.set(true); Thread.yield(); assertNull(f.pendingClose.get()); effectActive.set(false);
            }); });
            Thread stop = thread(failure, () -> { await(together); f.execution.requestStop(); });
            together.countDown(); join(effect); join(stop); rethrow(failure);
            assertNotNull(f.pendingClose.get()); f.pendingClose.get().run(); assertEquals(IDLE, f.backend.record.phase);
        }
    }

    @Test public void configurationCannotReenterFromOrdinaryAdmittedEffect() {
        Fixture f = active();
        assertTrue(f.execution.runEffect(() -> assertEquals(NOT_READY,
                f.execution.applyConfiguration("generation", 3, "nested"))));
        assertTrue(f.configurations.isEmpty()); assertEquals(2, f.backend.record.appliedConfigurationRevision);
        assertEquals(OK, f.execution.applyConfiguration("generation", 3, "retry"));
    }
    @Test public void configurationReceiverCannotPublishNewerRevisionInsideOlderLoop() {
        Fixture f = new Fixture(); f.required = Set.of("renderer", "second"); f.reserve(); f.bind();
        List<String> order = new ArrayList<>();
        RuntimeExecution<String>.Component second = f.execution.registerComponent("second");
        second.installShutdown(Runnable::run); second.installConfigurationReceiver(value -> order.add("two=" + value));
        assertEquals(OK, second.ready()); f.activate();
        f.onConfiguration = value -> {
            order.add("one=" + value);
            if (value.equals("three")) order.add("nested=" + f.execution.applyConfiguration("generation", 4, "four"));
        };
        assertEquals(OK, f.execution.applyConfiguration("generation", 3, "three"));
        assertEquals(List.of("one=three", "nested=NOT_READY", "two=three"), order);
        assertEquals(3, f.backend.record.appliedConfigurationRevision);
        assertEquals(OK, f.execution.applyConfiguration("generation", 4, "four"));
        assertEquals(List.of("one=three", "nested=NOT_READY", "two=three", "one=four", "two=four"), order);
    }
    @Test public void queuedStopFencesImmediatelyWithoutJournalIoOnCaller() {
        Fixture f = new Fixture(); Queue worker = new Queue(); f.lifecycle = worker;
        f.reserve(); f.bind(); f.activate();
        int writes = f.backend.writes;
        assertEquals(NOT_READY, f.execution.requestStop());
        assertEquals(writes, f.backend.writes); assertEquals(ACTIVE, f.backend.record.phase);
        assertFalse(f.execution.runEffect(() -> fail("Queued stop admitted an effect")));
        assertNull(f.pendingClose.get()); assertEquals(OCCUPIED, f.admit().result);
        worker.next(); assertEquals(STOPPING, f.backend.record.phase);
        writes = f.backend.writes; f.pendingClose.get().run();
        assertEquals(writes, f.backend.writes); assertEquals(STOPPING, f.backend.record.phase);
        worker.drain(); assertEquals(IDLE, f.backend.record.phase);
    }
    @Test public void effectExceptionQueuesStopInsteadOfDoingJournalIoInline() {
        Fixture f = new Fixture(); Queue worker = new Queue(); f.lifecycle = worker;
        f.reserve(); f.bind(); f.activate(); int writes = f.backend.writes;
        assertThrows(IllegalStateException.class, () -> f.execution.runEffect(() -> {
            throw new IllegalStateException("Window effect failed on main");
        }));
        assertEquals(writes, f.backend.writes); assertEquals(ACTIVE, f.backend.record.phase);
        assertFalse(f.execution.runEffect(() -> fail("Failed owner resumed")));
        worker.next(); assertEquals(STOPPING, f.backend.record.phase);
        f.pendingClose.get().run(); worker.drain(); assertEquals(IDLE, f.backend.record.phase);
    }
    @Test public void rejectedStopDispatchStaysFencedAndOccupied() {
        Fixture f = new Fixture(); f.lifecycle = action -> { throw new java.util.concurrent.RejectedExecutionException(); };
        f.reserve(); f.bind(); f.activate();
        assertEquals(NOT_READY, f.execution.requestStop()); assertTrue(f.execution.lifecycleNeedsAttention());
        assertEquals(ACTIVE, f.backend.record.phase); assertNull(f.pendingClose.get());
        assertFalse(f.execution.runEffect(() -> fail("Dispatch failure resumed owner")));
        assertEquals(OCCUPIED, f.admit().result);
    }
    @Test public void queuedStopRefusesNewComponentsBeforeJournalWorkerRuns() {
        Fixture f = new Fixture(); Queue worker = new Queue(); f.lifecycle = worker; f.reserve();
        assertEquals(NOT_READY, f.execution.requestStop());
        assertThrows(SecurityException.class, () -> f.execution.registerComponent("renderer"));
        assertFalse(f.execution.runStartupEffect(() -> fail("Queued stop admitted startup")));
        worker.drain(); assertEquals(IDLE, f.backend.record.phase);
    }
    @Test public void readinessReceiptIsAcknowledgedOnOwnerWorker() {
        Fixture f = new Fixture(); Queue worker = new Queue(); f.lifecycle = worker; f.reserve();
        RuntimeExecution<String>.Component component = f.execution.registerComponent("renderer");
        component.installShutdown(done -> f.pendingClose.set(done)); component.usesNoConfiguration();
        AtomicReference<ActiveRuntimeCoordinator.Result> result = new AtomicReference<>();
        int writes = f.backend.writes; component.readyAsync(result::set);
        assertEquals(writes, f.backend.writes); assertNull(result.get());
        assertEquals(NOT_READY, f.coordinator.activate(f.lease));
        worker.next(); assertEquals(OK, result.get()); assertEquals(OK, f.coordinator.activate(f.lease));
    }
    private static final class Queue implements java.util.concurrent.Executor {
        final java.util.ArrayDeque<Runnable> tasks = new java.util.ArrayDeque<>();
        @Override public void execute(Runnable action) { tasks.addLast(action); }
        void next() { assertFalse(tasks.isEmpty()); tasks.removeFirst().run(); }
        void drain() { while (!tasks.isEmpty()) next(); }
    }

    private static Fixture active() { Fixture f = new Fixture(); f.reserve(); f.bind(); f.activate(); return f; }
    private static final class Fixture {
        final RuntimeMutex mutex = new RuntimeMutex(new Object());
        final Backend backend = new Backend();
        final RuntimeAdmissionAuthority authority = new RuntimeAdmissionAuthority(mutex);
        final RuntimeTarget target = RuntimeTarget.internal("dummy_sub_preview");
        final ActiveRuntimeCoordinator coordinator = new ActiveRuntimeCoordinator(new RuntimeOwnerStore(mutex, backend),
                authority, () -> ActiveRuntimeCoordinator.Occupancy.CLEAR, UUID.randomUUID().toString());
        final AtomicReference<Runnable> pendingClose = new AtomicReference<>();
        final List<String> configurations = new ArrayList<>();
        RuntimeLease lease; RuntimeExecution<String> execution;
        Set<String> required = Set.of("renderer");
        java.util.concurrent.Executor lifecycle = Runnable::run;
        Runnable onClose = () -> { };
        java.util.function.Consumer<String> onConfiguration = ignored -> { };
        Fixture() {
            assertTrue(authority.completeAvailability(authority.beginAvailability(target), "generation", 2, true));
            assertTrue(authority.completeAuthorization(authority.beginAuthorization(target, RuntimeOrigin.LOCAL_UI), 1, true));
            assertEquals(OK, coordinator.reconcileStartup());
        }
        ActiveRuntimeCoordinator.Admission admit() {
            return coordinator.reserve(authority.capture(target, RuntimeOrigin.LOCAL_UI), required);
        }
        void reserve() {
            ActiveRuntimeCoordinator.Admission admitted = admit(); assertEquals(OK, admitted.result);
            lease = admitted.lease; assertEquals(OK, coordinator.beginStarting(lease, ignored -> true));
            execution = new RuntimeExecution<>(coordinator, lease, "initial", lifecycle); pendingClose.set(null);
        }
        void bind() {
            RuntimeExecution<String>.Component component = execution.registerComponent("renderer");
            component.installShutdown(done -> { onClose.run(); pendingClose.set(done); });
            component.installConfigurationReceiver(value -> { configurations.add(value); onConfiguration.accept(value); });
            assertEquals(OK, component.ready());
        }
        void activate() { assertEquals(OK, coordinator.activate(lease)); }
    }
    private static final class Backend implements RuntimeOwnerStore.Backend {
        volatile RuntimeOwnerStore.Record record = RuntimeOwnerStore.Record.idle(0);
        volatile RuntimeOwnerStore.Outcome next = COMMITTED;
        Runnable stoppingWrite = () -> { };
        int writes;
        @Override public RuntimeOwnerStore.Record read() { return record; }
        @Override public RuntimeOwnerStore.Outcome compareAndSet(RuntimeOwnerStore.Record expected, RuntimeOwnerStore.Record update) {
            writes++;
            if (!record.equals(expected)) return CONFLICT;
            RuntimeOwnerStore.Outcome outcome = next; next = COMMITTED;
            if (outcome == COMMITTED) record = update;
            if (update.phase == STOPPING) stoppingWrite.run();
            return outcome;
        }
    }
    private static Thread thread(AtomicReference<Throwable> failure, Runnable action) {
        Thread thread = new Thread(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        thread.start(); return thread;
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue("Deterministic boundary not reached", latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    private static void join(Thread thread) throws InterruptedException { thread.join(5000); assertFalse(thread.isAlive()); }
    private static void rethrow(AtomicReference<Throwable> failure) { if (failure.get() != null) throw new AssertionError(failure.get()); }
}
