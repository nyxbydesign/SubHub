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
    private final InternalTaskEffectDrain<TaskStore.Effect> effects = new InternalTaskEffectDrain<>();
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
        InternalTaskRuntime.changed(physical, data, generation);
    }
    void sweep(String generation,long controller,long now){
        current(generation,controller);
        data.await(data.ownerData().submitTask(generation,access->{
            try{sweepAdmitted(access,now);return null;}
            catch(RuntimeException problem){throw problem;}
            catch(Exception problem){throw new IllegalStateException("Internal Tasks update failed",problem);}
        }));
    }
    void effectsAdmitted(InternalOwnerDataBroker.TaskAccess access, com.subhub.app.stats.OwnerObservation observed,
            com.subhub.app.notifications.OptionalNotificationDispatch.InternalTaskOwner owner,
            InternalTaskNotifications notifications) throws Exception {
        TaskStore owned = admittedStore(access);
        TaskEngine engine = owned.load(DATA_SCOPE);
        effects.run(access.record().generation, observed.at,
                new InternalTaskEffectDrain.Access<TaskStore.Effect>() {
            public void requireCurrent() { access.requireCurrent(); }
            public List<InternalTaskEffectDrain.Pending<TaskStore.Effect>> pending(boolean failures,long after,int limit) throws Exception {
                access.requireCurrent();
                return owned.pendingEffects(DATA_SCOPE,failures,after,limit,access::requireCurrent);
            }
            public boolean discard(TaskStore.Effect effect) {
                return effect.action.at <= access.resumeAfterMillis()
                        || "sub_started".equals(effect.kind) && (engine.occurrence(effect.action.taskId,effect.action.date,observed.at).status != TaskEngine.Status.ACTIVE
                                || engine.occurrence(effect.action.taskId,effect.action.date,observed.at).task.end(effect.action.date).toEpochMilli() <= observed.at)
                        || engine.cancelled(effect.action.taskId, effect.action.date) && !effect.action.cancellation();
            }
            public boolean failure(TaskStore.Effect effect) { return "failure".equals(effect.kind); }
            public void assess(TaskStore.Effect effect) {
                TaskEngine.Occurrence row = engine.occurrence(effect.action.taskId, effect.action.date, observed.at);
                if (row.failed() && row.task.failureTribute) {
                    com.subhub.app.penance.OwnerPenanceLedger.Result result = access.assessTaskFailure(DATA_SCOPE + ":" + effect.id, observed);
                    if (result.status != com.subhub.app.penance.OwnerPenanceLedger.Status.ACKNOWLEDGED)
                        throw new IllegalStateException("Internal task assessment was not acknowledged");
                }
            }
            public void done(TaskStore.Effect effect) { access.requireCurrent(); owned.done(DATA_SCOPE, effect); }
            public String key(TaskStore.Effect effect) {
                return InternalTaskNoticeKey.create(effect.action.taskId, effect.action.date, effect.action.cancellation(), effect.kind, effect.id);
            }
            public TaskNoticeLedger.Audience audience(TaskStore.Effect effect) {
                if ("notification".equals(effect.kind)) return TaskNoticeLedger.Audience.DOM_REVIEW;
                if ("sub_started".equals(effect.kind) || "sub_notification".equals(effect.kind)) return TaskNoticeLedger.Audience.SUB_TASKS;
                throw new IllegalStateException("Unknown internal task effect");
            }
            public InternalTaskEffectDrain.Notice prepare(TaskStore.Effect effect) { return notifications.prepare(audience(effect)); }
            public List<TaskNoticeLedger.Receipt> receipts() { return access.notices(); }
            public boolean obsolete(TaskNoticeLedger.Receipt receipt) {
                InternalTaskNoticeKey key = InternalTaskNoticeKey.parse(receipt.effectKey);
                return !key.cancellation && engine.cancelled(key.task, key.date);
            }
            public void cancel(TaskNoticeLedger.Receipt receipt) { notifications.cancel(receipt); }
            public void removeAfterCancellation(TaskNoticeLedger.Receipt receipt) { access.removeNoticeAfterCancellation(receipt); }
            public TaskNoticeLedger.Receipt admit(TaskNoticeLedger.Receipt receipt) { return access.admitNotice(receipt); }
            public boolean post(TaskStore.Effect effect, TaskNoticeLedger.Receipt receipt) {
                return notifications.post(owner, effect, engine, receipt);
            }
        });
        owned.changed();
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
