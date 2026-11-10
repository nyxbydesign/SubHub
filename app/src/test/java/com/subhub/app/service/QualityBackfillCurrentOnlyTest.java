package com.subhub.app.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

public final class QualityBackfillCurrentOnlyTest {
    @Test public void emptySurfaceCurrentOnlySourceIsAcceptedAndReleasedOnce() {
        AtomicInteger released = new AtomicInteger();
        QualityBackfillCoordinator<String> coordinator = new QualityBackfillCoordinator<>();
        QualityBackfillCoordinator.BackfillFrame<String> frame = currentFrame(
                "current", 100L, 10L, released);

        assertTrue(coordinator.offer(frame, 100L).accepted());
        QualityBackfillCoordinator.PollResult<String> polled = coordinator.poll(
                currentContext(1L, 2L, 7, 3L, 4L, true,
                        5L, 11L, 13L, 10L),
                150L);

        assertTrue(polled.ready());
        assertSame("current", polled.frame().resource());
        assertEquals(QualityBackfillCoordinator.SourceMode.CURRENT_ONLY,
                polled.frame().stamp().sourceMode());
        assertEquals("", polled.frame().stamp().surfaceKey());
        polled.frame().close();
        polled.frame().close();
        assertEquals(1, released.get());
    }

    @Test public void unchangedCurrentOnlySourceMayWaitManyFastTicksWhileFresh() {
        AtomicInteger released = new AtomicInteger();
        QualityBackfillCoordinator<String> coordinator = new QualityBackfillCoordinator<>();
        coordinator.offer(currentFrame("current", 100L, 10L, released), 100L);
        QualityBackfillCoordinator.PollResult<String> result = coordinator.poll(
                currentContext(1L, 2L, 7, 3L, 4L, true, 5L, 11L, 13L, 10_000L), 150L);
        assertTrue(result.ready());
        result.frame().close();
        assertEquals(1, released.get());
    }

    @Test public void currentOnlyExpiryReleasesWithoutRunning() {
        AtomicInteger released = new AtomicInteger();
        QualityBackfillCoordinator<String> coordinator = new QualityBackfillCoordinator<>();
        coordinator.offer(currentFrame("current", 100L, 10L, released), 100L);
        QualityBackfillCoordinator.PollResult<String> result = coordinator.poll(
                currentContext(1L, 2L, 7, 3L, 4L, true, 5L, 11L, 13L, 11L), 2601L);
        assertEquals(QualityBackfillCoordinator.PollStatus.EXPIRED, result.status());
        assertEquals(1, released.get());
    }

    @Test public void everyCurrentOnlyFenceRejectsAndReleasesTheSource() {
        assertCurrentOnlyFenceRejected(currentContext(
                9L, 2L, 7, 3L, 4L, true, 5L, 11L, 13L, 10L));
        assertCurrentOnlyFenceRejected(currentContext(
                1L, 9L, 7, 3L, 4L, true, 5L, 11L, 13L, 10L));
        assertCurrentOnlyFenceRejected(currentContext(
                1L, 2L, 9, 3L, 4L, true, 5L, 11L, 13L, 10L));
        assertCurrentOnlyFenceRejected(currentContext(
                1L, 2L, 7, 9L, 4L, true, 5L, 11L, 13L, 10L));
        assertCurrentOnlyFenceRejected(currentContext(
                1L, 2L, 7, 3L, 9L, true, 5L, 11L, 13L, 10L));
        assertCurrentOnlyFenceRejected(currentContext(
                1L, 2L, 7, 3L, 4L, false, 5L, 11L, 13L, 10L));
        assertCurrentOnlyFenceRejected(currentContext(
                1L, 2L, 7, 3L, 4L, true, 9L, 11L, 13L, 10L));
        assertCurrentOnlyFenceRejected(currentContext(
                1L, 2L, 7, 3L, 4L, true, 5L, 99L, 13L, 10L));
        assertCurrentOnlyFenceRejected(currentContext(
                1L, 2L, 7, 3L, 4L, true, 5L, 11L, 99L, 10L));
        assertCurrentOnlyFenceRejected(currentContext(
                1L, 2L, 7, 3L, 4L, true, 5L, 11L, 13L, 9L));
        assertCurrentOnlyFenceRejected(new QualityBackfillCoordinator.BackfillContext(
                1L, 2L, "surface", 3L, 4L, true));
    }

    @Test public void cacheBackfillRemainsMotionTolerant() {
        AtomicInteger released = new AtomicInteger();
        QualityBackfillCoordinator<String> coordinator = new QualityBackfillCoordinator<>();
        QualityBackfillCoordinator.BackfillStamp stamp =
                new QualityBackfillCoordinator.BackfillStamp(
                        1L, 2L, "surface", 3L, 4L, true,
                        100L, 999L, 10L);
        coordinator.offer(new QualityBackfillCoordinator.BackfillFrame<>(
                "cache", stamp, ignored -> released.incrementAndGet()), 100L);

        QualityBackfillCoordinator.PollResult<String> polled = coordinator.poll(
                new QualityBackfillCoordinator.BackfillContext(
                        1L, 2L, "surface", 3L, 4L, true),
                150L);

        assertTrue(polled.ready());
        polled.frame().close();
        assertEquals(1, released.get());
    }

    @Test public void currentOnlyObservationCannotEnterCandidateTable() {
        QualityBackfillCoordinator<String> coordinator = new QualityBackfillCoordinator<>();

        QualityBackfillCoordinator.ObservationResult result = coordinator.observe(
                currentStamp(100L, 10L),
                currentContext(1L, 2L, 7, 3L, 4L, true,
                        5L, 11L, 13L, 10L),
                150L,
                Collections.singletonList(new QualityBackfillCoordinator.BackfillRegion(
                        "face", "face", .9f, new com.subhub.app.detection.BBox(10, 10, 40, 40),
                        true, false, null)));

        assertEquals(QualityBackfillCoordinator.ObservationStatus.REJECTED_CURRENT_ONLY,
                result.status());
        assertEquals(0, coordinator.candidateCount());
        assertTrue(result.readyRegions().isEmpty());
    }

    @Test public void latestCurrentOnlySourceReplacesAndReleasesOlderSource() {
        AtomicInteger released = new AtomicInteger();
        QualityBackfillCoordinator<String> coordinator = new QualityBackfillCoordinator<>();
        coordinator.offer(currentFrame("older", 100L, 10L, released), 100L);

        assertEquals(QualityBackfillCoordinator.OfferStatus.ACCEPTED_REPLACED_OLDER,
                coordinator.offer(currentFrame("newer", 101L, 11L, released), 101L).status());
        assertEquals(1, released.get());
        assertEquals(1, coordinator.pendingCount());

        QualityBackfillCoordinator.PollResult<String> polled = coordinator.poll(
                currentContext(1L, 2L, 7, 3L, 4L, true,
                        5L, 11L, 13L, 12L),
                150L);
        assertSame("newer", polled.frame().resource());
        polled.frame().close();
        assertEquals(2, released.get());
    }

    @Test public void emptySurfaceRemainsIllegalForCacheBackfill() {
        try {
            new QualityBackfillCoordinator.BackfillStamp(
                    1L, 2L, "", 3L, 4L, true,
                    100L, 5L, 10L);
            fail("empty cache surface must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("surfaceKey"));
        }
    }

    private static void assertCurrentOnlyFenceRejected(
            QualityBackfillCoordinator.BackfillContext context) {
        AtomicInteger released = new AtomicInteger();
        QualityBackfillCoordinator<String> coordinator = new QualityBackfillCoordinator<>();
        coordinator.offer(currentFrame("current", 100L, 10L, released), 100L);

        assertEquals(QualityBackfillCoordinator.PollStatus.FENCE_MISMATCH,
                coordinator.poll(context, 150L).status());
        assertEquals(1, released.get());
        assertEquals(0, coordinator.pendingCount());
    }

    private static QualityBackfillCoordinator.BackfillFrame<String> currentFrame(
            String value, long capturedAt, long sequence, AtomicInteger released) {
        return new QualityBackfillCoordinator.BackfillFrame<>(
                value, currentStamp(capturedAt, sequence),
                ignored -> released.incrementAndGet());
    }

    private static QualityBackfillCoordinator.BackfillStamp currentStamp(
            long capturedAt, long sequence) {
        return QualityBackfillCoordinator.BackfillStamp.currentOnly(
                1L, 2L, 7, 3L, 4L, true,
                capturedAt, 5L, 11L, 13L, sequence);
    }

    private static QualityBackfillCoordinator.BackfillContext currentContext(
            long captureEpoch,
            long documentEpoch,
            int applicationWindowId,
            long transformGeneration,
            long phaseToken,
            boolean phaseCertain,
            long motionGeneration,
            long cameraX,
            long cameraY,
            long captureSequence) {
        return QualityBackfillCoordinator.BackfillContext.currentOnly(
                captureEpoch, documentEpoch, applicationWindowId,
                transformGeneration, phaseToken, phaseCertain,
                motionGeneration, cameraX, cameraY, captureSequence);
    }
}
