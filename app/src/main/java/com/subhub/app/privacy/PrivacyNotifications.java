package com.subhub.app.privacy;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import com.subhub.app.R;
import java.util.concurrent.ConcurrentHashMap;

/** Plain visible content with unchanged service flags, content intents and action intents. */
public final class PrivacyNotifications {
    private static final ConcurrentHashMap<Integer, Notification> originals = new ConcurrentHashMap<>();
    private PrivacyNotifications() { }
    public static Notification present(Context context, int id, Notification original) {
        originals.put(id, original);
        if (!new PrivacyManager(context).isDiscreet()) return original;
        String title = context.getString(R.string.privacy_discreet_name);
        String body = context.getString(R.string.privacy_notification_body);
        Notification result = Notification.Builder.recoverBuilder(context, original)
                .setSmallIcon(R.drawable.ic_discreet_filter).setLargeIcon((android.graphics.Bitmap) null)
                .setContentTitle(title).setContentText(body).setSubText(null).setTicker(null)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setVisibility(Notification.VISIBILITY_SECRET).build();
        Bundle clean = new Bundle(); clean.putCharSequence(Notification.EXTRA_TITLE, title);
        clean.putCharSequence(Notification.EXTRA_TEXT, body);
        clean.putInt(Notification.EXTRA_PROGRESS, original.extras.getInt(Notification.EXTRA_PROGRESS));
        clean.putInt(Notification.EXTRA_PROGRESS_MAX, original.extras.getInt(Notification.EXTRA_PROGRESS_MAX));
        clean.putBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, original.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE));
        result.extras = clean; result.contentView = null; result.bigContentView = null;
        result.headsUpContentView = null; result.publicVersion = null; result.tickerText = null;
        return result;
    }
    public static void refresh(Context context) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        for (StatusBarNotification active : manager.getActiveNotifications()) {
            Notification original = originals.getOrDefault(active.getId(), active.getNotification());
            manager.notify(active.getTag(), active.getId(), present(context, active.getId(), original));
        }
    }
}
