package com.subhub.app.detection.text;

import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Fuses native-text and visual boxes before tracking to prevent duplicate strikes. */
public final class DetectionFusion {
    private DetectionFusion() {}

    @SafeVarargs
    public static List<Detection> merge(List<Detection> visual, List<Detection>... textSources) {
        List<Detection> merged = new ArrayList<>(visual == null
                ? Collections.emptyList() : visual);
        if (textSources == null) return merged;
        for (List<Detection> source : textSources) {
            if (source == null) continue;
            for (Detection candidate : source) addOrFuse(merged, candidate);
        }
        return merged;
    }

    /**
     * Adds settled-model coverage without allowing its older/coarser rectangles to reshape a
     * matching real-time observation. Unmatched refinement detections remain available as
     * quality-only coverage; the tracker deliberately treats their geometry as non-authoritative
     * after the track has been created.
     */
    public static List<Detection> mergeVisualRefinement(
            List<Detection> realtime,
            List<Detection> refinement) {
        List<Detection> merged = new ArrayList<>(realtime == null
                ? Collections.emptyList() : realtime);
        int realtimeCount = merged.size();
        if (refinement == null) return merged;
        for (Detection candidate : refinement) {
            if (candidate == null) continue;
            boolean matched = false;
            for (int index = 0; index < realtimeCount; index++) {
                Detection authoritative = merged.get(index);
                if (!shouldFuse(authoritative, candidate)) continue;
                merged.set(index, metadataWithAuthoritativeBox(authoritative, candidate));
                matched = true;
                break;
            }
            if (!matched) merged.add(candidate);
        }
        return merged;
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

    private static void addOrFuse(List<Detection> merged, Detection candidate) {
        if (candidate == null) return;
        for (int index = 0; index < merged.size(); index++) {
            Detection existing = merged.get(index);
            if (!shouldFuse(existing, candidate)) continue;
            merged.set(index, mergedDetection(existing, candidate));
            return;
        }
        merged.add(candidate);
    }

    private static Detection mergedDetection(Detection first, Detection second) {
        BBox fused = fusedBox(first, second);
        Detection preferred = preferredObservation(first, second);
        return new Detection(
                preferred.getClassName(),
                preferred.getCategory(),
                Math.max(first.getConfidence(), second.getConfidence()),
                fused,
                first.isNsfw() || second.isNsfw(),
                first.isExposed() || second.isExposed(),
                preferred.getSource(),
                preferred.getGeometryQuality(),
                preferred.getAnchorKey());
    }

    private static boolean isText(Detection detection) {
        return detection != null && "text_smut".equals(detection.getCategory());
    }

    private static boolean isOcr(Detection detection) {
        return detection != null && detection.getClassName() != null
                && detection.getClassName().startsWith("TEXT_SMUT_OCR_");
    }

    private static BBox fusedBox(Detection first, Detection second) {
        boolean textPair = "text_smut".equals(first.getCategory())
                && "text_smut".equals(second.getCategory());
        if (textPair) {
            // Text geometry is an anchored line, never a growing overlap component. Unioning a
            // bridge box with neighboring lines made the complete group reshape for one frame.
            return preferredObservation(first, second).getBox();
        }
        return union(first.getBox(), second.getBox());
    }

    private static boolean shouldFuse(Detection first, Detection second) {
        boolean textPair = "text_smut".equals(first.getCategory())
                && "text_smut".equals(second.getCategory());
        if (textPair && first.getAnchorKey() != null && second.getAnchorKey() != null
                && !first.getAnchorKey().equals(second.getAnchorKey())) return false;
        long intersection = intersection(first.getBox(), second.getBox());
        long smaller = Math.min(first.getBox().getArea(), second.getBox().getArea());
        float containment = smaller == 0 ? 0f : (float) intersection / smaller;
        if (containment >= (textPair ? 0.25f : 0.70f)) return true;
        // Separate rendered lines must remain separate bars. Only overlapping source estimates
        // are duplicates; proximity alone used to create oversized blocks spanning whitespace.
        return false;
    }

    private static Detection preferredObservation(Detection first, Detection second) {
        if (!isText(first) || !isText(second)) return isText(first) ? second : first;
        int firstQuality = first.getGeometryQuality().ordinal();
        int secondQuality = second.getGeometryQuality().ordinal();
        if (firstQuality != secondQuality) return firstQuality > secondQuality ? first : second;
        if (isOcr(first) != isOcr(second)) return isOcr(first) ? first : second;
        long smaller = Math.min(first.getBox().getArea(), second.getBox().getArea());
        long larger = Math.max(first.getBox().getArea(), second.getBox().getArea());
        if (smaller > 0L && larger >= smaller * 2L) {
            return first.getBox().getArea() <= second.getBox().getArea() ? first : second;
        }
        // Stable source order wins ties. This prevents tiny detector jitter from changing the
        // chosen rectangle when the same line appears in several overlapping windows.
        return first;
    }

    private static long intersection(BBox first, BBox second) {
        int left = Math.max(first.getX(), second.getX());
        int top = Math.max(first.getY(), second.getY());
        int right = Math.min(first.getRight(), second.getRight());
        int bottom = Math.min(first.getBottom(), second.getBottom());
        return right <= left || bottom <= top ? 0L : (long) (right - left) * (bottom - top);
    }

    private static BBox union(BBox first, BBox second) {
        int left = Math.min(first.getX(), second.getX());
        int top = Math.min(first.getY(), second.getY());
        int right = Math.max(first.getRight(), second.getRight());
        int bottom = Math.max(first.getBottom(), second.getBottom());
        return new BBox(left, top, right - left, bottom - top);
    }
}
