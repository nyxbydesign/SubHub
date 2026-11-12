package com.subhub.app.fullaccess;

import android.app.Notification;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Base64;
import androidx.core.app.NotificationCompat;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Collects only the bounded notification fields allowed by the current owner choice. */
public final class FullAccessNotificationListener extends NotificationListenerService {
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?iu)\\b(?:otp|one[- ]time|passcode|password|passphrase|verification|security code"
                    + "|recovery|reset|authenticate|authentication|sign[- ]?in|log[- ]?in"
                    + "|access token|credential|PIN)\\b|\\p{Nd}{4,}|https?://");
    private static final int RETAINED_LIMIT = 64;
    private static final int ICON_EDGE = 24;
    static final int ICON_ENCODED_LIMIT = 2048;
    // Existing richer-preview approvals use the same prospective timestamp.
    static final String TEXT_SINCE = "notifications_details_since";

    record Preview(String title, String text) {
        boolean empty() { return title.isEmpty() && text.isEmpty(); }
    }
    record Choice(long epoch, String generation, long revision, long since, long textSince) {
        boolean allowsText(long time) {
            return textSince > 0 && time >= textSince;
        }

        boolean sameScope(Choice captured) {
            return epoch == captured.epoch && generation.equals(captured.generation)
                    && revision == captured.revision && since == captured.since;
        }

        long acceptedTextSince(Choice captured, long time) {
            return sameScope(captured) && textSince == captured.textSince && allowsText(time) ? textSince : 0;
        }
    }

    static final Preview EMPTY = new Preview("", "");
    private static volatile FullAccessNotificationListener connected;
    private final ThreadPoolExecutor writes = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(RETAINED_LIMIT), new ThreadPoolExecutor.DiscardPolicy());

    static void reconcileIfConnected() {
        FullAccessNotificationListener listener = connected;
        if (listener != null) new Handler(listener.getMainLooper()).post(listener::reconcile);
    }

    @Override public void onListenerConnected() {
        connected = this;
        reconcile();
    }

    @Override public void onListenerDisconnected() {
        if (connected == this) connected = null;
    }

    @Override public void onNotificationPosted(StatusBarNotification notification) {
        capture(notification, FullAccessDataSources.notificationChoice(this), DemoFullAccess.notificationChoice(this));
    }

    @Override public void onDestroy() {
        onListenerDisconnected();
        writes.shutdownNow();
        super.onDestroy();
    }

    private void reconcile() {
        if (connected != this) return;
        Choice paired = FullAccessDataSources.notificationChoice(this);
        Choice demo = DemoFullAccess.notificationChoice(this);
        if (paired == null && demo == null) return;
        long since = Math.min(paired == null ? Long.MAX_VALUE : paired.since(), demo == null ? Long.MAX_VALUE : demo.since());
        since = Math.max(since, System.currentTimeMillis() - FullAccessNotificationHistory.RETENTION_MILLIS);
        Comparator<StatusBarNotification> oldestFirst = Comparator.comparingLong(StatusBarNotification::getPostTime);
        PriorityQueue<StatusBarNotification> recent = new PriorityQueue<>(RETAINED_LIMIT, oldestFirst);
        try {
            selectRetained(getActiveNotifications(), since, recent);
        } catch (RuntimeException unavailable) {
            // Android may disconnect the listener before the snapshot request.
        }
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                selectRetained(getSnoozedNotifications(), since, recent);
            } catch (RuntimeException unavailable) {
                // Snoozed history may be unavailable independently of active notifications.
            }
        }
        ArrayList<StatusBarNotification> ordered = new ArrayList<>(recent);
        ordered.sort(oldestFirst.reversed());
        for (StatusBarNotification item : ordered) capture(item, paired, demo);
    }

    private void selectRetained(StatusBarNotification[] entries, long since, PriorityQueue<StatusBarNotification> recent) {
        if (entries == null) return;
        for (StatusBarNotification item : entries) {
            if (!eligible(item) || item.getPostTime() < since) continue;
            if (recent.size() == RETAINED_LIMIT) {
                if (recent.element().getPostTime() >= item.getPostTime()) continue;
                recent.remove();
            }
            recent.add(item);
        }
    }

    private boolean eligible(StatusBarNotification item) {
        return item != null && android.os.Process.myUserHandle().equals(item.getUser())
                && item.getPackageName() != null && !getPackageName().equals(item.getPackageName())
                && item.getNotification() != null && !protectedNotification(item.getNotification());
    }

    private void capture(StatusBarNotification item, Choice paired, Choice demo) {
        if (paired == null && demo == null || !eligible(item)) return;
        long postedAt = item.getPostTime();
        long now = System.currentTimeMillis();
        long retention = now - FullAccessNotificationHistory.RETENTION_MILLIS;
        if (postedAt > now || postedAt < retention) return;
        boolean capturePaired = paired != null && postedAt >= paired.since();
        boolean captureDemo = demo != null && postedAt >= demo.since();
        if (!capturePaired && !captureDemo) return;
        String key = keyDigest(item.getKey());
        if (key == null) return;
        Notification source = item.getNotification();
        Notification exposed = source.publicVersion;
        boolean rejectedPublicVersion = exposed != null && protectedNotification(exposed);
        if (exposed == null && source.visibility == Notification.VISIBILITY_PUBLIC) exposed = source;
        if (rejectedPublicVersion) exposed = null;
        String pkg = item.getPackageName();
        String type = category(source.category);
        Preview pairedPreview = capturePaired ? preview(exposed, Math.max(paired.since(), retention), now) : EMPTY;
        Preview demoPreview = captureDemo ? preview(exposed, Math.max(demo.since(), retention), now) : EMPTY;
        Preview pairedDetails = capturePaired && !rejectedPublicVersion && paired.allowsText(postedAt)
                ? preview(source, Math.max(paired.textSince(), retention), now) : EMPTY;
        Preview demoDetails = captureDemo && !rejectedPublicVersion && demo.allowsText(postedAt)
                ? preview(source, Math.max(demo.textSince(), retention), now) : EMPTY;
        // The original small icon is status-bar metadata. A supplied public version can replace or withhold it.
        Icon selectedIcon = rejectedPublicVersion ? null : (exposed == null ? source : exposed).getSmallIcon();
        writes.execute(() -> {
            String raster = renderIcon(pkg, selectedIcon);
            if (capturePaired)
                FullAccessDataSources.recordNotification(this, paired, key, pkg, type, postedAt, pairedPreview, pairedDetails, raster);
            if (captureDemo)
                DemoFullAccess.recordNotification(this, demo, key, pkg, type, postedAt, demoPreview, demoDetails, raster);
        });
    }

    private static boolean protectedNotification(Notification source) {
        return source.visibility == Notification.VISIBILITY_SECRET || (source.flags & Notification.FLAG_LOCAL_ONLY) != 0;
    }

    static Preview preview(Notification source, long since, long now) {
        if (source == null || protectedNotification(source) || source.extras == null) return EMPTY;
        try {
            Bundle extras = source.extras;
            CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence bigTitle = extras.getCharSequence(Notification.EXTRA_TITLE_BIG);
            CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
            CharSequence bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
            CharSequence subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
            CharSequence summary = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT);
            CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (unsafe(title) || unsafe(bigTitle) || unsafe(text) || unsafe(bigText) || unsafe(subText) || unsafe(summary))
                return EMPTY;
            if (lines != null && lines.length > 25) return EMPTY;
            StringBuilder inbox = new StringBuilder();
            if (lines != null) {
                for (CharSequence line : lines) {
                    if (unsafe(line)) return EMPTY;
                    appendLine(inbox, line);
                }
            }
            if (extras.containsKey(Notification.EXTRA_MESSAGES)) {
                CharSequence conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
                if (unsafe(conversation)) return EMPTY;
                String current = messages(extras, since, now);
                return current.isEmpty() ? EMPTY : publicPreview(first(conversation, bigTitle, title), current);
            }
            return publicPreview(first(bigTitle, title), first(bigText, inbox, text, summary, subText));
        } catch (RuntimeException unavailable) {
            return EMPTY;
        }
    }

    private static String messages(Bundle extras, long since, long now) {
        Parcelable[] bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (bundles == null || bundles.length > 25) return "";
        ArrayList<String> current = new ArrayList<>(bundles.length);
        if (Build.VERSION.SDK_INT >= 30) {
            for (Notification.MessagingStyle.Message message : Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles)) {
                CharSequence sender = message.getSenderPerson() == null ? null : message.getSenderPerson().getName();
                addMessage(current, message.getText(), sender, message.getTimestamp(), since, now);
            }
        } else {
            Notification currentOnly = new Notification();
            currentOnly.extras = new Bundle();
            currentOnly.extras.putString(Notification.EXTRA_TEMPLATE, Notification.MessagingStyle.class.getName());
            currentOnly.extras.putParcelableArray(Notification.EXTRA_MESSAGES, bundles);
            NotificationCompat.MessagingStyle style = NotificationCompat.MessagingStyle
                    .extractMessagingStyleFromNotification(currentOnly);
            if (style == null) return "";
            for (NotificationCompat.MessagingStyle.Message message : style.getMessages()) {
                CharSequence sender = message.getPerson() == null ? null : message.getPerson().getName();
                addMessage(current, message.getText(), sender, message.getTimestamp(), since, now);
            }
        }
        // MessagingStyle stores oldest to newest. Keep the newest suffix within the body limit.
        StringBuilder newest = new StringBuilder();
        for (int index = current.size() - 1; index >= 0; index--) {
            String line = current.get(index);
            if (newest.length() == 0) appendLine(newest, line);
            else if (line.length() + newest.length() + 1 <= 480) newest.insert(0, line + "\n");
            else break;
        }
        return newest.toString();
    }

    private static void addMessage(ArrayList<String> lines, CharSequence text, CharSequence sender,
            long timestamp, long since, long now) {
        if (unsafe(text) || unsafe(sender)) throw new IllegalArgumentException("Protected notification");
        if (timestamp < since || timestamp > now || text == null || text.length() == 0) return;
        lines.add(sender == null || sender.length() == 0 ? text.toString() : sender + ": " + text);
    }

    private static void appendLine(StringBuilder result, CharSequence value) {
        if (value == null || value.length() == 0 || result.length() >= 480) return;
        if (result.length() != 0) result.append('\n');
        result.append(value, 0, Math.min(value.length(), 480 - result.length()));
    }

    private static CharSequence first(CharSequence... values) {
        for (CharSequence value : values) if (value != null && value.length() != 0) return value;
        return "";
    }

    static Preview publicPreview(CharSequence title, CharSequence text) {
        if (title != null && title.length() > 1024 || text != null && text.length() > 2048) return EMPTY;
        String normalizedTitle = normalize(title);
        String normalizedText = normalize(text);
        if (CREDENTIAL.matcher(normalizedTitle + " " + normalizedText).find()) return EMPTY;
        return new Preview(FullAccessDataSources.bounded(normalizedTitle, 160),
                FullAccessDataSources.bounded(normalizedText, 480));
    }

    private static boolean unsafe(CharSequence value) {
        return value != null && (value.length() > 2048 || CREDENTIAL.matcher(normalize(value)).find());
    }

    private static String normalize(CharSequence value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("\\p{Cf}", "").trim();
    }

    private String renderIcon(String pkg, Icon icon) {
        if (icon != null && Build.VERSION.SDK_INT >= 28) {
            try {
                int type = icon.getType();
                boolean postingResource = type == Icon.TYPE_RESOURCE
                        && (pkg.equals(icon.getResPackage()) || "android".equals(icon.getResPackage()));
                if (postingResource || type == Icon.TYPE_BITMAP || type == Icon.TYPE_ADAPTIVE_BITMAP) {
                    Drawable drawable = icon.loadDrawable(this);
                    String raster = drawable != null && (postingResource
                            || drawable.getIntrinsicWidth() <= 128 && drawable.getIntrinsicHeight() <= 128)
                            ? raster(drawable) : "";
                    if (!raster.isEmpty()) return raster;
                }
            } catch (RuntimeException unavailable) {
                // The posting package may have been replaced or removed.
            }
        }
        try {
            return raster(getPackageManager().getApplicationIcon(pkg));
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException unavailable) {
            return "";
        }
    }

    private static String raster(Drawable drawable) {
        if (drawable == null) return "";
        Bitmap bitmap = Bitmap.createBitmap(ICON_EDGE, ICON_EDGE, Bitmap.Config.ARGB_8888);
        try {
            drawable.setBounds(0, 0, ICON_EDGE, ICON_EDGE);
            drawable.draw(new Canvas(bitmap));
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, data)) return "";
            String encoded = Base64.encodeToString(data.toByteArray(), Base64.NO_WRAP);
            return encoded.length() <= ICON_ENCODED_LIMIT ? encoded : "";
        } finally {
            bitmap.recycle();
        }
    }

    private static String keyDigest(String key) {
        if (key == null) return null;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(hash, Base64.NO_WRAP);
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
