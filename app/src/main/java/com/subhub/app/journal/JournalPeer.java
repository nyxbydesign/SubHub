package com.subhub.app.journal;

import com.subhub.app.remote.PeerTransport;
import com.subhub.app.remote.RemoteAssets;
import com.subhub.app.remote.RemoteProtocol;
import com.subhub.app.remote.RemoteStore;
import java.io.IOException;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** A separate negotiated duplex phase carries only explicitly shared Journal entries and media. */
public final class JournalPeer {
    private static final int MAX_WANTS = 16, MAX_CHUNKS = 64;
    private final JournalRuntime runtime;
    private final RemoteStore.Peer peer;
    private final PeerTransport.Connection connection;
    private final long deadline;
    private final String scope;

    private JournalPeer(JournalRuntime runtime, PeerTransport.Connection connection, RemoteStore.Peer peer, long deadline) {
        this.runtime = runtime; this.connection = connection; this.peer = peer; this.deadline = deadline;
        scope = JournalRuntime.scope(peer);
    }

    public static void exchange(JournalRuntime runtime, PeerTransport.Connection connection,
            RemoteStore.Peer peer, long deadline) throws Exception {
        new JournalPeer(runtime, connection, peer, deadline).exchange();
    }

    private void exchange() throws Exception {
        boolean dom = peer.localRole == RemoteProtocol.Role.DOM;
        JSONObject status = frame("journal_status").put("received", runtime.entries.received(scope));
        JSONObject remote;
        if (dom) { send(status); remote = read("journal_status"); }
        else { remote = read("journal_status"); send(status); }
        long after = RemoteProtocol.integer(remote, "received");
        if (dom) { push(after); pull(); }
        else { pull(); push(after); }
    }

    private void push(long after) throws Exception {
        JSONArray changes;
        synchronized (runtime.authority) {
            check();
            changes = runtime.entries.outgoing(scope, after);
            send(frame("journal_changes").put("changes", changes));
        }
        JSONObject receipt = read("journal_receipt");
        long through = changes.length() == 0 ? after : RemoteProtocol.integer(changes.getJSONObject(changes.length() - 1), "seq");
        if (RemoteProtocol.integer(receipt, "received") != through) throw new SecurityException("Unmatched journal receipt");
        JSONArray wants = receipt.getJSONArray("assets");
        if (wants.length() > MAX_WANTS) throw new IOException("Too many journal asset requests");
        Set<String> selected = new HashSet<>(); int chunks = 0;
        for (int i = 0; i < wants.length(); i++) {
            JSONObject request = wants.getJSONObject(i);
            JournalEntry.keys(request, Set.of("hash", "length", "offset"));
            String hash = JournalEntry.text(request, "hash");
            long offset = RemoteProtocol.integer(request, "offset"), length = RemoteProtocol.integer(request, "length");
            synchronized (runtime.authority) {
                check(); JournalAttachment reference = runtime.published(peer).get(hash);
                if (reference == null || reference.length != length || !selected.add(hash) || offset < 0 || offset >= length)
                    throw new SecurityException("Unreferenced journal asset");
            }
            while (offset < length && chunks < MAX_CHUNKS) {
                synchronized (runtime.authority) {
                    check(); JournalAttachment reference = runtime.published(peer).get(hash);
                    if (reference == null || reference.length != length) throw new SecurityException("Journal sharing changed");
                    byte[] bytes = runtime.ownAssets(peer).chunk(hash, offset);
                    send(frame("journal_asset").put("hash", hash).put("offset", offset)
                            .put("data", Base64.getEncoder().encodeToString(bytes)));
                    offset += bytes.length; chunks++;
                }
            }
        }
        send(frame("journal_assets_end"));
    }

    private void pull() throws Exception {
        JSONObject page = read("journal_changes");
        JSONArray wants = new JSONArray(); Map<String, JournalAttachment> needed = new LinkedHashMap<>();
        long through;
        synchronized (runtime.authority) {
            check(); through = runtime.entries.receive(scope, page.getJSONArray("changes"));
            RemoteAssets assets = runtime.assets(peer);
            for (int offset = 0; offset < JournalStore.MAX_ENTRIES && needed.size() < MAX_WANTS; offset += JournalStore.PAGE_SIZE) {
                List<JournalEntry> entries = runtime.entries.shared(scope, offset);
                for (int i = 0; i < Math.min(entries.size(), JournalStore.PAGE_SIZE) && needed.size() < MAX_WANTS; i++)
                    for (JournalAttachment file : entries.get(i).media()) {
                        if (needed.size() >= MAX_WANTS || needed.containsKey(file.hash)) continue;
                        assets.reserve(file.hash, file.length);
                        if (!assets.isComplete(file.hash) && assets.offset(file.hash) == file.length) assets.finish(file.hash);
                        if (!assets.isComplete(file.hash)) needed.put(file.hash, file);
                    }
                if (entries.size() <= JournalStore.PAGE_SIZE) break;
            }
            for (JournalAttachment file : needed.values()) wants.put(new JSONObject().put("hash", file.hash)
                    .put("length", file.length).put("offset", assets.offset(file.hash)));
            send(frame("journal_receipt").put("received", through).put("assets", wants));
        }
        int chunks = 0;
        while (true) {
            JSONObject message = read(null);
            if ("journal_assets_end".equals(message.getString("type"))) break;
            if (!"journal_asset".equals(message.getString("type")) || ++chunks > MAX_CHUNKS)
                throw new SecurityException("Invalid journal media phase");
            String hash = JournalEntry.text(message, "hash"), encoded = JournalEntry.text(message, "data");
            if (!needed.containsKey(hash) || encoded.length() > (RemoteAssets.CHUNK_BYTES + 2) / 3 * 4)
                throw new SecurityException("Unrequested journal media");
            byte[] bytes = Base64.getDecoder().decode(encoded);
            synchronized (runtime.authority) {
                check(); RemoteAssets assets = runtime.assets(peer);
                long offset = RemoteProtocol.integer(message, "offset");
                if (offset != assets.offset(hash)) throw new SecurityException("Journal media offset mismatch");
                long complete = assets.append(hash, offset, bytes);
                if (complete == needed.get(hash).length) assets.finish(hash);
            }
        }
        synchronized (runtime.authority) { check(); runtime.pruneRemote(peer); }
    }

    private JSONObject frame(String type) throws Exception {
        return new JSONObject().put("version", RemoteProtocol.VERSION).put("type", type)
                .put("generation", peer.generation).put("role", peer.localRole.name());
    }
    private void check() throws Exception {
        if (System.nanoTime() >= deadline) throw new IOException("Journal session expired");
        if (!peer.identity.equals(connection.authenticatedPeerIdentity())) throw new SecurityException("Journal peer mismatch");
        runtime.requireActive(peer);
    }
    private void send(JSONObject frame) throws Exception {
        synchronized (runtime.authority) { check(); RemoteProtocol.write(connection.output(), frame); }
    }
    private JSONObject read(String type) throws Exception {
        synchronized (runtime.authority) { check(); }
        connection.setReadTimeoutMillis((int) Math.max(1, Math.min(8000, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))));
        JSONObject frame = RemoteProtocol.read(connection.input());
        synchronized (runtime.authority) {
            check();
            if (RemoteProtocol.integer(frame, "version") != RemoteProtocol.VERSION)
                throw new SecurityException("Unsupported journal protocol");
            String actual = JournalEntry.text(frame, "type");
            if (!peer.generation.equals(JournalEntry.text(frame, "generation")) || !peer.remoteRole().name().equals(JournalEntry.text(frame, "role"))
                    || type != null && !type.equals(actual)) throw new SecurityException("Journal frame mismatch");
            Set<String> fields = new HashSet<>(Set.of("version", "type", "generation", "role"));
            switch (actual) {
                case "journal_status": fields.add("received"); break;
                case "journal_changes": fields.add("changes"); break;
                case "journal_receipt": fields.addAll(Set.of("received", "assets")); break;
                case "journal_asset": fields.addAll(Set.of("hash", "offset", "data")); break;
                case "journal_assets_end": break;
                default: throw new SecurityException("Unknown journal frame");
            }
            JournalEntry.keys(frame, fields);
        }
        return frame;
    }
}
