package com.subhub.app.detection.text;

import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Maps screen-space Accessibility text boxes into the current screenshot frame. */
public final class TextDetectionCoordinateMapper {
    private TextDetectionCoordinateMapper() {}

    public static List<Detection> screenToCapture(
            List<Detection> source,
            int screenWidth,
            int screenHeight,
            int captureWidth,
            int captureHeight) {
        if (source == null || source.isEmpty() || screenWidth <= 0 || screenHeight <= 0
                || captureWidth <= 0 || captureHeight <= 0) return Collections.emptyList();
        List<Detection> mapped = new ArrayList<>(source.size());
        for (Detection detection : source) {
            if (detection == null) continue;
            BBox box = detection.getBox();
            int left = scaleEdge(box.getX(), screenWidth, captureWidth);
            int top = scaleEdge(box.getY(), screenHeight, captureHeight);
            int right = scaleEdge(box.getRight(), screenWidth, captureWidth);
            int bottom = scaleEdge(box.getBottom(), screenHeight, captureHeight);
            left = clamp(left, 0, Math.max(0, captureWidth - 1));
            top = clamp(top, 0, Math.max(0, captureHeight - 1));
            right = clamp(right, left + 1, captureWidth);
            bottom = clamp(bottom, top + 1, captureHeight);
            mapped.add(new Detection(
                    detection.getClassName(),
                    detection.getCategory(),
                    detection.getConfidence(),
                    new BBox(left, top, right - left, bottom - top),
                    detection.isNsfw(),
                    detection.isExposed(),
                    detection.getSource(),
                    detection.getGeometryQuality(),
                    detection.getAnchorKey()));
        }
        return Collections.unmodifiableList(mapped);
    }

    private static int scaleEdge(int value, int sourceExtent, int targetExtent) {
        return Math.round(value * (targetExtent / (float) sourceExtent));
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
