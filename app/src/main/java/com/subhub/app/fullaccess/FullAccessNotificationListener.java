package com.subhub.app.fullaccess;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.regex.Pattern;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Android-granted listener. No historical backfill or capture while the local scope is disabled. */
public final class FullAccessNotificationListener extends NotificationListenerService {
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?iu)\\b(?:otp|one[- ]time|passcode|password|passphrase|verification|security code"
                    + "|recovery|reset|authenticate|authentication|sign[- ]?in|log[- ]?in"
                    + "|access token|credential|PIN)\\b|\\p{Nd}{4,}|https?://");
    record Preview(String title, String text) { }
    private static final Preview EMPTY = new Preview("", "");
    private final ThreadPoolExecutor writes = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64), new ThreadPoolExecutor.DiscardPolicy());

    @Override public void onNotificationPosted(StatusBarNotification notification) {
        if (notification == null || !FullAccessDataSources.notificationScopeWasChosen(this)
                || !android.os.Process.myUserHandle().equals(notification.getUser())) return;
        Notification source = notification.getNotification();
        if (source == null || source.visibility == Notification.VISIBILITY_SECRET
                || (source.flags & Notification.FLAG_LOCAL_ONLY) != 0) return;
        String packageName = notification.getPackageName();
        if (packageName == null || packageName.equals(getPackageName())) return;
        // Keep only bounded, public-facing text and metadata across the callback.
        Preview preview = preview(source);
        String eventKey = keyDigest(notification.getKey());
        long postedAt = notification.getPostTime();
        String category = category(source.category);
        if (eventKey != null) writes.execute(() ->
                FullAccessDataSources.recordNotification(this, eventKey, packageName, category, postedAt, preview));
    }

    @Override public void onDestroy() {
        writes.shutdownNow();
        super.onDestroy();
    }

    private static Preview preview(Notification source) {
        Notification exposed = source.publicVersion;
        if (exposed == null && source.visibility == Notification.VISIBILITY_PUBLIC) exposed = source;
        if (exposed == null || exposed.visibility == Notification.VISIBILITY_SECRET
                || (exposed.flags & Notification.FLAG_LOCAL_ONLY) != 0) return EMPTY;
        try {
            Bundle extras = exposed.extras;
            if (extras == null) return EMPTY;
            CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
            return publicPreview(title, text);
        } catch (RuntimeException unavailable) {
            return EMPTY;
        }
    }

    static Preview publicPreview(CharSequence title, CharSequence text) {
        if (title != null && title.length() > 1024 || text != null && text.length() > 2048) return EMPTY;
        String publicTitle = normalize(title);
        String publicText = normalize(text);
        if (CREDENTIAL.matcher(publicTitle + " " + publicText).find()) return EMPTY;
        return new Preview(FullAccessDataSources.bounded(publicTitle, 160),
                FullAccessDataSources.bounded(publicText, 480));
    }

    private static String normalize(CharSequence value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("\\p{Cf}", "").trim();
    }

    private static String keyDigest(String key) {
        if (key == null) return null;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP);
        } catch (NoSuchAlgorithmException unavailable) {
            return null;
        }
    }

    private static String category(String value) {
        if (value == null) return "";
        return switch (value) {
            case Notification.CATEGORY_ALARM, Notification.CATEGORY_CALL, Notification.CATEGORY_EMAIL,
                    Notification.CATEGORY_EVENT, Notification.CATEGORY_MESSAGE,
                    Notification.CATEGORY_REMINDER, Notification.CATEGORY_SOCIAL, Notification.CATEGORY_TRANSPORT -> value;
            default -> "";
        };
    }
}
