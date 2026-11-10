package com.subhub.app.detection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Carries visual identity across the realtime and settled detector passes.
 *
 * <p>The two models observe the same capture at different resolutions and can disagree enough on
 * geometry that an IoU-only merge creates a second track. This reconciler first associates both
 * sources with established tracker identities, then performs a one-to-one source merge. A settled
 * observation linked to an existing identity remains coverage evidence; it cannot become a second
 * rendered censor merely because its rectangle is coarser.</p>
 */
public final class VisualIdentityReconciler {
    private static final float MIN_IOU = 0.20f;
    private static final float MIN_CONTAINMENT = 0.55f;
    private static final float MIN_AREA_RATIO = 0.35f;
    // A one-frame-old quality observation can trail fast scroll motion by roughly half a box.
    // Keep overlapping same-family observations on one identity without merging merely adjacent
    // grid items: the IoU/containment gate below still requires meaningful shared area.
    private static final float MAX_CENTER_DELTA_RATIO = 0.65f;

    private VisualIdentityReconciler() {}

    public static Result reconcile(
            List<Detection> realtime,
            List<Detection> refinement,
            List<TrackedObject> activeTracks) {
        List<Detection> live = nonNull(realtime);
        List<Detection> quality = nonNull(refinement);
        Map<Integer, TrackedObject> tracksById = new HashMap<>();
        if (activeTracks != null) {
            for (TrackedObject track : activeTracks) {
                if (track != null && track.isActive()) tracksById.put(track.getId(), track);
            }
        }

        int realtimeLinked = associateWithTracks(live, activeTracks, tracksById);
        int qualityLinked = associateWithTracks(quality, activeTracks, tracksById);

        List<SourceMatch> matches = new ArrayList<>();
        for (int liveIndex = 0; liveIndex < live.size(); liveIndex++) {
            Detection liveDetection = live.get(liveIndex);
            for (int qualityIndex = 0; qualityIndex < quality.size(); qualityIndex++) {
                Detection qualityDetection = quality.get(qualityIndex);
                boolean sharedIdentity = liveDetection.getTrackId() >= 0
                        && liveDetection.getTrackId() == qualityDetection.getTrackId();
                float score = sharedIdentity ? 100f : identityScore(liveDetection, qualityDetection);
                if (sharedIdentity || score > 0f) {
                    matches.add(new SourceMatch(liveIndex, qualityIndex, score));
                }
            }
        }
        matches.sort(Comparator.comparing(SourceMatch::score).reversed());
        Set<Integer> matchedLive = new HashSet<>();
        Set<Integer> matchedQuality = new HashSet<>();
        for (SourceMatch match : matches) {
            if (matchedLive.contains(match.liveIndex)
                    || matchedQuality.contains(match.qualityIndex)) continue;
            matchedLive.add(match.liveIndex);
            matchedQuality.add(match.qualityIndex);
            Detection authoritative = live.get(match.liveIndex);
            Detection supporting = quality.get(match.qualityIndex);
            live.set(match.liveIndex, metadataWithAuthoritativeBox(authoritative, supporting));
        }

        List<Detection> merged = new ArrayList<>(live.size() + quality.size());
        merged.addAll(live);
        int carriedQuality = 0;
        int unlinkedQuality = 0;
        for (int index = 0; index < quality.size(); index++) {
            if (matchedQuality.contains(index)) continue;
            Detection detection = quality.get(index);
            merged.add(detection);
            if (detection.getTrackId() >= 0) carriedQuality++;
            else unlinkedQuality++;
        }
        return new Result(merged, realtimeLinked, qualityLinked,
                matchedQuality.size(), carriedQuality, unlinkedQuality);
    }

    private static int associateWithTracks(
            List<Detection> detections,
            List<TrackedObject> activeTracks,
            Map<Integer, TrackedObject> tracksById) {
        if (detections.isEmpty() || activeTracks == null || activeTracks.isEmpty()) return 0;
        Set<Integer> usedTracks = new HashSet<>();
        int linked = 0;
        for (Detection detection : detections) {
            if (detection.getTrackId() >= 0 && tracksById.containsKey(detection.getTrackId())) {
                usedTracks.add(detection.getTrackId());
                linked++;
            }
        }
        List<TrackMatch> candidates = new ArrayList<>();
        for (int detectionIndex = 0; detectionIndex < detections.size(); detectionIndex++) {
            Detection detection = detections.get(detectionIndex);
            if (detection.getTrackId() >= 0 && tracksById.containsKey(detection.getTrackId())) {
                continue;
            }
            for (TrackedObject track : activeTracks) {
                if (track == null || !track.isActive() || usedTracks.contains(track.getId())) continue;
                float score = identityScore(detection, track);
                if (score > 0f) candidates.add(new TrackMatch(detectionIndex, track.getId(), score));
            }
        }
        candidates.sort(Comparator.comparing(TrackMatch::score).reversed());
        Set<Integer> usedDetections = new HashSet<>();
        for (TrackMatch match : candidates) {
            if (usedDetections.contains(match.detectionIndex) || usedTracks.contains(match.trackId)) {
                continue;
            }
            detections.get(match.detectionIndex).setTrackId(match.trackId);
            usedDetections.add(match.detectionIndex);
            usedTracks.add(match.trackId);
            linked++;
        }
        return linked;
    }

    private static float identityScore(Detection first, Detection second) {
        if (!compatibleFamily(first, second)) return 0f;
        return geometryScore(first.getBox(), second.getBox());
    }

    private static float identityScore(Detection detection, TrackedObject track) {
        if (!compatibleFamily(detection, track)) return 0f;
        return geometryScore(detection.getBox(), track.getBox());
    }

    static float geometryScore(BBox first, BBox second) {
        if (first == null || second == null || first.getArea() <= 0L || second.getArea() <= 0L) {
            return 0f;
        }
        float centerX = Math.abs(first.getCenterX() - second.getCenterX())
                / (float) Math.max(1, Math.min(first.getWidth(), second.getWidth()));
        float centerY = Math.abs(first.getCenterY() - second.getCenterY())
                / (float) Math.max(1, Math.min(first.getHeight(), second.getHeight()));
        if (centerX > MAX_CENTER_DELTA_RATIO || centerY > MAX_CENTER_DELTA_RATIO) return 0f;
        long intersection = intersection(first, second);
        long smaller = Math.min(first.getArea(), second.getArea());
        long larger = Math.max(first.getArea(), second.getArea());
        float containment = smaller <= 0L ? 0f : intersection / (float) smaller;
        float areaRatio = larger <= 0L ? 0f : smaller / (float) larger;
        float iou = first.intersectionOverUnion(second);
        if (iou < MIN_IOU && (containment < MIN_CONTAINMENT || areaRatio < MIN_AREA_RATIO)) {
            return 0f;
        }
        return iou * 4f + containment * 2f + areaRatio - centerX - centerY;
    }

    static boolean likelySameIdentity(TrackedObject first, TrackedObject second) {
        return first != null && second != null
                && compatibleFamily(first, second)
                && geometryScore(first.getBox(), second.getBox()) > 0f;
    }

    private static boolean compatibleFamily(Detection first, Detection second) {
        return first != null && second != null
                && (first.getCategory().equals(second.getCategory())
                || bothFaces(first.getClassName(), second.getClassName()));
    }

    private static boolean compatibleFamily(Detection detection, TrackedObject track) {
        return detection != null && track != null
                && (detection.getCategory().equals(track.getCategory())
                || bothFaces(detection.getClassName(), track.getClassName()));
    }

    private static boolean compatibleFamily(TrackedObject first, TrackedObject second) {
        return first.getCategory().equals(second.getCategory())
                || bothFaces(first.getClassName(), second.getClassName());
    }

    private static boolean bothFaces(String first, String second) {
        return first != null && second != null
                && first.startsWith("FACE_") && second.startsWith("FACE_");
    }

    private static Detection metadataWithAuthoritativeBox(
            Detection authoritative,
            Detection supporting) {
        Detection merged = new Detection(
                authoritative.getClassName(),
                authoritative.getCategory(),
                Math.max(authoritative.getConfidence(), supporting.getConfidence()),
                authoritative.getBox(),
                authoritative.isNsfw() || supporting.isNsfw(),
                authoritative.isExposed() || supporting.isExposed(),
                authoritative.getSource(),
                authoritative.getGeometryQuality(),
                authoritative.getAnchorKey());
        int trackId = authoritative.getTrackId() >= 0
                ? authoritative.getTrackId() : supporting.getTrackId();
        if (trackId >= 0) merged.setTrackId(trackId);
        return merged;
    }

    private static List<Detection> nonNull(List<Detection> source) {
        List<Detection> copy = new ArrayList<>();
        if (source == null) return copy;
        for (Detection detection : source) if (detection != null) copy.add(detection);
        return copy;
    }

    private static long intersection(BBox first, BBox second) {
        int left = Math.max(first.getX(), second.getX());
        int top = Math.max(first.getY(), second.getY());
        int right = Math.min(first.getRight(), second.getRight());
        int bottom = Math.min(first.getBottom(), second.getBottom());
        return right <= left || bottom <= top ? 0L : (long) (right - left) * (bottom - top);
    }

    private static final class TrackMatch {
        private final int detectionIndex;
        private final int trackId;
        private final float score;

        TrackMatch(int detectionIndex, int trackId, float score) {
            this.detectionIndex = detectionIndex;
            this.trackId = trackId;
            this.score = score;
        }

        float score() { return score; }
    }

    private static final class SourceMatch {
        private final int liveIndex;
        private final int qualityIndex;
        private final float score;

        SourceMatch(int liveIndex, int qualityIndex, float score) {
            this.liveIndex = liveIndex;
            this.qualityIndex = qualityIndex;
            this.score = score;
        }

        float score() { return score; }
    }

    public static final class Result {
        private final List<Detection> detections;
        private final int realtimeLinked;
        private final int qualityLinked;
        private final int fused;
        private final int carriedQuality;
        private final int unlinkedQuality;

        Result(
                List<Detection> detections,
                int realtimeLinked,
                int qualityLinked,
                int fused,
                int carriedQuality,
                int unlinkedQuality) {
            this.detections = detections;
            this.realtimeLinked = realtimeLinked;
            this.qualityLinked = qualityLinked;
            this.fused = fused;
            this.carriedQuality = carriedQuality;
            this.unlinkedQuality = unlinkedQuality;
        }

        public List<Detection> detections() { return detections; }
        public int realtimeLinked() { return realtimeLinked; }
        public int qualityLinked() { return qualityLinked; }
        public int fused() { return fused; }
        public int carriedQuality() { return carriedQuality; }
        public int unlinkedQuality() { return unlinkedQuality; }
    }
}
