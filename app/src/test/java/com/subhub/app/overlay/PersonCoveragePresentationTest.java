package com.subhub.app.overlay;

import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.PersonBox;
import com.subhub.app.detection.RenderSourceReference;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PersonCoveragePresentationTest {
    private final PersonCoveragePresentation state = new PersonCoveragePresentation();
    private final Detection chest = part("breasts", new BBox(65, 80, 70, 45));
    private final Detection head = part("face", new BBox(80, 20, 40, 40));
    private final RenderTrackSnapshot raw = new RenderTrackSnapshot(
            7, "breasts", chest.getBox(), 0, 0, false);

    private static Detection part(String category, BBox box) {
        return new Detection(category, category, .9f, box, false, false);
    }

    private long begin(long time) {
        return state.begin(Collections.singletonList(raw), Collections.singletonList(chest),
                Arrays.asList(head, chest), 200, 300, time, time, false,
                0, 0, 200, 300, RenderSourceReference.UNKNOWN);
    }

    private BBox box(long now) {
        return state.expand(raw, raw.box(), Collections.emptyList(), now);
    }

    @Test public void provisionalRefinesWithoutChangingRawOrDroppingOriginalCoverage() {
        long token = begin(100);
        assertEquals(new BBox(57, 12, 86, 121), box(100));
        assertTrue(state.refine(token, Collections.singletonList(new PersonBox(
                new BBox(60, 10, 90, 190), .9f)), 120));
        assertEquals(new BBox(60, 10, 90, 190), box(120));
        assertEquals(new BBox(65, 80, 70, 45), raw.box());
        assertEquals(raw.box(), chest.getBox());
    }

    @Test public void replacementAndClearRejectLateResults() {
        long old = begin(100);
        long next = begin(110);
        assertNotEquals(old, next);
        assertFalse(state.refine(old, Collections.emptyList(), 120));
        state.clear();
        assertFalse(state.refine(next, Collections.emptyList(), 130));
        assertEquals(raw.box(), box(130));
    }

    @Test public void expiryUsesCaptureTimeNotCompletionTime() {
        long token = begin(100);
        assertNotEquals(raw.box(), box(849));
        assertEquals(raw.box(), box(850));
        assertFalse(state.refine(token, Collections.emptyList(), 850));
        assertEquals(raw.box(), box(99));
    }

    @Test public void recreatedOverlayCannotAcceptOldControllerToken() {
        long old = begin(100);
        PersonCoveragePresentation replacement = new PersonCoveragePresentation();
        long next = replacement.begin(Collections.singletonList(raw), Collections.singletonList(chest),
                Arrays.asList(head, chest), 200, 300, 100, 100, false,
                0, 0, 200, 300, RenderSourceReference.UNKNOWN);
        assertNotEquals(old, next);
        assertFalse(replacement.refine(old, Collections.emptyList(), 110));
    }

    @Test public void staleAndFutureInputsNeverAcquireCoverage() {
        for (long captured : new long[]{0, 1001}) {
            state.begin(Collections.singletonList(raw), Collections.singletonList(chest),
                    Arrays.asList(head, chest), 200, 300, captured, 1000, false,
                    0, 0, 200, 300, RenderSourceReference.UNKNOWN);
            assertEquals(raw.box(), box(1000));
        }
    }

    @Test public void groupedMemberExpansionPreservesEveryOriginalMask() {
        begin(100);
        RenderTrackSnapshot group = raw.withGroupBox(new BBox(20, 50, 160, 100))
                .withRenderBox(new BBox(20, 50, 160, 100), -1500000000);
        assertEquals(new BBox(20, 12, 160, 138), state.expand(group, group.box(),
                Arrays.asList(7, 8), 100));
    }

    @Test public void projectionPredictionMovesPersonAndPartTogether() {
        begin(100);
        assertEquals(new BBox(67, 32, 86, 121), state.expand(raw,
                new BBox(75, 100, 70, 45), Collections.emptyList(), 120));
    }

    @Test public void cachedAndTextTracksNeverAcquireCoverage() {
        for (RenderTrackSnapshot track : Arrays.asList(
                new RenderTrackSnapshot(7, "text_smut", chest.getBox(), 0, 0, false),
                new RenderTrackSnapshot(7, "breasts", chest.getBox(), 0, 0, true))) {
            state.begin(Collections.singletonList(track), Collections.singletonList(chest),
                    Arrays.asList(head, chest), 200, 300, 100, 100, false,
                    0, 0, 200, 300, RenderSourceReference.UNKNOWN);
            assertEquals(track.box(), state.expand(track, track.box(), Collections.emptyList(), 100));
        }
    }

    @Test public void ambiguousRawAssociationDoesNotBridgePeople() {
        state.begin(Collections.singletonList(raw), Arrays.asList(chest, chest),
                Arrays.asList(head, chest), 200, 300, 100, 100, false,
                0, 0, 200, 300, RenderSourceReference.UNKNOWN);
        assertEquals(raw.box(), box(100));
    }

    @Test public void worldMappingUsesCaptureCameraAndExactSourceReference() {
        RenderSourceReference.Origin origin = new RenderSourceReference.Origin(1, 1, 1, 400, 600, 1);
        RenderSourceReference reference = RenderSourceReference.known(origin, 100, 0, 0);
        RenderTrackSnapshot worldRaw = new RenderTrackSnapshot(7, "breasts",
                new BBox(115, 180, 70, 45), 0, 0, false, reference);
        state.begin(Collections.singletonList(worldRaw), Collections.singletonList(chest),
                Arrays.asList(head, chest), 200, 300, 100, 110, true,
                100, 200, 400, 600, reference);
        assertEquals(new BBox(107, 112, 86, 121), state.expand(worldRaw,
                worldRaw.box(), Collections.emptyList(), 110));
        // Same geometry, different frame must not reuse this expansion.
        RenderSourceReference other = RenderSourceReference.known(origin, 101, 0, 0);
        state.begin(Collections.singletonList(worldRaw), Collections.singletonList(chest),
                Arrays.asList(head, chest), 200, 300, 101, 110, true,
                100, 200, 400, 600, other);
        assertEquals(worldRaw.box(), state.expand(worldRaw, worldRaw.box(), Collections.emptyList(), 110));
    }

    @Test public void worldModeRejectsUnknownOriginAndChangedBias() {
        RenderSourceReference.Origin origin = new RenderSourceReference.Origin(1, 1, 1, 200, 300, 1);
        RenderTrackSnapshot worldRaw = new RenderTrackSnapshot(7, "breasts", chest.getBox(),
                0, 0, false, RenderSourceReference.known(origin, 100, 0, 0));
        for (RenderSourceReference reference : Arrays.asList(RenderSourceReference.UNKNOWN,
                RenderSourceReference.known(origin, 100, 5, 0))) {
            state.begin(Collections.singletonList(worldRaw), Collections.singletonList(chest),
                    Arrays.asList(head, chest), 200, 300, 100, 110, true,
                    0, 0, 200, 300, reference);
            assertEquals(worldRaw.box(), state.expand(worldRaw, worldRaw.box(), Collections.emptyList(), 110));
        }
    }

    @Test public void unanchoredWorldRequiresMatchingCurrentTrackerAssignment() {
        chest.setTrackId(7);
        state.begin(Collections.singletonList(raw), Collections.singletonList(chest),
                Arrays.asList(head, chest), 200, 300, 100, 110, true,
                0, 0, 200, 300, RenderSourceReference.UNKNOWN);
        chest.setTrackId(99); // Caller mutation cannot change the retained proof.
        assertEquals(new BBox(57, 12, 86, 121), box(110));
        state.begin(Collections.singletonList(raw), Collections.singletonList(chest),
                Arrays.asList(head, chest), 200, 300, 100, 110, true,
                0, 0, 200, 300, RenderSourceReference.UNKNOWN);
        assertEquals(raw.box(), box(110));
    }

    @Test public void briefRefinedShapeReuseAvoidsProvisionalPulsingWithoutRenewingProof() {
        long token = begin(100);
        BBox refined = new BBox(60, 10, 90, 190);
        assertTrue(state.refine(token, Collections.singletonList(
                new PersonBox(refined, .9f)), 120));
        state.advanceFrame();
        begin(300);
        assertEquals(refined, box(300));
        assertEquals(550L, state.nextRefreshDelay(300));
        assertFalse(state.refine(token, Collections.emptyList(), 310));
        // Reusing on another frame cannot turn an old model observation into fresh evidence.
        assertEquals(new BBox(57, 12, 86, 121), box(850));
        assertEquals(200L, state.nextRefreshDelay(850));
        state.clear();
        begin(400);
        assertEquals(new BBox(57, 12, 86, 121), box(400));
    }

    @Test public void recordedCadenceDoesNotShrinkBetweenRawPublicationAndNextRefinement() {
        long first = state.begin(Collections.singletonList(raw), Collections.singletonList(chest),
                Collections.emptyList(), 200, 300, 100, 221, false,
                0, 0, 200, 300, RenderSourceReference.UNKNOWN);
        BBox person = new BBox(60, 10, 90, 190);
        assertTrue(state.refine(first, Collections.singletonList(
                new PersonBox(person, .9f)), 307));
        long second = state.begin(Collections.singletonList(raw), Collections.singletonList(chest),
                Collections.emptyList(), 200, 300, 434, 555, false,
                0, 0, 200, 300, RenderSourceReference.UNKNOWN);
        // 334 ms capture cadence, 121 ms raw publication and 207 ms refinement age:
        // a 500 ms reuse expiry introduces a ~41 ms pulse even with every model run admitted.
        assertEquals(person, box(600));
        assertEquals(person, box(640));
        assertTrue(state.refine(second, Collections.singletonList(
                new PersonBox(person, .9f)), 641));
        assertEquals(person, box(641));
    }
}
