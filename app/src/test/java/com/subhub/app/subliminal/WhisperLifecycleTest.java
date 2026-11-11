package com.subhub.app.subliminal;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Runs the real production lifecycle with deterministic queue/window failure-injection ports. */
public final class WhisperLifecycleTest {
    @Test public void attachmentThatThrowsAfterAddingIsStillOwnedAndDetached() {
        Fixture f = new Fixture(); f.window.partialAddFailure = true;
        assertThrows(IllegalStateException.class, () -> f.life.runOrRetry(f.apply, f.life::ensureAdded));
        assertTrue(f.window.attached); assertTrue(f.life.hasWindow()); assertEquals(1, f.effects.failures);
        f.close();
        assertFalse(f.window.attached); assertEquals(1, f.window.removals);
        assertTrue(f.life.drained()); assertEquals(1, f.receipts.get());
    }
    @Test public void attemptedAddBeforeAttachmentStillRequiresARealDetachProbe() {
        Fixture f = new Fixture(); f.window.earlyAddFailure = true;
        assertThrows(IllegalStateException.class, () -> f.life.runOrRetry(f.apply, f.life::ensureAdded));
        f.window.removeAbsent = true;
        f.close(); // IllegalArgumentException is harmless ONLY when both probes establish absence.
        assertEquals(1, f.window.removals); assertTrue(f.life.drained());
    }
    @Test public void illegalArgumentWhileStillAttachedCannotAcknowledgeClosure() {
        Fixture f = attached(); f.window.removeAbsent = true;
        assertThrows(IllegalArgumentException.class, f::close);
        assertFalse(f.life.drained()); assertEquals(0, f.receipts.get()); assertTrue(f.life.hasWindow());
    }
    @Test public void removalThatReturnsWithoutDetachingCannotAcknowledgeClosure() {
        Fixture f = attached(); f.window.silentRemoveFailure = true;
        assertThrows(IllegalStateException.class, f::close);
        assertFalse(f.life.drained()); assertEquals(0, f.receipts.get());
    }
    @Test public void unexpectedDetachExceptionPropagatesAndRetainsOwnership() {
        Fixture f = attached(); f.window.removeFailure = true;
        assertThrows(SecurityException.class, f::close);
        assertFalse(f.life.drained()); assertEquals(0, f.receipts.get());
    }
    @Test public void repeatedCloseRetriesFailedDetachAndAcknowledgesOnlyOnce() {
        Fixture f = attached(); f.window.silentRemoveFailure = true;
        assertThrows(IllegalStateException.class, f::close);
        f.window.silentRemoveFailure = false;
        f.life.close(); f.life.close();
        assertTrue(f.life.drained()); assertEquals(1, f.receipts.get()); assertEquals(2, f.window.removals);
    }
    @Test public void hideFailureDoesNotSkipDetachAndCannotPretendToBeDrained() {
        Fixture f = attached(); f.window.hideFailure = true;
        assertThrows(IllegalStateException.class, f::close);
        assertFalse(f.window.attached); assertFalse(f.life.drained()); assertEquals(0, f.receipts.get());
        f.window.hideFailure = false; f.life.close();
        assertTrue(f.life.drained()); assertEquals(1, f.receipts.get());
    }
    @Test public void busyEligibilityIsRetainedUntilItCanAttachWithoutAnotherEvent() {
        Fixture f = new Fixture(); f.effects.busy = true;
        assertFalse(f.life.runOrRetry(f.apply, f.life::ensureAdded));
        assertFalse(f.window.attached); assertEquals(1, f.life.pendingCallbacks());
        f.queue.next(); assertEquals(1, f.life.pendingCallbacks());
        f.effects.busy = false; f.queue.next();
        assertTrue(f.window.attached); assertEquals(1, f.window.adds); assertEquals(0, f.life.pendingCallbacks());
    }
    @Test public void busySettingsReturnsFalseAndRetriesTheSameUnappliedRequest() {
        Fixture f = new Fixture(); AtomicInteger revision = new AtomicInteger(2); f.effects.busy = true;
        assertFalse(f.life.runOrRetry(f.apply, () -> revision.set(3)));
        assertEquals(2, revision.get());
        f.effects.busy = false; f.queue.next();
        assertEquals(3, revision.get()); assertEquals(0, f.life.pendingCallbacks());
    }
    @Test public void newerSettingsSupersedesPendingOldSettingsAndItsStaleCallback() {
        Fixture f = new Fixture(); AtomicInteger revision = new AtomicInteger(2); f.effects.busy = true;
        assertFalse(f.life.runOrRetry(f.apply, () -> revision.set(3)));
        Runnable old = f.queue.first();
        f.effects.busy = false;
        assertTrue(f.life.runOrRetry(f.apply, () -> revision.set(4)));
        int admissions = f.effects.attempts;
        old.run(); assertEquals(4, revision.get()); assertEquals(admissions, f.effects.attempts);
        assertEquals(0, f.queue.callbacks.size()); assertEquals(0, f.life.pendingCallbacks());
    }
    @Test public void cancelledEligibilityCannotAttachFromALateRetry() {
        Fixture f = new Fixture(); f.effects.busy = true;
        f.life.runOrRetry(f.apply, f.life::ensureAdded); Runnable old = f.queue.first();
        f.life.cancel(f.apply); f.effects.busy = false; old.run();
        assertEquals(0, f.window.adds); assertEquals(0, f.life.pendingCallbacks());
    }
    @Test public void revocationCancelsBusyRetryAndDetachesWithoutAnotherAdmission() {
        Fixture f = attached(); f.effects.busy = true;
        f.life.runOrRetry(f.apply, () -> fail("Revoked request ran"));
        f.effects.revoked = true; int attempts = f.effects.attempts;
        f.queue.next();
        assertEquals(attempts, f.effects.attempts); assertFalse(f.window.attached);
        assertTrue(f.life.drained()); assertEquals(0, f.queue.callbacks.size());
    }
    @Test public void revocationBetweenAdmissionAndCallbackBodyCannotRenderOrRetry() {
        Fixture f = new Fixture(); f.effects.revokeInsideAdmission = true;
        assertFalse(f.life.runOrRetry(f.apply, () -> fail("Revoked body")));
        assertTrue(f.life.drained()); assertEquals(0, f.queue.callbacks.size());
    }
    @Test public void stopCancelsEveryQueueAndStaleAnimationCannotReopenOrCount() {
        Fixture f = attached(); AtomicInteger impressions = new AtomicInteger();
        Object fade = new Object(), show = new Object(), display = new Object();
        f.life.post(show, f.life::ensureAdded, 10_000);
        f.life.post(display, impressions::incrementAndGet, 0);
        f.life.post(fade, () -> f.life.post(display, impressions::incrementAndGet, 0), 16);
        List<Runnable> stale = new ArrayList<>(f.queue.callbacks.keySet());
        f.close(); int attempts = f.effects.attempts;
        for (Runnable callback : stale) callback.run();
        assertEquals(0, impressions.get()); assertEquals(attempts, f.effects.attempts);
        assertEquals(0, f.queue.callbacks.size()); assertEquals(0, f.life.pendingCallbacks());
        assertEquals(1, f.receipts.get()); assertTrue(f.life.drained());
    }
    @Test public void reentrantCloseWaitsUntilAdmittedCallbackActuallyFinishes() {
        Fixture f = attached(); List<String> order = new ArrayList<>();
        assertTrue(f.life.runOrRetry(f.apply, () -> {
            f.life.close(() -> order.add("drained"));
            assertFalse(f.life.drained()); assertEquals(1, f.life.pendingCallbacks());
            assertEquals(List.of(), order); order.add("body returned");
        }));
        assertEquals(List.of("body returned", "drained"), order); assertTrue(f.life.drained());
    }
    @Test public void closeInEffectFinallyStillWaitsForTheCallerCallback() {
        Fixture f = attached(); f.effects.afterEffect = f::close;
        assertTrue(f.life.runOrRetry(f.apply, () -> { }));
        assertEquals(1, f.receipts.get()); assertTrue(f.life.drained());
        assertTrue(f.effects.observedPendingDuringClose);
    }
    @Test public void failedCallbackRemovalRetainsTicketUntilActualStaleCallbackReturn() {
        Fixture f = attached(); f.life.post(f.apply, () -> fail("Cancelled callback"), 5);
        f.queue.removeFailure = true;
        assertThrows(IllegalStateException.class, f::close);
        assertFalse(f.window.attached); assertFalse(f.life.drained()); assertEquals(0, f.receipts.get());
        f.queue.next();
        assertTrue(f.life.drained()); assertEquals(1, f.receipts.get());
    }
    @Test public void failedCallbackRemovalCanBeRetriedWithoutLosingOriginalReceipt() {
        Fixture f = attached(); f.life.post(f.apply, () -> fail("Cancelled callback"), 5);
        f.queue.removeFailure = true; assertThrows(IllegalStateException.class, f::close);
        f.queue.removeFailure = false; f.life.close();
        assertEquals(0, f.queue.callbacks.size()); assertTrue(f.life.drained()); assertEquals(1, f.receipts.get());
    }
    @Test public void refusedQueueCannotSilentlyLoseAnUnappliedSettingsRequest() {
        Fixture f = new Fixture(); f.effects.busy = true; f.queue.rejectPost = true;
        assertThrows(IllegalStateException.class, () -> f.life.runOrRetry(f.apply, () -> fail("Busy body")));
        assertTrue(f.effects.failures > 0); assertEquals(0, f.life.pendingCallbacks());
    }
    @Test public void aCallbackMayReplaceItsOwnKeyWithoutCancellingTheNewFrame() {
        Fixture f = new Fixture(); AtomicInteger frames = new AtomicInteger(); Object fade = new Object();
        f.life.post(fade, () -> { frames.incrementAndGet(); f.life.post(fade, frames::incrementAndGet, 16); }, 16);
        f.queue.next(); assertEquals(1, frames.get()); assertEquals(1, f.life.pendingCallbacks());
        f.queue.next(); assertEquals(2, frames.get()); assertEquals(0, f.life.pendingCallbacks());
    }
    @Test public void oldOwnerCallbacksCannotMutateOrDrainAReplacement() {
        Fixture old = attached(), replacement = attached();
        old.life.post(old.apply, () -> fail("Old callback"), 16); Runnable stale = old.queue.first();
        old.close(); replacement.life.post(replacement.apply, () -> { }, 16);
        stale.run();
        assertEquals(1, old.receipts.get()); assertEquals(0, replacement.receipts.get());
        assertTrue(replacement.window.attached); assertEquals(1, replacement.life.pendingCallbacks());
    }
    @Test public void lateRuntimeCloseCanAttachReceiptAfterRevocationAlreadyDrained() {
        Fixture f = attached(); f.effects.revoked = true;
        assertFalse(f.life.runOrRetry(f.apply, () -> fail("Revoked request")));
        assertTrue(f.life.drained()); f.close(); assertEquals(1, f.receipts.get());
    }

    private static Fixture attached() {
        Fixture f = new Fixture(); assertTrue(f.life.runOrRetry(f.apply, f.life::ensureAdded)); return f;
    }
    private static final class Fixture {
        final FakeEffects effects = new FakeEffects();
        final FakeQueue queue = new FakeQueue();
        final FakeWindow window = new FakeWindow();
        final WhisperLifecycle life = new WhisperLifecycle(effects, queue, window);
        final Object apply = new Object();
        final AtomicInteger receipts = new AtomicInteger();
        final Runnable receipt = receipts::incrementAndGet;
        Fixture() { effects.life = life; }
        void close() { life.close(receipt); }
    }
    private static final class FakeEffects implements WhisperLifecycle.Effects {
        boolean busy, revoked, revokeInsideAdmission, observedPendingDuringClose;
        int attempts, failures;
        Runnable afterEffect;
        WhisperLifecycle life;
        @Override public boolean run(Runnable effect) {
            attempts++; if (busy || revoked) return false;
            if (revokeInsideAdmission) revoked = true;
            effect.run();
            if (afterEffect != null) {
                afterEffect.run(); observedPendingDuringClose = !life.drained();
            }
            return true;
        }
        @Override public boolean revoked() { return revoked; }
        @Override public void failed() { failures++; }
    }
    private static final class FakeQueue implements WhisperLifecycle.Queue {
        final Map<Runnable, Long> callbacks = new LinkedHashMap<>();
        boolean rejectPost, removeFailure;
        @Override public boolean post(Runnable callback, long delay) {
            if (rejectPost) return false;
            assertNull(callbacks.put(callback, delay)); return true;
        }
        @Override public void remove(Runnable callback) {
            if (removeFailure) throw new IllegalStateException("Injected queue removal failure");
            callbacks.remove(callback);
        }
        Runnable first() { return callbacks.keySet().iterator().next(); }
        void next() { Runnable callback = first(); callbacks.remove(callback); callback.run(); }
    }
    private static final class FakeWindow implements WhisperLifecycle.Window {
        boolean attached, partialAddFailure, earlyAddFailure, removeAbsent, silentRemoveFailure;
        boolean removeFailure, hideFailure;
        int adds, removals;
        @Override public void add() {
            adds++;
            if (earlyAddFailure) throw new IllegalStateException("Injected pre-attachment failure");
            attached = true;
            if (partialAddFailure) throw new IllegalStateException("Injected partial attach failure");
        }
        @Override public boolean attached() { return attached; }
        @Override public void remove() {
            removals++;
            if (removeAbsent) throw new IllegalArgumentException("Injected absent-window response");
            if (removeFailure) throw new SecurityException("Injected window removal failure");
            if (!silentRemoveFailure) attached = false;
        }
        @Override public void hide() {
            if (hideFailure) throw new IllegalStateException("Injected hide failure");
        }
    }
}
