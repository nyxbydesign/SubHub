package com.subhub.app.update;

import android.Manifest;
import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import com.subhub.app.R;

/** Quiet updater notices; DownloadManager remains responsible for the transfer. */
public final class UpdateNotifications {
    private static final String CHANNEL = "subhub_updates";
    private static final int AVAILABLE_ID = 9070;
    private static final int READY_ID = 9071;
    private static final int DOWNLOAD_ID = 9072;
    private UpdateNotifications() {}

    /** Automatic transfers are silent; only a verified APK produces a notice. */
    static int downloadVisibility(Context context) {
        return DownloadManager.Request.VISIBILITY_HIDDEN;
    }

    static void cancelDownload(Context context, long id) {
        if (id >= 0) manager(context).cancel(downloadTag(id), DOWNLOAD_ID);
    }

    static String downloadTag(long id) { return "subhub-download-" + id; }

    static void cancelReady(Context context) {
        manager(context).cancel(AVAILABLE_ID);
        manager(context).cancel(READY_ID);
    }

    public static void ready(Context context, UpdateCandidate candidate) {
        synchronized (UpdateStateStore.class) {
            UpdateStateStore state = new UpdateStateStore(context);
            String path = state.verifiedPath();
            if (!UpdateStateStore.sameCandidate(candidate, state.candidate())
                    || path.isEmpty() || !new java.io.File(path).isFile() || !canNotify(context)
                    || state.wasNotified(candidate.manifest.tag)) return;
            try {
                post(context, READY_ID, builder(context)
                        .setContentTitle(context.getString(R.string.update_ready_title, candidate.manifest.versionName))
                        .setContentText(context.getString(R.string.update_ready_body)).build());
                state.markNotified(candidate.manifest.tag);
            } catch (SecurityException permissionChanged) {
                // Keep the verified APK and allow a later check to retry notification.
            }
        }
    }

    private static void post(Context context, int id, android.app.Notification notification) {
        manager(context).notify(id, com.subhub.app.privacy.PrivacyNotifications.present(context, id, notification));
    }

    private static NotificationCompat.Builder builder(Context context) {
        ensureChannel(context);
        return new NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification_subhub)
                .setColor(ContextCompat.getColor(context, R.color.accent)).setAutoCancel(true)
                .setContentIntent(intent(context)).setPriority(NotificationCompat.PRIORITY_DEFAULT);
    }

    private static PendingIntent intent(Context context) {
        return PendingIntent.getActivity(context, 0, new Intent(context, UpdatesActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static boolean canNotify(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager manager = manager(context);
        if (manager == null || !manager.areNotificationsEnabled()) return false;
        ensureChannel(context);
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    private static NotificationManager manager(Context context) {
        return context.getSystemService(NotificationManager.class);
    }

    private static void ensureChannel(Context context) {
        manager(context).createNotificationChannel(new NotificationChannel(CHANNEL,
                context.getString(R.string.update_channel_name), NotificationManager.IMPORTANCE_DEFAULT));
    }
}
