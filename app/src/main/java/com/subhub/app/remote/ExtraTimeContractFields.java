package com.subhub.app.remote;

import org.json.JSONException;
import org.json.JSONObject;

/** Explicit extension defaults preserve old contracts without weakening other completeness checks. */
public final class ExtraTimeContractFields {
    public static final String ENABLED = "rule_extra_time_request_enabled", CENTS = "rule_extra_time_request_cents";
    private ExtraTimeContractFields() { }
    public static void expandLegacy(JSONObject sections) throws JSONException {
        JSONObject wallet = sections.getJSONObject("wallet");
        if (!wallet.has(ENABLED) && !wallet.has(CENTS)) wallet.put(ENABLED,false).put(CENTS,100);
    }
    public static JSONObject forPeer(JSONObject configuration, boolean supported, boolean editing) throws JSONException {
        JSONObject result = new JSONObject(configuration.toString());
        if (supported) return result;
        JSONObject wallet = result.getJSONObject("contract").getJSONObject("sections").getJSONObject("wallet");
        if (editing && Boolean.TRUE.equals(wallet.opt(ENABLED)))
            throw new SecurityException("Update the paired app to configure extra-time request tributes");
        wallet.remove(ENABLED); wallet.remove(CENTS);
        return result;
    }
}
