package com.subhub.app.security;

import android.content.Context;
import android.content.SharedPreferences;
import com.subhub.app.settings.SettingsRepository;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Receiver-owned choices are separate from all remotely writable profile stores. */
public final class SubFeaturePolicy {
    public static final Object LOCK = new Object();
    public static final String STORE = "subhub_local_feature_consent";
    public static final Set<String> FEATURES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("censor", "limits", "wallet", "subliminal", "popup")));
    private static final String HARDCORE = "hardcore_locally_consented";
    private SubFeaturePolicy() { }

    public static boolean chosen(Context context, String feature) {
        requireFeature(feature);
        return preferences(context).getBoolean(feature, true);
    }
    public static boolean hardcoreOverride(Context context) {
        return preferences(context).getBoolean(HARDCORE, false) && new HardcoreModeManager(context).isEnabled();
    }
    public static boolean allowed(Context context, String feature) {
        return chosen(context, feature) || hardcoreOverride(context);
    }
    public static boolean setAllowed(Context context, String feature, boolean allowed) {
        requireFeature(feature);
        synchronized (LOCK) {
            if (!DeviceRolePreferences.supportsSub(context)) return false;
            boolean wasAllowed = allowed(context, feature);
            SharedPreferences.Editor edit = preferences(context).edit().putBoolean(feature, allowed);
            if ("tasks".equals(feature) && allowed && !wasAllowed)
                edit.putLong("tasks_resume_after", System.currentTimeMillis());
            if (!edit.commit()) return false;
            changed(context);
            return true;
        }
    }
    public static boolean consentHardcore(Context context, boolean consent) {
        synchronized (LOCK) {
            if (consent && (!DeviceRolePreferences.supportsSub(context)
                    || DeviceRolePreferences.isDomWorkspace(context))) return false;
            boolean saved = preferences(context).edit().putBoolean(HARDCORE, consent).commit();
            if (saved) changed(context);
            return saved;
        }
    }
    public static void changed(Context context) {
        SharedPreferences settings = context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0);
        settings.edit().putLong("sub_feature_policy_revision", settings.getLong("sub_feature_policy_revision", 0) + 1).apply();
    }
    private static SharedPreferences preferences(Context context) { return context.getSharedPreferences(STORE, 0); }
    private static void requireFeature(String feature) {
        if (!FEATURES.contains(feature) && !"tasks".equals(feature)) throw new IllegalArgumentException("Unknown feature");
    }
}
