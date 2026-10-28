package com.subhub.app.remote;

import static org.junit.Assert.*;
import android.content.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.appmode.*;
import com.subhub.app.people.*;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.stats.StatsRepository;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

@RunWith(AndroidJUnit4.class)
public class RemoteRuntimeAndroidTest {
    private static final String DOM=repeat('a'),SUB=repeat('b');
    private Context context;private File root;private String prefix,generation;private RemoteFeature feature;private RemoteStore source;
    private final Set<String> preferences=new HashSet<>();
    @Before public void setup()throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();prefix="runtime-"+UUID.randomUUID();root=new File(target.getCacheDir(),prefix);assertTrue(root.mkdirs());
        context=new ContextWrapper(target){@Override public Context getApplicationContext(){return this;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode){preferences.add(name);return super.getSharedPreferences(prefix+name,mode);}
            @Override public File getFilesDir(){return directory("files");}@Override public File getNoBackupFilesDir(){return directory("private");}
            private File directory(String name){File value=new File(root,name);value.mkdirs();return value;}};
        assertTrue(com.subhub.app.configuration.LegacyPackMigration.recoverAndMigrate(context));
        new StatsRepository(context).endSession();feature=new RemoteFeature(context,new RemoteStore(context,prefix+"dom.db"));source=new RemoteStore(context,prefix+"sub.db");
        assertTrue(feature.recoveryReady());
        PairingInvite invite=PairingInvite.create(DOM,"ticket",1000);generation=invite.id;feature.store.saveInvite(invite);feature.store.bindInvite(generation,SUB,invite.tokenHash(),1001);feature.store.approveInvite(generation,SUB,"Sub",1001);feature.store.activate(SUB,generation);source.acceptInvite(invite,"Dom");source.activate(DOM,generation);
    }
    @After public void cleanup()throws Exception {
        new AppModeManager(context).setArmed(false);new StatsRepository(context).endSession();feature.shutdownForTest();source.close();context.deleteDatabase(prefix+"dom.db");context.deleteDatabase(prefix+"sub.db");
        for(String name:preferences)context.getSharedPreferences(name,0).edit().clear().commit();
        try(java.util.stream.Stream<java.nio.file.Path> paths=java.nio.file.Files.walk(root.toPath())){for(java.nio.file.Path path:paths.sorted(Comparator.reverseOrder()).toArray(java.nio.file.Path[]::new))java.nio.file.Files.delete(path);}
        ControllerPinManager.enterSubMode();
    }
    @Test public void unknownDatasetsNeverBecomeMeasuredZerosOrConnected()throws Exception {
        JSONObject configuration=RemoteConfigurationAndroidTest.configuration();configuration.getJSONObject("contract").getJSONObject("sections").getJSONObject("limits").put("app_timer_per_app_enabled",true);
        configuration.getJSONObject("apps").getJSONArray("packages").put("com.android.settings");
        source.publish(DOM,generation,RemoteProtocol.Stream.BASELINE,"active",configuration,false,1);transfer(RemoteProtocol.Stream.BASELINE);
        PersonSnapshot snapshot=new RemotePersonRepository(context,feature).load(RemotePersonRepository.id(peer()));
        assertEquals(PersonSnapshot.Connection.PAIRED,snapshot.connection);assertEquals(PersonSnapshot.Freshness.UNKNOWN,snapshot.sync.stats);assertEquals(PersonSnapshot.Freshness.UNKNOWN,snapshot.sync.runtime);
        assertEquals(1,snapshot.allowances.size());assertEquals(-1,snapshot.allowances.get(0).usedMillis);assertFalse(snapshot.serviceActive);assertFalse(snapshot.can(PersonSnapshot.Capability.END_SESSION));
    }
    @Test public void invalidSummaryAndRuntimeStayUnknownAndStaleDoesNotGrantEnd()throws Exception {
        source.publish(DOM,generation,RemoteProtocol.Stream.STATS,"summary",new JSONObject().put("schema",1).put("protectedSeconds",0),false,1);transfer(RemoteProtocol.Stream.STATS);
        source.publish(DOM,generation,RemoteProtocol.Stream.RUNTIME,"current",new JSONObject().put("serviceActive",true),false,1);transfer(RemoteProtocol.Stream.RUNTIME);
        RemotePersonRepository repository=new RemotePersonRepository(context,feature);PersonSnapshot snapshot=repository.load(RemotePersonRepository.id(peer()));assertEquals(PersonSnapshot.Freshness.UNKNOWN,snapshot.sync.stats);assertEquals(PersonSnapshot.Freshness.UNKNOWN,snapshot.sync.runtime);
        new AppModeManager(context).setArmed(true);new RemoteTelemetry(context,source).prepare(source.peer(DOM,generation));transfer(RemoteProtocol.Stream.RUNTIME);transfer(RemoteProtocol.Stream.STATS);
        snapshot=repository.load(RemotePersonRepository.id(peer()));assertTrue(snapshot.can(PersonSnapshot.Capability.END_SESSION));assertFalse(snapshot.sessionIdentity.isEmpty());
        feature.store.putMetadata(SUB,generation,"confirmed_RUNTIME",Long.toString(System.currentTimeMillis()-100_000));snapshot=repository.load(RemotePersonRepository.id(peer()));
        assertEquals(PersonSnapshot.Freshness.STALE,snapshot.sync.runtime);assertFalse(snapshot.can(PersonSnapshot.Capability.END_SESSION));assertFalse(snapshot.serviceActive);
    }
    @Test public void unauthorizedPairingDoesNotCreateIdentityOrOpenTransport()throws Exception {
        ControllerPinManager.enterSubMode();ExecutionException failure=assertThrows(ExecutionException.class,()->feature.createInvite().get(10,TimeUnit.SECONDS));assertTrue(failure.getCause() instanceof SecurityException);
        assertTrue(feature.store.invitations(System.currentTimeMillis()).isEmpty());assertTrue(context.getSharedPreferences("subhub_remote_secrets_v1",0).getAll().isEmpty());
    }
    @Test public void targetedEndReplaysSafelyAndCannotEndALaterSession()throws Exception {
        AppModeManager mode=new AppModeManager(context);mode.setArmed(true);assertTrue(mode.isArmed());String old=RuntimeSessionIdentity.current(context);assertFalse(old.isEmpty());publishRuntime();
        String request=feature.endCommands.queue(peer(),old,System.currentTimeMillis());deliverEnd();
        RemoteEndCommands receiver=new RemoteEndCommands(context,source);receiver.process(source.peer(DOM,generation),System.currentTimeMillis());assertFalse(mode.isArmed());
        mode.setArmed(true);String next=RuntimeSessionIdentity.current(context);assertNotEquals(old,next);
        receiver.process(source.peer(DOM,generation),System.currentTimeMillis());assertTrue(mode.isArmed());assertEquals(next,RuntimeSessionIdentity.current(context));
        transfer(RemoteProtocol.Stream.RUNTIME);feature.endCommands.process(peer(),System.currentTimeMillis());assertEquals("ENDED",feature.endCommands.latest(peer()).getString("state"));
        assertEquals(request,feature.endCommands.latest(peer()).getJSONObject("request").getString("id"));assertFalse(ControllerPinManager.isDomModeActive());
    }
    @Test public void expiredStaleAndRevokedEndRequestsLeaveRuntimeActive()throws Exception {
        new AppModeManager(context).setArmed(true);String original=RuntimeSessionIdentity.current(context);publishRuntime();
        feature.endCommands.queue(peer(),original,System.currentTimeMillis());deliverEnd();RemoteEndCommands receiver=new RemoteEndCommands(context,source);
        receiver.process(source.peer(DOM,generation),System.currentTimeMillis()+RemoteEndCommands.LIFETIME+1);assertTrue(new AppModeManager(context).isArmed());transfer(RemoteProtocol.Stream.RUNTIME);feature.endCommands.process(peer(),System.currentTimeMillis());assertEquals("EXPIRED",feature.endCommands.latest(peer()).getString("state"));
        publishRuntime();feature.endCommands.queue(peer(),original,System.currentTimeMillis());deliverEnd();new AppModeManager(context).setArmed(false);new AppModeManager(context).setArmed(true);
        receiver.process(source.peer(DOM,generation),System.currentTimeMillis());assertTrue(new AppModeManager(context).isArmed());transfer(RemoteProtocol.Stream.RUNTIME);feature.endCommands.process(peer(),System.currentTimeMillis());assertEquals("REJECTED",feature.endCommands.latest(peer()).getString("state"));
        source.revoke(DOM,generation);assertThrows(SecurityException.class,()->receiver.process(source.peer(DOM,generation),System.currentTimeMillis()));assertTrue(new AppModeManager(context).isArmed());
    }
    @Test public void lostDispatchedReceiptNeverTargetsAnotherGenerationAfterReopen()throws Exception {
        new AppModeManager(context).setArmed(true);String original=RuntimeSessionIdentity.current(context);publishRuntime();feature.endCommands.queue(peer(),original,System.currentTimeMillis());deliverEnd();
        RemoteEndCommands receiver=new RemoteEndCommands(context,source);receiver.process(source.peer(DOM,generation),System.currentTimeMillis());source.close();source=new RemoteStore(context,prefix+"sub.db");
        new AppModeManager(context).setArmed(true);String next=RuntimeSessionIdentity.current(context);new RemoteEndCommands(context,source).process(source.peer(DOM,generation),System.currentTimeMillis());assertEquals(next,RuntimeSessionIdentity.current(context));assertTrue(new AppModeManager(context).isArmed());
    }
    @Test public void legacyActiveSessionWithoutGenerationCannotBeEndedRemotely()throws Exception {
        new AppModeManager(context).setArmed(true);context.getSharedPreferences(StatsRepository.PREFS_NAME,0).edit().remove("active_session_identity").commit();assertEquals("",RuntimeSessionIdentity.current(context));publishRuntime();
        assertThrows(IllegalArgumentException.class,()->feature.endCommands.queue(peer(),"",System.currentTimeMillis()));assertFalse(new RemotePersonRepository(context,feature).load(RemotePersonRepository.id(peer())).can(PersonSnapshot.Capability.END_SESSION));assertTrue(new AppModeManager(context).isArmed());
    }
    @Test public void cancellingRunningSessionKeepsPeerReservedUntilItsIoEnds()throws Exception {
        BlockingTransport transport=new BlockingTransport();field("endpoint").set(feature,transport);
        @SuppressWarnings("unchecked") Set<String> reservations=(Set<String>)field("busy").get(feature);String key=SUB+":"+generation;assertTrue(reservations.add(key));
        java.lang.reflect.Method schedule=RemoteFeature.class.getDeclaredMethod("schedulePeer",RemoteStore.Peer.class,long.class);schedule.setAccessible(true);
        Future<?> attempt=(Future<?>)schedule.invoke(feature,peer(),System.nanoTime()+TimeUnit.SECONDS.toNanos(5));
        assertTrue(transport.reading.await(3,TimeUnit.SECONDS));attempt.cancel(true);assertTrue("Cancelled running IO lost its peer reservation",reservations.contains(key));
        transport.connection.close();awaitReleased(reservations,key);assertFalse(reservations.contains(key));
    }
    @Test public void backgroundExpiryClosesIncomingAndOutgoingIo()throws Exception {
        BlockingTransport transport=new BlockingTransport();field("endpoint").set(feature,transport);
        BlockingTransport incoming=new BlockingTransport();java.lang.reflect.Method track=RemoteFeature.class.getDeclaredMethod("track",PeerTransport.Connection.class,String.class,long.class);track.setAccessible(true);
        track.invoke(feature,incoming.connection,"",System.nanoTime()+TimeUnit.SECONDS.toNanos(55));
        long start=android.os.SystemClock.elapsedRealtime();assertFalse(feature.backgroundWindow(250));
        assertTrue(transport.closed.await(2,TimeUnit.SECONDS));assertTrue(incoming.closed.await(2,TimeUnit.SECONDS));assertTrue(android.os.SystemClock.elapsedRealtime()-start<3000);
        @SuppressWarnings("unchecked") Set<String> reservations=(Set<String>)field("busy").get(feature);awaitReleased(reservations,SUB+":"+generation);
    }
    @Test public void queuedCancellationCannotReleaseANewerPeerReservation()throws Exception {
        BlockingTransport transport=new BlockingTransport();field("endpoint").set(feature,transport);
        CountDownLatch started=new CountDownLatch(4),release=new CountDownLatch(1);List<CompletableFuture<Void>> blockers=new ArrayList<>();
        try {
            for(int i=0;i<4;i++)blockers.add(feature.submit(()->{started.countDown();release.await();return null;}));assertTrue(started.await(3,TimeUnit.SECONDS));
            assertFalse(feature.backgroundWindow(100));
            @SuppressWarnings("unchecked") Set<String> reservations=(Set<String>)field("busy").get(feature);String key=SUB+":"+generation;
            assertFalse(reservations.contains(key));assertTrue(reservations.add(key));release.countDown();for(Future<?> blocker:blockers)blocker.get(3,TimeUnit.SECONDS);
            java.util.concurrent.ThreadPoolExecutor executor=(java.util.concurrent.ThreadPoolExecutor)field("sessions").get(feature);
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while((executor.getActiveCount()>0||!executor.getQueue().isEmpty())&&System.nanoTime()<end)Thread.sleep(10);
            assertTrue("Old cancelled task released a newer reservation",reservations.remove(key));
        } finally{release.countDown();}
    }
    private java.lang.reflect.Field field(String name)throws Exception {java.lang.reflect.Field field=RemoteFeature.class.getDeclaredField(name);field.setAccessible(true);return field;}
    private static void awaitReleased(Set<String> reservations,String key)throws InterruptedException {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(reservations.contains(key)&&System.nanoTime()<deadline)Thread.sleep(10);assertFalse(reservations.contains(key));}
    private static final class BlockingTransport implements PeerTransport {
        final CountDownLatch reading=new CountDownLatch(1),closed=new CountDownLatch(1);
        final Connection connection=new Connection(){
            @Override public String authenticatedPeerIdentity(){return SUB;}
            @Override public InputStream input(){return new InputStream(){@Override public int read(){reading.countDown();boolean interrupted=false;while(true)try{closed.await();break;}catch(InterruptedException ignored){interrupted=true;}if(interrupted)Thread.currentThread().interrupt();return -1;}};}
            @Override public OutputStream output(){return new ByteArrayOutputStream();}
            @Override public void setReadTimeoutMillis(long millis){}
            @Override public boolean isDirect(){return true;}
            @Override public void close(){closed.countDown();}
        };
        @Override public String localIdentity(){return DOM;}
        @Override public String inviteRoute(){return "";}
        @Override public Connection connect(String peer,String route,long millis){return connection;}
        @Override public Connection accept(long millis)throws IOException{throw new java.net.SocketTimeoutException();}
        @Override public void close()throws IOException{connection.close();}
    }
    private void publishRuntime()throws Exception {new RemoteTelemetry(context,source).prepare(source.peer(DOM,generation));transfer(RemoteProtocol.Stream.RUNTIME);}
    private void deliverEnd()throws Exception {RemoteProtocol.Batch batch=feature.store.nextBatch(SUB,generation,RemoteProtocol.Stream.CONFIGURATION);feature.store.acknowledge(SUB,source.receive(DOM,batch));feature.store.markSent(SUB,batch);}
    private void transfer(RemoteProtocol.Stream stream)throws Exception{RemoteProtocol.Batch batch=source.nextBatch(DOM,generation,stream);source.acknowledge(DOM,feature.store.receive(SUB,batch));feature.store.confirmHeads(SUB,generation,source.outgoingHeads(DOM,generation));}
    private RemoteStore.Peer peer(){return feature.store.peer(SUB,generation);}
    private static String repeat(char value){char[] data=new char[64];Arrays.fill(data,value);return new String(data);}
}
