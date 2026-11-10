package com.subhub.app.detection;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

/** A partly overlapping body part is not evidence that a missing face changed class. */
public final class VisualTrackCategorySafetyTest {
    @Test
    public void missingFaceCannotConsumeNewBodyPartObservation() {
        assertIndependent("FACE_FEMALE", "face_female",
                "FEMALE_BREAST_EXPOSED", "breasts", 1);
    }

    @Test
    public void missingFaceCannotHandItsIdentityToConfirmedBodyPart() {
        assertIndependent("FACE_FEMALE", "face_female",
                "FEMALE_BREAST_EXPOSED", "breasts", 2);
    }

    @Test
    public void missingBodyPartCannotConsumeNearbyFace() {
        assertIndependent("FEMALE_BREAST_EXPOSED", "breasts",
                "FACE_MALE", "face", 2);
    }

    @Test
    public void sexSpecificFacePoliciesRemainIndependentDuringReacquisition() {
        assertIndependent("FACE_FEMALE", "face_female",
                "FACE_MALE", "face_male", 2);
    }

    private static void assertIndependent(String staleClass, String staleCategory,
            String freshClass, String freshCategory, int freshObservations) {
        TrackedObject stale = track(41, staleClass, staleCategory,
                new BBox(100, 200, 180, 180), 7);
        stale.miss(stale.getBox());
        TrackedObject fresh = track(82, freshClass, freshCategory,
                new BBox(100, 286, 176, 184), freshObservations);
        for (List<TrackedObject> input : List.of(List.of(stale, fresh), List.of(fresh, stale))) {
            VisualTrackArbitrator.Result result = VisualTrackArbitrator.arbitrate(input);
            assertEquals(2, result.tracks().size());
            assertEquals(0, result.suppressed());
            assertEquals(0, result.handedOff());
            for (TrackedObject output : result.tracks()) {
                TrackedObject original = output.getId() == stale.getId() ? stale : fresh;
                assertEquals(original.getBox(), output.getBox());
                assertEquals(original.getCategory(), output.getCategory());
            }
        }
    }

    private static TrackedObject track(int id, String className, String category,
            BBox box, int observations) {
        Detection detection = new Detection(className, category, 0.90f, box, true, true);
        TrackedObject track = new TrackedObject(id, detection, 0L);
        for (int index = 1; index < observations; index++) {
            track.update(detection, box, 0f, 0f, index * 10_000_000L);
        }
        return track;
    }
}
