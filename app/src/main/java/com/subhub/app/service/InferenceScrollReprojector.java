package com.subhub.app.service;

import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;

import java.util.ArrayList;
import java.util.List;

/** Reprojects detector results from their screenshot scroll position to the live viewport. */
final class InferenceScrollReprojector {
    private InferenceScrollReprojector() {}

    static List<Detection> toCurrentViewport(
            List<Detection> detections,
            int frameWidth,
            int frameHeight,
            int viewportWidth,
            int viewportHeight,
            long requestedScrollX,
            long requestedScrollY,
            long currentScrollX,
            long currentScrollY) {
        ScreenMotion motion = screenMotion(
                requestedScrollX, requestedScrollY, currentScrollX, currentScrollY);
        if (!motion.moved() || detections.isEmpty()) return detections;
        int dx = Math.round(motion.dx * frameWidth / (float) Math.max(1, viewportWidth));
        int dy = Math.round(motion.dy * frameHeight / (float) Math.max(1, viewportHeight));
        List<Detection> shifted = new ArrayList<>(detections.size());
        for (Detection detection : detections) {
            BBox box = detection.getBox();
            int left = clamp(box.getX() + dx, 0, frameWidth);
            int top = clamp(box.getY() + dy, 0, frameHeight);
            int right = clamp(box.getRight() + dx, 0, frameWidth);
            int bottom = clamp(box.getBottom() + dy, 0, frameHeight);
            if (right <= left || bottom <= top) continue;
            Detection projected = new Detection(
                    detection.getClassName(),
                    detection.getCategory(),
                    detection.getConfidence(),
                    new BBox(left, top, right - left, bottom - top),
                    detection.isNsfw(),
                    detection.isExposed(),
                    detection.getSource(),
                    detection.getGeometryQuality(),
                    detection.getAnchorKey()).withRenderSourceReference(detection.getRenderSourceReference());
            projected.setTrackId(detection.getTrackId());
            shifted.add(projected);
        }
        return shifted;
    }

    static ScreenMotion screenMotion(
            long requestedScrollX,
            long requestedScrollY,
            long currentScrollX,
            long currentScrollY) {
        return new ScreenMotion(
                saturatingInt(-(currentScrollX - requestedScrollX)),
                saturatingInt(-(currentScrollY - requestedScrollY)));
    }

    private static int saturatingInt(long value) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    static final class ScreenMotion {
        final int dx;
        final int dy;

        ScreenMotion(int dx, int dy) {
            this.dx = dx;
            this.dy = dy;
        }

        boolean moved() { return dx != 0 || dy != 0; }
    }
}
