package com.subhub.app.service;

import static org.junit.Assert.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.subhub.app.runtime.ActiveRuntimeCoordinator;
import org.junit.Test;

/** Real component/borrow control flow; fake platform delivery, never bound Android evidence. */
public final class OwnedLegacyAccessibilityBindingTest {
    @Test public void startupDoesNotRequestScreenshotsAndNormalRequestsNeedActive() throws Exception {
        Fixture f = new Fixture(); f.binding.start();
        assertEquals(0, f.source.requests);
        assertFalse(f.binding.requestFrame(frame -> fail("No ACTIVE owner")));
        f.core.activate(); assertTrue(f.binding.requestFrame(frame -> { assertNotNull(frame); frame.close(); }));
        f.source.succeed(); f.callbacks.drain(); assertEquals(1, f.source.releases);
        f.binding.close(); f.core.poll(); assertTrue(f.binding.managedResourcesDrained()); f.core.idle();
    }

    @Test public void lateScreenshotAfterStopIsDisposedWithoutReadingAndCompletesRequest() throws Exception {
        Fixture f = new Fixture(); f.start(); AtomicInteger completion = new AtomicInteger();
        assertTrue(f.binding.requestFrame(frame -> { assertNull(frame); completion.incrementAndGet(); }));
        f.binding.close(); f.callbacks.drain(); f.core.poll(); f.core.stopping();
        assertFalse(f.binding.managedResourcesDrained()); assertFalse(f.callbacks.isShutdown());
        f.source.succeed(); f.callbacks.drain(); f.core.poll();
        assertEquals(1, f.source.releases); assertEquals(1, completion.get()); f.core.idle();
    }

    @Test public void heldBorrowBlocksDrainAndStopImmediatelyFencesRead() throws Exception {
        Fixture f = new Fixture(); f.start();
        AtomicReference<OwnedLegacyAccessibilityBinding<Object>.Frame> held = new AtomicReference<>();
        f.binding.requestFrame(held::set); f.source.succeed(); f.callbacks.drain();
        assertTrue(held.get().read(value -> assertSame(f.source.pixels, value)));
        f.binding.close(); assertFalse(held.get().read(value -> fail("Stop allowed raw read")));
        assertEquals(0, f.source.releases); assertFalse(f.binding.managedResourcesDrained());
        held.get().close(); held.get().close(); f.callbacks.drain(); f.core.poll();
        assertEquals(1, f.source.releases); f.core.idle();
    }

    @Test public void missingTerminalCallbackKeepsActualObligationWithoutTimeoutRelease() throws Exception {
        Fixture f = new Fixture(); f.start();
        f.binding.requestFrame(frame -> assertNull(frame)); f.binding.close();
        for (int i = 0; i < 100; i++) { f.callbacks.drain(); f.core.poll(); }
        assertFalse(f.binding.managedResourcesDrained()); assertFalse(f.callbacks.isShutdown()); f.core.stopping();
        f.source.fail(); f.callbacks.drain(); f.core.poll(); f.core.idle();
    }

    @Test public void apiThrowAfterAcceptedRequestRetainsUntilActualCallback() throws Exception {
        Fixture f = new Fixture(); f.start(); f.source.throwAfterRequest = true;
        AtomicInteger completed = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> f.binding.requestFrame(frame -> {
            assertNull(frame); completed.incrementAndGet();
        }));
        assertFalse(f.binding.managedResourcesDrained()); f.core.stopping();
        f.source.succeed(); f.callbacks.drain(); f.core.poll();
        assertEquals(1, f.source.releases); assertEquals(1, completed.get()); f.core.idle();
    }

    @Test public void configurationEditRejectsOldCaptureButStillCompletesIt() throws Exception {
        Fixture f = new Fixture(); f.start(); AtomicInteger completed = new AtomicInteger();
        f.binding.requestFrame(frame -> { assertNull(frame); completed.incrementAndGet(); });
        assertEquals(ActiveRuntimeCoordinator.Result.OK, f.core.runtime.applyConfiguration("generation", 3, "next"));
        f.source.succeed(); f.callbacks.drain(); assertEquals(1, completed.get()); assertEquals(1, f.source.releases);
        f.binding.close(); f.core.poll(); f.core.idle();
    }

    @Test public void disconnectedSourceCannotDispatchAScreenshot() throws Exception {
        Fixture f = new Fixture(); f.start();
        f.source.connected = false;
        assertFalse(f.binding.requestFrame(frame -> fail("Lost source started a screenshot")));
        assertEquals(0, f.source.requests);
        f.callbacks.drain(); f.core.poll(); f.core.idle();
    }

    @Test public void disconnectedSourceCannotPublishItsPendingScreenshot() throws Exception {
        Fixture f = new Fixture(); f.start(); AtomicInteger completed = new AtomicInteger();
        assertTrue(f.binding.requestFrame(frame -> { assertNull(frame); completed.incrementAndGet(); }));
        f.source.connected = false;
        assertFalse(f.binding.managedResourcesDrained());
        f.source.succeed(); f.callbacks.drain(); f.core.poll();
        assertEquals(1, completed.get()); assertEquals(1, f.source.releases); f.core.idle();
    }

    @Test public void disconnectedSourceFencesAnAlreadyTransferredRead() throws Exception {
        Fixture f = new Fixture(); f.start();
        AtomicReference<OwnedLegacyAccessibilityBinding<Object>.Frame> held = new AtomicReference<>();
        assertTrue(f.binding.requestFrame(held::set)); f.source.succeed(); f.callbacks.drain();
        f.source.connected = false;
        assertFalse(held.get().read(pixels -> fail("Lost source exposed a raw frame")));
        f.core.poll(); f.core.stopping();
        assertEquals(0, f.source.releases);
        held.get().close(); f.callbacks.drain(); f.core.poll();
        assertEquals(1, f.source.releases); f.core.idle();
    }

    @Test public void failedHardwareDisposalCannotPublishDrain() throws Exception {
        Fixture f = new Fixture(); f.start();
        AtomicReference<OwnedLegacyAccessibilityBinding<Object>.Frame> held = new AtomicReference<>();
        f.binding.requestFrame(held::set); f.source.succeed(); f.callbacks.drain();
        f.source.failRelease = true; f.binding.close(); held.get().close(); f.callbacks.drain(); f.core.poll();
        assertFalse(f.binding.managedResourcesDrained()); f.core.stopping(); assertTrue(f.core.runtime.cleanupNeedsAttention());
    }

    @Test public void rawReadBorrowOutlivesReentrantCloseUntilItsActualReturn() throws Exception {
        Fixture f = new Fixture(); f.start();
        AtomicReference<OwnedLegacyAccessibilityBinding<Object>.Frame> held = new AtomicReference<>();
        f.binding.requestFrame(held::set); f.source.succeed(); f.callbacks.drain();
        assertTrue(held.get().read(value -> {
            f.binding.close(); held.get().close(); f.callbacks.drain();
            assertEquals(0, f.source.releases); assertFalse(f.binding.managedResourcesDrained());
        }));
        f.callbacks.drain(); f.core.poll(); assertEquals(1, f.source.releases); f.core.idle();
    }

    @Test public void callbackEnqueueThenThrowRetainsCleanupFailureEvenAfterActualTerminalReturn() throws Exception {
        Fixture f = new Fixture(); f.start(); AtomicInteger completed = new AtomicInteger();
        f.binding.requestFrame(frame -> { assertNull(frame); completed.incrementAndGet(); });
        f.callbacks.throwAfterEnqueue = true;
        assertThrows(IllegalStateException.class, f.source::succeed);
        assertFalse(f.binding.managedResourcesDrained()); f.core.stopping();
        f.callbacks.drain(); f.core.poll(); assertEquals(1, completed.get()); assertEquals(1, f.source.releases);
        assertFalse(f.binding.managedResourcesDrained()); assertTrue(f.core.runtime.cleanupNeedsAttention());
    }

    @Test public void sourceCanRetireBeforeStartupWithoutNativeCaptureOrForcedCallbackCancellation() throws Exception {
        Fixture f = new Fixture(); f.binding.close(); f.core.poll();
        assertEquals(0, f.source.requests); assertEquals(0, f.source.releases); f.core.idle();
    }

    private static final class Fixture {
        final OwnedProjectionBindingTest.Fixture core;
        final OwnedProjectionBindingTest.Queue callbacks = new OwnedProjectionBindingTest.Queue();
        final Source source = new Source();
        final OwnedLegacyAccessibilityBinding<Object> binding;
        Fixture() throws Exception {
            core = new OwnedProjectionBindingTest.Fixture(OwnedLegacyAccessibilityBinding.COMPONENT);
            binding = new OwnedLegacyAccessibilityBinding<>(core.runtime, source, callbacks, core::schedule);
        }
        void start() { binding.start(); core.activate(); }
    }
    private static final class Source implements OwnedLegacyAccessibilityBinding.Endpoint<Object> {
        final Object pixels = new Object();
        Executor executor; OwnedLegacyAccessibilityBinding.Callback<Object> callback;
        int requests, releases; boolean connected = true, failRelease, throwAfterRequest;
        @Override public boolean connected() { return connected; }
        @Override public void request(Executor delivery, OwnedLegacyAccessibilityBinding.Callback<Object> callback) {
            requests++; executor = delivery; this.callback = callback;
            if (throwAfterRequest) throw new IllegalStateException("Accepted native request then threw");
        }
        void succeed() { executor.execute(() -> callback.success(pixels)); }
        void fail() { executor.execute(callback::failure); }
        @Override public void release(Object value) {
            assertSame(pixels, value); releases++;
            if (failRelease) throw new IllegalStateException("Hardware close failed");
        }
    }
}
