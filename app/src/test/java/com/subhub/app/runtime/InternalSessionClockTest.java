package com.subhub.app.runtime;

import static org.junit.Assert.*;
import com.subhub.app.stats.OwnerObservation;
import java.security.SecureRandom;
import java.util.*;
import org.junit.Test;

public final class InternalSessionClockTest {
    private static final long HOUR = 3_600_000;
    private static final RuntimeLease.Identity OWNER = owner();
    private static RuntimeLease.Identity owner() {
        return new RuntimeLease.Identity(RuntimeTarget.internal("dummy_sub"), RuntimeOrigin.LOCAL_UI, "generation",
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), 2);
    }
    private static InternalSessionClock.Sample at(long wall, long elapsed) {
        return new InternalSessionClock.Sample(new OwnerObservation(wall, "UTC"), elapsed);
    }
    private static InternalSessionClock.Rules rules(long revision, boolean enabled) {
        return new InternalSessionClock.Rules(revision, enabled, enabled, enabled, 500, 15, "EUR");
    }
    private static InternalSessionClock.State begin(long mode) {
        return InternalSessionClock.begin(null, OWNER, at(1_000_000, 100),
                new InternalSessionClock.Selection(mode, HOUR, 2 * HOUR, true), new SecureRandom());
    }
    @Test public void oneRandomDrawAndStrictRoundTripNeverResume() {
        final int[] draws = {0};
        SecureRandom random = new SecureRandom() {
            private static final long serialVersionUID = 1;
            @Override public long nextLong() { draws[0]++; return 8; }
        };
        InternalSessionClock.State state = InternalSessionClock.begin(null, OWNER, at(1000, 5),
                new InternalSessionClock.Selection(-2, HOUR, 2 * HOUR, true), random);
        assertEquals(1, draws[0]); assertEquals(HOUR + 4, state.selectedDuration);
        InternalSessionClock.State restored = InternalSessionClock.decode(InternalSessionClock.encode(state));
        assertEquals(InternalSessionClock.encode(state), InternalSessionClock.encode(restored));
        assertEquals(InternalSessionClock.Status.RECOVERY_REQUIRED, InternalSessionClock.observe(restored).status);
        assertFalse(InternalSessionClock.observe(restored).protectionEligible);
        assertThrows(IllegalStateException.class, () -> InternalSessionClock.begin(restored, owner(), at(2000, 50), state.selection, random));
        assertEquals(1, draws[0]);
    }
    @Test public void backwardWallAndTimezoneChangesCannotExtendDuration() {
        InternalSessionClock.State s = begin(HOUR);
        InternalSessionClock.Sample changed = new InternalSessionClock.Sample(new OwnerObservation(20, "Pacific/Kiritimati"), HOUR + 99);
        assertEquals(1, InternalSessionClock.live(s, OWNER, changed, rules(2, true), true).remainingMillis);
        assertEquals(InternalSessionClock.Status.EXPIRED, InternalSessionClock.live(s, OWNER, at(10, HOUR + 100), rules(2, true), true).status);
        assertFalse(InternalSessionClock.live(s, OWNER, at(Long.MAX_VALUE, HOUR + 101), rules(2, true), true).protectionEligible);
        assertThrows(IllegalStateException.class, () -> InternalSessionClock.live(s, OWNER, at(10, 99), rules(2, true), true));
    }
    @Test public void startupIsNotProtectionAndDifferentOwnerRefuses() {
        InternalSessionClock.State s = begin(HOUR);
        assertFalse(InternalSessionClock.live(s, OWNER, at(10, 100), rules(2, true), false).protectionEligible);
        assertThrows(SecurityException.class, () -> InternalSessionClock.live(s, owner(), at(10, 100), rules(2, true), true));
    }
    @Test public void simulatedPauseHasCapturedTermsAndNoExtensionFromLiveRules() {
        InternalSessionClock.State s = begin(HOUR);
        s = InternalSessionClock.pause(s, OWNER, at(1_010_000, 10_100), rules(2, true), 0, UUID.randomUUID().toString());
        assertEquals(500L, s.simulatedTotals.get("EUR").longValue()); assertEquals(1, s.recentPauses.size());
        assertEquals(1_010_000, s.pause.started.calendar.at);
        assertFalse(InternalSessionClock.live(s, OWNER, at(5, 10_101), rules(3, true), true).protectionEligible);
        InternalSessionClock.Rules changed = new InternalSessionClock.Rules(4, true, true, true, 900, 100, "USD");
        s = InternalSessionClock.tick(s, OWNER, at(5, 20_100), changed);
        assertEquals(500, s.pause.cents); assertEquals("EUR", s.pause.currency); assertEquals(900_000, s.pause.duration);
        assertEquals(890_000, InternalSessionClock.live(s, OWNER, at(5, 20_100), changed, true).pauseRemainingMillis);
        s = InternalSessionClock.tick(s, OWNER, at(5, 910_100), changed);
        assertNull(s.pause); assertTrue(InternalSessionClock.live(s, OWNER, at(5, 910_100), changed, true).protectionEligible);
        assertEquals(InternalSessionClock.encode(s), InternalSessionClock.encode(InternalSessionClock.decode(InternalSessionClock.encode(s))));
    }
    @Test public void disabledPenanceClearsGrantButDisabledPauseAloneDoesNot() {
        InternalSessionClock.State s = InternalSessionClock.pause(begin(HOUR), OWNER, at(1000, 200), rules(2, true), 0, UUID.randomUUID().toString());
        InternalSessionClock.Rules onlyPauseDisabled = new InternalSessionClock.Rules(3, true, false, false, 500, 15, "EUR");
        assertNotNull(InternalSessionClock.tick(s, OWNER, at(2000, 300), onlyPauseDisabled).pause);
        InternalSessionClock.State cleared = InternalSessionClock.tick(s, OWNER, at(2000, 300), rules(4, false));
        assertNull(cleared.pause); assertEquals(1, cleared.pauseSequence); assertEquals(500L, cleared.simulatedTotals.get("EUR").longValue());
    }
    @Test public void permanentEnabledInternalRuleQualifiesWithoutDeviceHardcore() {
        InternalSessionClock.State s = begin(-1);
        assertTrue(InternalSessionClock.live(s, OWNER, at(500, Long.MAX_VALUE), rules(2, true), true).canSimulatePause);
        assertFalse(InternalSessionClock.live(s, OWNER, at(500, 100), rules(3, false), true).canSimulatePause);
        s = InternalSessionClock.pause(s, OWNER, at(500, 100), rules(2, true), 0, UUID.randomUUID().toString());
        assertEquals(InternalSessionClock.Status.SIMULATED_PAUSE, InternalSessionClock.live(s, OWNER, at(500, 101), rules(2, true), true).status);
    }
    @Test public void staleDuplicateAndExpiredPauseRequestsCannotCharge() {
        String id = UUID.randomUUID().toString();
        InternalSessionClock.State s = InternalSessionClock.pause(begin(HOUR), OWNER, at(1000, 100), rules(2, true), 0, id);
        assertThrows(IllegalStateException.class, () -> InternalSessionClock.pause(s, OWNER, at(1000, 100), rules(2, true), 0, id));
        InternalSessionClock.State expired = InternalSessionClock.tick(s, OWNER, at(2000, HOUR + 100), rules(2, true));
        assertThrows(IllegalStateException.class, () -> InternalSessionClock.pause(expired, OWNER, at(2000, HOUR + 100), rules(2, true), 1, UUID.randomUUID().toString()));
        assertEquals(500L, expired.simulatedTotals.get("EUR").longValue());
    }
    @Test public void cleanStopUsesFirstFenceAndRecoveryIsDataOnly() {
        InternalSessionClock.State live = begin(HOUR);
        InternalSessionClock.State ended = InternalSessionClock.finish(live, OWNER, new OwnerObservation(10, "Europe/Berlin"));
        assertEquals(10, ended.endedAt); assertEquals("Europe/Berlin", ended.endedZone);
        assertEquals(InternalSessionClock.Status.STOPPED, InternalSessionClock.observe(ended).status);
        assertThrows(IllegalStateException.class, () -> InternalSessionClock.finish(ended, OWNER, new OwnerObservation(20, "UTC")));
        InternalSessionClock.State recovered = InternalSessionClock.recover(live, OWNER.session, new OwnerObservation(100, "UTC"));
        assertEquals(InternalSessionClock.Phase.RECOVERED, recovered.phase); assertFalse(InternalSessionClock.observe(recovered).protectionEligible);
        assertThrows(SecurityException.class, () -> InternalSessionClock.recover(live, UUID.randomUUID().toString(), new OwnerObservation(100, "UTC")));
        InternalSessionClock.State next = InternalSessionClock.begin(recovered, owner(), at(2000, 200), live.selection, new SecureRandom());
        assertFalse(live.session.equals(next.session));
    }
    @Test public void strictSchemaRejectsTypeRangeReceiptAndOverflowCorruption() {
        Map<String,Object> encoded = new TreeMap<>(InternalSessionClock.encode(begin(HOUR)));
        encoded.put("extra", true); assertThrows(IllegalArgumentException.class, () -> InternalSessionClock.decode(encoded));
        encoded.remove("extra"); encoded.put("started_elapsed", "100");
        assertThrows(IllegalArgumentException.class, () -> InternalSessionClock.decode(encoded));
        encoded.put("started_elapsed", Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> InternalSessionClock.decode(encoded));
        encoded.put("started_elapsed", 100L); encoded.put("pause_sequence", 1L);
        assertThrows(IllegalArgumentException.class, () -> InternalSessionClock.decode(encoded));
        assertThrows(IllegalArgumentException.class, () -> new InternalSessionClock.Rules(2, true, true, true, 500, 15, "JPY"));
    }
    @Test public void retainedLedgerRollsWithoutLosingTotalsOrAcceptingOldSequence() {
        InternalSessionClock.State state = begin(-1);
        InternalSessionClock.Rules shortPause = new InternalSessionClock.Rules(2, true, true, true, 50, 1, "USD");
        for (int index = 0; index < 205; index++) {
            long elapsed = 100 + index * 60_000L;
            state = InternalSessionClock.tick(state, OWNER, at(1000, elapsed), shortPause);
            state = InternalSessionClock.pause(state, OWNER, at(1000, elapsed), shortPause, index, UUID.randomUUID().toString());
        }
        assertEquals(200, state.recentPauses.size()); assertEquals(205, state.pauseSequence);
        assertEquals(10250L, state.simulatedTotals.get("USD").longValue());
        assertEquals(6, state.recentPauses.get(0).sequence);
        assertEquals(InternalSessionClock.encode(state), InternalSessionClock.encode(InternalSessionClock.decode(InternalSessionClock.encode(state))));
    }
    @Test public void readOnlyProjectionFencesPendingConfigurationAndUpdatesExpiryWithoutWriting() {
        InternalSessionClock.State s = begin(HOUR);
        InternalSessionClock.Snapshot acknowledged = InternalSessionClock.live(s, OWNER, at(1000, 100), rules(2, true), true);
        Map<String,Object> before = InternalSessionClock.encode(s);
        InternalSessionClock.Snapshot pending = InternalSessionClock.project(acknowledged, 1100, 3, false);
        assertEquals(InternalSessionClock.Status.CONFIGURATION_PENDING, pending.status);
        assertEquals(3, pending.configurationRevision); assertFalse(pending.canSimulatePause); assertFalse(pending.protectionEligible);
        assertEquals(HOUR - 1000, pending.remainingMillis);
        InternalSessionClock.Snapshot expired = InternalSessionClock.project(acknowledged, HOUR + 100, 3, false);
        assertEquals(InternalSessionClock.Status.EXPIRED, expired.status); assertEquals(0, expired.remainingMillis);
        assertFalse(expired.protectionEligible); assertFalse(expired.canSimulatePause);
        assertEquals(before, InternalSessionClock.encode(s));
    }
    @Test public void readOnlyPauseProjectionWaitsForDurableRetirementRatherThanRearming() {
        InternalSessionClock.State s = InternalSessionClock.pause(begin(HOUR), OWNER, at(1000, 100), rules(2, true), 0, UUID.randomUUID().toString());
        InternalSessionClock.Snapshot acknowledged = InternalSessionClock.live(s, OWNER, at(1000, 100), rules(2, true), true);
        assertEquals(450_000, InternalSessionClock.project(acknowledged, 450_100, 2, true).pauseRemainingMillis);
        InternalSessionClock.Snapshot elapsed = InternalSessionClock.project(acknowledged, 900_100, 2, true);
        assertEquals(InternalSessionClock.Status.PAUSE_END_PENDING, elapsed.status);
        assertEquals(0, elapsed.pauseRemainingMillis); assertFalse(elapsed.protectionEligible); assertFalse(elapsed.canSimulatePause);
        assertNotNull(s.pause); assertEquals(1, s.pauseSequence);
        InternalSessionClock.State cleared = InternalSessionClock.tick(s, OWNER, at(2000, 900_100), rules(2, true));
        assertTrue(InternalSessionClock.live(cleared, OWNER, at(2000, 900_100), rules(2, true), true).protectionEligible);
    }
    @Test public void projectionCannotMintReadinessOrIgnoreMonotonicRegression() {
        InternalSessionClock.State s = begin(HOUR);
        InternalSessionClock.Snapshot prepared = InternalSessionClock.live(s, OWNER, at(1000, 200), rules(2, true), false);
        InternalSessionClock.Snapshot projected = InternalSessionClock.project(prepared, 300, 2, true);
        assertEquals(InternalSessionClock.Status.PREPARED, projected.status); assertFalse(projected.protectionEligible);
        InternalSessionClock.Snapshot regressed = InternalSessionClock.project(prepared, 199, 2, true);
        assertEquals(InternalSessionClock.Status.RECOVERY_REQUIRED, regressed.status); assertFalse(regressed.canSimulatePause);
    }

}
