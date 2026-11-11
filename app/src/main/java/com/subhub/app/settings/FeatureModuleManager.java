package com.subhub.app.settings;

import android.content.Context;
import android.content.SharedPreferences;

/** Optional effects and compatibility availability; Limits and Wallet use their own rules. */
public final class FeatureModuleManager {
    public static final String KEY_CENSOR_ENABLED = "module_censor_enabled";
    public static final String KEY_LIMITS_ENABLED = "module_limits_enabled";
    public static final String KEY_WALLET_ENABLED = "module_wallet_enabled";
    public static final String KEY_SUBLIMINAL_ENABLED = "module_subliminal_enabled";

    private final SharedPreferences preferences;
    private final Context context;
    private final boolean internal;

    public FeatureModuleManager(Context context) {
        this.context = context.getApplicationContext();
        internal = com.subhub.app.configuration.FeatureEditorSession.internal(this.context);
        preferences = this.context.getSharedPreferences(
                SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE);
        if (!internal && preferences.contains(KEY_LIMITS_ENABLED)) synchronized (FeatureModuleManager.class) {
            if (preferences.contains(KEY_LIMITS_ENABLED)) {
                SharedPreferences.Editor edit = preferences.edit().remove(KEY_LIMITS_ENABLED);
                if (!preferences.getBoolean(KEY_LIMITS_ENABLED, true)) {
                    edit.putBoolean(com.subhub.app.appmode.AppTimerManager.KEY_PER_APP_ENABLED, false)
                            .putBoolean(com.subhub.app.appmode.AppTimerManager.KEY_TOTAL_ENABLED, false);
                }
                edit.apply();
            }
        }
    }

    public boolean isCensorEnabled() {
        return (internal || com.subhub.app.security.SubFeaturePolicy.allowed(context, "censor")) && preferences.getBoolean(KEY_CENSOR_ENABLED, true);
    }

    public boolean isLimitsEnabled() {
        return internal || com.subhub.app.security.SubFeaturePolicy.allowed(context, "limits");
    }

    public boolean isWalletEnabled() {
        return internal || com.subhub.app.security.SubFeaturePolicy.allowed(context, "wallet");
    }

    public boolean isSubliminalEnabled() {
        return (internal || com.subhub.app.security.SubFeaturePolicy.allowed(context, "subliminal")) && preferences.getBoolean(KEY_SUBLIMINAL_ENABLED, false);
    }

    public void setCensorEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_CENSOR_ENABLED, enabled).apply();
    }

    public void setSubliminalEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_SUBLIMINAL_ENABLED, enabled).apply();
    }

    public boolean hasRuntimeFeature() {
        if (internal) return isCensorEnabled() || isSubliminalEnabled()
                || preferences.getBoolean(com.subhub.app.appmode.AppTimerManager.KEY_PER_APP_ENABLED, false)
                || preferences.getBoolean(com.subhub.app.appmode.AppTimerManager.KEY_TOTAL_ENABLED, false)
                || preferences.getBoolean(com.subhub.app.popup.PopupStormSettings.K_ENABLED, false)
                || com.subhub.app.penance.PenanceRuleSettings.forEditor(context).isEnabled();
        return isCensorEnabled()
                || new com.subhub.app.appmode.AppTimerManager(context).loadSettings().anyEnabled()
                || new com.subhub.app.penance.PenanceManager(context).isEnabled() || isSubliminalEnabled()
                || com.subhub.app.popup.PopupStormSettings.load(context).isEnabled();
    }

    public void save(boolean censor, boolean limits, boolean wallet, boolean subliminal) {
        // Internal catalogs have no retired device keys or financial/runtime migration authority.
        if (!internal) new com.subhub.app.penance.PenanceManager(context);
        SharedPreferences.Editor edit = preferences.edit()
                .putBoolean(KEY_CENSOR_ENABLED, censor)
                .putBoolean(KEY_SUBLIMINAL_ENABLED, subliminal);
        if (!internal) edit.remove(KEY_LIMITS_ENABLED);
        edit.apply();
    }

    /** Compatibility overload used by older callers; preserves the independent new module. */
    public void save(boolean censor, boolean limits, boolean wallet) {
        save(censor, limits, wallet, isSubliminalEnabled());
    }
}
