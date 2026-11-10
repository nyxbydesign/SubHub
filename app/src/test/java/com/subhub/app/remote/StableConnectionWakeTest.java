package com.subhub.app.remote;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.Test;

public final class StableConnectionWakeTest {
    private final Clock clock = new Clock();
    private int wakes;
    private final StableConnectionWake gate = new StableConnectionWake(clock, () -> wakes++);

    @Test public void availabilityAloneAndUnvalidatedNetworkNeverWake() {
        gate.available("wifi"); clock.advance(10_000);
        gate.capabilities("wifi", false); clock.advance(10_000);
        assertEquals(0, wakes); assertTrue(clock.jobs.isEmpty());
    }

    @Test public void waitsForContinuousStabilityAndOnlyWakesOnce() {
        ready("wifi"); clock.advance(StableConnectionWake.STABLE_MILLIS - 1);
        assertEquals(0, wakes);
        clock.advance(1); assertEquals(1, wakes);
        for (int i = 0; i < 10; i++) { gate.available("wifi"); gate.capabilities("wifi", true); }
        clock.advance(10_000); assertEquals(1, wakes); assertEquals(1, clock.jobs.size());
    }

    @Test public void unrelatedCapabilityUpdatesDoNotPostponeRecovery() {
        ready("wifi"); clock.advance(1_500); gate.capabilities("wifi", true);
        clock.advance(500); assertEquals(1, wakes);
    }

    @Test public void flappingValidationRestartsTheFullStableInterval() {
        ready("wifi"); clock.advance(1_999); gate.capabilities("wifi", false);
        ready("wifi"); clock.advance(1_999); assertEquals(0, wakes);
        clock.jobs.get(0).work.run(); assertEquals(0, wakes);
        clock.advance(1); assertEquals(1, wakes);
    }

    @Test public void replacementIgnoresOldLossCapabilitiesAndDelayedCallbacks() {
        ready("wifi"); clock.advance(1_000); ready("mobile");
        gate.lost("wifi"); gate.capabilities("wifi", false); gate.capabilities("wifi", true);
        clock.jobs.get(0).work.run(); clock.advance(1_999); assertEquals(0, wakes);
        clock.advance(1); assertEquals(1, wakes);
    }

    @Test public void lossCancelsPendingWakeAndRequiresFreshAvailability() {
        ready("wifi"); gate.lost("wifi");
        gate.capabilities("wifi", true); clock.advance(10_000);
        clock.jobs.get(0).work.run(); assertEquals(0, wakes);
        ready("wifi"); clock.advance(2_000); assertEquals(1, wakes);
    }

    @Test public void validationRecoveryOnSameNetworkWakesAgainWithoutAnAvailableEvent() {
        ready("wifi"); clock.advance(2_000); assertEquals(1, wakes);
        gate.capabilities("wifi", false); gate.capabilities("wifi", true);
        clock.advance(2_000); assertEquals(2, wakes);
    }

    @Test public void closeFencesAllLateCallbacksAndIsIdempotent() {
        ready("wifi"); gate.close(); gate.close();
        ready("mobile"); gate.lost("wifi"); clock.advance(10_000);
        clock.jobs.get(0).work.run(); assertEquals(0, wakes);
        assertTrue(clock.jobs.get(0).cancelled); assertEquals(1, clock.jobs.size());
    }

    private void ready(String network) { gate.available(network); gate.capabilities(network, true); }

    private static final class Clock implements StableConnectionWake.Scheduler {
        final List<Job> jobs = new ArrayList<>();
        long now;
        public Runnable schedule(Runnable work, long delay) {
            Job job = new Job(work, now + delay); jobs.add(job);
            return () -> job.cancelled = true;
        }
        void advance(long millis) {
            long target = now + millis;
            List<Job> ordered = new ArrayList<>(jobs); ordered.sort(Comparator.comparingLong(job -> job.at));
            for (Job job : ordered) if (!job.cancelled && !job.ran && job.at <= target) {
                now = job.at; job.ran = true; job.work.run();
            }
            now = target;
        }
    }
    private static final class Job {
        final Runnable work;
        final long at;
        boolean cancelled, ran;
        Job(Runnable work, long at) { this.work = work; this.at = at; }
    }
}
