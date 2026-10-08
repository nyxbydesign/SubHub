package com.subhub.app.settings;

import android.content.Context;
import android.content.SharedPreferences;

/** App-wide switches for optional product areas. Settings is always available. */
public final class FeatureModuleManager {
    public static final String KEY_CENSOR_ENABLED = "module_censor_enabled";
    public static final String KEY_LIMITS_ENABLED = "module_limits_enabled";
    public static final String KEY_WALLET_ENABLED = "module_wallet_enabled";
    public static final String KEY_SUBLIMINAL_ENABLED = "module_subliminal_enabled";

    private final SharedPreferences preferences;

    public FeatureModuleManager(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(
                SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public boolean isCensorEnabled() {
        return preferences.getBoolean(KEY_CENSOR_ENABLED, true);
    }

    public boolean isLimitsEnabled() {
        return preferences.getBoolean(KEY_LIMITS_ENABLED, true);
    }

    public boolean isWalletEnabled() {
        return preferences.getBoolean(KEY_WALLET_ENABLED, true);
    }

    public boolean isSubliminalEnabled() {
        return preferences.getBoolean(KEY_SUBLIMINAL_ENABLED, false);
    }

    public void setSubliminalEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_SUBLIMINAL_ENABLED, enabled).commit();
    }

    public boolean hasRuntimeFeature() {
        return isCensorEnabled() || isLimitsEnabled() || isSubliminalEnabled()
                || preferences.getBoolean(com.subhub.app.popup.PopupStormSettings.K_ENABLED, false);
    }

    public void save(boolean censor, boolean limits, boolean wallet, boolean subliminal) {
        preferences.edit()
                .putBoolean(KEY_CENSOR_ENABLED, censor)
                .putBoolean(KEY_LIMITS_ENABLED, limits)
                .putBoolean(KEY_WALLET_ENABLED, wallet)
                .putBoolean(KEY_SUBLIMINAL_ENABLED, subliminal)
                .commit();
    }

    /** Compatibility overload used by older callers; preserves the independent new module. */
    public void save(boolean censor, boolean limits, boolean wallet) {
        save(censor, limits, wallet, isSubliminalEnabled());
    }
}
