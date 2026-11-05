package com.subhub.app.remote;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.ListenableWorker;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.testing.TestWorkerBuilder;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;

/** Independent preference namespaces; worker refusal must not initialize the peer endpoint. */
public final class BackgroundSyncPreferencesAndroidTest {
    @Test public void absentChoiceIsOnAndSavedChoiceSurvivesANewContext() {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String prefix="background-choice-"+UUID.randomUUID()+"-";
        try{
            Context first=isolated(target,prefix),reopened=isolated(target,prefix);
            assertTrue(BackgroundSyncPreferences.enabled(first));
            assertTrue(BackgroundSyncPreferences.set(first,false));
            assertFalse(BackgroundSyncPreferences.enabled(reopened));
            assertTrue(BackgroundSyncPreferences.set(reopened,true));
            assertTrue(BackgroundSyncPreferences.enabled(first));
        }finally{assertTrue(target.deleteSharedPreferences(prefix+BackgroundSyncPreferences.STORE));}
    }
    @Test public void offRejectsSchedulerAndStaleWorkerWithoutOpeningPeerState()throws Exception{
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String prefix="background-stale-worker-"+UUID.randomUUID()+"-";
        Context context=isolated(target,prefix);ExecutorService executor=Executors.newSingleThreadExecutor();
        WorkManager manager=WorkManager.getInstance(target);boolean ownsWork=false;
        try{
            assertTrue(BackgroundSyncPreferences.set(context,false));
            for(String name:List.of(RemoteScheduler.PERIODIC,RemoteScheduler.IMMEDIATE))
                for(WorkInfo work:manager.getWorkInfosForUniqueWork(name).get(5,TimeUnit.SECONDS))
                    org.junit.Assume.assumeTrue("Preserve an incumbent peer worker",work.getState().isFinished());
            // Model durable jobs restored after process/boot while a saved opt-out is already present.
            // A long delay prevents these owned stale jobs from entering the application endpoint.
            OneTimeWorkRequest immediate=new OneTimeWorkRequest.Builder(RemoteSyncWorker.class).setInitialDelay(1,TimeUnit.DAYS).build();
            PeriodicWorkRequest periodic=new PeriodicWorkRequest.Builder(RemoteSyncWorker.class,15,TimeUnit.MINUTES).setInitialDelay(1,TimeUnit.DAYS).build();
            ownsWork=true;
            manager.enqueueUniqueWork(RemoteScheduler.IMMEDIATE,ExistingWorkPolicy.KEEP,immediate).getResult().get(5,TimeUnit.SECONDS);
            manager.enqueueUniquePeriodicWork(RemoteScheduler.PERIODIC,ExistingPeriodicWorkPolicy.KEEP,periodic).getResult().get(5,TimeUnit.SECONDS);
            assertEquals(WorkInfo.State.ENQUEUED,manager.getWorkInfoById(immediate.getId()).get(5,TimeUnit.SECONDS).getState());
            assertEquals(WorkInfo.State.ENQUEUED,manager.getWorkInfoById(periodic.getId()).get(5,TimeUnit.SECONDS).getState());
            Set<UUID> before=workIds(manager);
            RemoteScheduler.synchronize(context,true);RemoteScheduler.kick(context);
            awaitCancelled(manager,immediate.getId());awaitCancelled(manager,periodic.getId());
            assertEquals(before,workIds(manager));
            java.lang.reflect.Field singleton=RemoteFeature.class.getDeclaredField("instance");singleton.setAccessible(true);
            Object original; synchronized(RemoteFeature.class){original=singleton.get(null);}
            RemoteSyncWorker worker=TestWorkerBuilder.from(context,RemoteSyncWorker.class,executor).build();
            assertEquals(ListenableWorker.Result.success(),worker.doWork());
            assertTrue(context.getSharedPreferences("subhub_peer_worker",0).getAll().isEmpty());
            synchronized(RemoteFeature.class){assertSame(original,singleton.get(null));}
        }finally{
            List<Throwable> failures=new ArrayList<>();
            executor.shutdownNow();
            try{assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}catch(Throwable failure){failures.add(failure);}
            if(ownsWork)for(String name:List.of(RemoteScheduler.PERIODIC,RemoteScheduler.IMMEDIATE))
                try{manager.cancelUniqueWork(name).getResult().get(5,TimeUnit.SECONDS);}catch(Throwable failure){failures.add(failure);}
            try{assertTrue(target.deleteSharedPreferences(prefix+BackgroundSyncPreferences.STORE));}catch(Throwable failure){failures.add(failure);}
            try{target.deleteSharedPreferences(prefix+"subhub_peer_worker");}catch(Throwable failure){failures.add(failure);}
            org.junit.runners.model.MultipleFailureException.assertEmpty(failures);
        }
    }
    private static void awaitCancelled(WorkManager manager,UUID id)throws Exception{
        long deadline=android.os.SystemClock.elapsedRealtime()+5000;
        while(manager.getWorkInfoById(id).get(5,TimeUnit.SECONDS).getState()!=WorkInfo.State.CANCELLED&&android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(20);
        assertEquals(WorkInfo.State.CANCELLED,manager.getWorkInfoById(id).get(5,TimeUnit.SECONDS).getState());
    }
    private static Set<UUID> workIds(WorkManager manager)throws Exception{
        Set<UUID> result=new HashSet<>();
        for(String name:List.of(RemoteScheduler.PERIODIC,RemoteScheduler.IMMEDIATE))
            for(WorkInfo work:manager.getWorkInfosForUniqueWork(name).get(5,TimeUnit.SECONDS))result.add(work.getId());
        return result;
    }
    private static Context isolated(Context target,String prefix){
        return new ContextWrapper(target){
            @Override public Context getApplicationContext(){return this;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode){return target.getSharedPreferences(prefix+name,mode);}
        };
    }
}
