package com.subhub.app.people;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;

/** A local presentation flag, deliberately unrelated to identities or connections. */
public final class DummySubPreview {
    public static final String PREFERENCES_NAME = "subhub_dummy_preview";
    private static final String ENABLED = "enabled";
    // A failed commit can update SharedPreferences' in-memory map. Never trust that map afterward.
    private static boolean persistenceFailed;

    private DummySubPreview() { }

    static boolean authorized(Context context) {
        return ControllerPinManager.isDomWorkspaceUnlocked() && DeviceRolePreferences.supportsDom(context);
    }

    public static synchronized boolean isEnabled(Context context) {
        if (!authorized(context) || persistenceFailed) return false;
        try {
            return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).getBoolean(ENABLED, false);
        } catch (ClassCastException invalidFlag) {
            return false;
        }
    }

    /** Returns the resulting usable state; failed persistence always closes the preview. */
    public static synchronized boolean toggle(Context context) {
        if (!authorized(context)) throw new SecurityException("Dummy preview requires an unlocked Dom workspace");
        boolean enabled = !isEnabled(context);
        SharedPreferences preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        persistenceFailed = true;
        if (!preferences.edit().putBoolean(ENABLED, enabled).commit())
            throw new IllegalStateException("Dummy preview could not be persisted");
        persistenceFailed = false;
        if (!authorized(context)) throw new SecurityException("Dom workspace changed");
        return enabled;
    }

    public static void open(Activity activity) {
        if (isEnabled(activity)) activity.startActivity(new Intent(activity, DummySubPreviewActivity.class));
    }
}
