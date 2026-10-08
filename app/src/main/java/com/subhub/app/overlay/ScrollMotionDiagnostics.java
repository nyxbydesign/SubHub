package com.subhub.app.overlay;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Allocation-free draw sampling; JSON is produced only for an explicit dumpsys request. */
final class ScrollMotionDiagnostics {
    static final int CAPACITY = 256;
    private final long[] time = new long[CAPACITY], sequence = new long[CAPACITY];
    private final float[] x = new float[CAPACITY], y = new float[CAPACITY];
    private final float[] authorityX = new float[CAPACITY], authorityY = new float[CAPACITY];
    private long samples, rejectedFrames;

    void record(long presentationTime, float renderedX, float renderedY,
            float eventX, float eventY, long inputSequence) {
        if (!Float.isFinite(renderedX) || !Float.isFinite(renderedY)
                || !Float.isFinite(eventX) || !Float.isFinite(eventY)) {
            rejectedFrames++;
            return;
        }
        int index = (int) (samples++ % CAPACITY);
        time[index] = presentationTime;
        x[index] = renderedX;
        y[index] = renderedY;
        authorityX[index] = eventX;
        authorityY[index] = eventY;
        sequence[index] = inputSequence;
    }

    void clear() { samples = rejectedFrames = 0; }

    JSONObject encode(long now) throws JSONException {
        int count = (int) Math.min(samples, CAPACITY);
        JSONArray frames = new JSONArray();
        for (long sample = samples - count; sample < samples; sample++) {
            int index = (int) (sample % CAPACITY);
            frames.put(new JSONArray().put(time[index]).put(x[index]).put(y[index])
                    .put(authorityX[index]).put(authorityY[index]).put(sequence[index]));
        }
        return new JSONObject().put("schemaVersion", 1)
                .put("clock", "render-evaluation-uptime-ms-not-photon-time")
                .put("fields", new JSONArray().put("timeMs").put("renderX").put("renderY")
                        .put("eventX").put("eventY").put("inputSequence"))
                .put("observedAtUptimeMs", now).put("totalFrames", samples)
                .put("rejectedFrames", rejectedFrames)
                .put("retainedFrames", count).put("truncated", samples > count)
                .put("frames", frames);
    }
}
