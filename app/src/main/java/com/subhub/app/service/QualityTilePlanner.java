package com.subhub.app.service;

import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure alternating crop plan for preserving real source detail in the quality lane. */
final class QualityTilePlanner {
    private static final float SPLIT_ASPECT_RATIO = 1.35f;
    /** Conservative overlap: preserves full-frame recall while recovering small-source detail. */
    private static final float TILE_SPAN_RATIO = .82f;

    private QualityTilePlanner() {}

    static Tile select(int sourceWidth, int sourceHeight, long sequence) {
        return select(sourceWidth, sourceHeight, sequence, TILE_SPAN_RATIO);
    }

    /**
     * Continuous quality cadence: one cheap whole-frame pass, then both detail tiles.
     *
     * <p>The whole-frame pass restores broad coverage quickly and costs substantially less than
     * a cropped hardware readback on gfxstream devices. Top and bottom detail passes still recur
     * within the backfill confirmation window, preserving small-target recovery.</p>
     */
    static Tile selectContinuous(int sourceWidth, int sourceHeight, long qualityPassSequence) {
        int width = Math.max(1, sourceWidth);
        int height = Math.max(1, sourceHeight);
        int phase = (int) Math.floorMod(qualityPassSequence, 3L);
        if (phase == 0 || height < Math.round(width * SPLIT_ASPECT_RATIO)
                && width < Math.round(height * SPLIT_ASPECT_RATIO)) {
            return new Tile(0, 0, width, height, 0, 1);
        }
        return select(width, height, phase - 1L);
    }

    static float productionSpanRatio() {
        return TILE_SPAN_RATIO;
    }

    static Tile select(
            int sourceWidth,
            int sourceHeight,
            long sequence,
            float requestedSpanRatio) {
        int width = Math.max(1, sourceWidth);
        int height = Math.max(1, sourceHeight);
        float spanRatio = Math.max(.50f, Math.min(1f, requestedSpanRatio));
        int phase = (int) Math.floorMod(sequence, 2L);
        if (height >= Math.round(width * SPLIT_ASPECT_RATIO)) {
            int tileHeight = Math.max(1, Math.min(height, Math.round(height * spanRatio)));
            int top = phase == 0 ? 0 : height - tileHeight;
            return new Tile(0, top, width, tileHeight, phase, 2);
        }
        if (width >= Math.round(height * SPLIT_ASPECT_RATIO)) {
            int tileWidth = Math.max(1, Math.min(width, Math.round(width * spanRatio)));
            int left = phase == 0 ? 0 : width - tileWidth;
            return new Tile(left, 0, tileWidth, height, phase, 2);
        }
        return new Tile(0, 0, width, height, 0, 1);
    }

    static List<Detection> toFullFrame(
            List<Detection> detections,
            Tile tile,
            int fullWidth,
            int fullHeight) {
        if (detections == null || detections.isEmpty() || tile == null) {
            return Collections.emptyList();
        }
        int width = Math.max(1, fullWidth);
        int height = Math.max(1, fullHeight);
        List<Detection> mapped = new ArrayList<>(detections.size());
        for (Detection detection : detections) {
            if (detection == null) continue;
            BBox source = detection.getBox();
            int left = clamp(tile.left + source.getX(), 0, width - 1);
            int top = clamp(tile.top + source.getY(), 0, height - 1);
            int right = clamp(tile.left + source.getRight(), left + 1, width);
            int bottom = clamp(tile.top + source.getBottom(), top + 1, height);
            Detection copy = new Detection(
                    detection.getClassName(), detection.getCategory(), detection.getConfidence(),
                    new BBox(left, top, right - left, bottom - top),
                    detection.isNsfw(), detection.isExposed(), detection.getSource(),
                    detection.getGeometryQuality(), detection.getAnchorKey());
            copy.setTrackId(detection.getTrackId());
            mapped.add(copy);
        }
        return Collections.unmodifiableList(mapped);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    static final class Tile {
        private final int left;
        private final int top;
        private final int width;
        private final int height;
        private final int index;
        private final int count;

        private Tile(int left, int top, int width, int height, int index, int count) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
            this.index = index;
            this.count = count;
        }

        int left() { return left; }
        int top() { return top; }
        int width() { return width; }
        int height() { return height; }
        int right() { return left + width; }
        int bottom() { return top + height; }
        int index() { return index; }
        int count() { return count; }
        boolean fullFrame() { return count == 1; }
    }
}
