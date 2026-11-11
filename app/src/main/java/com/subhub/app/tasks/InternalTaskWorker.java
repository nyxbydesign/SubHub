package com.subhub.app.tasks;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** This worker never invokes physical TaskRuntime, remote synchronization or capture. */
public final class InternalTaskWorker extends Worker {
    public InternalTaskWorker(@NonNull Context context, @NonNull WorkerParameters parameters) { super(context, parameters); }
    @NonNull @Override public Result doWork() {
        try {
            InternalTaskRuntime.background(getApplicationContext(), getInputData().getString(InternalTaskRuntime.GENERATION)).get();
            return Result.success();
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return Result.retry(); }
        catch (java.util.concurrent.ExecutionException | RuntimeException unavailable) { return Result.retry(); }
    }
}
