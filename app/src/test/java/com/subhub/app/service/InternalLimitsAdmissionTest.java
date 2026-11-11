package com.subhub.app.service;

import static org.junit.Assert.*;
import com.subhub.app.runtime.RuntimeLease;
import com.subhub.app.runtime.RuntimeOrigin;
import com.subhub.app.runtime.RuntimeTarget;
import com.subhub.app.stats.OwnerObservation;
import java.time.Instant;
import java.util.UUID;
import org.junit.Test;

public final class InternalLimitsAdmissionTest {
    private static final String APP = "com.example.app";
    private static final long WALL = Instant.parse("2026-11-11T10:00:00Z").toEpochMilli();
    private final RuntimeLease.Identity owner = owner();
    private final Object config = new Object();
    private final InternalLimitsAdmission<Object> state = new InternalLimitsAdmission<>(owner, config);
    private long epoch;

    @Test public void unavailableAndPendingNeverFabricateEligibility() {
        assertFalse(eligible(APP, 4, 0, WALL, "UTC"));
        InternalLimitsAdmission.Observation<Object> observed = observe(APP, 4, 0, WALL, "UTC");
        assertFalse(eligible(APP, 4, 0, WALL, "UTC"));
        assertNotNull(state.acknowledge(observed, false));
        assertTrue(eligible(APP, 4, 0, WALL, "UTC"));
    }
    @Test public void exactOwnerConfigurationPackageAndFocusedWindowAreRequired() {
        state.acknowledge(observe(APP, 4, 0, WALL, "UTC"), false);
        assertFalse(state.eligible(owner(), config, APP, 4, 0, calendar(WALL, "UTC")));
        assertFalse(state.eligible(owner, new Object(), APP, 4, 0, calendar(WALL, "UTC")));
        assertFalse(eligible("com.example.other", 4, 0, WALL, "UTC"));
        assertFalse(eligible(APP, 5, 0, WALL, "UTC"));
    }
    @Test public void fiveSecondsQualifyRetroactivelyAndOnlyAcknowledgedSpentBudgetBlocks() {
        for (int n = 0; n < 5; n++) {
            InternalLimitsAdmission.Observation<Object> observed = observe(APP, 4, n * 1000L, WALL + n * 1000L, "UTC");
            assertEquals(0, observed.usageMillis);
            assertFalse(state.acknowledge(observed, true).blocked);
        }
        InternalLimitsAdmission.Observation<Object> observed = observe(APP, 4, 5000, WALL + 5000, "UTC");
        assertEquals(5000, observed.usageMillis);
        assertTrue(state.acknowledge(observed, true).blocked);
        assertFalse(eligible(APP, 4, 5000, WALL + 5000, "UTC"));
    }
    @Test public void wallClockRollbackDoesNotChangeElapsedButCannotReuseFutureEvidence() {
        for (int n = 0; n <= 5; n++) {
            long wall = n < 3 ? WALL + n * 1000L : WALL - 100000 + n * 1000L;
            InternalLimitsAdmission.Observation<Object> observed = observe(APP, 4, n * 1000L, wall, "UTC");
            assertEquals(n == 5 ? 5000 : 0, observed.usageMillis);
            state.acknowledge(observed, false);
        }
        assertTrue(eligible(APP, 4, 5000, WALL - 95000, "UTC"));
        assertFalse(eligible(APP, 4, 5000, WALL - 95001, "UTC"));
    }
    @Test public void queuedDayAndZoneStayCapturedAndNewCalendarCannotUseOldAck() {
        long midnight = Instant.parse("2026-11-11T23:59:59Z").toEpochMilli();
        InternalLimitsAdmission.Observation<Object> observed = observe(APP, 4, 1000, midnight, "UTC");
        assertEquals("2026-11-11", observed.calendar.day());
        state.acknowledge(observed, false);
        assertFalse(eligible(APP, 4, 2000, midnight + 1000, "UTC"));
        assertFalse(eligible(APP, 4, 1000, midnight, "Europe/Berlin"));
        assertEquals("UTC", observed.calendar.zone);
        assertEquals(midnight, observed.calendar.at);
    }
    @Test public void rapidPackageAndConfigurationChangesRejectOldAcknowledgement() {
        InternalLimitsAdmission.Observation<Object> first = observe(APP, 4, 0, WALL, "UTC");
        epoch = state.invalidate(true);
        InternalLimitsAdmission.Observation<Object> second = state.observe(config, epoch, "com.example.other", 5,
                100, calendar(WALL + 100, "UTC"), true, true, true);
        assertNull(state.acknowledge(first, false));
        state.adopt(new Object());
        assertNull(state.acknowledge(second, false));
        assertFalse(eligible(APP, 4, 100, WALL + 100, "UTC"));
    }
    @Test public void aRuleEditKeepsNormalQualificationButOldRulesCannotPublish() {
        for (int n = 0; n <= 5; n++) state.acknowledge(observe(APP, 4, n * 1000L, WALL + n * 1000L, "UTC"), false);
        Object next = new Object(); state.adopt(next); epoch = state.invalidate(false);
        InternalLimitsAdmission.Observation<Object> observed = state.observe(next, epoch, APP, 4, 6000,
                calendar(WALL + 6000, "UTC"), true, true, true);
        assertTrue(observed.qualified); assertEquals(1000, observed.usageMillis);
        assertTrue(state.acknowledge(observed, true).blocked);
    }
    @Test public void homeRequestIsNotAnExitUnknownWindowsDoNotUndoHandoff() {
        InternalLimitsAdmission.Decision<Object> decision = qualify(true);
        state.homeRequested(decision);
        InternalLimitsAdmission.Observation<Object> unknown = observe("", -1, 6000, WALL + 6000, "UTC");
        assertEquals(0, unknown.usageMillis); assertFalse(unknown.accountingAllowed);
        // The actual package is unavailable: no new budget observation clears a requested exit.
        state.acknowledge(unknown, true);
        InternalLimitsAdmission.Observation<Object> stale = observe(APP, 4, 7000, WALL + 7000, "UTC");
        assertFalse(stale.accountingAllowed); assertEquals(0, stale.usageMillis);
        assertTrue(state.acknowledge(stale, true).blocked);
        InternalLimitsAdmission.Observation<Object> exited = observe("com.example.home", 8, 8000, WALL + 8000, "UTC");
        assertTrue(exited.accountingAllowed);
        state.acknowledge(exited, false);
        InternalLimitsAdmission.Observation<Object> returned = observe(APP, 4, 9000, WALL + 9000, "UTC");
        assertFalse(returned.qualified); assertTrue(returned.accountingAllowed);
    }
    @Test public void raisedBudgetReleasesPendingHomeWithoutInventingDeparture() {
        state.homeRequested(qualify(true));
        InternalLimitsAdmission.Observation<Object> released = observe(APP, 4, 6000, WALL + 6000, "UTC");
        assertFalse(state.acknowledge(released, false).blocked);
        assertTrue(eligible(APP, 4, 6000, WALL + 6000, "UTC"));
    }
    @Test public void stopRetiresPublicationButDoesNotTurnOutstandingWorkIntoAck() {
        InternalLimitsAdmission.Observation<Object> observed = observe(APP, 4, 0, WALL, "UTC");
        state.seal();
        assertNull(state.acknowledge(observed, false));
        assertFalse(eligible(APP, 4, 0, WALL, "UTC"));
    }
    @Test public void staleMonotonicEvidenceDoesNotGrantAuthorityOrBackfillUnknownGap() {
        qualify(false);
        assertFalse(eligible(APP, 4, 7501, WALL + 7501, "UTC"));
        InternalLimitsAdmission.Observation<Object> delayed = observe(APP, 4, 7501, WALL + 7501, "UTC");
        assertEquals(0, delayed.usageMillis); assertFalse(delayed.qualified);
        assertFalse(eligible(APP, 4, 7501, WALL + 7501, "UTC"));
    }
    @Test public void lockedDeselectedAndDisabledSamplesResetOnlyPendingContinuity() {
        qualify(false);
        epoch = state.invalidate(false);
        InternalLimitsAdmission.Observation<Object> paused = state.observe(config, epoch, APP, 4, 6000,
                calendar(WALL + 6000, "UTC"), true, true, false);
        assertFalse(paused.qualified); assertEquals(0, paused.usageMillis);
        state.acknowledge(paused, false);
        assertFalse(eligible(APP, 4, 6000, WALL + 6000, "UTC"));
    }
    @Test public void sourceChangedBeforeHomeResultCannotMutateTheSuccessor() {
        InternalLimitsAdmission.Decision<Object> old = qualify(true);
        assertTrue(state.currentDecision(old));
        epoch = state.invalidate(true);
        InternalLimitsAdmission.Observation<Object> next = state.observe(config, epoch, "com.example.other", 7,
                6000, calendar(WALL + 6000, "UTC"), true, true, true);
        assertFalse(state.currentDecision(old)); assertFalse(state.homeRequested(old));
        assertTrue(next.accountingAllowed);
        state.acknowledge(next, false);
        assertTrue(eligible("com.example.other", 7, 6000, WALL + 6000, "UTC"));
    }
    @Test public void stopBeforeNativeHomeReturnIsInertRatherThanADataFailure() {
        InternalLimitsAdmission.Decision<Object> old = qualify(true);
        state.seal(); assertFalse(state.currentDecision(old)); assertFalse(state.homeRequested(old));
    }
    @Test public void adoptedConfigurationCannotAcceptOldHomeBookkeeping() {
        InternalLimitsAdmission.Decision<Object> old = qualify(true);
        state.adopt(new Object());
        assertFalse(state.currentDecision(old)); assertFalse(state.homeRequested(old));
    }
    @Test public void onlyExactAcknowledgedBlockedDecisionCanRecordHome() {
        InternalLimitsAdmission.Decision<Object> old = qualify(true);
        assertFalse(state.currentDecision(null));
        assertFalse(state.homeRequested(new InternalLimitsAdmission.Decision<>(old.observation, true)));
        assertTrue(state.homeRequested(old));
        InternalLimitsAdmission.Observation<Object> same = observe(APP, 4, 6000, WALL + 6000, "UTC");
        assertFalse(same.accountingAllowed); assertEquals(0, same.usageMillis);
    }
    private InternalLimitsAdmission.Decision<Object> qualify(boolean spent) {
        InternalLimitsAdmission.Decision<Object> result = null;
        for (int n = 0; n <= 5; n++) result = state.acknowledge(observe(APP, 4, n * 1000L, WALL + n * 1000L, "UTC"), spent);
        return result;
    }
    private InternalLimitsAdmission.Observation<Object> observe(String name, int window, long mono, long wall, String zone) {
        epoch = state.invalidate(false);
        return state.observe(config, epoch, name, window, mono, calendar(wall, zone), true, true, true);
    }
    private boolean eligible(String name, int window, long mono, long wall, String zone) {
        return state.eligible(owner, config, name, window, mono, calendar(wall, zone));
    }
    private static OwnerObservation calendar(long wall, String zone) { return new OwnerObservation(wall, zone); }
    private static RuntimeLease.Identity owner() {
        return new RuntimeLease.Identity(RuntimeTarget.internal("internal-sub"), RuntimeOrigin.LOCAL_UI, "generation",
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1);
    }
}
