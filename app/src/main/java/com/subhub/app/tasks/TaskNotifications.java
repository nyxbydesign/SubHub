package com.subhub.app.tasks;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import com.subhub.app.R;
import com.subhub.app.notifications.*;
import com.subhub.app.people.PersonId;
import com.subhub.app.people.PersonRoutes;
import static com.subhub.app.notifications.NotificationPreferences.*;

/** Notices consume only committed, relationship-scoped task effects. */
final class TaskNotifications {
    private TaskNotifications() { }
    static int title(TaskStore.Effect effect) {
        if (effect.kind.equals("sub_started")) return R.string.notification_task_started;
        return switch (effect.action.kind) {
            case CREATE -> R.string.notification_task_new;
            case CANCEL_ONCE, CANCEL_SERIES -> R.string.notification_task_cancelled;
            case ACCEPT -> R.string.notification_task_approved;
            case DENY -> R.string.notification_task_declined;
            case TIMEOUT -> R.string.notification_task_overdue;
            default -> throw new IllegalArgumentException("Not a Sub task notice");
        };
    }
    static void deliver(Context context, TaskRepository.Target target, TaskStore.Effect effect, TaskEngine engine) {
        try { synchronized (DELIVERY_LOCK) {
            if (!PeerNotifications.consume(context, Role.SUB, Event.TASKS, target.scope + ":" + effect.kind + ":" + effect.id, 1)) return;
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null || !manager.areNotificationsEnabled()) return;
            manager.createNotificationChannel(new NotificationChannel("tasks", context.getString(R.string.notification_tasks), NotificationManager.IMPORTANCE_DEFAULT));
            if (manager.getNotificationChannel("tasks").getImportance() == NotificationManager.IMPORTANCE_NONE) return;
            TaskDefinition task = effect.action.definition;
            if (task == null) task = engine.occurrence(effect.action.taskId, effect.action.date, effect.action.at).task;
            java.time.LocalDate date = effect.action.date == null ? task.firstStart.toLocalDate() : effect.action.date;
            java.util.Locale locale = context.getResources().getConfiguration().getLocales().get(0);
            java.time.format.DateTimeFormatter format = java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT).withLocale(locale).withZone(task.zone);
            String schedule = context.getString(R.string.notification_task_schedule, NotificationText.clean(task.title),
                    format.format(task.start(date)), format.format(task.end(date))) + " · " + task.zone.getId();
            if (effect.action.kind == TaskAction.Kind.CANCEL_SERIES)
                schedule = context.getString(R.string.notification_task_series_cancelled, NotificationText.clean(task.title));
            Intent open = new Intent(context, TasksActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value())
                    .setData(new Uri.Builder().scheme("subhub").authority("task-notice").appendPath(target.scope).appendPath(effect.id).build());
            PendingIntent destination = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            android.app.Notification notice = new NotificationCompat.Builder(context, "tasks")
                    .setSmallIcon(R.drawable.ic_notification_subhub).setColor(context.getColor(R.color.accent))
                    .setContentTitle(context.getString(title(effect))).setContentText(NotificationText.clean(task.title))
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(schedule))
                    .setContentIntent(destination).setGroup("subhub.tasks:" + target.scope)
                    .setWhen(effect.action.at).setShowWhen(true).setAutoCancel(true).setOnlyAlertOnce(true)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setPublicVersion(new NotificationCompat.Builder(context, "tasks").setSmallIcon(R.drawable.ic_notification_subhub)
                            .setContentTitle(context.getString(R.string.app_name)).setContentText(context.getString(R.string.privacy_notification_body)).build()).build();
            OptionalNotificationDispatch.post(context, Role.SUB, Event.TASKS, "subhub.optional:SUB:TASKS:" + target.scope + ":" + effect.kind + ":" + effect.id,
                    2160, OptionalNotificationPresentation.present(context, notice));
        } } catch (RuntimeException unavailable) { /* A committed task must not fail because optional presentation is unavailable. */ }
    }
}
