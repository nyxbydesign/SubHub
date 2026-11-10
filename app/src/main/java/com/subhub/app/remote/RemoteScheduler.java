package com.subhub.app.remote;

import android.content.Context;
import androidx.work.*;
import java.util.concurrent.TimeUnit;

/** Opportunistic network work has no exact cadence or offline mailbox. */
public final class RemoteScheduler {
    static final String PERIODIC="subhub-peer-periodic-v1",IMMEDIATE="subhub-peer-immediate-v1";
    private RemoteScheduler(){}
    private static Constraints constraints(){return new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();}
    public static void synchronize(Context context,boolean needed) {
        synchronized(BackgroundSyncPreferences.LOCK){
        WorkManager manager=WorkManager.getInstance(context);
        if(!needed||!BackgroundSyncPreferences.enabled(context)){manager.cancelUniqueWork(PERIODIC);manager.cancelUniqueWork(IMMEDIATE);return;}
        PeriodicWorkRequest request=new PeriodicWorkRequest.Builder(RemoteSyncWorker.class,15,TimeUnit.MINUTES)
                .setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build();
        manager.enqueueUniquePeriodicWork(PERIODIC,ExistingPeriodicWorkPolicy.KEEP,request);
        }
    }
    static OneTimeWorkRequest immediateRequest() {
        OneTimeWorkRequest.Builder request=new OneTimeWorkRequest.Builder(RemoteSyncWorker.class).setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS);
        // Android 12 schedules expedited jobs without WorkManager's pre-12 foreground-service fallback.
        if(android.os.Build.VERSION.SDK_INT>=31)request.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST);
        return request.build();
    }
    public static void kick(Context context) {
        synchronized(BackgroundSyncPreferences.LOCK){
        if(!BackgroundSyncPreferences.enabled(context))return;
        OneTimeWorkRequest request=immediateRequest();
        WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE,ExistingWorkPolicy.KEEP,request);
        }
    }
}
