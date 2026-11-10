package com.subhub.app.remote;

import android.content.Context;
import com.subhub.app.notifications.NotificationPreferences.Event;
import com.subhub.app.notifications.NotificationPreferences.Role;
import com.subhub.app.notifications.PeerNotifications;
import org.json.JSONException;
import org.json.JSONObject;

/** Bridges applied receiver effects and authenticated application receipts to local presentation. */
final class RemoteAppliedNotifications {
    private RemoteAppliedNotifications() { }
    static boolean changed(JSONObject before, JSONObject after) throws JSONException {
        JSONObject a = normalized(before), b = normalized(after);
        // Export timestamps are bookkeeping. Compare actual feature settings, artwork and session service.
        for (String key : new String[]{"sections", "assets"})
            if (!RemoteProtocol.canonical(a.getJSONObject("contract").get(key)).equals(
                    RemoteProtocol.canonical(b.getJSONObject("contract").get(key)))) return true;
        for (String key : new String[]{"apps", "service"})
            if (!RemoteProtocol.canonical(a.get(key)).equals(RemoteProtocol.canonical(b.get(key)))) return true;
        for (String key : new String[]{"id", "origin", "name", "author", "description", "version"})
            if (!RemoteProtocol.canonical(a.getJSONObject("contract").opt(key)).equals(
                    RemoteProtocol.canonical(b.getJSONObject("contract").opt(key)))) return true;
        return false;
    }
    static JSONObject normalized(JSONObject document) throws JSONException {
        JSONObject result = RemoteConfiguration.active(document);
        JSONObject sections = result.getJSONObject("contract").getJSONObject("sections");
        for (String section : com.subhub.app.configuration.ConfigurationSchema.SECTIONS)
            for (com.subhub.app.configuration.ConfigurationSettingCatalog.Field field
                    : com.subhub.app.configuration.ConfigurationSettingCatalog.fields(section)) {
                if (field.kind != com.subhub.app.configuration.ConfigurationSettingCatalog.Kind.SELECTION) continue;
                org.json.JSONArray values = sections.getJSONObject(section).getJSONArray(field.key);
                java.util.Set<String> sorted = new java.util.TreeSet<>();
                for (int index = 0; index < values.length(); index++) sorted.add(values.getString(index));
                sections.getJSONObject(section).put(field.key, new org.json.JSONArray(sorted));
            }
        result.getJSONObject("apps").put("packages", new org.json.JSONArray(new java.util.TreeSet<>(RemoteConfiguration.selected(result))));
        java.util.Map<String, JSONObject> assets = new java.util.TreeMap<>();
        org.json.JSONArray refs = result.getJSONObject("contract").getJSONArray("assets");
        for (int index = 0; index < refs.length(); index++) assets.put(refs.getJSONObject(index).getString("path"), refs.getJSONObject(index));
        result.getJSONObject("contract").put("assets", new org.json.JSONArray(new java.util.ArrayList<>(assets.values())));
        return result;
    }
    static void configuration(Context context, RemoteStore.Peer peer, long revision,
            JSONObject before, JSONObject after) {
        if (peer.localRole != RemoteProtocol.Role.SUB) return;
        try {
            if (changed(before, after)) PeerNotifications.applied(context, role(peer), Event.CONTRACT_CHANGED, scope(peer), revision, ContractChangeDetails.lines(context, before, after));
        } catch (JSONException | RuntimeException malformed) { /* Presentation cannot fail committed application. */ }
    }
    static void configuration(Context context, RemoteStore.Peer peer, long revision, String before, JSONObject after) {
        try { configuration(context, peer, revision, new JSONObject(before), after); }
        catch (JSONException | RuntimeException malformed) { /* Optional observer only. */ }
    }
    static void nativeSession(Context context, RemoteStore store, String identity, String generation,
            RemoteSessionRuntime.Record record) {
        try {
            RemoteStore.Peer peer = store.peer(identity, generation);
            if (peer != null && peer.state == RemoteStore.State.ACTIVE) session(context, peer, record);
        } catch (RuntimeException unavailable) { /* Include lookup in the optional post-commit boundary. */ }
    }
    static void session(Context context, RemoteStore.Peer peer, RemoteSessionRuntime.Record record) {
        if (peer.localRole != RemoteProtocol.Role.SUB || record.result.state != RemoteSessionCommands.State.APPLIED) return;
        PeerNotifications.applied(context, Role.SUB, record.request.action == RemoteSessionCommands.Action.START
                ? Event.SESSION_STARTED : Event.SESSION_STOPPED, scope(peer), record.request.sequence);
    }
    private static Role role(RemoteStore.Peer peer) { return peer.localRole == RemoteProtocol.Role.DOM ? Role.DOM : Role.SUB; }
    private static String scope(RemoteStore.Peer peer) { return peer.identity + ":" + peer.generation; }
}
