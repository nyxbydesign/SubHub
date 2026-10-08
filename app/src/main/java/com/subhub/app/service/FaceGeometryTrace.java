package com.subhub.app.service;

import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.TrackedObject;
import java.util.List;

/** Bounded numeric-only QA trace of pre-projection face observations and selected track geometry. */
final class FaceGeometryTrace {
    private static final int LIMIT = 8;
    private FaceGeometryTrace() {}

    /** Source-image pose only; never a receipt-time/current-display camera measurement. */
    static String sourcePose(SpatialRegionCache.Frame frame) {
        if (frame == null || frame.result.pose == null || frame.scope == null) return "-";
        return frame.scope.captureEpoch + "," + frame.scope.document + "," + frame.scope.window
                + "," + frame.result.pose.mapGeneration + "," + frame.result.pose.sourceY;
    }

    static String encode(List<Detection> observations, List<TrackedObject> tracks) {
        StringBuilder observed = new StringBuilder(), tracked = new StringBuilder();
        int totalObserved = 0, encodedObserved = 0, totalTracked = 0, encodedTracked = 0;
        for (Detection detection : observations) {
            int kind = kind(detection.getClassName());
            if (kind == 0) continue;
            totalObserved++;
            if (encodedObserved == LIMIT) continue;
            if (encodedObserved++ > 0) observed.append('|');
            observed.append(kind).append(',').append(detection.getSource().ordinal()).append(',');
            box(observed, detection.getBox());
        }
        for (TrackedObject track : tracks) {
            int kind = kind(track.getClassName());
            if (kind == 0) continue;
            totalTracked++;
            if (encodedTracked == LIMIT) continue;
            if (encodedTracked++ > 0) tracked.append('|');
            tracked.append(track.getId()).append(',').append(kind).append(',')
                    .append(track.getFramesMissing()).append(',').append(track.getObservationSource().ordinal()).append(',');
            box(tracked, track.getRawBox());
            tracked.append(',');
            box(tracked, track.getBox());
        }
        return "obsTotal=" + totalObserved + " obsEncoded=" + encodedObserved
                + " observations=" + (encodedObserved == 0 ? "-" : observed)
                + " tracksTotal=" + totalTracked + " tracksEncoded=" + encodedTracked
                + " tracks=" + (encodedTracked == 0 ? "-" : tracked);
    }

    private static int kind(String value) {
        return "FACE_FEMALE".equals(value) ? 1 : "FACE_MALE".equals(value) ? 2 : 0;
    }
    private static void box(StringBuilder text, BBox box) {
        text.append(box.getX()).append(',').append(box.getY()).append(',')
                .append(box.getWidth()).append(',').append(box.getHeight());
    }
}
