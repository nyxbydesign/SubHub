package com.subhub.app.service;

import static org.junit.Assert.*;
import android.os.PowerManager;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;

/** Pure callback/registration controller; no actual Android thermal registration or native claim. */
public final class CaptureLoadGovernorOwnershipTest {
    @Test public void retainedPlatformCallbackIsInertAfterClose() {
        Platform p = new Platform(); AtomicInteger delivered = new AtomicInteger();
        CaptureLoadGovernor.OwnedThermal owner = new CaptureLoadGovernor.OwnedThermal(delivered::set, p);
        owner.start(); PowerManager.OnThermalStatusChangedListener retained = p.callback;
        retained.onThermalStatusChanged(3); assertEquals(3, delivered.get());
        owner.close(); assertTrue(owner.drained()); retained.onThermalStatusChanged(6);
        assertEquals(3, delivered.get()); assertEquals(1, p.removals); owner.close(); assertEquals(1, p.removals);
    }
    @Test public void closeWaitsActualAdmittedCallbackReturn() throws Exception {
        Platform p = new Platform(); CountDownLatch entered = new CountDownLatch(1), leave = new CountDownLatch(1);
        CaptureLoadGovernor.OwnedThermal owner = new CaptureLoadGovernor.OwnedThermal(status -> { entered.countDown(); await(leave); }, p);
        owner.start(); Thread callback = new Thread(() -> p.callback.onThermalStatusChanged(4)); callback.start();
        try { assertTrue(entered.await(5, TimeUnit.SECONDS)); owner.close(); assertFalse(owner.drained()); }
        finally { leave.countDown(); callback.join(5000); }
        assertFalse(callback.isAlive()); assertTrue(owner.drained());
    }
    @Test public void queuedConcurrentCallbackCannotEnterOwnerAfterRetirement() throws Exception {
        Platform p = new Platform(); CountDownLatch entered = new CountDownLatch(1), leave = new CountDownLatch(1);
        AtomicInteger delivered = new AtomicInteger();
        CaptureLoadGovernor.OwnedThermal owner = new CaptureLoadGovernor.OwnedThermal(status -> { delivered.incrementAndGet(); entered.countDown(); await(leave); }, p);
        owner.start(); Thread first = new Thread(() -> p.callback.onThermalStatusChanged(1));
        Thread late = new Thread(() -> p.callback.onThermalStatusChanged(2)); first.start();
        try { assertTrue(entered.await(5, TimeUnit.SECONDS)); late.start(); owner.close(); assertFalse(owner.drained()); }
        finally { leave.countDown(); first.join(5000); late.join(5000); }
        assertFalse(first.isAlive()); assertFalse(late.isAlive()); assertEquals(1, delivered.get()); assertTrue(owner.drained());
    }
    @Test public void closeDuringRegistrationWaitsActualRegistrationAndThenUnregisters() {
        Platform p = new Platform(); AtomicReference<CaptureLoadGovernor.OwnedThermal> ref = new AtomicReference<>();
        p.duringRegister = () -> { ref.get().close(); assertFalse(ref.get().drained()); assertEquals(0, p.removals); };
        CaptureLoadGovernor.OwnedThermal owner = new CaptureLoadGovernor.OwnedThermal(value -> fail("Sealed callback"), p); ref.set(owner);
        owner.start(); assertEquals(1, p.removals); assertTrue(owner.drained()); p.callback.onThermalStatusChanged(2);
    }
    @Test public void registerThenThrowRemainsFailedEvenIfUnregisterSucceeds() {
        Platform p = new Platform(); p.registerFailure = true;
        CaptureLoadGovernor.OwnedThermal owner = new CaptureLoadGovernor.OwnedThermal(value -> {}, p);
        assertThrows(IllegalStateException.class, owner::start); assertThrows(IllegalStateException.class, owner::close);
        assertFalse(owner.drained()); assertEquals(1, p.removals); assertThrows(IllegalStateException.class, owner::close); assertEquals(1, p.removals);
    }
    @Test public void unregisterErrorIsLatchedAndNeverRetriedAsProof() {
        Platform p = new Platform(); p.unregisterError = true;
        CaptureLoadGovernor.OwnedThermal owner = new CaptureLoadGovernor.OwnedThermal(value -> fail("Retired"), p); owner.start();
        assertThrows(AssertionError.class, owner::close); assertFalse(owner.drained()); p.unregisterError = false;
        assertThrows(IllegalStateException.class, owner::close); assertEquals(1, p.removals); p.callback.onThermalStatusChanged(3);
    }
    @Test public void closeBeforeStartDoesNotInventNativeRegistration() {
        Platform p = new Platform(); CaptureLoadGovernor.OwnedThermal owner = new CaptureLoadGovernor.OwnedThermal(value -> {}, p);
        owner.close(); assertTrue(owner.drained()); assertEquals(0, p.additions); assertEquals(0, p.removals);
        assertThrows(IllegalStateException.class, owner::start);
    }
    @Test public void sealAloneFencesButDoesNotReportUnregistration() {
        Platform p = new Platform(); AtomicInteger delivered = new AtomicInteger();
        CaptureLoadGovernor.OwnedThermal owner = new CaptureLoadGovernor.OwnedThermal(delivered::set, p); owner.start();
        owner.seal(); p.callback.onThermalStatusChanged(5); assertEquals(0, delivered.get()); assertFalse(owner.drained()); assertEquals(0, p.removals);
        owner.close(); assertTrue(owner.drained());
    }
    @Test public void throwingCallbackReturnsItsAdmissionWithoutInventingOwnerClose() {
        Platform p = new Platform(); CaptureLoadGovernor.OwnedThermal owner = new CaptureLoadGovernor.OwnedThermal(value -> { throw new AssertionError("callback"); }, p);
        owner.start(); assertThrows(AssertionError.class, () -> p.callback.onThermalStatusChanged(3)); assertFalse(owner.drained());
        owner.close(); assertTrue(owner.drained());
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    private static final class Platform implements CaptureLoadGovernor.OwnedThermal.Platform {
        PowerManager.OnThermalStatusChangedListener callback; Runnable duringRegister;
        int additions, removals; boolean registerFailure, unregisterError;
        public void register(PowerManager.OnThermalStatusChangedListener value) {
            additions++; callback = value; if (duringRegister != null) duringRegister.run();
            if (registerFailure) throw new IllegalStateException("Registered then threw");
        }
        public void unregister(PowerManager.OnThermalStatusChangedListener value) {
            assertSame(callback, value); removals++; if (unregisterError) throw new AssertionError("Remove uncertain");
        }
    }
}
