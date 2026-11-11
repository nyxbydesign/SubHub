package com.subhub.app.remote;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import com.subhub.app.R;
import com.subhub.app.appmode.ExtraTimeActivity;
import com.subhub.app.notifications.NotificationPreferences;
import com.subhub.app.notifications.OptionalNotificationDispatch;
import com.subhub.app.people.PersonId;

/** Optional notices open the same Overview or Dom review route as the app. */
public final class ExtraTimeNotifications {
    private ExtraTimeNotifications() { }
    public static void reached(Context context, String packageName) {
        post(context, PersonId.LOCAL, packageName, "limit:" + packageName,
                context.getString(R.string.extra_time_limit_title), context.getString(R.string.extra_time_limit_body),
                false);
    }
    static void request(Context context, RemoteStore.Peer peer, ExtraTimeStore.Row row) {
        post(context, RemotePersonRepository.id(peer), row.packageName, "request:" + row.id,
                context.getString(R.string.extra_time_request_title, peer.name),
                context.getString(R.string.extra_time_request_body, row.label), true);
    }
    static void outcome(Context context, ExtraTimeStore.Row row) {
        post(context, PersonId.LOCAL, row.packageName, "outcome:" + row.id,
                context.getString(row.state.equals("APPROVED") ? R.string.extra_time_approved_title : R.string.extra_time_declined_title),
                row.state.equals("APPROVED") ? context.getString(R.string.extra_time_approved_body, row.minutes, row.label)
                        : context.getString(R.string.extra_time_declined_body, row.label), false);
    }
    private static void post(Context context, PersonId person, String packageName, String identity,
            String title, String text, boolean dom) {
        NotificationPreferences.Role role = dom ? NotificationPreferences.Role.DOM : NotificationPreferences.Role.SUB;
        if (!NotificationPreferences.enabled(context,role,NotificationPreferences.Event.EXTRA_TIME_REQUESTS)) return;
        if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return;
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null) return;
            manager.createNotificationChannel(new NotificationChannel("app_time_requests",
                    context.getString(R.string.extra_time_notification_channel), NotificationManager.IMPORTANCE_DEFAULT));
            if (manager.getNotificationChannel("app_time_requests").getImportance() == NotificationManager.IMPORTANCE_NONE) return;
            Intent intent = ExtraTimeActivity.intent(context, person, packageName)
                    .setData(android.net.Uri.parse("subhub://app-time/" + person.value() + "/" + identity));
            PendingIntent action = PendingIntent.getActivity(context,0,intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            OptionalNotificationDispatch.post(context,role,NotificationPreferences.Event.EXTRA_TIME_REQUESTS,
                    "subhub.optional:" + NotificationPreferences.key(role,NotificationPreferences.Event.EXTRA_TIME_REQUESTS) + ":" + identity,
                    1,new NotificationCompat.Builder(context,"app_time_requests").setSmallIcon(R.drawable.ic_notification_subhub)
                            .setContentTitle(title).setContentText(text).setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                            .setContentIntent(action).setAutoCancel(true)
                            .addAction(0,context.getString(R.string.extra_time_notification_action),action).build());
        } catch (RuntimeException unavailable) { /* The persisted in-app request remains available. */ }
    }
}
