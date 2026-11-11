package com.subhub.app.service;

import static org.junit.Assert.*;
import com.subhub.app.runtime.ActiveRuntimeCoordinator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Full binding control tests. Native platform source and hardware drawing are not exercised. */
public final class OwnedProjectionBorrowTest {
    private static final class Source implements OwnedProjectionBinding.Endpoint<Object> {
        final Object pixels = new Object(); int starts, reads, releases, seals, closes; boolean sealed, failSeal;
        @Override public void start(RuntimeCaptureHost.ProjectionGrant grant, Runnable revoked) { starts++; }
        @Override public void paused(boolean value) { }
        @Override public Object acquire() { reads++; return pixels; }
        @Override public void release(Object value) { assertSame(pixels, value); releases++; }
        @Override public void seal() { seals++; sealed = true; if (failSeal) throw new IllegalStateException("seal uncertainty"); }
        @Override public void close() { closes++; }
        @Override public boolean drained() { return closes == 1; }
    }
    private static final class Case {
        final OwnedProjectionBindingTest.Fixture core;
        final OwnedProjectionBindingTest.Queue producer = new OwnedProjectionBindingTest.Queue();
        final Source source = new Source();
        final OwnedProjectionBinding<Object> binding;
        final AtomicReference<OwnedProjectionBinding<Object>.Frame> frame = new AtomicReference<>();
        Case() throws Exception {
            core = new OwnedProjectionBindingTest.Fixture(OwnedProjectionBinding.COMPONENT);
            binding = new OwnedProjectionBinding<>(core.runtime, source, producer, core::schedule);
            binding.start(null); producer.drain(); core.activate(); binding.requestFrame(frame::set); producer.drain();
        }
        void finish() { binding.close(); frame.get().close(); producer.drain(); core.poll(); }
    }
    @Test public void borrowedComputationDoesNotHoldGlobalEffectGateAndCloseWaitsForReturn() throws Exception {
        Case c = new Case();
        OwnedProjectionBinding<Object>.Frame.Borrow<String> borrow = c.frame.get().borrowComputation(() -> "immutable");
        assertNotNull(borrow); assertEquals("immutable", borrow.facts); c.frame.get().close();
        CountDownLatch entered = new CountDownLatch(1), leave = new CountDownLatch(1);
        Thread worker = new Thread(() -> borrow.use(pixels -> { assertSame(c.source.pixels, pixels); entered.countDown(); await(leave); }));
        worker.start(); await(entered);
        try {
            assertTrue(c.core.runtime.runEffect(() -> {}));
            c.binding.close(); assertTrue(c.source.sealed); borrow.close(); c.producer.drain();
            assertEquals(0, c.source.releases); assertEquals(0, c.source.closes); c.core.stopping();
        } finally { leave.countDown(); worker.join(5000); }
        assertFalse(worker.isAlive()); c.producer.drain(); c.core.poll();
        assertEquals(1, c.source.releases); assertEquals(1, c.source.closes); c.core.idle();
        assertFalse(borrow.use(value -> fail("closed borrow reused")));
    }
    @Test public void staleConfigurationRejectsNewBorrowButAcceptedNativeBorrowStillReturns() throws Exception {
        Case c = new Case();
        OwnedProjectionBinding<Object>.Frame.Borrow<String> old = c.frame.get().borrowComputation(() -> "old");
        assertEquals(ActiveRuntimeCoordinator.Result.OK, c.core.runtime.applyConfiguration("generation", 3, "new"));
        assertNull(c.frame.get().borrowComputation(() -> "must not capture"));
        assertFalse(c.frame.get().read(value -> fail("old synchronous read")));
        assertTrue(old.use(value -> assertSame(c.source.pixels, value)));
        c.frame.get().close(); c.producer.drain(); assertEquals(0, c.source.releases);
        old.close(); c.finish(); c.core.idle();
    }
    @Test public void reentrantRetirementDuringFactsCaptureNeverMintsBorrow() throws Exception {
        Case c = new Case();
        assertNull(c.frame.get().borrowComputation(() -> { c.frame.get().close(); return "retired"; }));
        c.finish(); assertEquals(1, c.source.releases); c.core.idle();
    }
    @Test public void exceptionalComputationStillRequiresExplicitBorrowReturn() throws Exception {
        Case c = new Case(); OwnedProjectionBinding<Object>.Frame.Borrow<String> borrow = c.frame.get().borrowComputation(() -> "facts");
        assertThrows(IllegalArgumentException.class, () -> borrow.use(value -> { throw new IllegalArgumentException(); }));
        c.frame.get().close(); c.binding.close(); c.producer.drain(); assertEquals(0, c.source.releases);
        borrow.close(); borrow.close(); c.producer.drain(); c.core.poll(); assertEquals(1, c.source.releases); c.core.idle();
    }
    @Test public void sealFencesQueuedAcquisitionAndEveryNewBorrowBeforeResourceDisposal() throws Exception {
        Case c = new Case(); int reads = c.source.reads;
        AtomicReference<OwnedProjectionBinding<Object>.Frame> rejected = new AtomicReference<>(c.frame.get());
        c.binding.requestFrame(rejected::set); c.binding.seal();
        assertNull(c.frame.get().borrowComputation(() -> "retired"));
        assertFalse(c.frame.get().read(value -> fail("read after source seal")));
        c.producer.drain(); assertNull(rejected.get()); assertEquals(reads, c.source.reads);
        assertEquals(0, c.source.closes); c.finish(); assertEquals(1, c.source.seals); c.core.idle();
    }
    @Test public void failingSealStillAttemptsIndependentCloseAndCannotClaimDrain() throws Exception {
        Case c = new Case(); c.frame.get().close(); c.producer.drain(); c.source.failSeal = true;
        c.binding.close(); c.producer.drain(); c.core.poll();
        assertEquals(1, c.source.seals); assertEquals(1, c.source.closes);
        assertFalse(c.binding.managedResourcesDrained()); c.core.stopping();
        c.binding.close(); assertEquals(1, c.source.seals);
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
}
