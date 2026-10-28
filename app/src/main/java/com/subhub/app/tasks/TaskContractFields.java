package com.subhub.app.tasks;

import org.json.*;

/** The task-rule extension is explicit; all other active-contract completeness checks stay strict. */
public final class TaskContractFields {
    public static final String ENABLED = "rule_task_failure_enabled", CENTS = "rule_task_failure_cents";
    private TaskContractFields() { }
    public static void expandLegacy(JSONObject sections) throws JSONException {
        JSONObject wallet = sections.getJSONObject("wallet");
        if (!wallet.has(ENABLED) && !wallet.has(CENTS)) wallet.put(ENABLED, false).put(CENTS, 100);
    }
    public static JSONObject forPeer(JSONObject configuration, boolean supported, boolean editing) throws JSONException {
        JSONObject result = new JSONObject(configuration.toString());
        if (supported) return result;
        JSONObject wallet = result.getJSONObject("contract").getJSONObject("sections").getJSONObject("wallet");
        if (editing && Boolean.TRUE.equals(wallet.opt(ENABLED)))
            throw new SecurityException("Update the paired device to configure task failure tributes");
        wallet.remove(ENABLED); wallet.remove(CENTS);
        return result;
    }
}
