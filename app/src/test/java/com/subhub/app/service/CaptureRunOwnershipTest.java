package com.subhub.app.service;

import static org.junit.Assert.*;
import java.util.UUID;
import org.junit.Test;

public class CaptureRunOwnershipTest {
    private static String id() { return UUID.randomUUID().toString(); }
    private static CaptureRunOwnership.Components components() { return new CaptureRunOwnership.Components(id(), id(), id()); }
    @Test public void lateStopAndDestructionCannotAffectANewerCaptureInstance() {
        CaptureRunOwnership registry = new CaptureRunOwnership(); CaptureRunOwnership.Components first = components(), later = components();
        CaptureRunOwnership.Token old = registry.activate(id(), 1, first), current = registry.activate(id(), 1, later);
        assertFalse(registry.beginStop(old)); assertFalse(registry.release(old));
        assertTrue(registry.owns(current)); assertTrue(registry.isRunning());
    }
    @Test public void changedStatisticsRejectOldCleanupWithoutLeavingAFictitiousCaptureRunning() {
        CaptureRunOwnership registry = new CaptureRunOwnership(); CaptureRunOwnership.Components old = components();
        CaptureRunOwnership.Token owner = registry.activate(id(), 1, old);
        assertFalse(registry.mayAffectRuntime(owner, new CaptureRunOwnership.Components(id(), old.pact, old.pause)));
        assertTrue(registry.release(owner)); assertFalse(registry.isRunning());
    }
    @Test public void changedPactWithTheSameStatisticsStillRejectsCleanup() {
        CaptureRunOwnership registry = new CaptureRunOwnership(); CaptureRunOwnership.Components old = components();
        CaptureRunOwnership.Token owner = registry.activate(id(), 1, old);
        assertFalse(registry.mayAffectRuntime(owner, new CaptureRunOwnership.Components(old.statistics, id(), old.pause)));
        assertFalse(registry.mayAffectRuntime(owner, new CaptureRunOwnership.Components(old.statistics, old.pact, id())));
    }
    @Test public void mainSideEndCanClearTheOldComponentsBeforeTargetedStopArrives() {
        CaptureRunOwnership registry = new CaptureRunOwnership(); CaptureRunOwnership.Components old = components();
        CaptureRunOwnership.Token owner = registry.activate(id(), 1, old);
        assertTrue(registry.mayAffectRuntime(owner, new CaptureRunOwnership.Components("", "", "")));
        assertTrue(registry.beginStop(owner)); assertTrue(registry.isStopping(owner));
        assertTrue(registry.release(owner)); assertFalse(registry.beginStop(owner)); assertFalse(registry.release(owner));
    }
    @Test public void addingAPactToAnUnboundedSessionDoesNotInheritOldCaptureCleanup() {
        CaptureRunOwnership registry = new CaptureRunOwnership(); String stats = id();
        CaptureRunOwnership.Token owner = registry.activate(id(), 1, new CaptureRunOwnership.Components(stats, "", ""));
        assertFalse(registry.mayAffectRuntime(owner, new CaptureRunOwnership.Components(stats, id(), "")));
    }
    @Test public void repeatedActivationKeepsItsTokenButANewStartIdChangesIt() {
        CaptureRunOwnership registry = new CaptureRunOwnership(); CaptureRunOwnership.Components components = components(); String instance = id();
        CaptureRunOwnership.Token first = registry.activate(instance, 10, components);
        assertSame(first, registry.activate(instance, 10, components));
        assertTrue(registry.beginStop(first)); CaptureRunOwnership.Token next = registry.activate(instance, 12, components);
        assertNotEquals(first.activation, next.activation); assertFalse(registry.isStopping(next));
        assertFalse(registry.beginStop(first)); assertFalse(registry.release(first));
    }
    @Test public void reusingAnActivationIdWithDifferentComponentsIsRefused() {
        CaptureRunOwnership registry = new CaptureRunOwnership(); String instance = id(); registry.activate(instance, 1, components());
        assertThrows(IllegalStateException.class, () -> registry.activate(instance, 1, components()));
    }
    @Test public void malformedOrMissingCaptureIdentityIsNeverAnOwner() {
        CaptureRunOwnership registry = new CaptureRunOwnership();
        assertThrows(IllegalArgumentException.class, () -> registry.activate(id(), 1, new CaptureRunOwnership.Components("", "", "")));
        assertThrows(IllegalArgumentException.class, () -> registry.activate(id(), 0, components()));
        assertThrows(IllegalArgumentException.class, () -> new CaptureRunOwnership.Components("not-a-uuid", "", ""));
        assertFalse(registry.owns(null)); assertFalse(registry.isRunning());
    }
    @Test public void resourceRetirementDoesNotRequireAuthorityOverReplacementComponents() {
        CaptureRunOwnership registry = new CaptureRunOwnership(); CaptureRunOwnership.Components first = components();
        CaptureRunOwnership.Token owner = registry.activate(id(), 1, first);
        assertFalse(registry.mayAffectRuntime(owner, components()));
        assertTrue(registry.beginStop(owner)); assertTrue(registry.isStopping(owner)); assertTrue(registry.release(owner));
    }
}
