package com.subhub.app.censor.android;

import static org.junit.Assert.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.junit.Test;

public final class CensorAccountingReceiptTest {
    @Test public void submitWaitsForActualCallbackEvenWhenWriterAckAlreadyExists() {
        AtomicReference<Runnable> callback = new AtomicReference<>();
        CensorAccountingQueue queue = new CensorAccountingQueue(action -> {
            callback.set(action); return CompletableFuture.completedFuture(null);
        }, error -> fail());
        CompletableFuture<Void> receipt = queue.submit(() -> {});
        queue.close(); assertFalse(receipt.isDone()); assertFalse(queue.drained());
        callback.get().run(); assertTrue(receipt.isDone()); assertTrue(queue.drained());
    }
    @Test public void ackBeforeCallbackReturnCannotAdvanceFrame() throws Exception {
        AtomicReference<Runnable> callback = new AtomicReference<>(); CompletableFuture<Void> writer = new CompletableFuture<>();
        CountDownLatch entered = new CountDownLatch(1), leave = new CountDownLatch(1);
        CensorAccountingQueue queue = new CensorAccountingQueue(action -> { callback.set(action); return writer; }, error -> fail());
        CompletableFuture<Void> receipt = queue.submit(() -> { entered.countDown(); await(leave); });
        Thread worker = new Thread(callback.get()); worker.start(); await(entered);
        try { queue.close(); writer.complete(null); assertFalse(receipt.isDone()); assertFalse(queue.drained()); }
        finally { leave.countDown(); worker.join(5000); }
        assertFalse(worker.isAlive()); assertTrue(receipt.isDone()); assertTrue(queue.drained());
    }
    @Test public void actualReceiptRegistrationReturnIsAlsoRequired() throws Exception {
        CountDownLatch registered = new CountDownLatch(1), returned = new CountDownLatch(1);
        CompletableFuture<Void> writer = new CompletableFuture<Void>() {
            @Override public CompletableFuture<Void> whenComplete(BiConsumer<? super Void, ? super Throwable> action) {
                CompletableFuture<Void> result = super.whenComplete(action);
                registered.countDown(); await(returned); return result;
            }
        };
        writer.complete(null);
        CensorAccountingQueue queue = new CensorAccountingQueue(action -> { action.run(); return writer; }, error -> fail());
        AtomicReference<CompletableFuture<Void>> receipt = new AtomicReference<>();
        Thread submitter = new Thread(() -> receipt.set(queue.submit(() -> {}))); submitter.start(); await(registered);
        try { queue.close(); assertFalse(queue.drained()); assertNull(receipt.get()); }
        finally { returned.countDown(); submitter.join(5000); }
        assertFalse(submitter.isAlive()); assertTrue(receipt.get().isDone()); assertTrue(queue.drained());
    }
    @Test public void cancellationOrDirectCompletionCannotEraseQueuedWrite() {
        AtomicReference<Runnable> callback = new AtomicReference<>(); CompletableFuture<Void> writer = new CompletableFuture<>();
        CensorAccountingQueue queue = new CensorAccountingQueue(action -> { callback.set(action); return writer; }, error -> fail());
        CompletableFuture<Void> receipt = queue.submit(() -> {});
        assertFalse(receipt.cancel(true)); assertFalse(receipt.complete(null));
        assertFalse(receipt.completeExceptionally(new IllegalStateException("observer")));
        queue.close(); assertFalse(receipt.isDone()); callback.get().run(); assertFalse(receipt.isDone());
        writer.complete(null); assertTrue(receipt.isDone()); assertTrue(queue.drained());
    }
    @Test public void wrongOwnerWriteFailureIsNeverSuccessfulReceiptOrDrain() {
        AtomicReference<Runnable> callback = new AtomicReference<>(); CompletableFuture<Void> writer = new CompletableFuture<>();
        CensorAccountingQueue queue = new CensorAccountingQueue(action -> { callback.set(action); return writer; }, error -> {});
        CompletableFuture<Void> receipt = queue.submit(() -> { throw new SecurityException("wrong owner"); });
        assertThrows(SecurityException.class, () -> callback.get().run());
        writer.completeExceptionally(new SecurityException("wrong owner")); queue.close();
        assertTrue(receipt.isCompletedExceptionally()); assertFalse(queue.drained());
    }
    @Test public void ambiguousRegistrationThrowRetainsAlreadyReturnedCallback() {
        CompletableFuture<Void> writer = new CompletableFuture<Void>() {
            @Override public CompletableFuture<Void> whenComplete(BiConsumer<? super Void, ? super Throwable> action) {
                super.whenComplete(action); throw new IllegalStateException("registered then threw");
            }
        };
        writer.complete(null);
        CensorAccountingQueue queue = new CensorAccountingQueue(action -> { action.run(); return writer; }, error -> {});
        assertThrows(IllegalStateException.class, () -> queue.submit(() -> {}));
        queue.close(); assertFalse(queue.drained());
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
}
