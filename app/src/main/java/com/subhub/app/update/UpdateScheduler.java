package com.subhub.app.update;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** Owns the periodic background checks and minute checks while an activity is visible. */
public final class UpdateScheduler {
    public static final long INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(15);
    static final long DEV_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(15);
    static final String PERIODIC_NAME = "subhub-release-check";
    static final String IMMEDIATE_NAME = "subhub-release-check-now";

    public static final long FOREGROUND_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(1);
    private static boolean installed;

    private UpdateScheduler() {}

    /** Activity visibility owns the timer; no alarm or background polling loop is used. */
    public static synchronized void install(android.app.Application application) {
        if (installed) return;
        installed = true;
        application.registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
            private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            private int started;
            private final Runnable tick = new Runnable() {
                @Override public void run() {
                    if (started == 0) return;
                    UpdateStateStore state = new UpdateStateStore(application);
                    if (state.automaticChecks() && checkDue(System.currentTimeMillis(), state.lastCheck(), state.devUpdates())) {
                        checkNow(application);
                    }
                    main.postDelayed(this, FOREGROUND_INTERVAL_MILLIS);
                }
            };
            @Override public void onActivityStarted(android.app.Activity activity) {
                if (started++ == 0) main.post(tick);
            }
            @Override public void onActivityStopped(android.app.Activity activity) {
                if (--started == 0) main.removeCallbacks(tick);
            }
            @Override public void onActivityCreated(android.app.Activity activity, android.os.Bundle state) {}
            @Override public void onActivityResumed(android.app.Activity activity) {}
            @Override public void onActivityPaused(android.app.Activity activity) {}
            @Override public void onActivitySaveInstanceState(android.app.Activity activity, android.os.Bundle state) {}
            @Override public void onActivityDestroyed(android.app.Activity activity) {}
        });
    }

    public static void synchronize(Context context) {
        synchronize(context, true);
    }

    /** A channel toggle already performs its own foreground check. */
    static void synchronize(Context context, boolean checkIfStale) {
        UpdateStateStore state = new UpdateStateStore(context);
        WorkManager manager = WorkManager.getInstance(context);
        if (!state.automaticChecks()) {
            manager.cancelUniqueWork(PERIODIC_NAME);
            manager.cancelUniqueWork(IMMEDIATE_NAME);
            return;
        }
        Constraints network = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest periodic = new PeriodicWorkRequest.Builder(
                UpdateCheckWorker.class, intervalMillis(state.devUpdates()), TimeUnit.MILLISECONDS)
                .setConstraints(network).build();
        manager.enqueueUniquePeriodicWork(PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.UPDATE, periodic);
        if (checkIfStale && checkDue(System.currentTimeMillis(), state.lastCheck(), state.devUpdates())) {
            checkNow(context);
        }
    }

    static long intervalMillis(boolean developmentChannel) {
        return developmentChannel ? DEV_INTERVAL_MILLIS : INTERVAL_MILLIS;
    }

    static boolean checkDue(long nowMillis, long lastCheckMillis, boolean developmentChannel) {
        return lastCheckMillis <= 0 || nowMillis < lastCheckMillis
                || nowMillis - lastCheckMillis >= FOREGROUND_INTERVAL_MILLIS;
    }

    public static void checkNow(Context context) {
        Constraints network = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(UpdateCheckWorker.class)
                .setConstraints(network).build();
        WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_NAME, ExistingWorkPolicy.KEEP, request);
    }
}
