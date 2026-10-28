package com.subhub.app.tasks;

import com.subhub.app.penance.PenanceEvent;
import com.subhub.app.penance.PenanceInfraction;
import com.subhub.app.penance.PenancePolicy;
import org.junit.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class TaskFailurePolicyTest {
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private static long at(String instant) {
        return Instant.parse(instant).toEpochMilli();
    }

    private static PenanceEvent failure(String id, long created, long mercyEnd,
            int cents, PenanceEvent.Status status) {
        return new PenanceEvent(id, created, mercyEnd, cents, 1,
                PenanceInfraction.TASK_FAILURE, status, "", "EUR");
    }

    @Test public void taskFailureUsesAnIndependentOptInRule() {
        PenanceInfraction taskFailure = PenanceInfraction.TASK_FAILURE;
        assertEquals("task_failure", taskFailure.preferenceKey());
        assertFalse(taskFailure.enabledByDefault());
        assertTrue(taskFailure.defaultCents() >= PenancePolicy.MIN_STRIKE_CENTS);
        assertTrue(taskFailure.defaultCents() <= PenancePolicy.MAX_STRIKE_CENTS);
        for (PenanceInfraction other : PenanceInfraction.values()) {
            if (other != taskFailure) assertNotEquals(other.preferenceKey(), taskFailure.preferenceKey());
        }
    }

    @Test public void taskFailuresConsumeDailyCapInOpenCheckoutAndPaidStates() {
        long now = at("2026-10-21T10:00:00Z");
        for (PenanceEvent.Status status : PenanceEvent.Status.values()) {
            PenanceEvent existing = failure("prior-task", now - 1, now + 60_000, 450, status);
            assertEquals(status != PenanceEvent.Status.FORGIVEN, existing.countsTowardCaps());
            int charge = PenancePolicy.boundedCharge(Collections.singletonList(existing), now,
                    1, 100, 500, 2_000, BERLIN);
            assertEquals(status == PenanceEvent.Status.FORGIVEN ? 100 : 50, charge);
        }
    }

    @Test public void taskFailureAndOtherInfractionsShareTheWeeklyCap() {
        long now = at("2026-10-22T10:00:00Z");
        PenanceEvent yesterday = failure("yesterday", now - 86_400_000, now, 450,
                PenanceEvent.Status.PAID);
        PenanceEvent otherRule = new PenanceEvent("other-rule", now - 1, now + 60_000, 200, 1,
                PenanceInfraction.WATCHED_APP_OPEN, PenanceEvent.Status.OPEN, "", "EUR");
        assertEquals(50, PenancePolicy.boundedCharge(Arrays.asList(yesterday, otherRule), now,
                1, 100, 500, 700, BERLIN));
        PenanceEvent capped = failure("remaining", now, now, 50, PenanceEvent.Status.OPEN);
        assertEquals(0, PenancePolicy.boundedCharge(Arrays.asList(yesterday, otherRule, capped), now,
                1, 100, 500, 700, BERLIN));
    }

    @Test public void autumnOverlapDoesNotResetDailyCapDuringTheRepeatedHour() {
        PenanceEvent earlier = failure("earlier-offset", at("2026-10-25T00:30:00Z"),
                at("2026-10-25T00:30:00Z"), 225, PenanceEvent.Status.OPEN);
        PenanceEvent later = failure("later-offset", at("2026-10-25T01:30:00Z"),
                at("2026-10-25T01:30:00Z"), 225, PenanceEvent.Status.PAID);
        assertEquals(50, PenancePolicy.boundedCharge(Arrays.asList(earlier, later),
                at("2026-10-25T02:00:00Z"), 1, 100, 500, 2_000, BERLIN));
    }

    @Test public void springDailyCapResetsAtBerlinMidnightAfterTheShortDay() {
        PenanceEvent beforeGap = failure("before-gap", at("2026-03-29T00:30:00Z"),
                at("2026-03-29T00:30:00Z"), 500, PenanceEvent.Status.PAID);
        assertEquals(0, PenancePolicy.boundedCharge(Collections.singletonList(beforeGap),
                at("2026-03-29T21:59:59.999Z"), 1, 100, 500, 2_000, BERLIN));
        assertEquals(100, PenancePolicy.boundedCharge(Collections.singletonList(beforeGap),
                at("2026-03-29T22:00:00Z"), 1, 100, 500, 2_000, BERLIN));
    }

    @Test public void taskFailureMercyExpiresAtTheExactElapsedDeadline() {
        long created = at("2026-03-29T00:30:00Z");
        long deadline = created + 60 * 60_000L;
        PenanceEvent event = failure("task-occurrence", created, deadline, 100, PenanceEvent.Status.OPEN);
        assertTrue(event.isInMercy(deadline - 1));
        assertFalse(event.isDue(deadline - 1));
        assertFalse(event.isInMercy(deadline));
        assertTrue(event.isDue(deadline));
        assertEquals(100, event.getAmountCents());
        assertEquals(1, event.getStrikeCount());
        assertEquals(PenanceInfraction.TASK_FAILURE, event.getInfraction());
        PenanceEvent forgiven = event.withStatus(PenanceEvent.Status.FORGIVEN, "");
        assertFalse(forgiven.isDue(deadline));
        assertFalse(forgiven.isInMercy(deadline - 1));
        assertEquals(event.getId(), forgiven.getId());
        assertEquals(deadline, forgiven.getMercyEndsAtMillis());
        assertEquals(PenanceInfraction.TASK_FAILURE, forgiven.getInfraction());
        assertEquals("EUR", forgiven.getCurrency());
    }
}
