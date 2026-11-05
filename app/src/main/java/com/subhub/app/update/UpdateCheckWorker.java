package com.subhub.app.update;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Performs a release check and prepares a verified APK without interrupting the user. */
public final class UpdateCheckWorker extends Worker {
    public UpdateCheckWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull @Override public Result doWork() {
        if (!new UpdateStateStore(getApplicationContext()).automaticChecks()) return Result.success();
        GitHubReleaseRepository.Result result =
                new GitHubReleaseRepository(getApplicationContext()).check();
        if (result.succeeded()) {
            if (result.candidate != null) new UpdateDownloadCoordinator(getApplicationContext())
                    .prepare(result.candidate, true);
            return Result.success();
        }
        return result.failure == GitHubReleaseRepository.Failure.OFFLINE
                || result.failure == GitHubReleaseRepository.Failure.SERVER
                ? Result.retry() : Result.success();
    }
}
