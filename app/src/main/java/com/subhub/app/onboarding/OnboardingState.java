package com.subhub.app.onboarding;

import android.content.Context;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.SettingsRepository;

public final class OnboardingState {
    private OnboardingState() { }
    public static boolean completed(Context context) { return context.getSharedPreferences("subhub_onboarding",0).getBoolean("completed",false); }
    public static boolean shouldStart(Context context) {
        if(completed(context)) return false;
        boolean existing=ControllerPinManager.hasCredentials(context) || ControllerPinManager.allowsUnkeyedAccess(context)
                || context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).getBoolean("has_seen_onboarding",false);
        if(existing) { context.getSharedPreferences("subhub_onboarding",0).edit().putBoolean("completed",true).apply(); return false; }
        return true;
    }
    public static void complete(Context context) {
        context.getSharedPreferences("subhub_onboarding",0).edit().putBoolean("completed",true).commit();
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit().putBoolean("has_seen_onboarding",true).apply();
        new GlobalNoticeState(context).dismiss(GlobalNoticeState.Kind.KEYHOLDER,"");
    }
}
