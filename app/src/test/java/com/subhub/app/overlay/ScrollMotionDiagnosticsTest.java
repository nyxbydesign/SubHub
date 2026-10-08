package com.subhub.app.overlay;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ScrollMotionDiagnosticsTest {
    @Test public void frameFixtureRoundTripsRenderedAndEventCoordinatesSeparately() throws Exception {
        ScrollMotionDiagnostics recorder = new ScrollMotionDiagnostics();
        recorder.record(1000, 1.5f, -33.25f, 0, -20, 3);
        JSONObject parsed = new JSONObject(recorder.encode(1010).toString());
        assertEquals(1, parsed.getInt("schemaVersion"));
        assertEquals(1, parsed.getInt("totalFrames"));
        assertEquals(1, parsed.getInt("retainedFrames"));
        assertFalse(parsed.getBoolean("truncated"));
        assertEquals(6, parsed.getJSONArray("fields").length());
        assertEquals(1000, parsed.getJSONArray("frames").getJSONArray(0).getLong(0));
        assertEquals(-33.25, parsed.getJSONArray("frames").getJSONArray(0).getDouble(2), .001);
        assertEquals(-20, parsed.getJSONArray("frames").getJSONArray(0).getDouble(4), .001);
    }

    @Test public void ringTruncationIsExplicitAndRetainsChronologicalOrder() throws Exception {
        ScrollMotionDiagnostics recorder = new ScrollMotionDiagnostics();
        for (int index = 0; index < 300; index++) recorder.record(index * 8L, 0, -index, 0, 0, index / 10);
        JSONObject parsed = new JSONObject(recorder.encode(2400).toString());
        assertEquals(300, parsed.getInt("totalFrames"));
        assertEquals(256, parsed.getInt("retainedFrames"));
        assertTrue(parsed.getBoolean("truncated"));
        assertEquals(44 * 8L, parsed.getJSONArray("frames").getJSONArray(0).getLong(0));
        assertEquals(299 * 8L, parsed.getJSONArray("frames").getJSONArray(255).getLong(0));
        recorder.clear();
        recorder.record(3000, 0, -1, 0, 0, 1);
        assertEquals(1, recorder.encode(3001).getInt("totalFrames"));
        assertFalse(recorder.encode(3001).getBoolean("truncated"));
    }

    @Test public void noFramesAreMissingEvidenceNotZeroJitter() throws Exception {
        ScrollMotionDiagnostics recorder = new ScrollMotionDiagnostics();
        recorder.record(1, 0, Float.NaN, 0, 0, 1);
        assertEquals(0, recorder.encode(1).getJSONArray("frames").length());
        assertEquals(1, recorder.encode(1).getLong("rejectedFrames"));
    }
}
