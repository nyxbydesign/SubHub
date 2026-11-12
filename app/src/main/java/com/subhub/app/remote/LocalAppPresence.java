package com.subhub.app.remote;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Presence requires a resumed Activity and a usable display; services cannot assert it. */
final class LocalAppPresence implements AutoCloseable,Application.ActivityLifecycleCallbacks {
    private static final Object SEQUENCE_LOCK=new Object();
    private final Context context;
    private final Set<Activity> resumed = Collections.newSetFromMap(new IdentityHashMap<>());
    private Application application;

    LocalAppPresence(Context context) { this.context = context; }

    synchronized void install(Application app) {
        if (application != null) return;
        application = java.util.Objects.requireNonNull(app);
        application.registerActivityLifecycleCallbacks(this);
    }

    @Override public synchronized void onActivityResumed(Activity activity) {
        if (application != null) resumed.add(activity);
    }

    @Override public synchronized void onActivityPaused(Activity activity) { resumed.remove(activity); }
    @Override public void onActivityDestroyed(Activity activity) { onActivityPaused(activity); }
    @Override public void onActivityCreated(Activity activity,Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity,Bundle state) { }
    synchronized boolean foreground() {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return !resumed.isEmpty() && power != null && power.isInteractive()
                && keyguard != null && !keyguard.isKeyguardLocked();
    }

    synchronized JSONObject snapshot(Boolean previousForeground) throws JSONException {
        boolean visible = foreground();
        // The first existing frame carries presence. Later checkpoints carry only a change.
        if (previousForeground != null && previousForeground == visible) return null;
        synchronized (SEQUENCE_LOCK) {
            SharedPreferences preferences = context.getSharedPreferences("subhub_foreground_presence", 0);
            long previous = preferences.getLong("sequence", 0);
            if (previous < 0 || previous == Long.MAX_VALUE) throw new JSONException("Presence sequence unavailable");
            long sequence = previous + 1;
            if (!preferences.edit().putLong("sequence", sequence).commit())
                throw new JSONException("Presence sequence could not be saved");
            return new JSONObject().put("version", 1).put("sequence", sequence).put("foreground", visible);
        }
    }

    @Override public synchronized void close() {
        resumed.clear();
        if (application != null) {
            application.unregisterActivityLifecycleCallbacks(this);
            application = null;
        }
    }
}
