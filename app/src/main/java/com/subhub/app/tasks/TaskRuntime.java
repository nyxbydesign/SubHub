package com.subhub.app.tasks;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.*;
import com.subhub.app.R;
import com.subhub.app.people.*;
import com.subhub.app.remote.*;
import com.subhub.app.security.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Tasks wake only their own ledger and peer sync; Android background deadlines are best effort. */
public final class TaskRuntime {
    private static TaskRuntime instance;
    public static synchronized TaskRuntime get(Context context) {
        if (instance == null) instance = new TaskRuntime(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    public final TaskStore store;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "subhub-tasks"));
    private volatile boolean installed, foreground;
    private TaskRuntime(Context context) { this.context = context; store = new TaskStore(context); }
    public RemoteAssets assets(String scope) throws Exception {
        if (!scope.matches("local|[0-9a-f]{64}_[A-Za-z0-9_-]{1,128}")) throw new SecurityException("Invalid Tasks scope");
        return new RemoteAssets(new File(context.getFilesDir(), "tasks-media/" + scope));
    }
    public synchronized void install(Application application) {
        if (installed) return; installed = true;
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            private int started;
            @Override public void onActivityStarted(Activity activity) { if (++started == 1) { foreground = true; executor.execute(TaskRuntime.this::tick); } }
            @Override public void onActivityStopped(Activity activity) { if (--started == 0) foreground = false; }
            @Override public void onActivityCreated(Activity a, android.os.Bundle b) { }
            @Override public void onActivityResumed(Activity a) { }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, android.os.Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
        executor.scheduleWithFixedDelay(() -> { if (foreground) tick(); }, 30, 30, TimeUnit.SECONDS);
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("subhub-task-deadlines", ExistingPeriodicWorkPolicy.KEEP,
                new PeriodicWorkRequest.Builder(TaskWorker.class, 15, TimeUnit.MINUTES).build());
    }
    public void changed() {
        executor.execute(this::tick);
        RemoteFeature.get(context).changed();
    }
    public void tick() {
        try {
            RemoteFeature remote = RemoteFeature.get(context);
            boolean controlled = remote.store.isRemotelyControlled();
            if (!controlled && DeviceRolePreferences.supportsSub(context)) sweep(new TaskRepository.Target("local", null));
            for (RemoteStore.Peer peer : remote.store.peers(false)) if (peer.state == RemoteStore.State.ACTIVE)
                sweep(new TaskRepository.Target(peer.identity + "_" + peer.generation, peer));
        } catch (Exception unavailable) { android.util.Log.w("Tasks", "Task update deferred"); }
    }
    public void requireActive(TaskRepository.Target target) {
        if (target.peer == null) {
            if (RemoteFeature.get(context).store.isRemotelyControlled()) throw new SecurityException("Local task authority changed");
        } else {
            RemoteStore.Peer live = RemoteFeature.get(context).store.peer(target.peer.identity, target.peer.generation);
            if (live == null || live.state != RemoteStore.State.ACTIVE || live.localRole != target.peer.localRole)
                throw new SecurityException("Task pairing is no longer active");
        }
    }
    public RemoteStore authorityLock() { return RemoteFeature.get(context).store; }
    public void sweep(TaskRepository.Target target) throws Exception {
        synchronized (SubFeaturePolicy.LOCK) {
            synchronized (authorityLock()) {
                requireActive(target);
                if (!enabled(target)) return;
                if (target.peer == null || target.peer.localRole == RemoteProtocol.Role.SUB) {
                    long now = System.currentTimeMillis(); TaskEngine engine = store.load(target.scope); int count = 0;
                    long resumeAfter = context.getSharedPreferences(SubFeaturePolicy.STORE, 0).getLong("tasks_resume_after", 0);
                    for (TaskDefinition task : engine.definitions()) {
                        if (engine.seriesRefused(task.id)) continue;
                        LocalDate today = Instant.ofEpochMilli(now).atZone(task.zone).toLocalDate();
                        for (LocalDate date = task.nextDate(task.firstStart.toLocalDate()); date != null && !date.isAfter(today);
                                date = task.nextDate(date.plusDays(1))) {
                            if (task.end(date).toEpochMilli() > now) continue;
                            TaskEngine.Occurrence row = engine.occurrence(task.id, date, now);
                            if (row.status != TaskEngine.Status.ACTIVE) continue;
                            requireActive(target);
                            String id = "timeout_" + UUID.nameUUIDFromBytes((target.scope + ":" + row.id).getBytes(StandardCharsets.UTF_8));
                            TaskAction.Kind kind = task.end(date).toEpochMilli() <= resumeAfter ? TaskAction.Kind.SKIP : TaskAction.Kind.TIMEOUT;
                            TaskAction action = new TaskAction(id, task.id, kind, date, now, "", "", List.of(), null);
                            store.append(target.scope, action, false, false); engine.apply(action, false);
                            if (++count == 64) break;
                        }
                        if (count == 64) break;
                    }
                }
                effects(target);
            }
        }
    }
    public void effects(TaskRepository.Target target) throws Exception {
        synchronized (SubFeaturePolicy.LOCK) {
            synchronized (authorityLock()) {
                requireActive(target);
                if (!enabled(target)) return;
                for (TaskStore.Effect effect : store.pending(target.scope)) {
                    requireActive(target);
                    if ("notification".equals(effect.kind)) {
                        if (target.peer != null && target.peer.localRole != RemoteProtocol.Role.DOM) { store.done(target.scope, effect); continue; }
                        if (!notifyDom(target, effect)) continue;
                    } else {
                        if (target.peer != null && target.peer.localRole != RemoteProtocol.Role.SUB) { store.done(target.scope, effect); continue; }
                        TaskEngine.Occurrence row = store.load(target.scope).occurrence(effect.action.taskId, effect.action.date, System.currentTimeMillis());
                        long resumeAfter = context.getSharedPreferences(SubFeaturePolicy.STORE, 0).getLong("tasks_resume_after", 0);
                        if (row.failed() && row.task.failureTribute && effect.action.at > resumeAfter)
                            new com.subhub.app.penance.PenanceManager(context).recordTaskFailure(target.scope + ":" + effect.id, System.currentTimeMillis());
                    }
                    store.done(target.scope, effect);
                }
            }
        }
    }
    public boolean enabled(TaskRepository.Target target) {
        return target.peer != null && target.peer.localRole == RemoteProtocol.Role.DOM
                || SubFeaturePolicy.allowed(context, "tasks");
    }
    private boolean notifyDom(TaskRepository.Target target, TaskStore.Effect effect) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManagerCompat notifications = NotificationManagerCompat.from(context);
        if (!notifications.areNotificationsEnabled()) return false;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("tasks", "Tasks", NotificationManager.IMPORTANCE_DEFAULT));
        PersonId person = target.peer == null ? PersonId.LOCAL : RemotePersonRepository.id(target.peer);
        Intent intent = new Intent(context, TasksActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID, person.value());
        intent.setData(android.net.Uri.parse("subhub://tasks/" + effect.action.id));
        PendingIntent pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        notifications.notify("tasks:" + target.scope + ":" + effect.id, 1,
                new NotificationCompat.Builder(context, "tasks").setSmallIcon(R.drawable.ic_nav_contract)
                        .setContentTitle(context.getString(R.string.tasks_completion_notification))
                        .setContentText(context.getString(R.string.tasks_review_notification))
                        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOnlyAlertOnce(true).setAutoCancel(true)
                        .setContentIntent(pending).build());
        return true;
    }
}
