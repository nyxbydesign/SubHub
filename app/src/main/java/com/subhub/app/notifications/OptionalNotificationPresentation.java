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
        result.extras = clean; result.publicVersion = null; result.contentView = null;
        result.bigContentView = null; result.headsUpContentView = null; result.tickerText = null;
        return result;
    }
}
