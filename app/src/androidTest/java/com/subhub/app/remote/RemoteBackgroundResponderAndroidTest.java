package com.subhub.app.remote;

import static org.junit.Assert.*;

import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.configuration.LegacyPackMigration;
import com.subhub.app.security.Totp;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Native authenticated transport and actual Feature lifecycle, with no singleton or WorkManager mutation. */
@RunWith(AndroidJUnit4.class)
public final class RemoteBackgroundResponderAndroidTest {
    @Test public void delayedAuthenticatedInboundExchangesAfterOutgoingHasFinished() throws Exception {
        try(Fixture fixture=new Fixture()) { fixture.exchange(); }
    }
    private static final class Fixture implements AutoCloseable {
        final Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        final String prefix="background-responder-"+UUID.randomUUID()+"-",generation=UUID.randomUUID().toString();
        final File root=new File(target.getCacheDir(),prefix);
        final Set<String> preferences=ConcurrentHashMap.newKeySet();
        final String localAlias="subhub.test."+prefix+"local",peerAlias="subhub.test."+prefix+"peer";
        final ExecutorService workers=Executors.newFixedThreadPool(2);
        final AtomicBoolean cancelled=new AtomicBoolean();
        final java.util.concurrent.atomic.AtomicInteger credentialReceipts=new java.util.concurrent.atomic.AtomicInteger();
        final Context context=new ContextWrapper(target){
            @Override public Context getApplicationContext(){return this;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode){preferences.add(name);return target.getSharedPreferences(prefix+name,mode);}
            @Override public File getFilesDir(){return child("files");}
            @Override public File getNoBackupFilesDir(){return child("private");}
            @Override public File getCacheDir(){return child("cache");}
            private File child(String name){File file=new File(root,name);if(!file.isDirectory()&&!file.mkdirs())throw new IllegalStateException();return file;}
        };
        RemoteStore local,remote;RemoteSecrets peerSecrets;RemoteFeature feature;PeerTransport peer;
        Future<Boolean> window;Future<?> initial;String localIdentity,peerIdentity;
        void exchange() throws Exception {
            assertTrue(LegacyPackMigration.recoverAndMigrate(context));
            context.getSharedPreferences("subhub_peer_transport",0).edit().putStringSet("relay_urls",Collections.emptySet()).commit();
            local=new RemoteStore(context,prefix+"local.db");remote=new RemoteStore(context,prefix+"peer.db");
            local.getWritableDatabase();remote.getWritableDatabase();
            RemoteSecrets localSecrets=new RemoteSecrets(context,"local-secrets",localAlias);
            peerSecrets=new RemoteSecrets(context,"peer-secrets",peerAlias);
            byte[] seed=localSecrets.identitySeed();
            try(PeerTransport temporary=IrohPeerTransport.open(context,seed,new String[0],10_000)){localIdentity=temporary.localIdentity();}
            finally{Arrays.fill(seed,(byte)0);}
            seed=peerSecrets.identitySeed();
            try{peer=IrohPeerTransport.open(context,seed,new String[0],10_000);}finally{Arrays.fill(seed,(byte)0);}
            peerIdentity=peer.localIdentity();assertNotEquals(localIdentity,peerIdentity);
            seedPeer(local,peerIdentity,RemoteProtocol.Role.DOM,peer.inviteRoute());
            seedPeer(remote,localIdentity,RemoteProtocol.Role.SUB,"");
            // Avoid first-provision PIN changes; credentials remain isolated in this fixture's own namespace.
            peerSecrets.saveControllerSecret(RemoteProtocol.Role.SUB,peerIdentity,localIdentity,generation,Totp.newSecret());
            feature=new RemoteFeature(context,local,localSecrets,PairingLifecycleClock.source(context),new RemoteFeature.BackgroundScheduling(){
                public void synchronize(Context ignored,boolean needed){}
                public void kick(Context ignored){}
            });
            assertTrue(feature.recoveryReady());assertFalse((Boolean)field("foreground").get(feature));
            remote.publish(localIdentity,generation,RemoteProtocol.Stream.STATS,"delayed-proof",new JSONObject().put("phase",1),false,System.currentTimeMillis());
            initial=workers.submit(()->{
                try(PeerTransport.Connection connection=peer.accept(20_000)){
                    assertEquals(localIdentity,connection.authenticatedPeerIdentity());assertTrue(connection.isDirect());
                    connection.setReadTimeoutMillis(10_000);
                    JSONObject hello=RemoteProtocol.read(connection.input());assertEquals("hello",hello.getString("type"));
                    session().incoming(connection,hello,System.nanoTime()+TimeUnit.SECONDS.toNanos(20));
                }
                return null;
            });
            window=workers.submit(()->feature.backgroundWindow(55_000,cancelled::get));
            initial.get(25,TimeUnit.SECONDS);
            await(()->local.peer(peerIdentity,generation).lastSyncedAt>0&&((Set<?>)value("busy")).isEmpty());
            assertEquals(1,local.mirrored(peerIdentity,generation,RemoteProtocol.Stream.STATS,"delayed-proof").getInt("phase"));
            assertFalse("Outgoing completion must leave the responder alive",window.isDone());
            long outgoingFinished=SystemClock.elapsedRealtime();
            Thread.sleep(750);
            assertFalse(window.isDone());assertTrue(SystemClock.elapsedRealtime()-outgoingFinished>=700);
            PeerTransport responder=(PeerTransport)field("endpoint").get(feature);assertNotNull(responder);
            assertEquals(localIdentity,responder.localIdentity());
            remote.publish(localIdentity,generation,RemoteProtocol.Stream.STATS,"delayed-proof",new JSONObject().put("phase",2),false,System.currentTimeMillis());
            RemoteProtocol.Batch pending=remote.nextBatch(localIdentity,generation,RemoteProtocol.Stream.STATS);assertFalse(pending.changes.isEmpty());
            try(PeerTransport.Connection connection=peer.connect(localIdentity,responder.inviteRoute(),8_000)){
                assertEquals(localIdentity,connection.authenticatedPeerIdentity());assertTrue(connection.isDirect());
                session().outgoing(connection,remote.peer(localIdentity,generation),System.nanoTime()+TimeUnit.SECONDS.toNanos(15));
            }
            await(()->((Set<?>)value("busy")).isEmpty());
            assertEquals(2,local.mirrored(peerIdentity,generation,RemoteProtocol.Stream.STATS,"delayed-proof").getInt("phase"));
            assertTrue(remote.nextBatch(localIdentity,generation,RemoteProtocol.Stream.STATS).changes.isEmpty());
            assertFalse("The delayed peer exchanged within the original window",window.isDone());
            assertEquals(2,credentialReceipts.get());
            assertTrue(window.get(60,TimeUnit.SECONDS));
            await(()->value("endpoint")==null);
            assertTrue(((Map<?,?>)field("connections").get(feature)).isEmpty());
            assertEquals(0,((java.util.concurrent.atomic.AtomicInteger)field("backgroundWindows").get(feature)).get());
        }
        RemoteSession session(){
            RemoteKeyholder keyholder=new RemoteKeyholder(context,remote,peerSecrets);
            return new RemoteSession(remote,peer,new RemoteSession.Hooks(){
                @Override public void authenticate(PeerTransport.Connection wire,RemoteStore.Peer current,long deadline)throws Exception{
                    wire.setReadTimeoutMillis(10_000);
                    RemoteProtocol.write(wire.output(),new JSONObject().put("version",1).put("type","keyholder_credential").put("generation",generation).put("authVersion",1).put("authSecret",keyholder.provisionSub(current,peerIdentity)));
                    JSONObject ack=RemoteProtocol.read(wire.input());RemoteProtocol.requireVersion(ack);
                    assertEquals("keyholder_receipt",ack.getString("type"));assertEquals(generation,ack.getString("generation"));assertEquals(1,RemoteProtocol.integer(ack,"authVersion"));credentialReceipts.incrementAndGet();
                }
                @Override public void prepare(RemoteStore.Peer current){}
                @Override public void received(RemoteStore.Peer current,RemoteProtocol.Stream stream){}
                @Override public List<RemoteSession.AssetNeed> missingAssets(RemoteStore.Peer current){return Collections.emptyList();}
                @Override public RemoteAssets assets(RemoteStore.Peer current)throws Exception{return new RemoteAssets(new File(root,"assets"));}
                @Override public void complete(RemoteStore.Peer current){}
            });
        }
        void seedPeer(RemoteStore store,String identity,RemoteProtocol.Role role,String route){
            ContentValues row=new ContentValues();row.put("peer",identity);row.put("generation",generation);row.put("name","Delayed peer");row.put("local_role",role.name());row.put("state","ACTIVE");row.put("route",route);store.getWritableDatabase().insertOrThrow("peers",null,row);
        }
        Object value(String name){try{return field(name).get(feature);}catch(Exception failure){throw new AssertionError(failure);}}
        @Override public void close()throws Exception{
            List<Throwable> failures=new ArrayList<>();cancelled.set(true);
            clean(failures,()->{if(window!=null&&!window.isDone())window.cancel(true);if(peer!=null)peer.close();});
            clean(failures,()->{workers.shutdownNow();assertTrue(workers.awaitTermination(20,TimeUnit.SECONDS));});
            clean(failures,()->{if(feature!=null)feature.shutdownForTest();else if(local!=null)local.close();});
            clean(failures,()->{if(remote!=null)remote.close();});
            clean(failures,()->{if(local!=null)assertTrue(target.deleteDatabase(prefix+"local.db"));if(remote!=null)assertTrue(target.deleteDatabase(prefix+"peer.db"));for(String name:preferences)assertTrue(target.deleteSharedPreferences(prefix+name));});
            clean(failures,()->{KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);for(String alias:new String[]{localAlias,peerAlias})if(keys.containsAlias(alias))keys.deleteEntry(alias);});
            clean(failures,()->{Field ready=LegacyPackMigration.class.getDeclaredField("READY");ready.setAccessible(true);synchronized(LegacyPackMigration.class){((Set<?>)ready.get(null)).remove(context.getFilesDir().getAbsoluteFile());}});
            clean(failures,()->{Path owned=root.getCanonicalFile().toPath();assertEquals(prefix,owned.getFileName().toString());assertTrue(owned.startsWith(target.getCacheDir().getCanonicalFile().toPath()));if(Files.exists(owned))try(java.util.stream.Stream<Path> paths=Files.walk(owned)){for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator){assertFalse(Files.isSymbolicLink(path));assertTrue(path.toRealPath().startsWith(owned));Files.delete(path);}}});
            org.junit.runners.model.MultipleFailureException.assertEmpty(failures);
        }
    }
    private static Field field(String name)throws Exception{Field value=RemoteFeature.class.getDeclaredField(name);value.setAccessible(true);return value;}
    private static void await(java.util.function.BooleanSupplier ready)throws Exception{long deadline=SystemClock.elapsedRealtime()+5000;while(!ready.getAsBoolean()&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(20);assertTrue(ready.getAsBoolean());}
    private interface Cleanup{void run()throws Exception;}
    private static void clean(List<Throwable> failures,Cleanup cleanup){try{cleanup.run();}catch(Throwable failure){failures.add(failure);}}
}
