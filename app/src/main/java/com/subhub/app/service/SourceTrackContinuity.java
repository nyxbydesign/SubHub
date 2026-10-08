package com.subhub.app.service;

import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.TrackedObject;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Source-to-source association aid. Does not estimate pixel time or the current display camera. */
final class SourceTrackContinuity {
    private SpatialRegionCache.Frame previous;
    private long previousSourceX, previousSourceY;

    synchronized void clear() { previous = null; }

    /** Commit only after tracker.update succeeds. Unknown global pose can retain local pixels, not a pose. */
    synchronized void record(SpatialRegionCache.Frame frame, long sourceX, long sourceY) {
        previous = frame != null && (frame.result.pose != null || frame.trackingImage != null) ? frame : null;
        previousSourceX = sourceX;
        previousSourceY = sourceY;
    }

    /** Pure proposal: caller invalidates the old basis before mutation, then records successful update. */
    synchronized Map<Integer, Integer> corrections(SpatialRegionCache.Frame frame,
            long sourceX, long sourceY, long oldTrackerY, long nextTrackerY,
            int dx, int dy, int width, int height, List<TrackedObject> tracks,
            List<Detection> alignedDetections, float minimumConfidence) {
        return propose(frame, sourceX, sourceY, oldTrackerY, nextTrackerY, dx, dy, width, height,
                tracks, alignedDetections, minimumConfidence).offsets;
    }

    synchronized Proposal propose(SpatialRegionCache.Frame frame,
            long sourceX, long sourceY, long oldTrackerY, long nextTrackerY,
            int dx, int dy, int width, int height, List<TrackedObject> tracks,
            List<Detection> alignedDetections, float minimumConfidence) {
        Map<Integer, Integer> global = globalCorrections(frame, sourceX, sourceY, oldTrackerY,
                nextTrackerY, dx, dy, width, height, tracks, alignedDetections, minimumConfidence);
        LocalProposal local = localCorrections(frame, sourceX, sourceY, oldTrackerY,
                nextTrackerY, dx, dy, width, height, tracks, alignedDetections, minimumConfidence, global);
        Map<Integer, Integer> combined = new HashMap<>(global);
        combined.putAll(local.offsets);
        return new Proposal(Collections.unmodifiableMap(combined), global.size(), local.offsets.size(), local.pairs);
    }

    private Map<Integer, Integer> globalCorrections(SpatialRegionCache.Frame frame,
            long sourceX, long sourceY, long oldTrackerY, long nextTrackerY,
            int dx, int dy, int width, int height, List<TrackedObject> tracks,
            List<Detection> alignedDetections, float minimumConfidence) {
        if (frame == null || !frame.sameMapAs(previous) || frame.id <= previous.id
                || frame.receipt < previous.receipt || sourceX != previousSourceX || dx != 0
                || width != frame.scope.sourceWidth || height != frame.scope.sourceHeight) {
            return Collections.emptyMap();
        }
        double scale = height / (double) frame.viewportHeight;
        double correction = ((double) sourceY - previousSourceY) * scale
                - (frame.result.pose.sourceY - previous.result.pose.sourceY);
        if (!Double.isFinite(correction) || Math.abs(correction) > height / 3d) return Collections.emptyMap();
        int extra = (int) Math.round(correction);
        if (extra == 0 || (long) dy + extra < Integer.MIN_VALUE || (long) dy + extra > Integer.MAX_VALUE) {
            return Collections.emptyMap();
        }
        double previousTop = previous.top + ((double) previousSourceY - oldTrackerY) * scale;
        double previousBottom = previous.bottom + ((double) previousSourceY - oldTrackerY) * scale;
        double currentTop = frame.top + ((double) sourceY - nextTrackerY) * scale;
        double currentBottom = frame.bottom + ((double) sourceY - nextTrackerY) * scale;
        Map<Integer, Integer> candidates = new HashMap<>();
        Map<Integer, Integer> uses = new HashMap<>();
        for (TrackedObject track : tracks) {
            if (!track.isActive() || track.getFramesMissing() != 0
                    || !visual(track.getObservationSource(), track.getCategory(), track.getAnchorKey())
                    || !inside(track.getRawBox(), previousTop, previousBottom, width)) continue;
            BBox base = shifted(track.getRawBox(), dx, dy);
            BBox moved = shifted(track.getRawBox(), dx, dy + extra);
            if (base == null || moved == null || !inside(moved, currentTop, currentBottom, width)) continue;
            int chosen = -1;
            float bestBase = 0;
            for (int index = 0; index < alignedDetections.size(); index++) {
                Detection detection = alignedDetections.get(index);
                if (!visual(detection.getSource(), detection.getCategory(), detection.getAnchorKey())
                        || !track.getClassName().equals(detection.getClassName())
                        || detection.getConfidence() < minimumConfidence) continue;
                bestBase = Math.max(bestBase, base.intersectionOverUnion(detection.getBox()));
                if (inside(detection.getBox(), currentTop, currentBottom, width)
                        && moved.intersectionOverUnion(detection.getBox()) >= .5f) {
                    if (chosen >= 0) { chosen = -2; break; }
                    chosen = index;
                }
            }
            // Keep a stationary/fixed object at its better-supported event position. The .5 IoU
            // admission is stricter than ordinary tracking; no identity or confidence gate is relaxed.
            if (chosen >= 0 && moved.intersectionOverUnion(alignedDetections.get(chosen).getBox())
                    >= bestBase + .2f) {
                candidates.put(track.getId(), chosen);
                uses.put(chosen, uses.getOrDefault(chosen, 0) + 1);
            }
        }
        Map<Integer, Integer> result = new HashMap<>();
        for (Map.Entry<Integer, Integer> candidate : candidates.entrySet()) {
            if (uses.get(candidate.getValue()) != 1) continue;
            Detection target = alignedDetections.get(candidate.getValue());
            boolean occupied = false;
            for (TrackedObject track : tracks) {
                if (track.getId() == candidate.getKey() || !track.isActive()) continue;
                BBox base = shifted(track.getRawBox(), dx, dy);
                if (base != null && base.intersectionOverUnion(target.getBox()) >= .5f) {
                    occupied = true;
                    break;
                }
            }
            if (!occupied) result.put(candidate.getKey(), extra);
        }
        return Collections.unmodifiableMap(result);
    }

    private LocalProposal localCorrections(SpatialRegionCache.Frame frame,
            long sourceX, long sourceY, long oldTrackerY, long nextTrackerY,
            int dx, int dy, int width, int height, List<TrackedObject> tracks,
            List<Detection> detections, float minimumConfidence, Map<Integer, Integer> global) {
        if (frame == null || !frame.sameScopeAs(previous) || frame.trackingImage == null
                || previous.trackingImage == null || frame.id <= previous.id || frame.receipt < previous.receipt
                || frame.receipt - previous.receipt > 1500 || sourceX != previousSourceX || dx != 0
                || frame.scope.sourceWidth != width || frame.scope.sourceHeight != height) return LocalProposal.EMPTY;
        int pairs = 0;
        for (TrackedObject track : tracks) if (localTrack(track, global)) {
            for (Detection detection : detections) if (localDetection(track, detection, minimumConfidence)) {
                if (++pairs > 16) return new LocalProposal(Collections.emptyMap(), pairs);
            }
        }
        // Reject the entire local proposal when over budget: a partial search cannot prove uniqueness.
        double scale = height / (double) frame.viewportHeight;
        double oldUndo = ((double) oldTrackerY - previousSourceY) * scale;
        double newUndo = ((double) nextTrackerY - sourceY) * scale;
        if (!Double.isFinite(oldUndo) || !Double.isFinite(newUndo)
                || Math.abs(oldUndo) > height || Math.abs(newUndo) > height) return LocalProposal.EMPTY;
        Map<Integer, Integer> candidates = new HashMap<>(), offsets = new HashMap<>(), uses = new HashMap<>();
        for (TrackedObject track : tracks) {
            if (!localTrack(track, global)) continue;
            BBox oldBox = shifted(track.getRawBox(), 0, (int) Math.round(oldUndo));
            BBox base = shifted(track.getRawBox(), dx, dy);
            if (oldBox == null || base == null) continue;
            float bestBase = 0;
            for (Detection detection : detections) if (localDetection(track, detection, minimumConfidence)) {
                bestBase = Math.max(bestBase, base.intersectionOverUnion(detection.getBox()));
            }
            // No IoU can exceed 1. Avoid image searches whose required .2 improvement is impossible.
            if (bestBase > .8f) continue;
            int chosen = -1, chosenExtra = 0;
            for (int index = 0; index < detections.size(); index++) {
                Detection detection = detections.get(index);
                if (!localDetection(track, detection, minimumConfidence)) continue;
                BBox newBox = shifted(detection.getBox(), 0, (int) Math.round(newUndo));
                if (newBox == null) continue;
                SourcePatchMatcher.Match match = SourcePatchMatcher.match(previous.trackingImage,
                        frame.trackingImage, oldBox, newBox, width, height);
                if (match == null) continue;
                double correction = ((double) sourceY - previousSourceY) * scale + match.sourceDy;
                if (!Double.isFinite(correction) || Math.abs(correction) > height / 3d) continue;
                int extra = (int) Math.round(correction);
                if (extra == 0 || (long) dy + extra < Integer.MIN_VALUE || (long) dy + extra > Integer.MAX_VALUE) continue;
                BBox moved = shifted(track.getRawBox(), dx, dy + extra);
                if (moved == null) continue;
                float overlap = moved.intersectionOverUnion(detection.getBox());
                if (overlap < .5f || overlap < bestBase + .2f) continue;
                if (chosen >= 0) { chosen = -2; break; }
                chosen = index;
                chosenExtra = extra;
            }
            if (chosen >= 0) {
                candidates.put(track.getId(), chosen);
                offsets.put(track.getId(), chosenExtra);
                uses.put(chosen, uses.getOrDefault(chosen, 0) + 1);
            }
        }
        Map<Integer, Integer> result = new HashMap<>();
        for (Map.Entry<Integer, Integer> candidate : candidates.entrySet()) {
            if (uses.get(candidate.getValue()) != 1) continue;
            BBox target = detections.get(candidate.getValue()).getBox();
            boolean occupied = false;
            for (TrackedObject track : tracks) {
                if (track.getId() == candidate.getKey() || !track.isActive()) continue;
                BBox other = shifted(track.getRawBox(), dx, dy + global.getOrDefault(track.getId(), 0));
                if (other != null && other.intersectionOverUnion(target) >= .5f) { occupied = true; break; }
            }
            if (!occupied) result.put(candidate.getKey(), offsets.get(candidate.getKey()));
        }
        return new LocalProposal(result, pairs);
    }

    private static boolean localTrack(TrackedObject track, Map<Integer, Integer> global) {
        return track.isActive() && track.getFramesMissing() == 0 && !global.containsKey(track.getId())
                && visual(track.getObservationSource(), track.getCategory(), track.getAnchorKey());
    }

    private static boolean localDetection(TrackedObject track, Detection detection, float minimumConfidence) {
        return visual(detection.getSource(), detection.getCategory(), detection.getAnchorKey())
                && track.getClassName().equals(detection.getClassName()) && detection.getConfidence() >= minimumConfidence;
    }

    static final class Proposal {
        static final Proposal EMPTY = new Proposal(Collections.emptyMap(), 0, 0, 0);
        final Map<Integer, Integer> offsets;
        final int global, local, pairs;
        Proposal(Map<Integer, Integer> offsets, int global, int local, int pairs) {
            this.offsets = offsets; this.global = global; this.local = local; this.pairs = pairs;
        }
        int maxAbsDy() {
            int max = 0;
            for (int value : offsets.values()) max = Math.max(max, Math.abs(value));
            return max;
        }
    }

    private static final class LocalProposal {
        static final LocalProposal EMPTY = new LocalProposal(Collections.emptyMap(), 0);
        final Map<Integer, Integer> offsets;
        final int pairs;
        LocalProposal(Map<Integer, Integer> offsets, int pairs) { this.offsets = offsets; this.pairs = pairs; }
    }

    private static boolean visual(Detection.ObservationSource source, String category, String anchor) {
        return source == Detection.ObservationSource.VISUAL && category != null
                && !category.startsWith("text_") && anchor == null;
    }

    private static boolean inside(BBox box, double top, double bottom, int width) {
        // A partly cropped body object can still have a valid match. Require its center in both
        // registered crops AND a matching live box; crop membership alone never moves a track.
        return box.getArea() > 0 && box.getCenterX() >= 0 && box.getCenterX() < width
                && box.getCenterY() >= top && box.getCenterY() < bottom;
    }

    private static BBox shifted(BBox box, int dx, int dy) {
        long x = (long) box.getX() + dx, y = (long) box.getY() + dy;
        if (x < Integer.MIN_VALUE || x + box.getWidth() > Integer.MAX_VALUE
                || y < Integer.MIN_VALUE || y + box.getHeight() > Integer.MAX_VALUE) return null;
        return new BBox((int) x, (int) y, box.getWidth(), box.getHeight());
    }
}
