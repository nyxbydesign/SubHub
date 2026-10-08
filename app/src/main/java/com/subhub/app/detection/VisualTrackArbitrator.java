package com.subhub.app.detection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Removes only near-identical visual identities before renderer publication. */
public final class VisualTrackArbitrator {
    private static final float MIN_IOU = 0.72f;
    private static final float MIN_SIZE_RATIO = 0.80f;
    private static final float MAX_CENTER_DISTANCE_RATIO = 0.12f;
    private static final float MOTION_MIN_IOU = 0.20f;
    private static final float MOTION_MIN_SIZE_RATIO = 0.68f;
    private static final int MIN_REACQUIRED_OBSERVATIONS = 2;

    private VisualTrackArbitrator() {}

    public static Result arbitrate(List<TrackedObject> tracks) {
        if (tracks == null || tracks.isEmpty()) return new Result(List.of(), 0);
        List<TrackedObject> candidates = new ArrayList<>(tracks.size());
        for (TrackedObject track : tracks) {
            if (track != null && !"text_smut".equals(track.getCategory())) {
                candidates.add(track);
            }
        }
        candidates.sort(Comparator
                .comparing(TrackedObject::isQualityOnly)
                .thenComparing(TrackedObject::isConfirmed, Comparator.reverseOrder())
                .thenComparingInt(TrackedObject::getFramesMissing)
                .thenComparing(TrackedObject::getFramesTracked, Comparator.reverseOrder())
                .thenComparing(TrackedObject::getConfidence, Comparator.reverseOrder())
                .thenComparingInt(TrackedObject::getId));

        List<TrackedObject> selected = new ArrayList<>(candidates.size());
        int suppressed = 0;
        int handedOff = 0;
        for (TrackedObject candidate : candidates) {
            boolean duplicate = false;
            for (int index = 0; index < selected.size(); index++) {
                TrackedObject existing = selected.get(index);
                boolean crossSourceDuplicate = candidate.isQualityOnly() != existing.isQualityOnly()
                        && VisualIdentityReconciler.likelySameIdentity(candidate, existing);
                if (crossSourceDuplicate || nearDuplicate(candidate.getBox(), existing.getBox())) {
                    duplicate = true;
                    break;
                }
                if (motionReacquisitionDuplicate(candidate, existing)) {
                    duplicate = true;
                    TrackedObject stale = candidate.getFramesMissing() > 0 ? candidate : existing;
                    TrackedObject fresh = stale == candidate ? existing : candidate;
                    if (stale == existing
                            && fresh.getFramesTracked() >= MIN_REACQUIRED_OBSERVATIONS) {
                        // Keep the old renderer ID so its on-screen trajectory does not disappear
                        // and respawn, but steer it toward the fresh observation's geometry.
                        selected.set(index, existing.renderSnapshotWithGeometryFrom(fresh));
                        handedOff++;
                    }
                    break;
                }
            }
            if (duplicate) {
                suppressed++;
            } else {
                selected.add(candidate.snapshot());
            }
        }
        return new Result(selected, suppressed, handedOff);
    }

    static boolean nearDuplicate(BBox first, BBox second) {
        if (first == null || second == null || first.getArea() <= 0 || second.getArea() <= 0) {
            return false;
        }
        float widthRatio = ratio(first.getWidth(), second.getWidth());
        float heightRatio = ratio(first.getHeight(), second.getHeight());
        if (widthRatio < MIN_SIZE_RATIO || heightRatio < MIN_SIZE_RATIO) return false;
        int minimumDimension = Math.max(1, Math.min(
                Math.min(first.getWidth(), first.getHeight()),
                Math.min(second.getWidth(), second.getHeight())));
        int centerLimit = Math.max(6,
                Math.round(minimumDimension * MAX_CENTER_DISTANCE_RATIO));
        if (Math.abs(first.getCenterX() - second.getCenterX()) > centerLimit
                || Math.abs(first.getCenterY() - second.getCenterY()) > centerLimit) return false;
        return first.intersectionOverUnion(second) >= MIN_IOU;
    }

    private static boolean motionReacquisitionDuplicate(
            TrackedObject first,
            TrackedObject second) {
        if (first == null || second == null
                || first.getFramesMissing() == second.getFramesMissing()
                || first.getFramesMissing() > 0 && second.getFramesMissing() > 0
                || !sameCensorCategory(first.getCategory(), second.getCategory())) return false;
        BBox firstBox = first.getBox();
        BBox secondBox = second.getBox();
        if (firstBox == null || secondBox == null
                || ratio(firstBox.getWidth(), secondBox.getWidth()) < MOTION_MIN_SIZE_RATIO
                || ratio(firstBox.getHeight(), secondBox.getHeight()) < MOTION_MIN_SIZE_RATIO) {
            return false;
        }
        return firstBox.intersectionOverUnion(secondBox) >= MOTION_MIN_IOU;
    }

    private static boolean sameCensorCategory(String first, String second) {
        // Partial overlap plus one missed observation does not establish cross-category identity.
        // Keep independent coverage until stronger correspondence evidence is available.
        return first != null && first.equals(second) && !first.startsWith("text_");
    }

    private static float ratio(int first, int second) {
        int maximum = Math.max(first, second);
        return maximum <= 0 ? 0f : Math.min(first, second) / (float) maximum;
    }

    public static final class Result {
        private final List<TrackedObject> tracks;
        private final int suppressed;
        private final int handedOff;

        Result(List<TrackedObject> tracks, int suppressed) {
            this(tracks, suppressed, 0);
        }

        Result(List<TrackedObject> tracks, int suppressed, int handedOff) {
            this.tracks = tracks;
            this.suppressed = suppressed;
            this.handedOff = handedOff;
        }

        public List<TrackedObject> tracks() { return tracks; }
        public int suppressed() { return suppressed; }
        public int handedOff() { return handedOff; }
    }
}
