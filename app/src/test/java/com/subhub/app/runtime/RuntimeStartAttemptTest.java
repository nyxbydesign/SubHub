package com.subhub.app.runtime;

import static org.junit.Assert.*;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class RuntimeStartAttemptTest {
    @Test public void queuedPromptCannotLaunchAfterRevocation() {
        Fixture f = new Fixture(); RuntimeStartAttempt.Prompt prompt = f.attempt.issue(RuntimeStartAttempt.Permission.PROJECTION);
        f.attempt.revoke(); assertTrue(f.attempt.promptsReturned());
        assertFalse(prompt.launch(() -> fail("Revoked prompt"))); assertFalse(prompt.resultReturned());
    }
    @Test public void admittedPromptRetainsOccupancyUntilExactResult() {
        Fixture f = new Fixture(); RuntimeStartAttempt.Prompt prompt = f.attempt.issue(RuntimeStartAttempt.Permission.PROJECTION);
        assertTrue(prompt.launch(() -> {})); f.attempt.revoke(); assertFalse(f.attempt.promptsReturned());
        assertFalse(prompt.resultReturned()); assertTrue(f.attempt.promptsReturned()); assertFalse(prompt.resultReturned());
    }
    @Test public void synchronousResultCannotRetireStillRunningLaunch() {
        Fixture f = new Fixture(); RuntimeStartAttempt.Prompt prompt = f.attempt.issue(RuntimeStartAttempt.Permission.OVERLAY);
        assertTrue(prompt.launch(() -> { assertTrue(prompt.resultReturned()); assertFalse(f.attempt.promptsReturned()); }));
        assertTrue(f.attempt.promptsReturned());
    }
    @Test public void acceptedLaunchThenThrowIsNotNeverLaunchedProof() {
        Fixture f = new Fixture(); RuntimeStartAttempt.Prompt prompt = f.attempt.issue(RuntimeStartAttempt.Permission.PROJECTION);
        assertThrows(IllegalStateException.class, () -> prompt.launch(() -> { throw new IllegalStateException("Maybe launched"); }));
        assertTrue(f.attempt.revoked()); assertFalse(f.attempt.promptsReturned());
        assertFalse(prompt.resultReturned()); assertTrue(f.attempt.promptsReturned());
    }
    @Test public void noSecondPromptAndNoTransferWhileFirstPromptPending() {
        Fixture f = new Fixture(); RuntimeStartAttempt.Prompt prompt = f.attempt.issue(RuntimeStartAttempt.Permission.NOTIFICATIONS);
        assertThrows(SecurityException.class, () -> f.attempt.issue(RuntimeStartAttempt.Permission.OVERLAY));
        assertFalse(f.attempt.transferToStarting()); assertTrue(prompt.launch(() -> {})); assertTrue(prompt.resultReturned());
        assertTrue(f.attempt.transferToStarting()); assertFalse(f.attempt.transferToStarting());
        assertFalse(f.attempt.revoke()); assertFalse(f.attempt.revoked());
        assertThrows(SecurityException.class, () -> f.attempt.issue(RuntimeStartAttempt.Permission.PROJECTION));
    }
    @Test public void stopNeverWaitsForBlockedPromptLaunch() throws Exception {
        Fixture f = new Fixture(); RuntimeStartAttempt.Prompt prompt = f.attempt.issue(RuntimeStartAttempt.Permission.PROJECTION);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread launcher = new Thread(() -> prompt.launch(() -> { entered.countDown(); try {
            if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError();
        } catch (InterruptedException error) { throw new AssertionError(error); } }));
        launcher.start(); assertTrue(entered.await(3, TimeUnit.SECONDS)); f.attempt.revoke();
        assertFalse(f.attempt.promptsReturned()); assertFalse(prompt.resultReturned()); assertFalse(f.attempt.promptsReturned());
        release.countDown(); launcher.join(3000); assertFalse(launcher.isAlive()); assertTrue(f.attempt.promptsReturned());
    }
    @Test public void lateOldResultDoesNotCloseAnotherAttemptPrompt() {
        Fixture old = new Fixture(), next = new Fixture();
        RuntimeStartAttempt.Prompt a = old.attempt.issue(RuntimeStartAttempt.Permission.PROJECTION);
        RuntimeStartAttempt.Prompt b = next.attempt.issue(RuntimeStartAttempt.Permission.PROJECTION);
        a.launch(() -> {}); b.launch(() -> {}); old.attempt.revoke(); a.resultReturned();
        assertFalse(next.attempt.promptsReturned()); assertFalse(next.attempt.revoked()); b.resultReturned();
    }
    static final class Fixture {
        final AtomicInteger wakes = new AtomicInteger();
        final RuntimeStartAttempt attempt;
        Fixture() {
            RuntimeMutex mutex = new RuntimeMutex(new Object());
            RuntimeLease lease = new RuntimeLease(new RuntimeLease.Identity(RuntimeTarget.internal("dummy_sub_preview"),
                    RuntimeOrigin.LOCAL_UI, "generation", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(), 1));
            OwnedResourceRegistry registry = new OwnedResourceRegistry(mutex, lease, () -> {});
            RuntimeAdmissionAuthority authority = new RuntimeAdmissionAuthority(mutex);
            authority.completeAvailability(authority.beginAvailability(lease.identity.target), "generation", 1, true);
            authority.completeAuthorization(authority.beginAuthorization(lease.identity.target, RuntimeOrigin.LOCAL_UI), 1, true);
            RuntimeAdmissionAuthority.Witness witness = authority.capture(lease.identity.target, RuntimeOrigin.LOCAL_UI);
            attempt = new RuntimeStartAttempt(lease, registry,
                    () -> mutex.runtime(() -> registry.reservePermission(lease, witness)), wakes::incrementAndGet);
        }
    }
}
