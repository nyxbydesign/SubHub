package com.subhub.app.remote;

import android.content.Context;
import com.subhub.app.remote.icons.AndroidAppIconSource;
import com.subhub.app.remote.icons.AppIconCache;
import com.subhub.app.remote.icons.AppIconExchange;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.storage.StoredPayloads;
import com.subhub.app.util.RemoteAppIcon;
import com.subhub.app.util.RemoteAppIcons;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import org.json.JSONObject;

/** Composes current peer records with the optional image phase. Disk cache contents grant nothing. */
final class RemoteAppIconIntegration implements RemoteAppIcons.Provider, AppIconExchange.Authority, AutoCloseable {
    private final Context context;
    private final RemoteStore store;
    private final Executor cancelWorker;
    private final Runnable synchronize;
    private final Object initialization = new Object();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, RemoteAppIcon> demand = new LinkedHashMap<>();
    private final Map<String, Long> lastAttempt = new HashMap<>();
    private Set<RemoteAppIcon> references = Collections.emptySet();
    private volatile AppIconCache cache;
    private volatile AppIconExchange exchange;
    private volatile android.content.SharedPreferences rolePreferences;
    private AutoCloseable sessionObserver;
    private volatile boolean closed;
    private static final class ViewerDispatch {static final android.os.Handler MAIN=new android.os.Handler(android.os.Looper.getMainLooper());}
    private final java.util.concurrent.atomic.AtomicBoolean viewerNotification=new java.util.concurrent.atomic.AtomicBoolean();
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener roleObserver=(preferences,key)->{
        if(key==null||com.subhub.app.security.DeviceRolePreferences.KEY_MODE.equals(key))viewerChanged();
    };

    RemoteAppIconIntegration(Context context, RemoteStore store, Executor cancelWorker, Runnable synchronize) {
        this.context=context.getApplicationContext();this.store=store;
        this.cancelWorker=cancelWorker;this.synchronize=synchronize;
    }

    private void initialize() throws IOException {
        if (cache!=null) return;
        synchronized (initialization) {
            if (cache!=null) return;
            if(closed)throw new IOException("Icon integration closed");
            AppIconCache created=new AppIconCache(context.getCacheDir());
            android.content.SharedPreferences roles=context.getSharedPreferences(com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME,Context.MODE_PRIVATE);
            roles.contains(com.subhub.app.security.DeviceRolePreferences.KEY_MODE); // Await initial loading on this worker, once.
            roles.registerOnSharedPreferenceChangeListener(roleObserver);rolePreferences=roles;
            sessionObserver=ControllerPinManager.observeSession(this::viewerChanged);
            created.addInvalidationListener(this::changed);
            exchange=new AppIconExchange(this,new AndroidAppIconSource(context,this),created);
            cache=created;
        }
    }

    /** Worker-only. Publish only changes to reference authority, preserving descriptors for unchanged refs. */
    void refresh() throws IOException {
        initialize();
        long revision=ControllerPinManager.domWorkspaceRevision();
        if(!viewerAllowed()){publish(Collections.emptySet());return;}
        try {
            store.authorityAccess(()->{
                if(!viewerAllowed()){publish(Collections.emptySet());return null;}
                Set<RemoteAppIcon> current=new LinkedHashSet<>();
                for (RemoteStore.Peer peer:store.peers(false)) {
                    if (peer.state!=RemoteStore.State.ACTIVE || peer.localRole!=RemoteProtocol.Role.DOM) continue;
                    for (String name:packages(peer,false)) current.add(new RemoteAppIcon(peer.identity,peer.generation,name,null));
                }
                if(revision!=ControllerPinManager.domWorkspaceRevision()||!viewerAllowed())throw new SecurityException("Dom workspace changed");
                publish(current);
                long previous=viewerRevision;viewerRevision=revision;if(previous!=revision)changed();
                return null;
            });
        } catch (RuntimeException unavailable) {
            publish(Collections.emptySet());throw unavailable;
        }
    }

    private synchronized void publish(Set<RemoteAppIcon> current) {
        if (references.equals(current)) return;
        AppIconCache target=cache;
        List<RemoteAppIcon> complete=new ArrayList<>();
        for (RemoteAppIcon bare:current) {
            RemoteAppIcon retained=references.contains(bare)
                    ?target.snapshot().identity(bare.peer,bare.generation,bare.packageName):null;
            complete.add(retained==null?bare:retained);
        }
        references=Collections.unmodifiableSet(new LinkedHashSet<>(current));
        synchronized (demand) {
            demand.values().removeIf(icon->!references.contains(icon));
            lastAttempt.keySet().retainAll(demand.keySet());
        }
        target.publishReferences(complete);
    }

    void revoked(String identity,String generation) {
        synchronized (this) {
            Set<RemoteAppIcon> reduced=new LinkedHashSet<>(references);
            reduced.removeIf(icon->icon.peer.equals(identity)&&icon.generation.equals(generation));
            if (cache!=null) publish(reduced);
        }
        // Closing a transport can block; the store's revoke callback only publishes memory invalidation.
        try{cancelWorker.execute(()->{AppIconExchange active=exchange;if(active!=null)active.cancel(identity,generation);});}
        catch(java.util.concurrent.RejectedExecutionException stopped){/* Published authority already refuses every delivery. */}
    }

    void exchange(PeerTransport.Connection connection,RemoteStore.Peer peer,long deadline) throws Exception {
        refresh();
        exchange.exchange(connection,peer,deadline);
    }

    RemoteAppIcon identity(String peer,String generation,String name) {
        RemoteAppIcon bare;
        try {bare=new RemoteAppIcon(peer,generation,name,null);}
        catch(IllegalArgumentException invalid){return null;}
        boolean requested;
        synchronized (demand) {
            String key=peer+"\n"+generation+"\n"+name;
            requested=!demand.containsKey(key);
            demand.remove(key);demand.put(key,bare);
            while(demand.size()>256){String oldest=demand.keySet().iterator().next();demand.remove(oldest);lastAttempt.remove(oldest);}
        }
        if (requested) synchronize.run();
        AppIconCache target=cache;
        RemoteAppIcon known=target==null?null:target.snapshot().identity(peer,generation,name);
        return known!=null && currentAuthority().permits(known)?known:bare;
    }

    @Override public boolean current(RemoteStore.Peer peer) {
        try{return store.authorityAccess(()->active(peer));}catch(RuntimeException unavailable){refused();return false;}
    }
    private boolean active(RemoteStore.Peer captured) {
        RemoteStore.Peer actual=store.peer(captured.identity,captured.generation);
        return actual!=null && actual.state==RemoteStore.State.ACTIVE && actual.localRole==captured.localRole;
    }
    @Override public boolean published(RemoteStore.Peer peer,String name) {return reference(peer,name,true);}
    @Override public boolean mirrored(RemoteStore.Peer peer,String name) {return reference(peer,name,false);}
    private boolean reference(RemoteStore.Peer peer,String name,boolean published) {
        try{return store.authorityAccess(()->active(peer)
                && peer.localRole==(published?RemoteProtocol.Role.SUB:RemoteProtocol.Role.DOM)
                && (catalogReference(peer,name,published)||packages(peer,published).contains(name)));}
        catch(RuntimeException unavailable){refused();return false;}
    }
    private void refused(){if(cache!=null)publish(Collections.emptySet());}
    private boolean catalogReference(RemoteStore.Peer peer,String name,boolean published)throws Exception{
        // Exact catalog lookup keeps per-chunk guards independent of the installed catalog's size.
        synchronized(store){
            try(android.database.Cursor row=store.getReadableDatabase().rawQuery("SELECT body FROM "
                    +(published?"local_objects":"remote_objects")
                    +" WHERE peer=? AND generation=? AND stream='APP_CATALOG' AND item=? AND deleted=0",
                    new String[]{peer.identity,peer.generation,name})){
                if(!row.moveToFirst())return false;
                JSONObject body=new JSONObject(StoredPayloads.read(row,0));
                return RemoteProtocol.integer(body,"schema")==1 && name.equals(body.optString("package"));
            }
        }
    }
    @Override public List<String> wanted(RemoteStore.Peer peer) {
        if(!viewerAllowed())return Collections.emptyList();
        List<RemoteAppIcon> requested;
        synchronized(demand){
            requested=new ArrayList<>(demand.values());Collections.reverse(requested);
            // Stable sort retains recent display priority for equally unattempted records. Completed and
            // unavailable images rotate alike, so neither a fixed page order nor missing apps starve others.
            requested.sort(Comparator.comparingLong(icon->lastAttempt.getOrDefault(demandKey(icon),0L)));
        }
        List<String> result=new ArrayList<>();
        for(RemoteAppIcon icon:requested) {
            if(icon.peer.equals(peer.identity)&&icon.generation.equals(peer.generation)&&mirrored(peer,icon.packageName)) {
                result.add(icon.packageName);if(result.size()==AppIconExchange.MAX_REQUESTS)break;
            }
        }
        synchronized(demand){
            for(String name:result){String key=peer.identity+"\n"+peer.generation+"\n"+name;if(demand.containsKey(key))lastAttempt.put(key,System.nanoTime());}
        }
        return result;
    }
    private static String demandKey(RemoteAppIcon icon){return icon.peer+"\n"+icon.generation+"\n"+icon.packageName;}

    private Set<String> packages(RemoteStore.Peer peer,boolean published) throws Exception {
        Set<String> result=new TreeSet<>();
        for(RemoteProtocol.Stream stream:new RemoteProtocol.Stream[]{RemoteProtocol.Stream.APP_CATALOG,
                RemoteProtocol.Stream.EVENTS,RemoteProtocol.Stream.APP_USAGE}) {
            for(RemoteProtocol.Change row:published?store.published(peer.identity,peer.generation,stream)
                    :store.mirror(peer.identity,peer.generation,stream)) {
                if(row.deleted)continue;
                JSONObject body=new JSONObject(row.body);
                if(stream==RemoteProtocol.Stream.EVENTS) {
                    com.subhub.app.activity.ActivityEvent event=RemoteActivityWire.decode(body);
                    if(event.kind==com.subhub.app.activity.ActivityEvent.Kind.APP_OPENED
                            ||event.kind==com.subhub.app.activity.ActivityEvent.Kind.APP_LIMIT_REACHED)add(result,peer,event.packageName);
                }
                else if(stream==RemoteProtocol.Stream.APP_CATALOG) {
                    if(RemoteProtocol.integer(body,"schema")==1 && row.key.equals(body.optString("package"))) add(result,peer,row.key);
                } else if(RemoteProtocol.integer(body,"schema")==1) {
                    if("today".equals(row.key)) {
                        JSONObject values=body.getJSONObject("millis");
                        for(Iterator<String> names=values.keys();names.hasNext();)add(result,peer,names.next());
                    } else add(result,peer,body.optString("package"));
                }
            }
        }
        // A retained request is itself an exact peer-scoped app reference, including when
        // the app is no longer in the current catalog or today's usage projection.
        for (ExtraTimeStore.Row row : new ExtraTimeStore(context).load(ExtraTimeRequests.scope(peer)))
            if (!row.state.equals("SUBMITTING")) add(result, peer, row.packageName);
        return result;
    }
    private static void add(Set<String> names,RemoteStore.Peer peer,String name) {
        if(name.isEmpty())return;
        try { names.add(new RemoteAppIcon(peer.identity,peer.generation,name,null).packageName); }
        catch(IllegalArgumentException invalid){/* Unsupported package strings stay generic. */}
    }

    @Override public RemoteAppIcons.Authority currentAuthority() {
        AppIconCache target=cache;
        return target!=null && viewerAllowed()&&viewerRevision==ControllerPinManager.domWorkspaceRevision()?target.currentAuthority():RemoteAppIcons.Authority.EMPTY;
    }
    private volatile long viewerRevision=-1;
    private boolean viewerAllowed(){
        android.content.SharedPreferences loaded=rolePreferences;
        if(closed||loaded==null||!ControllerPinManager.isDomWorkspaceUnlocked())return false;
        // This exact SharedPreferences instance finished loading before publication. getString is a
        // memory read here, so capacity loss is observed even before its queued listener runs.
        try{return com.subhub.app.security.DeviceRolePreferences.Mode.valueOf(loaded.getString(
                com.subhub.app.security.DeviceRolePreferences.KEY_MODE,com.subhub.app.security.DeviceRolePreferences.Mode.BOTH.name()))
                !=com.subhub.app.security.DeviceRolePreferences.Mode.SUB_ONLY;}
        catch(IllegalArgumentException|ClassCastException malformed){return false;}
    }
    private void viewerChanged(){
        // SharedPreferences may invoke this inline from a commit under Runtime/authority locks.
        // Only enqueue here; provider listeners and reference rebuilding happen after returning.
        if(!closed&&viewerNotification.compareAndSet(false,true))ViewerDispatch.MAIN.post(()->{
            viewerNotification.set(false);if(closed)return;
            changed();
            try{cancelWorker.execute(()->{try{refresh();}catch(Exception unavailable){refused();}});}
            catch(java.util.concurrent.RejectedExecutionException stopped){/* Owner has stopped. */}
        });
    }
    @Override public void close(){
        synchronized(initialization){
            closed=true;
            if(rolePreferences!=null)rolePreferences.unregisterOnSharedPreferenceChangeListener(roleObserver);
            if(sessionObserver!=null)try{sessionObserver.close();}catch(Exception ignored){/* Memory subscription only. */}
        }
        refused();
    }
    @Override public byte[] load(RemoteAppIcon identity) throws Exception {
        long revision=ControllerPinManager.domWorkspaceRevision();
        if(!currentAuthority().permits(identity))return null;
        RemoteStore.Peer peer=store.authorityAccess(()->store.peer(identity.peer,identity.generation));
        if(peer==null||!mirrored(peer,identity.packageName))return null;
        byte[] bytes=cache.load(identity);
        return revision==ControllerPinManager.domWorkspaceRevision()&&currentAuthority().permits(identity)&&mirrored(peer,identity.packageName)?bytes:null;
    }
    @Override public void addInvalidationListener(Runnable listener){listeners.addIfAbsent(Objects.requireNonNull(listener));}
    @Override public void removeInvalidationListener(Runnable listener){listeners.remove(listener);}
    private void changed(){for(Runnable listener:listeners){try{listener.run();}catch(RuntimeException ignored){/* Observer owns failure. */}}}
}
