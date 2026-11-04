package com.subhub.app.notifications;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import static com.subhub.app.notifications.NotificationPreferences.*;

/** Exact admissions cover Android's accepted-but-not-yet-visible notification queue. */
public final class OptionalNotificationDispatch {
    static final int MAX_ADMITTED_PER_TYPE = 128;
    private static final Admissions admitted = new Admissions();
    interface Operations {
        void post(String tag, int id, Notification notification);
        void cancel(String tag, int id);
    }
    private OptionalNotificationDispatch() { }
    private static final class Identity {
        final String tag;
        final int id;
        Identity(String tag, int id) { this.tag = tag; this.id = id; }
        @Override public boolean equals(Object other) {
            return other instanceof Identity && id == ((Identity) other).id && Objects.equals(tag, ((Identity) other).tag);
        }
        @Override public int hashCode() { return Objects.hash(tag, id); }
    }
    /** Each instance owns its exact keys; the application retains one bounded process-local instance. */
    static final class Admissions {
        private final Map<String, LinkedHashSet<Identity>> keysByType = new HashMap<>();
        void post(Role role, Event event, String tag, int id, Notification notification, Operations operations) {
            synchronized (DELIVERY_LOCK) {
                LinkedHashSet<Identity> keys = keysByType.computeIfAbsent(key(role, event), unused -> new LinkedHashSet<>());
                Identity identity = new Identity(tag, id);
                if (!keys.contains(identity) && keys.size() >= MAX_ADMITTED_PER_TYPE) {
                    Iterator<Identity> oldest = keys.iterator(); Identity retired = oldest.next();
                    operations.cancel(retired.tag, retired.id); // Cancel before forgetting a post Android may not list yet.
                    oldest.remove();
                }
                keys.remove(identity); keys.add(identity);
                operations.post(tag, id, notification);
            }
        }
        void cancelTracked(Role role, Event event, Operations operations) {
            synchronized (DELIVERY_LOCK) {
                LinkedHashSet<Identity> keys = keysByType.get(key(role, event));
                if (keys == null) return;
                Iterator<Identity> iterator = keys.iterator();
                while (iterator.hasNext()) {
                    Identity identity = iterator.next(); operations.cancel(identity.tag, identity.id); iterator.remove();
                }
            }
        }
        void cancelExact(Role role, Event event, String tag, int id, Operations operations) {
            synchronized (DELIVERY_LOCK) {
                operations.cancel(tag, id);
                LinkedHashSet<Identity> keys = keysByType.get(key(role, event));
                if (keys != null) keys.remove(new Identity(tag, id));
            }
        }
        boolean tracked(Role role, Event event, String tag, int id) {
            synchronized (DELIVERY_LOCK) {
                LinkedHashSet<Identity> keys = keysByType.get(key(role, event));
                return keys != null && keys.contains(new Identity(tag, id));
            }
        }
        int trackedCount(Role role, Event event) {
            synchronized (DELIVERY_LOCK) {
                LinkedHashSet<Identity> keys = keysByType.get(key(role, event)); return keys == null ? 0 : keys.size();
            }
        }
    }
    private static Operations system(NotificationManager manager) {
        return new Operations() {
            public void post(String tag, int id, Notification notification) { manager.notify(tag, id, notification); }
            public void cancel(String tag, int id) { manager.cancel(tag, id); }
        };
    }
    public static boolean post(Context context, Role role, Event event, String tag, int id, Notification notification) {
        synchronized (DELIVERY_LOCK) {
            if (!enabled(context, role, event)) return false;
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null) return false;
            try { admitted.post(role, event, tag, id, notification, system(manager)); return true; }
            catch (RuntimeException unavailable) { return false; }
        }
    }
    public static void cancelTracked(Context context, Role role, Event event) {
        synchronized (DELIVERY_LOCK) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) admitted.cancelTracked(role, event, system(manager));
        }
    }
    public static void cancelExact(Context context, Role role, Event event, String tag, int id) {
        synchronized (DELIVERY_LOCK) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) admitted.cancelExact(role, event, tag, id, system(manager));
        }
    }
}
