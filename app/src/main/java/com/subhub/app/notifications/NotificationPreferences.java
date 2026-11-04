package com.subhub.app.notifications;

import android.content.Context;
import android.content.SharedPreferences;
import com.subhub.app.security.DeviceRolePreferences;

/** Optional notification choices belong to this device, independently of received contracts. */
public final class NotificationPreferences {
    public enum Role { DOM, SUB }
    public enum Event { ACTIVITY, TASK_REVIEW, CONTRACT_CHANGED, SESSION_STARTED, SESSION_STOPPED }
    /** Acquire after store/runtime locks. Code holding this lock never enters those subsystems. */
    public static final Object DELIVERY_LOCK = new Object();
    public static final String STORE = "local_notification_choices";
    private NotificationPreferences() { }
    public static boolean enabled(Context context, Role role, Event event) {
        if (!supported(role, event)) return false;
        if (role == Role.DOM ? !DeviceRolePreferences.supportsDom(context) : !DeviceRolePreferences.supportsSub(context)) return false;
        return preferences(context).getBoolean(key(role, event), true);
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
        return role == Role.DOM || event != Event.ACTIVITY && event != Event.TASK_REVIEW;
    }
    private static void cancel(Context context, Role role, Event event) {
        OptionalNotificationDispatch.cancelTracked(context, role, event);
        if (role == Role.DOM && event == Event.ACTIVITY) {
            com.subhub.app.activity.ActivityNotifications.cancelAll(context); return;
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
