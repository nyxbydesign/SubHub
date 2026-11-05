package com.subhub.app.remote;

import android.app.*;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.people.People;
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
    public final RemoteKeyholder keyholder;
    public final RemoteActivityRepository activity;
    final RemoteAppIconIntegration appIcons;
    private final RemoteSecrets secrets;
    private final PairingLifecycleClock.Source pairingClock;
    private final RemoteConfigurationWriter writer;
    private final RemoteTelemetry telemetry;
    interface BackgroundScheduling {
        void synchronize(Context context,boolean needed);
        void kick(Context context);
    }
    private final BackgroundScheduling backgroundScheduling;
    private final Object sessionControlLock = new Object();
    private RemoteSessionControlCoordinator sessionControlCoordinator;
    private String sessionControlIdentity = "";
    private final Object endpointLock=new Object();
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"subhub-peer-clock"));
    // This executor only reads immutable clock bounds and publishes attenuation; it never runs transport/SQL work.
    private final ScheduledExecutorService pairingDeadlineTimer=newPairingDeadlineTimer();
    private final ExecutorService acceptor=Executors.newSingleThreadExecutor(r->new Thread(r,"subhub-peer-accept"));
    // Keep four workers and twelve queued slots; old outgoing work cannot consume UI or inbound capacity.
    private final ThreadPoolExecutor operations=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->new Thread(r,"subhub-peer-operation"),new ThreadPoolExecutor.AbortPolicy());
    private final ThreadPoolExecutor incomingSessions=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),r->new Thread(r,"subhub-peer-incoming"),new ThreadPoolExecutor.AbortPolicy());
    private final ThreadPoolExecutor sessions=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new SynchronousQueue<>(),r->new Thread(r,"subhub-peer-session"),new ThreadPoolExecutor.AbortPolicy());
    private final Semaphore revocations=new Semaphore(1);
    private final Set<String> busy=ConcurrentHashMap.newKeySet();
    private final Map<String,Long> nextAttempt=new ConcurrentHashMap<>();
    private final Map<String,Integer> consecutiveFailures=new ConcurrentHashMap<>();
    private final Object attemptClockLock=new Object();
    private long dirtyGeneration;
    private final Map<PeerTransport.Connection,String> connections=new ConcurrentHashMap<>();
    private final Map<PeerTransport.Connection,Long> connectionDeadlines=new ConcurrentHashMap<>();
    private final AtomicInteger leases=new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean warming=new java.util.concurrent.atomic.AtomicBoolean();
    private volatile PeerTransport endpoint;
    private volatile long nextEndpointAttempt;
    private final AtomicInteger endpointFailures=new AtomicInteger();
    private volatile boolean foreground,recovered;
    private final java.util.concurrent.atomic.AtomicBoolean idleCheckPending=new java.util.concurrent.atomic.AtomicBoolean();
    private final Map<String,PairingOperationLease> pairingLeases=new ConcurrentHashMap<>();
    // Retain only the latest incarnation in each direction, including a deadline-closed owner.
    private final Map<PairingLifecycleClock.Direction,PairingOperationLease> pairingDeadlineOwners=new ConcurrentHashMap<>();
    private final Map<String,StartTicket> pairingTickets=new ConcurrentHashMap<>();
    private final Map<String,PairingServiceState> pairingPresentation=new ConcurrentHashMap<>();
    private final Map<PeerTransport.Connection,String> pairingConnections=new ConcurrentHashMap<>();
    private final Map<Future<?>,String> pairingTasks=new ConcurrentHashMap<>();
    private final Map<Future<?>,Long> pairingTaskDeadlines=new ConcurrentHashMap<>();
    private final AtomicInteger backgroundWindows=new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean pairingPoll=new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean pairingTimerInstalled;
    private boolean installed;
    private volatile boolean stopped;
    private Application lifecycleApplication;
    private Application.ActivityLifecycleCallbacks lifecycleCallbacks;
    private RemoteConnectivityWake connectivityWake;
    private final java.util.concurrent.atomic.AtomicBoolean automaticSyncPending = new java.util.concurrent.atomic.AtomicBoolean();
    private boolean librariesRetired;
    private final Object connectedServiceLock=new Object();
    private final Object backgroundPreferenceChangeLock=new Object();
    private volatile ConnectedServiceLease connectedServiceLease;
    private ScheduledFuture<?> connectedServiceWake;
    private long connectedServiceWakeAt;
    private final android.os.Handler serviceMain=new android.os.Handler(android.os.Looper.getMainLooper());

    RemoteFeature(Context context) {this(context,new RemoteStore(context));}
    RemoteFeature(Context context,RemoteStore store) {this(context,store,new RemoteSecrets(context.getApplicationContext()));}
    RemoteFeature(Context context,RemoteStore store,RemoteSecrets secrets) {
        this(context,store,secrets,PairingLifecycleClock.source(context.getApplicationContext()));
    }
    RemoteFeature(Context context,RemoteStore store,RemoteSecrets secrets,PairingLifecycleClock.Source pairingClock) {
        this(context,store,secrets,pairingClock,new BackgroundScheduling(){
            public void synchronize(Context selected,boolean needed){RemoteScheduler.synchronize(selected,needed);}
            public void kick(Context selected){RemoteScheduler.kick(selected);}
        });
    }
    RemoteFeature(Context context,RemoteStore store,RemoteSecrets secrets,PairingLifecycleClock.Source pairingClock,
            BackgroundScheduling backgroundScheduling) {
        this.backgroundScheduling=Objects.requireNonNull(backgroundScheduling);
        this.context=context.getApplicationContext();this.store=store;this.secrets=java.util.Objects.requireNonNull(secrets);
        this.pairingClock=java.util.Objects.requireNonNull(pairingClock);
        if (!Thread.holdsLock(store)) RemoteSessionClock.observeAtStartup(this.context);
        com.subhub.app.security.SubFeaturePolicy.registerHardcoreAuthority(this.context, store);
        keyholder=new RemoteKeyholder(this.context,store,secrets);
        appIcons=new RemoteAppIconIntegration(this.context,store,timer,this::kick);
        activity=new RemoteActivityRepository(this.context,store,this::changed);
        store.onActivityReceived(activity::received);
        store.onPairingApproval((approved,sampled)->{
            PairingOperationLease lease=pairingDeadlineOwners.get(approved.direction);
            return lease!=null&&lease.matches(approved)?lease.stageApproval(approved,sampled):()->{};
        });
        store.onRevoked((identity,generation)->{appIcons.revoked(identity,generation);cleanupRevokedSecrets(identity,generation);activity.revoked(store.peer(identity,generation));scheduleConnectedSync(0);});
        store.onActivated(peer->{if(peer.localRole==RemoteProtocol.Role.SUB)ControllerPinManager.lockNow();activity.refreshSource();requestConnectedServiceStart();scheduleConnectedSync(0);});
        endCommands=new RemoteEndCommands(this.context,store);
        writer=new RemoteConfigurationWriter(this.context,store);contracts=new RemoteContracts(this.context,store,writer);telemetry=new RemoteTelemetry(this.context,store);
        sessions.allowCoreThreadTimeOut(true);operations.allowCoreThreadTimeOut(true);incomingSessions.allowCoreThreadTimeOut(true);
        try{writer.recover();writer.prunePrepared();if(com.subhub.app.configuration.LegacyPackMigration.isReady(this.context)){store.retireLibraries();librariesRetired=true;}for(RemoteStore.Peer old:store.peers(true))if(old.state==RemoteStore.State.REVOKED){cleanupRevokedSecrets(old.identity,old.generation);}recovered=true;}catch(Exception unavailable){recovered=false;}
    }
    private void cleanupRevokedSecrets(String identity,String generation){
        store.authorityAccess(()->{
            keyholder.revoked(identity,generation);
            String expected=secrets.pendingLink(generation);
            if(store.ownsPendingSecret(identity,generation,expected))secrets.removePendingIfMatches(generation,expected);
            return null;
        });
    }
    public boolean recoveryReady(){return recovered&&com.subhub.app.configuration.LegacyPackMigration.isReady(context);}
    public boolean controlsLocal(){try{return !recoveryReady()||store.authorityObservation(store::isRemotelyControlled);}catch(RuntimeException unavailable){return true;}}

    public synchronized void installLifecycle(Application app) {
        if(installed||stopped)return;installed=true;
        timer.execute(()->{try{appIcons.refresh();}catch(Exception unavailable){/* Reconstruct from a later verified sync. */}});
        lifecycleApplication=app;
        lifecycleCallbacks=new Application.ActivityLifecycleCallbacks() {
            private int started;
            @Override public void onActivityStarted(Activity activity){synchronized(RemoteFeature.this){if(stopped)return;if(++started==1){foreground=true;RemoteFeature.this.activity.dispatchNotifications();requestConnectedServiceStart();requestAutomaticSync();}}}
            @Override public void onActivityStopped(Activity activity){synchronized(RemoteFeature.this){if(stopped)return;if(--started==0){foreground=false;requestAutomaticSync();timer.execute(RemoteFeature.this::suspendIfIdle);}}}
            @Override public void onActivityCreated(Activity a,Bundle b){} @Override public void onActivityDestroyed(Activity a){}
            @Override public void onActivityResumed(Activity a){} @Override public void onActivityPaused(Activity a){}
            @Override public void onActivitySaveInstanceState(Activity a,Bundle b){}
        };
        app.registerActivityLifecycleCallbacks(lifecycleCallbacks);
        timer.scheduleWithFixedDelay(()->{try{if(foreground)tick();}catch(RuntimeException unavailable){android.util.Log.w("PeerSync","Sync attempt deferred");}},2,2,TimeUnit.SECONDS);
        synchronizeBackground();
        try { connectivityWake=new RemoteConnectivityWake(app,this::requestAutomaticSync); }
        catch(RuntimeException unavailable){android.util.Log.w("PeerSync","Network observation unavailable");}
    }
    /** Coalesce lifecycle/network returns and preserve WorkManager's existing unique-work ownership. */
    void requestAutomaticSync() {
        if(stopped||!automaticSyncPending.compareAndSet(false,true))return;
        try{timer.execute(()->{
            automaticSyncPending.set(false);if(stopped)return;
            try{invalidateAttempts();if(!needsBackground())return;
                if(hasConnectedService())scheduleConnectedSync(0);else if(foreground)tick();else kickBackground();
            }catch(RuntimeException unavailable){android.util.Log.w("PeerSync","Sync attempt deferred");}
        });}catch(RejectedExecutionException closed){automaticSyncPending.set(false);}
    }
    /** Admission survives future cancellation until the actual resource-using callable exits. */
    private static class AdmittedTask<T> extends FutureTask<T> {
        private final java.util.concurrent.atomic.AtomicBoolean started=new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicBoolean released=new java.util.concurrent.atomic.AtomicBoolean();
        private final Runnable release;
        AdmittedTask(Callable<T> work,Runnable release){super(work);this.release=release;}
        private void release(){if(released.compareAndSet(false,true))release.run();}
        @Override public void run(){started.set(true);try{super.run();}finally{release();}}
        @Override protected void done(){if(!started.get())release();}
    }
    public <T> CompletableFuture<T> submit(Callable<T> work) {
        CompletableFuture<T> result=new CompletableFuture<>();
        leases.incrementAndGet();
        AdmittedTask<T> task=new AdmittedTask<T>(() -> store.authorityAccess(work::call),()->{leases.decrementAndGet();deferSuspendIfIdle();}) {
            @Override protected void done(){
                super.done();
                if(isCancelled())operations.remove(this);
                try{result.complete(get());}
                catch(CancellationException cancelled){result.cancel(false);}
                catch(ExecutionException failure){result.completeExceptionally(failure.getCause());}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();result.completeExceptionally(interrupted);}
            }
        };
        result.whenComplete((value,error)->{if(result.isCancelled())task.cancel(true);});
        try{operations.execute(task);}
        catch(RejectedExecutionException full){result.completeExceptionally(new IOException("Sync is busy"));task.cancel(false);}
        return result;
    }
    public enum PairingServiceState { STARTING, RUNNING, STOPPED, UNAVAILABLE }
    public static final class PairingOperation {
        public final String id,generation,direction,state;
        public final boolean asDom,live,active,clockCertain;
        public final long remainingMillis,deadlineWall;
        private PairingOperation(String id,String generation,boolean asDom,String state,boolean live,boolean active,
                boolean clockCertain,long remainingMillis,long deadlineWall){
            this.id=id;this.generation=generation;this.asDom=asDom;direction=asDom?"CREATE":"ACCEPT";this.state=state;
            this.live=live;this.active=active;this.clockCertain=clockCertain;this.remainingMillis=remainingMillis;this.deadlineWall=deadlineWall;
        }
    }
    public static final class PairingStart {
        public final PairingOperation operation;private final String value;
        private PairingStart(PairingOperation operation,String value){this.operation=operation;this.value=value;}
        String ticket(){return value;}
    }
    private static final class StartTicket {
        final PairingOperation operation;final String capability;final FormalRelationshipClock.Sample issued;
        PairingOperationLease acquired;
        StartTicket(PairingOperation operation,String capability,FormalRelationshipClock.Sample issued){this.operation=operation;this.capability=capability;this.issued=issued;}
    }
    private static PairingLifecycleClock.Direction pairingDirection(boolean asDom){return asDom?PairingLifecycleClock.Direction.CREATE:PairingLifecycleClock.Direction.ACCEPT;}
    private static String pairingKey(String id,String generation,boolean asDom){return (asDom?"CREATE:":"ACCEPT:")+RemoteProtocol.identifier(id)+":"+RemoteProtocol.identifier(generation);}
    private static String pairingKey(PairingOperation operation){return pairingKey(operation.id,operation.generation,operation.asDom);}
    private void requirePairingAccess(boolean asDom){if(asDom)requireDomWorkspace();else requireEnrollmentAccess();}
    private PairingOperation pairingSnapshot(String id,String generation,boolean asDom,FormalRelationshipClock.Sample observed){
        String key=pairingKey(id,generation,asDom);PairingLifecycleLedger.Operation operation=store.pairingOperation(id,generation,pairingDirection(asDom));
        RemoteStore.Peer peer=null;
        if(operation!=null&&operation.peer!=null)peer=store.authorityObservation(()->store.peer(operation.peer,generation));
        else for(RemoteStore.Peer selected:store.authorityObservation(()->store.peers(true)))
            if(selected.generation.equals(generation)&&selected.localRole==(asDom?RemoteProtocol.Role.DOM:RemoteProtocol.Role.SUB)){
                if(peer!=null)throw new SecurityException("Ambiguous pairing participant");peer=selected;
            }
        if(peer!=null&&peer.state==RemoteStore.State.ACTIVE){
            PairingOperationLease lease=pairingLeases.get(key);
            long original=Long.MAX_VALUE;boolean owned=false;
            for(Map.Entry<PeerTransport.Connection,String> entry:pairingConnections.entrySet())if(key.equals(entry.getValue())){
                owned=true;original=Math.min(original,connectionDeadlines.getOrDefault(entry.getKey(),System.nanoTime()));
            }
            for(Map.Entry<Future<?>,String> entry:pairingTasks.entrySet())if(key.equals(entry.getValue())&&!entry.getKey().isDone()){
                owned=true;original=Math.min(original,pairingTaskDeadlines.getOrDefault(entry.getKey(),System.nanoTime()));
            }
            boolean draining=lease!=null&&owned&&lease.activeDraining(original);
            return new PairingOperation(id,generation,asDom,"COMPLETE",draining,true,true,0,0);
        }
        if(peer!=null&&peer.state==RemoteStore.State.REVOKED)return new PairingOperation(id,generation,asDom,"CANCELLED",false,false,true,0,0);
        if(operation==null)return store.legacyPendingGeneration(pairingDirection(asDom))!=null
                &&generation.equals(store.legacyPendingGeneration(pairingDirection(asDom)))?new PairingOperation(id,generation,asDom,"UNAVAILABLE",false,false,false,0,0):null;
        FormalRelationshipClock.Sample now=pairingClock.transition(observed);
        PairingLifecycleClock.Verdict verdict=operation.verdict(now);
        long deadline=operation.approval==null?operation.bounds.publicWall:operation.approval.deadlineWall;
        return new PairingOperation(id,generation,asDom,operation.stage.name(),verdict==PairingLifecycleClock.Verdict.LIVE,false,
                verdict!=PairingLifecycleClock.Verdict.UNCERTAIN,operation.remaining(now),deadline);
    }
    public CompletableFuture<PairingOperation> currentPairingOperation(boolean asDom){return submit(()->{
        requirePairingAccess(asDom);FormalRelationshipClock.Sample observed=pairingClock.observe();observed.requireValid();
        PairingLifecycleLedger.Operation current=store.currentPairingOperation(pairingDirection(asDom));
        String generation=current==null?store.legacyPendingGeneration(pairingDirection(asDom)):current.generation;
        return generation==null?null:pairingSnapshot(current==null?generation:current.id,generation,asDom,observed);
    });}
    public CompletableFuture<PairingOperation> pairingStatus(String id,String generation,boolean asDom){return submit(()->{
        FormalRelationshipClock.Sample observed=pairingClock.observe();observed.requireValid();return pairingSnapshot(id,generation,asDom,observed);
    });}
    public CompletableFuture<PairingStart> preparePairingStart(String id,String generation,boolean asDom){return submit(()->{
        requirePairingAccess(asDom);FormalRelationshipClock.Sample observed=pairingClock.observe();observed.requireValid();
        PairingOperation operation=pairingSnapshot(id,generation,asDom,observed);
        if(operation==null||!operation.live||operation.active)throw new SecurityException("Original pairing lifetime unavailable");
        String key=pairingKey(operation);PairingOperationLease incumbent=pairingLeases.get(key);
        String capability=incumbent!=null&&!incumbent.isClosed()?incumbent.cancelCapability():PairingCompletionProof.freshNonce();
        for(Map.Entry<String,StartTicket> entry:pairingTickets.entrySet()){
            StartTicket ticket=entry.getValue();
            if(!PairingLifecycleClock.freshProof(ticket.issued,pairingClock.transition(observed)))pairingTickets.remove(entry.getKey(),ticket);
            else if(pairingKey(ticket.operation).equals(key)
                    &&(ticket.acquired==null||!ticket.acquired.isClosed()))return new PairingStart(operation,entry.getKey());
        }
        if(pairingTickets.size()>=16)throw new SecurityException("Pairing start capacity reached");
        store.authorizePairingStart(id,generation,pairingDirection(asDom),capabilityHash(capability),observed,pairingClock);
        String value=PairingCompletionProof.freshNonce();pairingTickets.put(value,new StartTicket(operation,capability,observed));
        reportPairingServiceState(operation,PairingServiceState.STARTING);return new PairingStart(operation,value);
    });}
    public CompletableFuture<PairingOperationLease> acquirePairingLease(String value){return submit(()->{
        if(value==null||!value.matches("[0-9a-f]{64}"))throw new SecurityException("Pairing start unavailable");
        StartTicket ticket=pairingTickets.get(value);FormalRelationshipClock.Sample observed=pairingClock.observe();observed.requireValid();
        if(ticket==null||!PairingLifecycleClock.freshProof(ticket.issued,pairingClock.transition(observed)))throw new SecurityException("Pairing start expired");
        PairingOperation operation=pairingSnapshot(ticket.operation.id,ticket.operation.generation,ticket.operation.asDom,observed);
        if(operation==null)throw new SecurityException("Pairing start unavailable");
        PairingLifecycleLedger.Operation ledger=store.pairingOperation(operation.id,operation.generation,pairingDirection(operation.asDom));
        if(ticket.acquired!=null){
            if(ticket.acquired.isClosed()||!operation.live||ledger==null||!capabilityHash(ticket.capability).equals(ledger.cancelHash)){
                ticket.acquired.close();throw new SecurityException("Pairing start already consumed or changed");
            }
            ticket.acquired.update(operation);return ticket.acquired;
        }
        if(!operation.live||operation.active||ledger==null||!capabilityHash(ticket.capability).equals(ledger.cancelHash))throw new SecurityException("Pairing start changed");
        String key=pairingKey(operation);PairingOperationLease incumbent=pairingLeases.get(key);
        if(incumbent!=null&&!incumbent.isClosed()){ticket.acquired=incumbent;return incumbent;}
        if(pairingLeases.size()>=2)throw new SecurityException("Pairing listener capacity reached");
        PairingOperationLease lease=new PairingOperationLease(operation,ticket.capability,ledger,observed,pairingClock,closed->releasePairingLease(key,closed));
        ticket.acquired=lease;pairingLeases.put(key,lease);pairingDeadlineOwners.put(pairingDirection(operation.asDom),lease);
        try{transport();installPairingTimer();requestPairingPoll();return lease;}
        catch(Exception unavailable){lease.close();throw unavailable;}
    });}
    private static String capabilityHash(String capability){
        if(capability==null||!capability.matches("[0-9a-f]{64}"))throw new SecurityException("Pairing cancellation unavailable");
        return LocalRelationshipAuthority.digest("pairing-cancel-v1:"+capability);
    }
    public CompletableFuture<Boolean> cancelPairingOperation(String id,String generation,boolean asDom,String capability){return submit(()->{
        FormalRelationshipClock.Sample observed=pairingClock.observe();observed.requireValid();
        PairingOperation selected=pairingSnapshot(id,generation,asDom,observed);
        PairingLifecycleLedger.Operation current=store.currentPairingOperation(pairingDirection(asDom));
        if(selected==null||selected.active||"CANCELLED".equals(selected.state)||"COMPLETE".equals(selected.state)
                ||current!=null&&(!id.equals(current.id)||!generation.equals(current.generation)))return false;
        if(capability==null){
            try{requirePairingAccess(asDom);}
            catch(SecurityException changed){
                PairingOperation latest=pairingSnapshot(id,generation,asDom,observed);
                if(latest!=null&&latest.active)return false;throw changed;
            }
        }
        String hash=capability==null?null:capabilityHash(capability);
        boolean cancelled=store.pairingOperation(()->{
            String expected=secrets.pendingLink(generation);
            boolean changed=store.cancelPairingOperation(id,generation,pairingDirection(asDom),hash,observed,pairingClock);
            if(changed)secrets.removePendingIfMatches(generation,expected);return changed;
        });
        if(cancelled){PairingOperationLease lease=pairingLeases.get(pairingKey(id,generation,asDom));if(lease!=null)lease.close();changed();}
        return cancelled;
    });}
    public void reportPairingServiceState(PairingOperation operation,PairingServiceState state){
        Objects.requireNonNull(operation);Objects.requireNonNull(state);String key=pairingKey(operation);
        if(pairingPresentation.size()<128||pairingPresentation.containsKey(key))pairingPresentation.put(key,state);
    }
    public PairingServiceState pairingServiceState(String id,String generation,boolean asDom){return pairingPresentation.getOrDefault(pairingKey(id,generation,asDom),PairingServiceState.STOPPED);}
    private static ScheduledExecutorService newPairingDeadlineTimer(){
        return Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"subhub-pairing-deadline"));
    }
    private static void startPairingDeadlineChecks(ScheduledExecutorService deadlineOnly,Collection<PairingOperationLease> leases){
        deadlineOnly.scheduleWithFixedDelay(()->{for(PairingOperationLease lease:leases)lease.checkDeadline();},0,2,TimeUnit.SECONDS);
    }
    private synchronized void installPairingTimer(){
        if(pairingTimerInstalled)return;pairingTimerInstalled=true;
        startPairingDeadlineChecks(pairingDeadlineTimer,pairingLeases.values());
        timer.scheduleWithFixedDelay(this::requestPairingPoll,0,2,TimeUnit.SECONDS);
    }
    private void requestPairingPoll(){
        if(pairingLeases.isEmpty()||!pairingPoll.compareAndSet(false,true))return;
        submit(()->{pollPairingLeases();return null;}).whenComplete((ignored,failure)->{
            pairingPoll.set(false);
            if(failure!=null)for(PairingOperationLease lease:pairingLeases.values())lease.close();
        });
    }
    private void pollPairingLeases()throws Exception {
        FormalRelationshipClock.Sample observed=pairingClock.observe();observed.requireValid();
        for(Map.Entry<String,PairingOperationLease> entry:pairingLeases.entrySet()){
            PairingOperationLease lease=entry.getValue();if(lease.isClosed())continue;
            PairingOperation previous=lease.operation(),operation=pairingSnapshot(previous.id,previous.generation,previous.asDom,observed);
            if(operation==null){lease.close();continue;}lease.update(operation);
            if(!operation.live){
                if(!operation.active&&operation.clockCertain)store.expirePairingOperation(operation.id,operation.generation,pairingDirection(operation.asDom),observed,pairingClock);
                lease.close();continue;
            }
            if(operation.active)continue; // Existing committed ready/proof drains keep only their original 55-second deadline.
            PairingLifecycleLedger.Operation bounds=store.pairingOperation(operation.id,operation.generation,pairingDirection(operation.asDom));
            if(bounds==null){lease.close();continue;}lease.observeApproval(bounds,observed);
            transport();
            if(!operation.asDom){
                PairingLifecycleLedger.Operation ledger=store.pairingOperation(operation.id,operation.generation,PairingLifecycleClock.Direction.ACCEPT);
                RemoteStore.Peer peer=ledger==null?null:store.peer(ledger.peer,ledger.generation);
                long now=SystemClock.elapsedRealtime();
                if(peer!=null&&peer.state==RemoteStore.State.PENDING&&now>=nextAttempt.getOrDefault(key(peer),0L)&&busy.add(key(peer)))
                    schedulePeer(peer,System.nanoTime()+TimeUnit.SECONDS.toNanos(55),entry.getKey());
            }
        }
    }
    private boolean hasPairingLease(){for(PairingOperationLease lease:pairingLeases.values())if(!lease.isClosed())return true;return false;}
    private void releasePairingLease(String key,PairingOperationLease closed){
        pairingLeases.remove(key,closed);
        try{operations.execute(()->{
            if(pairingLeases.containsKey(key))return; // A new incarnation already owns this operation.
            // Serialize against activation: stopping an old pending owner cannot interrupt an ACTIVE ready drain.
            try{store.pairingOperation(()->{
                PairingOperationLease successor=pairingLeases.get(key);if(successor!=null&&!successor.isClosed())return null;
                String[] parts=key.split(":",3);PairingLifecycleLedger.Operation operation=store.pairingOperation(parts[1],parts[2],PairingLifecycleClock.Direction.valueOf(parts[0]));
                RemoteStore.Peer peer=operation==null||operation.peer==null?null:store.peer(operation.peer,operation.generation);
                if(peer==null||peer.state!=RemoteStore.State.ACTIVE){
                    for(Map.Entry<Future<?>,String> entry:pairingTasks.entrySet())if(key.equals(entry.getValue()))entry.getKey().cancel(true);
                    for(Map.Entry<PeerTransport.Connection,String> entry:pairingConnections.entrySet())if(key.equals(entry.getValue()))close(entry.getKey());
                }return null;
            });}catch(RuntimeException unavailable){/* Original connection deadlines remain the conservative bound. */}
            suspendIfIdle();
        });}catch(RejectedExecutionException stopped){/* Shutdown owns the endpoint and connections. */}
    }

    PairingInvite restoreInvitation(String id)throws Exception {
        requireDomWorkspace();FormalRelationshipClock.Sample observed=pairingClock.observe();observed.requireValid();
        PairingLifecycleLedger.Operation operation=store.currentPairingOperation(PairingLifecycleClock.Direction.CREATE);
        if(operation==null||!operation.id.equals(id)||operation.stage!=PairingLifecycleLedger.Stage.PENDING
                ||operation.verdict(pairingClock.transition(observed))!=PairingLifecycleClock.Verdict.LIVE)return null;
        String saved=secrets.pendingLink(id);if(saved==null)return null;PairingInvite invite=PairingInvite.restore(saved);
        operation.requireToken(invite.tokenHash());
        if(!invite.id.equals(operation.id)||invite.expiresAt!=operation.bounds.publicWall)throw new SecurityException("Original invitation differs");return invite;
    }
    public CompletableFuture<PairingInvite> ensureInvitation(){long revision=ControllerPinManager.domWorkspaceRevision();return submit(()->{
        java.util.List<String> retired=new java.util.ArrayList<>();
        try{
            requireDomWorkspace();if(revision!=ControllerPinManager.domWorkspaceRevision())throw new SecurityException("Dom workspace changed");
            PairingInvite invite=pairing(transport(),revision).ensureInvitation(retired::addAll);kick();changed();return invite;
        }catch(Exception unavailable){logSessionFailure("ensure-invitation",unavailable);throw unavailable;}
        finally{
            // Only a returned, committed retirement can release an old listener or its exact pending work.
            for(String generation:retired){PairingOperationLease lease=pairingLeases.get(pairingKey(generation,generation,true));if(lease!=null)lease.close();}
        }
    });}
    public CompletableFuture<PairingInvite> createInvite() {return submit(()->{if(!com.subhub.app.security.DeviceRolePreferences.supportsDom(context))throw new SecurityException("Dom capability unavailable");requireDomWorkspace();PairingInvite invite=pairing(transport()).create(System.currentTimeMillis());kick();return invite;});}
    public CompletableFuture<String> localIdentity(){return submit(()->{if(ControllerPinManager.isLocalDomWorkspaceUnlocked())requireDomWorkspace();else requireKeyholder();return transport().localIdentity();});}
    public static final class Enrollment {
        private final RemoteFeature owner;private final PairingInvite invitation;private final long expires;
        private final java.util.concurrent.atomic.AtomicBoolean used=new java.util.concurrent.atomic.AtomicBoolean();
        public final String domIdentity,localIdentity,fingerprint;
        private Enrollment(RemoteFeature owner,PairingInvite invite,String identity){this.owner=owner;invitation=invite;domIdentity=invite.domIdentity;localIdentity=identity;fingerprint=PairingInvite.fingerprint(domIdentity,identity,invite.id);expires=SystemClock.elapsedRealtime()+120_000;}
    }
    private void requireEnrollmentAccess(){
        if(!com.subhub.app.security.DeviceRolePreferences.supportsSub(context))throw new SecurityException("Sub capability unavailable");
        if(controlsLocal())throw new SecurityException("A Dom is already assigned or recovery is unavailable");
        boolean credentials=ControllerPinManager.isConfigured(context)||new com.subhub.app.security.ControllerAuthenticator(context).isPaired();
        if(credentials&&!ControllerPinManager.isSessionUnlocked())throw new SecurityException("Existing keyholder authentication required");
    }
    public CompletableFuture<Enrollment> prepareEnrollment(String code){return submit(()->{
        requireEnrollmentAccess();PairingInvite invite=PairingInvite.parse(code,System.currentTimeMillis());String identity=transport().localIdentity();
        if(identity.equals(invite.domIdentity))throw new SecurityException("Cannot pair with this device");return new Enrollment(this,invite,identity);
    });}
    public CompletableFuture<Void> confirmEnrollment(Enrollment enrollment){return submit(()->{
        requireEnrollmentAccess();
        if(enrollment==null||enrollment.owner!=this||SystemClock.elapsedRealtime()>enrollment.expires||!enrollment.used.compareAndSet(false,true))throw new SecurityException("Pairing confirmation expired");
        PairingInvite invite=PairingInvite.parse(enrollment.invitation.code(),System.currentTimeMillis());
        if(!transport().localIdentity().equals(enrollment.localIdentity)||!PairingInvite.fingerprint(invite.domIdentity,enrollment.localIdentity,invite.id).equals(enrollment.fingerprint))throw new SecurityException("Pairing identity changed");
        pairing(transport()).acceptConfirmed(invite,"Dom device");changed();return null;
    });}
    public void cancelEnrollment(RemoteStore.Peer peer){
        requireEnrollmentAccess();RemoteStore.Peer current=store.peer(peer.identity,peer.generation);
        if(current==null||current.localRole!=RemoteProtocol.Role.SUB||current.state!=RemoteStore.State.PENDING)throw new SecurityException("Enrollment is not pending");
        String expected=store.authorityAccess(()->secrets.pendingLink(current.generation));
        FormalRelationshipClock.Sample observed=pairingClock.observe();
        if(!store.cancelPairingOperation(current.generation,current.generation,PairingLifecycleClock.Direction.ACCEPT,null,observed,pairingClock))throw new SecurityException("Enrollment is no longer pending");
        store.authorityAccess(()->{secrets.removePendingIfMatches(current.generation,expected);return null;});
        changed();
    }
    public CompletableFuture<Void> acceptInvite(String link,String name) {return submit(()->{
        if(!com.subhub.app.security.DeviceRolePreferences.supportsSub(context))throw new SecurityException("Sub capability unavailable");
        requireKeyholder();
        pairing(transport()).accept(link,name,System.currentTimeMillis());changed();return null;
    });}
    public CompletableFuture<Void> approve(String invitation,String candidate,String fingerprint) {return submit(()->{
        if(!com.subhub.app.security.DeviceRolePreferences.supportsDom(context))throw new SecurityException("Dom capability unavailable");
        requireDomWorkspace();pairing(transport()).approve(invitation,candidate,fingerprint,System.currentTimeMillis());changed();return null;
    });}
    public CompletableFuture<String> endSession(RemoteStore.Peer peer,String expected){return submit(()->{requireDomWorkspace();String id=endCommands.queue(peer,expected,System.currentTimeMillis());changed();return id;});}
    public CompletableFuture<RemoteSessionRuntime.Snapshot> sessionSnapshot(RemoteStore.Peer peer) {
        return submit(() -> {
            requireDomWorkspace();
            RemoteStore.Peer current = store.peer(peer.identity, peer.generation);
            if (current == null || current.state != RemoteStore.State.ACTIVE || current.localRole != RemoteProtocol.Role.DOM)
                throw new SecurityException("Current Sub relationship required");
            if (!RemoteSessionRuntime.applicationEnabled()) return RemoteSessionRuntime.Snapshot.unsupported(current);
            return controlCoordinator(transport()).snapshot(current);
        });
    }
    public CompletableFuture<RemoteSessionRuntime.Review> reviewSession(RemoteStore.Peer peer,
            RemoteSessionCommands.Action action) {
        return submit(() -> {
            requireDomWorkspace();
            if (!RemoteSessionRuntime.applicationEnabled()) throw new UnsupportedOperationException("Session control unavailable");
            return controlCoordinator(transport()).review(peer, action);
        });
    }
    public CompletableFuture<String> confirmSession(RemoteSessionRuntime.Review review) {
        return submit(() -> {
            requireDomWorkspace();
            if (!RemoteSessionRuntime.applicationEnabled()) throw new UnsupportedOperationException("Session control unavailable");
            String id = controlCoordinator(transport()).confirm(review); changed(); return id;
        });
    }
    public CompletableFuture<RemoteSessionRuntime.HardcoreReview> reviewHardcoreStart() {
        return submit(() -> {
            if (!recoveryReady()) throw new IllegalStateException("Relationship recovery unavailable");
            if (!com.subhub.app.security.DeviceRolePreferences.supportsSub(context)
                    || com.subhub.app.security.DeviceRolePreferences.isDomWorkspace(context))
                throw new SecurityException("Local Sub review required");
            if (RemoteSessionRuntime.assignedDom(store) == null) return null;
            String self = transport().localIdentity();
            return RemoteSessionRuntime.reviewHardcore(context, store, self, RemoteSessionClock.observe(context));
        });
    }
    public CompletableFuture<Boolean> acceptHardcoreStartReview(RemoteSessionRuntime.HardcoreReview review) {
        return submit(() -> {
            java.util.Objects.requireNonNull(review);
            if (!recoveryReady()) throw new IllegalStateException("Relationship recovery unavailable");
            String self = transport().localIdentity();
            boolean admin = new com.subhub.app.security.HardcoreModeManager(context).isAdminActive();
            RemoteSessionRuntime.SystemObservation observed = new RemoteSessionRuntime.SystemObservation(self, admin,
                    RemoteSessionClock.observe(context));
            return com.subhub.app.security.SubFeaturePolicy.acceptHardcoreStartReview(context, store, review, observed);
        });
    }
    public void cancel(String invitation) {
        requireDomWorkspace();pairingServiceCancel(invitation);changed();
    }
    private void pairingServiceCancel(String invitation){
        FormalRelationshipClock.Sample observed=pairingClock.observe();
        store.pairingOperation(()->{String expected=secrets.pendingLink(invitation);
            if(store.cancelPairingOperation(invitation,invitation,PairingLifecycleClock.Direction.CREATE,null,observed,pairingClock))
                secrets.removePendingIfMatches(invitation,expected);return null;});
        PairingOperationLease lease=pairingLeases.get(pairingKey(invitation,invitation,true));if(lease!=null)lease.close();
    }
    public void revoke(RemoteStore.Peer peer) {
        RemoteStore.Peer selected=store.authorityObservation(()->store.peer(peer.identity,peer.generation));
        if(selected==null||selected.localRole!=peer.localRole)throw new SecurityException("Relationship changed");
        if(selected.localRole==RemoteProtocol.Role.DOM)requireDomWorkspace();else requireKeyholder();
        if(selected.state==RemoteStore.State.PENDING){
            FormalRelationshipClock.Sample observed=pairingClock.observe();
            store.cancelPairingOperation(selected.generation,selected.generation,pairingDirection(selected.localRole==RemoteProtocol.Role.DOM),null,observed,pairingClock);
            PairingOperationLease lease=pairingLeases.get(pairingKey(selected.generation,selected.generation,selected.localRole==RemoteProtocol.Role.DOM));if(lease!=null)lease.close();changed();return;
        }
        long revision=ControllerPinManager.domWorkspaceRevision();
        store.authorityAccess(()->{
            RemoteStore.Peer current=store.peer(selected.identity,selected.generation);
            if(current==null||current.localRole!=selected.localRole||revision!=ControllerPinManager.domWorkspaceRevision())throw new SecurityException("Relationship changed");
            if(current.localRole==RemoteProtocol.Role.DOM)requireDomWorkspace();else requireKeyholder();
            store.revoke(current.identity,current.generation);return null;
        });
        for(Map.Entry<PeerTransport.Connection,String> entry:connections.entrySet())if(key(peer).equals(entry.getValue()))timer.execute(()->close(entry.getKey()));
        changed();
    }
    private void invalidateAttempts(){synchronized(attemptClockLock){dirtyGeneration++;nextAttempt.clear();consecutiveFailures.clear();}}
    private long attemptGeneration(){synchronized(attemptClockLock){return dirtyGeneration;}}
    private void finishAttempt(String key,long generation,boolean success,boolean pending){
        synchronized(attemptClockLock){
            // A mutation during this exchange still needs a follow-up, even when its old snapshot completed.
            if(generation!=dirtyGeneration){nextAttempt.remove(key);return;}
            int failures=success?0:Math.min(6,consecutiveFailures.getOrDefault(key,0)+1);
            if(success)consecutiveFailures.remove(key);else consecutiveFailures.put(key,failures);
            long delay=success&&!pending?(hasConnectedService()?60_000:30_000):success?2_000:Math.min(60_000,2_000L<<(failures-1));
            if(!success)delay+=ThreadLocalRandom.current().nextInt(1000);
            nextAttempt.put(key,SystemClock.elapsedRealtime()+delay);
        }
    }
    public void changed(){invalidateAttempts();synchronizeBackground();if(needsBackground()&&!hasConnectedService())kickBackground();kick();}
    private void synchronizeBackground(){backgroundScheduling.synchronize(context,BackgroundSyncPreferences.enabled(context)&&needsBackground());}
    private void kickBackground(){if(BackgroundSyncPreferences.enabled(context))backgroundScheduling.kick(context);}
    /** Save a local choice before retiring only the persistent/background synchronization owners. */
    public boolean setBackgroundSyncEnabled(boolean enabled){
        synchronized(backgroundPreferenceChangeLock){
            boolean saved=BackgroundSyncPreferences.set(context,enabled);
            // A failed disk commit can still publish the new in-memory preference. Runtime follows that observation.
            if(!BackgroundSyncPreferences.enabled(context)){
                synchronized(connectedServiceLock){
                    ConnectedServiceLease selected=connectedServiceLease;if(selected!=null)selected.close();
                }
                context.stopService(new android.content.Intent(context,PeerSyncForegroundService.class));
            }
            synchronizeBackground();
            if(BackgroundSyncPreferences.enabled(context)){requestConnectedServiceStart();requestAutomaticSync();}
            else deferSuspendIfIdle();
            return saved;
        }
    }
    public void kick(){if(hasConnectedService()){scheduleConnectedSync(0);return;}try{timer.execute(()->{try{if(foreground)tick();}catch(RuntimeException ignored){android.util.Log.w("PeerSync","Sync attempt deferred");}});}catch(RejectedExecutionException ignored){}}
    public boolean isConnected(RemoteStore.Peer peer){return connections.containsValue(key(peer));}
    private static String key(RemoteStore.Peer peer){return peer.identity+":"+peer.generation;}
    private void requireKeyholder(){store.authorityAccess(()->{if(!ControllerPinManager.isSessionUnlocked()||!ControllerPinManager.hasCredentials(context))throw new SecurityException("Keyholder access required");return null;});}
    /** Outgoing Dom authority is independent of the incoming Sub's controller availability. */
    void requireDomWorkspace(){
        long revision=ControllerPinManager.domWorkspaceRevision();
        boolean local=ControllerPinManager.isLocalDomWorkspaceUnlocked();
        com.subhub.app.security.KeyholderProvider provider=People.keyholder();
        com.subhub.app.security.KeyholderProvider.State state=local?null:provider.state();
        store.authorityAccess(()->{
            if(revision!=ControllerPinManager.domWorkspaceRevision()||provider!=People.keyholder()
                    ||local!=ControllerPinManager.isLocalDomWorkspaceUnlocked()
                    ||!com.subhub.app.security.DeviceRolePreferences.supportsDom(context)
                    ||!ControllerPinManager.isDomWorkspaceUnlocked())throw new SecurityException("Dom workspace changed");
            if(local){if(!ControllerPinManager.isConfigured(context))throw new SecurityException("Local Dom credentials required");}
            else if(!ControllerPinManager.isSessionUnlocked()
                    ||state!=com.subhub.app.security.KeyholderProvider.State.NONE&&state!=com.subhub.app.security.KeyholderProvider.State.READY
                    ||!ControllerPinManager.hasCredentials(context))throw new SecurityException("Keyholder access required");
            return null;
        });
    }
    private PairingService pairing(PeerTransport transport){return pairing(transport,-1);}
    private PairingService pairing(PeerTransport transport,long revision){return new PairingService(store,secrets,transport,keyholder,
            ()->ControllerPinManager.isSessionUnlocked() && ControllerPinManager.hasCredentials(context),
            ()->{requireDomWorkspace();if(revision>=0&&revision!=ControllerPinManager.domWorkspaceRevision())throw new SecurityException("Dom workspace changed");return true;},android.os.Build.MODEL,pairingClock);}

    private PeerTransport transport() throws Exception {
        if(!recoveryReady())throw new IOException("Configuration recovery is pending");
        synchronized(endpointLock) {
            if(!librariesRetired){store.retireLibraries();librariesRetired=true;}
            if(endpoint==null) {
                if(SystemClock.elapsedRealtime()<nextEndpointAttempt)throw new IOException("Endpoint reconnect deferred");
                byte[] seed=secrets.identitySeed();
                try {
                    Set<String> relays=context.getSharedPreferences("subhub_peer_transport",0).getStringSet("relay_urls",null);
                    if(relays!=null && relays.size()>8)throw new IOException("Too many relay endpoints");
                    endpoint=IrohPeerTransport.open(context,seed,relays==null?null:relays.toArray(new String[0]),10_000);
                } catch(Exception unavailable){deferEndpointAttempt();throw unavailable;} finally{Arrays.fill(seed,(byte)0);}
                PeerTransport started=endpoint;acceptor.execute(()->accept(started));
            }
            return endpoint;
        }
    }
    private boolean needsBackground() {
        if(!recoveryReady())return false;
        for(RemoteStore.Peer peer:store.peers(true))if(peer.state!=RemoteStore.State.REVOKED || !"true".equals(store.metadata(peer.identity,peer.generation,"revocation_delivered","")))return true;
        return liveInvitation();
    }
    private boolean liveInvitation(){
        FormalRelationshipClock.Sample observed=pairingClock.observe();observed.requireValid();
        PairingLifecycleLedger.Operation current=store.currentPairingOperation(PairingLifecycleClock.Direction.CREATE);
        return current!=null&&current.verdict(pairingClock.transition(observed))==PairingLifecycleClock.Verdict.LIVE;
    }
    private void tick() {
        long now=SystemClock.elapsedRealtime();
        if(endpoint==null && now>=nextEndpointAttempt && needsBackground() && warming.compareAndSet(false,true)) {
            warmTransport();
        }
        for(RemoteStore.Peer peer:outgoingPeers()) {
            if(peer.state==RemoteStore.State.PENDING && peer.localRole==RemoteProtocol.Role.DOM)continue;
            if(peer.state==RemoteStore.State.REVOKED && "true".equals(store.metadata(peer.identity,peer.generation,"revocation_delivered","")))continue;
            String key=key(peer);if(now<nextAttempt.getOrDefault(key,0L) || !busy.add(key))continue;
            schedulePeer(peer,System.nanoTime()+TimeUnit.SECONDS.toNanos(55));
        }
    }
    private List<RemoteStore.Peer> outgoingPeers() {
        List<RemoteStore.Peer> peers=new ArrayList<>(store.peers(true));
        // Offer retained delivery first; its single permit leaves the other worker for a current peer.
        peers.sort(Comparator.comparingInt((RemoteStore.Peer peer)->peer.state==RemoteStore.State.REVOKED?0:1)
                .thenComparingLong(peer->nextAttempt.getOrDefault(key(peer),0L)));
        return peers;
    }
    private Future<Boolean> schedulePeer(RemoteStore.Peer peer,long deadline){return schedulePeer(peer,deadline,null);}
    private Future<Boolean> schedulePeer(RemoteStore.Peer peer,long deadline,String pairingOwner) {
        String key=key(peer);boolean revoked=peer.state==RemoteStore.State.REVOKED;
        long generation=attemptGeneration();
        if(revoked&&!revocations.tryAcquire()){deferAdmission(key,generation);busy.remove(key);return null;}
        AdmittedTask<Boolean> task=new AdmittedTask<>(()->runPeer(peer,deadline,pairingOwner,generation),()->{
            if(revoked)revocations.release();busy.remove(key);scheduleConnectedSync(0);
            for(Future<?> finished:pairingTasks.keySet())if(finished.isDone()){pairingTasks.remove(finished);pairingTaskDeadlines.remove(finished);}suspendIfIdle();
        });
        if(pairingOwner!=null){pairingTasks.put(task,pairingOwner);pairingTaskDeadlines.put(task,deadline);}
        try{sessions.execute(task);return task;}catch(RejectedExecutionException full){deferAdmission(key,generation);task.cancel(false);return null;}
    }
    private void deferAdmission(String key,long generation){
        synchronized(attemptClockLock){if(generation==dirtyGeneration)nextAttempt.putIfAbsent(key,SystemClock.elapsedRealtime()+2000);}
    }
    /** Exception messages and causes may contain private wire data; record only bounded code locations. */
    private static void logSessionFailure(String direction,Exception failure) {
        try {
            android.util.Log.w("PeerSync",sessionFailureSummary(direction,failure));
        } catch(RuntimeException ignored) { /* Diagnostics never replace the existing failure path. */ }
    }
    /** Code locations and numeric errno only: exception messages can contain peer data or secrets. */
    static String sessionFailureSummary(String direction,Throwable failure) {
        StringBuilder value=new StringBuilder("Session failure ").append(boundedCodeName(direction));
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        Throwable current=failure;int causes=0;
        while(current!=null&&causes<4) {
            if(!seen.add(current)){value.append(" cause-cycle");break;}
            value.append(causes++==0?" ":" caused-by ").append(boundedCodeName(current.getClass().getName()));
            if(current instanceof android.system.ErrnoException)
                value.append(" errno=").append(((android.system.ErrnoException)current).errno);
            int frames=0,scanned=0;
            for(StackTraceElement frame:current.getStackTrace()) {
                if(++scanned>64)break;
                if(!frame.getClassName().startsWith("com.subhub.app."))continue;
                value.append(" at ").append(boundedCodeName(frame.getClassName())).append('.')
                        .append(boundedCodeName(frame.getMethodName())).append(':').append(frame.getLineNumber());
                if(++frames==6)break;
            }
            if(value.length()>=4000)return value.substring(0,4000)+" truncated";
            current=current.getCause();
        }
        if(current!=null&&causes==4)value.append(" cause-truncated");
        return value.length()>4096?value.substring(0,4096):value.toString();
    }
    private static String boundedCodeName(String name) {
        return name==null?"unknown":name.substring(0,Math.min(160,name.length()));
    }
    private void track(PeerTransport.Connection connection,String key,long deadline) {
        connections.put(connection,key);connectionDeadlines.put(connection,deadline);
        try{timer.schedule(()->{if(connectionDeadlines.remove(connection,deadline))close(connection);},Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);}
        catch(RejectedExecutionException stopped){close(connection);}
    }
    private void untrack(PeerTransport.Connection connection){connections.remove(connection);connectionDeadlines.remove(connection);pairingConnections.remove(connection);}
    private boolean runPeer(RemoteStore.Peer peer,long deadline,String pairingOwner,long generation) {
        String key=key(peer);boolean success=false;
        try (NetworkWake wake=hasConnectedService()?new NetworkWake(context,deadline):null) {
            RemoteStore.Peer requested=peer;
            peer=store.authorityAccess(()->{RemoteStore.Peer current=store.peer(requested.identity,requested.generation);if(current==null||current.localRole!=requested.localRole)throw new SecurityException("Retained peer changed");return current;});
            remaining(deadline);PeerTransport active=transport();PeerTransport.Connection connection;
            try{connection=active.connect(peer.identity,peer.route,Math.min(8_000,remaining(deadline)));}
            catch(IOException oldRoute){if(peer.route.isEmpty())throw oldRoute;connection=active.connect(peer.identity,"",Math.min(8_000,remaining(deadline)));}
            endpointFailures.set(0);nextEndpointAttempt=0;
            if(Thread.currentThread().isInterrupted()){close(connection);throw new InterruptedException("Sync work stopped");}
            track(connection,key,deadline);
            if(Thread.currentThread().isInterrupted()){untrack(connection);close(connection);throw new InterruptedException("Sync work stopped");}
            if(peer.state==RemoteStore.State.PENDING&&pairingOwner!=null)pairingConnections.put(connection,pairingOwner);
            try(PeerTransport.Connection selected=connection) {
                if(peer.state==RemoteStore.State.PENDING)success=pairing(active).resume(selected,peer);
                else if(peer.state==RemoteStore.State.REVOKED){session(active).sendRevocation(selected,peer,deadline);success=true;}
                else{session(active).outgoing(selected,peer,deadline);success=true;}
            } finally{untrack(connection);}
            if(success)store.putMetadata(peer.identity,peer.generation,"connection_error","");
        } catch(Exception unavailable){
            logSessionFailure(peer.state==RemoteStore.State.ACTIVE?"outgoing-active":peer.state==RemoteStore.State.REVOKED?"outgoing-revoked":"outgoing-pending",unavailable);
            store.putMetadata(peer.identity,peer.generation,"connection_error","Unable to sync");
        }
        finally{finishAttempt(key,generation,success,peer.state==RemoteStore.State.PENDING);}
        return success;
    }
    private void accept(PeerTransport active) {
        while(endpoint==active) {
            PeerTransport.Connection connection;
            try{connection=active.accept(2000);}catch(SocketTimeoutException waiting){continue;}
            catch(IOException unavailable){break;}
            endpointFailures.set(0);nextEndpointAttempt=0;
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(55);track(connection,"",deadline);
            leases.incrementAndGet();
            AdmittedTask<Void> task=new AdmittedTask<>(()->{incoming(active,connection,deadline);return null;},()->{
                untrack(connection);close(connection);leases.decrementAndGet();suspendIfIdle();
            });
            try{incomingSessions.execute(task);}catch(RejectedExecutionException full){task.cancel(false);}
        }
        boolean failed=false;
        synchronized(endpointLock){if(endpoint==active){endpoint=null;failed=true;}}
        if(failed){close(active);deferEndpointAttempt();scheduleConnectedSync(Math.max(0,nextEndpointAttempt-SystemClock.elapsedRealtime()));}
    }
    private void deferEndpointAttempt(){
        int failures=endpointFailures.updateAndGet(previous->Math.min(6,previous+1));
        nextEndpointAttempt=SystemClock.elapsedRealtime()+Math.min(60_000,2_000L<<(failures-1));
    }
    private void incoming(PeerTransport active,PeerTransport.Connection connection,long deadline) {
        String reservation=null;
        try(PeerTransport.Connection selected=connection) {
            selected.setReadTimeoutMillis(8000);JSONObject first=RemoteProtocol.read(selected.input());
            store.authorityAccess(()->null);
            String type=first.optString("type");
            if("pair_request".equals(type)||"pair_recover".equals(type)){
                if(!com.subhub.app.security.DeviceRolePreferences.supportsDom(context))throw new SecurityException("Dom capability unavailable");
                String generation=RemoteProtocol.identifier(first.getString("pair_request".equals(type)?"invite":"generation"));
                String identity=RemoteProtocol.peerId(selected.authenticatedPeerIdentity());
                String owner=pairingKey(generation,generation,true);PairingOperationLease lease=pairingLeases.get(owner);
                if(!foreground&&backgroundWindows.get()==0&&lease!=null&&!lease.isClosed())pairingConnections.put(selected,owner);
                connections.put(selected,identity+":"+generation);
                pairing(active).handle(selected,first,System.currentTimeMillis());synchronizeBackground();return;
            }
            String generation=RemoteProtocol.identifier(first.getString("generation"));RemoteStore.Peer peer=store.authorityAccess(()->store.peer(selected.authenticatedPeerIdentity(),generation));
            if(peer==null)throw new SecurityException("Unknown peer");
            reservation=key(peer);if(!busy.add(reservation)){reservation=null;return;}
            connections.put(selected,reservation);
            try(NetworkWake wake=hasConnectedService()?new NetworkWake(context,deadline):null){
                if("revoke".equals(type))session(active).receiveRevocation(selected,first,deadline);
                else session(active).incoming(selected,first,deadline);
                store.putMetadata(peer.identity,peer.generation,"connection_error","");
            }
        }catch(Exception unavailable){logSessionFailure("incoming",unavailable);}
        finally{untrack(connection);if(reservation!=null)busy.remove(reservation);synchronizeBackground();scheduleConnectedSync(0);suspendIfIdle();}
    }
    private RemoteSessionControlCoordinator controlCoordinator(PeerTransport active) {
        if (Thread.holdsLock(store) || Thread.holdsLock(com.subhub.app.security.SubFeaturePolicy.LOCK)
                || Thread.holdsLock(com.subhub.app.appmode.RuntimeSessionIdentity.LOCK))
            throw new IllegalStateException("Resolve session identity outside authority locks");
        String identity = RemoteProtocol.peerId(active.localIdentity());
        synchronized (sessionControlLock) {
            if (sessionControlCoordinator == null || !sessionControlIdentity.equals(identity)) {
                sessionControlCoordinator = new RemoteSessionControlCoordinator(context, store, identity);
                sessionControlIdentity = identity;
            }
            return sessionControlCoordinator;
        }
    }
    private RemoteSession session(PeerTransport active) {
        return new RemoteSession(store,active,new RemoteSession.Hooks() {
            @Override public int appIconsVersion() { return com.subhub.app.remote.icons.AppIconExchange.VERSION; }
            @Override public void appIcons(PeerTransport.Connection connection,RemoteStore.Peer peer,long deadline) throws Exception {
                requireActive(peer);appIcons.exchange(connection,peer,deadline);
            }
            @Override public int sessionControlVersion() { return RemoteSessionRuntime.applicationEnabled() ? RemoteSessionCommands.VERSION : 0; }
            @Override public void sessionControl(PeerTransport.Connection connection, RemoteStore.Peer peer, long deadline) throws Exception {
                sessionControl(connection,peer,deadline,true);
            }
            @Override public void sessionControl(PeerTransport.Connection connection, RemoteStore.Peer peer, long deadline, boolean startsReady) throws Exception {
                requireActive(peer);
                if (!RemoteSessionRuntime.applicationEnabled()) throw new UnsupportedOperationException("Session control unavailable");
                RemoteSessionControlCoordinator coordinator = controlCoordinator(active);
                new RemoteSessionControlPeer(context, store, active.localIdentity(), coordinator::receiverState).exchange(connection, peer, deadline,startsReady);
            }
            @Override public int journalVersion() { return 1; }
            @Override public int journalTitlesVersion() { return 1; }
            @Override public void journalTitlesUpgraded(RemoteStore.Peer peer) throws Exception {
                com.subhub.app.journal.JournalRuntime runtime = com.subhub.app.journal.JournalRuntime.get(context);
                synchronized (store) {
                    runtime.requireActive(peer);
                    runtime.entries.republishTitles(peer.localRole == RemoteProtocol.Role.DOM,
                            com.subhub.app.journal.JournalRuntime.scope(peer));
                }
            }
            @Override public void journal(PeerTransport.Connection connection, RemoteStore.Peer peer, long deadline) throws Exception {
                requireActive(peer);
                com.subhub.app.journal.JournalPeer.exchange(com.subhub.app.journal.JournalRuntime.get(context), connection, peer, deadline);
            }
            @Override public int profileVersion() { return 1; }
            @Override public int profileDetailsVersion() { return 1; }
            @Override public int profileRoleVersion() { return 1; }
            @Override public JSONObject profile(RemoteStore.Peer peer) throws Exception {
                return withActivePeer(peer,()->RemoteProfiles.encode(com.subhub.app.people.ProfileStore.load(context,
                        peer.localRole == RemoteProtocol.Role.DOM), peer.localRole));
            }
            @Override public JSONObject profile(RemoteStore.Peer peer, boolean details) throws Exception {
                return withActivePeer(peer,()->RemoteProfiles.encode(com.subhub.app.people.ProfileStore.load(context,
                        peer.localRole == RemoteProtocol.Role.DOM), peer.localRole, details));
            }
            @Override public JSONObject profile(RemoteStore.Peer peer, boolean details, boolean roleIdentity) throws Exception {
                return withActivePeer(peer,()->RemoteProfiles.encode(com.subhub.app.people.ProfileStore.load(context,
                        peer.localRole == RemoteProtocol.Role.DOM), peer.localRole, details, roleIdentity));
            }
            @Override public int tasksVersion() { return 1; }
            @Override public int taskCancelVersion() { return 1; }
            @Override public void tasks(PeerTransport.Connection connection, RemoteStore.Peer peer, long deadline) throws Exception {
                requireActive(peer);
                com.subhub.app.tasks.TaskPeer.exchange(context, connection, peer, deadline);
            }
            @Override public String displayName(RemoteStore.Peer peer) {
                return withActivePeer(peer,()->com.subhub.app.people.ProfileNames.get(context,peer.localRole==RemoteProtocol.Role.DOM));
            }
            @Override public void authenticate(PeerTransport.Connection connection,RemoteStore.Peer peer,long deadline)throws Exception {
                connection.setReadTimeoutMillis(Math.min(10_000,remaining(deadline)));
                if(peer.localRole==RemoteProtocol.Role.SUB) {
                    withActivePeer(peer,()->{
                        String secret=keyholder.provisionSub(peer,active.localIdentity());
                        JSONObject credential=new JSONObject().put("version",1).put("type","keyholder_credential").put("generation",peer.generation).put("authVersion",1).put("authSecret",secret);
                        RemoteProtocol.write(connection.output(),credential);return null;
                    });
                    JSONObject receipt=RemoteProtocol.read(connection.input());requireKeyholderFrame(receipt,"keyholder_receipt",peer);requireActive(peer);
                } else {
                    JSONObject credential=RemoteProtocol.read(connection.input());requireKeyholderFrame(credential,"keyholder_credential",peer);
                    withActivePeer(peer,()->{
                        keyholder.receiveDom(peer,active.localIdentity(),credential.getString("authSecret"));
                        RemoteProtocol.write(connection.output(),new JSONObject().put("version",1).put("type","keyholder_receipt").put("generation",peer.generation).put("authVersion",1));return null;
                    });
                }
            }
            @Override public void prepare(RemoteStore.Peer peer) throws Exception {
                withActivePeer(peer,()->{
                activity.prepare(peer);
                endCommands.process(peer,System.currentTimeMillis());
                if(peer.localRole==RemoteProtocol.Role.SUB) {
                    boolean available=true;try{telemetry.prepare(peer);store.putMetadata(peer.identity,peer.generation,"stats_error","");}
                    catch(Exception unavailable){available=false;store.putMetadata(peer.identity,peer.generation,"stats_error","Statistics could not be refreshed");}
                    for(RemoteProtocol.Stream stream:new RemoteProtocol.Stream[]{RemoteProtocol.Stream.STATS,RemoteProtocol.Stream.DAILY,RemoteProtocol.Stream.APP_USAGE,RemoteProtocol.Stream.APP_CATALOG,RemoteProtocol.Stream.RUNTIME})
                        store.putMetadata(peer.identity,peer.generation,"unavailable_"+stream.name(),Boolean.toString(!available));
                }
                try{contracts.prepare(peer);store.putMetadata(peer.identity,peer.generation,"unavailable_BASELINE","false");}
                catch(Exception unavailable){store.putMetadata(peer.identity,peer.generation,"unavailable_BASELINE","true");store.putMetadata(peer.identity,peer.generation,"contract_error","Contract data could not be refreshed");}
                return null;});
            }
            @Override public void received(RemoteStore.Peer peer,RemoteProtocol.Stream stream) throws Exception {
                withActivePeer(peer,()->{endCommands.process(peer,System.currentTimeMillis());contracts.process(peer);return null;});
                if(stream==RemoteProtocol.Stream.APP_CATALOG||stream==RemoteProtocol.Stream.APP_USAGE||stream==RemoteProtocol.Stream.EVENTS)appIcons.refresh();
            }
            @Override public List<RemoteSession.AssetNeed> missingAssets(RemoteStore.Peer peer) throws Exception{return withActivePeer(peer,()->contracts.missing(peer));}
            @Override public RemoteAssets assets(RemoteStore.Peer peer) throws Exception{return withActivePeer(peer,()->contracts.assets(peer));}
            @Override public void complete(RemoteStore.Peer peer) throws Exception{withActivePeer(peer,()->{contracts.process(peer);return null;});}
        });
    }
    private <T>T withActivePeer(RemoteStore.Peer peer,LinkingWitness.CheckedWork<T> work){
        return store.authorityAccess(()->{
            RemoteStore.Peer current=store.peer(peer.identity,peer.generation);
            if(current==null||current.state!=RemoteStore.State.ACTIVE||current.localRole!=peer.localRole)throw new SecurityException("Pairing is not active");return work.run();
        });
    }
    private void requireActive(RemoteStore.Peer peer){withActivePeer(peer,()->null);}
    private static void requireKeyholderFrame(JSONObject frame,String type,RemoteStore.Peer peer)throws Exception {
        RemoteProtocol.requireVersion(frame);if(!type.equals(frame.getString("type"))||!peer.generation.equals(frame.getString("generation"))||RemoteProtocol.integer(frame,"authVersion")!=1)throw new SecurityException("Keyholder identity mismatch");
    }
    public boolean backgroundWindow() throws Exception {return backgroundWindow(55_000,()->false);}
    boolean backgroundWindow(long durationMillis) throws Exception {return backgroundWindow(durationMillis,()->false);}
    boolean backgroundWindow(long durationMillis,java.util.function.BooleanSupplier cancelled) throws Exception {
        if(stopped||!BackgroundSyncPreferences.enabled(context)||cancelled.getAsBoolean()||!needsBackground())return true;
        if(durationMillis<=0||durationMillis>55_000)throw new IllegalArgumentException("Invalid sync window");
        leases.incrementAndGet();backgroundWindows.incrementAndGet();
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(durationMillis);
        Map<Future<Boolean>,String> attempts=new LinkedHashMap<>();
        Map<String,Boolean> results=new HashMap<>();
        Map<String,Long> completedGeneration=new HashMap<>();
        Map<String,long[]> observedSync=new HashMap<>();
        try {
            if(!BackgroundSyncPreferences.enabled(context)||cancelled.getAsBoolean())return true;
            transport();
            // Retain the listener for the whole bounded window: another phone may wake after our outgoing IO ends.
            long nextRefresh=0,observedGeneration=-1;
            while(!stopped&&BackgroundSyncPreferences.enabled(context)&&!cancelled.getAsBoolean()&&System.nanoTime()<deadline) {
                for(Iterator<Map.Entry<Future<Boolean>,String>> iterator=attempts.entrySet().iterator();iterator.hasNext();) {
                    Map.Entry<Future<Boolean>,String> entry=iterator.next();
                    if(!entry.getKey().isDone())continue;
                    boolean complete=false;
                    try{complete=entry.getKey().get();}catch(ExecutionException|CancellationException unavailable){/* Bounded retry below. */}
                    results.put(entry.getValue(),complete);iterator.remove();
                }
                long now=SystemClock.elapsedRealtime(),generation=attemptGeneration();
                // Poll cancellation cheaply; only refresh SQLite once per second or after a local mutation.
                if(now>=nextRefresh||generation!=observedGeneration){
                    if(!needsBackground())break;
                    nextRefresh=now+1000;observedGeneration=generation;
                    for(RemoteStore.Peer peer:outgoingPeers()) {
                        if(peer.state==RemoteStore.State.PENDING&&peer.localRole==RemoteProtocol.Role.DOM)continue;
                        if(peer.state==RemoteStore.State.REVOKED&&"true".equals(store.metadata(peer.identity,peer.generation,"revocation_delivered","")))continue;
                        String key=key(peer);
                        long[] observed=observedSync.get(key);
                        if(observed==null){observed=new long[]{peer.lastSyncedAt,generation};observedSync.put(key,observed);}
                        // An already-running foreground/inbound owner can satisfy this window. A completed
                        // receipt must advance this exact peer/generation, with no intervening local mutation.
                        if(!busy.contains(key)&&peer.lastSyncedAt>observed[0]&&observed[1]==generation){
                            results.put(key,true);completedGeneration.put(key,generation);
                        }
                        // A successful snapshot needs no repeated payload in this window unless local data changed.
                        if(Boolean.TRUE.equals(results.get(key))&&Objects.equals(completedGeneration.get(key),generation))continue;
                        if(now<nextAttempt.getOrDefault(key,0L))continue;
                        if(!busy.add(key)){results.putIfAbsent(key,false);continue;}
                        Future<Boolean> attempt=schedulePeer(peer,deadline);
                        if(attempt!=null){attempts.put(attempt,key);completedGeneration.put(key,generation);}else results.put(key,false);
                    }
                }
                Thread.sleep(Math.min(100,Math.max(1,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()))));
            }
            if(!BackgroundSyncPreferences.enabled(context))return true;
            if(stopped||cancelled.getAsBoolean())throw new InterruptedException("Sync work stopped");
            for(Map.Entry<Future<Boolean>,String> entry:attempts.entrySet()) {
                if(!entry.getKey().isDone())return false;
                try{results.put(entry.getValue(),entry.getKey().get());}
                catch(ExecutionException|CancellationException unavailable){results.put(entry.getValue(),false);}
            }
            long finalGeneration=attemptGeneration();
            // Reconcile an external owner that completed after the last periodic inventory refresh.
            for(RemoteStore.Peer peer:store.peers(true)){
                String key=key(peer);long[] observed=observedSync.get(key);
                if(observed!=null&&!busy.contains(key)&&peer.lastSyncedAt>observed[0]&&observed[1]==finalGeneration){
                    results.put(key,true);completedGeneration.put(key,finalGeneration);
                }
            }
            return !results.containsValue(false)&&results.keySet().stream().allMatch(key->Objects.equals(completedGeneration.get(key),finalGeneration))&&!liveInvitation()
                    &&store.peers(false).stream().noneMatch(peer->peer.state==RemoteStore.State.PENDING);
        } finally {
            Set<String> cancelledKeys=new HashSet<>();
            for(Map.Entry<Future<Boolean>,String> entry:attempts.entrySet())if(!entry.getKey().isDone())cancelledKeys.add(entry.getValue());
            // Capture exact handles before cancellation releases admission; never close a successor's connection.
            List<PeerTransport.Connection> cancelledConnections=new ArrayList<>();
            for(Map.Entry<PeerTransport.Connection,String> entry:connections.entrySet())
                if(cancelledKeys.contains(entry.getValue())&&!pairingConnections.containsKey(entry.getKey()))cancelledConnections.add(entry.getKey());
            for(Future<Boolean> attempt:attempts.keySet())if(!attempt.isDone())attempt.cancel(true);
            for(PeerTransport.Connection connection:cancelledConnections)close(connection);
            if(!foreground && !hasConnectedService() && leases.get()==1 && !hasPairingLease())for(PeerTransport.Connection connection:connections.keySet())if(!pairingConnections.containsKey(connection))close(connection);
            backgroundWindows.decrementAndGet();leases.decrementAndGet();suspendIfIdle();
        }
    }
    /** An active-pair connection owner; this never grants invitation or approval lifetime. */
    final class ConnectedServiceLease implements AutoCloseable {
        private final Runnable noPeers;
        private final String admission;
        private final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
        private ConnectedServiceLease(Runnable noPeers,String admission){this.noPeers=noPeers;this.admission=admission;}
        boolean isClosed(){return closed.get();}
        @Override public void close(){
            if(!closed.compareAndSet(false,true))return;
            synchronized(connectedServiceLock){
                if(connectedServiceLease==this){connectedServiceLease=null;if(connectedServiceWake!=null)connectedServiceWake.cancel(false);connectedServiceWake=null;}
            }
            // Bound in-flight data exchanges retain their existing owner/deadline. No pairing lease is changed.
            deferSuspendIfIdle();
        }
        void noPeers(){
            close();
            // Retained revocations keep their bounded WorkManager delivery after the last active pair leaves.
            boolean needed=needsBackground();synchronizeBackground();
            if(needed)kickBackground();
            noPeers.run();
        }
    }
    CompletableFuture<ConnectedServiceLease> acquireConnectedService(Runnable noPeers){
        String admission=BackgroundSyncPreferences.admission(context);
        if(admission==null)return CompletableFuture.completedFuture(null);
        return submit(()->{
            if(stopped||!hasActivePeers())return null;
            ConnectedServiceLease selected;
            synchronized(connectedServiceLock){
                if(!BackgroundSyncPreferences.admits(context,admission))return null;
                // Each request owns its exact handle. A retired Service's late callback cannot close its successor.
                ConnectedServiceLease previous=connectedServiceLease;
                selected=new ConnectedServiceLease(Objects.requireNonNull(noPeers),admission);
                connectedServiceLease=selected;
                if(previous!=null)previous.close();
            }
            scheduleConnectedSync(0);return selected;
        });
    }
    boolean hasConnectedService(){ConnectedServiceLease selected=connectedServiceLease;return selected!=null&&!selected.isClosed()&&BackgroundSyncPreferences.admits(context,selected.admission);}
    private boolean hasActivePeers(){
        if(!recoveryReady())return false;
        for(RemoteStore.Peer peer:store.peers(false))if(peer.state==RemoteStore.State.ACTIVE)return true;
        return false;
    }
    private void requestConnectedServiceStart(){
        if(!installed||stopped||!BackgroundSyncPreferences.enabled(context)||!foreground&&!hasPairingLease())return;
        try{timer.execute(()->{
            if(stopped||!BackgroundSyncPreferences.enabled(context)||!hasActivePeers())return;
            serviceMain.post(()->{if(!stopped&&BackgroundSyncPreferences.enabled(context)&&(foreground||hasPairingLease()))PeerSyncForegroundService.requestStart(context);});
        });}catch(RejectedExecutionException closed){/* A retired runtime cannot restart service ownership. */}
    }
    /** One scheduled due wake, coalesced with mutations and completions, rather than a background polling loop. */
    private void scheduleConnectedSync(long delayMillis){
        if(stopped||!hasConnectedService())return;
        long due=SystemClock.elapsedRealtime()+Math.max(0,delayMillis);
        synchronized(connectedServiceLock){
            if(stopped||!hasConnectedService())return;
            if(connectedServiceWake!=null&&!connectedServiceWake.isDone()&&connectedServiceWakeAt<=due)return;
            if(connectedServiceWake!=null)connectedServiceWake.cancel(false);
            connectedServiceWakeAt=due;
            try{connectedServiceWake=timer.schedule(()->{
                synchronized(connectedServiceLock){connectedServiceWake=null;}
                connectedSync();
            },Math.max(0,delayMillis),TimeUnit.MILLISECONDS);}
            catch(RejectedExecutionException closed){connectedServiceWake=null;}
        }
    }
    private void connectedSync(){
        ConnectedServiceLease owner=connectedServiceLease;if(owner==null||owner.isClosed()||stopped||!BackgroundSyncPreferences.enabled(context))return;
        try{
            if(!hasActivePeers()){owner.noPeers();return;}
            android.net.ConnectivityManager network=(android.net.ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if(network==null||network.getActiveNetwork()==null){scheduleConnectedSync(60_000);return;}
            long endpointDelay=nextEndpointAttempt-SystemClock.elapsedRealtime();
            if(endpoint==null&&endpointDelay>0){scheduleConnectedSync(endpointDelay);return;}
            tick();
            long now=SystemClock.elapsedRealtime(),next=now+60_000;
            for(RemoteStore.Peer peer:outgoingPeers()){
                if(peer.state==RemoteStore.State.PENDING&&peer.localRole==RemoteProtocol.Role.DOM)continue;
                if(peer.state==RemoteStore.State.REVOKED&&"true".equals(store.metadata(peer.identity,peer.generation,"revocation_delivered","")))continue;
                if(!busy.contains(key(peer)))next=Math.min(next,nextAttempt.getOrDefault(key(peer),now+1000));
            }
            scheduleConnectedSync(Math.max(250,next-now));
        }catch(RuntimeException unavailable){android.util.Log.w("PeerSync","Connected sync deferred");scheduleConnectedSync(5_000);}
    }
    private void warmTransport(){
        leases.incrementAndGet();
        AdmittedTask<Void> task=new AdmittedTask<>(()->{
            try(NetworkWake wake=hasConnectedService()?new NetworkWake(context,System.nanoTime()+TimeUnit.SECONDS.toNanos(15)):null){transport();}
            return null;
        },()->{leases.decrementAndGet();warming.set(false);scheduleConnectedSync(endpoint==null?5_000:0);deferSuspendIfIdle();});
        try{operations.execute(task);}catch(RejectedExecutionException full){task.cancel(false);}
    }
    /** Holds CPU only for the existing bounded network operation, never for the idle listener. */
    private static final class NetworkWake implements AutoCloseable{
        private final android.os.PowerManager.WakeLock lock;
        NetworkWake(Context context,long deadline){
            android.os.PowerManager power=(android.os.PowerManager)context.getSystemService(Context.POWER_SERVICE);
            if(power==null)throw new IllegalStateException("Power service unavailable");
            lock=power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"com.subhub.app:peer_exchange");lock.setReferenceCounted(false);
            long remaining=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());lock.acquire(Math.max(1,Math.min(60_000,remaining+1000)));
        }
        @Override public void close(){try{if(lock.isHeld())lock.release();}catch(RuntimeException expired){/* Timeout and completion may race. */}}
    }
    private void deferSuspendIfIdle() {
        if(foreground||hasConnectedService()||hasPairingLease()||leases.get()>0||!busy.isEmpty()||!idleCheckPending.compareAndSet(false,true))return;
        try{timer.execute(()->{idleCheckPending.set(false);suspendIfIdle();});}
        catch(RejectedExecutionException stopped){idleCheckPending.set(false);}
    }
    private void suspendIfIdle() {
        if(foreground || hasConnectedService() || hasPairingLease() || leases.get()>0 || !busy.isEmpty())return;
        synchronized(endpointLock){if(foreground || hasConnectedService() || hasPairingLease() || leases.get()>0 || !busy.isEmpty())return;PeerTransport previous=endpoint;endpoint=null;if(previous!=null)close(previous);}
    }
    synchronized void shutdownForTest() throws Exception {
        stopped=true;
        ConnectedServiceLease connected=connectedServiceLease;if(connected!=null)connected.close();
        if(connectivityWake!=null){connectivityWake.close();connectivityWake=null;}
        if(lifecycleApplication!=null&&lifecycleCallbacks!=null)lifecycleApplication.unregisterActivityLifecycleCallbacks(lifecycleCallbacks);
        lifecycleApplication=null;lifecycleCallbacks=null;
        pairingDeadlineTimer.shutdownNow();
        appIcons.close();
        for(PairingOperationLease lease:pairingLeases.values())lease.close();
        foreground=false;timer.shutdownNow();synchronized(endpointLock){PeerTransport previous=endpoint;endpoint=null;if(previous!=null)close(previous);}
        acceptor.shutdownNow();
        for(ThreadPoolExecutor pool:new ThreadPoolExecutor[]{operations,incomingSessions,sessions}) {
            for(Runnable queued:pool.shutdownNow())if(queued instanceof Future<?>)((Future<?>)queued).cancel(false);
        }
        for(ThreadPoolExecutor pool:new ThreadPoolExecutor[]{operations,incomingSessions,sessions})
            if(!pool.awaitTermination(10,TimeUnit.SECONDS))throw new IllegalStateException("Peer work did not stop");
        acceptor.awaitTermination(10,TimeUnit.SECONDS);
        if(!pairingDeadlineTimer.awaitTermination(10,TimeUnit.SECONDS))throw new IllegalStateException("Pairing deadlines did not stop");
        activity.close();
        com.subhub.app.security.SubFeaturePolicy.unregisterHardcoreAuthority(context, store);store.close();
    }
    private static long remaining(long deadline) throws SocketTimeoutException {long millis=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());if(millis<=0)throw new SocketTimeoutException("Sync window expired");return millis;}
    private static void close(AutoCloseable value){try{value.close();}catch(Exception ignored){}}
}
