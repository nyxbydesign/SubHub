package com.subhub.app.service;

import static org.junit.Assert.*;
import com.subhub.app.runtime.*;
import java.lang.reflect.Constructor;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Actual binding control flow with pure endpoint/executor fixtures. No Android/native claim. */
public final class OwnedProjectionBindingTest {
    @Test public void startupIsPausedAndNormalFramesWaitForActive() throws Exception {
        Fixture f = new Fixture(OwnedProjectionBinding.COMPONENT); Queue producer = new Queue();
        Source source = new Source(); OwnedProjectionBinding<Object> binding = new OwnedProjectionBinding<>(
                f.runtime, source, producer, f::schedule);
        assertTrue(binding.start(null)); producer.drain();
        assertEquals(1, source.starts); assertTrue(source.paused);
        AtomicInteger deliveries = new AtomicInteger(); binding.requestFrame(frame -> { assertNull(frame); deliveries.incrementAndGet(); });
        producer.drain(); assertEquals(0, source.reads); assertEquals(1, deliveries.get());
        f.activate(); binding.paused(false, admitted -> assertTrue(admitted)); producer.drain();
        assertFalse(source.paused);
        binding.requestFrame(frame -> { assertNotNull(frame); frame.close(); }); producer.drain();
        assertEquals(1, source.reads); assertEquals(1, source.releases);
        binding.close(); producer.drain(); f.poll(); assertTrue(binding.managedResourcesDrained()); f.idle();
    }

    @Test public void stopSealsReadsButWaitsForExactHeldFrameDisposal() throws Exception {
        Fixture f = new Fixture(OwnedProjectionBinding.COMPONENT); Queue producer = new Queue(); Source source = new Source();
        OwnedProjectionBinding<Object> binding = new OwnedProjectionBinding<>(f.runtime, source, producer, f::schedule);
        binding.start(null); producer.drain(); f.activate();
        AtomicReference<OwnedProjectionBinding<Object>.Frame> held = new AtomicReference<>();
        binding.requestFrame(held::set); producer.drain();
        assertTrue(held.get().read(value -> assertSame(source.pixels, value)));
        binding.close(); producer.drain();
        assertFalse(held.get().read(value -> fail("Read after Stop")));
        assertEquals(0, source.closes); assertFalse(binding.managedResourcesDrained()); f.stopping();
        held.get().close(); held.get().close(); producer.drain(); f.poll();
        assertEquals(1, source.releases); assertEquals(1, source.closes); f.idle();
    }

    @Test public void aConfigurationChangeFencesOldFrameWithoutPreventingCleanup() throws Exception {
        Fixture f = new Fixture(OwnedProjectionBinding.COMPONENT); Queue producer = new Queue(); Source source = new Source();
        OwnedProjectionBinding<Object> binding = new OwnedProjectionBinding<>(f.runtime, source, producer, f::schedule);
        binding.start(null); producer.drain(); f.activate();
        AtomicReference<OwnedProjectionBinding<Object>.Frame> held = new AtomicReference<>();
        binding.requestFrame(held::set); producer.drain();
        assertEquals(ActiveRuntimeCoordinator.Result.OK, f.runtime.applyConfiguration("generation", 3, "next"));
        assertFalse(held.get().read(value -> fail("Old configuration read")));
        held.get().close(); producer.drain(); binding.close(); producer.drain(); f.poll(); f.idle();
    }

    @Test public void unknownDispatchIsNotClosedUntilItsAcceptedTaskReturns() throws Exception {
        Fixture f = new Fixture(OwnedProjectionBinding.COMPONENT); Queue producer = new Queue(); Source source = new Source();
        OwnedProjectionBinding<Object> binding = new OwnedProjectionBinding<>(f.runtime, source, producer, f::schedule);
        producer.throwAfterEnqueue = true;
        assertThrows(IllegalStateException.class, () -> binding.start(null));
        assertFalse(binding.managedResourcesDrained()); assertEquals(0, source.closes); f.stopping();
        producer.drain(); f.poll(); assertEquals(0, source.starts); assertEquals(1, source.closes); f.idle();
    }

    @Test public void resourceReleaseFailureKeepsOwnerOccupied() throws Exception {
        Fixture f = new Fixture(OwnedProjectionBinding.COMPONENT); Queue producer = new Queue(); Source source = new Source();
        OwnedProjectionBinding<Object> binding = new OwnedProjectionBinding<>(f.runtime, source, producer, f::schedule);
        binding.start(null); producer.drain(); f.activate();
        AtomicReference<OwnedProjectionBinding<Object>.Frame> held = new AtomicReference<>();
        binding.requestFrame(held::set); producer.drain(); binding.close();
        source.failRelease = true; held.get().close(); producer.drain(); f.poll();
        assertFalse(binding.managedResourcesDrained()); assertEquals(0, source.closes); f.stopping();
        assertTrue(f.runtime.cleanupNeedsAttention());
    }

    @Test public void closedFrameCannotReadEvenWhileRuntimeStillActive() throws Exception {
        Fixture f = new Fixture(OwnedProjectionBinding.COMPONENT); Queue producer = new Queue(); Source source = new Source();
        OwnedProjectionBinding<Object> binding = new OwnedProjectionBinding<>(f.runtime, source, producer, f::schedule);
        binding.start(null); producer.drain(); f.activate();
        AtomicReference<OwnedProjectionBinding<Object>.Frame> held = new AtomicReference<>();
        binding.requestFrame(held::set); producer.drain(); held.get().close();
        assertFalse(held.get().read(value -> fail("Retired frame read")));
        producer.drain(); binding.close(); producer.drain(); f.poll(); f.idle();
    }

    @Test public void terminatedProducerStillWaitsForActualProjectionCallbackDrain() throws Exception {
        Fixture f = new Fixture(OwnedProjectionBinding.COMPONENT); Queue producer = new Queue(); Source source = new Source();
        source.allowDrain = false;
        OwnedProjectionBinding<Object> binding = new OwnedProjectionBinding<>(f.runtime, source, producer, f::schedule);
        binding.start(null); producer.drain(); f.activate(); binding.close(); producer.drain(); f.poll();
        assertTrue(producer.isTerminated()); assertFalse(binding.managedResourcesDrained()); f.stopping();
        source.allowDrain = true; f.poll(); f.idle();
    }

    @Test public void closeFailureRetainsComponentInsteadOfInventingResourceReceipt() throws Exception {
        Fixture f = new Fixture(OwnedProjectionBinding.COMPONENT); Queue producer = new Queue(); Source source = new Source();
        source.failClose = true;
        OwnedProjectionBinding<Object> binding = new OwnedProjectionBinding<>(f.runtime, source, producer, f::schedule);
        binding.start(null); producer.drain(); f.activate(); binding.close(); producer.drain(); f.poll();
        assertTrue(producer.isTerminated()); assertFalse(binding.managedResourcesDrained()); f.stopping();
        assertTrue(f.runtime.cleanupNeedsAttention());
    }

    @Test public void stoppingBeforeStartupDispatchStillClosesExactSourceOnlyAfterTaskReturn() throws Exception {
        Fixture f = new Fixture(OwnedProjectionBinding.COMPONENT); Queue producer = new Queue(); Source source = new Source();
        OwnedProjectionBinding<Object> binding = new OwnedProjectionBinding<>(f.runtime, source, producer, f::schedule);
        binding.start(null); binding.close(); assertEquals(0, source.closes);
        producer.drain(); f.poll(); assertEquals(0, source.starts); assertEquals(1, source.closes); f.idle();
    }

    @Test public void retiredRelayCannotCallOrRetainOwnerAfterLateDelivery() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        OwnedProjectionBinding.CallbackRelay relay = new OwnedProjectionBinding.CallbackRelay(calls::incrementAndGet);
        assertFalse(relay.drained()); relay.run(); assertEquals(1, calls.get());
        relay.retire(); relay.retire();
        assertTrue(relay.drained());
        java.lang.reflect.Field target = relay.getClass().getDeclaredField("target"); target.setAccessible(true);
        assertNull("Retirement clears the owner reference, rather than just blocking invocation", target.get(relay));
        for (int i = 0; i < 100; i++) relay.run();
        assertEquals(1, calls.get()); assertTrue(relay.drained());
    }

    @Test public void retireWaitsForAdmittedCallbackBodyToActuallyReturn() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), leave = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        OwnedProjectionBinding.CallbackRelay relay = new OwnedProjectionBinding.CallbackRelay(() -> {
            calls.incrementAndGet(); entered.countDown(); await(leave);
        });
        Thread callback = new Thread(relay);
        callback.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            relay.retire(); assertFalse(relay.drained());
            relay.run(); assertEquals(1, calls.get()); assertFalse(relay.drained());
        } finally { leave.countDown(); callback.join(5000); }
        assertFalse(callback.isAlive()); assertTrue(relay.drained());
    }

    @Test public void everyAdmittedCallbackMustReturnEvenWhenAnotherThrows() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), leave = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        OwnedProjectionBinding.CallbackRelay relay = new OwnedProjectionBinding.CallbackRelay(() -> {
            if (calls.incrementAndGet() == 1) { entered.countDown(); await(leave); }
            else throw new IllegalStateException("Callback body failed");
        });
        Thread callback = new Thread(relay); callback.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, relay::run);
            relay.retire(); assertFalse(relay.drained());
        } finally { leave.countDown(); callback.join(5000); }
        assertFalse(callback.isAlive()); assertTrue(relay.drained()); assertEquals(2, calls.get());
    }

    @Test public void callbackCanRetireItselfWithoutClaimingItsOwnReturn() {
        AtomicReference<OwnedProjectionBinding.CallbackRelay> reference = new AtomicReference<>();
        OwnedProjectionBinding.CallbackRelay relay = new OwnedProjectionBinding.CallbackRelay(() -> {
            reference.get().retire(); assertFalse(reference.get().drained());
        });
        reference.set(relay); relay.run(); assertTrue(relay.drained());
        relay.run(); assertTrue(relay.drained());
    }

    @Test public void concurrentRetireAndEntryNeverLeaveAReachableOwner() throws Exception {
        for (int i = 0; i < 100; i++) {
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            AtomicReference<Throwable> failed = new AtomicReference<>();
            OwnedProjectionBinding.CallbackRelay relay = new OwnedProjectionBinding.CallbackRelay(calls::incrementAndGet);
            Thread callback = new Thread(() -> {
                try { await(start); relay.run(); } catch (Throwable failure) { failed.set(failure); }
            });
            callback.start(); start.countDown(); relay.retire(); callback.join(5000);
            assertFalse(callback.isAlive()); assertNull(failed.get());
            assertTrue(relay.drained()); int count = calls.get();
            assertTrue(count == 0 || count == 1); relay.run(); assertEquals(count, calls.get());
            java.lang.reflect.Field target = relay.getClass().getDeclaredField("target"); target.setAccessible(true);
            assertNull(target.get(relay));
        }
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Fixture did not release callback"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }

    private static final class Source implements OwnedProjectionBinding.Endpoint<Object> {
        final Object pixels = new Object(); int starts, reads, releases, closes;
        boolean paused = true, failRelease, failClose, allowDrain = true;
        @Override public void start(RuntimeCaptureHost.ProjectionGrant grant, Runnable revoked) { starts++; }
        @Override public void paused(boolean value) { paused = value; }
        @Override public Object acquire() { reads++; return pixels; }
        @Override public void release(Object value) { assertSame(pixels, value); releases++; if (failRelease) throw new IllegalStateException(); }
        @Override public void close() { closes++; if (failClose) throw new IllegalStateException("Close failed"); }
        @Override public boolean drained() { return closes == 1 && allowDrain; }
    }
    static final class Queue extends AbstractExecutorService {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>(); boolean shutdown, running, throwAfterEnqueue;
        @Override public void execute(Runnable command) {
            if (shutdown) throw new IllegalStateException("Executor shut down");
            tasks.add(command);
            if (throwAfterEnqueue) { throwAfterEnqueue = false; throw new IllegalStateException("accepted then threw"); }
        }
        void next() { running = true; try { tasks.remove().run(); } finally { running = false; } }
        void drain() { while (!tasks.isEmpty()) next(); }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { throw new AssertionError("No cancel-based shutdown"); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && !running && tasks.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { throw new AssertionError("No blocking drain"); }
    }
    static final class Fixture {
        final ArrayDeque<Runnable> polls = new ArrayDeque<>();
        final ActiveRuntimeCoordinator coordinator;
        final RuntimeLease lease;
        final RuntimeExecution<String> runtime;
        @SuppressWarnings("unchecked") Fixture(String required) throws Exception {
            RuntimeMutex mutex = new RuntimeMutex(new Object());
            RuntimeAdmissionAuthority authority = new RuntimeAdmissionAuthority(mutex);
            RuntimeOwnerStore.Backend backend = new RuntimeOwnerStore.Backend() {
                RuntimeOwnerStore.Record record = RuntimeOwnerStore.Record.idle(0);
                @Override public RuntimeOwnerStore.Record read() { return record; }
                @Override public RuntimeOwnerStore.Outcome compareAndSet(RuntimeOwnerStore.Record before, RuntimeOwnerStore.Record next) {
                    if (!record.equals(before)) return RuntimeOwnerStore.Outcome.CONFLICT;
                    record = next; return RuntimeOwnerStore.Outcome.COMMITTED;
                }
            };
            coordinator = new ActiveRuntimeCoordinator(new RuntimeOwnerStore(mutex, backend), authority,
                    () -> ActiveRuntimeCoordinator.Occupancy.CLEAR, UUID.randomUUID().toString());
            RuntimeTarget target = RuntimeTarget.internal("dummy_sub_preview");
            assertTrue(authority.completeAvailability(authority.beginAvailability(target), "generation", 2, true));
            assertTrue(authority.completeAuthorization(authority.beginAuthorization(target, RuntimeOrigin.LOCAL_UI), 1, true));
            assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.reconcileStartup());
            ActiveRuntimeCoordinator.Admission admission = coordinator.reserve(authority.capture(target, RuntimeOrigin.LOCAL_UI), Set.of(required));
            assertEquals(ActiveRuntimeCoordinator.Result.OK, admission.result); lease = admission.lease;
            assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.beginStarting(lease, ignored -> true));
            Constructor<?> constructor = RuntimeExecution.class.getDeclaredConstructor(ActiveRuntimeCoordinator.class, RuntimeLease.class, Object.class);
            constructor.setAccessible(true); runtime = (RuntimeExecution<String>) constructor.newInstance(coordinator, lease, "initial");
        }
        boolean schedule(Runnable action, long ignored) { polls.add(action); return true; }
        void poll() { int count = polls.size(); while (count-- > 0) polls.remove().run(); }
        void activate() { assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.activate(lease)); }
        void idle() { assertEquals(RuntimeOwnerStore.Phase.IDLE, coordinator.observe().effectivePhase()); }
        void stopping() { assertEquals(RuntimeOwnerStore.Phase.STOPPING, coordinator.observe().effectivePhase()); }
    }
}
