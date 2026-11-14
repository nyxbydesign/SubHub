package com.subhub.app.remote;

import org.json.JSONException;
import org.json.JSONObject;

/** The negotiated Gag section contains only Dom-authored rules. */
public final class GagContractFields {
    public static final String SECTION = "gag";
    static final String VERSION = "gag_version";
    private GagContractFields() { }

    public static JSONObject defaults() throws JSONException {
        return new JSONObject().put("enabled", false).put("mode", "ALL").put("interval", 3)
                .put("words", "").put("replacements", "mmph\nmph");
    }

    public static JSONObject section(JSONObject configuration) throws JSONException {
        return configuration.getJSONObject("contract").getJSONObject("sections").optJSONObject(SECTION);
    }

    public static JSONObject validate(JSONObject rules) throws JSONException {
        RemoteConfiguration.keys(rules, "enabled", "mode", "interval", "words", "replacements");
        Object enabled = rules.get("enabled"), mode = rules.get("mode");
        long interval = RemoteProtocol.integer(rules, "interval");
        String words = ruleList(rules.get("words")), replacements = ruleList(rules.get("replacements"));
        if (!(enabled instanceof Boolean) || !("ALL".equals(mode) || "INTERVAL".equals(mode) || "MATCH".equals(mode))
                || interval < 1 || interval > 999
                || Boolean.TRUE.equals(enabled) && (replacements.trim().isEmpty()
                        || "MATCH".equals(mode) && words.trim().isEmpty()))
            throw new IllegalArgumentException("Invalid Gag rules");
        return rules;
    }

    private static String ruleList(Object value) {
        if (!(value instanceof String text) || text.length() > 2048
                || text.matches("(?s).*[\\p{Cntrl}&&[^\\r\\n\\t]].*"))
            throw new IllegalArgumentException("Invalid Gag list");
        return text;
    }

    static boolean supported(RemoteStore store, RemoteStore.Peer peer) {
        return "1".equals(store.metadata(peer.identity, peer.generation, VERSION, "0"));
    }

    static JSONObject applied(RemoteStore store, RemoteStore.Peer selected) throws JSONException {
        RemoteStore.Peer peer = store.peer(selected.identity, selected.generation);
        if (peer == null || peer.localRole != RemoteProtocol.Role.SUB || peer.state != RemoteStore.State.ACTIVE
                || !supported(store, peer)) return null;
        String applied = store.metadata(peer.identity, peer.generation, "applied_configuration", "");
        return applied.isEmpty() ? null : section(RemoteConfiguration.active(new JSONObject(applied)));
    }

    /** After a peer downgrade, old in-flight rows still need their original digest and sequence. */
    static boolean containsRules(RemoteProtocol.Batch batch) throws JSONException {
        for (RemoteProtocol.Change row : batch.changes) {
            if (row.deleted) continue;
            JSONObject body = new JSONObject(row.body);
            JSONObject document = batch.stream == RemoteProtocol.Stream.CONFIGURATION
                    ? body.optJSONObject("configuration") : body;
            JSONObject contract = document == null ? null : document.optJSONObject("contract");
            JSONObject sections = contract == null ? null : contract.optJSONObject("sections");
            if (sections != null && sections.has(SECTION)) return true;
        }
        return false;
    }

    static JSONObject forPeer(JSONObject configuration, boolean supported, boolean editing) throws JSONException {
        JSONObject copy = new JSONObject(configuration.toString());
        JSONObject sections = copy.getJSONObject("contract").getJSONObject("sections");
        if (supported) {
            if (!sections.has(SECTION)) sections.put(SECTION, defaults());
        } else if (sections.has(SECTION)) {
            if (editing && !RemoteConfigurationEdits.same(sections.getJSONObject(SECTION), defaults()))
                throw new SecurityException("Update the paired app to configure Gag");
            sections.remove(SECTION);
        }
        return copy;
    }
}
