package com.subhub.app.remote;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;

public final class RemoteSyncWorker extends Worker {
    public RemoteSyncWorker(@NonNull Context context,@NonNull WorkerParameters parameters){super(context,parameters);}
    @NonNull @Override public Result doWork() {
        android.content.SharedPreferences receipt=getApplicationContext().getSharedPreferences("subhub_peer_worker",0);
        receipt.edit().putLong("last_started",System.currentTimeMillis()).putString("last_work_id",getId().toString()).commit();
        try{boolean complete=RemoteFeature.get(getApplicationContext()).backgroundWindow();
            receipt.edit().putLong("last_finished",System.currentTimeMillis()).putBoolean("last_complete",complete).commit();
            return complete?Result.success():Result.retry();}
        catch(InterruptedException stopped){Thread.currentThread().interrupt();return Result.retry();}
        catch(Exception unavailable){return Result.retry();}
    }
}
