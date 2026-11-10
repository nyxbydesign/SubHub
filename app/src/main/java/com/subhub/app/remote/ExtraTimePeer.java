package com.subhub.app.remote;

import android.content.Context;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.json.JSONObject;

/** A negotiated, acknowledged phase on the existing authenticated pairing connection. */
final class ExtraTimePeer {
    static final int VERSION = 1;
    static final int MAX_SNAPSHOT_BYTES = 128 * 1024;

    interface Endpoint {
        JSONObject snapshot() throws Exception;
        /** Returns only after the validated incoming state is durably committed. */
        void receive(JSONObject snapshot) throws Exception;
        boolean enabled();
        void requireActive();
    }

    private final Endpoint endpoint;
    private final PeerTransport.Connection connection;
    private final RemoteStore.Peer peer;
    private final long deadline;

    private ExtraTimePeer(Endpoint endpoint, PeerTransport.Connection connection, RemoteStore.Peer peer, long deadline) {
        this.endpoint = Objects.requireNonNull(endpoint);
        this.connection = Objects.requireNonNull(connection);
        this.peer = Objects.requireNonNull(peer);
        this.deadline = deadline;
        check(false);
    }

    static void exchange(Context context, RemoteStore store, String localIdentity,
            PeerTransport.Connection connection, RemoteStore.Peer peer, long deadline) throws Exception {
        exchange(ExtraTimeRequests.endpoint(context, store, localIdentity, peer), connection, peer, deadline);
    }

    static void exchange(Endpoint endpoint, PeerTransport.Connection connection, RemoteStore.Peer peer, long deadline) throws Exception {
        new ExtraTimePeer(endpoint, connection, peer, deadline).exchange();
    }

    private void exchange() throws Exception {
        boolean dom = peer.localRole == RemoteProtocol.Role.DOM;
        JSONObject local = frame("extra_time_status").put("allowed", endpoint.enabled());
        JSONObject remote;
        if (dom) { send(local, false); remote = read("extra_time_status", false); }
        else { remote = read("extra_time_status", false); send(local, false); }
        if (!(remote.get("allowed") instanceof Boolean)) throw new SecurityException("Invalid extra-time availability");
        if (!local.getBoolean("allowed") || !remote.getBoolean("allowed")) return;
        check(true);
        if (dom) {
            push(); pull();
            send(frame("extra_time_complete"), true);
            read("extra_time_complete_ack", true);
        } else {
            pull(); push();
            read("extra_time_complete", true);
            send(frame("extra_time_complete_ack"), true);
        }
    }

    private void push() throws Exception {
        check(true);
        JSONObject value = endpoint.snapshot();
        check(true);
        String digest = digest(value);
        send(frame("extra_time_state").put("state", value), true);
        JSONObject receipt = read("extra_time_receipt", true);
        if (!digest.equals(receipt.getString("digest"))) throw new SecurityException("Unmatched extra-time receipt");
    }

    private void pull() throws Exception {
        JSONObject value = read("extra_time_state", true).getJSONObject("state");
        String digest = digest(value);
        check(true);
        endpoint.receive(value);
        check(true);
        send(frame("extra_time_receipt").put("digest", digest), true);
    }

    private static String digest(JSONObject value) throws Exception {
        byte[] bytes = RemoteProtocol.canonical(Objects.requireNonNull(value)).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_SNAPSHOT_BYTES) throw new IOException("Extra-time state too large");
        return RemoteProtocol.sha256(bytes);
    }

    private JSONObject frame(String type) throws Exception {
        return new JSONObject().put("version", RemoteProtocol.VERSION).put("extraTimeVersion", VERSION)
                .put("type", type).put("generation", peer.generation);
    }

    private void send(JSONObject value, boolean enabled) throws Exception {
        check(enabled);
        connection.setReadTimeoutMillis(remaining());
        RemoteProtocol.write(connection.output(), value);
    }

    private JSONObject read(String type, boolean enabled) throws Exception {
        check(enabled);
        connection.setReadTimeoutMillis(remaining());
        JSONObject value = RemoteProtocol.read(connection.input());
        check(enabled);
        if (RemoteProtocol.integer(value, "version") != RemoteProtocol.VERSION
                || RemoteProtocol.integer(value, "extraTimeVersion") != VERSION
                || !peer.generation.equals(value.getString("generation")) || !type.equals(value.getString("type")))
            throw new SecurityException("Invalid extra-time frame");
        return value;
    }

    private void check(boolean enabled) {
        endpoint.requireActive();
        if (!peer.identity.equals(connection.authenticatedPeerIdentity())) throw new SecurityException("Extra-time peer mismatch");
        if (enabled && !endpoint.enabled()) throw new SecurityException("Extra-time requests unavailable");
    }

    private long remaining() throws IOException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new IOException("Extra-time phase deadline expired");
        return Math.min(10_000L, Math.max(1L, nanos / 1_000_000L));
    }
}
