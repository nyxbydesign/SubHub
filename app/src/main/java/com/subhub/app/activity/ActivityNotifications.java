package com.subhub.app.activity;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import com.subhub.app.R;
import com.subhub.app.people.PersonId;

/** Stable OS identities make replayed delivery update the same notification silently. */
public final class ActivityNotifications {
    public static final String CHANNEL = "sub_activity";
    private static final int ID = 1;
    private static final String PREFIX = "subhub.activity:";

    private ActivityNotifications() {}

    public static boolean enabled(Context context) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return false;
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    /** Called only after the receiver durably accepts and revalidates a new eligible event. */
    public static boolean show(Context context, ActivityEntry entry) {
        if (!entry.unread || !com.subhub.app.security.DeviceRolePreferences.supportsDom(context) || !enabled(context)) return false;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return false;
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                context.getString(R.string.dom_activity_channel), NotificationManager.IMPORTANCE_DEFAULT));
        Intent open = new Intent(context, DomActivityActivity.class)
                .setData(new Uri.Builder().scheme("subhub").authority("activity").appendPath(entry.key).build())
                .putExtra(DomActivityActivity.EXTRA_ENTRY_KEY, entry.key)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent destination = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder publicVersion = new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification_subhub).setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.dom_activity_new_activity));
        NotificationCompat.Builder notification = new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification_subhub)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.dom_activity_new_activity))
                .setWhen(entry.event.observedAt).setShowWhen(true).setContentIntent(destination)
                .setAutoCancel(true).setOnlyAlertOnce(true).setGroup(peerPrefix(entry.personId))
                .setCategory(NotificationCompat.CATEGORY_SOCIAL).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion.build());
        try { manager.notify(tag(entry), ID, notification.build()); return true; }
        catch (SecurityException denied) { return false; }
    }

    public static void cancel(Context context, ActivityEntry entry) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(tag(entry), ID);
    }

    public static void cancelPeer(Context context, PersonId person) { cancelPrefix(context, peerPrefix(person)); }
    public static void cancelAll(Context context) { cancelPrefix(context, PREFIX); }

    private static void cancelPrefix(Context context, String prefix) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        for (android.service.notification.StatusBarNotification notification : manager.getActiveNotifications())
            if (notification.getTag() != null && notification.getTag().startsWith(prefix))
                manager.cancel(notification.getTag(), notification.getId());
    }

    private static String peerPrefix(PersonId person) { return PREFIX + Uri.encode(person.value()) + ":"; }
    private static String tag(ActivityEntry entry) { return peerPrefix(entry.personId) + entry.key; }
}
