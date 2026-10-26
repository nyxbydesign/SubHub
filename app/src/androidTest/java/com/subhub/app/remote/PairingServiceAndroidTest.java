package com.subhub.app.remote;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(AndroidJUnit4.class)
public class PairingServiceAndroidTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final List<String> names=new ArrayList<>();
    private final AtomicBoolean domAccess=new AtomicBoolean(true),subAccess=new AtomicBoolean(true);
    private RemoteStore domStore,subStore;
    private PeerTransport domTransport,subTransport;
    private PairingService dom,sub;
    private RemoteSecrets domSecrets,subSecrets;
    private ExecutorService server;

    @Before public void setup() throws Exception {
        String dn="pair-dom-"+UUID.randomUUID(),sn="pair-sub-"+UUID.randomUUID();names.add(dn);names.add(sn);
        domStore=new RemoteStore(context,dn+".db");subStore=new RemoteStore(context,sn+".db");
        domSecrets=new RemoteSecrets(context,dn,dn);subSecrets=new RemoteSecrets(context,sn,sn);
        domTransport=IrohPeerTransport.open(context,seed(),new String[0],10_000);
        subTransport=IrohPeerTransport.open(context,seed(),new String[0],10_000);
        dom=new PairingService(domStore,domSecrets,domTransport,new RemoteKeyholder(context,domStore,domSecrets),domAccess::get,"Dom fixture");
        sub=new PairingService(subStore,subSecrets,subTransport,new RemoteKeyholder(context,subStore,subSecrets),subAccess::get,"Sub fixture");
        server=Executors.newSingleThreadExecutor();
    }
    @After public void cleanup() throws Exception {
        server.shutdownNow();domTransport.close();subTransport.close();domStore.close();subStore.close();
        KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);
        for(String name:names) {context.deleteDatabase(name+".db");context.getSharedPreferences(name,0).edit().clear().commit();keys.deleteEntry(name);}
    }

    @Test public void twoExplicitApprovalsBindTheNativePeerIdentity() throws Exception {
        PairingInvite invite=dom.create(1000);
        subAccess.set(false);
        assertThrows(SecurityException.class, () -> sub.accept(invite.link(),"Dom fixture",1001));
        assertTrue(subStore.peers(true).isEmpty());
        subAccess.set(true);sub.accept(invite.link(),"Dom fixture",1001);
        assertThrows(SecurityException.class,()->sub.accept(invite.code(),"Dom fixture",1001));
        assertNotNull(subSecrets.pendingLink(invite.id));
        assertFalse(handshake(invite,1002,false));
        assertNull(domStore.peer(subTransport.localIdentity(),invite.id));
        assertEquals(RemoteStore.State.PENDING,subStore.peer(domTransport.localIdentity(),invite.id).state);
        assertTrue(domStore.mirror(subTransport.localIdentity(),invite.id,RemoteProtocol.Stream.STATS).isEmpty());
        String fingerprint=PairingInvite.fingerprint(domTransport.localIdentity(),subTransport.localIdentity(),invite.id);
        domAccess.set(false);
        assertThrows(SecurityException.class, () -> dom.approve(invite.id,subTransport.localIdentity(),fingerprint,1003));
        domAccess.set(true);
        assertThrows(SecurityException.class, () -> dom.approve(invite.id,subTransport.localIdentity(),"wrong fingerprint",1003));
        dom.approve(invite.id,subTransport.localIdentity(),fingerprint,1003);
        assertTrue(handshake(invite,1004,false));
        assertEquals(RemoteStore.State.ACTIVE,domStore.peer(subTransport.localIdentity(),invite.id).state);
        assertEquals(RemoteStore.State.ACTIVE,subStore.peer(domTransport.localIdentity(),invite.id).state);
        assertTrue(subStore.isRemotelyControlled());
        assertNull(subSecrets.pendingLink(invite.id));
        subAccess.set(false);
        assertThrows(SecurityException.class, () -> sub.revoke(domTransport.localIdentity(),invite.id));
        assertTrue(subStore.isRemotelyControlled());
        subAccess.set(true);sub.revoke(domTransport.localIdentity(),invite.id);
        assertFalse(subStore.isRemotelyControlled());
    }

    @Test public void approvedHandshakeResumesAfterLostFinalResponseAndInviteExpiry() throws Exception {
        PairingInvite invite=dom.create(1000);sub.accept(invite.link(),"Dom fixture",1001);
        assertFalse(handshake(invite,1002,false));
        dom.approve(invite.id,subTransport.localIdentity(),
                PairingInvite.fingerprint(domTransport.localIdentity(),subTransport.localIdentity(),invite.id),1003);
        assertThrows(IOException.class, () -> handshake(invite,1004,true));
        assertEquals(RemoteStore.State.ACTIVE,domStore.peer(subTransport.localIdentity(),invite.id).state);
        assertEquals(RemoteStore.State.PENDING,subStore.peer(domTransport.localIdentity(),invite.id).state);
        assertTrue(handshake(invite,invite.expiresAt+1000,false));
        assertEquals(RemoteStore.State.ACTIVE,subStore.peer(domTransport.localIdentity(),invite.id).state);
    }

    @Test public void cancelledApprovedInvitationCannotCompleteOrProvision()throws Exception {
        PairingInvite invite=dom.create(1000);sub.accept(invite.code(),"Dom fixture",1001);assertFalse(handshake(invite,1002,false));
        dom.approve(invite.id,subTransport.localIdentity(),PairingInvite.fingerprint(domTransport.localIdentity(),subTransport.localIdentity(),invite.id),1003);
        dom.cancel(invite.id);assertEquals(RemoteStore.State.REVOKED,domStore.peer(subTransport.localIdentity(),invite.id).state);
        assertFalse(domStore.approvedInvite(invite.id,subTransport.localIdentity(),invite.tokenHash()));
        assertNull(domSecrets.controllerSecret(RemoteProtocol.Role.DOM,subTransport.localIdentity(),invite.id));
        assertThrows(SecurityException.class,()->domStore.bindInvite(invite.id,subTransport.localIdentity(),invite.tokenHash(),1004));
    }
    @Test public void sharedPayloadIsPlainCodeAndOldHandshakeDoesNotProvision()throws Exception {
        PairingInvite invite=dom.create(1000);android.content.Intent shared=PairingActivity.sharePayload(invite);
        assertEquals(android.content.Intent.ACTION_SEND,shared.getAction());assertEquals("text/plain",shared.getType());
        String payload=shared.getStringExtra(android.content.Intent.EXTRA_TEXT);assertTrue(payload!=null&&!payload.contains("subhubapp://"));
        assertTrue(invite.tokenHash().equals(PairingInvite.parse(payload,1001).tokenHash()));
        JSONObject old=new JSONObject().put("version",1).put("type","pair_request").put("invite",invite.id).put("secret",invite.secret()).put("name","Sub fixture").put("route",subTransport.inviteRoute());
        PeerTransport.Connection connection=new PeerTransport.Connection(){
            public String authenticatedPeerIdentity(){return subTransport.localIdentity();}
            public InputStream input(){return new ByteArrayInputStream(new byte[0]);}
            public OutputStream output(){return new ByteArrayOutputStream();}
            public void setReadTimeoutMillis(long timeout){}public boolean isDirect(){return true;}public void close(){}
        };
        assertThrows(IOException.class,()->dom.handle(connection,old,1001));old.put("authVersion",2);assertThrows(SecurityException.class,()->dom.handle(connection,old,1001));assertTrue(domStore.peers(true).isEmpty());
    }

    private boolean handshake(PairingInvite invite,long now,boolean dropReady) throws Exception {
        Future<Boolean> serving=server.submit(() -> {
            try(PeerTransport.Connection incoming=domTransport.accept(10_000)) {
                PeerTransport.Connection observed=dropReady?new DropReady(incoming):incoming;
                JSONObject request=RemoteProtocol.read(observed.input());
                return dom.handle(observed,request,now);
            }
        });
        try(PeerTransport.Connection outgoing=subTransport.connect(domTransport.localIdentity(),invite.route,10_000)) {
            try {
                boolean completed=sub.resume(outgoing,subStore.peer(domTransport.localIdentity(),invite.id));
                assertEquals(completed,serving.get(10,TimeUnit.SECONDS));return completed;
            } catch(IOException failure) {
                if(dropReady) {
                    try {serving.get(10,TimeUnit.SECONDS);}catch(java.util.concurrent.ExecutionException expected) {assertTrue(expected.getCause() instanceof IOException);}
                }
                throw failure;
            }
        }
    }
    private static byte[] seed() {byte[] value=new byte[32];new SecureRandom().nextBytes(value);return value;}
    private static final class DropReady implements PeerTransport.Connection {
        private final PeerTransport.Connection wrapped;
        private final OutputStream output;
        DropReady(PeerTransport.Connection wrapped) {
            this.wrapped=wrapped;
            output=new OutputStream() {
                final ByteArrayOutputStream pending=new ByteArrayOutputStream();
                @Override public void write(int value) {pending.write(value);}
                @Override public void write(byte[] b,int offset,int length) {pending.write(b,offset,length);}
                @Override public void flush() throws IOException {
                    JSONObject frame=RemoteProtocol.read(new ByteArrayInputStream(pending.toByteArray()));
                    if("pair_ready".equals(frame.optString("type"))) throw new IOException("Fixture lost final response");
                    wrapped.output().write(pending.toByteArray());wrapped.output().flush();pending.reset();
                }
            };
        }
        public String authenticatedPeerIdentity() {return wrapped.authenticatedPeerIdentity();}
        public InputStream input() throws IOException {return wrapped.input();}
        public OutputStream output() {return output;}
        public void setReadTimeoutMillis(long timeout) {wrapped.setReadTimeoutMillis(timeout);}
        public boolean isDirect() {return wrapped.isDirect();}
        public void close() throws IOException {wrapped.close();}
    }
}
