package com.subhub.app.remote;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;

public final class RemoteSyncWorker extends Worker {
    public RemoteSyncWorker(@NonNull Context context,@NonNull WorkerParameters parameters){super(context,parameters);}
    @NonNull @Override public Result doWork() {
        android.content.SharedPreferences receipt=getApplicationContext().getSharedPreferences("subhub_peer_worker",0);
        receipt.edit().putLong("last_started",System.currentTimeMillis()).putString("last_work_id",getId().toString()).commit();
        try{RemoteFeature feature=RemoteFeature.get(getApplicationContext());
            boolean complete=feature.hasConnectedService()||feature.backgroundWindow(55_000,this::isStopped);
            receipt.edit().putLong("last_finished",System.currentTimeMillis()).putBoolean("last_complete",complete).commit();
            return complete?Result.success():retryResult();}
        catch(InterruptedException stopped){Thread.currentThread().interrupt();return retryResult();}
        catch(Exception unavailable){return retryResult();}
    }
    private Result retryResult(){return getRunAttemptCount()<3&&!isStopped()?Result.retry():Result.failure();}
}
