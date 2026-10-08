package com.subhub.app.security;

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

/** Keeps a degraded Hardcore setup visible until its Accessibility guard is restored. */
public final class HardcoreReadinessNotificationManager {
    private static final String CHANNEL_ID = "subhub_hardcore_readiness";
    private static final int NOTIFICATION_ID = 1703;

    private HardcoreReadinessNotificationManager() {}

    public static void refresh(Context context) {
        Context app = context.getApplicationContext();
        NotificationManager notifications = app.getSystemService(NotificationManager.class);
        if (notifications == null) return;
        HardcoreModeManager hardcore = new HardcoreModeManager(app);
        if (!hardcore.isRequested() || hardcore.isGuardReady()) {
            notifications.cancel(NOTIFICATION_ID);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                app.getString(R.string.hardcore_repair_channel),
                NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(app, 0,
                new Intent(app, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        notifications.notify(NOTIFICATION_ID,
                com.subhub.app.privacy.PrivacyNotifications.present(app, NOTIFICATION_ID, new NotificationCompat.Builder(app, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_launcher_foreground)
                        .setContentTitle(app.getString(R.string.hardcore_repair_title))
                        .setContentText(app.getString(R.string.hardcore_repair_body))
                        .setContentIntent(open)
                        .setOngoing(true)
                        .setSilent(true)
                        .build()));
    }
}
