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
    static final int MAX_INTERNAL_ADMITTED_PER_TYPE = 64;
    static final int MAX_INTERNAL_OWNERS_PER_TYPE = 4;
    public static final int INTERNAL_TASK_NOTIFICATION_ID = com.subhub.app.tasks.TaskNoticeLedger.NOTIFICATION_ID;
    /** One broker-owned generation; the predicate must be memory-only and must not enter data/runtime locks. */
    public static final class InternalTaskOwner {
        public final String generation;
        private final java.util.function.BooleanSupplier current;
        private volatile boolean retired;
        public InternalTaskOwner(String generation, java.util.function.BooleanSupplier current) {
            if (!java.util.UUID.fromString(generation).toString().equals(generation))
                throw new IllegalArgumentException("Invalid internal notification generation");
            this.generation = generation; this.current = Objects.requireNonNull(current);
        }
        boolean current() { return !retired && current.getAsBoolean(); }
        public String tag(Role role, Event event, String effectKey) {
            boolean domReview;
            if (role == Role.SUB && event == Event.TASKS) domReview = false;
            else if (role == Role.DOM && event == Event.TASK_REVIEW) domReview = true;
            else throw new IllegalArgumentException("Not an internal task notification kind");
            return com.subhub.app.tasks.TaskNoticeLedger.notificationTag(generation, domReview, effectKey);
        }
    }
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
        private final Map<String, Map<InternalTaskOwner, LinkedHashSet<Identity>>> internalByType = new HashMap<>();
        boolean postInternal(Role role, Event event, InternalTaskOwner owner, String effectKey,
                Notification notification, Operations operations) {
            synchronized (DELIVERY_LOCK) {
                if (!owner.current()) return false;
                Identity identity = new Identity(owner.tag(role, event, effectKey), INTERNAL_TASK_NOTIFICATION_ID);
                Map<InternalTaskOwner, LinkedHashSet<Identity>> owners = internalByType.computeIfAbsent(key(role, event), unused -> new HashMap<>());
                if (!owners.containsKey(owner) && owners.size() >= MAX_INTERNAL_OWNERS_PER_TYPE) return false;
                for (InternalTaskOwner existing : owners.keySet())
                    if (existing != owner && existing.generation.equals(owner.generation)) return false;
                LinkedHashSet<Identity> keys = owners.computeIfAbsent(owner, unused -> new LinkedHashSet<>());
                if (!keys.contains(identity) && keys.size() >= MAX_INTERNAL_ADMITTED_PER_TYPE) {
                    Iterator<Identity> oldest = keys.iterator(); Identity retired = oldest.next();
                    operations.cancel(retired.tag, retired.id); oldest.remove();
                }
                keys.remove(identity); keys.add(identity); // Register before the platform accepts an invisible post.
                operations.post(identity.tag, identity.id, notification); return true;
            }
        }
        void cancelInternal(InternalTaskOwner owner, Operations operations) {
            synchronized (DELIVERY_LOCK) {
                owner.retired = true; // Fence queued callbacks before any platform cancellation.
                for (Map<InternalTaskOwner, LinkedHashSet<Identity>> owners : internalByType.values()) {
                    LinkedHashSet<Identity> keys = owners.get(owner);
                    if (keys == null) continue;
                    cancelKeys(keys, operations); owners.remove(owner);
                }
            }
        }
        private static void cancelKeys(LinkedHashSet<Identity> keys, Operations operations) {
            Iterator<Identity> iterator = keys.iterator();
            while (iterator.hasNext()) {
                Identity identity = iterator.next(); operations.cancel(identity.tag, identity.id); iterator.remove();
            }
        }
        int internalTrackedCount(Role role, Event event, InternalTaskOwner owner) {
            synchronized (DELIVERY_LOCK) {
                Map<InternalTaskOwner, LinkedHashSet<Identity>> owners = internalByType.get(key(role, event));
                LinkedHashSet<Identity> keys = owners == null ? null : owners.get(owner);
                return keys == null ? 0 : keys.size();
            }
        }
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
                if (keys != null) cancelKeys(keys, operations);
                Map<InternalTaskOwner, LinkedHashSet<Identity>> owners = internalByType.get(key(role, event));
                if (owners != null) {
                    for (LinkedHashSet<Identity> owned : owners.values()) cancelKeys(owned, operations);
                    owners.clear();
                }
            }
        }
        void cancelExact(Role role, Event event, String tag, int id, Operations operations) {
            synchronized (DELIVERY_LOCK) {
                operations.cancel(tag, id);
                LinkedHashSet<Identity> keys = keysByType.get(key(role, event));
                if (keys != null) keys.remove(new Identity(tag, id));
                Map<InternalTaskOwner, LinkedHashSet<Identity>> owners = internalByType.get(key(role, event));
                if (owners != null) for (LinkedHashSet<Identity> owned : owners.values()) owned.remove(new Identity(tag, id));
            }
        }
        boolean tracked(Role role, Event event, String tag, int id) {
            synchronized (DELIVERY_LOCK) {
                LinkedHashSet<Identity> keys = keysByType.get(key(role, event));
                Identity identity = new Identity(tag, id);
                if (keys != null && keys.contains(identity)) return true;
                Map<InternalTaskOwner, LinkedHashSet<Identity>> owners = internalByType.get(key(role, event));
                if (owners != null) for (LinkedHashSet<Identity> owned : owners.values()) if (owned.contains(identity)) return true;
                return false;
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
    /** Caller checks normal Android permission/channel availability; this preserves the existing role/event policy. */
    public static boolean postInternalTask(Context context, Role role, Event event, InternalTaskOwner owner,
            String effectKey, Notification notification) {
        synchronized (DELIVERY_LOCK) {
            if (!enabled(context, role, event) || !owner.current()) return false;
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null) return false;
            try { return admitted.postInternal(role, event, owner, effectKey, notification, system(manager)); }
            catch (RuntimeException unavailable) { return false; }
        }
    }
    /** In-process exact receipts include accepted-but-not-visible posts. Host also retains durable receipts across process loss. */
    public static boolean cancelInternalTasks(Context context, InternalTaskOwner owner) {
        synchronized (DELIVERY_LOCK) {
            owner.retired = true;
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null) return false;
            try { admitted.cancelInternal(owner, system(manager)); return true; }
            catch (RuntimeException unavailable) { return false; }
        }
    }
    /** Replay only a validated exact owned receipt after process loss; a missing host keeps it recoverable. */
    public static boolean cancelInternalTaskReceipt(Context context, com.subhub.app.tasks.TaskNoticeLedger.Receipt receipt) {
        synchronized (DELIVERY_LOCK) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null) return false;
            boolean review = receipt.audience == com.subhub.app.tasks.TaskNoticeLedger.Audience.DOM_REVIEW;
            try {
                admitted.cancelExact(review ? Role.DOM : Role.SUB, review ? Event.TASK_REVIEW : Event.TASKS,
                        receipt.tag(), receipt.id(), system(manager));
                return true;
            } catch (RuntimeException unavailable) { return false; }
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
