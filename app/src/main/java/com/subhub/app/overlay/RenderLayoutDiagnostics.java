package com.subhub.app.overlay;

import com.subhub.app.detection.BBox;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.List;

/** Bounded numeric layout snapshot for an explicit shell dumpsys request; no page text/pixels. */
final class RenderLayoutDiagnostics {
    private static final int LIMIT = 64;

    static JSONObject encode(List<RenderTrackSnapshot> live, List<RenderTrackSnapshot> cached,
            List<RenderTrackSnapshot> output, StableVisualLayout layout, int sourceWidth,
            int sourceHeight, int viewportWidth, int viewportHeight, float padding,
            float cameraOffsetX, float cameraOffsetY) throws JSONException {
        JSONObject result = new JSONObject();
        result.put("schemaVersion", 1);
        result.put("active", true);
        result.put("coordinates", "source-scaled-world-targets");
        result.put("sourceWidth", sourceWidth).put("sourceHeight", sourceHeight);
        result.put("viewportWidth", viewportWidth).put("viewportHeight", viewportHeight);
        result.put("cameraOffsetX", cameraOffsetX).put("cameraOffsetY", cameraOffsetY);
        result.put("padding", padding).put("rawLiveCount", live.size());
        result.put("rawCachedCount", cached.size()).put("outputCount", output.size());
        result.put("frozenGeometry", layout.frozenGeometry()).put("heldGroups", layout.heldGroups());
        result.put("layoutDurationMicros", layout.durationMicros());
        JSONArray sources = new JSONArray(), rendered = new JSONArray();
        for (RenderTrackSnapshot item : live) {
            if (sources.length() >= LIMIT) break;
            sources.put(item(item).put("lane", "live"));
        }
        for (RenderTrackSnapshot item : cached) {
            if (sources.length() >= LIMIT) break;
            sources.put(item(item).put("lane", "cached"));
        }
        for (RenderTrackSnapshot item : output) {
            if (rendered.length() >= LIMIT) break;
            rendered.put(item(item).put("memberSourceIds", new JSONArray(layout.memberIds(item.id()))));
        }
        result.put("sources", sources).put("output", rendered);
        result.put("truncated", sources.length() < live.size() + cached.size()
                || rendered.length() < output.size());
        return result;
    }

    private static JSONObject item(RenderTrackSnapshot value) throws JSONException {
        BBox box = value.box();
        return new JSONObject().put("id", value.id()).put("sourceId", value.sourceId())
                .put("category", value.category()).put("cached", value.isCached())
                .put("paddingApplied", value.paddingApplied()).put("basisKnown", value.reference().isKnown())
                .put("box", new JSONArray(List.of(box.getX(), box.getY(), box.getWidth(), box.getHeight())));
    }
}
