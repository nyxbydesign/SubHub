package com.subhub.app.remote;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;
import org.junit.*;

/** Actual isolated Feature executors and SQLite; no global Feature/provider, native runtime or OS grants. */
public final class RemoteFeatureSchedulingAndroidTest {
    private Context target, context;
    private File directory;
    private String prefix;
    private RemoteStore store;
    private RemoteFeature feature;
    private final CountDownLatch release=new CountDownLatch(1);
    private BlockingTransport transport;
    private AtomicInteger leases;
    private final Set<String> preferenceNames=new HashSet<>();
    private String wrappingAlias;
    private final AtomicInteger serviceStarts=new AtomicInteger();
    private final AtomicInteger serviceStops=new AtomicInteger(),backgroundKicks=new AtomicInteger();
    private final AtomicBoolean failNextChoiceCommit=new AtomicBoolean();
    private volatile boolean backgroundNeeded;
    @Before public void setup() throws Exception {
        target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefix="scheduling-"+UUID.randomUUID()+"-";directory=new File(target.getCacheDir(),prefix+"owned");
        context=new ContextWrapper(target) {
            @Override public Context getApplicationContext(){return this;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode){
                synchronized(preferenceNames){preferenceNames.add(name);}
                SharedPreferences selected=target.getSharedPreferences(prefix+name,mode);
                if(!BackgroundSyncPreferences.STORE.equals(name)||!failNextChoiceCommit.get())return selected;
                return (SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
                    if(!method.getName().equals("edit"))return method.invoke(selected,args);
                    SharedPreferences.Editor edit=selected.edit();
                    return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},(editor,operation,values)->{
                        if(operation.getName().equals("commit")&&failNextChoiceCommit.compareAndSet(true,false)){
                            // Inject the documented commit(false) contract: memory changed although persistence failed.
                            edit.apply();return false;
                        }
                        Object result=operation.invoke(edit,values);return result==edit?editor:result;
                    });
                });
            }
            @Override public File getFilesDir(){return child("files");}
            @Override public File getNoBackupFilesDir(){return child("no-backup");}
            @Override public File getCacheDir(){return child("cache");}
            @Override public android.content.ComponentName startForegroundService(android.content.Intent service){
                assertEquals(PeerSyncForegroundService.class.getName(),service.getComponent().getClassName());serviceStarts.incrementAndGet();return service.getComponent();
            }
            @Override public boolean stopService(android.content.Intent service){
                assertEquals(PeerSyncForegroundService.class.getName(),service.getComponent().getClassName());serviceStops.incrementAndGet();return true;
            }
            private File child(String name){File f=new File(directory,name);if(!f.isDirectory()&&!f.mkdirs())throw new IllegalStateException();return f;}
        };
        assertTrue(com.subhub.app.configuration.LegacyPackMigration.recoverAndMigrate(context));
        store=new RemoteStore(context,prefix+"db");store.getWritableDatabase();
        // Synthetic retained facts exercise scheduling only, never application readiness or authentication.
        for(int i=0;i<87;i++)insert(i,RemoteStore.State.REVOKED);
        for(int i=87;i<90;i++)insert(i,RemoteStore.State.ACTIVE);
        wrappingAlias="subhub.test.scheduling."+UUID.randomUUID();
        feature=new RemoteFeature(context,store,new RemoteSecrets(context,"secrets",wrappingAlias),PairingLifecycleClock.source(context),new RemoteFeature.BackgroundScheduling(){
            public void synchronize(Context ignored,boolean needed){backgroundNeeded=needed;}
            public void kick(Context ignored){backgroundKicks.incrementAndGet();}
        });assertTrue(feature.recoveryReady());
        leases=(AtomicInteger)field("leases");leases.incrementAndGet();
        transport=new BlockingTransport();set("endpoint",transport);
    }
    private void insert(int value,RemoteStore.State state){
        store.getWritableDatabase().execSQL("INSERT INTO peers(peer,generation,name,local_role,state) VALUES(?,?,?,?,?)",
                new Object[]{identity(value),generation(value),"Synthetic","DOM",state.name()});
    }
    private static String identity(int i){return String.format(Locale.ROOT,"%064x",i+1);}
    private static String generation(int i){return new UUID(0,i+1).toString();}
    private Object field(String name)throws Exception{Field f=RemoteFeature.class.getDeclaredField(name);f.setAccessible(true);return f.get(feature);}
    private void set(String name,Object value)throws Exception{Field f=RemoteFeature.class.getDeclaredField(name);f.setAccessible(true);f.set(feature,value);}
    private Object invoke(String name,Class<?>[] types,Object... args)throws Exception{
        Method m=RemoteFeature.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(feature,args);
    }
    private static void until(java.util.function.BooleanSupplier ready)throws Exception{
        long end=SystemClock.uptimeMillis()+5000;while(!ready.getAsBoolean()&&SystemClock.uptimeMillis()<end)Thread.sleep(10);assertTrue(ready.getAsBoolean());
    }
    private void blockIgnoringInterrupts(){boolean interrupted=false;for(;;)try{release.await();break;}catch(InterruptedException e){interrupted=true;}if(interrupted)Thread.currentThread().interrupt();}
    private final class BlockingTransport implements PeerTransport {
        final CountDownLatch revokedEntered=new CountDownLatch(1),activeEntered=new CountDownLatch(1);
        final AtomicInteger revokedCalls=new AtomicInteger(),activeCalls=new AtomicInteger();
        final BlockingQueue<Connection> arrivals=new ArrayBlockingQueue<>(1);
        volatile boolean closed,replyRevocations,failImmediately;
        public String localIdentity(){return identity(200);}
        public String inviteRoute(){return "";}
        public Connection connect(String peer,String route,long timeout)throws IOException{
            assertFalse(Thread.holdsLock(store));assertFalse(Thread.holdsLock(com.subhub.app.security.SubFeaturePolicy.LOCK));assertFalse(Thread.holdsLock(com.subhub.app.appmode.RuntimeSessionIdentity.LOCK));
            if(peer.equals(identity(87))||peer.equals(identity(88))||peer.equals(identity(89))){activeCalls.incrementAndGet();activeEntered.countDown();}else{revokedCalls.incrementAndGet();revokedEntered.countDown();}
            if(failImmediately)throw new IOException("Controlled unavailable transport");
            if(replyRevocations){
                ByteArrayOutputStream frame=new ByteArrayOutputStream();
                try{RemoteProtocol.write(frame,new JSONObject().put("version",1).put("type","revoke_ack").put("generation",generation(0)));}
                catch(org.json.JSONException invalid){throw new IOException(invalid);}
                return new Connection(){
                    final InputStream input=new ByteArrayInputStream(frame.toByteArray());final OutputStream output=new ByteArrayOutputStream();
                    public String authenticatedPeerIdentity(){return peer;}
                    public InputStream input(){return input;}public OutputStream output(){return output;}
                    public void setReadTimeoutMillis(long timeout){}public boolean isDirect(){return true;}public void close(){}
                };
            }
            blockIgnoringInterrupts();throw new IOException("Controlled unavailable transport");
        }
        public Connection accept(long timeout)throws IOException{
            if(closed)throw new IOException("Closed");
            try{Connection c=arrivals.poll(50,TimeUnit.MILLISECONDS);if(c!=null)return c;throw new java.net.SocketTimeoutException();}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
        }
        public void close(){closed=true;}
    }
    @Test public void retainedRevocationsCannotConsumeForegroundIncomingOrCurrentPeerCapacity()throws Exception{
        invoke("tick",new Class<?>[0]);
        assertTrue(transport.revokedEntered.await(5,TimeUnit.SECONDS));assertTrue(transport.activeEntered.await(5,TimeUnit.SECONDS));
        assertEquals("One retained revocation at a time",1,transport.revokedCalls.get());assertEquals("One current worker remains available beside retained delivery",1,transport.activeCalls.get());
        assertEquals("foreground",feature.submit(()->"foreground").get(5,TimeUnit.SECONDS));
        // Exercise the actual reserved inbound executor without touching global WorkManager jobs.
        CountDownLatch incomingProgress=new CountDownLatch(1);
        ((ThreadPoolExecutor)field("incomingSessions")).execute(incomingProgress::countDown);
        assertTrue(incomingProgress.await(5,TimeUnit.SECONDS));
        ThreadPoolExecutor out=(ThreadPoolExecutor)field("sessions"),in=(ThreadPoolExecutor)field("incomingSessions"),ops=(ThreadPoolExecutor)field("operations");
        assertEquals(4,out.getMaximumPoolSize()+in.getMaximumPoolSize()+ops.getMaximumPoolSize());
        assertEquals(0,out.getQueue().size());assertEquals(12,in.getQueue().remainingCapacity()+ops.getQueue().remainingCapacity());
        assertEquals(90,store.peers(true).size());assertEquals("",store.metadata(identity(0),generation(0),"revocation_delivered",""));
        release.countDown();until(()->((Set<?>)unchecked("busy")).isEmpty());until(()->leases.get()==1);
    }
    private Object unchecked(String name){try{return field(name);}catch(Exception e){throw new AssertionError(e);}}
    @Test public void cancellationRetainsRunningAdmissionAndRejectedQueuedWorkReleasesOwnership()throws Exception{
        Set<String> busy=(Set<String>)field("busy");RemoteStore.Peer first=store.peer(identity(0),generation(0)),second=store.peer(identity(1),generation(1));
        busy.add(first.identity+":"+first.generation);
        Future<?> running=(Future<?>)invoke("schedulePeer",new Class<?>[]{RemoteStore.Peer.class,long.class},first,System.nanoTime()+TimeUnit.SECONDS.toNanos(30));
        assertNotNull(running);assertTrue(transport.revokedEntered.await(5,TimeUnit.SECONDS));assertTrue(running.cancel(true));
        busy.add(second.identity+":"+second.generation);
        assertNull(invoke("schedulePeer",new Class<?>[]{RemoteStore.Peer.class,long.class},second,System.nanoTime()+TimeUnit.SECONDS.toNanos(30)));
        assertTrue(busy.contains(first.identity+":"+first.generation));assertFalse(busy.contains(second.identity+":"+second.generation));
        CountDownLatch entered=new CountDownLatch(1);CompletableFuture<Void> operation=feature.submit(()->{entered.countDown();blockIgnoringInterrupts();return null;});
        assertTrue(entered.await(5,TimeUnit.SECONDS));List<CompletableFuture<Integer>> queued=new ArrayList<>();
        for(int i=0;i<8;i++)queued.add(feature.submit(()->1));
        CompletableFuture<Integer> rejected=feature.submit(()->2);
        try{rejected.get(5,TimeUnit.SECONDS);fail();}catch(ExecutionException expected){assertTrue(expected.getCause() instanceof IOException);}
        for(CompletableFuture<?> value:queued)assertTrue(value.cancel(false));
        assertEquals(0,((ThreadPoolExecutor)field("operations")).getQueue().size());
        assertTrue(operation.cancel(true));
        assertEquals("Cancelled running operation retains its lease",2,leases.get());
        release.countDown();until(()->busy.isEmpty());until(()->leases.get()==1);
        assertEquals(1,((Semaphore)field("revocations")).availablePermits());
    }
    @Test public void backgroundResponderRetainsWindowAndRespectsFutureDueTimes()throws Exception{
        Map<String,Long> due=(Map<String,Long>)field("nextAttempt");
        for(RemoteStore.Peer peer:store.peers(true))due.put(peer.identity+":"+peer.generation,SystemClock.elapsedRealtime()+60_000);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try{
            Future<Boolean> result=worker.submit(()->feature.backgroundWindow(500));
            until(()->((AtomicInteger)unchecked("backgroundWindows")).get()==1);
            assertFalse("No outgoing work must not close the responder early",result.isDone());
            assertEquals(2,leases.get());
            assertTrue(result.get(5,TimeUnit.SECONDS));
            assertEquals(0,transport.activeCalls.get());assertEquals(0,transport.revokedCalls.get());
            assertEquals(0,((AtomicInteger)field("backgroundWindows")).get());assertEquals(1,leases.get());
        }finally{worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test public void completedOutgoingDeliveryStillLeavesTheBackgroundResponderAvailable()throws Exception{
        Map<String,Long> due=(Map<String,Long>)field("nextAttempt");
        for(RemoteStore.Peer peer:store.peers(true))due.put(peer.identity+":"+peer.generation,SystemClock.elapsedRealtime()+60_000);
        due.remove(identity(0)+":"+generation(0));transport.replyRevocations=true;
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try{
            Future<Boolean> result=worker.submit(()->feature.backgroundWindow(1500));
            until(()->"true".equals(store.metadata(identity(0),generation(0),"revocation_delivered","")));
            assertFalse("A finished outgoing exchange must leave time for a later inbound peer",result.isDone());
            assertEquals(1,((AtomicInteger)field("backgroundWindows")).get());
            assertTrue(result.get(5,TimeUnit.SECONDS));assertEquals(1,transport.revokedCalls.get());
        }finally{worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test public void stoppedBackgroundWindowReleasesOwnershipWithoutWaitingForDeadline()throws Exception{
        Map<String,Long> due=(Map<String,Long>)field("nextAttempt");
        for(RemoteStore.Peer peer:store.peers(true))due.put(peer.identity+":"+peer.generation,SystemClock.elapsedRealtime()+60_000);
        AtomicBoolean stopped=new AtomicBoolean();ExecutorService worker=Executors.newSingleThreadExecutor();
        try{
            Future<Boolean> result=worker.submit(()->feature.backgroundWindow(55_000,stopped::get));
            until(()->((AtomicInteger)unchecked("backgroundWindows")).get()==1);stopped.set(true);
            try{result.get(5,TimeUnit.SECONDS);fail("Stopped work should abort");}
            catch(ExecutionException expected){assertTrue(expected.getCause() instanceof InterruptedException);}
            assertEquals(0,((AtomicInteger)field("backgroundWindows")).get());assertEquals(1,leases.get());
        }finally{worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test public void failedBackgroundPeerRetriesOnlyAfterItsBackoff()throws Exception{
        Map<String,Long> due=(Map<String,Long>)field("nextAttempt");
        for(RemoteStore.Peer peer:store.peers(true))due.put(peer.identity+":"+peer.generation,SystemClock.elapsedRealtime()+60_000);
        due.remove(identity(87)+":"+generation(87));transport.failImmediately=true;
        assertFalse(feature.backgroundWindow(4200));
        assertEquals("One initial failure and one bounded due retry",2,transport.activeCalls.get());
        assertEquals(0,transport.revokedCalls.get());
    }
    @Test public void preexistingBusyOwnerCanSatisfyWindowWithAnExactCompletedSync()throws Exception{
        Map<String,Long> due=(Map<String,Long>)field("nextAttempt");
        for(RemoteStore.Peer peer:store.peers(true))due.put(peer.identity+":"+peer.generation,SystemClock.elapsedRealtime()+60_000);
        String key=identity(87)+":"+generation(87);due.remove(key);
        Set<String> busy=(Set<String>)field("busy");busy.add(key);ExecutorService worker=Executors.newSingleThreadExecutor();
        AtomicReference<Thread> backgroundThread=new AtomicReference<>();
        try{
            Future<Boolean> result=worker.submit(()->{backgroundThread.set(Thread.currentThread());return feature.backgroundWindow(1500);});
            until(()->((AtomicInteger)unchecked("backgroundWindows")).get()==1);
            until(()->backgroundThread.get()!=null&&backgroundThread.get().getState()==Thread.State.TIMED_WAITING);
            // Simulate the exact durable receipt emitted by a pre-existing foreground/inbound session.
            store.synced(identity(87),generation(87),System.currentTimeMillis());busy.remove(key);
            assertTrue(result.get(5,TimeUnit.SECONDS));assertEquals(0,transport.activeCalls.get());
        }finally{busy.remove(key);worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test public void preexistingBusyOwnerDoesNotHideMutationOrMissingCompletion()throws Exception{
        Map<String,Long> due=(Map<String,Long>)field("nextAttempt");
        for(RemoteStore.Peer peer:store.peers(true))due.put(peer.identity+":"+peer.generation,SystemClock.elapsedRealtime()+60_000);
        String key=identity(87)+":"+generation(87);due.remove(key);
        Set<String> busy=(Set<String>)field("busy");busy.add(key);ExecutorService worker=Executors.newSingleThreadExecutor();
        AtomicReference<Thread> backgroundThread=new AtomicReference<>();
        try{
            Future<Boolean> result=worker.submit(()->{backgroundThread.set(Thread.currentThread());return feature.backgroundWindow(1500);});
            until(()->((AtomicInteger)unchecked("backgroundWindows")).get()==1);
            until(()->backgroundThread.get()!=null&&backgroundThread.get().getState()==Thread.State.TIMED_WAITING);
            synchronized(field("attemptClockLock")){
                invoke("invalidateAttempts",new Class<?>[0]);
                for(RemoteStore.Peer peer:store.peers(true))if(!(peer.identity+":"+peer.generation).equals(key))due.put(peer.identity+":"+peer.generation,SystemClock.elapsedRealtime()+60_000);
                store.synced(identity(87),generation(87),System.currentTimeMillis());
            }
            // The older in-flight snapshot cannot prove that the newly dirty data was included.
            assertFalse(result.get(5,TimeUnit.SECONDS));assertEquals(0,transport.activeCalls.get());
        }finally{busy.remove(key);worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test public void dirtyMutationWhileBusyRemainsDueWhenTheOldExchangeExits()throws Exception{
        RemoteStore.Peer peer=store.peer(identity(87),generation(87));String key=peer.identity+":"+peer.generation;
        Set<String> busy=(Set<String>)field("busy");busy.add(key);
        Future<?> attempt=(Future<?>)invoke("schedulePeer",new Class<?>[]{RemoteStore.Peer.class,long.class},peer,System.nanoTime()+TimeUnit.SECONDS.toNanos(10));
        assertNotNull(attempt);assertTrue(transport.activeEntered.await(5,TimeUnit.SECONDS));
        invoke("invalidateAttempts",new Class<?>[0]);release.countDown();attempt.get(5,TimeUnit.SECONDS);until(()->busy.isEmpty());
        assertFalse("The pre-mutation exchange cannot consume the dirty wake",((Map<?,?>)field("nextAttempt")).containsKey(key));
    }
    @Test public void completionCannotClobberMutationAndFailuresBackOffToABoundedDelay()throws Exception{
        String key=identity(87)+":"+generation(87);Map<String,Long> due=(Map<String,Long>)field("nextAttempt");
        long old=(Long)invoke("attemptGeneration",new Class<?>[0]);
        invoke("invalidateAttempts",new Class<?>[0]);
        invoke("finishAttempt",new Class<?>[]{String.class,long.class,boolean.class,boolean.class},key,old,true,false);
        assertFalse("An old successful snapshot cannot delay new local data",due.containsKey(key));
        long generation=(Long)invoke("attemptGeneration",new Class<?>[0]);
        long prior=0;
        for(int failure=0;failure<8;failure++){
            long before=SystemClock.elapsedRealtime();
            invoke("finishAttempt",new Class<?>[]{String.class,long.class,boolean.class,boolean.class},key,generation,false,false);
            long delay=due.get(key)-before;
            assertTrue(delay>=Math.min(60_000,2_000L<<Math.min(5,failure)));assertTrue(delay<62_000);
            if(failure<4)assertTrue(delay>prior);prior=delay;
        }
        invoke("finishAttempt",new Class<?>[]{String.class,long.class,boolean.class,boolean.class},key,generation,true,false);
        assertFalse(((Map<?,?>)field("consecutiveFailures")).containsKey(key));
    }
    @Test public void connectedServiceLeaseIsSeparateFromPairingAndOldCloseCannotRetireANewOwner()throws Exception{
        AtomicInteger empty=new AtomicInteger();
        RemoteFeature.ConnectedServiceLease first=feature.acquireConnectedService(empty::incrementAndGet).get(5,TimeUnit.SECONDS);
        assertNotNull(first);assertTrue(feature.hasConnectedService());assertTrue(((Map<?,?>)field("pairingLeases")).isEmpty());
        first.close();assertFalse(feature.hasConnectedService());
        RemoteFeature.ConnectedServiceLease second=feature.acquireConnectedService(empty::incrementAndGet).get(5,TimeUnit.SECONDS);
        assertNotNull(second);assertNotSame(first,second);first.close();assertTrue(feature.hasConnectedService());
        second.close();assertFalse(feature.hasConnectedService());assertEquals(0,empty.get());
    }
    @Test public void connectedServiceRefusesPendingOnlyPeersWithoutBorrowingPairingAuthority()throws Exception{
        store.getWritableDatabase().execSQL("UPDATE peers SET state='PENDING'");
        assertNull(feature.acquireConnectedService(()->fail("No active owner was admitted")).get(5,TimeUnit.SECONDS));
        assertFalse(feature.hasConnectedService());assertTrue(((Map<?,?>)field("pairingLeases")).isEmpty());
    }
    @Test public void connectedServiceStartsFromVisibleLifecycleButNotBackgroundNetworkWork()throws Exception{
        set("installed",true);assertFalse((Boolean)field("foreground"));
        invoke("requestConnectedServiceStart",new Class<?>[0]);assertEquals(0,serviceStarts.get());
        set("foreground",true);invoke("requestConnectedServiceStart",new Class<?>[0]);until(()->serviceStarts.get()==1);
        set("foreground",false);feature.requestAutomaticSync();
        ((ScheduledExecutorService)field("timer")).submit(()->{}).get(5,TimeUnit.SECONDS);
        assertEquals("Background recovery cannot restart a foreground service",1,serviceStarts.get());
    }
    @Test public void stableReconnectClearsOfflineBackoffAndCoalescesExistingBackgroundWork()throws Exception{
        ScheduledExecutorService clock=(ScheduledExecutorService)field("timer");
        CountDownLatch entered=new CountDownLatch(1),resume=new CountDownLatch(1);
        clock.execute(()->{entered.countDown();try{resume.await();}catch(InterruptedException stopped){Thread.currentThread().interrupt();}});
        assertTrue(entered.await(5,TimeUnit.SECONDS));
        try{
            Map<String,Long> due=(Map<String,Long>)field("nextAttempt");
            due.put(identity(87)+":"+generation(87),SystemClock.elapsedRealtime()+60_000);
            ((Map<String,Integer>)field("consecutiveFailures")).put(identity(87)+":"+generation(87),6);
            set("nextEndpointAttempt",SystemClock.elapsedRealtime()+60_000);
            ((AtomicInteger)field("endpointFailures")).set(6);
            feature.requestAutomaticSync();
            for(int i=0;i<10;i++)feature.requestStableConnectionSync();
            resume.countDown();clock.submit(()->{}).get(5,TimeUnit.SECONDS);
            assertEquals(0L,field("nextEndpointAttempt"));assertEquals(0,((AtomicInteger)field("endpointFailures")).get());
            assertTrue(due.isEmpty());assertTrue(((Map<?,?>)field("consecutiveFailures")).isEmpty());
            assertEquals(1,backgroundKicks.get());assertEquals(0,serviceStarts.get());
            assertEquals(0,transport.activeCalls.get());assertSame(transport,field("endpoint"));assertFalse(transport.closed);
            assertEquals(90,store.peers(true).size());
        }finally{resume.countDown();}
    }
    @Test public void stableReconnectCannotBypassBackgroundOptOutOrRetiredRuntime()throws Exception{
        assertTrue(feature.setBackgroundSyncEnabled(false));
        feature.requestStableConnectionSync();
        ((ScheduledExecutorService)field("timer")).submit(()->{}).get(5,TimeUnit.SECONDS);
        assertEquals(0,backgroundKicks.get());assertEquals(0,transport.activeCalls.get());assertEquals(0,serviceStarts.get());
        set("stopped",true);
        try{
            set("nextEndpointAttempt",12345L);feature.requestStableConnectionSync();
            ((ScheduledExecutorService)field("timer")).submit(()->{}).get(5,TimeUnit.SECONDS);
            assertEquals(12345L,field("nextEndpointAttempt"));assertEquals(0,backgroundKicks.get());
        }finally{set("stopped",false);}
    }
    @Test public void connectedServiceBackpressureKeepsOneFutureDueWakeInsteadOfSpinning()throws Exception{
        RemoteFeature.ConnectedServiceLease owner=feature.acquireConnectedService(()->{}).get(5,TimeUnit.SECONDS);
        try{
            assertTrue(transport.revokedEntered.await(5,TimeUnit.SECONDS));assertTrue(transport.activeEntered.await(5,TimeUnit.SECONDS));
            until(()->{ScheduledFuture<?> wake=(ScheduledFuture<?>)unchecked("connectedServiceWake");return wake!=null&&wake.getDelay(TimeUnit.MILLISECONDS)>500;});
            assertTrue(((Map<String,Long>)field("nextAttempt")).get(identity(88)+":"+generation(88))>SystemClock.elapsedRealtime());
            assertEquals(1,transport.activeCalls.get());assertEquals(1,transport.revokedCalls.get());
        }finally{if(owner!=null)owner.close();}
    }
    @Test public void backgroundOptOutRetiresOnlyBackgroundOwnersAndForegroundSyncContinues()throws Exception{
        assertTrue(BackgroundSyncPreferences.enabled(context));
        Map<String,Long> due=(Map<String,Long>)field("nextAttempt");
        for(RemoteStore.Peer peer:store.peers(true))due.put(keyForTest(peer),SystemClock.elapsedRealtime()+60_000);
        RemoteFeature.ConnectedServiceLease owner=feature.acquireConnectedService(()->{}).get(5,TimeUnit.SECONDS);
        assertNotNull(owner);assertTrue(feature.hasConnectedService());
        failNextChoiceCommit.set(true);assertFalse("Report the failed save while reconciling its observed memory state",feature.setBackgroundSyncEnabled(false));
        assertFalse(BackgroundSyncPreferences.enabled(context));assertTrue(owner.isClosed());assertFalse(feature.hasConnectedService());
        assertNull(field("connectedServiceWake"));assertFalse(backgroundNeeded);assertEquals(1,serviceStops.get());
        assertEquals(90,store.peers(true).size());assertTrue(((Map<?,?>)field("pairingLeases")).isEmpty());
        feature.changed();feature.requestAutomaticSync();
        ((ScheduledExecutorService)field("timer")).submit(()->{}).get(5,TimeUnit.SECONDS);
        assertEquals(0,backgroundKicks.get());assertTrue(feature.backgroundWindow(1000));
        assertEquals(0,((AtomicInteger)field("backgroundWindows")).get());
        set("foreground",true);feature.kick();
        assertTrue("Opting out cannot disable foreground exchange",transport.activeEntered.await(5,TimeUnit.SECONDS));
        assertSame(transport,field("endpoint"));assertFalse(transport.closed);
        set("foreground",false);assertTrue(feature.setBackgroundSyncEnabled(true));
        ((ScheduledExecutorService)field("timer")).submit(()->{}).get(5,TimeUnit.SECONDS);
        assertTrue(backgroundNeeded);assertTrue(backgroundKicks.get()>0);assertTrue(BackgroundSyncPreferences.enabled(context));
    }
    @Test public void queuedServiceStartAndLeaseAcquisitionCannotOutrunPersistedOptOut()throws Exception{
        CountDownLatch timerEntered=new CountDownLatch(1),timerRelease=new CountDownLatch(1),operationEntered=new CountDownLatch(1);
        ScheduledExecutorService clock=(ScheduledExecutorService)field("timer");
        clock.execute(()->{timerEntered.countDown();try{timerRelease.await();}catch(InterruptedException stopped){Thread.currentThread().interrupt();}});
        assertTrue(timerEntered.await(5,TimeUnit.SECONDS));
        feature.submit(()->{operationEntered.countDown();blockIgnoringInterrupts();return null;});
        assertTrue(operationEntered.await(5,TimeUnit.SECONDS));
        set("installed",true);set("foreground",true);invoke("requestConnectedServiceStart",new Class<?>[0]);
        CompletableFuture<RemoteFeature.ConnectedServiceLease> acquiring=feature.acquireConnectedService(()->{});
        try{
            assertTrue(feature.setBackgroundSyncEnabled(false));
            timerRelease.countDown();clock.submit(()->{}).get(5,TimeUnit.SECONDS);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(0,serviceStarts.get());assertFalse(feature.hasConnectedService());assertFalse(backgroundNeeded);
            set("foreground",false);set("installed",false);
            assertTrue(feature.setBackgroundSyncEnabled(true));
            Map<String,Long> due=(Map<String,Long>)field("nextAttempt");
            for(RemoteStore.Peer peer:store.peers(true))due.put(keyForTest(peer),SystemClock.elapsedRealtime()+60_000);
            CompletableFuture<RemoteFeature.ConnectedServiceLease> replacement=feature.acquireConnectedService(()->{});
            release.countDown();assertNull("Off/on cannot revive a request admitted under the old choice",acquiring.get(5,TimeUnit.SECONDS));
            RemoteFeature.ConnectedServiceLease first=replacement.get(5,TimeUnit.SECONDS);assertNotNull(first);assertFalse(first.isClosed());
            RemoteFeature.ConnectedServiceLease newest=feature.acquireConnectedService(()->{}).get(5,TimeUnit.SECONDS);
            assertNotNull(newest);assertNotSame(first,newest);assertTrue(first.isClosed());
            first.close();assertTrue(feature.hasConnectedService());assertSame(newest,field("connectedServiceLease"));assertFalse(newest.isClosed());
            newest.close();
        }finally{release.countDown();timerRelease.countDown();}
    }
    @Test public void optOutDrainsOwnedBackgroundWindowWithoutClosingForegroundEndpoint()throws Exception{
        set("foreground",true);
        Map<String,Long> due=(Map<String,Long>)field("nextAttempt");
        for(RemoteStore.Peer peer:store.peers(true))due.put(keyForTest(peer),SystemClock.elapsedRealtime()+60_000);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try{
            Future<Boolean> result=worker.submit(()->feature.backgroundWindow(55_000));
            until(()->((AtomicInteger)unchecked("backgroundWindows")).get()==1);
            assertTrue(feature.setBackgroundSyncEnabled(false));
            assertTrue(result.get(5,TimeUnit.SECONDS));assertEquals(1,leases.get());
            assertEquals(0,((AtomicInteger)field("backgroundWindows")).get());
            assertSame(transport,field("endpoint"));assertFalse(transport.closed);assertEquals(90,store.peers(true).size());
        }finally{worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }
    /** One owned active peer, with automatic work parked beyond this test's assertions. */
    private RemoteFeature.ConnectedServiceLease quietOwner()throws Exception{
        store.getWritableDatabase().execSQL("DELETE FROM peers WHERE peer<>?",new Object[]{identity(87)});
        ((Map<String,Long>)field("nextAttempt")).put(identity(87)+":"+generation(87),SystemClock.elapsedRealtime()+900_000);
        RemoteFeature.ConnectedServiceLease owner=feature.acquireConnectedService(()->{}).get(5,TimeUnit.SECONDS);
        assertNotNull(owner);((ScheduledExecutorService)field("timer")).submit(()->{}).get(5,TimeUnit.SECONDS);
        assertEquals(0,transport.activeCalls.get());return owner;
    }
    @Test public void quietBackgroundCadenceKeepsNewDataFastWithoutResettingOfflineBackoff()throws Exception{
        try(RemoteFeature.ConnectedServiceLease owner=quietOwner()){
            String key=identity(87)+":"+generation(87);Map<String,Long> due=(Map<String,Long>)field("nextAttempt");
            long generation=(Long)invoke("attemptGeneration",new Class<?>[0]),before=SystemClock.elapsedRealtime();
            invoke("finishAttempt",new Class<?>[]{String.class,long.class,boolean.class,boolean.class},key,generation,true,false);
            assertTrue(due.get(key)>=before+300_000);assertTrue(due.get(key)<SystemClock.elapsedRealtime()+301_000);
            invoke("invalidateChangedAttempts",new Class<?>[0]);assertFalse("New data to a healthy peer stays immediately due",due.containsKey(key));
            generation=(Long)invoke("attemptGeneration",new Class<?>[0]);
            invoke("finishAttempt",new Class<?>[]{String.class,long.class,boolean.class,boolean.class},key,generation,false,false);
            long failedDue=due.get(key);assertTrue(failedDue>=SystemClock.elapsedRealtime()+29_000);
            for(int i=0;i<10;i++)feature.changed();
            ((ScheduledExecutorService)field("timer")).submit(()->{}).get(5,TimeUnit.SECONDS);
            assertEquals(failedDue,due.get(key).longValue());assertEquals(0,transport.activeCalls.get());
            assertEquals(1,((Map<String,Integer>)field("consecutiveFailures")).get(key).intValue());
            // New data during an authenticated successful duplex snapshot still needs another exchange.
            invoke("finishAttempt",new Class<?>[]{String.class,long.class,boolean.class,boolean.class},key,generation,true,false);
            assertFalse(due.containsKey(key));assertFalse(((Map<?,?>)field("consecutiveFailures")).containsKey(key));
            generation=(Long)invoke("attemptGeneration",new Class<?>[0]);set("foreground",true);before=SystemClock.elapsedRealtime();
            invoke("finishAttempt",new Class<?>[]{String.class,long.class,boolean.class,boolean.class},key,generation,true,false);
            assertTrue(due.get(key)>=before+30_000);assertTrue(due.get(key)<SystemClock.elapsedRealtime()+31_000);
            set("foreground",false);before=SystemClock.elapsedRealtime();
            invoke("finishAttempt",new Class<?>[]{String.class,long.class,boolean.class,boolean.class},key,generation,false,true);
            assertTrue("Pairing/revocation remains fast in background",due.get(key)<before+3_100);
            ((AtomicInteger)field("backgroundWindows")).incrementAndGet();
            try{
                ((Map<?,?>)field("consecutiveFailures")).clear();before=SystemClock.elapsedRealtime();
                invoke("finishAttempt",new Class<?>[]{String.class,long.class,boolean.class,boolean.class},key,generation,false,false);
                assertTrue("A bounded window sharing the service retains its short retry",due.get(key)<before+3_100);
            }finally{((AtomicInteger)field("backgroundWindows")).decrementAndGet();}
            due.put(key,SystemClock.elapsedRealtime()+900_000);
            ((Map<String,Integer>)field("consecutiveFailures")).put(key,6);
            // Synthetic durable revocation, then the production notification path: never carry a
            // previously ACTIVE peer's slow backoff into retained revocation delivery.
            store.getWritableDatabase().execSQL("UPDATE peers SET state='REVOKED' WHERE peer=?",new Object[]{identity(87)});
            store.revoke(identity(87),generation(87));
            assertFalse(due.containsKey(key));assertFalse(((Map<?,?>)field("consecutiveFailures")).containsKey(key));
            invoke("finishAttempt",new Class<?>[]{String.class,long.class,boolean.class,boolean.class},key,generation,false,false);
            assertFalse("A late old ACTIVE failure cannot delay revocation",due.containsKey(key));
        }
    }
    @Test public void dirtyBackgroundFailureBacksOffButNetworkResetKeepsRetryDue()throws Exception{
        try(RemoteFeature.ConnectedServiceLease owner=quietOwner()){
            RemoteStore.Peer peer=store.peer(identity(87),generation(87));String key=keyForTest(peer);
            Set<String> busy=(Set<String>)field("busy");busy.add(key);
            Future<?> attempt=(Future<?>)invoke("schedulePeer",new Class<?>[]{RemoteStore.Peer.class,long.class},peer,System.nanoTime()+TimeUnit.SECONDS.toNanos(10));
            assertNotNull(attempt);assertTrue(transport.activeEntered.await(5,TimeUnit.SECONDS));
            invoke("invalidateChangedAttempts",new Class<?>[0]);release.countDown();attempt.get(5,TimeUnit.SECONDS);until(()->busy.isEmpty());
            Map<String,Long> due=(Map<String,Long>)field("nextAttempt");
            assertTrue("A new source event cannot bypass the failed background attempt",due.get(key)>SystemClock.elapsedRealtime()+28_000);
            long old=(Long)invoke("attemptGeneration",new Class<?>[0]);invoke("invalidateAttempts",new Class<?>[0]);
            invoke("finishAttempt",new Class<?>[]{String.class,long.class,boolean.class,boolean.class},key,old,false,false);
            assertFalse("A late offline failure cannot undo explicit network recovery",due.containsKey(key));
            assertTrue(((Map<?,?>)field("consecutiveFailures")).isEmpty());
        }
    }
    @Test public void backgroundHidingPreservesBackoffAndForegroundResetsIt()throws Exception{
        try(RemoteFeature.ConnectedServiceLease owner=quietOwner()){
            String key=identity(87)+":"+generation(87);Map<String,Long> due=(Map<String,Long>)field("nextAttempt");
            long generation=(Long)invoke("attemptGeneration",new Class<?>[0]);
            invoke("finishAttempt",new Class<?>[]{String.class,long.class,boolean.class,boolean.class},key,generation,false,false);
            long failedDue=due.get(key);
            invoke("requestAutomaticSync",new Class<?>[]{boolean.class},false);
            ((ScheduledExecutorService)field("timer")).submit(()->{}).get(5,TimeUnit.SECONDS);
            assertEquals(failedDue,due.get(key).longValue());assertEquals(0,transport.activeCalls.get());
            set("foreground",true);feature.requestAutomaticSync();
            assertTrue(transport.activeEntered.await(5,TimeUnit.SECONDS));
            assertFalse(((Map<?,?>)field("consecutiveFailures")).containsKey(key));
        }
    }
    @Test public void foregroundPollingStopsWhileHiddenAndRestartsOnce()throws Exception{
        assertNull(field("foregroundPoll"));set("foreground",true);
        invoke("refreshForegroundPolling",new Class<?>[0]);ScheduledFuture<?> first=(ScheduledFuture<?>)field("foregroundPoll");assertNotNull(first);
        set("foreground",false);invoke("refreshForegroundPolling",new Class<?>[0]);
        assertTrue(first.isCancelled());assertNull(field("foregroundPoll"));
        set("foreground",true);invoke("refreshForegroundPolling",new Class<?>[0]);
        ScheduledFuture<?> next=(ScheduledFuture<?>)field("foregroundPoll");assertNotNull(next);assertNotSame(first,next);assertFalse(next.isCancelled());
        invoke("refreshForegroundPolling",new Class<?>[0]);assertTrue(next.isCancelled());assertFalse(((ScheduledFuture<?>)field("foregroundPoll")).isCancelled());
        set("foreground",false);invoke("refreshForegroundPolling",new Class<?>[0]);assertNull(field("foregroundPoll"));
    }
    @Test public void pairingPollsStopAtLastLeaseAndRestartForReplacement()throws Exception{
        // No SQL or transport authority is manufactured: closed synthetic leases only exercise timer ownership.
        // Gate timer execution so the test can inspect ownership before any poll body runs.
        CountDownLatch timerEntered=new CountDownLatch(1),deadlineEntered=new CountDownLatch(1),resume=new CountDownLatch(1);
        ScheduledExecutorService timer=(ScheduledExecutorService)field("timer"),deadline=(ScheduledExecutorService)field("pairingDeadlineTimer");
        Runnable block=()->{try{resume.await();}catch(InterruptedException stopped){Thread.currentThread().interrupt();}};
        timer.execute(()->{timerEntered.countDown();block.run();});deadline.execute(()->{deadlineEntered.countDown();block.run();});
        assertTrue(timerEntered.await(5,TimeUnit.SECONDS));assertTrue(deadlineEntered.await(5,TimeUnit.SECONDS));
        Map<String,PairingOperationLease> owners=(Map<String,PairingOperationLease>)field("pairingLeases");
        PairingOperationLease first=new PairingOperationLease(null,"synthetic",null,null,null,ignored->{});first.close();
        PairingOperationLease replacement=new PairingOperationLease(null,"synthetic",null,null,null,ignored->{});replacement.close();
        try{
            owners.put("test",first);invoke("installPairingTimer",new Class<?>[0]);
            ScheduledFuture<?> oldPoll=(ScheduledFuture<?>)field("pairingPollWake"),oldDeadline=(ScheduledFuture<?>)field("pairingDeadlineWake");
            assertNotNull(oldPoll);assertNotNull(oldDeadline);
            owners.remove("test",first);invoke("stopIdlePairingTimers",new Class<?>[0]);
            assertTrue(oldPoll.isCancelled());assertTrue(oldDeadline.isCancelled());assertFalse((Boolean)field("pairingTimerInstalled"));
            owners.put("test",replacement);invoke("installPairingTimer",new Class<?>[0]);
            ScheduledFuture<?> newPoll=(ScheduledFuture<?>)field("pairingPollWake"),newDeadline=(ScheduledFuture<?>)field("pairingDeadlineWake");
            assertNotSame(oldPoll,newPoll);assertNotSame(oldDeadline,newDeadline);
            owners.remove("test",first);invoke("stopIdlePairingTimers",new Class<?>[0]);
            assertFalse("A stale owner cannot cancel its replacement",newPoll.isCancelled());assertFalse(newDeadline.isCancelled());
            owners.remove("test",replacement);invoke("stopIdlePairingTimers",new Class<?>[0]);
            assertTrue(newPoll.isCancelled());assertTrue(newDeadline.isCancelled());assertNull(field("pairingPollWake"));assertNull(field("pairingDeadlineWake"));
        }finally{owners.clear();invoke("stopIdlePairingTimers",new Class<?>[0]);resume.countDown();}
    }
    private static String keyForTest(RemoteStore.Peer peer){return peer.identity+":"+peer.generation;}
    @After public void cleanup()throws Exception{
        release.countDown();
        if(feature!=null){if(leases!=null)leases.decrementAndGet();feature.shutdownForTest();}
        else if(store!=null)store.close();
        if(feature!=null){
            for(String name:List.of("timer","acceptor","operations","incomingSessions","sessions","pairingDeadlineTimer")){
                ExecutorService worker=(ExecutorService)field(name);assertTrue(name+" must shut down",worker.isShutdown());assertTrue(name+" must terminate",worker.awaitTermination(10,TimeUnit.SECONDS));
            }
            Field activityWorker=RemoteActivityRepository.class.getDeclaredField("worker");activityWorker.setAccessible(true);
            ExecutorService reader=(ExecutorService)activityWorker.get(feature.activity);assertTrue(reader.isShutdown());assertTrue(reader.awaitTermination(10,TimeUnit.SECONDS));
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        if(prefix!=null){assertTrue(target.deleteDatabase(prefix+"db"));for(String name:preferenceNames)target.getSharedPreferences(prefix+name,0).edit().clear().commit();}
        if(wrappingAlias!=null){assertTrue(wrappingAlias.startsWith("subhub.test.scheduling."));java.security.KeyStore keys=java.security.KeyStore.getInstance("AndroidKeyStore");keys.load(null);if(keys.containsAlias(wrappingAlias))keys.deleteEntry(wrappingAlias);}
        if(context!=null){Field ready=com.subhub.app.configuration.LegacyPackMigration.class.getDeclaredField("READY");ready.setAccessible(true);synchronized(com.subhub.app.configuration.LegacyPackMigration.class){((Set<?>)ready.get(null)).remove(context.getFilesDir().getAbsoluteFile());}}
        if(directory!=null&&directory.exists()){
            java.nio.file.Path root=directory.getCanonicalFile().toPath();assertTrue(root.startsWith(target.getCacheDir().getCanonicalFile().toPath()));
            try(java.util.stream.Stream<java.nio.file.Path> paths=java.nio.file.Files.walk(root)){
                for(java.nio.file.Path path:(Iterable<java.nio.file.Path>)paths.sorted(Comparator.reverseOrder())::iterator){assertFalse(java.nio.file.Files.isSymbolicLink(path));assertTrue(path.toRealPath().startsWith(root));java.nio.file.Files.delete(path);}
            }
        }
    }
}
