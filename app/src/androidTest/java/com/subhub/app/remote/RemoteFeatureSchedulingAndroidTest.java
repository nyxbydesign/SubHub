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
    @Before public void setup() throws Exception {
        target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefix="scheduling-"+UUID.randomUUID()+"-";directory=new File(target.getCacheDir(),prefix+"owned");
        context=new ContextWrapper(target) {
            @Override public Context getApplicationContext(){return this;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode){synchronized(preferenceNames){preferenceNames.add(name);}return target.getSharedPreferences(prefix+name,mode);}
            @Override public File getFilesDir(){return child("files");}
            @Override public File getNoBackupFilesDir(){return child("no-backup");}
            @Override public File getCacheDir(){return child("cache");}
            private File child(String name){File f=new File(directory,name);if(!f.isDirectory()&&!f.mkdirs())throw new IllegalStateException();return f;}
        };
        assertTrue(com.subhub.app.configuration.LegacyPackMigration.recoverAndMigrate(context));
        store=new RemoteStore(context,prefix+"db");store.getWritableDatabase();
        // Synthetic retained facts exercise scheduling only, never application readiness or authentication.
        for(int i=0;i<87;i++)insert(i,RemoteStore.State.REVOKED);
        for(int i=87;i<90;i++)insert(i,RemoteStore.State.ACTIVE);
        wrappingAlias="subhub.test.scheduling."+UUID.randomUUID();
        feature=new RemoteFeature(context,store,new RemoteSecrets(context,"secrets",wrappingAlias));assertTrue(feature.recoveryReady());
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
        volatile boolean closed;
        public String localIdentity(){return identity(200);}
        public String inviteRoute(){return "";}
        public Connection connect(String peer,String route,long timeout)throws IOException{
            assertFalse(Thread.holdsLock(store));assertFalse(Thread.holdsLock(com.subhub.app.security.SubFeaturePolicy.LOCK));assertFalse(Thread.holdsLock(com.subhub.app.appmode.RuntimeSessionIdentity.LOCK));
            if(peer.equals(identity(87))||peer.equals(identity(88))||peer.equals(identity(89))){activeCalls.incrementAndGet();activeEntered.countDown();}else{revokedCalls.incrementAndGet();revokedEntered.countDown();}
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
    @After public void cleanup()throws Exception{
        release.countDown();
        if(feature!=null){if(leases!=null)leases.decrementAndGet();feature.shutdownForTest();}
        else if(store!=null)store.close();
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
