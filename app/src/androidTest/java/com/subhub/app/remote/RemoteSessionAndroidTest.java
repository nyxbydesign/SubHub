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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class RemoteSessionAndroidTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private String domName,subName,generation;
    private File root;
    private RemoteStore dom,sub;
    private PeerTransport dt,st;
    private FixtureHooks dh,sh;
    private ExecutorService server;

    @Before public void setup() throws Exception {
        domName="session-dom-"+UUID.randomUUID();subName="session-sub-"+UUID.randomUUID();
        dom=new RemoteStore(context,domName);sub=new RemoteStore(context,subName);
        dt=IrohPeerTransport.open(context,seed(),new String[0],10_000);
        st=IrohPeerTransport.open(context,seed(),new String[0],10_000);
        PairingInvite invite=PairingInvite.create(dt.localIdentity(),dt.inviteRoute(),1000);generation=invite.id;
        dom.saveInvite(invite);dom.bindInvite(invite.id,st.localIdentity(),invite.tokenHash(),1001);
        sub.acceptInvite(invite,"Dom");dom.approveInvite(invite.id,st.localIdentity(),"Sub",1001);
        dom.activate(st.localIdentity(),generation);sub.activate(dt.localIdentity(),generation);
        root=new File(context.getCacheDir(),"remote-session-"+UUID.randomUUID());
        dh=new FixtureHooks(dom,new RemoteAssets(new File(root,"dom")));
        sh=new FixtureHooks(sub,new RemoteAssets(new File(root,"sub")));
        server=Executors.newSingleThreadExecutor();
    }
    @After public void cleanup() throws Exception {
        server.shutdownNow();dt.close();st.close();server.awaitTermination(5,TimeUnit.SECONDS);
        dom.close();sub.close();context.deleteDatabase(domName);context.deleteDatabase(subName);
        if(root.getCanonicalPath().startsWith(context.getCacheDir().getCanonicalPath()+File.separator)) {
            try(java.util.stream.Stream<java.nio.file.Path> paths=Files.walk(root.toPath())) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {try{Files.delete(path);}catch(IOException error){throw new RuntimeException(error);}});
            }
        }
    }

    @Test public void backgroundSubCanInitiateBidirectionalAcknowledgedExchange() throws Exception {
        dom.publish(st.localIdentity(),generation,RemoteProtocol.Stream.CONFIGURATION,"desired",new JSONObject().put("revision",1),false,1000);
        sub.publish(dt.localIdentity(),generation,RemoteProtocol.Stream.STATS,"summary",new JSONObject().put("count",42),false,1000);
        exchange(false,false);
        assertEquals(42,dom.mirrored(st.localIdentity(),generation,RemoteProtocol.Stream.STATS,"summary").getInt("count"));
        assertEquals(1,sub.mirrored(dt.localIdentity(),generation,RemoteProtocol.Stream.CONFIGURATION,"desired").getInt("revision"));
        assertTrue(dom.nextBatch(st.localIdentity(),generation,RemoteProtocol.Stream.CONFIGURATION).changes.isEmpty());
        assertTrue(sub.nextBatch(dt.localIdentity(),generation,RemoteProtocol.Stream.STATS).changes.isEmpty());
        assertTrue(dom.peer(st.localIdentity(),generation).lastSyncedAt>0);
        assertTrue(sub.peer(dt.localIdentity(),generation).lastSyncedAt>0);
    }

    @Test public void floatingContractNumbersKeepTheirAcknowledgementDigest() throws Exception {
        JSONObject document=new JSONObject().put("whole",1.0f).put("negativeZero",-0.0).put("fraction",0.2f).put("large",Long.MAX_VALUE);
        sub.publish(dt.localIdentity(),generation,RemoteProtocol.Stream.BASELINE,"active",document,false,1);
        exchange(false,false);assertTrue(sub.nextBatch(dt.localIdentity(),generation,RemoteProtocol.Stream.BASELINE).changes.isEmpty());
        JSONObject received=dom.mirrored(st.localIdentity(),generation,RemoteProtocol.Stream.BASELINE,"active");
        assertEquals(1,received.getInt("whole"));assertEquals(0.2,received.getDouble("fraction"),0.00001);assertEquals(Long.MAX_VALUE,received.getLong("large"));
    }

    @Test public void disconnectAfterCommitBeforeAckReplaysWithoutDuplicatingData() throws Exception {
        sub.publish(dt.localIdentity(),generation,RemoteProtocol.Stream.STATS,"summary",new JSONObject().put("count",7),false,1000);
        assertThrows(IOException.class, () -> exchange(false,true));
        assertEquals(0,sub.nextBatch(dt.localIdentity(),generation,RemoteProtocol.Stream.STATS).after);
        assertEquals(1,dom.mirror(st.localIdentity(),generation,RemoteProtocol.Stream.STATS).size());
        exchange(false,false);
        assertEquals(1,dom.mirror(st.localIdentity(),generation,RemoteProtocol.Stream.STATS).size());
        assertEquals(7,dom.mirrored(st.localIdentity(),generation,RemoteProtocol.Stream.STATS,"summary").getInt("count"));
        assertTrue(sub.nextBatch(dt.localIdentity(),generation,RemoteProtocol.Stream.STATS).changes.isEmpty());
    }

    @Test public void assetResumeContinuesAfterMetadataWasAlreadyAcknowledged() throws Exception {
        File source=new File(root,"source.bin");Random random=new Random(17);
        long length=5L*1024*1024;
        try(OutputStream output=new FileOutputStream(source)) {
            byte[] buffer=new byte[RemoteAssets.CHUNK_BYTES];
            for(long left=length;left>0;left-=buffer.length) {random.nextBytes(buffer);output.write(buffer);}
        }
        String hash=RemoteAssets.digest(source);dh.assets.reserve(hash,length);
        try(InputStream input=new FileInputStream(source)) {
            byte[] buffer=new byte[RemoteAssets.CHUNK_BYTES];int count;long offset=0;
            while((count=input.read(buffer))!=-1) {offset=dh.assets.append(hash,offset,java.util.Arrays.copyOf(buffer,count));}
        }
        dh.assets.finish(hash);
        dom.publish(st.localIdentity(),generation,RemoteProtocol.Stream.CONFIGURATION,"fixture",new JSONObject().put("hash",hash).put("length",length),false,1000);
        exchange(true,false);
        assertFalse(sh.assets.isComplete(hash));
        assertEquals(4L*1024*1024,sh.assets.offset(hash));
        assertTrue(dom.nextBatch(st.localIdentity(),generation,RemoteProtocol.Stream.CONFIGURATION).changes.isEmpty());
        exchange(true,false);
        assertTrue(sh.assets.isComplete(hash));assertEquals(hash,RemoteAssets.digest(sh.assets.finish(hash)));
    }

    @Test public void offlineRevocationIsDeliveredBeforeAnyApplicationData() throws Exception {
        sub.revoke(dt.localIdentity(),generation);
        exchange(true,false);
        assertEquals(RemoteStore.State.REVOKED,dom.peer(st.localIdentity(),generation).state);
        assertEquals(0,dh.prepares);assertEquals(0,sh.prepares);
    }

    @Test public void revokedDomCanNotifyAnActiveSubWithoutWaitingForItToInitiate() throws Exception {
        dom.revoke(st.localIdentity(),generation);
        Future<?> receive=server.submit(()->{try(PeerTransport.Connection incoming=st.accept(10_000)) {
            JSONObject first=RemoteProtocol.read(incoming.input());new RemoteSession(sub,st,sh).receiveRevocation(incoming,first,System.nanoTime()+TimeUnit.SECONDS.toNanos(20));
        }catch(Exception failure){throw new RuntimeException(failure);}});
        try(PeerTransport.Connection outgoing=dt.connect(st.localIdentity(),st.inviteRoute(),10_000)) {
            new RemoteSession(dom,dt,dh).sendRevocation(outgoing,dom.peer(st.localIdentity(),generation),System.nanoTime()+TimeUnit.SECONDS.toNanos(20));
        }
        receive.get(25,TimeUnit.SECONDS);assertEquals(RemoteStore.State.REVOKED,sub.peer(dt.localIdentity(),generation).state);
        assertEquals("true",dom.metadata(st.localIdentity(),generation,"revocation_delivered","false"));
    }
    @Test public void activityFactsSurviveLostReceiptWithoutDuplicateInboxRows() throws Exception {
        long now=System.currentTimeMillis();com.subhub.app.activity.ActivityEvent event=new com.subhub.app.activity.ActivityEvent("activity_fixture",com.subhub.app.activity.ActivityEvent.Kind.APP_OPENED,now,"com.example.selected",0,"","",0);
        sub.publish(dt.localIdentity(),generation,RemoteProtocol.Stream.EVENTS,event.id,RemoteActivityWire.encode(event,"UTC",""),false,now);
        assertThrows(IOException.class,()->exchange(false,true));
        assertEquals(0,sub.nextBatch(dt.localIdentity(),generation,RemoteProtocol.Stream.EVENTS).after);
        try(android.database.Cursor rows=dom.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM activity_receipts",null)){assertTrue(rows.moveToFirst());assertEquals(1,rows.getInt(0));}
        exchange(false,false);
        assertTrue(sub.nextBatch(dt.localIdentity(),generation,RemoteProtocol.Stream.EVENTS).changes.isEmpty());
        try(android.database.Cursor rows=dom.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM activity_receipts",null)){assertTrue(rows.moveToFirst());assertEquals(1,rows.getInt(0));}
    }
    @Test public void legacyPeerContinuesStatisticsWithoutReceivingOrAcknowledgingEvents() throws Exception {
        long now=System.currentTimeMillis();com.subhub.app.activity.ActivityEvent event=new com.subhub.app.activity.ActivityEvent("activity_fixture",com.subhub.app.activity.ActivityEvent.Kind.APP_OPENED,now,"com.example.selected",0,"","",0);
        sub.publish(dt.localIdentity(),generation,RemoteProtocol.Stream.EVENTS,event.id,RemoteActivityWire.encode(event,"UTC",""),false,now);
        sub.publish(dt.localIdentity(),generation,RemoteProtocol.Stream.STATS,"summary",new JSONObject().put("count",32),false,now);
        exchange(false,false,false);
        assertEquals(32,dom.mirrored(st.localIdentity(),generation,RemoteProtocol.Stream.STATS,"summary").getInt("count"));
        assertTrue(dom.mirror(st.localIdentity(),generation,RemoteProtocol.Stream.EVENTS).isEmpty());
        assertEquals(1,sub.nextBatch(dt.localIdentity(),generation,RemoteProtocol.Stream.EVENTS).changes.size());
        assertTrue(sub.nextBatch(dt.localIdentity(),generation,RemoteProtocol.Stream.STATS).changes.isEmpty());
    }
    private void exchange(boolean domInitiates,boolean dropAck) throws Exception { exchange(domInitiates,dropAck,true); }
    private void exchange(boolean domInitiates,boolean dropAck,boolean activity) throws Exception {
        PeerTransport source=domInitiates?dt:st,target=domInitiates?st:dt;
        RemoteStore sourceStore=domInitiates?dom:sub,targetStore=domInitiates?sub:dom;
        FixtureHooks sourceHooks=domInitiates?dh:sh,targetHooks=domInitiates?sh:dh;
        RemoteSession outgoing=new RemoteSession(sourceStore,source,sourceHooks),incoming=new RemoteSession(targetStore,target,targetHooks);
        Future<Void> serving=server.submit(() -> {
            try(PeerTransport.Connection connection=target.accept(10_000)) {
                PeerTransport.Connection observed=dropAck?new DropAck(connection):connection;
                JSONObject hello=RemoteProtocol.read(observed.input());if(!activity)hello.remove("activityVersion");
                incoming.incoming(observed,hello,System.nanoTime()+60_000_000_000L);
            }
            return null;
        });
        try(PeerTransport.Connection connection=source.connect(target.localIdentity(),target.inviteRoute(),10_000)) {
            try {
                outgoing.outgoing(connection,sourceStore.peer(target.localIdentity(),generation),System.nanoTime()+60_000_000_000L);
                serving.get(60,TimeUnit.SECONDS);
            } catch(IOException failure) {
                if(dropAck) {
                    try{serving.get(10,TimeUnit.SECONDS);}catch(java.util.concurrent.ExecutionException expected){assertTrue(expected.getCause() instanceof IOException);}
                }
                throw failure;
            }
        }
    }
    private static byte[] seed(){byte[] value=new byte[32];new SecureRandom().nextBytes(value);return value;}

    private static final class FixtureHooks implements RemoteSession.Hooks {
        final RemoteStore store;final RemoteAssets assets;int prepares;
        FixtureHooks(RemoteStore store,RemoteAssets assets){this.store=store;this.assets=assets;}
        public void prepare(RemoteStore.Peer peer){prepares++;}
        public void received(RemoteStore.Peer peer,RemoteProtocol.Stream stream){}
        public List<RemoteSession.AssetNeed> missingAssets(RemoteStore.Peer peer) throws Exception {
            JSONObject manifest=store.mirrored(peer.identity,peer.generation,RemoteProtocol.Stream.CONFIGURATION,"fixture");
            if(manifest==null)return Collections.emptyList();
            String hash=manifest.getString("hash");long length=manifest.getLong("length");assets.reserve(hash,length);
            return assets.isComplete(hash)?Collections.emptyList():Collections.singletonList(new RemoteSession.AssetNeed(hash,length,assets.offset(hash)));
        }
        public RemoteAssets assets(RemoteStore.Peer peer){return assets;}
        public void complete(RemoteStore.Peer peer){}
    }
    private static final class DropAck implements PeerTransport.Connection {
        final PeerTransport.Connection wrapped;final OutputStream output;
        DropAck(PeerTransport.Connection wrapped){this.wrapped=wrapped;
            output=new OutputStream(){final ByteArrayOutputStream pending=new ByteArrayOutputStream();
                public void write(int value){pending.write(value);}
                public void write(byte[] value,int offset,int length){pending.write(value,offset,length);}
                public void flush() throws IOException{
                    JSONObject frame=RemoteProtocol.read(new ByteArrayInputStream(pending.toByteArray()));
                    if("ack".equals(frame.optString("type")))throw new IOException("Fixture lost acknowledgement");
                    wrapped.output().write(pending.toByteArray());wrapped.output().flush();pending.reset();
                }};
        }
        public String authenticatedPeerIdentity(){return wrapped.authenticatedPeerIdentity();}
        public InputStream input() throws IOException{return wrapped.input();}
        public OutputStream output(){return output;}
        public void setReadTimeoutMillis(long value){wrapped.setReadTimeoutMillis(value);}
        public boolean isDirect(){return wrapped.isDirect();}
        public void close() throws IOException{wrapped.close();}
    }
}
