package com.subhub.app.appmode;

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

import com.subhub.app.MainActivity;
import com.subhub.app.R;
import com.subhub.app.security.ProtectionStopPolicy;

/** Visible boot/resume state. It never attempts to reuse or bypass capture consent. */
public final class ResumeNotificationManager {
    private static final String CHANNEL_ID = "subhub_app_mode";
    private static final int NOTIFICATION_ID = 1702;

    private ResumeNotificationManager() {}

    public static void show(Context context) {
        boolean projectionPending = ProtectionSessionManager.needsMediaProjectionResume(context);
        boolean appModeArmed = new AppModeManager(context)
                .isEffectivelyArmed(System.currentTimeMillis());
        if (!projectionPending && !appModeArmed) {
            cancel(context);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.app_mode_notification_channel),
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(context, 0,
                new Intent(context, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent disarm = PendingIntent.getBroadcast(context, 1,
                new Intent(context, BootReceiver.class).setAction(BootReceiver.ACTION_DISARM),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        int message = projectionPending ? R.string.app_mode_notification_resume_projection
                : R.string.app_mode_notification_armed;
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.app_mode_notification_title))
                .setContentText(context.getString(message))
                .setContentIntent(open)
                .setOngoing(appModeArmed && !projectionPending)
                .setAutoCancel(projectionPending)
                .setSilent(true);
        if (ProtectionStopPolicy.showNotificationStop(context)) {
            builder.addAction(0, context.getString(R.string.app_mode_disarm), disarm);
        }
        notifications.notify(NOTIFICATION_ID, com.subhub.app.privacy.PrivacyNotifications.present(context, NOTIFICATION_ID, builder.build()));
    }

    public static void cancel(Context context) {
        context.getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
    }
}
