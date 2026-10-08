package com.subhub.app.update;

import android.Manifest;
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

/** Quiet, version-deduplicated updater notifications. */
public final class UpdateNotifications {
    private static final String CHANNEL = "subhub_updates";
    private static final int AVAILABLE_ID = 9070;
    private static final int READY_ID = 9071;

    private UpdateNotifications() {}

    public static void available(Context context, UpdateCandidate candidate) {
        UpdateStateStore state = new UpdateStateStore(context);
        if (candidate.prerelease && !state.devUpdates()) return;
        if (!state.markNotified(candidate.manifest.versionName) || !canNotify(context)) return;
        manager(context).notify(AVAILABLE_ID, com.subhub.app.privacy.PrivacyNotifications.present(context, AVAILABLE_ID, builder(context)
                .setContentTitle(context.getString(R.string.update_available_title,
                        candidate.manifest.versionName))
                .setContentText(context.getString(R.string.update_available_body))
                .setContentIntent(intent(context)).build()));
    }

    public static void ready(Context context, UpdateCandidate candidate) {
        if (!canNotify(context)) return;
        manager(context).notify(READY_ID, com.subhub.app.privacy.PrivacyNotifications.present(context, READY_ID, builder(context)
                .setContentTitle(context.getString(R.string.update_ready_title,
                        candidate.manifest.versionName))
                .setContentText(context.getString(R.string.update_ready_body))
                .setContentIntent(intent(context)).build()));
    }

    public static void failed(Context context) {
        if (!canNotify(context)) return;
        manager(context).notify(READY_ID, com.subhub.app.privacy.PrivacyNotifications.present(context, READY_ID, builder(context)
                .setContentTitle(context.getString(R.string.update_failed_title))
                .setContentText(context.getString(R.string.update_failed_body))
                .setContentIntent(intent(context)).build()));
    }

    private static NotificationCompat.Builder builder(Context context) {
        ensureChannel(context);
        return new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_tab_settings)
                .setColor(ContextCompat.getColor(context, R.color.accent))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
    }

    private static PendingIntent intent(Context context) {
        return PendingIntent.getActivity(context, 0,
                new Intent(context, UpdatesActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static boolean canNotify(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static NotificationManager manager(Context context) {
        return context.getSystemService(NotificationManager.class);
    }

    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager(context).createNotificationChannel(new NotificationChannel(CHANNEL,
                    context.getString(R.string.update_channel_name), NotificationManager.IMPORTANCE_DEFAULT));
        }
    }
}
