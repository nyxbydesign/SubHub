package com.subhub.app.tasks;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import com.subhub.app.internal.InternalSubRecord;
import com.subhub.app.notifications.OptionalNotificationDispatch;
import com.subhub.app.people.InternalSubData;
import com.subhub.app.stats.OwnerObservation;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Protection-independent host. Only broker admission, never installation, authorizes Tasks work. */
public final class InternalTaskRuntime {
    static final String GENERATION = "internal_task_generation";
    private static final Map<Context, Installation> INSTALLATIONS = new IdentityHashMap<>();
    private static final ScheduledExecutorService CLOCK = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "subhub-internal-task-clock"));
    private static final class Installation {
        final Application application;
        final java.util.Set<Activity> started = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        InternalTaskRuntime runtime;
        Installation(Application application) { this.application = application; }
    }
    /** Memory-only registration. Existing-profile discovery and all persisted reads run off main. */
    public static void install(Application application) {
        Installation installation;
        synchronized (INSTALLATIONS) {
            if (INSTALLATIONS.containsKey(application)) return;
            installation = new Installation(application); INSTALLATIONS.put(application, installation);
        }
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityStarted(Activity activity) {
                synchronized (INSTALLATIONS) {
                    if (installation.started.add(activity) && installation.runtime != null)
                        installation.runtime.lifecycle(true);
                }
            }
            public void onActivityStopped(Activity activity) {
                synchronized (INSTALLATIONS) {
                    if (installation.started.remove(activity) && installation.runtime != null)
                        installation.runtime.lifecycle(false);
                }
            }
            public void onActivityCreated(Activity a, Bundle b) { }
            public void onActivityResumed(Activity a) { }
            public void onActivityPaused(Activity a) { }
            public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            public void onActivityDestroyed(Activity a) { }
        });
        CLOCK.execute(() -> {
            try {
                // Never run migration/provisioning from an Application, service or scheduler callback.
                if (InternalSubData.provisioned(application)) ownerReady(application, InternalSubData.get(application));
            } catch (RuntimeException unavailable) { deferred(); }
        });
    }
    /** Called only after the chosen singleton and its existing owner-data handle acknowledge READY. */
    public static void ownerReady(Context physical, InternalSubData data) {
        if (!data.ownerDataReady()) return;
        Context app = physical.getApplicationContext();
        synchronized (INSTALLATIONS) {
            Installation installation = INSTALLATIONS.get(app);
            if (installation == null) return; // Owned fixtures do not install an application-global host.
            if (installation.runtime != null) {
                if (installation.runtime.data != data) throw new IllegalStateException("Internal Tasks host identity changed");
                return;
            }
            installation.runtime = new InternalTaskRuntime(app, data, installation.started.size());
        }
    }
    public static void changed(Context physical, InternalSubData data, String generation) {
        InternalTaskRuntime runtime = peek(physical);
        if (runtime != null && runtime.data == data) runtime.request(generation);
    }
    private static InternalTaskRuntime peek(Context physical) {
        synchronized (INSTALLATIONS) {
            Installation installation = INSTALLATIONS.get(physical.getApplicationContext());
            return installation == null ? null : installation.runtime;
        }
    }
    static CompletableFuture<Void> background(Context physical, String expected) {
        if (expected == null) throw new IllegalArgumentException("Missing internal Tasks generation");
        if (!InternalSubData.provisioned(physical)) return CompletableFuture.completedFuture(null);
        InternalSubData data = InternalSubData.get(physical);
        ownerReady(physical, data);
        InternalTaskRuntime runtime = peek(physical);
        if (runtime == null) throw new IllegalStateException("Internal Tasks host is unavailable");
        // A superseded periodic invocation is harmless. It never follows a newer generation.
        if (!runtime.current(expected)) return CompletableFuture.completedFuture(null);
        return runtime.request(expected);
    }
    private final Context physical;
    private final InternalSubData data;
    private final InternalTaskScheduler scheduler;
    private final InternalTaskNotifications notifications;
    private final Map<String, OptionalNotificationDispatch.InternalTaskOwner> owners = new java.util.HashMap<>();
    private String scheduledGeneration;
    private CompletableFuture<Void> pending;
    private String pendingGeneration;
    private boolean retryScheduled;
    private InternalTaskRuntime(Context physical, InternalSubData data, int started) {
        this.physical = physical; this.data = data; notifications = new InternalTaskNotifications(physical);
        scheduler = new InternalTaskScheduler(this::requireOwner, new InternalTaskScheduler.Scheduling() {
            public InternalTaskScheduler.Ticket foreground(Runnable wake, long delay) {
                java.util.concurrent.ScheduledFuture<?> ticket = CLOCK.scheduleWithFixedDelay(wake, 0, delay, TimeUnit.MILLISECONDS);
                return () -> ticket.cancel(false);
            }
            public void background(String name, String generation, long minutes) {
                if (generation.equals(scheduledGeneration)) return;
                awaitSchedule(WorkManager.getInstance(physical).enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE,
                        new PeriodicWorkRequest.Builder(InternalTaskWorker.class, minutes, TimeUnit.MINUTES)
                                .setInputData(new Data.Builder().putString(GENERATION, generation).build()).build()));
                scheduledGeneration = generation;
            }
            public void cancelBackground(String name) {
                awaitSchedule(WorkManager.getInstance(physical).cancelUniqueWork(name)); scheduledGeneration = null;
            }
        }, this::request, generation -> {
            OptionalNotificationDispatch.InternalTaskOwner owner = owners.get(generation);
            if (owner != null) {
                if (!OptionalNotificationDispatch.cancelInternalTasks(physical, owner))
                    throw new IllegalStateException("Internal notice retirement is pending");
                owners.remove(generation);
            }
        });
        data.store.addListener((previous, ignored) -> {
            // The monotonic acknowledged cache invalidates notification predicates immediately.
            // Listener payloads can arrive out of order; only the worker's current record is used.
            data.execute(this::reconcile);
        });
        data.execute(() -> {
            for (int i = 0; i < started; i++) scheduler.activityStarted();
            reconcile();
        });
    }
    private void requireOwner() {
        if (!data.isOwnerThread()) throw new IllegalStateException("Internal Tasks require the owner worker");
    }
    private boolean current(String generation) {
        InternalSubRecord record = data.acknowledged();
        return data.ownerDataReady() && record.visible && record.tasksResumeAfterMillis > 0 && record.generation.equals(generation);
    }
    private void lifecycle(boolean started) {
        data.execute(() -> {
            try { if (started) scheduler.activityStarted(); else scheduler.activityStopped(); }
            catch (RuntimeException unavailable) { retry(); }
        });
    }
    private void reconcile() {
        requireOwner();
        try {
            InternalSubRecord record = data.acknowledged();
            if (record.visible && record.tasksResumeAfterMillis <= 0)
                throw new IllegalStateException("Internal Tasks resume boundary is unavailable");
            try { scheduler.reconcile(record.visible ? record.generation : null); }
            catch (RuntimeException unavailable) { retry(); }
            if (record.visible) scheduler.changed(record.generation);
            cleanupRetired().whenComplete((ignored, failure) -> { if (failure != null) retry(); });
        } catch (RuntimeException unavailable) { retry(); }
    }
    private CompletableFuture<Void> cleanupRetired() {
        return data.ownerData().submitRetiredTaskNotices(access -> {
            for (TaskNoticeLedger.Receipt receipt : access.list()) {
                notifications.cancel(receipt);
                access.removeAfterCancellation(receipt);
            }
            return null;
        });
    }
    private synchronized CompletableFuture<Void> request(String generation) {
        if (!current(generation)) return CompletableFuture.completedFuture(null);
        if (pending != null && generation.equals(pendingGeneration)) return pending;
        OwnerObservation observed = OwnerObservation.capture(System.currentTimeMillis());
        CompletableFuture<Void> result;
        try { result = InternalTaskWorkOrder.submit(() -> data.ownerData().submitTask(generation, access -> {
            requireOwner();
            OptionalNotificationDispatch.InternalTaskOwner owner = owners.computeIfAbsent(generation,
                    key -> new OptionalNotificationDispatch.InternalTaskOwner(key, () -> current(key)));
            try {
                InternalTaskBackend backend = InternalTaskBackend.get(physical, data);
                backend.sweepAdmitted(access, observed.at);
                backend.effectsAdmitted(access, observed, owner, notifications);
                return null;
            } catch (RuntimeException unavailable) { throw unavailable; }
            catch (Exception unavailable) { throw new IllegalStateException("Internal Tasks update failed", unavailable); }
        }), this::cleanupRetired, this::retry); }
        catch (RuntimeException unavailable) {
            retry(); CompletableFuture<Void> failed = new CompletableFuture<>(); failed.completeExceptionally(unavailable); return failed;
        }
        pending = result; pendingGeneration = generation;
        result.whenComplete((ignored, failure) -> {
            synchronized (InternalTaskRuntime.this) { if (pending == result) { pending = null; pendingGeneration = null; } }
            if (failure != null) retry();
        });
        return result;
    }
    private synchronized void retry() {
        deferred();
        if (retryScheduled) return;
        retryScheduled = true;
        CLOCK.schedule(() -> {
            synchronized (InternalTaskRuntime.this) { retryScheduled = false; }
            data.execute(this::reconcile);
        }, InternalTaskScheduler.FOREGROUND_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }
    private static void awaitSchedule(androidx.work.Operation operation) {
        try { operation.getResult().get(30, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Internal Tasks scheduling interrupted", interrupted); }
        catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException unavailable) {
            throw new IllegalStateException("Internal Tasks scheduling was not acknowledged", unavailable);
        }
    }
    private static void deferred() { android.util.Log.w("InternalTasks", "Internal task update deferred"); }
}
