package com.subhub.app.remote;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
    private final Runnable changed;
    private final Set<Activity> resumed=Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean foreground,registered;
    private Application application;
    private final BroadcastReceiver display=new BroadcastReceiver() {
        @Override public void onReceive(Context context,Intent intent) { refresh(); }
    };
    LocalAppPresence(Context context,Runnable changed) { this.context=context;this.changed=changed; }
    synchronized void install(Application app) {
        if(registered)return;
        application=java.util.Objects.requireNonNull(app);
        IntentFilter filter=new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);filter.addAction(Intent.ACTION_USER_PRESENT);
        // These broadcasts are protected system broadcasts on every supported Android version.
        context.registerReceiver(display,filter);
        application.registerActivityLifecycleCallbacks(this);registered=true;
    }
    @Override public synchronized void onActivityResumed(Activity activity) { if(registered){resumed.add(activity);refresh();} }
    @Override public synchronized void onActivityPaused(Activity activity) { resumed.remove(activity);refresh(); }
    @Override public void onActivityDestroyed(Activity activity) { onActivityPaused(activity); }
    @Override public void onActivityCreated(Activity activity,Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity,Bundle state) { }
    synchronized boolean foreground() { refresh();return foreground; }
    private synchronized void refresh() {
        PowerManager power=(PowerManager)context.getSystemService(Context.POWER_SERVICE);
        KeyguardManager keyguard=(KeyguardManager)context.getSystemService(Context.KEYGUARD_SERVICE);
        boolean visible=!resumed.isEmpty() && power!=null && power.isInteractive()
                && keyguard!=null && !keyguard.isKeyguardLocked();
        if(visible!=foreground) { foreground=visible;changed.run(); }
    }
    synchronized JSONObject snapshot() throws JSONException {
        boolean visible=foreground();
        synchronized(SEQUENCE_LOCK) {
        SharedPreferences preferences=context.getSharedPreferences("subhub_foreground_presence",0);
        long previous=preferences.getLong("sequence",0);
        if(previous<0 || previous==Long.MAX_VALUE)throw new JSONException("Presence sequence unavailable");
        long sequence=previous+1;
        if(!preferences.edit().putLong("sequence",sequence).commit())
            throw new JSONException("Presence sequence could not be saved");
        return new JSONObject().put("version",1).put("sequence",sequence).put("foreground",visible);
        }
    }
    @Override public synchronized void close() {
        resumed.clear();foreground=false;
        if(registered){context.unregisterReceiver(display);
            application.unregisterActivityLifecycleCallbacks(this);application=null;registered=false;}
    }
}
