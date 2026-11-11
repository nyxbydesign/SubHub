package com.subhub.app.tasks;

import android.content.Context;
import com.subhub.app.internal.*;
import com.subhub.app.people.*;
import com.subhub.app.remote.RemoteAssets;
import java.util.*;

/** One owned SQL projection. All SQL mutations use the same serial broker as owner accounting/editors. */
final class InternalTaskBackend {
    private static final Map<InternalSubData, InternalTaskBackend> OPEN = new IdentityHashMap<>();
    static final String DATA_SCOPE = "internal_sub";
    private volatile TaskStore store;
    private RemoteAssets assets;
    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> observers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final InternalSubData data;
    private final Context physical;
    private InternalTaskBackend(Context physical, InternalSubData data) { this.physical=physical; this.data=data; }
    static InternalTaskBackend get(Context physical) { return get(physical, InternalSubData.get(physical)); }
    static InternalTaskBackend get(Context physical, InternalSubData data) {
        synchronized (InternalTaskBackend.class) {
            InternalTaskBackend value=OPEN.get(data);
            if(value==null){value=new InternalTaskBackend(physical.getApplicationContext(),data);OPEN.put(data,value);}
            return value;
        }
    }
    private void open(InternalStorageScope storage, Runnable admission) {
        if (!data.isOwnerThread()) throw new IllegalStateException("Internal Tasks require the sole owner worker");
        admission.run();
        if(store!=null)return;
        try {
            RemoteAssets openedAssets=new RemoteAssets(new java.io.File(storage.directory(InternalStorageScope.Area.TASKS),"attachments"));
            TaskStore openedStore=new TaskStore(physical,storage,physical.getSharedPreferences(InternalTaskDatabase.REGISTRY,0),admission);
            openedStore.observe(()->{for(Runnable observer:observers)observer.run();});
            assets=openedAssets;store=openedStore;
        }catch(Exception unavailable){throw new IllegalStateException("Internal Tasks unavailable",unavailable);}
    }
    private static void requireBoundary(InternalSubRecord record) {
        if(record.tasksResumeAfterMillis<=0)throw new IllegalStateException("Internal Tasks resume boundary is unavailable");
    }
    private interface Edit<T> { T run(InternalOwnerDataBroker.EditorAccess access) throws Exception; }
    private <T> T edit(String generation,long controller,Edit<T> work) {
        return data.await(data.ownerData().submitEditor(generation,controller,access->{
            requireBoundary(access.record());open(access.storage(),access::requireCurrent);
            try{return work.run(access);}catch(RuntimeException problem){throw problem;}
            catch(Exception problem){throw new IllegalStateException("Internal Tasks operation failed",problem);}
        }));
    }
    RemoteAssets assets(String generation,long controller){return edit(generation,controller,access->assets);}
    java.io.File assetsDirectory()throws java.io.IOException{return new java.io.File(data.storage.directory(InternalStorageScope.Area.TASKS),"attachments");}
    java.io.File seal(TaskAttachment attachment,String generation,long controller)throws java.io.IOException{
        return com.subhub.app.storage.InternalAssetDurability.seal(assetsDirectory(),assets(generation,controller),attachment.hash,attachment.length);
    }
    java.io.File temporaryDirectory()throws java.io.IOException{return data.storage.directory(InternalStorageScope.Area.CACHE);}
    TaskStore.Observation observe(Runnable changed){observers.add(Objects.requireNonNull(changed));return ()->observers.remove(changed);}
    InternalSubRecord current(String generation,long controller){
        if(!DummySubPreview.current(physical,generation,controller))throw new SecurityException("Internal Tasks authority changed");
        InternalSubRecord record=data.acknowledged();
        if(!record.visible||!record.generation.equals(generation))throw new SecurityException("Internal Tasks target changed");
        return record;
    }
    TaskEngine engine(String generation,long controller){return edit(generation,controller,access->store.load(DATA_SCOPE));}
    void append(String generation,long controller,TaskAction action,boolean dom){
        edit(generation,controller,access->{
            access.requireCurrent();store.append(DATA_SCOPE,action,dom,!dom);return null;
        });
        // Background wake/effect host is integrated separately before enabling internal runtime start.
        // Page refreshes continue through the same broker and retained SQL effects remain pending.
    }
    void sweep(String generation,long controller,long now){
        current(generation,controller);
        data.await(data.ownerData().submitTask(generation,access->{
            try{sweepAdmitted(access,now);return null;}
            catch(RuntimeException problem){throw problem;}
            catch(Exception problem){throw new IllegalStateException("Internal Tasks update failed",problem);}
        }));
    }
    TaskStore admittedStore(InternalOwnerDataBroker.TaskAccess access){
        requireBoundary(access.record());open(access.storage(),access::requireCurrent);return store;
    }
    void sweepAdmitted(InternalOwnerDataBroker.TaskAccess access,long now)throws Exception{
        TaskStore owned=admittedStore(access);TaskEngine engine=owned.load(DATA_SCOPE);int count=0;
        long resumeAfter=access.resumeAfterMillis();
        for(TaskDefinition task:engine.definitions()){
            if(engine.seriesRefused(task.id)||engine.seriesCancelled(task.id))continue;
            java.time.LocalDate today=java.time.Instant.ofEpochMilli(now).atZone(task.zone).toLocalDate();
            for(java.time.LocalDate date=task.nextDate(task.firstStart.toLocalDate());date!=null&&!date.isAfter(today);
                    date=task.nextDate(date.plusDays(1))){
                TaskEngine.Occurrence row=engine.occurrence(task.id,date,now);
                if(row.status!=TaskEngine.Status.ACTIVE)continue;
                access.requireCurrent();
                if(task.end(date).toEpochMilli()>now){owned.started(DATA_SCOPE,row,now);continue;}
                String id="timeout_"+UUID.nameUUIDFromBytes((DATA_SCOPE+":"+row.id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                TaskAction.Kind kind=task.end(date).toEpochMilli()<=resumeAfter?TaskAction.Kind.SKIP:TaskAction.Kind.TIMEOUT;
                TaskAction action=new TaskAction(id,task.id,kind,date,now,"","",List.of(),null);
                owned.append(DATA_SCOPE,action,false,false);engine.apply(action,false);
                if(++count==64)return;
            }
        }
    }
}
