package com.subhub.app.atmosphere;

import android.content.Context;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import com.subhub.app.people.ProfileStore;

/** Device-local Sub permission. The paired Dom remains the only source of replacement rules. */
public final class GagParticipation {
    public static final String STORE = "atmosphere_gag";
    private static final String ENABLED = "enabled";

    private GagParticipation() { }

    public static boolean allowed(Context context) {
        SharedPreferences preferences = preferences(context);
        return allowed(preferences, !preferences.contains(ENABLED) && keyboardEnabled(context));
    }

    static boolean allowed(SharedPreferences preferences, boolean defaultAllowed) {
        try { return preferences.getBoolean(ENABLED, defaultAllowed); }
        catch (ClassCastException invalid) { return false; }
    }

    static boolean keyboardEnabled(Context context) {
        InputMethodManager manager = context.getSystemService(InputMethodManager.class);
        if (manager == null) return false;
        ComponentName own = new ComponentName(context, GagKeyboardService.class);
        for (InputMethodInfo info : manager.getEnabledInputMethodList()) {
            if (own.equals(info.getComponent())) return true;
        }
        return false;
    }

    public static boolean setAllowed(Context context, boolean allowed) {
        return ProfileStore.mayEdit(context, false)
                && preferences(context).edit().putBoolean(ENABLED, allowed).commit();
    }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }
}
