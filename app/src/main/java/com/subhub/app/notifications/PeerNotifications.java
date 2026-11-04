package com.subhub.app.notifications;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import com.subhub.app.R;
import com.subhub.app.MainActivity;
import com.subhub.app.privacy.PrivacyManager;
import static com.subhub.app.notifications.NotificationPreferences.*;

/** Applied receipts only. A durable sequence fence prevents reconnects from alerting again. */
public final class PeerNotifications {
    public static final String CHANNEL = "relationship_changes";
    private PeerNotifications() { }

    /** Consumes disabled events too, so enabling a choice never delivers an old backlog. */
    public static boolean consume(Context context, Role role, Event event, String scope, long sequence) {
        synchronized (DELIVERY_LOCK) { return consumeLocked(context, role, event, scope, sequence); }
    }
    private static boolean consumeLocked(Context context, Role role, Event event, String scope, long sequence) {
        if (sequence <= 0) return false;
        SharedPreferences receipts = context.getSharedPreferences("local_notification_receipts", 0);
        String key = key(role, event) + ":" + scope;
        if (sequence <= receipts.getLong(key, 0)) return false;
        // Commit acknowledgement, rather than in-memory contains(), establishes the replay fence.
        if (!receipts.edit().putLong(key, sequence).commit()) return false;
        return enabled(context, role, event);
    }

    public static void applied(Context context, Role role, Event event, String scope, long sequence) {
        try { synchronized (DELIVERY_LOCK) { deliver(context, role, event, scope, sequence); } }
        catch (RuntimeException unavailable) { /* Optional presentation cannot fail a committed application effect. */ }
    }
    /** A saved expiry cannot claim Stop until its exact historical capture and runtime fence settle. */
    public static void pactExpired(Context context, com.subhub.app.service.ScreenCaptureService.StopTarget capture,
            long sequence, boolean remoteOwned) {
        LocalPactExpiryNotifications.schedule(context, capture, sequence, remoteOwned);
    }
    private static void deliver(Context context, Role role, Event event, String scope, long sequence) {
        if (!consume(context, role, event, scope, sequence)) return;
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return;
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                context.getString(R.string.notification_relationship_channel), NotificationManager.IMPORTANCE_DEFAULT));
        if (manager.getNotificationChannel(CHANNEL).getImportance() == NotificationManager.IMPORTANCE_NONE) return;
        int copy = event == Event.CONTRACT_CHANGED ? R.string.notification_contract_changed
                : event == Event.SESSION_STARTED ? R.string.notification_session_started : R.string.notification_session_stopped;
        boolean discreet = new PrivacyManager(context).isDiscreet();
        String title = context.getString(discreet ? R.string.privacy_discreet_name : R.string.app_name);
        String body = context.getString(discreet ? R.string.privacy_notification_body : copy);
        Intent open = new Intent(context, MainActivity.class).setAction(Intent.ACTION_MAIN)
                .setData(new Uri.Builder().scheme("subhub").authority("notifications").appendPath(role.name()).build())
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent destination = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        android.app.Notification notification = new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(discreet ? R.drawable.ic_discreet_filter : R.drawable.ic_notification_subhub)
                .setContentTitle(title).setContentText(body).setContentIntent(destination)
                .setVisibility(discreet ? NotificationCompat.VISIBILITY_SECRET : NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true).setOnlyAlertOnce(true).build();
        OptionalNotificationDispatch.post(context, role, event,
                "subhub.optional:" + key(role, event) + ":" + scope, 2140 + event.ordinal(), notification);
    }
}
