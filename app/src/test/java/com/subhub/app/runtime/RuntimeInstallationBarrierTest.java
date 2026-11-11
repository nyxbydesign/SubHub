package com.subhub.app.runtime;

import static org.junit.Assert.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public final class RuntimeInstallationBarrierTest {
    @Test public void legacyPromptAndDeliveryMustBothReturnBeforeInstallation() {
        Fixture f = new Fixture(); var prompt = f.barrier.legacyWork(); var delivery = prompt.fork();
        prompt.close(); assertNull(f.prepare()); assertEquals(1, f.barrier.outstandingLegacyWork());
        delivery.close(); assertNotNull(f.prepare());
    }
    @Test public void failedDispatchDoesNotProveQueuedCallbackReturned() {
        Fixture f = new Fixture(); var call = f.barrier.legacyWork(); var queued = call.fork();
        call.close(); assertNull(f.prepare()); queued.close(); assertNotNull(f.prepare());
    }
    @Test public void retiredParentCannotCreateUncountedChild() {
        Fixture f = new Fixture(); var parent = f.barrier.legacyWork(); parent.close();
        assertThrows(SecurityException.class, parent::fork); parent.close(); assertEquals(0, f.barrier.outstandingLegacyWork());
    }
    @Test public void sealingRejectsNewLegacyWorkBeforeInspectingQuiescence() {
        Fixture f = new Fixture(); var install = f.prepare(); assertNotNull(install);
        assertNull(f.barrier.tryLegacyWork());
        assertThrows(SecurityException.class, f.barrier::legacyWork);
        f.mutex.runtime(() -> { install.abortBeforeProvisioning(); return null; });
        var resumed = f.barrier.tryLegacyWork();
        assertNotNull(resumed);
        assertNull(f.prepare());
        resumed.close();
        assertNotNull(f.prepare());
    }
    @Test public void irreversibleFailureCannotFallBackToLegacyOrManufactureAnotherInstall() {
        Fixture f = new Fixture(); var install = f.prepare();
        f.mutex.runtime(() -> { install.beginProvisioning(); return null; }); install.failed();
        assertFalse(f.barrier.legacyAdmissionOpen()); assertNull(f.prepare());
        assertNull(f.barrier.tryLegacyWork());
        assertThrows(SecurityException.class, f.barrier::legacyWork);
        assertThrows(SecurityException.class, () -> f.mutex.runtime(() -> { install.abortBeforeProvisioning(); return null; }));
    }
    @Test public void installedBoundaryNeverReopensLegacy() {
        Fixture f = new Fixture(); var install = f.prepare();
        f.mutex.runtime(() -> { install.beginProvisioning(); install.installed(); return null; });
        assertThrows(SecurityException.class, f.barrier::legacyWork); assertNull(f.prepare());
        assertThrows(SecurityException.class, install::failed);
    }
    @Test public void unacknowledgedPreparationCannotPublishInstalled() {
        Fixture f = new Fixture(); var install = f.prepare();
        assertThrows(SecurityException.class, () -> f.mutex.runtime(() -> { install.installed(); return null; }));
        assertThrows(IllegalStateException.class, install::beginProvisioning);
    }
    @Test public void staleInstallationCannotRetireReplacement() {
        Fixture f = new Fixture(); var old = f.prepare();
        f.mutex.runtime(() -> { old.abortBeforeProvisioning(); return null; }); var current = f.prepare();
        assertNotNull(current); assertThrows(SecurityException.class, old::failed);
        assertThrows(SecurityException.class, () -> f.mutex.runtime(() -> { old.beginProvisioning(); return null; }));
    }
    @Test public void mainAdmissionAndReturnDoNotWaitForRuntimeJournalLock() throws Exception {
        Fixture f = new Fixture(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread journal = new Thread(() -> f.mutex.runtime(() -> { entered.countDown();
            try { if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError(); }
            catch (InterruptedException failure) { throw new AssertionError(failure); } return null; }));
        journal.start();
        try { assertTrue(entered.await(3, TimeUnit.SECONDS)); var work = f.barrier.legacyWork(); work.close(); }
        finally { release.countDown(); journal.join(3000); }
        assertFalse(journal.isAlive()); assertNotNull(f.prepare());
    }
    private static final class Fixture {
        final RuntimeMutex mutex = new RuntimeMutex(new Object());
        final RuntimeInstallationBarrier barrier = new RuntimeInstallationBarrier(mutex);
        RuntimeInstallationBarrier.Installation prepare() { return mutex.runtime(barrier::prepareInstallation); }
    }
}
