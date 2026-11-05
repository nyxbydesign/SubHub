package com.subhub.app.notifications;

import android.content.Context;
import android.content.SharedPreferences;
import com.subhub.app.security.DeviceRolePreferences;

/** Optional notification choices belong to this device, independently of received contracts. */
public final class NotificationPreferences {
    public enum Role { DOM, SUB }
    public enum Event { ACTIVITY, TASK_REVIEW, CONTRACT_CHANGED, SESSION_STARTED, SESSION_STOPPED,
        TRACKED_APP_OPENED, APP_LIMIT_REACHED, SHARED_LIMIT_REACHED, TRIBUTE_TRIGGERED, TRIBUTE_PAID }
    /** Acquire after store/runtime locks. Code holding this lock never enters those subsystems. */
    public static final Object DELIVERY_LOCK = new Object();
    public static final String STORE = "local_notification_choices";
    private NotificationPreferences() { }
    public static boolean enabled(Context context, Role role, Event event) {
        if (!supported(role, event)) return false;
        if (role == Role.DOM ? !DeviceRolePreferences.supportsDom(context) : !DeviceRolePreferences.supportsSub(context)) return false;
        SharedPreferences choices = preferences(context);
        boolean fallback = role == Role.DOM && isActivity(event) ? choices.getBoolean(key(Role.DOM, Event.ACTIVITY), true) : true;
        return choices.getBoolean(key(role, event), fallback);
    }
    public static boolean set(Context context, Role role, Event event, boolean enabled) {
        return setWithCancellation(context, role, event, enabled, () -> cancel(context, role, event));
    }
    static boolean setWithCancellation(Context context, Role role, Event event, boolean enabled, Runnable cancel) {
        synchronized (DELIVERY_LOCK) {
            if (!supported(role, event)) return false;
            boolean saved = preferences(context).edit().putBoolean(key(role, event), enabled).commit();
            if (saved && !enabled) {
                try { cancel.run(); }
                catch (RuntimeException unavailable) { return false; }
            }
            return saved;
        }
    }
    private static boolean supported(Role role, Event event) {
        return role == Role.DOM ? isActivity(event) || event == Event.TASK_REVIEW
                : event == Event.CONTRACT_CHANGED || event == Event.SESSION_STARTED || event == Event.SESSION_STOPPED;
    }
    public static boolean isActivity(Event event) {
        switch (event) {
            case TRACKED_APP_OPENED: case APP_LIMIT_REACHED: case SHARED_LIMIT_REACHED:
            case TRIBUTE_TRIGGERED: case TRIBUTE_PAID: return true;
            default: return false;
        }
    }
    public static Event activityEvent(com.subhub.app.activity.ActivityEvent.Kind kind) {
        switch (kind) {
            case APP_OPENED: return Event.TRACKED_APP_OPENED;
            case APP_LIMIT_REACHED: return Event.APP_LIMIT_REACHED;
            case SHARED_LIMIT_REACHED: return Event.SHARED_LIMIT_REACHED;
            case TRIBUTE_TRIGGERED: return Event.TRIBUTE_TRIGGERED;
            case TRIBUTE_PAID: return Event.TRIBUTE_PAID;
            default: throw new IllegalArgumentException("Unsupported activity notification");
        }
    }
    public static int activityLabel(Event event) {
        switch (event) {
            case TRACKED_APP_OPENED: return com.subhub.app.R.string.notification_tracked_app_opened;
            case APP_LIMIT_REACHED: return com.subhub.app.R.string.notification_app_limit_reached;
            case SHARED_LIMIT_REACHED: return com.subhub.app.R.string.notification_shared_limit_reached;
            case TRIBUTE_TRIGGERED: return com.subhub.app.R.string.notification_tribute_triggered;
            case TRIBUTE_PAID: return com.subhub.app.R.string.notification_tribute_paid;
            default: throw new IllegalArgumentException("Unsupported activity notification");
        }
    }
    private static void cancel(Context context, Role role, Event event) {
        OptionalNotificationDispatch.cancelTracked(context, role, event);
        if (role == Role.DOM && isActivity(event)) {
            com.subhub.app.activity.ActivityNotifications.cancelType(context, event); return;
        }
        android.app.NotificationManager manager = context.getSystemService(android.app.NotificationManager.class);
        if (manager == null) return;
        String prefix = role == Role.DOM && event == Event.TASK_REVIEW ? "tasks:"
                : "subhub.optional:" + key(role, event) + ":";
        for (android.service.notification.StatusBarNotification active : manager.getActiveNotifications())
            if (active.getTag() != null && active.getTag().startsWith(prefix)) manager.cancel(active.getTag(), active.getId());
    }
    public static String key(Role role, Event event) { return role.name() + ":" + event.name(); }
    private static SharedPreferences preferences(Context context) { return context.getSharedPreferences(STORE, 0); }
}
