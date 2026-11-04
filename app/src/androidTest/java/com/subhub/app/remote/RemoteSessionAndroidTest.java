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
    private WireTrace wireTrace;
    private Object phaseOverride;

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

    @Test public void controlsFollowAppliedCoreBeforeHistoricalAndAssetBacklogForEitherInitiator() throws Exception {
        dh.controls=sh.controls=true;
        dom.publish(st.localIdentity(),generation,RemoteProtocol.Stream.CONFIGURATION,"desired",new JSONObject().put("revision",1),false,1);
        sub.publish(dt.localIdentity(),generation,RemoteProtocol.Stream.RUNTIME,"current",new JSONObject().put("running",true),false,1);
        sub.publish(dt.localIdentity(),generation,RemoteProtocol.Stream.STATS,"history",new JSONObject().put("count",42),false,1);
        dh.beforeControls=()->{
            assertTrue(dh.events.contains("received_RUNTIME"));
            assertTrue(dom.mirrored(st.localIdentity(),generation,RemoteProtocol.Stream.RUNTIME,"current").optBoolean("running"));
            assertNull(dom.mirrored(st.localIdentity(),generation,RemoteProtocol.Stream.STATS,"history"));
        };
        sh.beforeControls=()->{assertTrue(sh.events.contains("received_CONFIGURATION"));assertEquals(1,sub.mirrored(dt.localIdentity(),generation,RemoteProtocol.Stream.CONFIGURATION,"desired").optInt("revision"));};
        exchange(true,false);
        assertEquals(1,dh.controlCalls);assertEquals(1,sh.controlCalls);
        assertFalse(dh.events.contains("assets"));
        assertFalse(sh.events.contains("assets"));
        assertEquals(42,dom.mirrored(st.localIdentity(),generation,RemoteProtocol.Stream.STATS,"history").getInt("count"));
        dh.beforeControls=()->{};dh.events.clear();sh.events.clear();
        exchange(false,false);
        assertEquals(2,dh.controlCalls);assertEquals(2,sh.controlCalls);
        assertFalse(dh.events.contains("assets"));
        assertFalse(sh.events.contains("assets"));
    }

    @Test public void boundedSessionWireBenchmarkReportsVirtualRttWithoutSleeping() throws Exception { wireBenchmark(true); }
    @Test public void legacySessionWireBenchmarkReportsVirtualRttWithoutSleeping() throws Exception { wireBenchmark(false); }

    @Test public void featureSectionReferencesWorkInBothDirectionsForEitherInitiatorAndIdleSendsNone() throws Exception {
        dh.controls=sh.controls=true;
        JSONObject before=sectionConfiguration();seedSectionBasis(before);
        int revision=0;
        for(boolean initiator:new boolean[]{true,false}) {
            JSONObject next=new JSONObject(before.toString());
            next.getJSONObject("contract").getJSONObject("sections").getJSONObject("limits").put("app_timer_per_app_enabled",initiator);
            JSONObject command=new JSONObject().put("id","section-command-"+(++revision)).put("base",revision-1).put("revision",revision).put("configuration",next);
            dom.publish(st.localIdentity(),generation,RemoteProtocol.Stream.CONFIGURATION,"desired",command,false,revision+1000);
            sub.publish(dt.localIdentity(),generation,RemoteProtocol.Stream.BASELINE,"active",next,false,revision+1000);
            wireTrace=new WireTrace();dh.trace=sh.trace=wireTrace;
            exchange(initiator,false);
            assertEquals(2,wireTrace.sectionBatches);assertEquals(0,wireTrace.sectionFallbacks);
            assertEquals(RemoteProtocol.canonical(command),RemoteProtocol.canonical(sub.mirrored(dt.localIdentity(),generation,RemoteProtocol.Stream.CONFIGURATION,"desired")));
            assertEquals(RemoteProtocol.canonical(next),RemoteProtocol.canonical(dom.mirrored(st.localIdentity(),generation,RemoteProtocol.Stream.BASELINE,"active")));
            assertTrue(dom.nextBatch(st.localIdentity(),generation,RemoteProtocol.Stream.CONFIGURATION).changes.isEmpty());
            assertTrue(sub.nextBatch(dt.localIdentity(),generation,RemoteProtocol.Stream.BASELINE).changes.isEmpty());
            System.out.println("WIRE_SECTION_SESSION initiatorDom="+initiator+" wireBytes="+wireTrace.bytes+" sectionBatches="+wireTrace.sectionBatches+" fallbackRoundTrips=0");
            wireTrace=new WireTrace();dh.trace=sh.trace=wireTrace;exchange(initiator,false);
            assertEquals(0,wireTrace.sectionBatches);assertEquals(0,wireTrace.sectionFallbacks);assertEquals(0,wireTrace.dataBatches);
            before=next;
        }
    }

    @Test public void staleSectionBasisFallsBackOnceBeforeCompleteAcknowledgement() throws Exception {
        dh.controls=sh.controls=true;
        JSONObject before=sectionConfiguration();seedSectionBasis(before);
        JSONObject desired=new JSONObject(before.toString());
        desired.getJSONObject("contract").getJSONObject("sections").getJSONObject("limits").put("app_timer_per_app_enabled",true);
        JSONObject changedBasis=new JSONObject(before.toString());
        changedBasis.getJSONObject("contract").getJSONObject("sections").getJSONObject("censor").put("fixture_changed",true);
        sub.publish(dt.localIdentity(),generation,RemoteProtocol.Stream.BASELINE,"active",changedBasis,false,2000);
        JSONObject command=new JSONObject().put("id","stale-section-command").put("base",0).put("revision",1).put("configuration",desired);
        dom.publish(st.localIdentity(),generation,RemoteProtocol.Stream.CONFIGURATION,"desired",command,false,2000);
        wireTrace=new WireTrace();dh.trace=sh.trace=wireTrace;exchange(true,false);
        assertEquals(1,wireTrace.sectionFallbacks);
        assertEquals(RemoteProtocol.canonical(command),RemoteProtocol.canonical(sub.mirrored(dt.localIdentity(),generation,RemoteProtocol.Stream.CONFIGURATION,"desired")));
        assertTrue(dom.nextBatch(st.localIdentity(),generation,RemoteProtocol.Stream.CONFIGURATION).changes.isEmpty());
        System.out.println("WIRE_SECTION_FALLBACK wireBytes="+wireTrace.bytes+" fallbackRequests="+wireTrace.sectionFallbacks);
    }

    private void seedSectionBasis(JSONObject doc)throws Exception {
        sub.publish(dt.localIdentity(),generation,RemoteProtocol.Stream.BASELINE,"active",doc,false,1000);
        RemoteProtocol.Batch baseline=sub.nextBatch(dt.localIdentity(),generation,RemoteProtocol.Stream.BASELINE);
        sub.acknowledge(dt.localIdentity(),dom.receive(st.localIdentity(),baseline));
        JSONObject command=new JSONObject().put("id","initial-sections").put("base",0).put("revision",0).put("configuration",doc);
        dom.publish(st.localIdentity(),generation,RemoteProtocol.Stream.CONFIGURATION,"desired",command,false,1000);
        RemoteProtocol.Batch desired=dom.nextBatch(st.localIdentity(),generation,RemoteProtocol.Stream.CONFIGURATION);
        dom.acknowledge(st.localIdentity(),sub.receive(dt.localIdentity(),desired));
    }

    private JSONObject sectionConfiguration()throws Exception {
        JSONObject sections=new JSONObject();
        for(String section:com.subhub.app.configuration.ConfigurationSchema.SECTIONS)
            sections.put(section,com.subhub.app.configuration.ConfigurationSettingCatalog.defaults(section));
        JSONObject metadata=RemoteConfiguration.currentMetadata("00000000-0000-4000-8000-000000000001",
                "00000000-0000-4000-8000-000000000002","Synthetic section transfer",1,1);
        return RemoteConfiguration.fromSections(metadata,sections,new org.json.JSONArray(),
                new JSONObject().put("all",false).put("packages",new org.json.JSONArray()).put("allowances",new JSONObject()),
                new JSONObject().put("duration",-1).put("minimum",3600000).put("maximum",86400000).put("hidden",false));
    }

    private void wireBenchmark(boolean priority) throws Exception {
        dh.controls=sh.controls=true;
        for(String scenario:new String[]{"idle","runtime","contract_media"}) {
            if(!scenario.equals("idle"))sub.publish(dt.localIdentity(),generation,RemoteProtocol.Stream.RUNTIME,"live",new JSONObject().put("running",true).put("remaining",1800),false,1);
            if(scenario.equals("contract_media")) {
                byte[] bytes=new byte[4*RemoteAssets.CHUNK_BYTES];new Random(2026).nextBytes(bytes);
                String hash=RemoteProtocol.sha256(bytes);dh.assets.reserve(hash,bytes.length);
                for(int offset=0;offset<bytes.length;offset+=RemoteAssets.CHUNK_BYTES)
                    dh.assets.append(hash,offset,java.util.Arrays.copyOfRange(bytes,offset,offset+RemoteAssets.CHUNK_BYTES));
                dh.assets.finish(hash);
                sh.beforeControls=()->assertTrue(sh.assets.isComplete(hash));
                dom.publish(st.localIdentity(),generation,RemoteProtocol.Stream.CONFIGURATION,"fixture",new JSONObject().put("hash",hash).put("length",bytes.length),false,1);
                sub.publish(dt.localIdentity(),generation,RemoteProtocol.Stream.BASELINE,"active",new JSONObject().put("revision",1),false,1);
                for(int i=0;i<130;i++)sub.publish(dt.localIdentity(),generation,RemoteProtocol.Stream.DAILY,"day"+i,new JSONObject().put("duration",100+i),false,1);
            }
            wireTrace=new WireTrace();dh.trace=sh.trace=wireTrace;
            exchange(true,false,true,priority);
            assertTrue(wireTrace.controlsAt>0);assertTrue(wireTrace.bytes>0);
            if(scenario.equals("idle"))assertEquals(priority?11:15,wireTrace.frames);
            assertTrue(wireTrace.frames<200);assertTrue(wireTrace.bytes<1024*1024);
            for(int rtt:new int[]{50,150,300})System.out.println("WIRE_SESSION "+wireTrace.report(scenario,rtt,priority));
        }
    }

    @Test public void legacyPhasePlanRetainsDuplexOrdering() throws Exception {
        dh.controls=sh.controls=true;
        exchange(true,false,true,false);
        assertTrue(dh.events.indexOf("assets")<dh.events.indexOf("controls"));
        assertTrue(sh.events.indexOf("assets")<sh.events.indexOf("controls"));
        dh.events.clear();sh.events.clear();exchange(false,false,true,false);
        assertTrue(dh.events.indexOf("assets")<dh.events.indexOf("controls"));
        assertTrue(sh.events.indexOf("assets")<sh.events.indexOf("controls"));
    }

    @Test public void malformedPhasePlanCannotEnterApplicationExchange() throws Exception {
        dh.controls=sh.controls=true;
        dom.publish(st.localIdentity(),generation,RemoteProtocol.Stream.CONFIGURATION,"desired",new JSONObject().put("revision",1),false,1);
        for(Object value:new Object[]{0,"1",true}) {
            phaseOverride=value;
            assertThrows(IOException.class,()->exchange(true,false));
            assertEquals(0,dh.controlCalls);assertEquals(0,sh.controlCalls);
            assertNull(sub.mirrored(dt.localIdentity(),generation,RemoteProtocol.Stream.CONFIGURATION,"desired"));
        }
        phaseOverride=null;
        assertFalse(dom.nextBatch(st.localIdentity(),generation,RemoteProtocol.Stream.CONFIGURATION).changes.isEmpty());
    }

    @Test public void appIconsBothNewNegotiateForEitherConnectionInitiator() throws Exception {
        dh.iconVersion=1;sh.iconVersion=1;
        String own=context.getPackageName();
        JSONObject catalog=new JSONObject().put("schema",1).put("package",own).put("label","Own fixture app");
        sub.publish(dt.localIdentity(),generation,RemoteProtocol.Stream.APP_CATALOG,own,catalog,false,1000);
        com.subhub.app.remote.icons.AppIconCache domCache=new com.subhub.app.remote.icons.AppIconCache(new File(root,"icons-dom"));
        com.subhub.app.remote.icons.AppIconCache subCache=new com.subhub.app.remote.icons.AppIconCache(new File(root,"icons-sub"));
        FixtureIconAuthority domAuthority=new FixtureIconAuthority(dom,own),subAuthority=new FixtureIconAuthority(sub,own);
        com.subhub.app.remote.icons.AndroidAppIconSource source=new com.subhub.app.remote.icons.AndroidAppIconSource(context,subAuthority);
        RemoteStore.Peer subPeer=sub.peer(dt.localIdentity(),generation);
        byte[] expected=source.rasterize(subPeer,own);assertNotNull(expected);
        dh.realIcons=new com.subhub.app.remote.icons.AppIconExchange(domAuthority,(peer,name)->{throw new AssertionError("Dom must not rasterize");},domCache);
        sh.realIcons=new com.subhub.app.remote.icons.AppIconExchange(subAuthority,source,subCache);
        dh.beforeIcons=()->{
            assertTrue(domAuthority.mirrored(dom.peer(st.localIdentity(),generation),own));
            if(domCache.snapshot().identity(st.localIdentity(),generation,own)==null)
                domCache.publishReferences(Collections.singletonList(new com.subhub.app.util.RemoteAppIcon(st.localIdentity(),generation,own,null)));
        };
        exchange(true,false);
        com.subhub.app.util.RemoteAppIcon received=domCache.snapshot().identity(st.localIdentity(),generation,own);
        assertNotNull(received);assertNotNull(received.descriptor);assertEquals(RemoteProtocol.sha256(expected),received.descriptor.sha256);
        byte[] transferred=domCache.load(received);assertArrayEquals(expected,transferred);received.validatePng(transferred);
        assertEquals(0,dh.iconResumeOffset);assertTrue(sh.iconChunks>0);assertEquals(1,dh.iconsAck);
        android.graphics.Bitmap wanted=android.graphics.BitmapFactory.decodeByteArray(expected,0,expected.length),actual=android.graphics.BitmapFactory.decodeByteArray(transferred,0,transferred.length);
        try{
            assertNotNull(wanted);assertNotNull(actual);assertEquals(128,actual.getWidth());assertEquals(128,actual.getHeight());
            if(!wanted.sameAs(actual)){
                File evidence=new File(context.getCacheDir(),"remote-icon-failure-"+UUID.randomUUID());assertTrue(evidence.mkdirs());
                Files.write(new File(evidence,"expected.png").toPath(),expected);Files.write(new File(evidence,"received.png").toPath(),transferred);
                fail("Decoded pixels differ; retained "+evidence.getAbsolutePath());
            }
        }finally{if(wanted!=null)wanted.recycle();if(actual!=null)actual.recycle();}
        dh.iconResumeOffset=-1;sh.iconChunks=0;dh.iconsAck=0;
        exchange(false,false);
        assertEquals(received,domCache.snapshot().identity(st.localIdentity(),generation,own));
        assertArrayEquals(expected,domCache.load(received));assertEquals(expected.length,dh.iconResumeOffset);assertEquals(0,sh.iconChunks);assertEquals(1,dh.iconsAck);
        assertEquals(1,dom.mirror(st.localIdentity(),generation,RemoteProtocol.Stream.APP_CATALOG).size());
        assertEquals(1,sub.published(dt.localIdentity(),generation,RemoteProtocol.Stream.APP_CATALOG).size());
        assertEquals(RemoteProtocol.canonical(catalog),RemoteProtocol.canonical(dom.mirrored(st.localIdentity(),generation,RemoteProtocol.Stream.APP_CATALOG,own)));
        assertTrue(sub.nextBatch(dt.localIdentity(),generation,RemoteProtocol.Stream.APP_CATALOG).changes.isEmpty());
        assertEquals(2,dh.iconCalls);assertEquals(2,sh.iconCalls);
        assertTrue(dom.peer(st.localIdentity(),generation).lastSyncedAt>0);
        assertTrue(sub.peer(dt.localIdentity(),generation).lastSyncedAt>0);
    }
    @Test public void appIconsNewDomOldSubSkipsPhaseAndStillAcknowledgesData() throws Exception {
        dh.iconVersion=1;sh.iconVersion=0;
        sub.publish(dt.localIdentity(),generation,RemoteProtocol.Stream.STATS,"summary",new JSONObject().put("count",19),false,1000);
        exchange(true,false);exchange(false,false);
        assertEquals(0,dh.iconCalls);assertEquals(0,sh.iconCalls);
        assertEquals(19,dom.mirrored(st.localIdentity(),generation,RemoteProtocol.Stream.STATS,"summary").getInt("count"));
        assertTrue(sub.nextBatch(dt.localIdentity(),generation,RemoteProtocol.Stream.STATS).changes.isEmpty());
    }
    @Test public void appIconsOldDomNewSubSkipsPhaseForEitherConnectionInitiator() throws Exception {
        dh.iconVersion=0;sh.iconVersion=1;
        exchange(true,false);exchange(false,false);
        assertEquals(0,dh.iconCalls);assertEquals(0,sh.iconCalls);
        assertTrue(dom.peer(st.localIdentity(),generation).lastSyncedAt>0);
        assertTrue(sub.peer(dt.localIdentity(),generation).lastSyncedAt>0);
    }

    @Test public void contractCheckpointStillRecordsContactWhenLaterCompletionFails() throws Exception {
        String identity=dt.localIdentity();long completed=System.currentTimeMillis()-12*60*60_000L;
        sub.synced(identity,generation,completed);
        dom.publish(st.localIdentity(),generation,RemoteProtocol.Stream.CONFIGURATION,"active",new JSONObject().put("revision",1),false,1);
        sh.failComplete=true;long started=System.currentTimeMillis();
        assertThrows(IOException.class,()->exchange(false,false));
        RemoteStore.Peer peer=sub.peer(identity,generation);long contact=sub.lastContactAt(peer);
        assertEquals(completed,peer.lastSyncedAt);assertTrue(contact>=started);
        assertTrue(Long.parseLong(sub.metadata(identity,generation,"confirmed_CONFIGURATION","0"))>=started);
        assertEquals(1,sub.mirrored(identity,generation,RemoteProtocol.Stream.CONFIGURATION,"active").getInt("revision"));
        assertTrue(dom.nextBatch(st.localIdentity(),generation,RemoteProtocol.Stream.CONFIGURATION).changes.isEmpty());
        assertTrue(PeerPresence.online(false,contact,contact+90_000));
        assertFalse(PeerPresence.online(false,contact,contact+90_001));
        sh.failComplete=false;exchange(false,false);
        assertTrue(sub.peer(identity,generation).lastSyncedAt>=started);
        assertEquals(1,sub.mirror(identity,generation,RemoteProtocol.Stream.CONFIGURATION).size());
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
        dh.controls=sh.controls=true;
        File source=new File(root,"source.bin");Random random=new Random(17);
        long length=5L*1024*1024;
        try(OutputStream output=new FileOutputStream(source)) {
            byte[] buffer=new byte[RemoteAssets.CHUNK_BYTES];
            for(long left=length;left>0;left-=buffer.length) {random.nextBytes(buffer);output.write(buffer);}
        }
        String hash=RemoteAssets.digest(source);dh.assets.reserve(hash,length);
        sh.beforeControls=()->assertTrue(sh.assets.isComplete(hash));
        try(InputStream input=new FileInputStream(source)) {
            byte[] buffer=new byte[RemoteAssets.CHUNK_BYTES];int count;long offset=0;
            while((count=input.read(buffer))!=-1) {offset=dh.assets.append(hash,offset,java.util.Arrays.copyOf(buffer,count));}
        }
        dh.assets.finish(hash);
        dom.publish(st.localIdentity(),generation,RemoteProtocol.Stream.CONFIGURATION,"fixture",new JSONObject().put("hash",hash).put("length",length),false,1000);
        exchange(true,false);
        assertFalse(sh.assets.isComplete(hash));
        // Eighty chunks exceed the sixty-four chunk pass: neither side may enter controls yet.
        assertEquals(0,dh.controlCalls);assertEquals(0,sh.controlCalls);
        assertEquals(4L*1024*1024,sh.assets.offset(hash));
        assertTrue(dom.nextBatch(st.localIdentity(),generation,RemoteProtocol.Stream.CONFIGURATION).changes.isEmpty());
        exchange(true,false);
        assertTrue(sh.assets.isComplete(hash));assertEquals(hash,RemoteAssets.digest(sh.assets.finish(hash)));
        assertEquals(1,dh.controlCalls);assertEquals(1,sh.controlCalls);
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

    @Test public void pairedProfilesPersistOfflineAndReconnectWithChangedAndRemovedAvatars() throws Exception {
        dh.profiles = true; sh.profiles = true;
        android.graphics.Bitmap image = android.graphics.Bitmap.createBitmap(240, 180, android.graphics.Bitmap.Config.ARGB_8888);
        String avatar;
        try { image.eraseColor(android.graphics.Color.MAGENTA); avatar = com.subhub.app.people.ProfileImages.encode(image); }
        finally { image.recycle(); }
        dh.profile = new com.subhub.app.people.ProfileData("Fixture Dom", "Dom bio", "");
        sh.profile = new com.subhub.app.people.ProfileData("Fixture Sub", "Sub bio", avatar);
        exchange(true, false);
        assertEquals(sh.profile, RemoteProfiles.read(dom, dom.peer(st.localIdentity(), generation)));
        assertEquals(dh.profile, RemoteProfiles.read(sub, sub.peer(dt.localIdentity(), generation)));
        assertEquals("Fixture Sub", dom.peer(st.localIdentity(), generation).name);
        com.subhub.app.people.ProfileData retained = sh.profile;
        image = android.graphics.Bitmap.createBitmap(180, 240, android.graphics.Bitmap.Config.ARGB_8888);
        try { image.eraseColor(android.graphics.Color.CYAN); avatar = com.subhub.app.people.ProfileImages.encode(image); }
        finally { image.recycle(); }
        sh.profile = new com.subhub.app.people.ProfileData("Updated Sub", "Changed while offline", avatar);
        assertEquals(retained, RemoteProfiles.read(dom, dom.peer(st.localIdentity(), generation)));
        exchange(false, false);
        assertEquals(sh.profile, RemoteProfiles.read(dom, dom.peer(st.localIdentity(), generation)));
        assertEquals(dh.profile, RemoteProfiles.read(sub, sub.peer(dt.localIdentity(), generation)));
        sh.profile = new com.subhub.app.people.ProfileData("Updated Sub", "", "");
        exchange(false, false);
        assertEquals("", RemoteProfiles.read(dom, dom.peer(st.localIdentity(), generation)).avatar);
        assertEquals("", RemoteProfiles.read(dom, dom.peer(st.localIdentity(), generation)).about);
        dom.close(); dom = new RemoteStore(context, domName);
        assertEquals(sh.profile, RemoteProfiles.read(dom, dom.peer(st.localIdentity(), generation)));
        RemoteStore.Peer beforeRename = dom.peer(st.localIdentity(), generation);
        dom.updateDisplayName(st.localIdentity(), generation, "Name from a later legacy hello");
        assertEquals("Name from a later legacy hello", RemoteProfiles.read(dom, beforeRename).name);
    }

    @Test public void ownerSwitchNegotiatesIndependentlyInBothDirectionsAndClears() throws Exception {
        dh.profiles = true; sh.profiles = true;
        for (boolean details : new boolean[]{false, true}) {
            dh.details = details; sh.details = details;
            for (boolean supported : new boolean[]{false, true}) {
                dh.roleIdentity = true; sh.roleIdentity = supported;
                dh.profile = new com.subhub.app.people.ProfileData("Switch Dom", "Bio", "", "Woman", 32, true);
                sh.profile = new com.subhub.app.people.ProfileData("Switch Sub", "Bio", "", "Man", 29, true);
                for (boolean outgoingDom : new boolean[]{true, false}) {
                    exchange(outgoingDom, false);
                    assertEquals((details ? 6 : 4) + (supported ? 1 : 0), dh.lastSentFields);
                    assertEquals(dh.lastSentFields, sh.lastSentFields);
                    assertEquals(supported, RemoteProfiles.read(dom, dom.peer(st.localIdentity(), generation)).isSwitch);
                    assertEquals(supported, RemoteProfiles.read(sub, sub.peer(dt.localIdentity(), generation)).isSwitch);
                    assertEquals(RemoteProtocol.Role.DOM, dom.peer(st.localIdentity(), generation).localRole);
                    assertEquals(RemoteProtocol.Role.SUB, sub.peer(dt.localIdentity(), generation).localRole);
                }
            }
        }
        dom.close(); dom = new RemoteStore(context, domName);
        RemoteStore.Peer peer = dom.peer(st.localIdentity(), generation);
        assertTrue(RemoteProfiles.read(dom, peer).isSwitch);
        dom.updateDisplayName(peer.identity, peer.generation, "Renamed Switch");
        assertTrue(RemoteProfiles.read(dom, peer).isSwitch);
        assertEquals("Renamed Switch", RemoteProfiles.read(dom, peer).name);
        sh.profile = new com.subhub.app.people.ProfileData("Single Sub", "Bio", "", "Man", 29);
        exchange(false, false);
        assertFalse(RemoteProfiles.read(dom, peer).isSwitch);
        JSONObject role = RemoteProfiles.encode(dh.profile, RemoteProtocol.Role.SUB, true, true);
        assertThrows(org.json.JSONException.class, () -> RemoteProfiles.receive(dom, peer, role, true));
        assertEquals("Single Sub", RemoteProfiles.read(dom, peer).name);
        dom.revoke(peer.identity, peer.generation);
        assertThrows(SecurityException.class, () -> RemoteProfiles.receive(dom, peer, role, true, true));
        assertFalse(RemoteProfiles.read(dom, peer).isSwitch);
    }

    @Test public void optionalProfileDetailsNegotiateInBothDirectionsPersistAndClear() throws Exception {
        dh.profiles = true; sh.profiles = true; dh.details = true; sh.details = true;
        dh.profile = new com.subhub.app.people.ProfileData("Fixture Dom", "Dom bio", "", "Woman", 32);
        sh.profile = new com.subhub.app.people.ProfileData("Fixture Sub", "Sub bio", "", "Non-binary", 29);
        exchange(true, false);
        assertEquals(sh.profile, RemoteProfiles.read(dom, dom.peer(st.localIdentity(), generation)));
        assertEquals(dh.profile, RemoteProfiles.read(sub, sub.peer(dt.localIdentity(), generation)));
        dom.close(); dom = new RemoteStore(context, domName);
        assertEquals(29, RemoteProfiles.read(dom, dom.peer(st.localIdentity(), generation)).age);
        sh.profile = new com.subhub.app.people.ProfileData("Fixture Sub", "Sub bio", "", "", 0);
        exchange(false, false);
        assertEquals(sh.profile, RemoteProfiles.read(dom, dom.peer(st.localIdentity(), generation)));
        assertEquals(6, dh.lastSentFields); assertEquals(6, sh.lastSentFields);
    }

    @Test public void optionalDetailsDoNotReachOlderProfilePeersOrRevokedRelationships() throws Exception {
        dh.profiles = true; sh.profiles = true; dh.details = true;
        dh.profile = new com.subhub.app.people.ProfileData("Fixture Dom", "Dom bio", "", "Woman", 32);
        sh.profile = new com.subhub.app.people.ProfileData("Fixture Sub", "Sub bio", "", "Non-binary", 29);
        exchange(true, false);
        assertEquals(4, dh.lastSentFields); assertEquals(4, sh.lastSentFields);
        assertEquals(0, RemoteProfiles.read(sub, sub.peer(dt.localIdentity(), generation)).age);
        assertEquals("", RemoteProfiles.read(dom, dom.peer(st.localIdentity(), generation)).gender);
        sh.details = true; exchange(false, false);
        RemoteStore.Peer peer = dom.peer(st.localIdentity(), generation);
        assertEquals(29, RemoteProfiles.read(dom, peer).age);
        JSONObject value = RemoteProfiles.encode(sh.profile, RemoteProtocol.Role.SUB, true);
        dom.revoke(peer.identity, peer.generation);
        assertEquals("", dom.profile(peer.identity, peer.generation));
        assertThrows(SecurityException.class, () -> RemoteProfiles.receive(dom, peer, value, true));
        assertEquals(0, RemoteProfiles.read(dom, peer).age);
    }

    @Test public void unnegotiatedOptionalFieldsCannotReplaceTheRetainedProfile() throws Exception {
        RemoteStore.Peer peer = dom.peer(st.localIdentity(), generation);
        JSONObject legacy = RemoteProfiles.encode(new com.subhub.app.people.ProfileData("Earlier", "Retained", ""), RemoteProtocol.Role.SUB);
        RemoteProfiles.receive(dom, peer, legacy);
        JSONObject details = RemoteProfiles.encode(new com.subhub.app.people.ProfileData("Injected", "", "", "Woman", 32), RemoteProtocol.Role.SUB, true);
        assertThrows(org.json.JSONException.class, () -> RemoteProfiles.receive(dom, peer, details));
        assertEquals("Earlier", RemoteProfiles.read(dom, peer).name);
    }

    @Test public void profileNegotiationDoesNotSendFieldsToLegacyPeers() throws Exception {
        sh.profiles = true;
        sh.profile = new com.subhub.app.people.ProfileData("Sub", "Unsent profile fields", "");
        sub.publish(dt.localIdentity(), generation, RemoteProtocol.Stream.STATS, "summary",
                new JSONObject().put("count", 32), false, 1000);
        exchange(false, false);
        assertEquals(0, sh.profileCalls); assertEquals(0, dh.profileCalls);
        assertEquals("", dom.profile(st.localIdentity(), generation));
        assertEquals(32, dom.mirrored(st.localIdentity(), generation, RemoteProtocol.Stream.STATS, "summary").getInt("count"));
    }

    @Test public void revokedRelationshipsCannotReadOrReplaceProfileSnapshots() throws Exception {
        RemoteStore.Peer peer = dom.peer(st.localIdentity(), generation);
        JSONObject profile = RemoteProfiles.encode(new com.subhub.app.people.ProfileData("Sub", "Private paired bio", ""), RemoteProtocol.Role.SUB);
        RemoteProfiles.receive(dom, peer, profile);
        dom.revoke(peer.identity, peer.generation);
        assertEquals("", dom.profile(peer.identity, peer.generation));
        assertThrows(SecurityException.class, () -> RemoteProfiles.receive(dom, peer, profile));
        assertEquals("", RemoteProfiles.read(dom, peer).about);
    }
    private void exchange(boolean domInitiates,boolean dropAck) throws Exception { exchange(domInitiates,dropAck,true); }
    private void exchange(boolean domInitiates,boolean dropAck,boolean activity) throws Exception {
        exchange(domInitiates,dropAck,activity,true);
    }
    private void exchange(boolean domInitiates,boolean dropAck,boolean activity,boolean priority) throws Exception {
        dh.probed=sh.probed=false;dh.priority=sh.priority=priority;
        PeerTransport source=domInitiates?dt:st,target=domInitiates?st:dt;
        RemoteStore sourceStore=domInitiates?dom:sub,targetStore=domInitiates?sub:dom;
        FixtureHooks sourceHooks=domInitiates?dh:sh,targetHooks=domInitiates?sh:dh;
        RemoteSession outgoing=new RemoteSession(sourceStore,source,sourceHooks),incoming=new RemoteSession(targetStore,target,targetHooks);
        Future<Void> serving=server.submit(() -> {
            try(PeerTransport.Connection connection=target.accept(10_000)) {
                PeerTransport.Connection measured=wireTrace==null?connection:new WireFrames(connection,wireTrace);
                PeerTransport.Connection observed=dropAck?new DropAck(measured):measured;
                JSONObject hello=RemoteProtocol.read(observed.input());if(!activity)hello.remove("activityVersion");
                if(!priority)hello.remove("phasePlanVersion");
                if(phaseOverride!=null)hello.put("phasePlanVersion",phaseOverride);
                incoming.incoming(observed,hello,System.nanoTime()+60_000_000_000L);
            }
            return null;
        });
        try(PeerTransport.Connection connection=source.connect(target.localIdentity(),target.inviteRoute(),10_000)) {
            try {
                outgoing.outgoing(wireTrace==null?connection:new WireFrames(connection,wireTrace),sourceStore.peer(target.localIdentity(),generation),System.nanoTime()+60_000_000_000L);
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
        interface CheckedAction { void run() throws Exception; }
        final RemoteStore store;final RemoteAssets assets;int prepares;
        final java.util.List<String> events=new java.util.ArrayList<>();
        boolean controls,probed,priority;int controlCalls;CheckedAction beforeControls=()->{};WireTrace trace;
        public int sessionControlVersion(){return controls?1:0;}
        public void sessionControl(PeerTransport.Connection connection,RemoteStore.Peer peer,long deadline)throws Exception{
            events.add("controls");controlCalls++;beforeControls.run();
            JSONObject frame=new JSONObject().put("version",1).put("type","fixture_controls").put("generation",peer.generation);
            if(peer.localRole==RemoteProtocol.Role.DOM){RemoteProtocol.write(connection.output(),frame);assertEquals("fixture_controls",RemoteProtocol.read(connection.input()).getString("type"));}
            else {assertEquals("fixture_controls",RemoteProtocol.read(connection.input()).getString("type"));RemoteProtocol.write(connection.output(),frame);}
            if(trace!=null)trace.controlsComplete();
        }
        int iconVersion,iconCalls;
        int iconChunks,iconsAck;long iconResumeOffset=-1;
        com.subhub.app.remote.icons.AppIconExchange realIcons;Runnable beforeIcons=()->{};
        public int appIconsVersion(){return iconVersion;}
        public void appIcons(PeerTransport.Connection connection,RemoteStore.Peer peer,long deadline)throws Exception{
            iconCalls++;
            if(realIcons!=null){beforeIcons.run();realIcons.exchange(new IconFrames(connection,this),peer,deadline);return;}
            JSONObject frame=new JSONObject().put("version",1).put("type","fixture_icons").put("generation",peer.generation);
            if(peer.localRole==RemoteProtocol.Role.DOM){RemoteProtocol.write(connection.output(),frame);assertEquals("fixture_icons",RemoteProtocol.read(connection.input()).getString("type"));}
            else {assertEquals("fixture_icons",RemoteProtocol.read(connection.input()).getString("type"));RemoteProtocol.write(connection.output(),frame);}
        }
        boolean profiles, details, roleIdentity; int profileCalls, lastSentFields;
        com.subhub.app.people.ProfileData profile = com.subhub.app.people.ProfileData.empty("Fixture");
        FixtureHooks(RemoteStore store,RemoteAssets assets){this.store=store;this.assets=assets;}
        public int profileVersion() { return profiles ? 1 : 0; }
        public int profileDetailsVersion() { return details ? 1 : 0; }
        public int profileRoleVersion() { return roleIdentity ? 1 : 0; }
        public String displayName(RemoteStore.Peer peer) { return profiles ? profile.name : ""; }
        public JSONObject profile(RemoteStore.Peer peer) throws Exception {
            profileCalls++; return RemoteProfiles.encode(profile, peer.localRole);
        }
        public JSONObject profile(RemoteStore.Peer peer, boolean includeDetails) throws Exception {
            profileCalls++;
            JSONObject value = RemoteProfiles.encode(profile, peer.localRole, includeDetails);
            lastSentFields = value.length();
            return value;
        }
        public JSONObject profile(RemoteStore.Peer peer, boolean includeDetails, boolean includeRole) throws Exception {
            profileCalls++;
            JSONObject value = RemoteProfiles.encode(profile, peer.localRole, includeDetails, includeRole);
            lastSentFields = value.length();
            return value;
        }
        public void prepare(RemoteStore.Peer peer){prepares++;}
        public void received(RemoteStore.Peer peer,RemoteProtocol.Stream stream){events.add("received_"+stream.name());}
        public List<RemoteSession.AssetNeed> missingAssets(RemoteStore.Peer peer) throws Exception {
            events.add(priority&&!probed?"asset_probe":"assets");probed=true;
            JSONObject manifest=store.mirrored(peer.identity,peer.generation,RemoteProtocol.Stream.CONFIGURATION,"fixture");
            if(manifest==null)return Collections.emptyList();
            String hash=manifest.getString("hash");long length=manifest.getLong("length");assets.reserve(hash,length);
            return assets.isComplete(hash)?Collections.emptyList():Collections.singletonList(new RemoteSession.AssetNeed(hash,length,assets.offset(hash)));
        }
        public RemoteAssets assets(RemoteStore.Peer peer){return assets;}
        boolean failComplete;
        public void complete(RemoteStore.Peer peer)throws IOException{if(failComplete)throw new IOException("Fixture completion failed");}
    }
    /** Counts actual serialized session frames; virtual delay excludes CPU, bandwidth and route behavior. */
    private static final class WireTrace {
        long bytes,frames,acks,controlsAt,sectionBatches,sectionFallbacks,dataBatches;
        synchronized void sent(byte[] encoded)throws IOException {
            JSONObject frame=RemoteProtocol.read(new ByteArrayInputStream(encoded));
            bytes+=encoded.length;frames++;
            String type=frame.optString("type");if(type.equals("ack")||type.endsWith("_ack"))acks++;
            if(type.equals("section_batch"))sectionBatches++;
            if(type.equals("section_full"))sectionFallbacks++;
            if(type.equals("batch")||type.equals("section_batch"))dataBatches++;
        }
        synchronized void controlsComplete(){if(controlsAt==0)controlsAt=frames;}
        synchronized JSONObject report(String scenario,int rtt,boolean priority)throws Exception {
            return new JSONObject().put("scenario",scenario).put("phasePlanVersion",priority?1:0).put("rttMs",rtt).put("wireBytes",bytes).put("frames",frames).put("acks",acks)
                    .put("framesThroughControls",controlsAt).put("serialFrameDelayThroughControlsMs",controlsAt*rtt/2)
                    .put("serialFrameDelayTotalMs",frames*rtt/2).put("model","virtual one-way half RTT per serialized frame; no real route claim");
        }
    }
    private static final class WireFrames implements PeerTransport.Connection {
        final PeerTransport.Connection delegate;final OutputStream output;
        WireFrames(PeerTransport.Connection delegate,WireTrace trace){this.delegate=delegate;
            output=new OutputStream(){final ByteArrayOutputStream pending=new ByteArrayOutputStream();
                public void write(int value){pending.write(value);}
                public void write(byte[] value,int offset,int length){pending.write(value,offset,length);}
                public void flush()throws IOException{
                    byte[] encoded=pending.toByteArray();pending.reset();trace.sent(encoded);delegate.output().write(encoded);delegate.output().flush();
                }
            };
        }
        public String authenticatedPeerIdentity(){return delegate.authenticatedPeerIdentity();}
        public InputStream input()throws IOException{return delegate.input();}public OutputStream output(){return output;}
        public void setReadTimeoutMillis(long timeout){delegate.setReadTimeoutMillis(timeout);}
        public boolean isDirect(){return delegate.isDirect();}public void close()throws IOException{delegate.close();}
    }

    private static final class FixtureIconAuthority implements com.subhub.app.remote.icons.AppIconExchange.Authority {
        private final RemoteStore store;private final String name;
        FixtureIconAuthority(RemoteStore store,String name){this.store=store;this.name=name;}
        public boolean current(RemoteStore.Peer captured){RemoteStore.Peer actual=store.peer(captured.identity,captured.generation);return actual!=null&&actual.state==RemoteStore.State.ACTIVE&&actual.localRole==captured.localRole;}
        public boolean published(RemoteStore.Peer peer,String packageName){
            if(!current(peer)||peer.localRole!=RemoteProtocol.Role.SUB||!name.equals(packageName))return false;
            for(RemoteProtocol.Change row:store.published(peer.identity,peer.generation,RemoteProtocol.Stream.APP_CATALOG))
                if(!row.deleted&&name.equals(row.key))try{return name.equals(new JSONObject(row.body).getString("package"));}catch(org.json.JSONException invalid){return false;}
            return false;
        }
        public boolean mirrored(RemoteStore.Peer peer,String packageName){
            if(!current(peer)||peer.localRole!=RemoteProtocol.Role.DOM||!name.equals(packageName))return false;
            JSONObject row=store.mirrored(peer.identity,peer.generation,RemoteProtocol.Stream.APP_CATALOG,name);
            return row!=null&&name.equals(row.optString("package"));
        }
        public List<String> wanted(RemoteStore.Peer peer){return mirrored(peer,name)?Collections.singletonList(name):Collections.emptyList();}
    }
    private static final class IconFrames implements PeerTransport.Connection {
        private final PeerTransport.Connection delegate;private final OutputStream output;
        IconFrames(PeerTransport.Connection delegate,FixtureHooks observed){this.delegate=delegate;
            output=new OutputStream(){final ByteArrayOutputStream pending=new ByteArrayOutputStream();
                public void write(int value){pending.write(value);}public void write(byte[] value,int offset,int length){pending.write(value,offset,length);}
                public void flush()throws IOException{
                    byte[] bytes=pending.toByteArray();
                    if(bytes.length>0)try{
                        JSONObject frame=RemoteProtocol.read(new ByteArrayInputStream(bytes));String type=frame.getString("type");
                        if("icon_resume".equals(type))observed.iconResumeOffset=RemoteProtocol.integer(frame,"offset");
                        if("icon_chunk".equals(type))observed.iconChunks++;if("icons_ack".equals(type))observed.iconsAck++;
                    }catch(org.json.JSONException invalid){throw new IOException(invalid);}
                    delegate.output().write(bytes);delegate.output().flush();pending.reset();
                }
            };
        }
        public String authenticatedPeerIdentity(){return delegate.authenticatedPeerIdentity();}
        public InputStream input()throws IOException{return delegate.input();}public OutputStream output(){return output;}
        public void setReadTimeoutMillis(long timeout){delegate.setReadTimeoutMillis(timeout);}public boolean isDirect(){return delegate.isDirect();}public void close()throws IOException{delegate.close();}
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
