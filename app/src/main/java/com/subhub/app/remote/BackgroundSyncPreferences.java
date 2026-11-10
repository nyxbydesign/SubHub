package com.subhub.app.remote;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.UUID;

/** This device's background connection choice is independent of paired configuration and roles. */
public final class BackgroundSyncPreferences {
    // Only preference IO and OS enqueue/start admission use this lock; never enter RemoteFeature with it held.
    static final Object LOCK = new Object();
    public static final String STORE = "local_background_sync";
    static final String ENABLED = "enabled";
    private static final String ADMISSION = "admission";
    private BackgroundSyncPreferences() { }

    public static boolean enabled(Context context) {
        return context.getSharedPreferences(STORE, 0).getBoolean(ENABLED, true);
    }

    static boolean set(Context context, boolean enabled) {
        synchronized (LOCK) {
            SharedPreferences preferences = context.getSharedPreferences(STORE, 0);
            SharedPreferences.Editor edit = preferences.edit().putBoolean(ENABLED, enabled);
            // Off then on must not revive asynchronous requests admitted before the user's choice changed.
            if (preferences.getBoolean(ENABLED, true) != enabled)
                edit.putString(ADMISSION, UUID.randomUUID().toString());
            return edit.commit();
        }
    }

    static String admission(Context context) {
        synchronized (LOCK) {
            SharedPreferences preferences = context.getSharedPreferences(STORE, 0);
            return preferences.getBoolean(ENABLED, true) ? preferences.getString(ADMISSION, "") : null;
        }
    }

    static boolean admits(Context context, String admission) {
        synchronized (LOCK) {
            return admission != null && admission.equals(admission(context));
        }
    }
}
