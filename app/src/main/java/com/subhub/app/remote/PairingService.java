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
    private final RemoteKeyholder authenticator;
    private final BooleanSupplier keyholder,domWorkspace;
    private final String deviceName;
    private final PairingLifecycleClock.Source clock;

    public PairingService(RemoteStore store,RemoteSecrets secrets,PeerTransport transport,RemoteKeyholder authenticator,
            BooleanSupplier keyholder,String deviceName) {
        this(store,secrets,transport,authenticator,keyholder,keyholder,deviceName);
    }
    public PairingService(RemoteStore store,RemoteSecrets secrets,PeerTransport transport,RemoteKeyholder authenticator,
            BooleanSupplier keyholder,BooleanSupplier domWorkspace,String deviceName) {
        this(store,secrets,transport,authenticator,keyholder,domWorkspace,deviceName,PairingLifecycleClock.source(store.applicationContext()));
    }
    PairingService(RemoteStore store,RemoteSecrets secrets,PeerTransport transport,RemoteKeyholder authenticator,
            BooleanSupplier keyholder,BooleanSupplier domWorkspace,String deviceName,PairingLifecycleClock.Source clock) {
        this.store=store;this.secrets=secrets;this.transport=transport;this.authenticator=java.util.Objects.requireNonNull(authenticator);this.keyholder=keyholder;this.domWorkspace=domWorkspace;
        this.deviceName=RemoteProtocol.deviceName(deviceName);this.clock=java.util.Objects.requireNonNull(clock);
    }

    PairingInvite ensureInvitation(java.util.function.Consumer<java.util.List<String>> committedRetirements) throws IOException,GeneralSecurityException {
        requireDomWorkspace();FormalRelationshipClock.Sample observed=observe();
        return store.pairingOperation(()->{
            java.util.List<String> inventory=store.creationGenerations();
            java.util.Map<String,String> saved=new java.util.HashMap<>();PairingInvite retained=null;
            String identity=transport.localIdentity();
            for(String generation:inventory){
                if(store.activeInvitationGeneration(generation))continue;
                String link=secrets.pendingLink(generation);saved.put(generation,link);
                PairingLifecycleLedger.Operation operation=store.pairingOperation(generation,generation,PairingLifecycleClock.Direction.CREATE);
                if(retained!=null||operation==null||operation.verdict(clock.transition(observed))!=PairingLifecycleClock.Verdict.LIVE
                        ||PairingLifecycleClock.publicVerdict(operation.bounds,clock.transition(observed))!=PairingLifecycleClock.Verdict.LIVE||link==null)continue;
                PairingInvite invite;
                try{invite=PairingInvite.restore(link);}catch(IllegalArgumentException invalid){continue;}
                if(invite.id.equals(generation)&&invite.domIdentity.equals(identity)&&invite.tokenHash().equals(operation.tokenHash)
                        &&invite.expiresAt==operation.bounds.publicWall&&store.shareableInvitation(operation,invite))retained=invite;
            }
            java.util.List<String> retired=store.retirePendingInvitations(inventory,retained==null?null:retained.id,observed,clock,committedRetirements);
            // Retirement is durable before its staged secrets are removed. ACTIVE generations never enter this list.
            for(String generation:retired)secrets.removePendingIfMatches(generation,saved.get(generation));
            requireDomWorkspace();
            if(retained!=null){
                PairingLifecycleLedger.Operation current=store.pairingOperation(retained.id,retained.id,PairingLifecycleClock.Direction.CREATE);
                FormalRelationshipClock.Sample boundary=clock.transition(observed);
                if(current!=null&&current.verdict(boundary)==PairingLifecycleClock.Verdict.LIVE
                        &&PairingLifecycleClock.publicVerdict(current.bounds,boundary)==PairingLifecycleClock.Verdict.LIVE
                        &&store.shareableInvitation(current,retained))return retained;
                // Cleanup may cross the original deadline. Retire that exact pending attempt once, never renew it.
                java.util.List<String> latest=store.creationGenerations();
                for(String generation:latest)if(!store.activeInvitationGeneration(generation)&&!saved.containsKey(generation))saved.put(generation,secrets.pendingLink(generation));
                java.util.List<String> expired=store.retirePendingInvitations(latest,null,boundary,clock,committedRetirements);
                for(String generation:expired)secrets.removePendingIfMatches(generation,saved.get(generation));
            }
            requireDomWorkspace();return createObserved(clock.transition(observed));
        });
    }
    public PairingInvite create(long now) throws IOException,GeneralSecurityException {
        requireDomWorkspace();return createObserved(observe(now));
    }
    private PairingInvite createObserved(FormalRelationshipClock.Sample observed) throws IOException,GeneralSecurityException {
        requireDomWorkspace();observed.requireValid();
        return store.pairingOperation(()->{
            PairingLifecycleLedger.Operation current=store.currentPairingOperation(PairingLifecycleClock.Direction.CREATE);
            if(current!=null){
                PairingLifecycleClock.Verdict verdict=current.verdict(clock.transition(observed));
                if(verdict==PairingLifecycleClock.Verdict.LIVE){
                    if(current.stage!=PairingLifecycleLedger.Stage.PENDING)throw new SecurityException("Approved pairing awaits completion");
                    PairingInvite retained=savedInvitation(current.id,transport.localIdentity(),current.tokenHash);
                    if(retained.expiresAt!=current.bounds.publicWall)throw new SecurityException("Original invitation changed");
                    requireDomWorkspace();return retained;
                }
                if(verdict==PairingLifecycleClock.Verdict.UNCERTAIN)throw new SecurityException("Resolve the unavailable invitation first");
                store.expirePairingOperation(current.id,current.generation,current.direction,observed,clock);
            }
            if(store.legacyPendingGeneration(PairingLifecycleClock.Direction.CREATE)!=null)
                throw new SecurityException("Resolve the retained pairing first");
            PairingInvite invite=PairingInvite.create(transport.localIdentity(),transport.inviteRoute(),observed.wall);
            PairingLifecycleClock.begin(observed,invite.expiresAt);requireDomWorkspace();
            String prior=secrets.pendingLink(invite.id);
            if(prior!=null&&!prior.equals(invite.link()))throw new SecurityException("Invitation changed");
            if(prior==null)secrets.savePending(invite);
            try { store.saveTimedInvite(invite,observed,clock); }
            catch(RuntimeException failure) {
                // The SQL outcome can be committed but not yet sealed. Its secret still belongs to this invite.
                if(prior==null&&LinkingWitness.acknowledgedRejection(failure)&&!store.hasInvite(invite.id)){
                    try{secrets.removePendingIfMatches(invite.id,invite.link());}catch(GeneralSecurityException cleanup){failure.addSuppressed(cleanup);}
                }
                throw failure;
            }
            return invite;
        });
    }

    public void accept(String link,String name,long now) throws GeneralSecurityException {
        requireKeyholder();FormalRelationshipClock.Sample observed=observe(now);
        acceptConfirmed(PairingInvite.parse(link,observed.wall),name,observed);
    }
    void acceptConfirmed(PairingInvite invite,String name)throws GeneralSecurityException {
        FormalRelationshipClock.Sample observed=observe();
        PairingInvite.parse(invite.link(),observed.wall);acceptConfirmed(invite,name,observed);
    }
    private void acceptConfirmed(PairingInvite invite,String name,FormalRelationshipClock.Sample observed)throws GeneralSecurityException {
        if(invite.domIdentity.equals(transport.localIdentity())) throw new IllegalArgumentException("Cannot pair with this device");
        PairingLifecycleClock.begin(observed,invite.expiresAt);
        store.pairingOperation(()->{
            if(store.peer(invite.domIdentity,invite.id)!=null)throw new SecurityException("Invitation already used");
            for(RemoteStore.Peer existing:store.peers(false))if(existing.localRole==RemoteProtocol.Role.SUB)throw new SecurityException("A Dom is already assigned or pending");
            String prior=secrets.pendingLink(invite.id);
            if(prior!=null&&!prior.equals(invite.link()))throw new SecurityException("Invitation changed");
            if(prior==null)secrets.savePending(invite);
            try { store.acceptTimedInvite(invite,name,observed,clock); }
            catch(RuntimeException failure) {
                // A committed/uncertain outcome owns its staged secret; never delete another attempt's token.
                if(prior==null&&LinkingWitness.acknowledgedRejection(failure)&&store.peer(invite.domIdentity,invite.id)==null){
                    try{secrets.removePendingIfMatches(invite.id,invite.link());}catch(GeneralSecurityException cleanup){failure.addSuppressed(cleanup);}
                }throw failure;
            }
            return null;
        });
    }

    public void approve(String invitation,String candidate,String fingerprint,long now) {
        requireDomWorkspace();FormalRelationshipClock.Sample observed=observe(now);
        String expected=PairingInvite.fingerprint(transport.localIdentity(),candidate,invitation);
        if(!expected.equals(fingerprint)) throw new SecurityException("Pairing identity changed");
        store.approveTimedInvite(invitation,candidate,transport.localIdentity(),fingerprint,observed,clock);
    }

    public void cancel(String invitation) {
        requireDomWorkspace();
        FormalRelationshipClock.Sample observed=clock.observe();
        store.pairingOperation(()->{String expected=secrets.pendingLink(invitation);
            if(store.cancelPairingOperation(invitation,invitation,PairingLifecycleClock.Direction.CREATE,null,observed,clock))
                secrets.removePendingIfMatches(invitation,expected);return null;});
    }

    public void revoke(String identity,String generation) {
        RemoteStore.Peer selected=store.authorityObservation(()->store.peer(identity,generation));
        if(selected==null)throw new SecurityException("Relationship unavailable");
        if(selected.localRole==RemoteProtocol.Role.DOM)requireDomWorkspace();else requireKeyholder();
        FormalRelationshipClock.Sample observed=observe();
        long revision=com.subhub.app.security.ControllerPinManager.domWorkspaceRevision();
        store.pairingOperation(()->{
            RemoteStore.Peer current=store.peer(identity,generation);
            if(current==null||current.localRole!=selected.localRole||revision!=com.subhub.app.security.ControllerPinManager.domWorkspaceRevision())
                throw new SecurityException("Relationship changed");
            if(current.localRole==RemoteProtocol.Role.DOM)requireDomWorkspace();else requireKeyholder();
            String candidate=secrets.pendingLink(generation);String expected=store.ownsPendingSecret(identity,generation,candidate)?candidate:null;
            if(current.state==RemoteStore.State.PENDING)
                store.cancelPairingOperation(generation,generation,current.localRole==RemoteProtocol.Role.DOM?PairingLifecycleClock.Direction.CREATE:PairingLifecycleClock.Direction.ACCEPT,null,observed,clock);
            else store.revoke(identity,generation);
            secrets.removePendingIfMatches(generation,expected);return null;
        });
    }

    boolean resume(PeerTransport.Connection connection,RemoteStore.Peer peer) throws IOException,GeneralSecurityException {
        requireCurrent(peer,RemoteProtocol.Role.SUB,RemoteStore.State.PENDING,connection);
        FormalRelationshipClock.Sample observed=observe();
        PairingLifecycleLedger.Operation operation=store.pairingOperation(peer.generation,peer.generation,PairingLifecycleClock.Direction.ACCEPT);
        String link=store.authorityAccess(()->secrets.pendingLink(peer.generation));
        PairingInvite invite=link==null?null:PairingInvite.restore(link);
        if(invite!=null&&(!invite.domIdentity.equals(peer.identity)||!invite.id.equals(peer.generation)))throw new SecurityException("Pairing identity mismatch");
        PairingCompletionProof.Offer offer=PairingCompletionProof.offer(peer.identity,transport.localIdentity(),peer.generation,observed);
        boolean bearer=operation!=null&&operation.verdict(clock.transition(observed))==PairingLifecycleClock.Verdict.LIVE&&invite!=null;
        if(bearer)operation.requireToken(invite.tokenHash());
        try {
            JSONObject request=context(bearer?"pair_request":"pair_recover",offer);
            if(bearer)request.put("invite",invite.id).put("secret",invite.secret()).put("name",deviceName).put("route",transport.inviteRoute());
            else existingCredential(peer,RemoteStore.State.PENDING);
            write(connection,request);JSONObject response=read(connection);requireAuthVersion(response);
            if("pair_recover_challenge".equals(response.getString("type")))return recoverSub(connection,peer,offer,response,link);
            if(!bearer)throw new SecurityException("Committed pairing outcome unavailable");
            if("pair_pending".equals(response.getString("type"))){
                if(!peer.generation.equals(response.getString("generation")))throw new SecurityException("Pairing identity changed");
                write(connection,frame("pair_pending_ack",peer.generation));return false;
            }
            String fingerprint=PairingInvite.fingerprint(peer.identity,transport.localIdentity(),peer.generation);
            if(!"pair_approved".equals(response.getString("type"))||!peer.generation.equals(response.getString("generation"))
                    ||!fingerprint.equals(response.getString("fingerprint")))throw new SecurityException("Pairing approval mismatch");
            FormalRelationshipClock.Sample approval=observe();
            store.observeTimedApproval(peer,invite.tokenHash(),transport.localIdentity(),fingerprint,approval,clock);
            String tokenHash=invite.tokenHash();
            String authSecret=store.authorityAccess(()->{
                RemoteStore.Peer current=store.requireTimedCompletion(peer,tokenHash,approval,clock);
                String secret=authenticator.provisionSub(current,transport.localIdentity());
                store.requireTimedCompletion(peer,tokenHash,approval,clock);return secret;
            });
            write(connection,frame("pair_confirmed",peer.generation).put("fingerprint",fingerprint).put("authSecret",authSecret));
            JSONObject ready=read(connection);requireAuthVersion(ready);
            if(!"pair_ready".equals(ready.getString("type"))||!peer.generation.equals(ready.getString("generation")))throw new SecurityException("Pairing completion mismatch");
            store.activateTimedPeer(peer,tokenHash,observe(),clock);com.subhub.app.security.ControllerPinManager.lockNow();
            cleanupPending(peer,link);
            try{write(connection,frame("pair_ready_ack",peer.generation));}catch(IOException alreadyCommitted){/* Committed state survives a lost receipt. */}
            return true;
        }catch(JSONException invalid){throw new IOException("Invalid pairing message");}
    }

    boolean handle(PeerTransport.Connection connection,JSONObject request,long now) throws IOException {
        String candidate=RemoteProtocol.peerId(connection.authenticatedPeerIdentity());
        FormalRelationshipClock.Sample observed=observe(now);
        try {
            requireAuthVersion(request);String type=request.getString("type");
            if(!"pair_request".equals(type)&&!"pair_recover".equals(type))throw new JSONException("Expected pairing request");
            String id=RemoteProtocol.identifier(request.getString("pair_request".equals(type)?"invite":"generation"));
            RemoteStore.Peer existing=store.authorityObservation(()->store.peer(candidate,id));
            if(existing!=null&&existing.localRole==RemoteProtocol.Role.DOM&&existing.state==RemoteStore.State.ACTIVE)
                return recoverDom(connection,existing,request,observed);
            if(!"pair_request".equals(type))throw new SecurityException("Committed pairing outcome unavailable");
            if(existing!=null&&(existing.localRole!=RemoteProtocol.Role.DOM||existing.state!=RemoteStore.State.PENDING))throw new SecurityException("Pairing unavailable");
            byte[] token=Base64.getUrlDecoder().decode(request.getString("secret"));
            String tokenHash;
            try{if(token.length!=32)throw new SecurityException("Invalid pairing secret");tokenHash=RemoteProtocol.sha256(token);}
            finally{java.util.Arrays.fill(token,(byte)0);}
            PairingInvite saved=savedInvitation(id,transport.localIdentity(),tokenHash);
            PairingLifecycleLedger.Operation operation=store.pairingOperation(id,id,PairingLifecycleClock.Direction.CREATE);
            if(operation==null||saved.expiresAt!=operation.bounds.publicWall)throw new SecurityException("Original invitation unavailable");
            if(!store.timedApproval(id,candidate,tokenHash,observed,clock)){
                store.bindTimedInvite(id,candidate,tokenHash,observed,clock);
                store.describeCandidate(id,candidate,request.getString("name"),request.getString("route"));
                write(connection,frame("pair_pending",id));
                try{JSONObject receipt=read(connection);requireAuthVersion(receipt);if(!"pair_pending_ack".equals(receipt.getString("type"))||!id.equals(receipt.getString("generation")))throw new SecurityException("Pairing receipt mismatch");}
                catch(IOException closedAfterReply){/* The persisted claim survives a lost receipt. */}return false;
            }
            String fingerprint=PairingInvite.fingerprint(transport.localIdentity(),candidate,id);
            write(connection,frame("pair_approved",id).put("fingerprint",fingerprint));
            JSONObject confirmed=read(connection);requireAuthVersion(confirmed);
            if(!"pair_confirmed".equals(confirmed.getString("type"))||!id.equals(confirmed.getString("generation"))
                    ||!fingerprint.equals(confirmed.getString("fingerprint")))throw new SecurityException("Pairing confirmation mismatch");
            String authSecret=confirmed.getString("authSecret");FormalRelationshipClock.Sample completion=observe();
            RemoteStore.Peer selected=store.authorityObservation(()->store.peer(candidate,id));
            if(selected==null)throw new SecurityException("Original pending pairing unavailable");
            store.authorityAccess(()->{
                RemoteStore.Peer current=store.requireTimedCompletion(selected,tokenHash,completion,clock);
                authenticator.receiveDom(current,transport.localIdentity(),authSecret);
                store.requireTimedCompletion(selected,tokenHash,completion,clock);return null;
            });
            store.activateTimedPeer(selected,tokenHash,observe(),clock);
            store.setRoute(candidate,id,request.getString("route"));
            write(connection,frame("pair_ready",id));cleanupPending(selected,saved.link());
            try{JSONObject receipt=read(connection);requireAuthVersion(receipt);if(!"pair_ready_ack".equals(receipt.getString("type"))||!id.equals(receipt.getString("generation")))throw new SecurityException("Pairing receipt mismatch");}
            catch(IOException closedAfterReady){/* A fresh credential proof can recover a lost ready frame. */}return true;
        }catch(JSONException|IllegalArgumentException invalid){throw new IOException("Invalid pairing message");}
        catch(GeneralSecurityException unavailable){throw new IOException("Pairing credential unavailable",unavailable);}
    }

    private boolean recoverSub(PeerTransport.Connection connection,RemoteStore.Peer peer,PairingCompletionProof.Offer offer,
            JSONObject challenge,String pendingLink)throws IOException,GeneralSecurityException,JSONException {
        requireContext(challenge,offer);String secret=existingCredential(peer,RemoteStore.State.PENDING);
        PairingCompletionProof.Exchange exchange=offer.challenge(challenge.getString("serverNonce"));
        exchange.verifyActive(secret,challenge.getString("proof"),observe());
        write(connection,context("pair_recover_proof",offer).put("serverNonce",exchange.serverNonce).put("proof",exchange.pending(secret)));
        JSONObject ready=read(connection);requireContext(ready,offer);
        if(!"pair_recover_ready".equals(ready.getString("type"))||!exchange.serverNonce.equals(ready.getString("serverNonce")))throw new SecurityException("Pairing outcome context changed");
        secret=existingCredential(peer,RemoteStore.State.PENDING);
        FormalRelationshipClock.Sample verifiedAt=observe();
        PairingCompletionProof.Verified verified=exchange.verifyReady(secret,ready.getString("proof"),verifiedAt);
        final String digest=PairingCompletionProof.credentialDigest(secret);
        // Re-read the credential under the same authority gate as the final SQL transition.
        store.pairingOperation(()->{
            String current=existingCredential(peer,RemoteStore.State.PENDING);
            if(!digest.equals(PairingCompletionProof.credentialDigest(current)))throw new SecurityException("Pairing credential changed");
            store.activateRecoveredPeer(transport.localIdentity(),verified,digest,verifiedAt,clock);return null;
        });
        com.subhub.app.security.ControllerPinManager.lockNow();cleanupPending(peer,pendingLink);
        try{write(connection,context("pair_recover_ack",offer).put("serverNonce",exchange.serverNonce));}
        catch(IOException alreadyCommitted){/* Outcome was already applied durably. */}return true;
    }
    private boolean recoverDom(PeerTransport.Connection connection,RemoteStore.Peer peer,JSONObject request,
            FormalRelationshipClock.Sample observed)throws IOException,GeneralSecurityException,JSONException {
        if(RemoteProtocol.integer(request,"recoveryVersion")!=1)throw new SecurityException("Fresh pairing outcome proof required");
        PairingCompletionProof.Offer offer=new PairingCompletionProof.Offer(transport.localIdentity(),peer.identity,peer.generation,request.getString("clientNonce"),observed);
        requireContext(request,offer);requireCurrent(peer,RemoteProtocol.Role.DOM,RemoteStore.State.ACTIVE,connection);
        String secret=existingCredential(peer,RemoteStore.State.ACTIVE);
        PairingCompletionProof.Exchange exchange=offer.challenge(PairingCompletionProof.freshNonce());
        write(connection,context("pair_recover_challenge",offer).put("serverNonce",exchange.serverNonce).put("proof",exchange.active(secret)));
        JSONObject confirmed=read(connection);requireContext(confirmed,offer);
        if(!"pair_recover_proof".equals(confirmed.getString("type"))||!exchange.serverNonce.equals(confirmed.getString("serverNonce")))throw new SecurityException("Pairing outcome context changed");
        secret=existingCredential(peer,RemoteStore.State.ACTIVE);exchange.verifyPending(secret,confirmed.getString("proof"),observe());
        write(connection,context("pair_recover_ready",offer).put("serverNonce",exchange.serverNonce).put("proof",exchange.ready(secret)));
        try{JSONObject ack=read(connection);requireContext(ack,offer);if(!"pair_recover_ack".equals(ack.getString("type"))||!exchange.serverNonce.equals(ack.getString("serverNonce")))throw new SecurityException("Pairing outcome receipt differs");}
        catch(IOException lostReceipt){/* No new relationship authority was granted on the Dom. */}return true;
    }
    private String existingCredential(RemoteStore.Peer peer,RemoteStore.State state)throws GeneralSecurityException {
        return store.authorityAccess(()->{
            RemoteStore.Peer current=store.peer(peer.identity,peer.generation);
            if(current==null||current.localRole!=peer.localRole||current.state!=state
                    ||!"1".equals(store.metadata(peer.identity,peer.generation,"controller_auth_v1_provisioned","0")))throw new SecurityException("Existing pairing outcome unavailable");
            PairingLifecycleLedger.Operation operation=store.pairingOperation(peer.generation,peer.generation,
                    peer.localRole==RemoteProtocol.Role.DOM?PairingLifecycleClock.Direction.CREATE:PairingLifecycleClock.Direction.ACCEPT);
            if(operation!=null&&(operation.stage==PairingLifecycleLedger.Stage.CANCELLED||operation.peer==null||!peer.identity.equals(operation.peer)))throw new SecurityException("Pairing operation changed");
            String secret=secrets.controllerSecret(current.localRole,transport.localIdentity(),current.identity,current.generation);
            PairingCompletionProof.credentialDigest(secret);return secret;
        });
    }
    private void requireCurrent(RemoteStore.Peer peer,RemoteProtocol.Role role,RemoteStore.State state,PeerTransport.Connection connection){
        if(peer.localRole!=role||!peer.identity.equals(connection.authenticatedPeerIdentity()))throw new SecurityException("Pairing identity mismatch");
        store.authorityObservation(()->{RemoteStore.Peer current=store.peer(peer.identity,peer.generation);
            if(current==null||current.localRole!=role||current.state!=state)throw new SecurityException("Pairing state changed");return null;});
    }
    private PairingInvite savedInvitation(String id,String dom,String tokenHash)throws GeneralSecurityException {
        return store.authorityAccess(()->{
            String link=secrets.pendingLink(id);if(link==null)throw new GeneralSecurityException("Pairing secret unavailable");
            PairingInvite saved=PairingInvite.restore(link);
            if(!saved.id.equals(id)||!saved.domIdentity.equals(dom)||!saved.tokenHash().equals(tokenHash))throw new SecurityException("Original invitation differs");return saved;
        });
    }
    private void cleanupPending(RemoteStore.Peer peer,String expected)throws GeneralSecurityException {
        store.authorityAccess(()->{secrets.removePendingIfMatches(peer.generation,expected);return null;});
    }
    private FormalRelationshipClock.Sample observe(){FormalRelationshipClock.Sample sample=clock.observe();sample.requireValid();return sample;}
    private FormalRelationshipClock.Sample observe(long wall){
        FormalRelationshipClock.Sample sample=observe();
        if(wall<1||(wall>=sample.wall?wall-sample.wall:sample.wall-wall)>2000)throw new SecurityException("Pairing clock changed");return sample;
    }
    private static JSONObject frame(String type,String generation)throws JSONException {
        return new JSONObject().put("version",1).put("authVersion",1).put("type",type).put("generation",generation);
    }
    private static JSONObject context(String type,PairingCompletionProof.Offer offer)throws JSONException {
        return frame(type,offer.generation).put("recoveryVersion",1).put("dom",offer.dom).put("sub",offer.sub).put("clientNonce",offer.clientNonce);
    }
    private static void requireContext(JSONObject frame,PairingCompletionProof.Offer offer)throws JSONException {
        requireAuthVersion(frame);
        if(RemoteProtocol.integer(frame,"recoveryVersion")!=1||!offer.dom.equals(frame.getString("dom"))||!offer.sub.equals(frame.getString("sub"))
                ||!offer.generation.equals(frame.getString("generation"))||!offer.clientNonce.equals(frame.getString("clientNonce")))throw new SecurityException("Pairing outcome identity differs");
    }

    private static void requireAuthVersion(JSONObject message)throws JSONException {
        RemoteProtocol.requireVersion(message);
        if(RemoteProtocol.integer(message,"authVersion")!=1)throw new SecurityException("Unsupported keyholder provisioning");
    }

    private void requireDomWorkspace() {
        if(!domWorkspace.getAsBoolean())throw new SecurityException("Dom workspace access required");
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
