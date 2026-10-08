package com.subhub.app.service;

import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.TrackedObject;
import com.subhub.app.detection.RenderSourceReference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Render-only plane alignment for delayed high-resolution detections. */
final class QualityPresentationAligner {
    private static final float MIN_IOU = .06f;
    private static final float MIN_CONTAINMENT = .18f;
    private static final float MIN_AREA_RATIO = .45f;
    private static final float MAX_CENTER_RATIO = .70f;
    private static final int MIN_MATCHES = 2;
    private static final int MAX_CORRECTION_PX = 240;
    private static final float SAFETY_PAD_RATIO = .04f;
    private static final int MIN_SAFETY_PAD_PX = 3;
    private static final int MAX_SAFETY_PAD_PX = 16;

    private QualityPresentationAligner() {}

    static Result align(
            List<Detection> quality,
            List<TrackedObject> live,
            int sourceWidth,
            int sourceHeight) {
        if (quality == null || quality.isEmpty()) return Result.EMPTY;
        if (live == null || live.isEmpty()) {
            return new Result(copy(quality), 0, 0, 0, 0);
        }
        List<Match> candidates = new ArrayList<>();
        for (int qualityIndex = 0; qualityIndex < quality.size(); qualityIndex++) {
            Detection detection = quality.get(qualityIndex);
            if (!isVisual(detection)) continue;
            for (int liveIndex = 0; liveIndex < live.size(); liveIndex++) {
                TrackedObject track = live.get(liveIndex);
                if (!isVisual(track)) continue;
                float score = matchScore(detection.getBox(), track.getRawBox());
                if (score > 0f) candidates.add(new Match(
                        qualityIndex, liveIndex, score,
                        track.getRawBox().getCenterX() - detection.getBox().getCenterX(),
                        track.getRawBox().getCenterY() - detection.getBox().getCenterY()));
            }
        }
        candidates.sort(Comparator.comparing(Match::score).reversed());
        Set<Integer> usedQuality = new HashSet<>();
        Set<Integer> usedLive = new HashSet<>();
        List<Integer> dxValues = new ArrayList<>();
        List<Integer> dyValues = new ArrayList<>();
        RenderSourceReference targetBasis = null;
        boolean compatibleTargets = true;
        for (Match candidate : candidates) {
            if (usedQuality.contains(candidate.qualityIndex)
                    || usedLive.contains(candidate.liveIndex)) continue;
            usedQuality.add(candidate.qualityIndex);
            usedLive.add(candidate.liveIndex);
            RenderSourceReference basis = live.get(candidate.liveIndex).getRenderSourceReference();
            if (targetBasis == null) targetBasis = basis;
            else compatibleTargets &= targetBasis.sameBasis(basis);
            dxValues.add(candidate.dx);
            dyValues.add(candidate.dy);
        }
        if (dxValues.size() < MIN_MATCHES) {
            return new Result(copy(quality), dxValues.size(), 0, 0, 0);
        }
        if (!compatibleTargets) return new Result(copy(quality), 0, 0, 0, 0);
        int dx = clampCorrection(median(dxValues));
        int dy = clampCorrection(median(dyValues));
        boolean referenced = targetBasis.isKnown();
        for (Detection detection : quality) referenced |= detection != null
                && detection.getRenderSourceReference().isKnown();
        if (referenced && (dx != median(dxValues) || dy != median(dyValues))) {
            // A partial clamped shift cannot claim to have reached the target coordinate basis.
            return new Result(copy(quality), 0, 0, 0, 0);
        }
        List<Integer> residuals = new ArrayList<>(dxValues.size());
        for (int index = 0; index < dxValues.size(); index++) {
            residuals.add(Math.abs(dxValues.get(index) - dx)
                    + Math.abs(dyValues.get(index) - dy));
        }
        int residual = median(residuals);
        if (dx == 0 && dy == 0) {
            List<Detection> unchanged = new ArrayList<>(quality.size());
            for (Detection detection : quality) if (detection != null) {
                unchanged.add(detection.withRenderSourceReference(targetBasis));
            }
            return new Result(Collections.unmodifiableList(unchanged), dxValues.size(), 0, 0, residual);
        }
        List<Detection> aligned = new ArrayList<>(quality.size());
        for (Detection detection : quality) {
            if (detection == null) continue;
            BBox box = detection.getBox();
            BBox shifted = new BBox(
                    box.getX() + dx,
                    box.getY() + dy,
                    box.getWidth(), box.getHeight());
            aligned.add(copyWithBox(detection, shifted).withRenderSourceReference(targetBasis));
        }
        return new Result(Collections.unmodifiableList(aligned),
                dxValues.size(), dx, dy, residual);
    }

    static List<Detection> addSafetyCoverage(
            List<Detection> detections,
            int sourceWidth,
            int sourceHeight) {
        if (detections == null || detections.isEmpty()) return Collections.emptyList();
        List<Detection> expanded = new ArrayList<>(detections.size());
        for (Detection detection : detections) {
            if (detection == null) continue;
            BBox box = detection.getBox();
            // Do not turn a wholly offscreen region into a new one-pixel censor on the edge.
            if (box.getRight() <= 0 || box.getBottom() <= 0
                    || box.getX() >= sourceWidth || box.getY() >= sourceHeight) continue;
            int padX = clamp(Math.round(box.getWidth() * SAFETY_PAD_RATIO),
                    MIN_SAFETY_PAD_PX, MAX_SAFETY_PAD_PX);
            int padY = clamp(Math.round(box.getHeight() * SAFETY_PAD_RATIO),
                    MIN_SAFETY_PAD_PX, MAX_SAFETY_PAD_PX);
            int left = Math.max(0, box.getX() - padX);
            int top = Math.max(0, box.getY() - padY);
            int right = Math.min(Math.max(1, sourceWidth), box.getRight() + padX);
            int bottom = Math.min(Math.max(1, sourceHeight), box.getBottom() + padY);
            expanded.add(copyWithBox(detection, new BBox(
                    left, top, Math.max(1, right - left), Math.max(1, bottom - top))));
        }
        return Collections.unmodifiableList(expanded);
    }

    /** Drop only quality geometry already fully covered in the same render coordinate basis. */
    static List<Detection> uncovered(List<Detection> quality, List<TrackedObject> live) {
        if (quality == null || quality.isEmpty()) return Collections.emptyList();
        List<Detection> result = new ArrayList<>();
        for (Detection candidate : quality) {
            if (candidate == null) continue;
            boolean covered = false;
            if (live != null) {
                for (TrackedObject track : live) {
                    if (!isVisual(track) || !track.isVisible()
                            || !candidate.getRenderSourceReference()
                                    .sameBasis(track.getRenderSourceReference())) continue;
                    BBox outer = track.getRawBox();
                    BBox inner = candidate.getBox();
                    if (outer.getX() <= inner.getX() && outer.getY() <= inner.getY()
                            && outer.getRight() >= inner.getRight()
                            && outer.getBottom() >= inner.getBottom()) {
                        covered = true;
                        break;
                    }
                }
            }
            if (!covered) result.add(candidate);
        }
        return Collections.unmodifiableList(result);
    }

    private static float matchScore(BBox first, BBox second) {
        if (first == null || second == null || first.getArea() <= 0L || second.getArea() <= 0L) {
            return 0f;
        }
        int left = Math.max(first.getX(), second.getX());
        int top = Math.max(first.getY(), second.getY());
        int right = Math.min(first.getRight(), second.getRight());
        int bottom = Math.min(first.getBottom(), second.getBottom());
        long intersection = right <= left || bottom <= top
                ? 0L : (long) (right - left) * (bottom - top);
        long smaller = Math.min(first.getArea(), second.getArea());
        float containment = smaller <= 0L ? 0f : intersection / (float) smaller;
        float iou = first.intersectionOverUnion(second);
        float areaRatio = smaller / (float) Math.max(first.getArea(), second.getArea());
        int minimumDimension = Math.max(1, Math.min(
                Math.min(first.getWidth(), first.getHeight()),
                Math.min(second.getWidth(), second.getHeight())));
        float centerRatio = (Math.abs(first.getCenterX() - second.getCenterX())
                + Math.abs(first.getCenterY() - second.getCenterY()))
                / (float) minimumDimension;
        if (areaRatio < MIN_AREA_RATIO || centerRatio > MAX_CENTER_RATIO
                || iou < MIN_IOU && containment < MIN_CONTAINMENT) return 0f;
        return iou * 4f + containment * 2f + areaRatio - centerRatio;
    }

    private static boolean isVisual(Detection detection) {
        return detection != null && !isText(detection.getCategory());
    }

    private static boolean isVisual(TrackedObject track) {
        return track != null && track.isActive() && !isText(track.getCategory());
    }

    private static boolean isText(String category) {
        return "text_smut".equals(category)
                || category != null && category.startsWith("text_");
    }

    private static List<Detection> copy(List<Detection> source) {
        List<Detection> result = new ArrayList<>(source.size());
        for (Detection detection : source) if (detection != null) result.add(detection);
        return Collections.unmodifiableList(result);
    }

    private static Detection copyWithBox(Detection source, BBox box) {
        Detection copy = new Detection(
                source.getClassName(), source.getCategory(), source.getConfidence(), box,
                source.isNsfw(), source.isExposed(), source.getSource(),
                source.getGeometryQuality(), source.getAnchorKey());
        copy.setTrackId(source.getTrackId());
        return copy.withRenderSourceReference(source.getRenderSourceReference());
    }

    private static int median(List<Integer> values) {
        if (values == null || values.isEmpty()) return 0;
        List<Integer> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        if ((sorted.size() & 1) == 1) return sorted.get(middle);
        return Math.round((sorted.get(middle - 1) + sorted.get(middle)) / 2f);
    }

    private static int clampCorrection(int value) {
        return clamp(value, -MAX_CORRECTION_PX, MAX_CORRECTION_PX);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    static final class Result {
        static final Result EMPTY = new Result(Collections.emptyList(), 0, 0, 0, 0);
        private final List<Detection> detections;
        private final int matched;
        private final int dx;
        private final int dy;
        private final int medianResidualPx;

        private Result(
                List<Detection> detections,
                int matched,
                int dx,
                int dy,
                int medianResidualPx) {
            this.detections = detections;
            this.matched = matched;
            this.dx = dx;
            this.dy = dy;
            this.medianResidualPx = medianResidualPx;
        }

        List<Detection> detections() { return detections; }
        int matched() { return matched; }
        int dx() { return dx; }
        int dy() { return dy; }
        int medianResidualPx() { return medianResidualPx; }
    }

    private static final class Match {
        private final int qualityIndex;
        private final int liveIndex;
        private final float score;
        private final int dx;
        private final int dy;

        private Match(int qualityIndex, int liveIndex, float score, int dx, int dy) {
            this.qualityIndex = qualityIndex;
            this.liveIndex = liveIndex;
            this.score = score;
            this.dx = dx;
            this.dy = dy;
        }

        private float score() { return score; }
    }
}
