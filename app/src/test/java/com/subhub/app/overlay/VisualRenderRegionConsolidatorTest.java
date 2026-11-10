package com.subhub.app.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.subhub.app.detection.BBox;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class VisualRenderRegionConsolidatorTest {
    @Test public void offsetDuplicateVisualBoxesBecomeOneStableUnion() {
        RenderTrackSnapshot first = snapshot(7, "face_female", 20, 20, 60, 80, false);
        RenderTrackSnapshot second = snapshot(12, "EXPOSED", 20, 30, 60, 80, false);

        VisualRenderRegionConsolidator.Result result =
                VisualRenderRegionConsolidator.consolidate(List.of(first, second));

        assertEquals(1, result.outputCount());
        assertEquals(1, result.consolidatedCount());
        assertEquals(7, result.regions().get(0).id());
        assertEquals(new BBox(20, 20, 60, 90), result.regions().get(0).box());
        assertFalse(result.regions().get(0).isCached());
    }

    @Test public void merelyModerateOverlapRemainsSeparate() {
        RenderTrackSnapshot first = snapshot(7, "face_female", 20, 20, 60, 80, false);
        RenderTrackSnapshot second = snapshot(12, "EXPOSED", 20, 75, 60, 80, false);

        assertEquals(2, VisualRenderRegionConsolidator
                .consolidate(List.of(first, second)).outputCount());
    }

    @Test public void halfHeightScrollPhaseDuplicateBecomesOneCensor() {
        RenderTrackSnapshot first = snapshot(7, "face_female", 20, 20, 60, 80, false);
        RenderTrackSnapshot second = snapshot(-12, "EXPOSED", 20, 60, 60, 80, true);

        VisualRenderRegionConsolidator.Result result =
                VisualRenderRegionConsolidator.consolidate(List.of(first, second));

        assertEquals(1, result.outputCount());
        assertEquals(new BBox(20, 20, 60, 120), result.regions().get(0).box());
        assertEquals(7, result.regions().get(0).id());
    }

    @Test public void ambiguousCachedSideShiftAndIndependentLiveBoxesRemainSeparate() {
        RenderTrackSnapshot live = snapshot(7, "face_female", 20, 20, 100, 100, false);
        RenderTrackSnapshot shiftedCache = snapshot(-12, "EXPOSED", 85, 20, 100, 100, true);
        RenderTrackSnapshot shiftedLive = snapshot(12, "EXPOSED", 85, 20, 100, 100, false);

        VisualRenderRegionConsolidator.Result cachedResult =
                VisualRenderRegionConsolidator.consolidate(List.of(live, shiftedCache));
        VisualRenderRegionConsolidator.Result liveResult =
                VisualRenderRegionConsolidator.consolidate(List.of(live, shiftedLive));

        assertEquals(2, cachedResult.outputCount());
        assertEquals(2, liveResult.outputCount());
    }

    @Test public void merelyAdjacentGridCellsRemainSeparate() {
        RenderTrackSnapshot first = snapshot(1, "face_female", 0, 0, 50, 50, false);
        RenderTrackSnapshot second = snapshot(2, "face_female", 50, 0, 50, 50, false);

        VisualRenderRegionConsolidator.Result result =
                VisualRenderRegionConsolidator.consolidate(List.of(first, second));

        assertEquals(2, result.outputCount());
    }

    @Test public void liveAnchorWinsWhenCachedCoverageExtendsIt() {
        RenderTrackSnapshot cached = snapshot(-20, "face_female", 20, 20, 60, 60, true);
        RenderTrackSnapshot live = snapshot(9, "EXPOSED", 30, 30, 60, 60, false);

        RenderTrackSnapshot merged = VisualRenderRegionConsolidator
                .consolidate(List.of(cached, live)).regions().get(0);

        assertEquals(9, merged.id());
        assertFalse(merged.isCached());
        assertEquals(new BBox(20, 20, 70, 70), merged.box());
    }

    @Test public void transitiveOverlapsFromRepeatedPassesCollapseIntoOneRegion() {
        RenderTrackSnapshot left = snapshot(1, "face_female", 0, 0, 50, 50, false);
        RenderTrackSnapshot middle = snapshot(2, "EXPOSED", 10, 0, 50, 50, false);
        RenderTrackSnapshot right = snapshot(3, "face_female", 20, 0, 50, 50, false);

        VisualRenderRegionConsolidator.Result result =
                VisualRenderRegionConsolidator.consolidate(List.of(left, middle, right));

        assertEquals(1, result.outputCount());
        assertEquals(new BBox(0, 0, 70, 50), result.regions().get(0).box());
    }

    @Test public void transitiveConsolidationIsIndependentOfDetectorOrder() {
        RenderTrackSnapshot left = snapshot(1, "face_female", 0, 0, 50, 50, false);
        RenderTrackSnapshot middle = snapshot(2, "EXPOSED", 10, 0, 50, 50, false);
        RenderTrackSnapshot right = snapshot(3, "face_female", 20, 0, 50, 50, false);
        BBox expected = new BBox(0, 0, 70, 50);

        for (List<RenderTrackSnapshot> order : permutations(List.of(left, middle, right))) {
            VisualRenderRegionConsolidator.Result result =
                    VisualRenderRegionConsolidator.consolidate(order);
            assertEquals(1, result.outputCount());
            assertEquals(expected, result.regions().get(0).box());
        }
    }

    @Test public void textIsNeverGeometryConsolidated() {
        RenderTrackSnapshot first = snapshot(-1, "text_smut", 0, 0, 80, 20, false);
        RenderTrackSnapshot second = snapshot(-2, "text_smut", 10, 0, 80, 20, false);

        assertEquals(2, VisualRenderRegionConsolidator
                .consolidate(List.of(first, second)).outputCount());
    }

    @Test public void repeatedRoundTripFragmentsAcrossOneImageBecomeOneCensor() {
        List<RenderTrackSnapshot> fragments = List.of(
                snapshot(1, "face_female", 0, 0, 80, 80, false),
                snapshot(-2, "EXPOSED", 8, 0, 80, 80, true),
                snapshot(-3, "face_female", 16, 0, 80, 80, true),
                snapshot(-4, "EXPOSED", 0, 8, 80, 80, true),
                snapshot(-5, "face_female", 8, 8, 80, 80, true),
                snapshot(-6, "EXPOSED", 16, 8, 80, 80, true));

        VisualRenderRegionConsolidator.Result result =
                VisualRenderRegionConsolidator.consolidate(fragments);

        assertEquals(1, result.outputCount());
        assertEquals(new BBox(0, 0, 96, 88), result.regions().get(0).box());
        assertFalse(result.regions().get(0).isCached());
    }

    @Test public void cachedBridgeCannotJoinTwoDisjointLiveIds() {
        RenderTrackSnapshot left = snapshot(1, "face_female", 0, 0, 100, 100, false);
        RenderTrackSnapshot bridge = snapshot(-2, "EXPOSED", 0, 50, 100, 100, true);
        RenderTrackSnapshot right = snapshot(3, "face_female", 0, 100, 100, 100, false);

        VisualRenderRegionConsolidator.Result result =
                VisualRenderRegionConsolidator.consolidate(List.of(left, bridge, right));

        assertEquals(2, result.outputCount());
    }

    @Test public void clusterUnionCannotManufactureAffinityWithThirdLiveBox() {
        RenderTrackSnapshot first = snapshot(1, "face_female", 0, 0, 100, 100, false);
        RenderTrackSnapshot cached = snapshot(-2, "EXPOSED", 0, 50, 100, 100, true);
        RenderTrackSnapshot third = snapshot(3, "face_female", 65, 30, 100, 100, false);

        VisualRenderRegionConsolidator.Result result =
                VisualRenderRegionConsolidator.consolidate(List.of(first, cached, third));

        assertEquals(2, result.outputCount());
    }

    @Test public void expandedAdjacentGridCellsRemainSeparate() {
        RenderTrackSnapshot cached = snapshot(-1, "face_female", 0, 0, 120, 100, true);
        RenderTrackSnapshot live = snapshot(2, "face_female", 78, 0, 120, 100, false);

        assertEquals(2, VisualRenderRegionConsolidator
                .consolidate(List.of(cached, live)).outputCount());
    }

    @Test public void cachedFragmentsDoNotChangeTheLiveAnchor() {
        RenderTrackSnapshot live = snapshot(9, "face_female", 20, 20, 80, 80, false);
        RenderTrackSnapshot first = snapshot(-2, "EXPOSED", 20, 52, 80, 80, true);
        RenderTrackSnapshot second = snapshot(-3, "EXPOSED", 20, 28, 80, 80, true);

        for (List<RenderTrackSnapshot> order : permutations(List.of(live, first, second))) {
            VisualRenderRegionConsolidator.Result result =
                    VisualRenderRegionConsolidator.consolidate(order);
            assertEquals(1, result.outputCount());
            assertEquals(9, result.regions().get(0).id());
        }
    }

    @Test public void overlappingTorsoPartsWithOneHeadBecomeOneBodyCensor() {
        List<RenderTrackSnapshot> source = List.of(
                snapshot(1, "face", 110, 0, 80, 80, false),
                snapshot(2, "breasts", 80, 100, 140, 120, false),
                snapshot(3, "belly", 90, 185, 120, 130, false));
        VisualRenderRegionConsolidator.Result result =
                VisualRenderRegionConsolidator.consolidate(source);
        assertEquals(2, result.outputCount());
        assertTrue(result.regions().stream().anyMatch(
                item -> item.box().equals(new BBox(80, 100, 140, 215))));
        assertCoveragePreserved(source, result.regions());
    }

    @Test public void headlessAlignedBodyOverlapStillMerges() {
        List<RenderTrackSnapshot> source = List.of(
                snapshot(2, "breasts", 0, 0, 100, 100, false),
                snapshot(3, "belly", 0, 60, 100, 100, false));
        VisualRenderRegionConsolidator.Result result =
                VisualRenderRegionConsolidator.consolidate(source);
        assertEquals(1, result.outputCount());
        assertEquals(new BBox(0, 0, 100, 160), result.regions().get(0).box());
        assertCoveragePreserved(source, result.regions());
    }

    @Test public void distinctHeadsKeepOverlappingBodiesSeparate() {
        List<RenderTrackSnapshot> source = List.of(
                snapshot(1, "face", 0, 0, 70, 70, false),
                snapshot(2, "face", 100, 0, 70, 70, false),
                snapshot(3, "breasts", 0, 80, 150, 140, false),
                snapshot(4, "breasts", 60, 80, 150, 140, false));
        // The body overlap alone passes the old merge gate. Head association must reject it.
        assertEquals(4, VisualRenderRegionConsolidator.consolidate(source).outputCount());
    }

    @Test public void duplicateFastQualityHeadsDoNotSplitOneBody() {
        List<RenderTrackSnapshot> source = List.of(
                snapshot(1, "face", 110, 0, 80, 80, false),
                snapshot(-2, "face_female", 115, 5, 80, 80, true),
                snapshot(3, "breasts", 80, 100, 140, 120, false),
                snapshot(4, "belly", 90, 185, 120, 130, false));
        assertEquals(2, VisualRenderRegionConsolidator.consolidate(source).outputCount());
    }

    @Test public void threePeopleCannotBecomeOneThroughOverlappingTorsoChain() {
        List<RenderTrackSnapshot> source = List.of(
                snapshot(1, "face", 0, 0, 70, 70, false),
                snapshot(2, "face", 100, 0, 70, 70, false),
                snapshot(3, "face", 200, 0, 70, 70, false),
                snapshot(4, "breasts", -10, 80, 150, 140, false),
                snapshot(5, "belly", 70, 80, 150, 140, false),
                snapshot(6, "breasts", 170, 80, 150, 140, false));
        assertEquals(6, VisualRenderRegionConsolidator.consolidate(source).outputCount());
    }

    @Test public void sameBodyGroupingIsOrderIndependentAndDoesNotMutateInputs() {
        List<RenderTrackSnapshot> source = List.of(
                snapshot(1, "face", 110, 0, 80, 80, false),
                snapshot(2, "breasts", 80, 100, 140, 120, false),
                snapshot(3, "belly", 90, 185, 120, 130, false));
        for (List<RenderTrackSnapshot> order : permutations(source)) {
            VisualRenderRegionConsolidator.Result result =
                    VisualRenderRegionConsolidator.consolidate(order);
            assertEquals(2, result.outputCount());
            assertEquals(2, result.regions().get(1).id());
            assertEquals(new BBox(80, 100, 140, 215), result.regions().get(1).box());
            assertCoveragePreserved(source, result.regions());
        }
        assertEquals(new BBox(80, 100, 140, 120), source.get(1).box());
    }

    @Test public void tinyOrNonOverlappingBodyPartsStaySeparate() {
        assertEquals(2, VisualRenderRegionConsolidator.consolidate(List.of(
                snapshot(1, "breasts", 0, 0, 30, 30, false),
                snapshot(2, "belly", 0, 18, 30, 30, false))).outputCount());
        assertEquals(2, VisualRenderRegionConsolidator.consolidate(List.of(
                snapshot(1, "breasts", 0, 0, 100, 100, false),
                snapshot(2, "belly", 0, 101, 100, 100, false))).outputCount());
    }

    @Test public void bodyMergeNeverCrossesCoordinateBasis() {
        com.subhub.app.detection.RenderSourceReference.Origin origin =
                new com.subhub.app.detection.RenderSourceReference.Origin(1, 1, 1, 1000, 2000, 1);
        RenderTrackSnapshot first = new RenderTrackSnapshot(1, "breasts",
                new BBox(0, 0, 100, 100), 0, 0, false,
                com.subhub.app.detection.RenderSourceReference.known(origin, 10, 0, 0));
        RenderTrackSnapshot second = new RenderTrackSnapshot(2, "belly",
                new BBox(0, 60, 100, 100), 0, 0, false,
                com.subhub.app.detection.RenderSourceReference.known(origin, 20, 0, 10));
        assertEquals(2, VisualRenderRegionConsolidator.consolidate(
                List.of(first, second)).outputCount());
    }

    @Test public void steeringCannotTemporarilyShrinkNewGroupBackToOneMember() {
        RenderTrackSnapshot first = snapshot(1, "breasts", 0, 0, 100, 100, false);
        RenderTrackSnapshot second = snapshot(2, "belly", 0, 60, 100, 100, false);
        RenderTrackSnapshot merged = VisualRenderRegionConsolidator.consolidate(
                List.of(first, second)).regions().get(0);
        assertEquals(new BBox(0, 0, 100, 160), merged.preserveGroupCoverage(first.box()));
        // Ordinary single-track steering is unchanged.
        assertEquals(second.box(), first.preserveGroupCoverage(second.box()));
    }

    @Test public void crowdedSceneSkipsCosmeticsWithoutDroppingCoverage() {
        List<RenderTrackSnapshot> source = new ArrayList<>();
        for (int index = 0; index < 129; index++) {
            source.add(snapshot(index, "breasts", index, 0, 100, 100, false));
        }
        VisualRenderRegionConsolidator.Result result = VisualRenderRegionConsolidator.consolidate(source);
        assertEquals(source, result.regions());
        assertEquals(0, result.consolidatedCount());
    }

    private static void assertCoveragePreserved(List<RenderTrackSnapshot> input,
            List<RenderTrackSnapshot> output) {
        for (RenderTrackSnapshot source : input) {
            BBox box = source.box();
            assertTrue(output.stream().anyMatch(item -> item.box().getX() <= box.getX()
                    && item.box().getY() <= box.getY()
                    && item.box().getRight() >= box.getRight()
                    && item.box().getBottom() >= box.getBottom()));
        }
    }

    private static RenderTrackSnapshot snapshot(
            int id, String category, int x, int y, int width, int height, boolean cached) {
        return new RenderTrackSnapshot(
                id, category, new BBox(x, y, width, height), 0f, 0f, cached);
    }

    private static List<List<RenderTrackSnapshot>> permutations(
            List<RenderTrackSnapshot> source) {
        List<List<RenderTrackSnapshot>> result = new ArrayList<>();
        permute(new ArrayList<>(source), 0, result);
        return result;
    }

    private static void permute(
            List<RenderTrackSnapshot> values,
            int start,
            List<List<RenderTrackSnapshot>> result) {
        if (start == values.size()) {
            result.add(List.copyOf(values));
            return;
        }
        for (int index = start; index < values.size(); index++) {
            RenderTrackSnapshot swap = values.get(start);
            values.set(start, values.get(index));
            values.set(index, swap);
            permute(values, start + 1, result);
            swap = values.get(start);
            values.set(start, values.get(index));
            values.set(index, swap);
        }
    }
}
