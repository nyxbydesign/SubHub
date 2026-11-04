package com.subhub.app.remote;

import android.content.Context;
import android.content.SharedPreferences;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.notifications.NotificationPreferences.Event;
import com.subhub.app.notifications.NotificationPreferences.Role;
import com.subhub.app.notifications.PeerNotifications;
import com.subhub.app.settings.SettingsRepository;
import org.json.JSONException;
import org.json.JSONObject;

/** One latest, exact-start-owned expiry receipt, independent of advisory runtime snapshots. */
public final class RemoteLifecycleNotifications {
    static final String KEY = "lifecycle:pact-expiry";
    static final String RECEIPT = "notification_pact_expiry_receipt";
    static final String READY = "notification_pact_expiry_ready";
    private RemoteLifecycleNotifications() { }

    /** Called under the runtime lock, before the matching pact is cleared. Shares its checked commit. */
    public static boolean appendExpiry(Context context, SharedPreferences.Editor edit, String pact, String statistics, long sequence) {
        try {
            edit.remove(RECEIPT).remove(READY);
            RemoteSessionNative.Marker marker = RemoteSessionNative.get(context).marker();
            if (marker == null || marker.ended || !marker.statisticsReady
                    || !pact.equals(marker.prepared.pactIdentity) || !statistics.equals(marker.prepared.statistics.identity)) return false;
            RemoteSessionCommands.Request request = marker.prepared.request;
            JSONObject receipt = new JSONObject().put("schema", 1).put("sequence", sequence)
                    .put("dom", request.sender).put("sub", request.recipient).put("generation", request.generation)
                    .put("request", request.id).put("digest", request.digest()).put("pact", pact);
            edit.putString(RECEIPT, RemoteProtocol.canonical(receipt));
            return true;
        } catch (JSONException | RuntimeException unavailable) { return false; }
    }

    /** Called only after historical capture closure and an inactive runtime were observed. */
    public static boolean ready(Context context, long sequence) {
        synchronized (RuntimeSessionIdentity.LOCK) {
            try {
                SharedPreferences main = main(context);
                JSONObject receipt = new JSONObject(main.getString(RECEIPT, "{}"));
                RemoteSessionNative.Marker marker = RemoteSessionNative.get(context).marker();
                if (!valid(receipt) || RuntimeSessionIdentity.observe(context, RemoteSessionClock.local()).active
                        || receipt.getLong("sequence") != sequence || marker == null || !marker.ended || !marker.statisticsReady
                        || !marker.prepared.request.id.equals(receipt.getString("request"))
                        || !marker.prepared.pactIdentity.equals(receipt.getString("pact"))) return false;
                boolean saved = main.edit().putLong(READY, sequence).commit();
                // A refused commit may already have changed in-memory preferences. Fail closed for publication.
                if (!saved) main.edit().remove(READY).commit();
                return saved;
            } catch (JSONException | RuntimeException unavailable) { return false; }
        }
    }

    static void publish(Context context, RemoteStore store, RemoteStore.Peer peer) {
        if (peer.localRole != RemoteProtocol.Role.SUB || peer.state != RemoteStore.State.ACTIVE) return;
        JSONObject receipt = null;
        try {
            synchronized (RuntimeSessionIdentity.LOCK) {
                JSONObject saved = new JSONObject(main(context).getString(RECEIPT, "{}"));
                RemoteSessionNative.Marker marker = RemoteSessionNative.get(context).marker();
                if (valid(saved) && saved.getString("dom").equals(peer.identity) && saved.getString("generation").equals(peer.generation)
                        && main(context).getLong(READY, 0) == saved.getLong("sequence")
                        && !RuntimeSessionIdentity.observe(context, RemoteSessionClock.local()).active
                        && marker != null && marker.ended && marker.statisticsReady
                        && marker.prepared.pactIdentity.equals(saved.getString("pact"))
                        && marker.prepared.request.id.equals(saved.getString("request"))
                        && marker.prepared.request.digest().equals(saved.getString("digest"))) receipt = saved;
            }
            if (receipt != null) store.publish(peer.identity, peer.generation, RemoteProtocol.Stream.RUNTIME, KEY, receipt, false, 1);
            else for (RemoteProtocol.Change old : store.published(peer.identity, peer.generation, RemoteProtocol.Stream.RUNTIME))
                if (KEY.equals(old.key) && !old.deleted)
                    store.publish(peer.identity, peer.generation, RemoteProtocol.Stream.RUNTIME, KEY, new JSONObject(), true, 1);
        } catch (JSONException | RuntimeException unavailable) { /* Optional telemetry cannot fail synchronization. */ }
    }

    static boolean accept(Context context, RemoteStore store, RemoteStore.Peer supplied, JSONObject receipt) {
        try {
            synchronized (store) {
                RemoteStore.Peer peer = store.peer(supplied.identity, supplied.generation);
                if (peer == null || peer.state != RemoteStore.State.ACTIVE || peer.localRole != RemoteProtocol.Role.DOM
                        || !valid(receipt) || !peer.identity.equals(receipt.getString("sub"))
                        || !peer.generation.equals(receipt.getString("generation"))) return false;
                String remembered = store.metadata(peer.identity, peer.generation, "notification_applied_session", "");
                RemoteSessionCommands.Request request;
                RemoteSessionCommands.Result result;
                if (!remembered.isEmpty()) {
                    JSONObject known = new JSONObject(remembered);
                    request = RemoteSessionCommands.Request.parse(known.getJSONObject("request"));
                    result = RemoteSessionCommands.Result.parse(request, known.getJSONObject("result"));
                } else {
                    RemoteSessionRuntime.Record known = store.sessionControl(peer.identity, peer.generation,
                            RemoteSessionRuntime.Direction.OUTGOING, receipt.getString("request"));
                    if (known == null) return false;
                    request = known.request; result = known.result;
                }
                if (!matches(receipt, request, result)) return false;
                String scope = peer.identity + ":" + peer.generation + ":pact-expiry";
                PeerNotifications.applied(context, Role.DOM, Event.SESSION_STOPPED, scope, receipt.getLong("sequence"));
                return true;
            }
        } catch (JSONException | RuntimeException malformed) { return false; }
    }
    static boolean matches(JSONObject receipt, RemoteSessionCommands.Request request, RemoteSessionCommands.Result result) throws JSONException {
        return valid(receipt) && request.action == RemoteSessionCommands.Action.START
                && result.state == RemoteSessionCommands.State.APPLIED
                && receipt.getString("dom").equals(request.sender) && receipt.getString("sub").equals(request.recipient)
                && receipt.getString("generation").equals(request.generation)
                && receipt.getString("request").equals(request.id) && receipt.getString("digest").equals(request.digest());
    }
    static boolean valid(JSONObject receipt) {
        try {
            RemoteSessionCommands.keys(receipt, "schema", "sequence", "dom", "sub", "generation", "request", "digest", "pact");
            if (RemoteProtocol.integer(receipt, "schema") != 1 || RemoteProtocol.integer(receipt, "sequence") <= 0) return false;
            RemoteProtocol.peerId(RemoteSessionCommands.text(receipt, "dom"));
            RemoteProtocol.peerId(RemoteSessionCommands.text(receipt, "sub"));
            RemoteProtocol.identifier(RemoteSessionCommands.text(receipt, "generation"));
            RemoteProtocol.identifier(RemoteSessionCommands.text(receipt, "request"));
            RemoteSessionCommands.digest(RemoteSessionCommands.text(receipt, "digest"));
            java.util.UUID.fromString(RemoteSessionCommands.text(receipt, "pact"));
            return !receipt.getString("dom").equals(receipt.getString("sub"));
        } catch (JSONException | RuntimeException malformed) { return false; }
    }
    private static SharedPreferences main(Context context) { return context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0); }
}
