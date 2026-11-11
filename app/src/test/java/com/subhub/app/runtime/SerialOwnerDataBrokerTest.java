package com.subhub.app.runtime;

import com.subhub.app.internal.SerialOwnerDataBroker;
import com.subhub.app.stats.OwnerWriteQueue;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Real serial broker, queue, registry and lease contracts; no Android or backend substitutes. */
public final class SerialOwnerDataBrokerTest {
    @Test public void hintHealthDoesNotOpenOrAdmitAndStopsAtCloseOrFailure() {
        Fixture healthy = new Fixture(); assertTrue(healthy.broker.acceptsDataHint());
        assertEquals(0, healthy.opens.get()); assertTrue(healthy.worker.work.isEmpty());
        healthy.broker.closeAfterDrain(); assertFalse(healthy.broker.acceptsDataHint()); healthy.worker.all();
        Fixture failed = new Fixture(); failed.openFailure = true;
        CompletableFuture<Void> opening = failed.broker.initialize(); failed.worker.all(); rejected(opening);
        assertFalse(failed.broker.acceptsDataHint()); assertEquals(1, failed.opens.get());
    }
    @Test public void oneHandleServesTaskEditorReadAndSuccessiveSessions() {
        Fixture f = new Fixture();
        CompletableFuture<Integer> task = f.broker.submitTask("one", h -> h.add(2));
        CompletableFuture<Integer> editor = f.broker.submitEditor("one", 1, h -> h.add(3));
        CompletableFuture<Integer> read = f.broker.submitRead("one", h -> h.value);
        assertEquals(0, f.opens.get()); f.worker.all();
        assertEquals(2, (int) task.join()); assertEquals(5, (int) editor.join()); assertEquals(5, (int) read.join());
        Runtime first = new Runtime(f); first.begin(); first.activate(); first.stop(); f.worker.all();
        assertFalse(f.handle.closed); assertEquals(1, f.opens.get());
        f.broker.submitTask("one", h -> h.add(4)); f.worker.all();
        Runtime second = new Runtime(f); second.begin(); second.activate(); second.stop(); f.worker.all();
        assertFalse(f.handle.closed); assertEquals(9, f.handle.value); assertEquals(1, f.opens.get());
        CompletableFuture<Void> close = f.broker.closeAfterDrain(); assertFalse(close.isDone()); f.worker.all();
        close.join(); assertTrue(f.handle.closed); assertEquals(1, f.closes.get());
    }
    @Test public void tasksNeedNoControllerOrSessionButEditorsDo() {
        Fixture f = new Fixture(); f.controller = 9;
        CompletableFuture<Integer> task = f.broker.submitTask("one", h -> {
            assertThrows(SecurityException.class, h.guards::requireSession); return h.add(1);
        });
        CompletableFuture<Integer> denied = f.broker.submitEditor("one", 1, h -> h.add(100));
        f.worker.all(); assertEquals(1, (int) task.join()); rejected(denied); assertEquals(1, f.handle.value);
        f.visible = false; CompletableFuture<Integer> hidden = f.broker.submitTask("one", h -> h.add(1));
        f.worker.all(); rejected(hidden); assertEquals(1, f.handle.value);
    }
    @Test public void queuedWorkRechecksGenerationAndTaskPolicyAtAdmission() {
        Fixture f = new Fixture(); CompletableFuture<Integer> old = f.broker.submitTask("one", h -> h.add(1));
        f.generation = "two"; f.worker.all(); rejected(old); assertEquals(0, f.opens.get());
        f.tasks = false; CompletableFuture<Integer> disabled = f.broker.submitTask("two", h -> h.add(1));
        f.worker.all(); rejected(disabled); assertEquals(0, f.opens.get());
        f.tasks = true; f.broker.submitTask("two", h -> h.add(2)); f.worker.all(); assertEquals(2, f.handle.value);
    }
    @Test public void preAdmittedRuntimeWorkDrainsAfterHideAndStopWithoutFreshAdmission() {
        Fixture f = new Fixture(); Runtime r = new Runtime(f); r.begin(); r.activate();
        CompletableFuture<Void> accepted = r.queue.submit(r.lease.identity, () -> r.binding.held(h -> h.add(7)));
        f.visible = false; r.seal();
        assertThrows(OwnerWriteQueue.AdmissionRejected.class, () -> r.queue.submit(r.lease.identity, () -> fail("Late runtime")));
        CompletableFuture<Void> stopped = r.stopSealed(); assertNull(r.registry.drained(r.lease));
        f.worker.next(); accepted.join(); assertEquals(7, f.handle.value); assertFalse(stopped.isDone());
        f.worker.next(); stopped.join(); assertNotNull(r.registry.drained(r.lease)); assertFalse(f.handle.closed);
    }
    @Test public void queuedReadsObserveWholeCompletedWritesAndNoRuntimeAuthority() {
        Fixture f = new Fixture(); Runtime r = new Runtime(f); r.begin(); r.activate();
        r.queue.submit(r.lease.identity, () -> r.binding.held(h -> { h.add(3); h.other = 8; return null; }));
        CompletableFuture<List<Integer>> read = f.broker.submitRead("one", h -> {
            assertThrows(SecurityException.class, h.guards::requireSession); return List.of(h.value, h.other);
        });
        assertFalse(read.isDone()); f.worker.all(); assertEquals(List.of(3, 8), read.join()); r.stop(); f.worker.all();
    }
    @Test public void genericAndRuntimeAdmissionsCannotBeBorrowedReentrantly() {
        Fixture f = new Fixture(); Runtime r = new Runtime(f); r.begin(); r.activate();
        CompletableFuture<Integer> task = f.broker.submitTask("one", h -> {
            assertThrows(SecurityException.class, () -> r.binding.held(x -> x.add(100)));
            assertThrows(SecurityException.class, h.guards::requireSession); return h.add(1);
        });
        r.queue.submit(r.lease.identity, () -> r.binding.held(h -> {
            assertThrows(SecurityException.class, () -> r.binding.held(x -> x.add(100)));
            h.guards.requireSession(); return h.add(2);
        }));
        f.worker.all(); assertEquals(1, (int) task.join()); assertEquals(3, f.handle.value);
        assertThrows(SecurityException.class, f.handle.guards::requireData);
        assertThrows(SecurityException.class, () -> r.binding.held(h -> h.add(100)));
        r.stop(); f.worker.all();
    }
    @Test public void futureObserversDoNotInheritTheCompletingCommandAdmission() {
        Fixture f = new Fixture(); AtomicBoolean observed = new AtomicBoolean();
        f.broker.submitTask("one", h -> h.add(1)).thenRun(() -> {
            assertThrows(SecurityException.class, f.handle.guards::requireData); observed.set(true);
        });
        f.worker.all(); assertTrue(observed.get());
    }
    @Test public void exactQueueIdentityAndSingleBindingAreRequired() {
        Fixture f = new Fixture(); Runtime r = new Runtime(f);
        assertThrows(SecurityException.class, () -> f.broker.bindRuntime(identity("one"), r.queue));
        assertThrows(IllegalStateException.class, () -> f.broker.bindRuntime(r.lease.identity, r.queue));
        // Even work on the supplied worker has no session admission without queue's actual permit.
        AtomicBoolean rejected = new AtomicBoolean(); f.broker.runtimeWorker().execute(() -> {
            assertThrows(SecurityException.class, () -> r.binding.held(h -> h.add(1))); rejected.set(true);
        }); f.worker.all(); assertTrue(rejected.get()); assertEquals(0, f.opens.get());
        r.begin(); r.activate(); r.stop(); f.worker.all();
    }
    @Test public void brokerCloseWaitsForQueuedGenericWorkAndLiveSessionCleanup() {
        Fixture f = new Fixture(); Runtime r = new Runtime(f); r.begin(); r.activate();
        CompletableFuture<Integer> work = f.broker.submitTask("one", h -> h.add(2));
        CompletableFuture<Void> close = f.broker.closeAfterDrain();
        assertThrows(RejectedExecutionException.class, () -> f.broker.submitTask("one", h -> h.add(99)));
        assertFalse(close.isDone()); f.worker.all(); work.join(); assertFalse(close.isDone()); assertFalse(f.handle.closed);
        r.stop(); f.worker.all(); close.join(); assertEquals(0, f.handle.sessions); assertTrue(f.handle.closed);
    }
    @Test public void failedRuntimeAckRetainsBindingAndCannotClaimBrokerOrPhysicalDrain() {
        Fixture f = new Fixture(); Runtime r = new Runtime(f); r.begin(); r.activate();
        CompletableFuture<Void> failed = r.queue.submit(r.lease.identity, () -> r.binding.held(h -> {
            throw new IllegalStateException("Uncertain durable ACK");
        })); f.worker.all(); rejected(failed);
        CompletableFuture<Void> close = f.broker.closeAfterDrain(); CompletableFuture<Void> stop = r.stop();
        f.worker.all(); rejected(stop); assertFalse(close.isDone()); assertFalse(f.handle.closed);
        assertEquals(1, r.queue.unresolvedWrites()); assertNull(r.registry.drained(r.lease));
    }
    @Test public void guardsAndCompletionNeverHoldThePolicyMonitor() {
        Fixture f = new Fixture(); CompletableFuture<Integer> value = f.broker.submitEditor("one", 1, h -> {
            assertFalse(Thread.holdsLock(f.policyLock)); return h.add(2);
        }); value.thenRun(() -> assertFalse(Thread.holdsLock(f.policyLock))); f.worker.all(); value.join();
    }
    @Test public void failedOpenIsNeverRetriedAsCreateOrAnotherHandle() {
        Fixture f = new Fixture(); f.openFailure = true;
        CompletableFuture<Void> first = f.broker.initialize(); f.worker.all(); rejected(first);
        CompletableFuture<Integer> second = f.broker.submitTask("one", h -> h.add(1)); f.worker.all(); rejected(second);
        assertEquals(1, f.opens.get()); assertNull(f.handle);
    }
    @Test public void initializationIsExplicitAndAllowedBeforeFirstVisibleGeneration() {
        Fixture f = new Fixture(); f.visible = false;
        CompletableFuture<Void> initialized = f.broker.initialize(); f.worker.all(); initialized.join();
        assertEquals(1, f.opens.get()); assertEquals(0, f.handle.value);
    }
    @Test public void acceptedDataWriteCanFinishAfterRevocationButNextCommandCannot() {
        Fixture f = new Fixture(); CompletableFuture<Integer> admitted = f.broker.submitTask("one", h -> {
            f.visible = false; return h.add(1);
        }); CompletableFuture<Integer> later = f.broker.submitTask("one", h -> h.add(99));
        f.worker.all(); assertEquals(1, (int) admitted.join()); rejected(later); assertEquals(1, f.handle.value);
    }
    @Test public void retiredNoticeCleanupWorksWhileHiddenButHasNoSessionAdmission() {
        Fixture f = new Fixture(); f.visible = false;
        CompletableFuture<Integer> cleanup = f.broker.submitRetiredTaskNotices(h -> {
            assertThrows(SecurityException.class, h.guards::requireSession); return h.add(1);
        });
        CompletableFuture<Integer> task = f.broker.submitTask("one", h -> h.add(100));
        f.worker.all(); assertEquals(1, (int) cleanup.join()); rejected(task);
    }
    @Test public void recoveryRejectsExistingRuntimeAndBlocksNewBindingDuringRepair() {
        Fixture f = new Fixture(); Runtime r = new Runtime(f); r.begin(); r.activate();
        CompletableFuture<Void> denied = f.broker.submitRecovery("one", 1, h -> { fail("Runtime recovery"); return null; });
        f.worker.all(); rejected(denied); r.stop(); f.worker.all();
        CompletableFuture<Integer> recovered = f.broker.submitRecovery("one", 1, h -> {
            RuntimeLease nextLease = new RuntimeLease(identity("one"));
            RuntimeMutex mutex = new RuntimeMutex(new Object());
            OwnedResourceRegistry registry = new OwnedResourceRegistry(mutex, nextLease, () -> {});
            OwnerWriteQueue queue = new OwnerWriteQueue(nextLease, registry, f.broker.runtimeWorker(), ignored -> {});
            assertThrows(IllegalStateException.class, () -> f.broker.bindRuntime(nextLease.identity, queue));
            assertThrows(SecurityException.class, h.guards::requireSession); return h.add(3);
        }); f.worker.all(); assertEquals(3, (int) recovered.join());
        Runtime later = new Runtime(f); later.begin(); later.activate(); later.stop(); f.worker.all();
    }
    @Test public void closedRuntimeObservationCannotBeReusedAfterNextBinding() {
        Fixture f = new Fixture(); Runtime first = new Runtime(f); first.begin(); first.activate();
        first.stop(); f.worker.all(); assertThrows(SecurityException.class, first.binding::requireBound);
        Runtime later = new Runtime(f); later.begin(); later.activate();
        assertThrows(SecurityException.class, first.binding::requireBound); later.binding.requireBound();
        later.stop(); f.worker.all();
    }
    @Test public void inlineExecutorCannotBorrowAdmissionOrCorruptPendingDrainCount() {
        AtomicReference<SerialOwnerDataBroker<Handle>> reference = new AtomicReference<>(); AtomicInteger closes = new AtomicInteger();
        SerialOwnerDataBroker<Handle> broker = new SerialOwnerDataBroker<>(Runnable::run, new SerialOwnerDataBroker.Policy() {
            @Override public void task(String ignored) { }
            @Override public void editor(String ignored, long revision) { }
            @Override public void read(String ignored) { }
        }, guards -> new Handle(guards, closes)); reference.set(broker);
        CompletableFuture<Integer> result = broker.submitTask("one", h -> {
            assertThrows(IllegalStateException.class, () -> reference.get().submitTask("one", nested -> nested.add(100)));
            return h.add(1);
        });
        assertEquals(1, (int) result.join()); broker.closeAfterDrain().join(); assertEquals(1, closes.get());
    }
    @Test public void lifecycleAndNormalCommandsRequireTheirExactHeldQueuePhase() {
        Fixture f = new Fixture(); Runtime r = new Runtime(f);
        r.queue.submitStartup(r.lease.identity, () -> {
            assertThrows(SecurityException.class, () -> r.binding.held(OwnedResourceRegistry.Effect.NORMAL, h -> h.add(100)));
            assertThrows(SecurityException.class, () -> r.binding.held(OwnedResourceRegistry.Effect.CLEANUP, h -> h.add(100)));
            r.binding.held(OwnedResourceRegistry.Effect.STARTUP, h -> { h.sessions++; return null; });
        }); f.worker.all(); r.activate();
        r.queue.submit(r.lease.identity, () -> {
            assertThrows(SecurityException.class, () -> r.binding.held(OwnedResourceRegistry.Effect.STARTUP, h -> h.add(100)));
            assertThrows(SecurityException.class, () -> r.binding.held(OwnedResourceRegistry.Effect.CLEANUP, h -> h.add(100)));
            r.binding.held(OwnedResourceRegistry.Effect.NORMAL, h -> h.add(1));
        }); r.seal();
        CompletableFuture<Void> stopped = r.binding.stop(h -> {
            r.queue.requireWrite(OwnedResourceRegistry.Effect.CLEANUP);
            assertThrows(SecurityException.class, () -> r.queue.requireWrite(OwnedResourceRegistry.Effect.STARTUP));
            assertThrows(SecurityException.class, () -> r.queue.requireWrite(OwnedResourceRegistry.Effect.NORMAL));
            h.sessions--; return null;
        }, r.component::close);
        f.worker.all(); stopped.join(); assertEquals(1, f.handle.value);
    }
    @Test public void explicitDataFailureFencesBoundQueueAndAllSubsequentDataAdmissions() {
        Fixture f = new Fixture(); Runtime r = new Runtime(f); r.begin(); r.activate();
        CompletableFuture<Void> failed = f.broker.submitTask("one", h -> {
            h.guards.dataUnavailable(new IllegalStateException("Uncertain generic write")); return null;
        }); f.worker.all(); failed.join();
        assertThrows(SecurityException.class, r.binding::requireBound);
        assertThrows(OwnerWriteQueue.AdmissionRejected.class, () -> r.queue.submit(r.lease.identity, () -> fail("Fenced normal")));
        CompletableFuture<Integer> task = f.broker.submitTask("one", h -> h.add(100));
        CompletableFuture<Integer> retired = f.broker.submitRetiredTaskNotices(h -> h.add(100));
        f.worker.all(); rejected(task); rejected(retired);
        CompletableFuture<Void> close = f.broker.closeAfterDrain(); r.stop(); f.worker.all();
        assertFalse(close.isDone()); assertNull(r.registry.drained(r.lease)); assertFalse(f.handle.closed);
    }
    @Test public void hiddenVisibilityAdmissionIsControllerBoundAndCannotRaceLiveBinding() {
        Fixture f = new Fixture(); f.visible = false;
        CompletableFuture<Boolean> readd = f.broker.submitVisibility("one", 1, h -> { f.visible = true; return true; });
        f.worker.all(); assertTrue(readd.join()); Runtime r = new Runtime(f); r.begin(); r.activate();
        CompletableFuture<Boolean> hide = f.broker.submitVisibility("one", 1, h -> { f.visible = false; return false; });
        f.worker.all(); rejected(hide); assertTrue(f.visible); r.stop(); f.worker.all();
        f.controller = 2; CompletableFuture<Boolean> stale = f.broker.submitVisibility("one", 1, h -> false);
        f.worker.all(); rejected(stale);
    }
    @Test public void enqueueThenThrowRetainsDispatchUntilActualCompletionAndNeverDoubleReleases() {
        Queue queue = new Queue(); AtomicBoolean uncertain = new AtomicBoolean(); AtomicInteger closes = new AtomicInteger();
        Executor executor = action -> { queue.execute(action); if (uncertain.getAndSet(false)) throw new RejectedExecutionException("Enqueued before throw"); };
        SerialOwnerDataBroker<Handle> broker = new SerialOwnerDataBroker<>(executor, new SerialOwnerDataBroker.Policy() {
            @Override public void task(String ignored) { }
            @Override public void editor(String ignored, long controller) { }
            @Override public void read(String ignored) { }
        }, guards -> new Handle(guards, closes));
        broker.initialize(); queue.all(); uncertain.set(true);
        assertThrows(RejectedExecutionException.class, () -> broker.submitTask("one", h -> h.add(100)));
        CompletableFuture<Void> close = broker.closeAfterDrain(); assertFalse(close.isDone());
        queue.all(); close.join(); assertEquals(1, closes.get());
    }
    @Test public void postInvocationThrowWithoutCompletionCannotClaimBackendDrain() {
        Queue queue = new Queue(); AtomicBoolean reject = new AtomicBoolean(); AtomicInteger closes = new AtomicInteger();
        Executor executor = action -> { if (reject.get()) throw new RejectedExecutionException("Outcome unknown"); queue.execute(action); };
        SerialOwnerDataBroker<Handle> broker = new SerialOwnerDataBroker<>(executor, new SerialOwnerDataBroker.Policy() {
            @Override public void task(String ignored) { }
            @Override public void editor(String ignored, long controller) { }
            @Override public void read(String ignored) { }
        }, guards -> new Handle(guards, closes));
        broker.initialize(); queue.all(); reject.set(true);
        assertThrows(RejectedExecutionException.class, () -> broker.submitTask("one", h -> h.add(100)));
        reject.set(false); CompletableFuture<Void> close = broker.closeAfterDrain(); queue.all();
        assertFalse(close.isDone()); assertEquals(0, closes.get());
    }
    @Test public void completedCallbackReceiptWinsOverExecutorThrowAfterReturn() {
        AtomicInteger closes = new AtomicInteger(); AtomicInteger actions = new AtomicInteger();
        Executor executor = action -> { action.run(); throw new RejectedExecutionException("After actual completion"); };
        SerialOwnerDataBroker<Handle> broker = new SerialOwnerDataBroker<>(executor, new SerialOwnerDataBroker.Policy() {
            @Override public void task(String ignored) { }
            @Override public void editor(String ignored, long controller) { }
            @Override public void read(String ignored) { }
        }, guards -> new Handle(guards, closes));
        broker.initialize().join();
        assertEquals(1, (int) broker.submitTask("one", h -> { actions.incrementAndGet(); return h.add(1); }).join());
        broker.closeAfterDrain().join(); assertEquals(1, actions.get()); assertEquals(1, closes.get());
    }
    @Test public void duplicateExecutorCallbacksCannotReplayACommandOrClose() {
        Queue queue = new Queue(); AtomicInteger closes = new AtomicInteger(); AtomicInteger actions = new AtomicInteger();
        Executor duplicate = action -> { queue.execute(action); queue.execute(action); };
        SerialOwnerDataBroker<Handle> broker = new SerialOwnerDataBroker<>(duplicate, new SerialOwnerDataBroker.Policy() {
            @Override public void task(String ignored) { }
            @Override public void editor(String ignored, long controller) { }
            @Override public void read(String ignored) { }
        }, guards -> new Handle(guards, closes));
        CompletableFuture<Integer> result = broker.submitTask("one", h -> { actions.incrementAndGet(); return h.add(1); });
        queue.all(); assertEquals(1, (int) result.join()); assertEquals(1, actions.get());
        CompletableFuture<Void> close = broker.closeAfterDrain(); queue.all(); close.join(); assertEquals(1, closes.get());
    }
    @Test public void visibilityOutcomeWaitsForActualCallbackAfterAmbiguousDispatch() {
        Queue queue = new Queue(); AtomicBoolean uncertain = new AtomicBoolean(); AtomicInteger closes = new AtomicInteger();
        Executor executor = action -> { queue.execute(action); if (uncertain.getAndSet(false)) throw new RejectedExecutionException("Enqueued before throw"); };
        SerialOwnerDataBroker<Handle> broker = new SerialOwnerDataBroker<>(executor, new SerialOwnerDataBroker.Policy() {
            @Override public void task(String ignored) { }
            @Override public void editor(String ignored, long controller) { }
            @Override public void read(String ignored) { }
            @Override public void visibility(String ignored, long controller) { }
        }, guards -> new Handle(guards, closes));
        broker.initialize(); queue.all(); uncertain.set(true);
        CompletableFuture<Boolean> outcome = broker.submitVisibility("one", 1, h -> { fail("Fenced visibility mutation"); return false; });
        assertFalse(outcome.isDone()); queue.all(); rejected(outcome);
        CompletableFuture<Void> close = broker.closeAfterDrain(); queue.all(); close.join(); assertEquals(1, closes.get());
    }
    private static void rejected(CompletableFuture<?> future) {
        assertTrue(future.isCompletedExceptionally()); assertThrows(CompletionException.class, future::join);
    }
    private static RuntimeLease.Identity identity(String generation) {
        return new RuntimeLease.Identity(RuntimeTarget.internal("dummy_sub_preview"), RuntimeOrigin.LOCAL_UI,
                generation, UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), 0);
    }
    private static final class Queue implements Executor {
        final ArrayDeque<Runnable> work = new ArrayDeque<>();
        @Override public void execute(Runnable action) { work.addLast(action); }
        void next() { work.removeFirst().run(); }
        void all() { while (!work.isEmpty()) next(); }
    }
    private static final class Handle implements AutoCloseable {
        final SerialOwnerDataBroker.Guards guards;
        final AtomicInteger closes;
        int value, other, sessions; boolean closed;
        Handle(SerialOwnerDataBroker.Guards guards, AtomicInteger closes) { this.guards = guards; this.closes = closes; }
        int add(int n) { guards.requireData(); assertFalse(closed); return value += n; }
        @Override public void close() { guards.requireData(); assertEquals(0, sessions); closed = true; closes.incrementAndGet(); }
    }
    private static final class Fixture {
        final Object policyLock = new Object(); final Queue worker = new Queue();
        final AtomicInteger opens = new AtomicInteger(), closes = new AtomicInteger();
        String generation = "one"; boolean visible = true, tasks = true, openFailure; long controller = 1; Handle handle;
        final SerialOwnerDataBroker<Handle> broker = new SerialOwnerDataBroker<>(worker, new SerialOwnerDataBroker.Policy() {
            @Override public void read(String expected) { synchronized (policyLock) {
                if (!visible || !generation.equals(expected)) throw new SecurityException("Stale generation");
            } }
            @Override public void task(String expected) { read(expected); if (!tasks) throw new SecurityException("Tasks disabled"); }
            @Override public void editor(String expected, long expectedController) {
                read(expected); if (expectedController != controller) throw new SecurityException("Controller changed");
            }
            @Override public void visibility(String expected, long expectedController) {
                if (!generation.equals(expected) || expectedController != controller) throw new SecurityException("Visibility authority changed");
            }
        }, guards -> {
            assertFalse(Thread.holdsLock(policyLock)); opens.incrementAndGet();
            if (openFailure) throw new IllegalStateException("Missing established data");
            return handle = new Handle(guards, closes);
        });
    }
    private static final class Runtime {
        final Fixture f; final RuntimeMutex mutex = new RuntimeMutex(new Object());
        final RuntimeLease lease; final OwnedResourceRegistry registry;
        final OwnerWriteQueue queue; final OwnedResourceRegistry.Registration component;
        final SerialOwnerDataBroker<Handle>.Binding binding;
        Runtime(Fixture f) {
            this.f = f; lease = new RuntimeLease(identity(f.generation));
            registry = new OwnedResourceRegistry(mutex, lease, () -> {});
            component = mutex.runtime(() -> { registry.starting(); return registry.register(lease, "owner_data", true); });
            queue = new OwnerWriteQueue(lease, registry, f.broker.runtimeWorker(), ignored -> {});
            binding = f.broker.bindRuntime(lease.identity, queue);
        }
        void begin() {
            queue.submitStartup(lease.identity, () -> binding.held(h -> { h.guards.requireSession(); h.sessions++; return null; }));
            f.worker.all();
        }
        void activate() { mutex.runtime(() -> { assertTrue(registry.prepareActivation()); assertTrue(registry.activate()); return null; }); }
        void seal() { mutex.runtime(() -> { registry.seal(); return null; }); }
        CompletableFuture<Void> stop() { seal(); return stopSealed(); }
        CompletableFuture<Void> stopSealed() {
            return binding.stop(h -> { h.guards.requireSession(); h.sessions--; return null; }, component::close);
        }
    }
}
