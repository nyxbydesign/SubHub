package com.subhub.app.tasks;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public final class TaskWorker extends Worker {
    public TaskWorker(@NonNull Context context, @NonNull WorkerParameters parameters) { super(context, parameters); }
    @NonNull @Override public Result doWork() {
        TaskRuntime.get(getApplicationContext()).tick();
        return Result.success();
    }
}
