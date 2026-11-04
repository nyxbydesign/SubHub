package com.subhub.app.remote;

import android.content.Context;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.security.SubFeaturePolicy;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Optional authenticated exchange. It has no production hook until independently negotiated. */
final class RemoteSessionControlPeer {
    private static final int MAX_COMMANDS = 16, COMMAND_BYTES = 64 * 1024, RESULT_BYTES = 32 * 1024;
    interface StateSource { RemoteSessionControlState observe(RemoteStore.Peer peer) throws Exception; }
    private final Context context;
    private final RemoteStore store;
    private final String self;
    private final StateSource stateSource;
    private final RemoteSessionNativeController controller;

    RemoteSessionControlPeer(Context context, RemoteStore store, String receiverIdentity, StateSource stateSource) {
        this(context, store, receiverIdentity, stateSource, null);
    }
    RemoteSessionControlPeer(Context context, RemoteStore store, String receiverIdentity, StateSource stateSource,
            RemoteSessionNativeController controller) {
        this.context = context.getApplicationContext(); this.store = Objects.requireNonNull(store);
        this.self = RemoteProtocol.peerId(receiverIdentity); this.stateSource = stateSource; this.controller = controller;
    }

    void exchange(PeerTransport.Connection connection, RemoteStore.Peer peer, long deadline) throws Exception {
        outsideLocks(); require(connection, peer);
        if (peer.localRole == RemoteProtocol.Role.DOM) dom(connection, peer, deadline);
        else sub(connection, peer, deadline);
    }
    private void dom(PeerTransport.Connection connection, RemoteStore.Peer peer, long deadline) throws Exception {
        List<RemoteSessionRuntime.Record> pending;
        List<RemoteSessionControlAck> acknowledgments;
        RemoteSessionClock.Sample clock = RemoteSessionClock.observe(context);
        synchronized (store) {
            android.database.sqlite.SQLiteDatabase db = store.getWritableDatabase(); db.beginTransaction();
            try {
                requireCurrent(peer);
                pending = store.pendingSessionControls(peer.identity, peer.generation,
                        RemoteSessionRuntime.Direction.OUTGOING, MAX_COMMANDS);
                for (RemoteSessionRuntime.Record record : pending) {
                    store.protectSessionControlOffer(peer.identity, peer.generation, record.request.id);
                    store.checkSessionControlClock(peer.identity, peer.generation, record.direction, record.request.id, clock);
                }
                acknowledgments = store.pendingSessionControlAcks(peer.identity, peer.generation);
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
        if (pending.size() + acknowledgments.size() > MAX_COMMANDS) throw new SecurityException("Too many unresolved commands");
        pending.sort(Comparator.comparingLong(record -> record.request.sequence));
        List<RemoteSessionCommands.Request> requests = new ArrayList<>(); JSONArray commands = new JSONArray(), reconcile = new JSONArray();
        for (RemoteSessionRuntime.Record record : pending) {
            if (!self.equals(record.request.sender) || !peer.identity.equals(record.request.recipient)
                    || !peer.generation.equals(record.request.generation)) throw new SecurityException("Local command scope changed");
            requests.add(record.request); commands.put(record.request.json());
            if (record.lifetime.check(record.request, clock) != RemoteSessionCommands.Reason.NONE) reconcile.put(record.request.id);
        }
        JSONArray acks = new JSONArray(); for (RemoteSessionControlAck ack : acknowledgments) acks.put(ack.json());
        String ackDigest = batchDigest(acks), digest = commandDigest(commands, acks, reconcile);
        send(connection, peer, frame("session_control_commands", peer).put("batchDigest", digest).put("commands", commands)
                .put("acks", acks).put("reconcile", reconcile), deadline, COMMAND_BYTES);
        applyResults(peer, requests, requireFrame(read(connection, peer, deadline, RESULT_BYTES), "session_control_receipts", peer,
                "batchDigest", "results", "resultDigest", "ackDigest"), digest, ackDigest, acknowledgments);
        JSONObject finalFrame = requireFrame(read(connection, peer, deadline, RESULT_BYTES), "session_control_results", peer,
                "batchDigest", "results", "resultDigest", "ackDigest");
        applyResults(peer, requests, finalFrame, digest, ackDigest, java.util.Collections.emptyList());
        // Application results are already durable before an unrelated Start-readiness snapshot is attempted.
        JSONObject stateFrame = requireFrame(read(connection, peer, deadline, RemoteSessionControlState.MAX_BYTES + 512),
                "session_control_state", peer, "state");
        RemoteSessionControlState state = RemoteSessionControlState.parse(stateFrame.getJSONObject("state"));
        if (!peer.identity.equals(state.sender) || !self.equals(state.recipient) || !peer.generation.equals(state.generation))
            throw new SecurityException("Session state scope changed");
        RemoteSessionControlState.Received received = new RemoteSessionControlState.Received(state, RemoteSessionClock.observe(context));
        synchronized (store) {
            requireCurrent(peer);
            store.putMetadata(peer.identity, peer.generation, RemoteSessionControlState.METADATA, RemoteProtocol.canonical(received.json()));
        }
        send(connection, peer, frame("session_control_complete", peer).put("batchDigest", digest)
                .put("resultDigest", finalFrame.getString("resultDigest")), deadline, 1024);
    }
    private void sub(PeerTransport.Connection connection, RemoteStore.Peer peer, long deadline) throws Exception {
        JSONObject commandFrame = requireFrame(read(connection, peer, deadline, COMMAND_BYTES), "session_control_commands", peer,
                "batchDigest", "commands", "acks", "reconcile");
        JSONArray commands = commandFrame.getJSONArray("commands");
        JSONArray acks = commandFrame.getJSONArray("acks");
        JSONArray reconcile = commandFrame.getJSONArray("reconcile");
        String digest = RemoteSessionCommands.text(commandFrame, "batchDigest"); RemoteSessionCommands.digest(digest);
        if (commands.length() + acks.length() > MAX_COMMANDS || reconcile.length() > commands.length()
                || !digest.equals(commandDigest(commands, acks, reconcile)))
            throw new JSONException("Invalid command batch");
        List<RemoteSessionCommands.Request> requests = new ArrayList<>(); HashSet<String> ids = new HashSet<>(); long sequence = 0;
        for (int index = 0; index < commands.length(); index++) {
            RemoteSessionCommands.Request request = RemoteSessionCommands.Request.parse(commands.getJSONObject(index));
            if (!peer.identity.equals(request.sender) || !self.equals(request.recipient) || !peer.generation.equals(request.generation)
                    || request.sequence <= sequence || !ids.add(request.id)) throw new SecurityException("Session command scope or order changed");
            sequence = request.sequence; requests.add(request);
        }
        HashSet<String> queryIds = new HashSet<>();
        for (int index = 0; index < reconcile.length(); index++) {
            Object value = reconcile.get(index);
            if (!(value instanceof String) || !ids.contains((String) value) || !queryIds.add((String) value))
                throw new JSONException("Invalid reconciliation subset");
        }
        List<RemoteSessionControlAck> parsedAcks = new ArrayList<>();
        for (int index = 0; index < acks.length(); index++) {
            RemoteSessionControlAck ack = RemoteSessionControlAck.parse(acks.getJSONObject(index));
            if (!ids.add(ack.id)) throw new SecurityException("Repeated command or acknowledgment");
            parsedAcks.add(ack);
        }
        // Decode and observe clocks outside authority locks. The whole packet commits before any echo or application.
        RemoteSessionClock.Sample clock = RemoteSessionClock.observe(context);
        require(connection, peer);
        JSONArray receipts = new JSONArray();
        synchronized (store) {
            android.database.sqlite.SQLiteDatabase db = store.getWritableDatabase(); db.beginTransaction();
            try {
                requireCurrent(peer);
                for (RemoteSessionControlAck ack : parsedAcks) store.acknowledgeSessionControl(peer.identity, peer.generation, ack);
                for (RemoteSessionCommands.Request request : requests)
                    receipts.put((queryIds.contains(request.id)
                            ? store.reconcileSessionControlReceipt(peer.identity, peer.generation, request, clock)
                            : store.rememberSessionControl(peer.identity, peer.generation, RemoteSessionRuntime.Direction.INCOMING,
                                    request, clock)).result.json());
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
        String ackDigest = batchDigest(acks);
        send(connection, peer, resultFrame("session_control_receipts", peer, digest, ackDigest, receipts), deadline, RESULT_BYTES);
        RemoteSessionNativeController nativeController = controller == null
                ? new RemoteSessionNativeController(context, store, self) : controller;
        JSONArray results = new JSONArray();
        for (RemoteSessionCommands.Request request : requests) {
            remaining(deadline); require(connection, peer);
            results.put((queryIds.contains(request.id)
                    ? nativeController.reconcile(peer.identity, peer.generation, request.id)
                    : nativeController.advance(peer.identity, peer.generation, request.id)).result.json());
        }
        send(connection, peer, resultFrame("session_control_results", peer, digest, ackDigest, results), deadline, RESULT_BYTES);
        if (stateSource == null) throw new IllegalStateException("Receiver session state provider required");
        RemoteSessionControlState state = Objects.requireNonNull(stateSource.observe(peer));
        if (!self.equals(state.sender) || !peer.identity.equals(state.recipient) || !peer.generation.equals(state.generation))
            throw new SecurityException("Local state scope changed");
        send(connection, peer, frame("session_control_state", peer).put("state", state.json()), deadline, RemoteSessionControlState.MAX_BYTES + 512);
        JSONObject complete = requireFrame(read(connection, peer, deadline, 1024), "session_control_complete", peer, "batchDigest", "resultDigest");
        if (!digest.equals(RemoteSessionCommands.text(complete, "batchDigest"))
                || !batchDigest(results).equals(RemoteSessionCommands.text(complete, "resultDigest")))
            throw new JSONException("Unmatched session completion");
        synchronized (store) {
            android.database.sqlite.SQLiteDatabase db = store.getWritableDatabase(); db.beginTransaction();
            try {
                requireCurrent(peer);
                for (int index = 0; index < requests.size(); index++) {
                    RemoteSessionCommands.Result sent = RemoteSessionCommands.Result.parse(requests.get(index), results.getJSONObject(index));
                    if (sent.state.terminal()) store.acknowledgeSessionControl(peer.identity, peer.generation,
                            new RemoteSessionControlAck(sent.id, requests.get(index).sequence, sent.requestDigest,
                                    RemoteSessionControlAck.digest(sent)));
                }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
    }
    private void applyResults(RemoteStore.Peer peer, List<RemoteSessionCommands.Request> requests, JSONObject frame, String digest,
            String ackDigest, List<RemoteSessionControlAck> acknowledged)
            throws JSONException {
        if (!digest.equals(RemoteSessionCommands.text(frame, "batchDigest"))) throw new JSONException("Unmatched session result batch");
        JSONArray results = frame.getJSONArray("results");
        if (results.length() != requests.size() || !batchDigest(results).equals(RemoteSessionCommands.text(frame, "resultDigest"))
                || !ackDigest.equals(RemoteSessionCommands.text(frame, "ackDigest"))) throw new JSONException("Incomplete session results or acknowledgment");
        List<RemoteSessionCommands.Result> parsed = new ArrayList<>();
        for (int index = 0; index < results.length(); index++)
            parsed.add(RemoteSessionCommands.Result.parse(requests.get(index), results.getJSONObject(index)));
        List<RemoteSessionRuntime.Record> applied = new ArrayList<>();
        synchronized (store) {
            android.database.sqlite.SQLiteDatabase db = store.getWritableDatabase(); db.beginTransaction();
            try {
                requireCurrent(peer);
                for (RemoteSessionControlAck ack : acknowledged) store.clearSessionControlAck(peer.identity, peer.generation, ack);
                for (int index = 0; index < parsed.size(); index++) {
                    RemoteSessionRuntime.Record before = store.sessionControl(peer.identity, peer.generation,
                            RemoteSessionRuntime.Direction.OUTGOING, requests.get(index).id);
                    RemoteSessionRuntime.Record after = store.advanceSessionControl(peer.identity, peer.generation,
                            RemoteSessionRuntime.Direction.OUTGOING, requests.get(index).id, parsed.get(index).json());
                    if (before != null && before.result.state != RemoteSessionCommands.State.APPLIED
                            && after.result.state == RemoteSessionCommands.State.APPLIED) applied.add(after);
                    if (parsed.get(index).state.terminal()) store.retainSessionControlAck(peer.identity, peer.generation, requests.get(index).id);
                }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
        for (RemoteSessionRuntime.Record record : applied) RemoteAppliedNotifications.session(context, store, peer, record);
    }
    private void require(PeerTransport.Connection connection, RemoteStore.Peer peer) {
        if (peer == null || peer.identity.equals(self) || !peer.identity.equals(connection.authenticatedPeerIdentity()))
            throw new SecurityException("Authenticated session peer required");
        requireCurrent(peer);
    }
    private void requireCurrent(RemoteStore.Peer peer) {
        RemoteStore.Peer current = store.peer(peer.identity, peer.generation);
        if (current == null || current.state != RemoteStore.State.ACTIVE || current.localRole != peer.localRole
                || !"1".equals(store.metadata(peer.identity, peer.generation, "session_control_version", "0")))
            throw new SecurityException("Session capability or relationship changed");
    }
    private void outsideLocks() {
        if (Thread.holdsLock(store) || Thread.holdsLock(SubFeaturePolicy.LOCK) || Thread.holdsLock(RuntimeSessionIdentity.LOCK))
            throw new IllegalStateException("Session exchange must run outside authority locks");
    }
    private void send(PeerTransport.Connection connection, RemoteStore.Peer peer, JSONObject frame, long deadline, int maximum) throws Exception {
        outsideLocks(); require(connection, peer); bounded(frame, maximum);
        connection.setReadTimeoutMillis(Math.min(10_000, remaining(deadline))); RemoteProtocol.write(connection.output(), frame);
    }
    private JSONObject read(PeerTransport.Connection connection, RemoteStore.Peer peer, long deadline, int maximum) throws Exception {
        outsideLocks(); require(connection, peer); connection.setReadTimeoutMillis(Math.min(10_000, remaining(deadline)));
        JSONObject value = RemoteProtocol.read(connection.input()); bounded(value, maximum); return value;
    }
    private static JSONObject frame(String type, RemoteStore.Peer peer) throws JSONException {
        return new JSONObject().put("version", 1).put("type", type).put("generation", peer.generation);
    }
    private static JSONObject requireFrame(JSONObject value, String type, RemoteStore.Peer peer, String... fields) throws JSONException {
        String[] keys = new String[fields.length + 3]; keys[0] = "version"; keys[1] = "type"; keys[2] = "generation";
        System.arraycopy(fields, 0, keys, 3, fields.length); RemoteSessionCommands.keys(value, keys); RemoteProtocol.requireVersion(value);
        if (!type.equals(RemoteSessionCommands.text(value, "type")) || !peer.generation.equals(RemoteSessionCommands.text(value, "generation")))
            throw new JSONException("Unexpected session control frame");
        return value;
    }
    private static String batchDigest(JSONArray commands) {
        return RemoteProtocol.sha256(RemoteProtocol.canonical(commands).getBytes(StandardCharsets.UTF_8));
    }
    private static String commandDigest(JSONArray commands, JSONArray acks, JSONArray reconcile) throws JSONException {
        return RemoteProtocol.sha256(RemoteProtocol.canonical(new JSONObject().put("commands", commands).put("acks", acks).put("reconcile", reconcile))
                .getBytes(StandardCharsets.UTF_8));
    }
    private static JSONObject resultFrame(String type, RemoteStore.Peer peer, String digest, String ackDigest, JSONArray results)
            throws JSONException {
        return frame(type, peer).put("batchDigest", digest).put("ackDigest", ackDigest)
                .put("results", results).put("resultDigest", batchDigest(results));
    }
    private static void bounded(JSONObject frame, int maximum) throws IOException {
        if (RemoteProtocol.canonical(frame).getBytes(StandardCharsets.UTF_8).length > maximum) throw new IOException("Session frame too large");
    }
    private static long remaining(long deadline) throws SocketTimeoutException {
        long left = deadline - System.nanoTime(); if (left <= 0) throw new SocketTimeoutException("Session exchange expired");
        return Math.max(1, left / 1_000_000);
    }
}
