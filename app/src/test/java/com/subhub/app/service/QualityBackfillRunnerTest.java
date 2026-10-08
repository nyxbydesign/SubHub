package com.subhub.app.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.subhub.app.detection.BBox;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class QualityBackfillRunnerTest {
    @Test public void motionChangeKeepsCapturedSourceAndClosesItOnce() {
        AtomicInteger released = new AtomicInteger();
        QualityBackfillRunner<Payload> runner = newRunner(
                QualityBackfillRunner.Policy.unbounded());
        Payload source = new Payload(731L);
        QualityBackfillCoordinator.BackfillFrame<Payload> frame = frame(
                source, 100L, 17L, 1L, released);
        assertTrue(runner.offer(frame, 100L).accepted());

        AtomicInteger permitsClosed = new AtomicInteger();
        AtomicLong observedMotion = new AtomicLong();
        QualityBackfillRunner.RunResult result = runner.runOne(
                context(), 250L,
                () -> new Permit(permitsClosed),
                (value, stamp) -> {
                    assertSame(source, value);
                    assertEquals(731L, value.capturedCamera);
                    // A changed motion generation is intentionally not part of BackfillContext.
                    observedMotion.set(stamp.motionGeneration());
                });

        assertTrue(result.ran());
        assertEquals(17L, observedMotion.get());
        assertEquals(1, released.get());
        assertEquals(1, permitsClosed.get());
        assertEquals(0, runner.pendingCount());
    }

    @Test public void deniedPermitLeavesOneSourcePendingForLaterAttempt() {
        AtomicInteger released = new AtomicInteger();
        QualityBackfillRunner<Payload> runner = newRunner(
                QualityBackfillRunner.Policy.unbounded());
        QualityBackfillCoordinator.BackfillFrame<Payload> frame = frame(
                new Payload(11L), 100L, 1L, 1L, released);
        runner.offer(frame, 100L);

        QualityBackfillRunner.RunResult denied = runner.runOne(
                context(), 150L, () -> null,
                (value, stamp) -> { throw new AssertionError("denied source ran"); });
        assertEquals(QualityBackfillRunner.RunStatus.DEFERRED_ADMISSION, denied.status());
        assertEquals(1, runner.pendingCount());
        assertEquals(0, released.get());

        QualityBackfillRunner.RunResult admitted = runner.runOne(
                context(), 200L,
                () -> new Permit(new AtomicInteger()),
                (value, stamp) -> assertEquals(11L, value.capturedCamera));
        assertEquals(QualityBackfillRunner.RunStatus.RAN, admitted.status());
        assertEquals(1, released.get());
    }

    @Test public void cadenceAndDutyBoundedBeforePermitOrPoll() {
        AtomicLong clock = new AtomicLong(1_000L);
        QualityBackfillRunner<Payload> runner = new QualityBackfillRunner<>(
                new QualityBackfillCoordinator<>(),
                new QualityBackfillRunner.Policy(100L, 1_000L, 200L, 100L, 0L),
                clock::get);
        AtomicInteger released = new AtomicInteger();
        AtomicInteger runs = new AtomicInteger();

        runner.offer(frame(new Payload(1L), 1_000L, 1L, 1L, released), 1_000L);
        QualityBackfillRunner.RunResult first = runner.runOne(
                context(), 1_000L,
                () -> new Permit(new AtomicInteger()),
                (value, stamp) -> {
                    runs.incrementAndGet();
                    clock.set(1_100L);
                });
        assertEquals(QualityBackfillRunner.RunStatus.RAN, first.status());

        runner.offer(frame(new Payload(2L), 1_200L, 2L, 2L, released), 1_200L);
        QualityBackfillRunner.RunResult second = runner.runOne(
                context(), 1_150L,
                () -> { throw new AssertionError("cadence must not acquire permit"); },
                (value, stamp) -> runs.incrementAndGet());
        assertEquals(QualityBackfillRunner.RunStatus.DEFERRED_CADENCE, second.status());
        assertEquals(50L, second.retryAfterMillis());
        assertEquals(1, runner.pendingCount());

        QualityBackfillRunner.RunResult third = runner.runOne(
                context(), 1_200L,
                () -> new Permit(new AtomicInteger()),
                (value, stamp) -> {
                    runs.incrementAndGet();
                    clock.set(1_300L);
                });
        assertEquals(QualityBackfillRunner.RunStatus.RAN, third.status());

        runner.offer(frame(new Payload(3L), 1_400L, 3L, 3L, released), 1_400L);
        QualityBackfillRunner.RunResult duty = runner.runOne(
                context(), 1_500L,
                () -> { throw new AssertionError("duty limit must not acquire permit"); },
                (value, stamp) -> runs.incrementAndGet());
        assertEquals(QualityBackfillRunner.RunStatus.DEFERRED_DUTY, duty.status());
        assertEquals(500L, duty.retryAfterMillis());
        assertEquals(2, runs.get());
        assertEquals(1, runner.pendingCount());

        // The completed duty window is reusable; the pending source remains the only source.
        QualityBackfillRunner.RunResult afterWindow = runner.runOne(
                context(), 2_000L,
                () -> new Permit(new AtomicInteger()),
                (value, stamp) -> runs.incrementAndGet());
        assertEquals(QualityBackfillRunner.RunStatus.RAN, afterWindow.status());
        assertEquals(0, runner.pendingCount());
        assertEquals(3, runs.get());
        assertEquals(3, released.get());
    }

    @Test public void callbackFailureTripsCircuitButDoesNotRetainPixels() {
        AtomicLong clock = new AtomicLong(1_000L);
        QualityBackfillRunner<Payload> runner = new QualityBackfillRunner<>(
                new QualityBackfillCoordinator<>(),
                new QualityBackfillRunner.Policy(0L, 10_000L, 0L, 0L, 500L),
                clock::get);
        AtomicInteger released = new AtomicInteger();
        runner.offer(frame(new Payload(1L), 1_000L, 1L, 1L, released), 1_000L);

        QualityBackfillRunner.RunResult failed = runner.runOne(
                context(), 1_000L,
                () -> new Permit(new AtomicInteger()),
                (value, stamp) -> { throw new IllegalStateException("synthetic failure"); });
        assertEquals(QualityBackfillRunner.RunStatus.FAILED, failed.status());
        assertEquals(1, released.get());

        runner.offer(frame(new Payload(2L), 1_100L, 2L, 2L, released), 1_100L);
        QualityBackfillRunner.RunResult blocked = runner.runOne(
                context(), 1_200L,
                () -> { throw new AssertionError("circuit must not acquire permit"); },
                (value, stamp) -> { throw new AssertionError("circuit must not run"); });
        assertEquals(QualityBackfillRunner.RunStatus.DEFERRED_CIRCUIT, blocked.status());
        assertEquals(300L, blocked.retryAfterMillis());
        assertTrue(!runner.circuitAllows(1_200L));
        assertEquals(300L, runner.circuitRetryAfterMillis(1_200L));
        assertEquals(1, runner.pendingCount());

        QualityBackfillRunner.RunResult recovered = runner.runOne(
                context(), 1_500L,
                () -> new Permit(new AtomicInteger()),
                (value, stamp) -> assertEquals(2L, value.capturedCamera));
        assertEquals(QualityBackfillRunner.RunStatus.RAN, recovered.status());
        assertEquals(2, released.get());
    }

    @Test public void policyResetClearsCircuitAndDutyWithoutDiscardingPendingSource() {
        AtomicLong clock = new AtomicLong(1_000L);
        QualityBackfillRunner<Payload> runner = new QualityBackfillRunner<>(
                new QualityBackfillCoordinator<>(),
                new QualityBackfillRunner.Policy(0L, 10_000L, 100L, 100L, 500L),
                clock::get);
        AtomicInteger released = new AtomicInteger();
        runner.offer(frame(new Payload(1L), 1_000L, 1L, 1L, released), 1_000L);
        assertEquals(QualityBackfillRunner.RunStatus.FAILED, runner.runOne(
                context(), 1_000L,
                () -> new Permit(new AtomicInteger()),
                (value, stamp) -> { throw new IllegalStateException("synthetic"); }).status());

        runner.offer(frame(new Payload(2L), 1_100L, 2L, 2L, released), 1_100L);
        assertTrue(!runner.circuitAllows(1_200L));
        runner.resetPolicyState();
        assertTrue(runner.circuitAllows(1_200L));
        assertEquals(0L, runner.circuitRetryAfterMillis(1_200L));
        assertEquals(1, runner.pendingCount());

        assertEquals(QualityBackfillRunner.RunStatus.RAN, runner.runOne(
                context(), 1_200L,
                () -> new Permit(new AtomicInteger()),
                (value, stamp) -> assertEquals(2L, value.capturedCamera)).status());
        assertEquals(2, released.get());
    }

    @Test public void fenceMismatchReleasesClaimedSourceExactlyOnce() {
        AtomicInteger released = new AtomicInteger();
        QualityBackfillRunner<Payload> runner = newRunner(
                QualityBackfillRunner.Policy.unbounded());
        runner.offer(frame(new Payload(1L), 100L, 1L, 1L, released), 100L);

        QualityBackfillCoordinator.BackfillContext otherSurface = new QualityBackfillCoordinator.BackfillContext(
                1L, 1L, "other-surface", 1L, 1L, true);
        QualityBackfillRunner.RunResult result = runner.runOne(
                otherSurface, 150L,
                () -> new Permit(new AtomicInteger()),
                (value, stamp) -> { throw new AssertionError("fence mismatch ran"); });

        assertEquals(QualityBackfillRunner.RunStatus.FENCE_MISMATCH, result.status());
        assertEquals(1, released.get());
        assertEquals(0, runner.pendingCount());
    }

    @Test public void reentrantDrainCannotRunASecondSourceWhileFirstIsActive() {
        AtomicInteger released = new AtomicInteger();
        QualityBackfillRunner<Payload> runner = newRunner(
                QualityBackfillRunner.Policy.unbounded());
        AtomicReference<QualityBackfillRunner.RunResult> nested = new AtomicReference<>();
        runner.offer(frame(new Payload(1L), 100L, 1L, 1L, released), 100L);

        QualityBackfillRunner.RunResult first = runner.runOne(
                context(), 150L,
                () -> new Permit(new AtomicInteger()),
                (value, stamp) -> {
                    runner.offer(frame(new Payload(2L), 120L, 2L, 2L, released), 150L);
                    nested.set(runner.runOne(
                            context(), 150L,
                            () -> { throw new AssertionError("busy drain acquired permit"); },
                            (second, secondStamp) -> {
                                throw new AssertionError("busy drain ran source");
                            }));
                });
        assertEquals(QualityBackfillRunner.RunStatus.RAN, first.status());
        assertEquals(QualityBackfillRunner.RunStatus.BUSY, nested.get().status());
        assertEquals(1, runner.pendingCount());

        QualityBackfillRunner.RunResult second = runner.runOne(
                context(), 450L,
                () -> new Permit(new AtomicInteger()),
                (value, stamp) -> assertEquals(2L, value.capturedCamera));
        assertEquals(QualityBackfillRunner.RunStatus.RAN, second.status());
        assertEquals(2, released.get());
    }

    @Test public void throwingSourceReleaserCannotStrandPermitOrRunner() {
        QualityBackfillRunner<Payload> runner = newRunner(
                QualityBackfillRunner.Policy.unbounded());
        AtomicInteger permitCloses = new AtomicInteger();
        runner.offer(new QualityBackfillCoordinator.BackfillFrame<>(
                new Payload(1L), stamp(100L, 1L, 1L), ignored -> {
                    throw new IllegalStateException("release");
                }), 100L);

        assertTrue(runner.runOne(
                context(), 120L, () -> new Permit(permitCloses),
                (value, stamp) -> {}).ran());

        assertEquals(1, permitCloses.get());
        assertTrue(!runner.isRunning());
        AtomicInteger released = new AtomicInteger();
        runner.offer(frame(new Payload(2L), 140L, 2L, 2L, released), 140L);
        assertTrue(runner.runOne(
                context(), 160L, () -> new Permit(permitCloses),
                (value, stamp) -> {}).ran());
        assertEquals(1, released.get());
        assertEquals(2, permitCloses.get());
    }

    private static QualityBackfillRunner<Payload> newRunner(
            QualityBackfillRunner.Policy policy) {
        return new QualityBackfillRunner<>(new QualityBackfillCoordinator<>(), policy);
    }

    private static QualityBackfillCoordinator.BackfillFrame<Payload> frame(
            Payload payload,
            long capturedAt,
            long motionGeneration,
            long sequence,
            AtomicInteger released) {
        return new QualityBackfillCoordinator.BackfillFrame<>(
                payload, stamp(capturedAt, motionGeneration, sequence),
                ignored -> released.incrementAndGet());
    }

    private static QualityBackfillCoordinator.BackfillStamp stamp(
            long capturedAt,
            long motionGeneration,
            long sequence) {
        return new QualityBackfillCoordinator.BackfillStamp(
                1L, 1L, "surface", 1L, 1L, true,
                capturedAt, motionGeneration, sequence);
    }

    private static QualityBackfillCoordinator.BackfillContext context() {
        return new QualityBackfillCoordinator.BackfillContext(
                1L, 1L, "surface", 1L, 1L, true);
    }

    private static final class Payload {
        private final long capturedCamera;

        private Payload(long capturedCamera) {
            this.capturedCamera = capturedCamera;
        }
    }

    private static final class Permit implements AutoCloseable {
        private final AtomicInteger closed;

        private Permit(AtomicInteger closed) {
            this.closed = closed;
        }

        @Override
        public void close() {
            closed.incrementAndGet();
        }
    }
}
