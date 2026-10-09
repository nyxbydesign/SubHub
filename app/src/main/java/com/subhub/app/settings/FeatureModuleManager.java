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

    public FeatureModuleManager(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(
                SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE);
        if (preferences.contains(KEY_LIMITS_ENABLED)) synchronized (FeatureModuleManager.class) {
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
        return preferences.getBoolean(KEY_CENSOR_ENABLED, true);
    }

    public boolean isLimitsEnabled() {
        return true;
    }

    public boolean isWalletEnabled() {
        return true;
    }

    public boolean isSubliminalEnabled() {
        return preferences.getBoolean(KEY_SUBLIMINAL_ENABLED, false);
    }

    public void setCensorEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_CENSOR_ENABLED, enabled).apply();
    }

    public void setSubliminalEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_SUBLIMINAL_ENABLED, enabled).apply();
    }

    public boolean hasRuntimeFeature() {
        return isCensorEnabled()
                || new com.subhub.app.appmode.AppTimerManager(context).loadSettings().anyEnabled()
                || new com.subhub.app.penance.PenanceManager(context).isEnabled() || isSubliminalEnabled()
                || preferences.getBoolean(com.subhub.app.popup.PopupStormSettings.K_ENABLED, false);
    }

    public void save(boolean censor, boolean limits, boolean wallet, boolean subliminal) {
        // Normalize legacy Wallet state before discarding its retired page switch.
        new com.subhub.app.penance.PenanceManager(context);
        preferences.edit()
                .putBoolean(KEY_CENSOR_ENABLED, censor)
                .remove(KEY_LIMITS_ENABLED)
                .putBoolean(KEY_SUBLIMINAL_ENABLED, subliminal)
                .apply();
    }

    /** Compatibility overload used by older callers; preserves the independent new module. */
    public void save(boolean censor, boolean limits, boolean wallet) {
        save(censor, limits, wallet, isSubliminalEnabled());
    }
}
