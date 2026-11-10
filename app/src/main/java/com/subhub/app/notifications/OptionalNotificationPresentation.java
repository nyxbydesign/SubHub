package com.subhub.app.notifications;

import android.app.Notification;
import android.content.Context;
import android.os.Bundle;
import com.subhub.app.R;
import com.subhub.app.privacy.PrivacyManager;

public final class OptionalNotificationPresentation {
    private OptionalNotificationPresentation() { }
    public static Notification present(Context context, Notification original) {
        if (!new PrivacyManager(context).isDiscreet()) return original;
        String title = context.getString(R.string.privacy_discreet_name);
        String body = context.getString(R.string.privacy_notification_body);
        Notification result = Notification.Builder.recoverBuilder(context, original)
                .setSmallIcon(R.drawable.ic_discreet_filter).setContentTitle(title).setContentText(body)
                .setVisibility(Notification.VISIBILITY_SECRET).build();
        Bundle clean = new Bundle();
        clean.putCharSequence(Notification.EXTRA_TITLE, title); clean.putCharSequence(Notification.EXTRA_TEXT, body);
        NotificationPreferences.Event type = activityType(original);
        if (type != null) clean.putString(com.subhub.app.activity.ActivityNotifications.EXTRA_TYPE, type.name());
        result.extras = clean; result.publicVersion = null; result.contentView = null;
        result.bigContentView = null; result.headsUpContentView = null; result.tickerText = null;
        return result;
    }
    public static NotificationPreferences.Event activityType(Notification notification) {
        try {
            String type = notification.extras.getString(com.subhub.app.activity.ActivityNotifications.EXTRA_TYPE, "");
            NotificationPreferences.Event event = NotificationPreferences.Event.valueOf(type);
            return NotificationPreferences.isActivity(event) ? event : null;
        } catch (IllegalArgumentException | ClassCastException invalid) { return null; }
    }
    /** Activity notices share an OS numeric ID; their own typed fact, rather than an ID cache, restores copy. */
    public static void refreshActivity(Context context, String tag, int id, Notification original) {
        NotificationPreferences.Event event = activityType(original);
        if (event == null) return;
        Notification publicCopy = new Notification.Builder(context, original.getChannelId())
                .setSmallIcon(R.drawable.ic_notification_subhub).setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.dom_activity_new_activity)).build();
        Notification restored = Notification.Builder.recoverBuilder(context, original)
                .setSmallIcon(R.drawable.ic_notification_subhub).setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(NotificationPreferences.activityLabel(event)))
                .setStyle((Notification.Style) null).setShowWhen(true).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(publicCopy).build();
        OptionalNotificationDispatch.post(context, NotificationPreferences.Role.DOM, event, tag, id, present(context, restored));
    }

}
