package com.subhub.app.remote;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Base64;
import java.util.List;

/** A bounded duplex exchange; receipt, asset completion and configuration application stay distinct. */
public final class RemoteSession {
    public interface Hooks {
        default int journalVersion() { return 0; }
        default void journal(PeerTransport.Connection connection, RemoteStore.Peer peer, long deadline) throws Exception { }
        default int profileVersion() { return 0; }
        default JSONObject profile(RemoteStore.Peer peer) throws Exception { return null; }
        default int tasksVersion() { return 0; }
        default int taskCancelVersion() { return 0; }
        default void tasks(PeerTransport.Connection connection, RemoteStore.Peer peer, long deadline) throws Exception { }
        default String displayName(RemoteStore.Peer peer) { return ""; }
        default void authenticate(PeerTransport.Connection connection,RemoteStore.Peer peer,long deadlineNanos)throws Exception {}
        void prepare(RemoteStore.Peer peer) throws Exception;
        void received(RemoteStore.Peer peer,RemoteProtocol.Stream stream) throws Exception;
        List<AssetNeed> missingAssets(RemoteStore.Peer peer) throws Exception;
        RemoteAssets assets(RemoteStore.Peer peer) throws Exception;
        void complete(RemoteStore.Peer peer) throws Exception;
    }
    public static final class AssetNeed {
        public final String hash;
        public final long length,offset;
        public AssetNeed(String hash,long length,long offset) {
            if(hash==null || !hash.matches("[0-9a-f]{64}") || length<=0 || length>RemoteAssets.MAX_ASSET_BYTES || offset<0 || offset>=length) {
                throw new IllegalArgumentException("Invalid asset request");
            }
            this.hash=hash;this.length=length;this.offset=offset;
        }
    }
    private static final int MAX_ASSET_REQUESTS=16,MAX_ASSET_CHUNKS=64,MAX_BATCHES=64;
    private final RemoteStore store;
    private final PeerTransport transport;
    private final Hooks hooks;

    public RemoteSession(RemoteStore store,PeerTransport transport,Hooks hooks) {
        this.store=store;this.transport=transport;this.hooks=hooks;
    }

    public void outgoing(PeerTransport.Connection connection,RemoteStore.Peer peer,long deadlineNanos) throws Exception {
        requirePeer(connection,peer);
        JSONObject hello = message("hello",peer).put("route",transport.inviteRoute()).put("authVersion",1).put("activityVersion",1).put("displayName",hooks.displayName(peer));
        if (hooks.profileVersion() == 1) hello.put("profileVersion", 1);
        if (hooks.journalVersion() == 1) hello.put("journalVersion", 1);
        if (hooks.tasksVersion() == 1) {
            hello.put("tasksVersion", 1);
            if (hooks.taskCancelVersion() == 1) hello.put("taskCancelVersion", 1);
        }
        send(connection,hello,deadlineNanos);
        JSONObject response=read(connection,deadlineNanos);
        if("revoked".equals(response.optString("type")) && peer.generation.equals(response.optString("generation"))) {
            requireType(response,"revoked",peer);store.revoke(peer.identity,peer.generation);
            send(connection,message("revoked_ack",peer),deadlineNanos);return;
        }
        requireType(response,"hello_ack",peer);
        boolean journal = journalVersion(response) == 1 && hooks.journalVersion() == 1;
        store.putMetadata(peer.identity, peer.generation, "journal_version", journal ? "1" : "0");
        store.putMetadata(peer.identity,peer.generation,"tasks_version",tasksVersion(response)==1 && hooks.tasksVersion()==1 ? "1" : "0");
        store.putMetadata(peer.identity,peer.generation,"tasks_cancel_version",
                tasksVersion(response)==1 && hooks.tasksVersion()==1 && cancelVersion(response)==1 && hooks.taskCancelVersion()==1 ? "1" : "0");
        receiveDisplayName(response, peer);
        if (profileVersion(response) == 1 && hooks.profileVersion() == 1) {
            send(connection, message("profile", peer).put("profile", hooks.profile(peer)), deadlineNanos);
            JSONObject profile = read(connection, deadlineNanos); requireType(profile, "profile", peer);
            RemoteProfiles.receive(store, peer, profile.getJSONObject("profile"));
        }
        if(response.has("authVersion")){if(RemoteProtocol.integer(response,"authVersion")!=1)throw new SecurityException("Unsupported keyholder provisioning");hooks.authenticate(connection,peer,deadlineNanos);}
        exchange(connection,peer,deadlineNanos,activityVersion(response)==1,tasksVersion(response)==1 && hooks.tasksVersion()==1,journal);
    }

    public void incoming(PeerTransport.Connection connection,JSONObject hello,long deadlineNanos) throws Exception {
        String identity=RemoteProtocol.peerId(connection.authenticatedPeerIdentity());
        String generation=RemoteProtocol.identifier(hello.getString("generation"));
        RemoteStore.Peer peer=store.peer(identity,generation);
        if(peer!=null && peer.state==RemoteStore.State.REVOKED) {
            send(connection,message("revoked",peer),deadlineNanos);
            terminalDrain(connection,"revoked_ack",peer,deadlineNanos);return;
        }
        requirePeer(connection,peer);requireType(hello,"hello",peer);
        store.setRoute(identity,generation,hello.getString("route"));
        receiveDisplayName(hello, peer);
        JSONObject ready=message("hello_ack",peer).put("displayName",hooks.displayName(peer));
        boolean profile = profileVersion(hello) == 1 && hooks.profileVersion() == 1;
        boolean journal = journalVersion(hello) == 1 && hooks.journalVersion() == 1;
        store.putMetadata(peer.identity, peer.generation, "journal_version", journal ? "1" : "0");
        if (journal) ready.put("journalVersion", 1);
        if (profile) ready.put("profileVersion", 1);
        if(hello.has("authVersion")){if(RemoteProtocol.integer(hello,"authVersion")!=1)throw new SecurityException("Unsupported keyholder provisioning");ready.put("authVersion",1);}
        boolean activity=activityVersion(hello)==1;
        if(activity)ready.put("activityVersion",1);
        boolean tasks = tasksVersion(hello)==1 && hooks.tasksVersion()==1;
        store.putMetadata(peer.identity,peer.generation,"tasks_version",tasks ? "1" : "0");
        if (tasks) ready.put("tasksVersion",1);
        boolean cancellation = tasks && cancelVersion(hello)==1 && hooks.taskCancelVersion()==1;
        store.putMetadata(peer.identity,peer.generation,"tasks_cancel_version",cancellation ? "1" : "0");
        if (cancellation) ready.put("taskCancelVersion",1);
        send(connection,ready,deadlineNanos);
        if (profile) {
            JSONObject received = read(connection, deadlineNanos); requireType(received, "profile", peer);
            RemoteProfiles.receive(store, peer, received.getJSONObject("profile"));
            send(connection, message("profile", peer).put("profile", hooks.profile(peer)), deadlineNanos);
        }
        if(hello.has("authVersion"))hooks.authenticate(connection,peer,deadlineNanos);
        exchange(connection,peer,deadlineNanos,activity,tasks,journal);
    }

    public void sendRevocation(PeerTransport.Connection connection,RemoteStore.Peer peer,long deadline) throws Exception {
        if(peer.state!=RemoteStore.State.REVOKED || !peer.identity.equals(connection.authenticatedPeerIdentity()))throw new SecurityException("Invalid revocation target");
        send(connection,message("revoke",peer),deadline);requireType(read(connection,deadline),"revoke_ack",peer);
        store.putMetadata(peer.identity,peer.generation,"revocation_delivered","true");
        try{send(connection,message("revoke_complete",peer),deadline);}catch(IOException closedAfterReceipt){/* Revocation receipt is already durable. */}
    }
    public void receiveRevocation(PeerTransport.Connection connection,JSONObject frame,long deadline) throws Exception {
        RemoteStore.Peer peer=store.peer(connection.authenticatedPeerIdentity(),RemoteProtocol.identifier(frame.getString("generation")));
        if(peer==null)throw new SecurityException("Unknown relationship");requireType(frame,"revoke",peer);
        store.revoke(peer.identity,peer.generation);store.putMetadata(peer.identity,peer.generation,"revocation_delivered","true");
        send(connection,message("revoke_ack",peer),deadline);terminalDrain(connection,"revoke_complete",peer,deadline);
    }

    private void terminalDrain(PeerTransport.Connection connection,String type,RemoteStore.Peer peer,long deadline)throws Exception {
        // A QUIC connection close can overtake buffered stream bytes. Keep the reply alive until its receipt.
        try{requireType(read(connection,deadline),type,peer);}catch(IOException closedAfterReply){/* The terminal application state is already committed. */}
    }

    private void receiveDisplayName(JSONObject frame, RemoteStore.Peer peer) throws JSONException {
        if (!frame.has("displayName")) return;
        Object raw = frame.get("displayName");
        if (!(raw instanceof String) || ((String) raw).length() > com.subhub.app.people.ProfileNames.MAX_LENGTH)
            throw new JSONException("Invalid display name");
        store.updateDisplayName(peer.identity, peer.generation, (String) raw);
    }

    private static long journalVersion(JSONObject frame) throws JSONException {
        if (!frame.has("journalVersion")) return 0;
        long version = RemoteProtocol.integer(frame, "journalVersion");
        if (version <= 0) throw new JSONException("Invalid Journal capability");
        return version;
    }

    private static long profileVersion(JSONObject frame) throws JSONException {
        if (!frame.has("profileVersion")) return 0;
        long version = RemoteProtocol.integer(frame, "profileVersion");
        if (version <= 0) throw new JSONException("Invalid profile capability");
        return version;
    }

    private static long activityVersion(JSONObject frame)throws JSONException {
        if(!frame.has("activityVersion"))return 0;
        long version=RemoteProtocol.integer(frame,"activityVersion");
        if(version<=0)throw new SecurityException("Invalid activity capability");return version;
    }

    private static long cancelVersion(JSONObject frame) throws JSONException {
        if (!frame.has("taskCancelVersion")) return 0;
        long value = RemoteProtocol.integer(frame, "taskCancelVersion");
        if (value <= 0) throw new SecurityException("Invalid task cancellation capability");
        return value;
    }

    private static long tasksVersion(JSONObject frame) throws JSONException {
        if (!frame.has("tasksVersion")) return 0;
        long value = RemoteProtocol.integer(frame,"tasksVersion");
        if (value <= 0) throw new SecurityException("Invalid Tasks capability");
        return value;
    }

    private void exchange(PeerTransport.Connection connection,RemoteStore.Peer peer,long deadline,boolean activity,boolean tasks,boolean journal) throws Exception {
        hooks.prepare(peer);
        if(peer.localRole==RemoteProtocol.Role.DOM) {
            push(connection,peer,deadline,activity);
            pull(connection,peer,deadline,activity);
            if (tasks) hooks.tasks(connection,peer,deadline);
            if (journal) hooks.journal(connection,peer,deadline);
            send(connection,message("done",peer),deadline);requireType(read(connection,deadline),"done_ack",peer);
            try{send(connection,message("done_complete",peer),deadline);}catch(IOException closedAfterReceipt){/* Both peers have acknowledged the exchange. */}
        } else {
            pull(connection,peer,deadline,activity);
            hooks.prepare(peer);
            push(connection,peer,deadline,activity);
            if (tasks) hooks.tasks(connection,peer,deadline);
            if (journal) hooks.journal(connection,peer,deadline);
            requireType(read(connection,deadline),"done",peer);send(connection,message("done_ack",peer),deadline);terminalDrain(connection,"done_complete",peer,deadline);
        }
        hooks.complete(peer);store.synced(peer.identity,peer.generation,System.currentTimeMillis());
    }

    private void push(PeerTransport.Connection connection,RemoteStore.Peer peer,long deadline,boolean activity) throws Exception {
        int batches=0;
        // Historical pagination must not delay current state or an application result.
        for(RemoteProtocol.Stream stream:new RemoteProtocol.Stream[]{RemoteProtocol.Stream.CONFIGURATION,
                RemoteProtocol.Stream.COMMAND_RESULT,RemoteProtocol.Stream.RUNTIME,RemoteProtocol.Stream.BASELINE,
                RemoteProtocol.Stream.EVENTS,RemoteProtocol.Stream.STATS,RemoteProtocol.Stream.APP_CATALOG,
                RemoteProtocol.Stream.DAILY,RemoteProtocol.Stream.APP_USAGE}) {
            if(stream.writer!=peer.localRole) continue;
            if(stream==RemoteProtocol.Stream.EVENTS&&!activity)continue;
            while(batches<MAX_BATCHES) {
                checkActive(peer);
                RemoteProtocol.Batch batch=store.nextBatch(peer.identity,peer.generation,stream);
                if(batch.changes.isEmpty()) break;
                send(connection,batch.json(),deadline);
                store.acknowledge(peer.identity,RemoteProtocol.Ack.parse(read(connection,deadline)));
                store.markSent(peer.identity,batch);
                batches++;
            }
        }
        send(connection,message("state_complete",peer).put("heads",store.outgoingHeads(peer.identity,peer.generation,activity)),deadline);
        JSONObject wanted=read(connection,deadline);requireType(wanted,"asset_wants",peer);
        JSONArray rows=wanted.getJSONArray("assets");
        if(rows.length()>MAX_ASSET_REQUESTS) throw new IOException("Too many asset requests");
        int chunks=0;
        for(int i=0;i<rows.length();i++) {
            checkActive(peer);
            JSONObject row=rows.getJSONObject(i);
            AssetNeed need=new AssetNeed(row.getString("hash"),RemoteProtocol.integer(row,"length"),RemoteProtocol.integer(row,"offset"));
            RemoteAssets source=hooks.assets(peer);
            if(source==null || !source.isComplete(need.hash) || source.length(need.hash)!=need.length) {
                send(connection,message("asset_unavailable",peer).put("hash",need.hash),deadline);continue;
            }
            long offset=need.offset;
            while(offset<need.length && chunks<MAX_ASSET_CHUNKS && remainingMillis(deadline)>1500) {
                checkActive(peer);
                byte[] bytes=source.chunk(need.hash,offset);
                send(connection,message("asset_chunk",peer).put("hash",need.hash).put("length",need.length)
                        .put("offset",offset).put("data",Base64.getEncoder().encodeToString(bytes)),deadline);
                JSONObject ack=read(connection,deadline);requireType(ack,"asset_ack",peer);
                if(!need.hash.equals(ack.getString("hash")) || RemoteProtocol.integer(ack,"offset")!=offset+bytes.length) {
                    throw new IOException("Unmatched asset acknowledgement");
                }
                offset+=bytes.length;chunks++;
            }
            if(chunks>=MAX_ASSET_CHUNKS || remainingMillis(deadline)<=1500) break;
        }
        send(connection,message("assets_complete",peer),deadline);
        requireType(read(connection,deadline),"phase_ack",peer);
    }

    private void pull(PeerTransport.Connection connection,RemoteStore.Peer peer,long deadline,boolean activity) throws Exception {
        int count=0;
        while(true) {
            checkActive(peer);
            JSONObject frame=read(connection,deadline);
            if("state_complete".equals(frame.optString("type"))) {
                requireType(frame,"state_complete",peer);store.confirmHeads(peer.identity,peer.generation,frame.getJSONObject("heads"),activity);break;
            }
            if(++count>MAX_BATCHES) throw new IOException("Too many sync batches");
            RemoteProtocol.Batch batch=RemoteProtocol.Batch.parse(frame);
            if(batch.stream==RemoteProtocol.Stream.EVENTS&&!activity)throw new SecurityException("Activity capability was not negotiated");
            RemoteProtocol.Ack ack=store.receive(peer.identity,batch);
            send(connection,ack.json(),deadline);
            hooks.received(peer,batch.stream);
        }
        List<AssetNeed> all=hooks.missingAssets(peer);
        List<AssetNeed> needs=all.subList(0,Math.min(all.size(),MAX_ASSET_REQUESTS));
        JSONArray requests=new JSONArray();
        for(AssetNeed need:needs) requests.put(new JSONObject().put("hash",need.hash).put("length",need.length).put("offset",need.offset));
        send(connection,message("asset_wants",peer).put("assets",requests),deadline);
        int chunks=0;
        while(true) {
            checkActive(peer);
            JSONObject frame=read(connection,deadline);
            String type=frame.getString("type");
            if("assets_complete".equals(type)) { requireType(frame,"assets_complete",peer);break; }
            if("asset_unavailable".equals(type)) {
                requireType(frame,"asset_unavailable",peer);requireRequested(needs,frame.getString("hash"));continue;
            }
            requireType(frame,"asset_chunk",peer);
            if(++chunks>MAX_ASSET_CHUNKS) throw new IOException("Too many asset chunks");
            AssetNeed need=requireRequested(needs,frame.getString("hash"));
            if(RemoteProtocol.integer(frame,"length")!=need.length) throw new IOException("Conflicting asset length");
            String encoded=frame.getString("data");
            if(encoded.length()>(RemoteAssets.CHUNK_BYTES+2)/3*4) throw new IOException("Asset chunk too large");
            byte[] bytes=Base64.getDecoder().decode(encoded);
            RemoteAssets assets=hooks.assets(peer);
            if(assets==null) throw new IOException("Asset store unavailable");
            assets.reserve(need.hash,need.length);
            long offset=assets.append(need.hash,RemoteProtocol.integer(frame,"offset"),bytes);
            if(offset==need.length) assets.finish(need.hash);
            send(connection,message("asset_ack",peer).put("hash",need.hash).put("offset",offset),deadline);
        }
        hooks.received(peer,RemoteProtocol.Stream.CONFIGURATION);
        send(connection,message("phase_ack",peer),deadline);
    }

    private static AssetNeed requireRequested(List<AssetNeed> needs,String hash) throws IOException {
        for(AssetNeed need:needs) if(need.hash.equals(hash)) return need;
        throw new IOException("Unrequested asset");
    }
    private void checkActive(RemoteStore.Peer peer) {
        RemoteStore.Peer current=store.peer(peer.identity,peer.generation);
        if(current==null || current.state!=RemoteStore.State.ACTIVE) throw new SecurityException("Pairing revoked");
    }
    private void requirePeer(PeerTransport.Connection connection,RemoteStore.Peer peer) {
        if(peer==null || !peer.identity.equals(connection.authenticatedPeerIdentity())) throw new SecurityException("Unknown peer");
        checkActive(peer);
    }
    private static JSONObject message(String type,RemoteStore.Peer peer) throws JSONException {
        return new JSONObject().put("version",1).put("type",type).put("generation",peer.generation);
    }
    private static void requireType(JSONObject message,String type,RemoteStore.Peer peer) throws JSONException {
        RemoteProtocol.requireVersion(message);
        if(!type.equals(message.getString("type")) || !peer.generation.equals(message.getString("generation"))) {
            throw new JSONException("Unexpected peer message");
        }
    }
    private static void send(PeerTransport.Connection connection,JSONObject frame,long deadline) throws IOException {
        connection.setReadTimeoutMillis(Math.min(10_000,remainingMillis(deadline)));
        RemoteProtocol.write(connection.output(),frame);
    }
    private static JSONObject read(PeerTransport.Connection connection,long deadline) throws IOException {
        connection.setReadTimeoutMillis(Math.min(10_000,remainingMillis(deadline)));
        return RemoteProtocol.read(connection.input());
    }
    private static long remainingMillis(long deadline) throws SocketTimeoutException {
        long left=deadline-System.nanoTime();
        if(left<=0) throw new SocketTimeoutException("Sync window expired");
        return Math.max(1,left/1_000_000);
    }
}
