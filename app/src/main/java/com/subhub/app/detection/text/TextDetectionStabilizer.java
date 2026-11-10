package com.subhub.app.detection.text;

import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Suppresses one-scan text noise and bridges one missed scan for already confirmed lines. */
public final class TextDetectionStabilizer {
    private final Map<String, Observation> observations = new HashMap<>();
    private long generation;
    private boolean bridgeCurrentMiss;

    public synchronized List<Detection> update(List<Detection> detections) {
        return updateWithMetrics(detections, true).getStableDetections();
    }

    /**
     * Updates confirmed text regions.
     *
     * @param bridgeConfirmedMiss when true, retain a confirmed line across one missing scan;
     *                            disable during post-scroll reconciliation so departed feed text
     *                            cannot survive as a ghost for another scan interval.
     */
    public synchronized List<Detection> update(
            List<Detection> detections,
            boolean bridgeConfirmedMiss) {
        return updateWithMetrics(detections, bridgeConfirmedMiss).getStableDetections();
    }

    /** Reconciles a complete viewport scan while reporting which candidates still need proof. */
    public synchronized UpdateResult updateWithMetrics(
            List<Detection> detections,
            boolean bridgeConfirmedMiss) {
        generation++;
        bridgeCurrentMiss = bridgeConfirmedMiss;
        List<Detection> stable = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int pending = 0;
        int confirmedPresent = 0;
        int bridged = 0;
        if (detections != null) {
            for (Detection detection : detections) {
                if (detection == null) continue;
                String key = key(detection);
                if (!seen.add(key)) continue;
                Observation previous = observations.get(key);
                int hits = previous != null && previous.lastSeenGeneration == generation - 1
                        ? previous.hits + 1 : 1;
                Observation current = new Observation(detection, hits, generation);
                observations.put(key, current);
                if (hits >= 2) {
                    stable.add(detection);
                    confirmedPresent++;
                } else {
                    pending++;
                }
            }
        }

        Iterator<Map.Entry<String, Observation>> iterator = observations.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Observation> entry = iterator.next();
            if (seen.contains(entry.getKey())) continue;
            Observation value = entry.getValue();
            long missed = generation - value.lastSeenGeneration;
            if (bridgeConfirmedMiss && value.hits >= 2 && missed == 1) {
                stable.add(value.detection);
                bridged++;
            }
            if (missed > 1 || value.hits < 2) iterator.remove();
        }
        return new UpdateResult(stable, pending, confirmedPresent, bridged, 0);
    }

    /**
     * Confirms candidates from a cheap targeted node refresh without treating every unprobed
     * viewport node as missing. The full scan's generation remains authoritative.
     */
    public synchronized UpdateResult confirmSubset(List<Detection> detections) {
        int newlyConfirmed = 0;
        if (detections != null) {
            Set<String> confirmed = new HashSet<>();
            for (Detection detection : detections) {
                if (detection == null) continue;
                String key = key(detection);
                if (!confirmed.add(key)) continue;
                Observation previous = observations.get(key);
                if (previous == null || previous.lastSeenGeneration != generation) continue;
                if (previous.hits >= 2) continue;
                observations.put(key,
                        new Observation(detection, Math.max(2, previous.hits + 1), generation));
                newlyConfirmed++;
            }
        }

        List<Detection> stable = new ArrayList<>();
        int pending = 0;
        for (Observation observation : observations.values()) {
            if (observation.lastSeenGeneration == generation) {
                if (observation.hits >= 2) stable.add(observation.detection);
                else pending++;
            } else if (bridgeCurrentMiss && observation.hits >= 2
                    && generation - observation.lastSeenGeneration == 1) {
                stable.add(observation.detection);
            }
        }
        return new UpdateResult(stable, pending, stable.size(), 0, newlyConfirmed);
    }

    public synchronized void clear() {
        observations.clear();
        generation = 0L;
        bridgeCurrentMiss = false;
    }

    private static String key(Detection detection) {
        String anchor = detection.getAnchorKey();
        if (anchor != null && !anchor.isEmpty()) return detection.getClassName() + '|' + anchor;
        BBox box = detection.getBox();
        return detection.getClassName() + '|' + box.getCenterX() / 48 + '|'
                + box.getCenterY() / 32 + '|' + box.getWidth() / 48 + '|'
                + box.getHeight() / 16;
    }

    public static final class UpdateResult {
        private final List<Detection> stableDetections;
        private final int pendingCandidates;
        private final int confirmedPresent;
        private final int bridgedConfirmed;
        private final int newlyConfirmedCandidates;

        private UpdateResult(
                List<Detection> stableDetections,
                int pendingCandidates,
                int confirmedPresent,
                int bridgedConfirmed,
                int newlyConfirmedCandidates) {
            this.stableDetections = Collections.unmodifiableList(
                    new ArrayList<>(stableDetections));
            this.pendingCandidates = pendingCandidates;
            this.confirmedPresent = confirmedPresent;
            this.bridgedConfirmed = bridgedConfirmed;
            this.newlyConfirmedCandidates = newlyConfirmedCandidates;
        }

        public List<Detection> getStableDetections() { return stableDetections; }
        public int getPendingCandidates() { return pendingCandidates; }
        public int getConfirmedPresent() { return confirmedPresent; }
        public int getBridgedConfirmed() { return bridgedConfirmed; }
        public int getNewlyConfirmedCandidates() { return newlyConfirmedCandidates; }
    }

    private static final class Observation {
        private final Detection detection;
        private final int hits;
        private final long lastSeenGeneration;

        private Observation(Detection detection, int hits, long lastSeenGeneration) {
            this.detection = detection;
            this.hits = hits;
            this.lastSeenGeneration = lastSeenGeneration;
        }
    }
}
