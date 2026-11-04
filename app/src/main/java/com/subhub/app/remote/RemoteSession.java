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
        default int appIconsVersion() { return 0; }
        default void appIcons(PeerTransport.Connection connection, RemoteStore.Peer peer, long deadline) throws Exception {
            throw new UnsupportedOperationException("App icons unavailable");
        }
        default int journalVersion() { return 0; }
        default int journalTitlesVersion() { return 0; }
        default void journalTitlesUpgraded(RemoteStore.Peer peer) throws Exception { }
        default void journal(PeerTransport.Connection connection, RemoteStore.Peer peer, long deadline) throws Exception { }
        default int sessionControlVersion() { return 0; }
        default void sessionControl(PeerTransport.Connection connection, RemoteStore.Peer peer, long deadline) throws Exception {
            throw new UnsupportedOperationException("Session control unavailable");
        }
        default void sessionControl(PeerTransport.Connection connection, RemoteStore.Peer peer, long deadline, boolean startsReady) throws Exception {
            if(startsReady)sessionControl(connection,peer,deadline);
        }
        default int profileVersion() { return 0; }
        default int profileDetailsVersion() { return 0; }
        default int profileRoleVersion() { return 0; }
        default JSONObject profile(RemoteStore.Peer peer) throws Exception { return null; }
        default JSONObject profile(RemoteStore.Peer peer, boolean details) throws Exception { return profile(peer); }
        default JSONObject profile(RemoteStore.Peer peer, boolean details, boolean roleIdentity) throws Exception { return profile(peer, details); }
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
    private static final int NEEDS_ASSETS=1,BULK_PENDING=2;
    private final RemoteStore store;
    private final PeerTransport transport;
    private final Hooks hooks;

    public RemoteSession(RemoteStore store,PeerTransport transport,Hooks hooks) {
        this.store=store;this.transport=transport;this.hooks=hooks;
    }

    public void outgoing(PeerTransport.Connection connection,RemoteStore.Peer peer,long deadlineNanos) throws Exception {
        requirePeer(connection,peer);
        store.putMetadata(peer.identity, peer.generation, "session_control_version", "0");
        int localSessionControl = hooks.sessionControlVersion();
        int localAppIcons = hooks.appIconsVersion();
        JSONObject hello = message("hello",peer).put("route",transport.inviteRoute()).put("authVersion",1).put("activityVersion",1).put("phasePlanVersion",1).put("displayName",hooks.displayName(peer));
        if (localSessionControl == 1) hello.put("sessionControlVersion", 1);
        if (localAppIcons == 1) hello.put("appIconsVersion", 1);
        if (hooks.profileVersion() == 1) hello.put("profileVersion", 1);
        if (hooks.profileVersion() == 1 && hooks.profileDetailsVersion() == 1) hello.put("profileDetailsVersion", 1);
        if (hooks.profileVersion() == 1 && hooks.profileRoleVersion() == 1) hello.put("profileRoleVersion", 1);
        if (hooks.journalVersion() == 1) hello.put("journalVersion", 1);
        if (hooks.journalVersion() == 1 && hooks.journalTitlesVersion() == 1) hello.put("journalTitlesVersion", 1);
        if (hooks.tasksVersion() == 1) {
            hello.put("tasksVersion", 1);
            if (hooks.taskCancelVersion() == 1) hello.put("taskCancelVersion", 1);
        }
        send(connection,hello,deadlineNanos);
        JSONObject response=read(connection,deadlineNanos);
        if("revoked".equals(response.optString("type")) && peer.generation.equals(response.optString("generation"))) {
            requireType(response,"revoked",peer);store.revokeAuthenticatedPeer(connection,peer);
            send(connection,message("revoked_ack",peer),deadlineNanos);return;
        }
        requireType(response,"hello_ack",peer);
        boolean controls = RemoteSessionCommands.negotiatedVersion(response) == 1 && localSessionControl == 1;
        boolean priority = phasePlanVersion(response) == 1 && controls;
        store.putMetadata(peer.identity, peer.generation, "session_control_version", controls ? "1" : "0");
        boolean journal = journalVersion(response) == 1 && hooks.journalVersion() == 1;
        store.putMetadata(peer.identity, peer.generation, "journal_version", journal ? "1" : "0");
        store.putMetadata(peer.identity, peer.generation, "journal_titles_version",
                journal && journalTitlesVersion(response) == 1 && hooks.journalTitlesVersion() == 1 ? "1" : "0");
        store.putMetadata(peer.identity,peer.generation,"tasks_version",tasksVersion(response)==1 && hooks.tasksVersion()==1 ? "1" : "0");
        store.putMetadata(peer.identity,peer.generation,"tasks_cancel_version",
                tasksVersion(response)==1 && hooks.tasksVersion()==1 && cancelVersion(response)==1 && hooks.taskCancelVersion()==1 ? "1" : "0");
        receiveDisplayName(response, peer);
        boolean details = profileDetailsVersion(response) == 1 && hooks.profileDetailsVersion() == 1;
        boolean roleIdentity = profileRoleVersion(response) == 1 && hooks.profileRoleVersion() == 1;
        if (profileVersion(response) == 1 && hooks.profileVersion() == 1) {
            send(connection, message("profile", peer).put("profile", hooks.profile(peer, details, roleIdentity)), deadlineNanos);
            JSONObject profile = read(connection, deadlineNanos); requireType(profile, "profile", peer);
            RemoteProfiles.receive(store, peer, profile.getJSONObject("profile"), details, roleIdentity);
        }
        if(response.has("authVersion")){if(RemoteProtocol.integer(response,"authVersion")!=1)throw new SecurityException("Unsupported keyholder provisioning");hooks.authenticate(connection,peer,deadlineNanos);}
        exchange(connection,peer,deadlineNanos,activityVersion(response)==1,tasksVersion(response)==1 && hooks.tasksVersion()==1,journal,controls,
                appIconsVersion(response)==1 && localAppIcons==1,priority);
    }

    public void incoming(PeerTransport.Connection connection,JSONObject hello,long deadlineNanos) throws Exception {
        store.authorityAccess(()->null);
        String identity=RemoteProtocol.peerId(connection.authenticatedPeerIdentity());
        String generation=RemoteProtocol.identifier(hello.getString("generation"));
        RemoteStore.Peer peer=store.peer(identity,generation);
        if(peer!=null && peer.state==RemoteStore.State.REVOKED) {
            send(connection,message("revoked",peer),deadlineNanos);
            terminalDrain(connection,"revoked_ack",peer,deadlineNanos);return;
        }
        requirePeer(connection,peer);requireType(hello,"hello",peer);
        store.putMetadata(peer.identity, peer.generation, "session_control_version", "0");
        boolean controls = RemoteSessionCommands.negotiatedVersion(hello) == 1 && hooks.sessionControlVersion() == 1;
        store.putMetadata(peer.identity, peer.generation, "session_control_version", controls ? "1" : "0");
        store.setRoute(identity,generation,hello.getString("route"));
        receiveDisplayName(hello, peer);
        boolean priority = phasePlanVersion(hello) == 1 && controls;
        JSONObject ready=message("hello_ack",peer).put("displayName",hooks.displayName(peer));
        if (priority) ready.put("phasePlanVersion",1);
        boolean appIcons=appIconsVersion(hello)==1 && hooks.appIconsVersion()==1;
        if (appIcons) ready.put("appIconsVersion",1);
        if (controls) ready.put("sessionControlVersion", 1);
        boolean profile = profileVersion(hello) == 1 && hooks.profileVersion() == 1;
        boolean details = profile && profileDetailsVersion(hello) == 1 && hooks.profileDetailsVersion() == 1;
        boolean roleIdentity = profile && profileRoleVersion(hello) == 1 && hooks.profileRoleVersion() == 1;
        boolean journal = journalVersion(hello) == 1 && hooks.journalVersion() == 1;
        store.putMetadata(peer.identity, peer.generation, "journal_version", journal ? "1" : "0");
        if (journal) ready.put("journalVersion", 1);
        boolean titles = journal && journalTitlesVersion(hello) == 1 && hooks.journalTitlesVersion() == 1;
        store.putMetadata(peer.identity, peer.generation, "journal_titles_version", titles ? "1" : "0");
        if (titles) ready.put("journalTitlesVersion", 1);
        if (profile) ready.put("profileVersion", 1);
        if (details) ready.put("profileDetailsVersion", 1);
        if (roleIdentity) ready.put("profileRoleVersion", 1);
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
            RemoteProfiles.receive(store, peer, received.getJSONObject("profile"), details, roleIdentity);
            send(connection, message("profile", peer).put("profile", hooks.profile(peer, details, roleIdentity)), deadlineNanos);
        }
        if(hello.has("authVersion"))hooks.authenticate(connection,peer,deadlineNanos);
        exchange(connection,peer,deadlineNanos,activity,tasks,journal,controls,appIcons,priority);
    }

    public void sendRevocation(PeerTransport.Connection connection,RemoteStore.Peer peer,long deadline) throws Exception {
        store.authorityAccess(()->{RemoteStore.Peer current=store.peer(peer.identity,peer.generation);if(current==null||current.state!=RemoteStore.State.REVOKED||current.localRole!=peer.localRole)throw new SecurityException("Current revocation required");return null;});
        if(peer.state!=RemoteStore.State.REVOKED || !peer.identity.equals(connection.authenticatedPeerIdentity()))throw new SecurityException("Invalid revocation target");
        send(connection,message("revoke",peer),deadline);requireType(read(connection,deadline),"revoke_ack",peer);
        store.putMetadata(peer.identity,peer.generation,"revocation_delivered","true");
        try{send(connection,message("revoke_complete",peer),deadline);}catch(IOException closedAfterReceipt){/* Revocation receipt is already durable. */}
    }
    public void receiveRevocation(PeerTransport.Connection connection,JSONObject frame,long deadline) throws Exception {
        RemoteStore.Peer peer=store.peer(connection.authenticatedPeerIdentity(),RemoteProtocol.identifier(frame.getString("generation")));
        if(peer==null)throw new SecurityException("Unknown relationship");requireType(frame,"revoke",peer);
        store.revokeAuthenticatedPeer(connection,peer);store.putMetadata(peer.identity,peer.generation,"revocation_delivered","true");
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

    private static long journalTitlesVersion(JSONObject frame) throws JSONException {
        if (!frame.has("journalTitlesVersion")) return 0;
        long version = RemoteProtocol.integer(frame, "journalTitlesVersion");
        if (version <= 0) throw new JSONException("Invalid Journal title capability");
        return version;
    }

    private static long profileRoleVersion(JSONObject frame) throws JSONException {
        if (!frame.has("profileRoleVersion")) return 0;
        long version = RemoteProtocol.integer(frame, "profileRoleVersion");
        if (version <= 0) throw new JSONException("Invalid profile role capability");
        return version;
    }

    private static long profileDetailsVersion(JSONObject frame) throws JSONException {
        if (!frame.has("profileDetailsVersion")) return 0;
        long version = RemoteProtocol.integer(frame, "profileDetailsVersion");
        if (version <= 0) throw new JSONException("Invalid profile details capability");
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

    private static long appIconsVersion(JSONObject frame) throws JSONException {
        if (!frame.has("appIconsVersion")) return 0;
        long value=RemoteProtocol.integer(frame,"appIconsVersion");
        if (value<=0) throw new SecurityException("Invalid app icon capability");
        return value;
    }

    private static long phasePlanVersion(JSONObject frame) throws JSONException {
        if (!frame.has("phasePlanVersion")) return 0;
        long value = RemoteProtocol.integer(frame, "phasePlanVersion");
        if (value <= 0) throw new JSONException("Invalid phase plan capability");
        return value;
    }

    private static boolean core(RemoteProtocol.Stream stream) {
        return stream == RemoteProtocol.Stream.CONFIGURATION || stream == RemoteProtocol.Stream.COMMAND_RESULT
                || stream == RemoteProtocol.Stream.RUNTIME || stream == RemoteProtocol.Stream.BASELINE;
    }

    private void exchange(PeerTransport.Connection connection,RemoteStore.Peer peer,long deadline,boolean activity,boolean tasks,boolean journal,boolean controls,boolean appIcons,boolean priority) throws Exception {
        hooks.prepare(peer);
        if(peer.localRole==RemoteProtocol.Role.DOM) {
            if (priority) {
                int outgoing=pushState(connection,peer,deadline,activity,0);
                int incoming=pullState(connection,peer,deadline,activity,0);
                // A START for a new revision must not be rejected just because its assets arrived later.
                boolean remoteReady=true,localReady=true;
                if((outgoing&NEEDS_ASSETS)!=0)remoteReady=pushAssets(connection,peer,deadline,true);
                if((incoming&NEEDS_ASSETS)!=0)localReady=pullAssets(connection,peer,deadline,true);
                if (controls) { checkActive(peer); hooks.sessionControl(connection,peer,deadline,remoteReady&&localReady); }
                if((outgoing&BULK_PENDING)!=0)pushState(connection,peer,deadline,activity,1);
                if((incoming&BULK_PENDING)!=0)pullState(connection,peer,deadline,activity,1);
            } else {
                push(connection,peer,deadline,activity);
                pull(connection,peer,deadline,activity);
            }
            if (tasks) hooks.tasks(connection,peer,deadline);
            if (journal) journal(connection,peer,deadline);
            if (controls && !priority) hooks.sessionControl(connection,peer,deadline);
            if (appIcons) { checkActive(peer); hooks.appIcons(connection,peer,deadline); }
            send(connection,message("done",peer),deadline);requireType(read(connection,deadline),"done_ack",peer);
            try{send(connection,message("done_complete",peer),deadline);}catch(IOException closedAfterReceipt){/* Both peers have acknowledged the exchange. */}
        } else {
            if (priority) {
                int incoming=pullState(connection,peer,deadline,activity,0);
                hooks.prepare(peer);
                int outgoing=pushState(connection,peer,deadline,activity,0);
                boolean localReady=true,remoteReady=true;
                if((incoming&NEEDS_ASSETS)!=0)localReady=pullAssets(connection,peer,deadline,true);
                if((outgoing&NEEDS_ASSETS)!=0)remoteReady=pushAssets(connection,peer,deadline,true);
                if (controls) { checkActive(peer); hooks.sessionControl(connection,peer,deadline,remoteReady&&localReady); }
                if((incoming&BULK_PENDING)!=0)pullState(connection,peer,deadline,activity,1);
                if((outgoing&BULK_PENDING)!=0)pushState(connection,peer,deadline,activity,1);
            } else {
                pull(connection,peer,deadline,activity);
                hooks.prepare(peer);
                push(connection,peer,deadline,activity);
            }
            if (tasks) hooks.tasks(connection,peer,deadline);
            if (journal) journal(connection,peer,deadline);
            if (controls && !priority) hooks.sessionControl(connection,peer,deadline);
            if (appIcons) { checkActive(peer); hooks.appIcons(connection,peer,deadline); }
            requireType(read(connection,deadline),"done",peer);send(connection,message("done_ack",peer),deadline);terminalDrain(connection,"done_complete",peer,deadline);
        }
        hooks.complete(peer);store.synced(peer.identity,peer.generation,System.currentTimeMillis());
    }

    private void journal(PeerTransport.Connection connection, RemoteStore.Peer peer, long deadline) throws Exception {
        requirePeer(connection, peer);
        store.authorityAccess(() -> {
          synchronized (store) {
            RemoteStore.Peer current = store.peer(peer.identity, peer.generation);
            if (current == null || current.state != RemoteStore.State.ACTIVE || current.localRole != peer.localRole)
                throw new SecurityException("Pairing revoked");
            String projection = store.metadata(peer.identity, peer.generation, "journal_titles_version", "0");
            if (!projection.equals(store.metadata(peer.identity, peer.generation, "journal_titles_projection", "0"))) {
                if ("1".equals(projection)) hooks.journalTitlesUpgraded(peer);
                // Queue first: an interruption before this marker safely coalesces the same audience again.
                store.putMetadata(peer.identity, peer.generation, "journal_titles_projection", projection);
            }
          }
          return null;
        });
        hooks.journal(connection, peer, deadline);
    }

    private void push(PeerTransport.Connection connection,RemoteStore.Peer peer,long deadline,boolean activity) throws Exception {
        pushState(connection,peer,deadline,activity,-1);
        pushAssets(connection,peer,deadline,false);
    }

    private int pushState(PeerTransport.Connection connection,RemoteStore.Peer peer,long deadline,boolean activity,int phase) throws Exception {
        int batches=0;
        int limit=phase==0?16:phase==1?48:MAX_BATCHES;
        // Historical pagination must not delay current state or an application result.
        for(RemoteProtocol.Stream stream:new RemoteProtocol.Stream[]{RemoteProtocol.Stream.CONFIGURATION,
                RemoteProtocol.Stream.COMMAND_RESULT,RemoteProtocol.Stream.RUNTIME,RemoteProtocol.Stream.BASELINE,
                RemoteProtocol.Stream.EVENTS,RemoteProtocol.Stream.STATS,RemoteProtocol.Stream.APP_CATALOG,
                RemoteProtocol.Stream.DAILY,RemoteProtocol.Stream.APP_USAGE}) {
            if(stream.writer!=peer.localRole) continue;
            if(phase>=0 && core(stream)!=(phase==0)) continue;
            if(stream==RemoteProtocol.Stream.EVENTS&&!activity)continue;
            while(batches<limit) {
                checkActive(peer);
                RemoteProtocol.Batch batch=store.nextBatch(peer.identity,peer.generation,stream);
                if(batch.changes.isEmpty()) break;
                boolean sectionCandidate=phase==0&&(stream==RemoteProtocol.Stream.CONFIGURATION||stream==RemoteProtocol.Stream.BASELINE);
                RemoteSectionTransfer.EncodedOffer encoded=sectionCandidate?RemoteSectionTransfer.encodeOffer(batch,sectionBasis(peer,true)):null;
                JSONObject offered=encoded==null?batch.json():encoded.frame;
                if(encoded==null)send(connection,offered,deadline);else sendEncoded(connection,encoded.encoded,deadline);
                JSONObject receipt=read(connection,deadline);
                if("section_full".equals(receipt.optString("type"))) {
                    if(!"section_batch".equals(offered.optString("type")))throw new IOException("Unexpected section fallback");
                    RemoteSectionTransfer.requireFullRequest(receipt,batch);
                    checkActive(peer);sendEncoded(connection,encoded.fullEncoded,deadline);
                    receipt=read(connection,deadline);
                }
                store.acknowledge(peer.identity,RemoteProtocol.Ack.parse(receipt));
                store.markSent(peer.identity,batch);
                batches++;
            }
        }
        JSONObject complete=message("state_complete",peer).put("heads",store.outgoingHeads(peer.identity,peer.generation,activity));
        boolean bulk=phase==0&&hasBulk(peer,activity);
        if(phase>=0) complete.put("phase",phase);
        if(phase==0) complete.put("bulkPending",bulk);
        send(connection,complete,deadline);
        if(phase>=0) {
            JSONObject receipt=read(connection,deadline);requireType(receipt,"state_ack",peer);
            if(RemoteProtocol.integer(receipt,"phase")!=phase)throw new IOException("Unmatched state receipt");
            if(phase==0) {
                Object pending=receipt.get("assetsPending");
                if(!(pending instanceof Boolean))throw new IOException("Invalid asset prerequisite receipt");
                return ((Boolean)pending?NEEDS_ASSETS:0)|(bulk?BULK_PENDING:0);
            }
        }
        return 0;
    }

    private boolean hasBulk(RemoteStore.Peer peer,boolean activity) throws Exception {
        for(RemoteProtocol.Stream stream:RemoteProtocol.Stream.values()) {
            if(stream.writer!=peer.localRole||core(stream)||stream==RemoteProtocol.Stream.CONTRACTS)continue;
            if(stream==RemoteProtocol.Stream.EVENTS&&!activity)continue;
            if(!store.nextBatch(peer.identity,peer.generation,stream).changes.isEmpty())return true;
        }
        return false;
    }

    private JSONObject sectionBasis(RemoteStore.Peer peer,boolean sending) throws JSONException {
        // The opposite direction already retains this document under the same peer generation.
        if(sending) {
            if(peer.localRole==RemoteProtocol.Role.DOM)
                return store.mirrored(peer.identity,peer.generation,RemoteProtocol.Stream.BASELINE,"active");
            JSONObject desired=store.mirrored(peer.identity,peer.generation,RemoteProtocol.Stream.CONFIGURATION,"desired");
            return desired==null?null:desired.optJSONObject("configuration");
        }
        RemoteProtocol.Stream stream=peer.localRole==RemoteProtocol.Role.DOM?RemoteProtocol.Stream.CONFIGURATION:RemoteProtocol.Stream.BASELINE;
        String key=stream==RemoteProtocol.Stream.CONFIGURATION?"desired":"active";
        for(RemoteProtocol.Change row:store.published(peer.identity,peer.generation,stream))
            if(key.equals(row.key)&&!row.deleted) {
                JSONObject body=new JSONObject(row.body);
                return stream==RemoteProtocol.Stream.CONFIGURATION?body.optJSONObject("configuration"):body;
            }
        return null;
    }

    private boolean pushAssets(PeerTransport.Connection connection,RemoteStore.Peer peer,long deadline,boolean readiness) throws Exception {
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
        JSONObject receipt=read(connection,deadline);requireType(receipt,"phase_ack",peer);
        if(readiness) {
            Object pending=receipt.get("assetsPending");
            if(!(pending instanceof Boolean))throw new IOException("Invalid asset readiness receipt");
            return !(Boolean)pending;
        }
        return true;
    }

    private void pull(PeerTransport.Connection connection,RemoteStore.Peer peer,long deadline,boolean activity) throws Exception {
        pullState(connection,peer,deadline,activity,-1);
        pullAssets(connection,peer,deadline,false);
    }

    private int pullState(PeerTransport.Connection connection,RemoteStore.Peer peer,long deadline,boolean activity,int phase) throws Exception {
        int count=0;boolean bulk=false;
        int limit=phase==0?16:phase==1?48:MAX_BATCHES;
        while(true) {
            checkActive(peer);
            JSONObject frame=read(connection,deadline);
            if("state_complete".equals(frame.optString("type"))) {
                requireType(frame,"state_complete",peer);
                if(phase>=0 && RemoteProtocol.integer(frame,"phase")!=phase)throw new IOException("Unexpected state phase");
                if(phase==0) {
                    Object queued=frame.get("bulkPending");
                    if(!(queued instanceof Boolean))throw new IOException("Invalid bulk checkpoint");
                    bulk=(Boolean)queued;
                }
                store.confirmHeads(peer.identity,peer.generation,frame.getJSONObject("heads"),activity);break;
            }
            if(++count>limit) throw new IOException("Too many sync batches");
            RemoteProtocol.Batch batch;
            if("section_batch".equals(frame.optString("type"))) {
                if(phase!=0)throw new IOException("Section transfer was not negotiated");
                try{batch=RemoteSectionTransfer.restore(frame,peer.generation,peer.remoteRole(),sectionBasis(peer,false));}
                catch(RemoteSectionTransfer.MissingBasis absent) {
                    send(connection,RemoteSectionTransfer.fullRequest(frame),deadline);
                    checkActive(peer);
                    batch=RemoteSectionTransfer.fullResponse(read(connection,deadline),frame);
                }
            } else batch=RemoteProtocol.Batch.parse(frame);
            if(phase>=0 && core(batch.stream)!=(phase==0))throw new IOException("Unexpected stream phase");
            if(batch.stream==RemoteProtocol.Stream.EVENTS&&!activity)throw new SecurityException("Activity capability was not negotiated");
            RemoteProtocol.Ack ack=store.receive(peer.identity,batch);
            send(connection,ack.json(),deadline);
            hooks.received(peer,batch.stream);
        }
        if(phase>=0) {
            // Metadata ACKs precede application. This boundary waits for application hooks too.
            hooks.received(peer,RemoteProtocol.Stream.CONFIGURATION);
            boolean pending=phase==0&&!hooks.missingAssets(peer).isEmpty();
            send(connection,message("state_ack",peer).put("phase",phase).put("assetsPending",pending),deadline);
            return (pending?NEEDS_ASSETS:0)|(bulk?BULK_PENDING:0);
        }
        return 0;
    }

    private boolean pullAssets(PeerTransport.Connection connection,RemoteStore.Peer peer,long deadline,boolean readiness) throws Exception {
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
        // A bounded transfer may stop before configuration assets are complete. Keep commands queued.
        boolean pending=readiness&&!hooks.missingAssets(peer).isEmpty();
        JSONObject receipt=message("phase_ack",peer);
        if(readiness)receipt.put("assetsPending",pending);
        send(connection,receipt,deadline);
        return !pending;
    }

    private static AssetNeed requireRequested(List<AssetNeed> needs,String hash) throws IOException {
        for(AssetNeed need:needs) if(need.hash.equals(hash)) return need;
        throw new IOException("Unrequested asset");
    }
    private void checkActive(RemoteStore.Peer peer) {
        store.authorityAccess(()->{
            RemoteStore.Peer current=store.peer(peer.identity,peer.generation);
            if(current==null||current.state!=RemoteStore.State.ACTIVE||current.localRole!=peer.localRole)throw new SecurityException("Pairing revoked");return null;
        });
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
    private static void sendEncoded(PeerTransport.Connection connection,byte[] frame,long deadline) throws IOException {
        connection.setReadTimeoutMillis(Math.min(10_000,remainingMillis(deadline)));
        java.io.OutputStream output=connection.output();output.write(frame);output.flush();
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
