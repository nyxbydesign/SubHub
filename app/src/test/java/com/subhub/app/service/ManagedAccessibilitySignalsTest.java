package com.subhub.app.service;

import static org.junit.Assert.*;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class ManagedAccessibilitySignalsTest {
    @Test public void liveDeliveryIsSynchronous() {
        AtomicInteger observed = new AtomicInteger();
        ManagedAccessibilitySignals<Integer> relay = new ManagedAccessibilitySignals<>(observed::set);
        relay.deliver(7); assertEquals(7, observed.get()); assertFalse(relay.drained());
        relay.detach(); assertTrue(relay.drained());
    }
    @Test public void detachedFrameworkRelayHasNoOwnerTarget() throws Exception {
        ManagedAccessibilitySignals<Object> relay = new ManagedAccessibilitySignals<>(value -> fail("late delivery"));
        relay.detach(); relay.deliver(new Object()); relay.detach();
        Field target = ManagedAccessibilitySignals.class.getDeclaredField("target"); target.setAccessible(true);
        assertNull(target.get(relay)); assertTrue(relay.drained());
    }
    @Test public void reentrantDetachWaitsForActualReceiverReturn() {
        @SuppressWarnings({"unchecked", "rawtypes"}) ManagedAccessibilitySignals<Object>[] holder = new ManagedAccessibilitySignals[1];
        holder[0] = new ManagedAccessibilitySignals<>(value -> {
            holder[0].detach(); assertFalse(holder[0].drained());
            holder[0].deliver(value); // Detached reentrancy must not invoke the target again.
        });
        holder[0].deliver(new Object()); assertTrue(holder[0].drained());
    }
    @Test public void concurrentDetachCannotRetireBorrowedTarget() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ManagedAccessibilitySignals<Object> relay = new ManagedAccessibilitySignals<>(value -> {
            entered.countDown(); await(release);
        });
        Thread thread = new Thread(() -> relay.deliver(new Object())); thread.start(); entered.await();
        relay.detach(); assertFalse(relay.drained()); release.countDown(); thread.join(); assertTrue(relay.drained());
    }
    @Test public void callbacksOfOldGenerationCannotResolveNewTarget() {
        AtomicInteger oldCount = new AtomicInteger(), newCount = new AtomicInteger();
        ManagedAccessibilitySignals<String> old = new ManagedAccessibilitySignals<>(ignored -> oldCount.incrementAndGet());
        old.detach(); ManagedAccessibilitySignals<String> next = new ManagedAccessibilitySignals<>(ignored -> newCount.incrementAndGet());
        old.deliver("late"); next.deliver("new"); assertEquals(0, oldCount.get()); assertEquals(1, newCount.get());
        next.detach(); assertTrue(old.drained()); assertTrue(next.drained());
    }
    @Test public void receiverFailureReturnsBorrowButNeverClaimsSuccess() {
        ManagedAccessibilitySignals<Object> relay = new ManagedAccessibilitySignals<>(value -> {throw new IllegalStateException("uncertain");});
        assertThrows(IllegalStateException.class, () -> relay.deliver(new Object()));
        relay.detach(); assertTrue(relay.failed()); assertFalse(relay.drained());
    }
    @Test public void receiverErrorAlsoRetainsFailure() {
        ManagedAccessibilitySignals<Object> relay = new ManagedAccessibilitySignals<>(value -> {throw new AssertionError("uncertain");});
        assertThrows(AssertionError.class, () -> relay.deliver(new Object()));
        relay.detach(); assertTrue(relay.failed()); assertFalse(relay.drained());
    }
    @Test public void nestedLiveCallbacksEachHoldReturn() {
        AtomicInteger depth = new AtomicInteger();
        @SuppressWarnings({"unchecked", "rawtypes"}) ManagedAccessibilitySignals<Object>[] holder = new ManagedAccessibilitySignals[1];
        holder[0] = new ManagedAccessibilitySignals<>(value -> {
            if (depth.incrementAndGet() == 1) {holder[0].deliver(value); assertFalse(holder[0].drained());}
            else {holder[0].detach(); assertFalse(holder[0].drained());}
        });
        holder[0].deliver(new Object()); assertEquals(2, depth.get()); assertTrue(holder[0].drained());
    }
    private static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException interrupted) {Thread.currentThread().interrupt(); throw new AssertionError(interrupted);}
    }
}
