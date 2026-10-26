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
        WorkManager manager=WorkManager.getInstance(context);
        if(!needed){manager.cancelUniqueWork(PERIODIC);manager.cancelUniqueWork(IMMEDIATE);return;}
        PeriodicWorkRequest request=new PeriodicWorkRequest.Builder(RemoteSyncWorker.class,15,TimeUnit.MINUTES)
                .setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build();
        manager.enqueueUniquePeriodicWork(PERIODIC,ExistingPeriodicWorkPolicy.KEEP,request);
    }
    public static void kick(Context context) {
        OneTimeWorkRequest request=new OneTimeWorkRequest.Builder(RemoteSyncWorker.class).setConstraints(constraints())
                .setInitialDelay(2,TimeUnit.SECONDS).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build();
        WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE,ExistingWorkPolicy.KEEP,request);
    }
}
