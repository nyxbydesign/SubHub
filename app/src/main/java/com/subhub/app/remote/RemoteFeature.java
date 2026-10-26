package com.subhub.app.remote;

import android.app.*;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import com.subhub.app.security.ControllerPinManager;
import org.json.JSONObject;
import java.io.*;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Owns one transport endpoint, bounded sessions and durable recovery independently of UI roles. */
public final class RemoteFeature {
    private static RemoteFeature instance;
    public static synchronized RemoteFeature get(Context context){if(instance==null)instance=new RemoteFeature(context);return instance;}
    private final Context context;
    public final RemoteStore store;
    public final RemoteContracts contracts;
    public final RemoteEndCommands endCommands;
    private final RemoteSecrets secrets;
    private final RemoteConfigurationWriter writer;
    private final RemoteTelemetry telemetry;
    private final Object endpointLock=new Object();
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"subhub-peer-clock"));
    private final ExecutorService acceptor=Executors.newSingleThreadExecutor(r->new Thread(r,"subhub-peer-accept"));
    private final ThreadPoolExecutor sessions=new ThreadPoolExecutor(4,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(12),r->new Thread(r,"subhub-peer-session"),new ThreadPoolExecutor.AbortPolicy());
    private final Set<String> busy=ConcurrentHashMap.newKeySet();
    private final Map<String,Long> nextAttempt=new ConcurrentHashMap<>();
    private final Map<PeerTransport.Connection,String> connections=new ConcurrentHashMap<>();
    private final Map<PeerTransport.Connection,Long> connectionDeadlines=new ConcurrentHashMap<>();
    private final AtomicInteger leases=new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean warming=new java.util.concurrent.atomic.AtomicBoolean();
    private volatile PeerTransport endpoint;
    private volatile boolean foreground,recovered;
    private boolean installed;

    RemoteFeature(Context context) {this(context,new RemoteStore(context));}
    RemoteFeature(Context context,RemoteStore store) {
        this.context=context.getApplicationContext();this.store=store;secrets=new RemoteSecrets(this.context);
        endCommands=new RemoteEndCommands(this.context,store);
        writer=new RemoteConfigurationWriter(this.context,store);contracts=new RemoteContracts(this.context,store,writer);telemetry=new RemoteTelemetry(this.context,store);
        sessions.allowCoreThreadTimeOut(true);
        try{writer.recover();writer.prunePrepared();recovered=true;}catch(Exception unavailable){recovered=false;}
    }
    public boolean recoveryReady(){return recovered;}
    public boolean controlsLocal(){return !recovered || store.isRemotelyControlled();}

    public synchronized void installLifecycle(Application app) {
        if(installed)return;installed=true;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            private int started;
            @Override public void onActivityStarted(Activity activity){if(++started==1){foreground=true;kick();}}
            @Override public void onActivityStopped(Activity activity){if(--started==0){foreground=false;timer.execute(RemoteFeature.this::suspendIfIdle);}}
            @Override public void onActivityCreated(Activity a,Bundle b){} @Override public void onActivityDestroyed(Activity a){}
            @Override public void onActivityResumed(Activity a){} @Override public void onActivityPaused(Activity a){}
            @Override public void onActivitySaveInstanceState(Activity a,Bundle b){}
        });
        timer.scheduleWithFixedDelay(()->{try{if(foreground)tick();}catch(RuntimeException unavailable){android.util.Log.w("PeerSync","Sync attempt deferred");}},2,2,TimeUnit.SECONDS);
        RemoteScheduler.synchronize(context,needsBackground());
    }
    public <T> CompletableFuture<T> submit(Callable<T> work) {
        CompletableFuture<T> result=new CompletableFuture<>();
        try{sessions.execute(()->{leases.incrementAndGet();try{result.complete(work.call());}catch(Exception failure){result.completeExceptionally(failure);}finally{leases.decrementAndGet();suspendIfIdle();}});}
        catch(RejectedExecutionException full){result.completeExceptionally(new IOException("Sync is busy"));}
        return result;
    }
    public CompletableFuture<PairingInvite> createInvite() {return submit(()->{requireKeyholder();PairingInvite invite=pairing(transport()).create(System.currentTimeMillis());kick();return invite;});}
    public CompletableFuture<String> localIdentity(){return submit(()->{requireKeyholder();return transport().localIdentity();});}
    public CompletableFuture<Void> acceptInvite(String link,String name) {return submit(()->{
        requireKeyholder();
        pairing(transport()).accept(link,name,System.currentTimeMillis());changed();return null;
    });}
    public CompletableFuture<Void> approve(String invitation,String candidate,String fingerprint) {return submit(()->{
        requireKeyholder();pairing(transport()).approve(invitation,candidate,fingerprint,System.currentTimeMillis());changed();return null;
    });}
    public CompletableFuture<String> endSession(RemoteStore.Peer peer,String expected){return submit(()->{requireKeyholder();String id=endCommands.queue(peer,expected,System.currentTimeMillis());changed();return id;});}
    public void cancel(String invitation) {
        requireKeyholder();store.cancelInvite(invitation);secrets.removePending(invitation);changed();
    }
    public void revoke(RemoteStore.Peer peer) {
        requireKeyholder();store.revoke(peer.identity,peer.generation);secrets.removePending(peer.generation);
        for(Map.Entry<PeerTransport.Connection,String> entry:connections.entrySet())if(key(peer).equals(entry.getValue()))timer.execute(()->close(entry.getKey()));
        changed();
    }
    public void changed(){nextAttempt.clear();RemoteScheduler.synchronize(context,needsBackground());if(needsBackground())RemoteScheduler.kick(context);kick();}
    public void kick(){try{timer.execute(()->{try{if(foreground)tick();}catch(RuntimeException ignored){android.util.Log.w("PeerSync","Sync attempt deferred");}});}catch(RejectedExecutionException ignored){}}
    public boolean isConnected(RemoteStore.Peer peer){return connections.containsValue(key(peer));}
    private static String key(RemoteStore.Peer peer){return peer.identity+":"+peer.generation;}
    private void requireKeyholder(){if(!ControllerPinManager.isSessionUnlocked() || !ControllerPinManager.hasCredentials(context))throw new SecurityException("Keyholder access required");}
    private PairingService pairing(PeerTransport transport){return new PairingService(store,secrets,transport,()->ControllerPinManager.isSessionUnlocked() && ControllerPinManager.hasCredentials(context),android.os.Build.MODEL);}

    private PeerTransport transport() throws Exception {
        if(!recovered)throw new IOException("Configuration recovery is pending");
        synchronized(endpointLock) {
            if(endpoint==null) {
                byte[] seed=secrets.identitySeed();
                try {
                    Set<String> relays=context.getSharedPreferences("subhub_peer_transport",0).getStringSet("relay_urls",null);
                    if(relays!=null && relays.size()>8)throw new IOException("Too many relay endpoints");
                    endpoint=IrohPeerTransport.open(context,seed,relays==null?null:relays.toArray(new String[0]),10_000);
                } finally{Arrays.fill(seed,(byte)0);}
                PeerTransport started=endpoint;acceptor.execute(()->accept(started));
            }
            return endpoint;
        }
    }
    private boolean needsBackground() {
        for(RemoteStore.Peer peer:store.peers(true))if(peer.state!=RemoteStore.State.REVOKED || !"true".equals(store.metadata(peer.identity,peer.generation,"revocation_delivered","")))return true;
        return false;
    }
    private void tick() {
        long now=SystemClock.elapsedRealtime();
        if(endpoint==null && (needsBackground() || !store.invitations(System.currentTimeMillis()).isEmpty()) && warming.compareAndSet(false,true)) {
            submit(()->{transport();return null;}).whenComplete((value,error)->warming.set(false));
        }
        for(RemoteStore.Peer peer:store.peers(true)) {
            if(peer.state==RemoteStore.State.PENDING && peer.localRole==RemoteProtocol.Role.DOM)continue;
            if(peer.state==RemoteStore.State.REVOKED && "true".equals(store.metadata(peer.identity,peer.generation,"revocation_delivered","")))continue;
            String key=key(peer);if(now<nextAttempt.getOrDefault(key,0L) || !busy.add(key))continue;
            schedulePeer(peer,System.nanoTime()+TimeUnit.SECONDS.toNanos(55));
        }
    }
    private Future<Boolean> schedulePeer(RemoteStore.Peer peer,long deadline) {
        String key=key(peer);
        FutureTask<Boolean> task=new FutureTask<Boolean>(()->runPeer(peer,deadline)) {
            private final java.util.concurrent.atomic.AtomicBoolean started=new java.util.concurrent.atomic.AtomicBoolean();
            private final java.util.concurrent.atomic.AtomicBoolean released=new java.util.concurrent.atomic.AtomicBoolean();
            private void release(){if(released.compareAndSet(false,true)){busy.remove(key);suspendIfIdle();}}
            @Override public void run(){started.set(true);try{super.run();}finally{release();}}
            @Override protected void done(){if(!started.get())release();}
        };
        try{sessions.execute(task);return task;}catch(RejectedExecutionException full){task.cancel(false);return null;}
    }
    private void track(PeerTransport.Connection connection,String key,long deadline) {
        connections.put(connection,key);connectionDeadlines.put(connection,deadline);
        try{timer.schedule(()->{if(connectionDeadlines.remove(connection,deadline))close(connection);},Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);}
        catch(RejectedExecutionException stopped){close(connection);}
    }
    private void untrack(PeerTransport.Connection connection){connections.remove(connection);connectionDeadlines.remove(connection);}
    private boolean runPeer(RemoteStore.Peer peer,long deadline) {
        String key=key(peer);boolean success=false;
        try {
            remaining(deadline);PeerTransport active=transport();PeerTransport.Connection connection;
            try{connection=active.connect(peer.identity,peer.route,Math.min(8_000,remaining(deadline)));}
            catch(IOException oldRoute){if(peer.route.isEmpty())throw oldRoute;connection=active.connect(peer.identity,"",Math.min(8_000,remaining(deadline)));}
            track(connection,key,deadline);
            try(PeerTransport.Connection selected=connection) {
                if(peer.state==RemoteStore.State.PENDING)success=pairing(active).resume(selected,peer);
                else if(peer.state==RemoteStore.State.REVOKED){session(active).sendRevocation(selected,peer,deadline);success=true;}
                else{session(active).outgoing(selected,peer,deadline);success=true;}
            } finally{untrack(connection);}
            if(success)store.putMetadata(peer.identity,peer.generation,"connection_error","");
        } catch(Exception unavailable){store.putMetadata(peer.identity,peer.generation,"connection_error","Unable to sync");}
        finally{nextAttempt.put(key,SystemClock.elapsedRealtime()+(success && peer.state!=RemoteStore.State.PENDING?30_000:2_000+ThreadLocalRandom.current().nextInt(4000)));}
        return success;
    }
    private void accept(PeerTransport active) {
        while(endpoint==active) {
            PeerTransport.Connection connection;
            try{connection=active.accept(2000);}catch(SocketTimeoutException waiting){continue;}
            catch(IOException unavailable){break;}
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(55);track(connection,"",deadline);
            try{sessions.execute(()->incoming(active,connection,deadline));}catch(RejectedExecutionException full){untrack(connection);close(connection);}
        }
    }
    private void incoming(PeerTransport active,PeerTransport.Connection connection,long deadline) {
        String reservation=null;
        try(PeerTransport.Connection selected=connection) {
            selected.setReadTimeoutMillis(8000);JSONObject first=RemoteProtocol.read(selected.input());
            String type=first.optString("type");
            if("pair_request".equals(type)){pairing(active).handle(selected,first,System.currentTimeMillis());RemoteScheduler.synchronize(context,needsBackground());return;}
            String generation=RemoteProtocol.identifier(first.getString("generation"));RemoteStore.Peer peer=store.peer(selected.authenticatedPeerIdentity(),generation);
            if(peer==null)throw new SecurityException("Unknown peer");
            reservation=key(peer);if(!busy.add(reservation)){reservation=null;return;}
            connections.put(selected,reservation);
            if("revoke".equals(type))session(active).receiveRevocation(selected,first,deadline);
            else session(active).incoming(selected,first,deadline);
            store.putMetadata(peer.identity,peer.generation,"connection_error","");
        }catch(Exception unavailable){android.util.Log.w("PeerSync","Peer session ended before completion");}
        finally{untrack(connection);if(reservation!=null)busy.remove(reservation);RemoteScheduler.synchronize(context,needsBackground());suspendIfIdle();}
    }
    private RemoteSession session(PeerTransport active) {
        return new RemoteSession(store,active,new RemoteSession.Hooks() {
            @Override public void prepare(RemoteStore.Peer peer) throws Exception {
                endCommands.process(peer,System.currentTimeMillis());
                if(peer.localRole==RemoteProtocol.Role.SUB) {
                    boolean available=true;try{telemetry.prepare(peer);store.putMetadata(peer.identity,peer.generation,"stats_error","");}
                    catch(Exception unavailable){available=false;store.putMetadata(peer.identity,peer.generation,"stats_error","Statistics could not be refreshed");}
                    for(RemoteProtocol.Stream stream:new RemoteProtocol.Stream[]{RemoteProtocol.Stream.STATS,RemoteProtocol.Stream.DAILY,RemoteProtocol.Stream.APP_USAGE,RemoteProtocol.Stream.APP_CATALOG,RemoteProtocol.Stream.RUNTIME})
                        store.putMetadata(peer.identity,peer.generation,"unavailable_"+stream.name(),Boolean.toString(!available));
                }
                try{contracts.prepare(peer);store.putMetadata(peer.identity,peer.generation,"unavailable_BASELINE","false");}
                catch(Exception unavailable){store.putMetadata(peer.identity,peer.generation,"unavailable_BASELINE","true");store.putMetadata(peer.identity,peer.generation,"contract_error","Contract data could not be refreshed");}
            }
            @Override public void received(RemoteStore.Peer peer,RemoteProtocol.Stream stream) throws Exception {endCommands.process(peer,System.currentTimeMillis());contracts.process(peer);}
            @Override public List<RemoteSession.AssetNeed> missingAssets(RemoteStore.Peer peer) throws Exception{return contracts.missing(peer);}
            @Override public RemoteAssets assets(RemoteStore.Peer peer) throws Exception{return contracts.assets(peer);}
            @Override public void complete(RemoteStore.Peer peer) throws Exception{contracts.process(peer);}
        });
    }
    public boolean backgroundWindow() throws Exception {return backgroundWindow(55_000);}
    boolean backgroundWindow(long durationMillis) throws Exception {
        if(!needsBackground())return true;leases.incrementAndGet();long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(durationMillis);boolean success=true;
        Map<Future<Boolean>,String> attempts=new LinkedHashMap<>();
        try {
            transport();
            for(RemoteStore.Peer peer:store.peers(true)) {
                if(peer.state==RemoteStore.State.PENDING && peer.localRole==RemoteProtocol.Role.DOM)continue;
                if(peer.state==RemoteStore.State.REVOKED && "true".equals(store.metadata(peer.identity,peer.generation,"revocation_delivered","")))continue;
                String key=key(peer);if(!busy.add(key))continue;
                Future<Boolean> attempt=schedulePeer(peer,deadline);if(attempt==null)success=false;else attempts.put(attempt,key);
            }
            if(attempts.isEmpty()) {
                while(System.nanoTime()<deadline && (!busy.isEmpty() || store.peers(false).stream().anyMatch(peer->peer.state==RemoteStore.State.PENDING)))Thread.sleep(Math.min(50,Math.max(1,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()))));
                success=busy.isEmpty() && store.peers(false).stream().noneMatch(peer->peer.state==RemoteStore.State.PENDING);
            }
            for(Future<Boolean> attempt:attempts.keySet()) {
                try{success=attempt.get(remaining(deadline),TimeUnit.MILLISECONDS)&&success;}
                catch(TimeoutException timeout){attempt.cancel(true);success=false;}
            }
            return success;
        } finally {
            for(Map.Entry<Future<Boolean>,String> attempt:attempts.entrySet()) {
                if(!attempt.getKey().isDone())attempt.getKey().cancel(true);

            }
            if(!foreground && leases.get()==1)for(PeerTransport.Connection connection:connections.keySet())close(connection);
            leases.decrementAndGet();suspendIfIdle();
        }
    }
    private void suspendIfIdle() {
        if(foreground || leases.get()>0 || !busy.isEmpty())return;
        synchronized(endpointLock){if(foreground || leases.get()>0 || !busy.isEmpty())return;PeerTransport previous=endpoint;endpoint=null;if(previous!=null)close(previous);}
    }
    void shutdownForTest() throws Exception {
        foreground=false;timer.shutdownNow();synchronized(endpointLock){PeerTransport previous=endpoint;endpoint=null;if(previous!=null)close(previous);}
        acceptor.shutdownNow();sessions.shutdownNow();sessions.awaitTermination(10,TimeUnit.SECONDS);acceptor.awaitTermination(10,TimeUnit.SECONDS);store.close();
    }
    private static long remaining(long deadline) throws SocketTimeoutException {long millis=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());if(millis<=0)throw new SocketTimeoutException("Sync window expired");return millis;}
    private static void close(AutoCloseable value){try{value.close();}catch(Exception ignored){}}
}
