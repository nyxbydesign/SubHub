package com.subhub.app.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.subhub.app.detection.BBox;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class QualityBackfillCoordinatorTest {
    @Test public void mailboxKeepsLatestAndClosesReplacedFrameExactlyOnce() {
        AtomicInteger released = new AtomicInteger();
        QualityBackfillCoordinator<String> coordinator = new QualityBackfillCoordinator<>();
        QualityBackfillCoordinator.BackfillFrame<String> older = frame(
                "older", 1L, 100L, 1L, released);
        QualityBackfillCoordinator.BackfillFrame<String> newer = frame(
                "newer", 2L, 200L, 2L, released);

        assertTrue(coordinator.offer(older, 100L).accepted());
        assertEquals(QualityBackfillCoordinator.OfferStatus.ACCEPTED_REPLACED_OLDER,
                coordinator.offer(newer, 200L).status());
        assertEquals(1, released.get());

        QualityBackfillCoordinator.PollResult<String> poll = coordinator.poll(
                context(), 250L);
        assertTrue(poll.ready());
        assertSame("newer", poll.frame().resource());
        poll.frame().close();
        poll.frame().close();
        assertEquals(2, released.get());
        assertEquals(QualityBackfillCoordinator.PollStatus.EMPTY,
                coordinator.poll(context(), 250L).status());
    }

    @Test public void outOfOrderOlderFrameCannotReplacePendingNewerFrame() {
        AtomicInteger released = new AtomicInteger();
        QualityBackfillCoordinator<String> coordinator = new QualityBackfillCoordinator<>();
        QualityBackfillCoordinator.BackfillFrame<String> newer = frame(
                "newer", 2L, 200L, 2L, released);
        QualityBackfillCoordinator.BackfillFrame<String> older = frame(
                "older", 1L, 100L, 1L, released);

        coordinator.offer(newer, 200L);
        assertEquals(QualityBackfillCoordinator.OfferStatus.REJECTED_OLDER,
                coordinator.offer(older, 200L).status());
        assertEquals(1, released.get());
        QualityBackfillCoordinator.PollResult<String> poll = coordinator.poll(
                context(), 250L);
        assertSame("newer", poll.frame().resource());
        poll.frame().close();
        assertEquals(2, released.get());
    }

    @Test public void stalePendingFrameIsReleasedWithoutBeingClaimed() {
        AtomicInteger released = new AtomicInteger();
        QualityBackfillCoordinator<String> coordinator = new QualityBackfillCoordinator<>(
                4, 100L, .35f);
        QualityBackfillCoordinator.BackfillFrame<String> stale = frame(
                "stale", 1L, 100L, 1L, released);
        assertEquals(QualityBackfillCoordinator.OfferStatus.ACCEPTED,
                coordinator.offer(stale, 100L).status());
        assertEquals(QualityBackfillCoordinator.PollStatus.EXPIRED,
                coordinator.poll(context(), 201L).status());
        assertEquals(1, released.get());
        assertEquals(0, coordinator.pendingCount());
    }

    @Test public void allNonMotionFencesRejectAClaim() {
        int[] mismatchKinds = {0, 1, 2, 3, 4};
        for (int mismatch : mismatchKinds) {
            AtomicInteger released = new AtomicInteger();
            QualityBackfillCoordinator<String> coordinator = new QualityBackfillCoordinator<>();
            coordinator.offer(frame("frame", 1L, 100L, 1L, released), 100L);
            QualityBackfillCoordinator.BackfillContext mismatchContext = context(
                    mismatch == 0 ? 2L : 1L,
                    mismatch == 1 ? 2L : 1L,
                    mismatch == 2 ? "other" : "surface",
                    mismatch == 3 ? 2L : 1L,
                    mismatch == 4 ? 2L : 1L,
                    true);
            assertEquals(QualityBackfillCoordinator.PollStatus.FENCE_MISMATCH,
                    coordinator.poll(mismatchContext, 150L).status());
            assertEquals(1, released.get());
        }
    }

    @Test public void motionGenerationIsNotABackfillFence() {
        QualityBackfillCoordinator<String> coordinator = new QualityBackfillCoordinator<>();
        QualityBackfillCoordinator.BackfillContext context = context();
        QualityBackfillCoordinator.BackfillStamp stamp = stamp(
                1L, 100L, 7L, 99_999L);

        QualityBackfillCoordinator.ObservationResult result = coordinator.observe(
                stamp, context, 150L, List.of(region("FACE_FEMALE", "face_female", 0, 0)));
        assertEquals(QualityBackfillCoordinator.ObservationStatus.ACCEPTED, result.status());
        assertEquals(1, result.inserted());
        assertEquals(1, coordinator.candidateCount());
    }

    @Test public void twoDistinctCapturesPromoteOneWorldRegionButSameCaptureDoesNot() {
        QualityBackfillCoordinator<String> coordinator = new QualityBackfillCoordinator<>();
        QualityBackfillCoordinator.BackfillContext context = context();
        QualityBackfillCoordinator.BackfillRegion first = region(
                "FACE_FEMALE", "face_female", 0, 0);
        QualityBackfillCoordinator.BackfillRegion sameCapture = region(
                "FACE_MALE", "face_male", 4, 4);
        QualityBackfillCoordinator.BackfillRegion secondCapture = region(
                "FACE_MALE", "face_male", 10, 10);

        QualityBackfillCoordinator.ObservationResult firstResult = coordinator.observe(
                stamp(1L, 100L, 1L, 1L), context, 100L, List.of(first));
        assertEquals(0, firstResult.newlyPromoted());
        QualityBackfillCoordinator.ObservationResult duplicateResult = coordinator.observe(
                stamp(1L, 120L, 2L, 1L), context, 120L, List.of(sameCapture));
        assertEquals(0, duplicateResult.newlyPromoted());
        QualityBackfillCoordinator.ObservationResult promoted = coordinator.observe(
                stamp(1L, 140L, 99L, 2L), context, 140L, List.of(secondCapture));
        assertEquals(QualityBackfillCoordinator.ObservationStatus.PROMOTED,
                promoted.status());
        assertEquals(1, promoted.newlyPromoted());
        assertEquals(1, promoted.readyRegions().size());
    }

    @Test public void sameFrameDuplicateRegionsCannotCreateTwinPromotions() {
        QualityBackfillCoordinator<String> coordinator = new QualityBackfillCoordinator<>();
        QualityBackfillCoordinator.BackfillContext context = context();
        coordinator.observe(stamp(1L, 100L, 1L, 1L), context, 100L, List.of(
                region("FACE_FEMALE", "face_female", 0, 0),
                region("FACE_FEMALE", "face_female", 4, 4)));
        assertEquals(1, coordinator.candidateCount());

        QualityBackfillCoordinator.ObservationResult promoted = coordinator.observe(
                stamp(1L, 120L, 2L, 2L), context, 120L, List.of(
                        region("FACE_FEMALE", "face_female", 8, 8),
                        region("FACE_FEMALE", "face_female", 10, 10)));

        assertEquals(1, promoted.newlyPromoted());
        assertEquals(1, promoted.readyRegions().size());
        assertEquals(1, coordinator.candidateCount());
    }

    @Test public void categoryFamilyAndWorldIouControlMatching() {
        QualityBackfillCoordinator<String> coordinator = new QualityBackfillCoordinator<>();
        QualityBackfillCoordinator.BackfillContext context = context();
        QualityBackfillCoordinator.BackfillRegion first = region(
                "FACE_FEMALE", "face_female", 0, 0);
        coordinator.observe(stamp(1L, 100L, 1L, 1L), context, 100L, List.of(first));

        // Male/female faces share the face family and overlap in world space.
        QualityBackfillCoordinator.ObservationResult familyMatch = coordinator.observe(
                stamp(1L, 120L, 2L, 2L), context, 120L,
                List.of(region("FACE_MALE", "face_male", 10, 10)));
        assertEquals(1, familyMatch.newlyPromoted());

        // A different family and a non-overlapping box cannot attach to that candidate.
        QualityBackfillCoordinator.ObservationResult differentFamily = coordinator.observe(
                stamp(1L, 140L, 3L, 3L), context, 140L,
                List.of(region("TEXT", "text_smut", 10, 10)));
        assertEquals(0, differentFamily.newlyPromoted());
        QualityBackfillCoordinator.ObservationResult lowIou = coordinator.observe(
                stamp(1L, 160L, 4L, 4L), context, 160L,
                List.of(region("FACE_FEMALE", "face_female", 500, 500)));
        assertEquals(0, lowIou.newlyPromoted());
        assertEquals(3, coordinator.candidateCount());
    }

    @Test public void crossFamilyObservationWithReturnDriftCannotConfirmBackfill() {
        QualityBackfillCoordinator<String> coordinator = new QualityBackfillCoordinator<>();
        QualityBackfillCoordinator.BackfillContext context = context();
        coordinator.observe(stamp(1L, 100L, 1L, 1L), context, 100L,
                List.of(region("FACE_FEMALE", "face_female", 0, 0)));

        QualityBackfillCoordinator.ObservationResult promoted = coordinator.observe(
                stamp(1L, 200L, 2L, 2L), context, 200L,
                List.of(region("FEMALE_BREAST_EXPOSED", "breasts", 55, 0)));

        assertEquals(QualityBackfillCoordinator.ObservationStatus.ACCEPTED,
                promoted.status());
        assertEquals(0, promoted.newlyPromoted());
        assertEquals(2, coordinator.candidateCount());
    }

    @Test public void distinctBodyPartCannotBorrowFaceConfirmationAtIdenticalGeometry() {
        QualityBackfillCoordinator<String> coordinator = new QualityBackfillCoordinator<>();
        coordinator.observe(stamp(1L, 100L, 1L, 1L), context(), 100L,
                List.of(region("FACE_FEMALE", "face_female", 0, 0)));
        QualityBackfillCoordinator.ObservationResult firstBody = coordinator.observe(
                stamp(1L, 200L, 2L, 2L), context(), 200L,
                List.of(region("FEMALE_BREAST_EXPOSED", "breasts", 0, 0)));
        assertEquals(0, firstBody.matched());
        assertEquals(0, firstBody.newlyPromoted());
        assertEquals(0, firstBody.readyRegions().size());
        assertEquals(2, coordinator.candidateCount());

        QualityBackfillCoordinator.ObservationResult secondBody = coordinator.observe(
                stamp(1L, 300L, 3L, 3L), context(), 300L,
                List.of(region("FEMALE_BREAST_EXPOSED", "breasts", 0, 0)));
        assertEquals(1, secondBody.newlyPromoted());
        assertEquals("breasts", secondBody.readyRegions().get(0).category());
    }

    @Test public void unrelatedVisualRegionsInOneCaptureDoNotDeduplicateEachOther() {
        QualityBackfillCoordinator<String> coordinator = new QualityBackfillCoordinator<>();
        List<QualityBackfillCoordinator.BackfillRegion> regions = List.of(
                region("FACE_FEMALE", "face_female", 0, 0),
                region("FEMALE_BREAST_EXPOSED", "breasts", 0, 0));
        coordinator.observe(stamp(1L, 100L, 1L, 1L), context(), 100L, regions);
        assertEquals(2, coordinator.candidateCount());
        QualityBackfillCoordinator.ObservationResult repeated = coordinator.observe(
                stamp(1L, 200L, 2L, 2L), context(), 200L, regions);
        assertEquals(2, repeated.newlyPromoted());
        assertEquals(2, repeated.readyRegions().size());
    }

    @Test public void promotedBodyPartCannotLendItsReadyStateToAnotherCategory() {
        QualityBackfillCoordinator<String> coordinator = new QualityBackfillCoordinator<>();
        for (int sequence = 1; sequence <= 2; sequence++) {
            coordinator.observe(stamp(1L, sequence * 100L, sequence, sequence),
                    context(), sequence * 100L,
                    List.of(region("FEMALE_BREAST_EXPOSED", "breasts", 0, 0)));
        }
        QualityBackfillCoordinator.ObservationResult switched = coordinator.observe(
                stamp(1L, 300L, 3L, 3L), context(), 300L,
                List.of(region("BUTTOCKS_EXPOSED", "buttocks", 0, 0)));
        assertEquals(0, switched.refined());
        assertEquals(0, switched.newlyPromoted());
        assertEquals(0, switched.readyRegions().size());
        assertEquals(2, coordinator.candidateCount());
    }

    @Test public void absentObservationsNeverRemoveCandidates() {
        QualityBackfillCoordinator<String> coordinator = new QualityBackfillCoordinator<>();
        QualityBackfillCoordinator.BackfillContext context = context();
        coordinator.observe(stamp(1L, 100L, 1L, 1L), context, 100L,
                List.of(region("FACE_FEMALE", "face_female", 0, 0)));
        QualityBackfillCoordinator.ObservationResult absent = coordinator.observe(
                stamp(1L, 120L, 2L, 2L), context, 120L, List.of());
        assertEquals(QualityBackfillCoordinator.ObservationStatus.ACCEPTED, absent.status());
        assertEquals(1, coordinator.candidateCount());

        QualityBackfillCoordinator.ObservationResult returnObservation = coordinator.observe(
                stamp(1L, 140L, 3L, 3L), context, 140L,
                List.of(region("FACE_FEMALE", "face_female", 8, 8)));
        assertEquals(1, returnObservation.newlyPromoted());
    }

    @Test public void candidateTableHasHardCapacityIndependentOfAbsence() {
        QualityBackfillCoordinator<String> coordinator = new QualityBackfillCoordinator<>(
                2, 2_500L, .35f);
        QualityBackfillCoordinator.BackfillContext context = context();
        coordinator.observe(stamp(1L, 100L, 1L, 1L), context, 100L,
                List.of(region("A", "a", 0, 0), region("B", "b", 500, 500)));
        assertEquals(2, coordinator.candidateCount());
        coordinator.observe(stamp(1L, 120L, 2L, 2L), context, 120L,
                List.of(region("C", "c", 1_000, 1_000)));
        assertEquals(2, coordinator.candidateCount());
    }

    @Test public void clearAndCloseReleaseAcceptedFrameOnlyOnce() {
        AtomicInteger released = new AtomicInteger();
        QualityBackfillCoordinator<String> coordinator = new QualityBackfillCoordinator<>();
        QualityBackfillCoordinator.BackfillFrame<String> frame = frame(
                "frame", 1L, 100L, 1L, released);
        coordinator.offer(frame, 100L);
        coordinator.clear();
        coordinator.clear();
        frame.close();
        assertEquals(1, released.get());

        QualityBackfillCoordinator.BackfillFrame<String> afterClose = frame(
                "after", 2L, 200L, 2L, released);
        coordinator.close();
        assertEquals(QualityBackfillCoordinator.OfferStatus.REJECTED_CLOSED,
                coordinator.offer(afterClose, 200L).status());
        assertEquals(2, released.get());
    }

    private static QualityBackfillCoordinator.BackfillFrame<String> frame(
            String value,
            long sequence,
            long captureUptime,
            long motionGeneration,
            AtomicInteger released) {
        return new QualityBackfillCoordinator.BackfillFrame<>(
                value, stamp(1L, captureUptime, motionGeneration, sequence),
                ignored -> released.incrementAndGet());
    }

    private static QualityBackfillCoordinator.BackfillStamp stamp(
            long captureEpoch,
            long captureUptime,
            long motionGeneration,
            long sequence) {
        return new QualityBackfillCoordinator.BackfillStamp(
                captureEpoch, 1L, "surface", 1L, 1L, true,
                captureUptime, motionGeneration, sequence);
    }

    private static QualityBackfillCoordinator.BackfillContext context() {
        return context(1L, 1L, "surface", 1L, 1L, true);
    }

    private static QualityBackfillCoordinator.BackfillContext context(
            long captureEpoch,
            long documentEpoch,
            String surface,
            long transformGeneration,
            long phaseToken,
            boolean phaseCertain) {
        return new QualityBackfillCoordinator.BackfillContext(
                captureEpoch, documentEpoch, surface,
                transformGeneration, phaseToken, phaseCertain);
    }

    private static QualityBackfillCoordinator.BackfillRegion region(
            String className, String category, int x, int y) {
        return new QualityBackfillCoordinator.BackfillRegion(
                className, category, .9f, new BBox(x, y, 100, 100), true, false, null);
    }
}
