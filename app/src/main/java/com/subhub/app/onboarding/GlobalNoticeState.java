package com.subhub.app.onboarding;

import android.content.Context;
import android.content.SharedPreferences;

/** One queue and one Home slot for app-wide notices; dismissal is shared across entry points. */
public final class GlobalNoticeState {
    public enum Kind { NONE, PERMISSIONS, KEYHOLDER }
    private final SharedPreferences prefs;
    public GlobalNoticeState(Context context) { prefs=context.getSharedPreferences("subhub_home",0); }
    public Kind next(String permissions) {
        if(!permissions.isEmpty() && !permissions.equals(prefs.getString("dismissed_permission_state",""))) return Kind.PERMISSIONS;
        if(!prefs.getBoolean("keyholder_intro_dismissed",false)) return Kind.KEYHOLDER;
        return Kind.NONE;
    }
    public void dismiss(Kind kind,String permissions) {
        if(kind==Kind.PERMISSIONS) prefs.edit().putString("dismissed_permission_state",permissions).apply();
        else if(kind==Kind.KEYHOLDER) prefs.edit().putBoolean("keyholder_intro_dismissed",true).apply();
    }
}
