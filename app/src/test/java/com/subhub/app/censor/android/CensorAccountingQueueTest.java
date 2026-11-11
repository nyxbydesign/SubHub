package com.subhub.app.censor.android;

import static org.junit.Assert.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class CensorAccountingQueueTest {
    @Test public void provenPreDispatchRefusalDoesNotInventAnUndrainableCallback() {
        CensorAccountingQueue queue = new CensorAccountingQueue(action -> {
            throw new CensorAccountingQueue.AdmissionRefused(new SecurityException("Revoked before enqueue"));
        }, error -> fail("Definite refusal is not an uncertain callback"));
        assertThrows(CensorAccountingQueue.AdmissionRefused.class, () -> queue.execute(() -> fail("Never enqueued")));
        queue.close(); assertTrue(queue.drained());
    }

    @Test public void contradictoryRefusalAfterCallbackStartedIsNeverProofOfDrain() {
        AtomicInteger calls = new AtomicInteger(), failures = new AtomicInteger();
        CensorAccountingQueue queue = new CensorAccountingQueue(action -> {
            action.run(); throw new CensorAccountingQueue.AdmissionRefused(new IllegalStateException("Invalid receipt"));
        }, error -> failures.incrementAndGet());
        assertThrows(CensorAccountingQueue.AdmissionRefused.class, () -> queue.execute(calls::incrementAndGet));
        queue.close(); assertEquals(1, calls.get()); assertEquals(1, failures.get()); assertFalse(queue.drained());
    }

    @Test public void boundedProductWorkDrainsAfterProvenStopAdmissionRefusal() {
        CensorAccountingQueue queue = new CensorAccountingQueue(action -> {
            throw new CensorAccountingQueue.AdmissionRefused(new SecurityException("Stop fenced writer"));
        }, error -> fail());
        AtomicInteger refusals = new AtomicInteger();
        com.subhub.app.censor.engine.BoundedLatestWork<String> product = new com.subhub.app.censor.engine.BoundedLatestWork<>(
                queue, value -> fail("Unadmitted product callback"), error -> refusals.incrementAndGet());
        product.offer("observed-before-stop"); product.close(); queue.close();
        assertEquals(1, refusals.get()); assertTrue(product.drained()); assertTrue(queue.drained());
    }

    @Test public void closingWaitsForCallbackAndAcknowledgedWrite() {
        AtomicReference<Runnable> callback = new AtomicReference<>(); CompletableFuture<Void> receipt = new CompletableFuture<>();
        CensorAccountingQueue queue = new CensorAccountingQueue(action -> { callback.set(action); return receipt; }, error -> fail());
        AtomicInteger count = new AtomicInteger(); queue.execute(count::incrementAndGet); queue.close();
        assertFalse(queue.drained()); callback.get().run(); assertEquals(1, count.get()); assertFalse(queue.drained());
        receipt.complete(null); assertTrue(queue.drained());
        assertThrows(RejectedExecutionException.class, () -> queue.execute(count::incrementAndGet));
        assertEquals(1, count.get());
    }

    @Test public void receiptAloneCannotInventActualCallbackReturn() {
        AtomicReference<Runnable> callback = new AtomicReference<>();
        CensorAccountingQueue queue = new CensorAccountingQueue(action -> {
            callback.set(action); return CompletableFuture.completedFuture(null);
        }, error -> fail());
        queue.execute(() -> { }); queue.close(); assertFalse(queue.drained());
        callback.get().run(); assertTrue(queue.drained());
    }

    @Test public void closeDoesNotWaitForBlockedAdmittedCallback() throws Exception {
        AtomicReference<Runnable> callback = new AtomicReference<>(); CompletableFuture<Void> receipt = new CompletableFuture<>();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CensorAccountingQueue queue = new CensorAccountingQueue(action -> { callback.set(action); return receipt; }, error -> fail());
        queue.execute(() -> { entered.countDown(); await(release); });
        FutureTask<Void> running = new FutureTask<>(callback.get(), null); Thread worker = new Thread(running); worker.start();
        await(entered);
        try {
            FutureTask<Void> close = new FutureTask<>(queue::close, null); new Thread(close).start(); close.get(2, TimeUnit.SECONDS);
            receipt.complete(null); assertFalse(queue.drained());
        } finally { release.countDown(); }
        running.get(2, TimeUnit.SECONDS); assertTrue(queue.drained());
    }

    @Test public void failedReceiptRetainsObligationAndReportsOnlyOnce() {
        AtomicReference<Runnable> callback = new AtomicReference<>(); CompletableFuture<Void> receipt = new CompletableFuture<>();
        AtomicInteger failures = new AtomicInteger();
        CensorAccountingQueue queue = new CensorAccountingQueue(action -> { callback.set(action); return receipt; }, error -> failures.incrementAndGet());
        queue.execute(() -> { }); callback.get().run(); receipt.completeExceptionally(new IllegalStateException("Uncertain write"));
        queue.close(); assertFalse(queue.drained()); assertEquals(1, failures.get());
        assertThrows(RejectedExecutionException.class, () -> queue.execute(() -> { }));
    }

    @Test public void uncertainDispatchStillWaitsForActualQueuedCallback() {
        AtomicReference<Runnable> callback = new AtomicReference<>(); AtomicInteger failures = new AtomicInteger(), calls = new AtomicInteger();
        CensorAccountingQueue queue = new CensorAccountingQueue(action -> {
            callback.set(action); throw new IllegalStateException("Queued outcome uncertain");
        }, error -> failures.incrementAndGet());
        assertThrows(IllegalStateException.class, () -> queue.execute(calls::incrementAndGet));
        queue.close(); assertFalse(queue.drained()); callback.get().run();
        assertEquals(1, calls.get()); assertEquals(1, failures.get()); assertFalse(queue.drained());
    }

    @Test public void duplicateCallbackCannotRepeatAccounting() {
        AtomicReference<Runnable> callback = new AtomicReference<>(); AtomicInteger calls = new AtomicInteger();
        CensorAccountingQueue queue = new CensorAccountingQueue(action -> {
            callback.set(action); return CompletableFuture.completedFuture(null);
        }, error -> fail());
        queue.execute(calls::incrementAndGet); callback.get().run(); callback.get().run(); queue.close();
        assertEquals(1, calls.get()); assertTrue(queue.drained());
    }

    @Test public void eachAcceptedCallbackNeedsItsOwnReceipt() {
        java.util.List<Runnable> actions = new java.util.ArrayList<>();
        java.util.List<CompletableFuture<Void>> receipts = new java.util.ArrayList<>();
        CensorAccountingQueue queue = new CensorAccountingQueue(action -> {
            actions.add(action); CompletableFuture<Void> receipt = new CompletableFuture<>(); receipts.add(receipt); return receipt;
        }, error -> fail());
        queue.execute(() -> { }); queue.execute(() -> { }); queue.close();
        actions.get(0).run(); receipts.get(0).complete(null); assertFalse(queue.drained());
        receipts.get(1).complete(null); assertFalse(queue.drained()); actions.get(1).run(); assertTrue(queue.drained());
    }

    @Test public void eventClosureRetainsItsAdmissionFactsAfterConfigurationChanges() {
        AtomicReference<String> current = new AtomicReference<>("old-rules"); AtomicReference<String> assessed = new AtomicReference<>();
        AtomicReference<Runnable> callback = new AtomicReference<>();
        CensorAccountingQueue queue = new CensorAccountingQueue(action -> {
            callback.set(action); return CompletableFuture.completedFuture(null);
        }, error -> fail());
        String admittedRules = current.get(); queue.execute(() -> assessed.set(admittedRules));
        current.set("new-rules"); callback.get().run(); queue.close();
        assertEquals("old-rules", assessed.get()); assertTrue(queue.drained());
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException failed) { Thread.currentThread().interrupt(); throw new AssertionError(failed); }
    }
}
