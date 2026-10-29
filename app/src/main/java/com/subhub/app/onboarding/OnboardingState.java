package com.subhub.app.onboarding;

import android.content.Context;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.SettingsRepository;

public final class OnboardingState {
    private OnboardingState() { }
    public static boolean completed(Context context) { return context.getSharedPreferences("subhub_onboarding",0).getBoolean("completed",false); }
    public static boolean inProgress(Context context) { return context.getSharedPreferences("subhub_onboarding",0).getBoolean("in_progress",false); }
    public static void begin(Context context) { context.getSharedPreferences("subhub_onboarding",0).edit().putBoolean("in_progress",true).commit(); }
    /** Only a device without retained setup or controller access may choose its first role without authentication. */
    public static boolean isPristine(Context context) {
        return !completed(context)
                && !context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).getBoolean("has_seen_onboarding", false)
                && !ControllerPinManager.hasCredentials(context) && !ControllerPinManager.allowsUnkeyedAccess(context);
    }
    public static boolean shouldStart(Context context) {
        if(completed(context)) return false;
        if(inProgress(context)) return true;
        boolean existing=ControllerPinManager.hasCredentials(context) || ControllerPinManager.allowsUnkeyedAccess(context)
                || context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).getBoolean("has_seen_onboarding",false);
        if(existing) { context.getSharedPreferences("subhub_onboarding",0).edit().putBoolean("completed",true).apply(); return false; }
        return true;
    }
    public static void complete(Context context) {
        context.getSharedPreferences("subhub_onboarding",0).edit()
                .putBoolean("completed",true).remove("in_progress")
                .remove("draft_flow_version").remove("draft_wallet_rules")
                .remove("draft_page").remove("draft_role").remove("draft_role_chosen")
                .remove("draft_step").remove("draft_censor").remove("draft_limits").remove("draft_wallet")
                .remove("draft_whispers").remove("draft_style").remove("draft_appearance_changed")
                .remove("draft_appearance_authorized").commit();
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit().putBoolean("has_seen_onboarding",true).apply();
        new GlobalNoticeState(context).dismiss(GlobalNoticeState.Kind.KEYHOLDER,"");
    }
}
