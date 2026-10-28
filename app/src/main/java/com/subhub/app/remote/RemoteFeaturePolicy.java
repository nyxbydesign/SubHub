package com.subhub.app.remote;

import android.content.Context;
import com.subhub.app.configuration.ConfigurationSchema;
import com.subhub.app.security.SubFeaturePolicy;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Full snapshots must preserve every feature the receiving Sub has opted out of. */
public final class RemoteFeaturePolicy {
    private RemoteFeaturePolicy() { }
    public static void requireAllowed(Context context, JSONObject proposed, JSONObject previous) throws Exception {
        if (SubFeaturePolicy.hardcoreOverride(context)) return;
        JSONObject sections = proposed.getJSONObject("contract").getJSONObject("sections");
        for (String feature : SubFeaturePolicy.FEATURES) {
            if (SubFeaturePolicy.chosen(context, feature)) continue;
            JSONObject current = ConfigurationSchema.captureSection(context, feature);
            if (!RemoteProtocol.canonical(current).equals(RemoteProtocol.canonical(sections.getJSONObject(feature))))
                throw new SecurityException("Feature disabled by Sub");
            if ("censor".equals(feature) || "subliminal".equals(feature)) {
                String key = "censor".equals(feature) ? "module_censor_enabled" : "module_subliminal_enabled";
                JSONObject modules = ConfigurationSchema.captureSection(context, "modules");
                if (!modules.get(key).equals(sections.getJSONObject("modules").get(key)))
                    throw new SecurityException("Feature disabled by Sub");
            }
            if ("limits".equals(feature)) {
                JSONObject currentApps = previous == null ? new JSONObject() : previous.getJSONObject("apps");
                if (!RemoteProtocol.canonical(currentApps.optJSONObject("allowances") == null ? new JSONObject() : currentApps.getJSONObject("allowances"))
                        .equals(RemoteProtocol.canonical(proposed.getJSONObject("apps").getJSONObject("allowances"))))
                    throw new SecurityException("Feature disabled by Sub");
            }
            if (("censor".equals(feature) || "popup".equals(feature))
                    && !assets(previous, feature).equals(assets(proposed, feature)))
                throw new SecurityException("Feature disabled by Sub");
        }
    }
    private static List<String> assets(JSONObject doc, String feature) throws Exception {
        List<String> result = new ArrayList<>();
        if (doc == null) return result;
        JSONArray refs = doc.getJSONObject("contract").getJSONArray("assets");
        for (int index = 0; index < refs.length(); index++) {
            JSONObject ref = refs.getJSONObject(index);
            if (ref.getString("path").startsWith("assets/" + feature + "/")) result.add(RemoteProtocol.canonical(ref));
        }
        Collections.sort(result);
        return result;
    }
    public static boolean remoteAllowed(RemoteStore store, RemoteStore.Peer peer, String section) {
        JSONObject runtime = store.mirrored(peer.identity, peer.generation, RemoteProtocol.Stream.RUNTIME, "current");
        if (runtime == null || !runtime.has("featureConsent")) return true;
        JSONObject consent = runtime.optJSONObject("featureConsent");
        if (consent == null) return false;
        if ("atmosphere".equals(section)) return consent.optBoolean("subliminal", false) || consent.optBoolean("popup", false);
        return !SubFeaturePolicy.FEATURES.contains(section) || consent.optBoolean(section, false);
    }
}
