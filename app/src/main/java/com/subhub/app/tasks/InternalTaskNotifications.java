package com.subhub.app.tasks;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import com.subhub.app.R;
import com.subhub.app.notifications.NotificationText;
import com.subhub.app.notifications.OptionalNotificationDispatch;
import com.subhub.app.notifications.OptionalNotificationPresentation;
import com.subhub.app.people.DummySubPreview;
import static com.subhub.app.notifications.NotificationPreferences.*;

/** Internal notice presentation only. Never consumes the physical/peer notification ledger. */
class InternalTaskNotifications {
    private final Context context;
    InternalTaskNotifications(Context context) { this.context = context.getApplicationContext(); }
    InternalTaskEffectDrain.Notice prepare(TaskNoticeLedger.Audience audience) {
        synchronized (DELIVERY_LOCK) {
            boolean review = audience == TaskNoticeLedger.Audience.DOM_REVIEW;
            if (!internalTaskEnabled(context, review ? Role.DOM : Role.SUB, review ? Event.TASK_REVIEW : Event.TASKS))
                return InternalTaskEffectDrain.Notice.SKIP;
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                return InternalTaskEffectDrain.Notice.SKIP;
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null) return InternalTaskEffectDrain.Notice.RETRY;
            if (!manager.areNotificationsEnabled()) return InternalTaskEffectDrain.Notice.SKIP;
            manager.createNotificationChannel(new NotificationChannel("tasks", context.getString(R.string.notification_tasks), NotificationManager.IMPORTANCE_DEFAULT));
            NotificationChannel channel = manager.getNotificationChannel("tasks");
            if (channel == null) return InternalTaskEffectDrain.Notice.RETRY;
            return channel.getImportance() == NotificationManager.IMPORTANCE_NONE
                    ? InternalTaskEffectDrain.Notice.SKIP : InternalTaskEffectDrain.Notice.READY;
        }
    }
    boolean post(OptionalNotificationDispatch.InternalTaskOwner owner, TaskStore.Effect effect,
            TaskEngine engine, TaskNoticeLedger.Receipt receipt) {
        synchronized (DELIVERY_LOCK) {
            if (prepare(receipt.audience) != InternalTaskEffectDrain.Notice.READY) return false;
            boolean review = receipt.audience == TaskNoticeLedger.Audience.DOM_REVIEW;
            TaskDefinition task = effect.action.definition;
            if (task == null) task = engine.occurrence(effect.action.taskId, effect.action.date, effect.action.at).task;
            java.time.LocalDate date = effect.action.date == null ? task.firstStart.toLocalDate() : effect.action.date;
            java.time.format.DateTimeFormatter format = java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT)
                    .withLocale(context.getResources().getConfiguration().getLocales().get(0)).withZone(task.zone);
            String title = NotificationText.clean(task.title);
            String details = review ? context.getString(R.string.notification_task_review_detail, context.getString(R.string.dummy_sub_title), title)
                    : effect.action.kind == TaskAction.Kind.CANCEL_SERIES ? context.getString(R.string.notification_task_series_cancelled, title)
                    : context.getString(R.string.notification_task_schedule, title, format.format(task.start(date)), format.format(task.end(date))) + " · " + task.zone.getId();
            Intent intent = DummySubPreview.taskNotificationIntent(context, owner.generation)
                    .setData(new Uri.Builder().scheme("subhub").authority("internal-task-notice")
                            .appendPath(owner.generation).appendPath(receipt.effectKey).build());
            PendingIntent destination = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification notice = new Notification.Builder(context, "tasks").setSmallIcon(R.drawable.ic_notification_subhub)
                    .setColor(context.getColor(R.color.accent)).setContentTitle(context.getString(review ? R.string.tasks_completion_notification : TaskNotifications.title(effect)))
                    .setContentText(title).setStyle(new Notification.BigTextStyle().bigText(details)).setContentIntent(destination)
                    .setGroup("subhub.tasks:internal:" + owner.generation).setWhen(effect.action.at).setShowWhen(true)
                    .setAutoCancel(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_REMINDER).setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setPublicVersion(new Notification.Builder(context, "tasks").setSmallIcon(R.drawable.ic_notification_subhub)
                            .setContentTitle(context.getString(R.string.app_name)).setContentText(context.getString(R.string.privacy_notification_body)).build()).build();
            return OptionalNotificationDispatch.postInternalTask(context, review ? Role.DOM : Role.SUB, review ? Event.TASK_REVIEW : Event.TASKS,
                    owner, receipt.effectKey, OptionalNotificationPresentation.present(context, notice));
        }
    }
    void cancel(TaskNoticeLedger.Receipt receipt) {
        if (!OptionalNotificationDispatch.cancelInternalTaskReceipt(context, receipt))
            throw new IllegalStateException("Internal notice cancellation was not acknowledged");
    }
}
