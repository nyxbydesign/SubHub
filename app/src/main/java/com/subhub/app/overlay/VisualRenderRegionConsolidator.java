package com.subhub.app.overlay;

import com.subhub.app.detection.BBox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Render-only consolidation of overlapping visual censors.
 *
 * <p>This deliberately runs after tracking and cache selection. It cannot create tracker
 * identity, statistics, or policy observations; it only replaces several visibly fragmented
 * rectangles with one stable union for the renderer. Text is excluded because text regions have
 * their own semantic anchors and line layout.</p>
 */
final class VisualRenderRegionConsolidator {
    // Duplicate cleanup stays stricter than the separate head-guided body pass below.
    private static final float MIN_IOU = 0.40f;
    private static final float MIN_CONTAINMENT = 0.72f;
    private static final float MIN_LIVE_COMPONENT_IOU = 0.38f;
    private static final float MIN_LIVE_COMPONENT_CONTAINMENT = 0.55f;
    private static final float MIN_PHASE_SHIFT_AXIS_ALIGNMENT = 0.82f;
    private static final float MIN_PHASE_SHIFT_CROSS_OVERLAP = 0.34f;
    private static final float MAX_PHASE_SHIFT_CENTER_RATIO = 0.58f;
    private static final float MIN_UNION_FILL = 0.62f;
    private static final float MAX_UNION_TO_LARGEST_MEMBER = 4.50f;
    private static final int MAX_COMPONENT_MEMBERS = 32;
    private static final int MAX_INPUT_REGIONS = 128;

    private VisualRenderRegionConsolidator() {}

    static Result consolidate(List<RenderTrackSnapshot> input) {
        if (input == null || input.isEmpty()) {
            return new Result(Collections.emptyList(), 0, 0);
        }
        List<Cluster> clusters = new ArrayList<>(input.size());
        for (RenderTrackSnapshot candidate : input) {
            if (candidate != null) clusters.add(new Cluster(candidate));
        }
        if (clusters.size() > MAX_INPUT_REGIONS) {
            // Bounded work on a crowded/malformed scene; keep every censor, skip cosmetics.
            List<RenderTrackSnapshot> unchanged = new ArrayList<>(clusters.size());
            for (Cluster cluster : clusters) unchanged.add(cluster.anchor);
            return new Result(Collections.unmodifiableList(unchanged), unchanged.size(), unchanged.size());
        }
        clusters.sort(CLUSTER_ORDER);
        List<RenderTrackSnapshot> ordered = new ArrayList<>(clusters.size());
        for (Cluster cluster : clusters) ordered.add(cluster.anchor);
        BodyOverlapHeuristics bodies = new BodyOverlapHeuristics(ordered);
        int inputCount = clusters.size();
        boolean bodyPass = false;
        while (true) {
            int bestFirst = -1;
            int bestSecond = -1;
            float bestAffinity = 0f;
            for (int first = 0; first < clusters.size(); first++) {
                Cluster firstCluster = clusters.get(first);
                if (firstCluster.text) continue;
                for (int second = first + 1; second < clusters.size(); second++) {
                    Cluster secondCluster = clusters.get(second);
                    float affinity = bodyPass
                            ? bodyAffinity(firstCluster, secondCluster, bodies)
                            : mergeAffinity(firstCluster, secondCluster);
                    if (secondCluster.text || affinity <= 0f
                            || !bodies.oneBody(firstCluster.sources, secondCluster.sources)
                            || !canMerge(firstCluster, secondCluster, bodyPass)) continue;
                    if (affinity > bestAffinity) {
                        bestAffinity = affinity;
                        bestFirst = first;
                        bestSecond = second;
                    }
                }
            }
            if (bestFirst < 0) {
                if (bodyPass) break;
                bodyPass = true;
                continue;
            }
            Cluster merged = Cluster.merge(clusters.get(bestFirst), clusters.get(bestSecond));
            clusters.remove(bestSecond);
            clusters.set(bestFirst, merged);
            clusters.sort(CLUSTER_ORDER);
        }
        List<RenderTrackSnapshot> output = new ArrayList<>(clusters.size());
        List<List<RenderTrackSnapshot>> sources = new ArrayList<>(clusters.size());
        for (Cluster cluster : clusters) {
            output.add(cluster.snapshot());
            sources.add(cluster.sources);
        }
        return new Result(Collections.unmodifiableList(output), inputCount, output.size(),
                Collections.unmodifiableList(sources));
    }

    private static boolean isText(RenderTrackSnapshot track) {
        return track != null && ("text_smut".equals(track.category())
                || track.category() != null && track.category().startsWith("text_"));
    }

    private static boolean canMerge(Cluster first, Cluster second, boolean bodyPass) {
        if (!first.anchor.reference().sameBasis(second.anchor.reference())) return false;
        if (first.members + second.members > MAX_COMPONENT_MEMBERS) return false;
        if (!bodyPass && !liveMembersRemainOneComponent(first, second)) return false;
        BBox merged = union(first.box, second.box);
        int largestWidth = 0, largestHeight = 0;
        for (List<RenderTrackSnapshot> group : List.of(first.sources, second.sources)) {
            for (RenderTrackSnapshot source : group) {
                largestWidth = Math.max(largestWidth, source.box().getWidth());
                largestHeight = Math.max(largestHeight, source.box().getHeight());
            }
        }
        // A connected chest/abdomen/pelvis chain can span three part heights.
        // Head separation and pairwise overlap still gate every body merge.
        float maximumSpan = bodyPass ? 3f : 1.9f;
        if (merged.getWidth() > largestWidth * maximumSpan
                || merged.getHeight() > largestHeight * maximumSpan) return false;
        long unionArea = merged.getArea();
        long summedArea = first.summedMemberArea + second.summedMemberArea;
        long largestArea = Math.max(first.largestMemberArea, second.largestMemberArea);
        float fill = unionArea <= 0L ? 0f : Math.min(1f, summedArea / (float) unionArea);
        float expansion = largestArea <= 0L ? Float.MAX_VALUE
                : unionArea / (float) largestArea;
        return fill >= MIN_UNION_FILL
                && expansion <= MAX_UNION_TO_LARGEST_MEMBER;
    }

    private static float bodyAffinity(Cluster first, Cluster second, BodyOverlapHeuristics bodies) {
        float affinity = 0f;
        for (RenderTrackSnapshot a : first.sources) {
            for (RenderTrackSnapshot b : second.sources) {
                affinity = Math.max(affinity, bodies.bodyAffinity(a, b));
            }
        }
        return affinity;
    }

    private static boolean preferredAnchor(
            RenderTrackSnapshot candidate,
            RenderTrackSnapshot current) {
        if (candidate.isCached() != current.isCached()) return !candidate.isCached();
        boolean candidateTracked = candidate.id() >= 0;
        boolean currentTracked = current.id() >= 0;
        if (candidateTracked != currentTracked) return candidateTracked;
        return candidateTracked && candidate.id() < current.id();
    }

    private static float mergeAffinity(Cluster firstCluster, Cluster secondCluster) {
        float strongest = 0f;
        for (RenderTrackSnapshot first : firstCluster.sources) {
            for (RenderTrackSnapshot second : secondCluster.sources) {
                strongest = Math.max(strongest, ordinaryAffinity(first.box(), second.box()));
                if (phaseShiftEligible(firstCluster, secondCluster, first, second)) {
                    strongest = Math.max(strongest,
                            verticalPhaseShiftAffinity(first.box(), second.box()));
                }
            }
        }
        return strongest;
    }

    private static float ordinaryAffinity(BBox first, BBox second) {
        int left = Math.max(first.getX(), second.getX());
        int top = Math.max(first.getY(), second.getY());
        int right = Math.min(first.getRight(), second.getRight());
        int bottom = Math.min(first.getBottom(), second.getBottom());
        if (right <= left || bottom <= top) return 0f;
        long intersection = (long) (right - left) * (bottom - top);
        long smaller = Math.min(first.getArea(), second.getArea());
        float containment = smaller <= 0L ? 0f : intersection / (float) smaller;
        float iou = first.intersectionOverUnion(second);
        return iou >= MIN_IOU || containment >= MIN_CONTAINMENT
                ? Math.max(iou, containment) : 0f;
    }

    private static boolean phaseShiftEligible(
            Cluster firstCluster,
            Cluster secondCluster,
            RenderTrackSnapshot first,
            RenderTrackSnapshot second) {
        if (first.isCached() == second.isCached()) return false;
        // A phase-shift copy may extend one live censor, but cached evidence must never gain
        // permission to bridge two separate live identities transitively.
        if (firstCluster.liveIds.size() + secondCluster.liveIds.size() > 1) return false;
        return firstCluster.allCached || secondCluster.allCached;
    }

    private static float verticalPhaseShiftAffinity(BBox first, BBox second) {
        int left = Math.max(first.getX(), second.getX());
        int top = Math.max(first.getY(), second.getY());
        int right = Math.min(first.getRight(), second.getRight());
        int bottom = Math.min(first.getBottom(), second.getBottom());
        if (right <= left || bottom <= top) return 0f;
        float horizontalOverlap = (right - left)
                / (float) Math.max(1, Math.min(first.getWidth(), second.getWidth()));
        float verticalOverlap = (bottom - top)
                / (float) Math.max(1, Math.min(first.getHeight(), second.getHeight()));
        float centerRatio = Math.abs(first.getCenterY() - second.getCenterY())
                / (float) Math.max(1, Math.min(first.getHeight(), second.getHeight()));
        if (horizontalOverlap < MIN_PHASE_SHIFT_AXIS_ALIGNMENT
                || verticalOverlap < MIN_PHASE_SHIFT_CROSS_OVERLAP
                || centerRatio > MAX_PHASE_SHIFT_CENTER_RATIO) return 0f;
        return Math.min(horizontalOverlap, verticalOverlap);
    }

    private static boolean liveMembersRemainOneComponent(Cluster first, Cluster second) {
        List<RenderTrackSnapshot> live = new ArrayList<>();
        for (RenderTrackSnapshot source : first.sources) {
            if (!source.isCached()) live.add(source);
        }
        for (RenderTrackSnapshot source : second.sources) {
            if (!source.isCached()) live.add(source);
        }
        if (live.size() <= 1) return true;
        RenderTrackSnapshot anchor = live.get(0);
        for (RenderTrackSnapshot candidate : live) {
            if (preferredAnchor(candidate, anchor)) anchor = candidate;
        }
        for (RenderTrackSnapshot candidate : live) {
            if (candidate == anchor) continue;
            BBox anchorBox = anchor.box();
            BBox candidateBox = candidate.box();
            int left = Math.max(anchorBox.getX(), candidateBox.getX());
            int top = Math.max(anchorBox.getY(), candidateBox.getY());
            int right = Math.min(anchorBox.getRight(), candidateBox.getRight());
            int bottom = Math.min(anchorBox.getBottom(), candidateBox.getBottom());
            if (right <= left || bottom <= top) return false;
            long intersection = (long) (right - left) * (bottom - top);
            long smaller = Math.min(anchorBox.getArea(), candidateBox.getArea());
            float containment = smaller <= 0L ? 0f : intersection / (float) smaller;
            float iou = anchorBox.intersectionOverUnion(candidateBox);
            if (iou < MIN_LIVE_COMPONENT_IOU
                    && containment < MIN_LIVE_COMPONENT_CONTAINMENT) return false;
        }
        return true;
    }

    private static BBox union(BBox first, BBox second) {
        int left = Math.min(first.getX(), second.getX());
        int top = Math.min(first.getY(), second.getY());
        int right = Math.max(first.getRight(), second.getRight());
        int bottom = Math.max(first.getBottom(), second.getBottom());
        return new BBox(left, top, Math.max(0, right - left), Math.max(0, bottom - top));
    }

    private static final Comparator<Cluster> CLUSTER_ORDER = Comparator
            .comparingInt((Cluster value) -> value.box.getY())
            .thenComparingInt(value -> value.box.getX())
            .thenComparingInt(value -> value.box.getWidth())
            .thenComparingInt(value -> value.box.getHeight())
            .thenComparingInt(value -> value.anchor.id());

    private static final class Cluster {
        private final RenderTrackSnapshot anchor;
        private final BBox box;
        private final boolean allCached;
        private final boolean text;
        private final int members;
        private final long summedMemberArea;
        private final long largestMemberArea;
        private final List<RenderTrackSnapshot> sources;
        private final List<Integer> liveIds;

        private Cluster(RenderTrackSnapshot source) {
            anchor = source;
            box = source.box();
            allCached = source.isCached();
            text = isText(source);
            members = 1;
            summedMemberArea = box.getArea();
            largestMemberArea = box.getArea();
            sources = List.of(source);
            liveIds = source.isCached() ? Collections.emptyList() : List.of(source.id());
        }

        private Cluster(
                RenderTrackSnapshot anchor,
                BBox box,
                boolean allCached,
                int members,
                long summedMemberArea,
                long largestMemberArea,
                List<RenderTrackSnapshot> sources,
                List<Integer> liveIds) {
            this.anchor = anchor;
            this.box = box;
            this.allCached = allCached;
            text = false;
            this.members = members;
            this.summedMemberArea = summedMemberArea;
            this.largestMemberArea = largestMemberArea;
            this.sources = sources;
            this.liveIds = liveIds;
        }

        private static Cluster merge(Cluster first, Cluster second) {
            RenderTrackSnapshot anchor = preferredAnchor(first.anchor, second.anchor)
                    ? first.anchor : second.anchor;
            List<RenderTrackSnapshot> sources = new ArrayList<>(
                    first.sources.size() + second.sources.size());
            sources.addAll(first.sources);
            sources.addAll(second.sources);
            List<Integer> liveIds = new ArrayList<>(
                    first.liveIds.size() + second.liveIds.size());
            liveIds.addAll(first.liveIds);
            for (Integer id : second.liveIds) {
                if (!liveIds.contains(id)) liveIds.add(id);
            }
            return new Cluster(
                    anchor,
                    union(first.box, second.box),
                    first.allCached && second.allCached,
                    first.members + second.members,
                    first.summedMemberArea + second.summedMemberArea,
                    Math.max(first.largestMemberArea, second.largestMemberArea),
                    Collections.unmodifiableList(sources),
                    Collections.unmodifiableList(liveIds));
        }

        private RenderTrackSnapshot snapshot() {
            if (members == 1 && allCached == anchor.isCached() && box.equals(anchor.box())) {
                return anchor;
            }
            return anchor.withGroupBox(box);
        }
    }

    static final class Result {
        private final List<RenderTrackSnapshot> regions;
        private final int inputCount;
        private final int outputCount;
        private final List<List<RenderTrackSnapshot>> sources;

        private Result(List<RenderTrackSnapshot> regions, int inputCount, int outputCount) {
            this(regions, inputCount, outputCount, singletons(regions));
        }

        private Result(List<RenderTrackSnapshot> regions, int inputCount, int outputCount,
                List<List<RenderTrackSnapshot>> sources) {
            this.regions = regions;
            this.inputCount = inputCount;
            this.outputCount = outputCount;
            this.sources = sources;
        }

        private static List<List<RenderTrackSnapshot>> singletons(List<RenderTrackSnapshot> regions) {
            List<List<RenderTrackSnapshot>> result = new ArrayList<>();
            for (RenderTrackSnapshot region : regions) result.add(List.of(region));
            return Collections.unmodifiableList(result);
        }

        List<RenderTrackSnapshot> regions() { return regions; }
        int inputCount() { return inputCount; }
        int outputCount() { return outputCount; }
        int consolidatedCount() { return Math.max(0, inputCount - outputCount); }
        List<List<RenderTrackSnapshot>> sources() { return sources; }
    }
}
