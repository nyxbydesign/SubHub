package com.subhub.app.remote;

import static org.junit.Assert.*;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import androidx.test.filters.SdkSuppress;
import android.service.notification.StatusBarNotification;
import androidx.test.core.app.ActivityScenario;
import androidx.lifecycle.Lifecycle;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import com.subhub.app.help.HelpActivity;
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
import org.junit.Rule;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.KeyholderPresentationAndroidTest;
import org.junit.runner.RunWith;

/** Actual OS foreground service plus two native authenticated Iroh endpoints; no singleton or WorkManager mutation. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion=31)
public final class PeerSyncForegroundServiceAndroidTest {
    private boolean readersDrained;
    @Rule public final org.junit.rules.ExternalResource protection=new org.junit.rules.ExternalResource(){
        private final Map<Field,Object> hooks=new LinkedHashMap<>();
        private final Map<String,Map<String,?>> saved=new LinkedHashMap<>();
        private boolean originalDom;
        private Field application;
        private Object originalApplication;
        @Override protected void before()throws Throwable{
            Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
            for(ActivityManager.RunningServiceInfo service:target.getSystemService(ActivityManager.class).getRunningServices(Integer.MAX_VALUE))
                org.junit.Assume.assumeFalse("An existing connection service owns this guest",PeerSyncForegroundService.class.getName().equals(service.service.getClassName()));
            org.junit.Assume.assumeTrue("Use an owned guest without application peer relationships",RemoteFeature.get(target).store.peers(false).isEmpty());
            originalDom=ControllerPinManager.isDomModeActive();
            application=ControllerPinManager.class.getDeclaredField("application");application.setAccessible(true);originalApplication=application.get(null);
            for(Field field:com.subhub.app.people.People.class.getDeclaredFields())if(java.lang.reflect.Modifier.isStatic(field.getModifiers())&&!java.lang.reflect.Modifier.isFinal(field.getModifiers())){
                field.setAccessible(true);hooks.put(field,field.get(null));
            }
            for(String name:List.of(com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME,"subhub_controller_auth","subhub_home","subhub_onboarding"))saved.put(name,new LinkedHashMap<>(target.getSharedPreferences(name,0).getAll()));
            com.subhub.app.people.People.installKeyholder(KeyholderPresentationAndroidTest.provider(com.subhub.app.security.KeyholderProvider.State.NONE,""));
            com.subhub.app.people.People.installRemoteAuthority(()->false);com.subhub.app.people.People.installRemoteRepository(null);
            com.subhub.app.people.People.installLocalProjection(java.util.function.UnaryOperator.identity());com.subhub.app.people.People.installConnectionUi(null);
        }
        @Override protected void after(){
            // Failed termination retains the isolated environment; never expose canonical authority to a live reader.
            if(!readersDrained)return;
            Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
            for(Map.Entry<String,Map<String,?>> entry:saved.entrySet()){
                SharedPreferences current=target.getSharedPreferences(entry.getKey(),0);
                if(!current.getAll().equals(entry.getValue()))com.subhub.app.settings.SharedPreferenceTestRestore.restore(current,entry.getValue());
            }
            try{
                for(Map.Entry<Field,Object> entry:hooks.entrySet())entry.getKey().set(null,entry.getValue());
                application.set(null,originalApplication);
            }catch(IllegalAccessException failure){throw new AssertionError(failure);}
            if(originalDom)ControllerPinManager.enterDomMode();else ControllerPinManager.enterSubMode();
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        }
    };
    @Test public void activeServiceDeliversBothDirectionsInBackgroundReconnectsAndStopsOnUnpair() throws Exception {
        try(Fixture fixture=new Fixture(()->readersDrained=true)) { fixture.exchange(); }
    }
    private static final class Fixture implements AutoCloseable {
        private final Runnable drained;
        Fixture(Runnable drained){this.drained=drained;}
        final Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        final String prefix="connected-service-"+UUID.randomUUID()+"-",generation=UUID.randomUUID().toString();
        final File root=new File(target.getCacheDir(),prefix);
        final Set<String> preferences=ConcurrentHashMap.newKeySet();
        final String localAlias="subhub.test."+prefix+"local",peerAlias="subhub.test."+prefix+"peer";
        final ExecutorService workers=Executors.newFixedThreadPool(2);
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
        Future<?> initial;String localIdentity,peerIdentity;
        ActivityScenario<HelpActivity> page;
        Field factoryField;Object previousFactory;
        boolean factoryInstalled,ownsChannel;
        int previousChannelImportance;
        final java.util.concurrent.atomic.AtomicReference<PeerSyncForegroundService> actualService=new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicInteger fallbackKicks=new java.util.concurrent.atomic.AtomicInteger();
        void exchange() throws Exception {
            // This fixture must not displace a user's already running paired-device service.
            org.junit.Assume.assumeFalse("An existing connection service owns this guest",runningService()!=null);
            org.junit.Assume.assumeTrue("Use an owned guest without application peer relationships",RemoteFeature.get(target).store.peers(false).isEmpty());
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
                public void kick(Context ignored){fallbackKicks.incrementAndGet();}
            });
            assertTrue(feature.recoveryReady());assertFalse((Boolean)field("foreground").get(feature));
            assertTrue(ControllerPinManager.setPin(context,"624871"));assertTrue(ControllerPinManager.verify(context,"624871"));

            NotificationManager notifications=target.getSystemService(NotificationManager.class);
            NotificationChannel previousChannel=notifications.getNotificationChannel(PeerSyncForegroundService.CHANNEL);
            ownsChannel=previousChannel==null;previousChannelImportance=previousChannel==null?NotificationManager.IMPORTANCE_LOW:previousChannel.getImportance();
            factoryField=PeerSyncForegroundService.class.getDeclaredField("factory");factoryField.setAccessible(true);
            previousFactory=factoryField.get(null);factoryField.set(null,(PeerSyncForegroundService.FeatureFactory)service->{actualService.set((PeerSyncForegroundService)service);return feature;});factoryInstalled=true;
            local.publish(peerIdentity,generation,RemoteProtocol.Stream.CONFIGURATION,"service-proof",new JSONObject().put("phase",1),false,System.currentTimeMillis());
            remote.publish(localIdentity,generation,RemoteProtocol.Stream.STATS,"service-proof",new JSONObject().put("phase",1),false,System.currentTimeMillis());
            String observedSession=String.join("",Collections.nCopies(64,"a"));
            JSONObject runtime=new JSONObject().put("schema",1).put("device","Synthetic peer").put("observedAt",System.currentTimeMillis())
                    .put("serviceActive",true).put("endSessionSupported",true).put("sessionIdentity",observedSession)
                    .put("sessionStart",0).put("sessionEnd",0).put("appliedRevision",0);
            for(String field:new String[]{"screenCapture","recognitionActive","accessibilityBound","accessibilityEnabled","overlayPermission","notifications","armed","paused"})runtime.put(field,false);
            assertTrue(RemoteTelemetry.validRuntime(runtime));remote.publish(localIdentity,generation,RemoteProtocol.Stream.RUNTIME,"current",runtime,false,System.currentTimeMillis());
            CountDownLatch backgrounded=new CountDownLatch(1);
            initial=workers.submit(()->{assertTrue(backgrounded.await(15,TimeUnit.SECONDS));serve();return null;});
            page=ActivityScenario.launch(HelpActivity.class);
            page.onActivity(PeerSyncForegroundService::requestStart);
            await(()->runningService()!=null&&runningService().foreground&&feature.hasConnectedService());
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,actualService.get().getForegroundServiceType());
            page.moveToState(Lifecycle.State.CREATED);
            assertBackground();backgrounded.countDown();initial.get(25,TimeUnit.SECONDS);
            await(()->((Set<?>)value("busy")).isEmpty()&&((java.util.concurrent.atomic.AtomicInteger)value("leases")).get()==0);
            assertEquals(0,((java.util.concurrent.atomic.AtomicInteger)value("backgroundWindows")).get());
            assertFalse((Boolean)value("foreground"));assertTrue(feature.hasConnectedService());
            assertEquals(1,local.mirrored(peerIdentity,generation,RemoteProtocol.Stream.STATS,"service-proof").getInt("phase"));
            assertEquals(1,remote.mirrored(localIdentity,generation,RemoteProtocol.Stream.CONFIGURATION,"service-proof").getInt("phase"));
            boolean drawerWitness=assertNotification(notifications);
            System.out.println("CONNECTED_SERVICE_OS "+new JSONObject().put("foregroundType",actualService.get().getForegroundServiceType()).put("foreground",runningService().foreground).put("notificationPermission",notifications.areNotificationsEnabled()).put("privateNotificationObserved",drawerWitness).put("startedActivities",0).put("workerWindows",0));
            PeerTransport original=(PeerTransport)value("endpoint");assertNotNull(original);
            // No Activity or WorkManager window contributes to this immediate outgoing mutation.
            Future<?> outgoing=workers.submit(()->{serve();return null;});
            local.publish(peerIdentity,generation,RemoteProtocol.Stream.CONFIGURATION,"service-proof",new JSONObject().put("phase",2),false,System.currentTimeMillis());
            long changedAt=SystemClock.elapsedRealtime();feature.changed();outgoing.get(15,TimeUnit.SECONDS);
            assertTrue("Dirty background delivery must not await the idle refresh",SystemClock.elapsedRealtime()-changedAt<12_000);
            assertEquals(2,remote.mirrored(localIdentity,generation,RemoteProtocol.Stream.CONFIGURATION,"service-proof").getInt("phase"));
            assertTrue(local.nextBatch(peerIdentity,generation,RemoteProtocol.Stream.CONFIGURATION).changes.isEmpty());
            await(()->((Set<?>)value("busy")).isEmpty());assertSame(original,value("endpoint"));
            // The production command API queues and requests synchronization; no test sync call follows it.
            Future<?> commandDelivery=workers.submit(()->{serve();return null;});
            long commandChangedAt=SystemClock.elapsedRealtime();
            String command=feature.endSession(local.peer(peerIdentity,generation),observedSession).get(5,TimeUnit.SECONDS);
            commandDelivery.get(15,TimeUnit.SECONDS);
            assertTrue("Production command delivery must not wait for the idle refresh",SystemClock.elapsedRealtime()-commandChangedAt<12_000);
            JSONObject delivered=remote.mirrored(localIdentity,generation,RemoteProtocol.Stream.CONFIGURATION,"end:"+command);
            assertNotNull(delivered);assertEquals("end_session",delivered.getString("type"));assertEquals(observedSession,delivered.getString("sessionIdentity"));
            assertTrue(local.nextBatch(peerIdentity,generation,RemoteProtocol.Stream.CONFIGURATION).changes.isEmpty());
            await(()->((Set<?>)value("busy")).isEmpty());
            // A later peer-initiated authenticated transfer reaches the same idle service-owned endpoint.
            remote.publish(localIdentity,generation,RemoteProtocol.Stream.STATS,"service-proof",new JSONObject().put("phase",2),false,System.currentTimeMillis());
            try(PeerTransport.Connection connection=peer.connect(localIdentity,original.inviteRoute(),8_000)){
                assertEquals(localIdentity,connection.authenticatedPeerIdentity());assertTrue(connection.isDirect());
                session().outgoing(connection,remote.peer(localIdentity,generation),System.nanoTime()+TimeUnit.SECONDS.toNanos(15));
            }
            await(()->((Set<?>)value("busy")).isEmpty());
            assertEquals(2,local.mirrored(peerIdentity,generation,RemoteProtocol.Stream.STATS,"service-proof").getInt("phase"));
            assertTrue(remote.nextBatch(localIdentity,generation,RemoteProtocol.Stream.STATS).changes.isEmpty());
            // Break the actual native listener, then verify bounded rebind and delivery without restarting the service.
            Future<?> reconnected=workers.submit(()->{serve();return null;});original.close();
            local.publish(peerIdentity,generation,RemoteProtocol.Stream.CONFIGURATION,"service-proof",new JSONObject().put("phase",3),false,System.currentTimeMillis());feature.changed();
            reconnected.get(25,TimeUnit.SECONDS);
            assertEquals(3,remote.mirrored(localIdentity,generation,RemoteProtocol.Stream.CONFIGURATION,"service-proof").getInt("phase"));
            assertNotNull(value("endpoint"));assertNotSame(original,value("endpoint"));
            assertTrue(runningService().foreground);assertBackground();assertTrue(credentialReceipts.get()>=5);
            await(()->((Set<?>)value("busy")).isEmpty());
            local.revoke(peerIdentity,generation);
            await(()->!feature.hasConnectedService()&&runningService()==null);
            await(()->value("endpoint")==null);
            assertEquals(RemoteStore.State.REVOKED,local.peer(peerIdentity,generation).state);
            assertTrue("Retained revocation still requests bounded fallback work",fallbackKicks.get()>0);
            assertTrue(((Map<?,?>)value("pairingLeases")).isEmpty());
            for(StatusBarNotification notification:notifications.getActiveNotifications())assertNotEquals(PeerSyncForegroundService.NOTIFICATION,notification.getId());
        }
        void serve()throws Exception{
            try(PeerTransport.Connection connection=peer.accept(20_000)){
                assertEquals(localIdentity,connection.authenticatedPeerIdentity());assertTrue(connection.isDirect());connection.setReadTimeoutMillis(10_000);
                JSONObject hello=RemoteProtocol.read(connection.input());assertEquals("hello",hello.getString("type"));
                session().incoming(connection,hello,System.nanoTime()+TimeUnit.SECONDS.toNanos(20));
            }
        }
        ActivityManager.RunningServiceInfo runningService(){
            for(ActivityManager.RunningServiceInfo service:target.getSystemService(ActivityManager.class).getRunningServices(Integer.MAX_VALUE))
                if(PeerSyncForegroundService.class.getName().equals(service.service.getClassName()))return service;
            return null;
        }
        void assertBackground(){
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
                for(Stage stage:new Stage[]{Stage.STARTED,Stage.RESUMED,Stage.PAUSED})assertTrue(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).isEmpty());
            });
        }
        boolean assertNotification(NotificationManager manager){
            Notification found=null;
            for(StatusBarNotification value:manager.getActiveNotifications())if(value.getId()==PeerSyncForegroundService.NOTIFICATION)found=value.getNotification();
            NotificationChannel channel=manager.getNotificationChannel(PeerSyncForegroundService.CHANNEL);
            if((!manager.areNotificationsEnabled()||channel.getImportance()==NotificationManager.IMPORTANCE_NONE)&&found==null)return false;
            assertNotNull("Actual foreground-service notification missing",found);
            assertEquals(Notification.VISIBILITY_SECRET,found.visibility);assertTrue((found.flags&Notification.FLAG_ONGOING_EVENT)!=0);assertTrue((found.flags&Notification.FLAG_FOREGROUND_SERVICE)!=0);
            assertEquals(target.getString(com.subhub.app.R.string.peer_sync_notification_title),found.extras.getString(Notification.EXTRA_TITLE));
            assertEquals(target.getString(com.subhub.app.R.string.peer_sync_notification_body),found.extras.getString(Notification.EXTRA_TEXT));
            assertEquals(previousChannelImportance,channel.getImportance());return true;
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
            ContentValues row=new ContentValues();row.put("peer",identity);row.put("generation",generation);row.put("name","Delayed peer");row.put("local_role",role.name());row.put("state","ACTIVE");row.put("route",route);
            android.database.sqlite.SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
            try{
                db.insertOrThrow("peers",null,row);
                // New-peer inventory includes its formal mapping before later authority transitions.
                FormalRelationshipStore.mapPeer(db,identity,generation,true);db.setTransactionSuccessful();
            }finally{db.endTransaction();}
        }
        Object value(String name){try{return field(name).get(feature);}catch(Exception failure){throw new AssertionError(failure);}}
        @Override public void close()throws Exception{
            List<Throwable> failures=new ArrayList<>();
            clean(failures,()->{
                if(factoryInstalled){
                    PeerSyncForegroundService owned=actualService.get();
                    assertTrue("Do not stop an unknown service",runningService()==null||owned!=null);
                    target.stopService(new Intent(target,PeerSyncForegroundService.class));
                    await(()->runningService()==null&&!feature.hasConnectedService()&&(owned==null||serviceDestroyed(owned)));
                }
            });
            clean(failures,()->{if(page!=null){page.close();page=null;}});
            clean(failures,()->{if(peer!=null)peer.close();});
            clean(failures,()->{workers.shutdownNow();assertTrue("Native fixture workers must terminate",workers.awaitTermination(20,TimeUnit.SECONDS));});
            clean(failures,()->{if(feature!=null)feature.shutdownForTest();else if(local!=null)local.close();});
            clean(failures,()->{
                if(feature!=null){
                    for(String name:List.of("timer","acceptor","operations","incomingSessions","sessions","pairingDeadlineTimer"))assertDrained((ExecutorService)value(name),"Feature "+name);
                    Field worker=RemoteActivityRepository.class.getDeclaredField("worker");worker.setAccessible(true);assertDrained((ExecutorService)worker.get(feature.activity),"Activity reader");
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                if(factoryInstalled)assertNull("Foreground service must be destroyed before restoring its factory",runningService());
                assertTrue(workers.isTerminated());
            });
            // Any failed stop/drain leaves the synthetic hooks, factory, files and credentials recoverable.
            org.junit.runners.model.MultipleFailureException.assertEmpty(failures);
            if(factoryInstalled){factoryField.set(null,previousFactory);factoryInstalled=false;}
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();drained.run();
            clean(failures,()->{if(remote!=null)remote.close();});
            clean(failures,()->{if(local!=null)assertTrue(target.deleteDatabase(prefix+"local.db"));if(remote!=null)assertTrue(target.deleteDatabase(prefix+"peer.db"));for(String name:preferences)assertTrue(target.deleteSharedPreferences(prefix+name));});
            clean(failures,()->{KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);for(String alias:new String[]{localAlias,peerAlias})if(keys.containsAlias(alias))keys.deleteEntry(alias);});
            clean(failures,()->{Field ready=LegacyPackMigration.class.getDeclaredField("READY");ready.setAccessible(true);synchronized(LegacyPackMigration.class){((Set<?>)ready.get(null)).remove(context.getFilesDir().getAbsoluteFile());}});
            clean(failures,()->{Path owned=root.getCanonicalFile().toPath();assertEquals(prefix,owned.getFileName().toString());assertTrue(owned.startsWith(target.getCacheDir().getCanonicalFile().toPath()));if(Files.exists(owned))try(java.util.stream.Stream<Path> paths=Files.walk(owned)){for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator){assertFalse(Files.isSymbolicLink(path));assertTrue(path.toRealPath().startsWith(owned));Files.delete(path);}}});
            clean(failures,()->{if(ownsChannel)target.getSystemService(NotificationManager.class).deleteNotificationChannel(PeerSyncForegroundService.CHANNEL);});
            org.junit.runners.model.MultipleFailureException.assertEmpty(failures);
        }
        boolean serviceDestroyed(PeerSyncForegroundService service){
            try{Field destroyed=PeerSyncForegroundService.class.getDeclaredField("destroyed");destroyed.setAccessible(true);return destroyed.getBoolean(service);}catch(Exception failure){throw new AssertionError(failure);}
        }
        void assertDrained(ExecutorService worker,String name)throws Exception{
            assertTrue(name+" must shut down",worker.isShutdown());assertTrue(name+" must terminate",worker.awaitTermination(10,TimeUnit.SECONDS));
        }

    }
    private static Field field(String name)throws Exception{Field value=RemoteFeature.class.getDeclaredField(name);value.setAccessible(true);return value;}
    private static void await(java.util.function.BooleanSupplier ready)throws Exception{long deadline=SystemClock.elapsedRealtime()+5000;while(!ready.getAsBoolean()&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(20);assertTrue(ready.getAsBoolean());}
    private interface Cleanup{void run()throws Exception;}
    private static void clean(List<Throwable> failures,Cleanup cleanup){try{cleanup.run();}catch(Throwable failure){failures.add(failure);}}
}
