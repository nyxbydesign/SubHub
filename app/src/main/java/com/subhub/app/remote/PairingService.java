package com.subhub.app.remote;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.function.BooleanSupplier;

/** Mutual approval is persisted separately from transport reachability and application sync. */
public final class PairingService {
    private final RemoteStore store;
    private final RemoteSecrets secrets;
    private final PeerTransport transport;
    private final BooleanSupplier keyholder;
    private final String deviceName;

    public PairingService(RemoteStore store,RemoteSecrets secrets,PeerTransport transport,
            BooleanSupplier keyholder,String deviceName) {
        this.store=store;this.secrets=secrets;this.transport=transport;this.keyholder=keyholder;
        this.deviceName=RemoteProtocol.deviceName(deviceName);
    }

    public PairingInvite create(long now) throws IOException,GeneralSecurityException {
        requireKeyholder();
        PairingInvite invite=PairingInvite.create(transport.localIdentity(),transport.inviteRoute(),now);
        secrets.savePending(invite);
        try { store.saveInvite(invite); }
        catch(RuntimeException failure) { secrets.removePending(invite.id);throw failure; }
        return invite;
    }

    public void accept(String link,String name,long now) throws GeneralSecurityException {
        requireKeyholder();
        PairingInvite invite=PairingInvite.parse(link,now);
        if(invite.domIdentity.equals(transport.localIdentity())) throw new IllegalArgumentException("Cannot pair with this device");
        secrets.savePending(invite);
        try { store.acceptInvite(invite,name); }
        catch(RuntimeException failure) { secrets.removePending(invite.id);throw failure; }
    }

    public void approve(String invitation,String candidate,String fingerprint,long now) {
        requireKeyholder();
        String expected=PairingInvite.fingerprint(transport.localIdentity(),candidate,invitation);
        if(!expected.equals(fingerprint)) throw new SecurityException("Pairing identity changed");
        String name=null;
        for(RemoteStore.Invitation invite:store.invitations(now)) {
            if(invite.id.equals(invitation) && candidate.equals(invite.candidate)) name=invite.name;
        }
        if(name==null) throw new SecurityException("Pairing request unavailable");
        store.approveInvite(invitation,candidate,name,now);
    }

    public void cancel(String invitation) {
        requireKeyholder();
        store.cancelInvite(invitation);secrets.removePending(invitation);
    }

    public void revoke(String identity,String generation) {
        requireKeyholder();
        store.revoke(identity,generation);secrets.removePending(generation);
    }

    boolean resume(PeerTransport.Connection connection,RemoteStore.Peer peer) throws IOException,GeneralSecurityException {
        if(peer.localRole!=RemoteProtocol.Role.SUB || peer.state!=RemoteStore.State.PENDING
                || !peer.identity.equals(connection.authenticatedPeerIdentity())) throw new SecurityException("Pairing identity mismatch");
        String link=secrets.pendingLink(peer.generation);
        if(link==null) throw new GeneralSecurityException("Pairing secret unavailable");
        PairingInvite invite=PairingInvite.restore(link);
        if(!invite.domIdentity.equals(peer.identity) || !invite.id.equals(peer.generation)) throw new SecurityException("Pairing identity mismatch");
        try {
            write(connection,new JSONObject().put("version",1).put("type","pair_request")
                    .put("invite",invite.id).put("secret",invite.secret()).put("name",deviceName).put("route",transport.inviteRoute()));
            JSONObject response=read(connection);
            if("pair_pending".equals(response.getString("type"))) return false;
            String fingerprint=PairingInvite.fingerprint(peer.identity,transport.localIdentity(),peer.generation);
            if(!"pair_approved".equals(response.getString("type")) || !peer.generation.equals(response.getString("generation"))
                    || !fingerprint.equals(response.getString("fingerprint"))) throw new SecurityException("Pairing approval mismatch");
            write(connection,new JSONObject().put("version",1).put("type","pair_confirmed")
                    .put("generation",peer.generation).put("fingerprint",fingerprint));
            JSONObject ready=read(connection);
            if(!"pair_ready".equals(ready.getString("type")) || !peer.generation.equals(ready.getString("generation"))) {
                throw new SecurityException("Pairing completion mismatch");
            }
            store.activate(peer.identity,peer.generation);secrets.removePending(peer.generation);
            return true;
        } catch(JSONException invalid) { throw new IOException("Invalid pairing message"); }
    }

    boolean handle(PeerTransport.Connection connection,JSONObject request,long now) throws IOException {
        String candidate=RemoteProtocol.peerId(connection.authenticatedPeerIdentity());
        try {
            RemoteProtocol.requireVersion(request);
            if(!"pair_request".equals(request.getString("type"))) throw new JSONException("Expected pairing request");
            String id=RemoteProtocol.identifier(request.getString("invite"));
            byte[] token=Base64.getUrlDecoder().decode(request.getString("secret"));
            if(token.length!=32) throw new SecurityException("Invalid pairing secret");
            String tokenHash;
            try { tokenHash=RemoteProtocol.sha256(token); }
            finally { java.util.Arrays.fill(token,(byte)0); }
            if(!store.approvedInvite(id,candidate,tokenHash)) {
                store.bindInvite(id,candidate,tokenHash,now);
                store.describeCandidate(id,candidate,request.getString("name"),request.getString("route"));
                write(connection,new JSONObject().put("version",1).put("type","pair_pending"));
                return false;
            }
            String fingerprint=PairingInvite.fingerprint(transport.localIdentity(),candidate,id);
            write(connection,new JSONObject().put("version",1).put("type","pair_approved")
                    .put("generation",id).put("fingerprint",fingerprint));
            JSONObject confirmed=read(connection);
            if(!"pair_confirmed".equals(confirmed.getString("type")) || !id.equals(confirmed.getString("generation"))
                    || !fingerprint.equals(confirmed.getString("fingerprint"))) throw new SecurityException("Pairing confirmation mismatch");
            store.activate(candidate,id);
            store.setRoute(candidate,id,request.getString("route"));
            write(connection,new JSONObject().put("version",1).put("type","pair_ready").put("generation",id));
            secrets.removePending(id);
            return true;
        } catch(JSONException | IllegalArgumentException invalid) { throw new IOException("Invalid pairing message"); }
    }

    private void requireKeyholder() {
        if(!keyholder.getAsBoolean()) throw new SecurityException("Keyholder access required");
    }

    private static void write(PeerTransport.Connection connection,JSONObject message) throws IOException {
        connection.setReadTimeoutMillis(10_000);RemoteProtocol.write(connection.output(),message);
    }
    private static JSONObject read(PeerTransport.Connection connection) throws IOException {
        connection.setReadTimeoutMillis(10_000);return RemoteProtocol.read(connection.input());
    }
}
