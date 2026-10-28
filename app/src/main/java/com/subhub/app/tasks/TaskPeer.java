package com.subhub.app.tasks;

import android.content.Context;
import com.subhub.app.remote.*;
import com.subhub.app.security.SubFeaturePolicy;
import org.json.*;
import java.io.IOException;
import java.util.*;

/** Capability-negotiated task actions and private media share the authenticated pairing connection. */
public final class TaskPeer {
    private static final int MAX_WANTS = 16, MAX_CHUNKS = 64;
    private final TaskRuntime runtime;
    private final TaskRepository.Target target;
    private final PeerTransport.Connection connection;
    private final long deadline;
    private TaskPeer(Context context, PeerTransport.Connection connection, RemoteStore.Peer peer, long deadline) {
        runtime = TaskRuntime.get(context); target = new TaskRepository.Target(peer.identity + "_" + peer.generation, peer);
        this.connection = connection; this.deadline = deadline;
        if (!peer.identity.equals(connection.authenticatedPeerIdentity())) throw new SecurityException("Task peer mismatch");
    }
    public static void exchange(Context context, PeerTransport.Connection connection, RemoteStore.Peer peer, long deadline) throws Exception {
        new TaskPeer(context, connection, peer, deadline).exchange();
    }
    private void exchange() throws Exception {
        runtime.sweep(target);
        JSONObject status = frame("tasks_status").put("allowed", runtime.enabled(target))
                .put("received", runtime.enabled(target) ? runtime.store.received(target.scope) : 0);
        JSONObject remote;
        boolean dom = target.peer.localRole == RemoteProtocol.Role.DOM;
        if (dom) { send(status); remote = read("tasks_status"); }
        else { remote = read("tasks_status"); send(status); }
        boolean remoteAllowed = TaskJson.bool(remote, "allowed");
        if (dom) synchronized (runtime.authorityLock()) {
            runtime.requireActive(target);
            runtime.authorityLock().taskConsentReceived(target.peer.identity, target.peer.generation, remoteAllowed);
        }
        boolean enabled = remoteAllowed && runtime.enabled(target);
        if (!enabled) return; // Neither side reads, writes or acknowledges the private journal while disabled.
        long remoteThrough = RemoteProtocol.integer(remote, "received");
        if (dom) { push(remoteThrough); pull(); }
        else { pull(); push(remoteThrough); }
        synchronized (SubFeaturePolicy.LOCK) { check(); runtime.effects(target); }
    }
    private void push(long through) throws Exception {
        JSONArray actions;
        synchronized (SubFeaturePolicy.LOCK) { check(); actions = runtime.store.outgoing(target.scope, through); }
        send(frame("tasks_actions").put("actions", actions));
        JSONObject receipt = read("tasks_receipt");
        long expected = actions.length() == 0 ? through : RemoteProtocol.integer(actions.getJSONObject(actions.length() - 1), "seq");
        if (RemoteProtocol.integer(receipt, "received") != expected) throw new SecurityException("Unmatched task receipt");
        // The next status repeats this receipt after reconnect, including a lost final acknowledgement.
        JSONArray wants = receipt.getJSONArray("assets");
        if (wants.length() > MAX_WANTS) throw new IOException("Too many task attachments requested");
        Map<String, TaskAttachment> references = TaskMedia.published(runtime.store.load(target.scope), target.peer.localRole == RemoteProtocol.Role.DOM);
        Set<String> selected = new HashSet<>(); int chunks = 0;
        for (int i = 0; i < wants.length(); i++) {
            JSONObject request = wants.getJSONObject(i);
            String hash = TaskJson.text(request, "hash", 64);
            TaskAttachment reference = references.get(hash);
            long offset = RemoteProtocol.integer(request, "offset");
            if (reference == null || !selected.add(hash) || RemoteProtocol.integer(request, "length") != reference.length
                    || offset < 0 || offset >= reference.length) throw new SecurityException("Unreferenced task media request");
            RemoteAssets assets = runtime.assets(target.scope);
            if (!assets.isComplete(hash)) continue;
            while (offset < reference.length && chunks < MAX_CHUNKS && remaining() > 1500) {
                byte[] bytes;
                synchronized (SubFeaturePolicy.LOCK) { check(); bytes = assets.chunk(hash, offset); }
                send(frame("tasks_chunk").put("hash", hash).put("offset", offset).put("data", Base64.getEncoder().encodeToString(bytes)));
                JSONObject ack = read("tasks_chunk_receipt");
                if (!hash.equals(TaskJson.text(ack, "hash", 64)) || RemoteProtocol.integer(ack, "offset") != offset + bytes.length)
                    throw new SecurityException("Unmatched task attachment receipt");
                offset += bytes.length; chunks++;
            }
        }
        send(frame("tasks_assets_complete")); read("tasks_phase_complete");
    }
    private void pull() throws Exception {
        JSONObject incoming = read("tasks_actions"); long through;
        Map<String, TaskAttachment> needs = new LinkedHashMap<>(); JSONArray wants = new JSONArray();
        synchronized (SubFeaturePolicy.LOCK) {
            synchronized (runtime.authorityLock()) {
                check();
                through = runtime.store.receive(target.scope, incoming.getJSONArray("actions"), target.peer.localRole == RemoteProtocol.Role.SUB,
                        target.peer.localRole == RemoteProtocol.Role.DOM);
                RemoteAssets assets = runtime.assets(target.scope);
                for (TaskAttachment attachment : TaskMedia.published(runtime.store.load(target.scope), target.peer.localRole == RemoteProtocol.Role.SUB).values()) {
                    if (needs.size() == MAX_WANTS) break;
                    assets.reserve(attachment.hash, attachment.length);
                    if (assets.isComplete(attachment.hash)) continue;
                    long offset = assets.offset(attachment.hash);
                    // A process may have stopped after the last chunk, before finish().
                    if (offset == attachment.length) {
                        TaskMedia.requireHeader(assets.finish(attachment.hash), attachment.mime); continue;
                    }
                    needs.put(attachment.hash, attachment);
                    wants.put(new JSONObject().put("hash", attachment.hash).put("length", attachment.length).put("offset", offset));
                }
            }
        }
        send(frame("tasks_receipt").put("received", through).put("assets", wants));
        int chunks = 0;
        while (true) {
            JSONObject message = read(null);
            if ("tasks_assets_complete".equals(message.getString("type"))) break;
            if (!"tasks_chunk".equals(message.getString("type")) || ++chunks > MAX_CHUNKS) throw new IOException("Invalid Tasks media phase");
            String hash = TaskJson.text(message, "hash", 64);
            TaskAttachment attachment = needs.get(hash);
            if (attachment == null) throw new SecurityException("Unrequested task attachment");
            String encoded = TaskJson.text(message, "data", (RemoteAssets.CHUNK_BYTES + 2) / 3 * 4);
            byte[] bytes = Base64.getDecoder().decode(encoded); long offset;
            synchronized (SubFeaturePolicy.LOCK) {
                synchronized (runtime.authorityLock()) {
                    check(); RemoteAssets assets = runtime.assets(target.scope);
                    long actual = assets.offset(hash);
                    if (RemoteProtocol.integer(message, "offset") != actual) throw new IOException("Unexpected task media offset");
                    offset = assets.append(hash, actual, bytes);
                    if (offset == attachment.length) TaskMedia.requireHeader(assets.finish(hash), attachment.mime);
                }
            }
            send(frame("tasks_chunk_receipt").put("hash", hash).put("offset", offset));
        }
        send(frame("tasks_phase_complete"));
    }
    private void check() {
        runtime.requireActive(target);
        if (!runtime.enabled(target)) throw new SecurityException("Tasks disabled by Sub");
    }
    private JSONObject frame(String type) throws JSONException {
        return new JSONObject().put("version", 1).put("tasksVersion", 1).put("type", type).put("generation", target.peer.generation);
    }
    private void send(JSONObject value) throws Exception {
        synchronized (SubFeaturePolicy.LOCK) {
            synchronized (runtime.authorityLock()) {
                runtime.requireActive(target); connection.setReadTimeoutMillis(Math.min(10_000, remaining()));
                if (!"tasks_status".equals(value.getString("type"))) check();
                RemoteProtocol.write(connection.output(), value);
            }
        }
    }
    private JSONObject read(String type) throws Exception {
        runtime.requireActive(target); connection.setReadTimeoutMillis(Math.min(10_000, remaining()));
        JSONObject value = RemoteProtocol.read(connection.input());
        if (RemoteProtocol.integer(value, "version") != RemoteProtocol.VERSION
                || RemoteProtocol.integer(value, "tasksVersion") != 1 || !target.peer.generation.equals(value.getString("generation"))
                || (type != null && !type.equals(value.getString("type")))) throw new SecurityException("Invalid task peer frame");
        return value;
    }
    private long remaining() throws IOException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new IOException("Tasks sync deadline expired");
        return Math.max(1, nanos / 1_000_000);
    }
}
