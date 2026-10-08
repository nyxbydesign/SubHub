package com.subhub.app.overlay;

import org.json.JSONObject;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public final class RenderLayoutDiagnosticsTest {
    @Test public void diagnosticFixtureKeepsRawAndRenderedCoordinatesDistinct() throws Exception {
        StableVisualLayout layout = new StableVisualLayout();
        List<RenderTrackSnapshot> raw = List.of(StableVisualLayoutTest.box(7, "face", 100, 100, 100, 100));
        List<RenderTrackSnapshot> rendered = layout.update(raw, .2f, 100);
        JSONObject encoded = RenderLayoutDiagnostics.encode(raw, List.of(), rendered, layout,
                1000, 2000, 1000, 2000, .2f, 0f, -50f);
        JSONObject parsed = new JSONObject(encoded.toString());
        assertEquals(1, parsed.getInt("schemaVersion"));
        assertEquals(1, parsed.getInt("rawLiveCount"));
        assertEquals(1, parsed.getInt("outputCount"));
        assertFalse(parsed.getBoolean("truncated"));
        assertEquals(100, parsed.getJSONArray("sources").getJSONObject(0).getJSONArray("box").getInt(0));
        assertEquals(80, parsed.getJSONArray("output").getJSONObject(0).getJSONArray("box").getInt(0));
        assertTrue(parsed.getJSONArray("output").getJSONObject(0).getBoolean("paddingApplied"));
        assertEquals(7, parsed.getJSONArray("output").getJSONObject(0).getJSONArray("memberSourceIds").getInt(0));
    }

    @Test public void diagnosticTruncationIsExplicitInsteadOfInventingCompleteGeometry() throws Exception {
        StableVisualLayout layout = new StableVisualLayout();
        List<RenderTrackSnapshot> raw = new ArrayList<>();
        for (int id = 1; id <= 70; id++) raw.add(StableVisualLayoutTest.box(id, "face", id * 200, 0, 80, 80));
        JSONObject result = RenderLayoutDiagnostics.encode(raw, List.of(), layout.update(raw, 0f, 100),
                layout, 1000, 2000, 1000, 2000, 0f, 0f, 0f);
        assertTrue(result.getBoolean("truncated"));
        assertEquals(70, result.getInt("rawLiveCount"));
        assertEquals(64, result.getJSONArray("sources").length());
    }
}
