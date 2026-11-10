package com.subhub.app.detection;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

public final class VisualTrackArbitratorTest {
    @Test
    public void nearIdenticalNewIdentityCannotRenderBesideEstablishedTrack() {
        TrackedObject established = tracked(1, new BBox(100, 200, 180, 180), 6);
        TrackedObject duplicate = tracked(2, new BBox(106, 204, 178, 182), 1);

        VisualTrackArbitrator.Result result = VisualTrackArbitrator.arbitrate(
                List.of(duplicate, established));

        assertEquals(1, result.tracks().size());
        assertEquals(1, result.tracks().get(0).getId());
        assertEquals(1, result.suppressed());
    }

    @Test
    public void nearbyDistinctFacesRemainIndependent() {
        TrackedObject first = tracked(1, new BBox(100, 200, 180, 180), 5);
        TrackedObject second = tracked(2, new BBox(220, 205, 180, 180), 4);

        VisualTrackArbitrator.Result result = VisualTrackArbitrator.arbitrate(
                List.of(first, second));

        assertEquals(2, result.tracks().size());
        assertEquals(0, result.suppressed());
    }

    @Test
    public void nestedDifferentSizedCoverageIsNotMistakenForDuplicate() {
        TrackedObject outer = tracked(1, new BBox(100, 200, 240, 240), 5);
        TrackedObject inner = tracked(2, new BBox(145, 245, 120, 120), 4);

        VisualTrackArbitrator.Result result = VisualTrackArbitrator.arbitrate(
                List.of(outer, inner));

        assertEquals(2, result.tracks().size());
        assertEquals(0, result.suppressed());
    }

    @Test
    public void offsetQualityOnlyTrackCannotRenderBesideFastIdentity() {
        TrackedObject fast = tracked(1, new BBox(100, 200, 180, 180), 5);
        TrackedObject quality = qualityTracked(2, new BBox(72, 176, 240, 230), 3);

        VisualTrackArbitrator.Result result = VisualTrackArbitrator.arbitrate(
                List.of(quality, fast));

        assertEquals(1, result.tracks().size());
        assertEquals(fast.getId(), result.tracks().get(0).getId());
        assertEquals(1, result.suppressed());
    }

    @Test
    public void freshReacquisitionUsesOldRenderIdentityAcrossHalfHeightOffset() {
        TrackedObject stale = tracked(41, new BBox(100, 200, 180, 180), 7);
        stale.miss(stale.getBox());
        TrackedObject fresh = tracked(82, new BBox(100, 286, 176, 184), 2);

        VisualTrackArbitrator.Result result = VisualTrackArbitrator.arbitrate(
                List.of(stale, fresh));

        assertEquals(1, result.tracks().size());
        assertEquals(41, result.tracks().get(0).getId());
        assertEquals(fresh.getBox(), result.tracks().get(0).getBox());
        assertEquals(1, result.suppressed());
        assertEquals(1, result.handedOff());
    }

    @Test
    public void oneFrameReacquisitionCannotPullEstablishedRenderGeometry() {
        TrackedObject stale = tracked(41, new BBox(100, 200, 180, 180), 7);
        stale.miss(stale.getBox());
        TrackedObject oneFrame = tracked(82, new BBox(100, 286, 176, 184), 1);

        VisualTrackArbitrator.Result result = VisualTrackArbitrator.arbitrate(
                List.of(stale, oneFrame));

        assertEquals(1, result.tracks().size());
        assertEquals(stale.getBox(), result.tracks().get(0).getBox());
        assertEquals(0, result.handedOff());
    }

    @Test
    public void changedCategoryAloneCannotProvePostScrollIdentity() {
        TrackedObject stale = tracked(
                41, "face_female", new BBox(100, 200, 180, 180), 7);
        stale.miss(stale.getBox());
        TrackedObject fresh = tracked(
                82, "EXPOSED", new BBox(100, 286, 176, 184), 2);

        VisualTrackArbitrator.Result result = VisualTrackArbitrator.arbitrate(
                List.of(stale, fresh));

        assertEquals(2, result.tracks().size());
        // A different category at partial overlap is not sufficient correspondence evidence.
        assertEquals(stale.getId(), result.tracks().get(0).getId());
        assertEquals(stale.getBox(), result.tracks().get(0).getBox());
        assertEquals(0, result.suppressed());
        assertEquals(0, result.handedOff());
    }

    private static TrackedObject tracked(int id, BBox box, int observations) {
        return tracked(id, "face", box, observations);
    }

    private static TrackedObject tracked(
            int id, String category, BBox box, int observations) {
        Detection detection = new Detection("FACE_FEMALE", category, 0.90f,
                box, true, true);
        TrackedObject track = new TrackedObject(id, detection, 0L);
        for (int index = 1; index < observations; index++) {
            track.update(detection, box, 0f, 0f, index * 10_000_000L);
        }
        return track;
    }

    private static TrackedObject qualityTracked(int id, BBox box, int observations) {
        Detection detection = new Detection("FACE_FEMALE", "face", 0.95f,
                box, true, true, Detection.ObservationSource.QUALITY_VISUAL,
                Detection.GeometryQuality.MODEL, null);
        TrackedObject track = new TrackedObject(id, detection, 0L);
        for (int index = 1; index < observations; index++) {
            track.update(detection, box, 0f, 0f, index * 10_000_000L);
        }
        return track;
    }
}
