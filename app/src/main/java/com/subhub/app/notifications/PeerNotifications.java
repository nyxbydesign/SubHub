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
        if (role != Role.SUB) return;
        applied(context, role, event, scope, sequence, java.util.Collections.emptyList());
    }
    public static void applied(Context context, Role role, Event event, String scope, long sequence, java.util.List<String> details) {
        if (role != Role.SUB) return;
        try { synchronized (DELIVERY_LOCK) { deliver(context, role, event, scope, sequence, details); } }
        catch (RuntimeException unavailable) { /* Optional presentation cannot fail a committed application effect. */ }
    }
    /** A saved expiry cannot claim Stop until its exact historical capture and runtime fence settle. */
    public static void pactExpired(Context context, com.subhub.app.service.ScreenCaptureService.StopTarget capture,
            long sequence) {
        LocalPactExpiryNotifications.schedule(context, capture, sequence);
    }
    private static void deliver(Context context, Role role, Event event, String scope, long sequence, java.util.List<String> details) {
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
        String title = context.getString(discreet ? R.string.privacy_discreet_name : copy);
        String body = discreet ? context.getString(R.string.privacy_notification_body)
                : !details.isEmpty() ? details.get(0) : context.getString(event == Event.CONTRACT_CHANGED ? copy : event == Event.SESSION_STARTED
                        ? R.string.notification_session_started_detail : R.string.notification_session_stopped_detail);
        String expanded = body;
        if (!discreet && !details.isEmpty()) {
            java.util.List<String> visible = new java.util.ArrayList<>(details.subList(0, Math.min(16, details.size())));
            if (details.size() > visible.size()) visible.add(context.getString(R.string.notification_more_changes, details.size() - visible.size()));
            expanded = String.join("\n", visible);
        }
        Intent open = new Intent(context, MainActivity.class).setAction(Intent.ACTION_MAIN)
                .setData(new Uri.Builder().scheme("subhub").authority("notifications").appendPath(role.name()).build())
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent destination = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        android.app.Notification notification = new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(discreet ? R.drawable.ic_discreet_filter : R.drawable.ic_notification_subhub)
                .setContentTitle(title).setContentText(body).setContentIntent(destination)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(expanded))
                .setColor(context.getColor(R.color.accent)).setShowWhen(true)
                .setGroup("subhub.relationship:" + scope).setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPublicVersion(new NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification_subhub)
                        .setContentTitle(context.getString(R.string.app_name)).setContentText(context.getString(R.string.privacy_notification_body)).build())
                .setVisibility(discreet ? NotificationCompat.VISIBILITY_SECRET : NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true).setOnlyAlertOnce(true).build();
        OptionalNotificationDispatch.post(context, role, event,
                "subhub.optional:" + key(role, event) + ":" + scope, 2140 + event.ordinal(), OptionalNotificationPresentation.present(context, notification));
    }
}
