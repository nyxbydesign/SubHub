package com.subhub.app.remote;

import com.subhub.app.atmosphere.GagWordPairs;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/** The negotiated Gag section contains only Dom-authored rules. */
public final class GagContractFields {
    public static final String SECTION = "gag";
    public static final String PAIRS = "pairs";
    static final String VERSION = "gag_version";
    static final String PAIRS_VERSION = "gag_pairs_version";
    static final String PAIR_OPTIONS_VERSION = "gag_pair_options_version";
    static final String PAIRS_UPDATE_REQUIRED = "Update the paired app for per-word replacements";
    private GagContractFields() { }

    public enum Capability {
        NONE, SHARED, PAIRS, PAIR_OPTIONS;

        public boolean supports(Capability required) { return compareTo(required) >= 0; }

        public boolean supportsPairs(JSONObject rules) {
            Capability required = required(rules);
            return required.compareTo(PAIRS) < 0 || supports(required);
        }
    }

    public static JSONObject defaults() throws JSONException {
        return new JSONObject().put("enabled", false).put("mode", "ALL").put("interval", 3)
                .put("words", "").put("replacements", "mmph\nmph");
    }

    public static JSONObject section(JSONObject configuration) throws JSONException {
        return configuration.getJSONObject("contract").getJSONObject("sections").optJSONObject(SECTION);
    }

    public static JSONObject validate(JSONObject rules) throws JSONException {
        boolean paired = needsPairs(rules);
        if (paired) RemoteConfiguration.keys(rules, "enabled", "mode", "interval", "words", "replacements", PAIRS);
        else RemoteConfiguration.keys(rules, "enabled", "mode", "interval", "words", "replacements");
        Object enabled = rules.get("enabled"), mode = rules.get("mode");
        long interval = RemoteProtocol.integer(rules, "interval");
        String words = ruleList(rules.get("words")), replacements = ruleList(rules.get("replacements"));
        boolean hasSharedWords = !words.trim().isEmpty();
        boolean matching = "MATCH".equals(mode);
        if (paired) {
            Set<String> keys = new HashSet<>();
            GagWordPairs.read(rules.getJSONObject(PAIRS)).keySet().forEach(word -> keys.add(GagWordPairs.sourceKey(word)));
            for (String word : words.split("\\r\\n|\\r|\\n")) {
                if (keys.contains(GagWordPairs.sourceKey(word)))
                    throw new IllegalArgumentException("A Gag word cannot also use shared replacements");
            }
        }
        if (!(enabled instanceof Boolean) || !("ALL".equals(mode) || "INTERVAL".equals(mode) || "MATCH".equals(mode))
                || interval < 1 || interval > 999
                || Boolean.TRUE.equals(enabled) && ((!matching || hasSharedWords) && replacements.trim().isEmpty()
                        || matching && !hasSharedWords && !paired))
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

    public static boolean pairsSupported(RemoteStore store, RemoteStore.Peer peer) {
        return supported(store, peer) && "1".equals(store.metadata(peer.identity, peer.generation, PAIRS_VERSION, "0"));
    }

    public static Capability capability(RemoteStore store, RemoteStore.Peer peer) {
        if (!supported(store, peer)) return Capability.NONE;
        if (!pairsSupported(store, peer)) return Capability.SHARED;
        return "1".equals(store.metadata(peer.identity, peer.generation, PAIR_OPTIONS_VERSION, "0"))
                ? Capability.PAIR_OPTIONS : Capability.PAIRS;
    }

    private static Capability required(JSONObject rules) {
        if (rules == null) return Capability.NONE;
        if (!needsPairs(rules)) return Capability.SHARED;
        return GagWordPairs.containsOptions(rules.optJSONObject(PAIRS)) ? Capability.PAIR_OPTIONS : Capability.PAIRS;
    }

    public static boolean needsPairs(JSONObject rules) {
        return rules != null && rules.has(PAIRS);
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
        return requiredByBatch(batch) != Capability.NONE;
    }

    static boolean containsPairs(RemoteProtocol.Batch batch) throws JSONException {
        return requiredByBatch(batch).supports(Capability.PAIRS);
    }

    static Capability requiredByBatch(RemoteProtocol.Batch batch) throws JSONException {
        if (batch.stream != RemoteProtocol.Stream.CONFIGURATION && batch.stream != RemoteProtocol.Stream.BASELINE)
            return Capability.NONE;
        Capability required = Capability.NONE;
        for (RemoteProtocol.Change row : batch.changes) {
            if (row.deleted) continue;
            JSONObject body = new JSONObject(row.body);
            JSONObject document = batch.stream == RemoteProtocol.Stream.CONFIGURATION
                    ? body.optJSONObject("configuration") : body;
            JSONObject contract = document == null ? null : document.optJSONObject("contract");
            JSONObject sections = contract == null ? null : contract.optJSONObject("sections");
            Capability candidate = required(sections == null ? null : sections.optJSONObject(SECTION));
            if (candidate == Capability.PAIR_OPTIONS) return candidate;
            if (candidate.supports(required)) required = candidate;
        }
        return required;
    }

    static JSONObject forPeer(JSONObject configuration, boolean supported, boolean editing) throws JSONException {
        return forPeer(configuration, supported, false, editing);
    }

    static JSONObject forPeer(JSONObject configuration, boolean supported, boolean pairsSupported, boolean editing)
            throws JSONException {
        Capability capability = !supported ? Capability.NONE : pairsSupported ? Capability.PAIRS : Capability.SHARED;
        return forPeer(configuration, capability, editing);
    }

    static JSONObject forPeer(JSONObject configuration, Capability capability, boolean editing) throws JSONException {
        if (!capability.supportsPairs(section(configuration)))
            throw new SecurityException(PAIRS_UPDATE_REQUIRED);
        JSONObject copy = new JSONObject(configuration.toString());
        JSONObject sections = copy.getJSONObject("contract").getJSONObject("sections");
        if (capability != Capability.NONE) {
            if (!sections.has(SECTION)) sections.put(SECTION, defaults());
        } else if (sections.has(SECTION)) {
            if (editing && !RemoteConfigurationEdits.same(sections.getJSONObject(SECTION), defaults()))
                throw new SecurityException("Update the paired app to configure Gag");
            sections.remove(SECTION);
        }
        return copy;
    }
}
