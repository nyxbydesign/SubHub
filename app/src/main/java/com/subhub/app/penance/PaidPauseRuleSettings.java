package com.subhub.app.penance;

import android.content.Context;
import android.content.SharedPreferences;
import com.subhub.app.configuration.FeatureEditorSession;

/** Configuration only, with no purchase, pause activation, expiry, scheduling or rearm authority. */
public interface PaidPauseRuleSettings {
    static PaidPauseRuleSettings forEditor(Context context) {
        Context scope = context.getApplicationContext();
        if (!FeatureEditorSession.internal(scope)) return new PaidPauseManager(scope);
        return configuration(scope.getSharedPreferences(PenanceManager.PREFS_NAME, Context.MODE_PRIVATE));
    }
    static PaidPauseRuleSettings configuration(SharedPreferences preferences) {
        java.util.Objects.requireNonNull(preferences);
        return new PaidPauseRuleSettings() {
            @Override public boolean isEnabled() { return preferences.getBoolean(PaidPauseManager.KEY_ENABLED, false); }
            @Override public int getPriceCents() { return clampPrice(preferences.getInt("paid_pause_price_cents", PaidPauseManager.DEFAULT_PRICE_CENTS)); }
            @Override public int getDurationMinutes() { return clampDuration(preferences.getInt("paid_pause_duration_minutes", PaidPauseManager.DEFAULT_DURATION_MINUTES)); }
            @Override public void configure(boolean enabled, int price, int duration) {
                preferences.edit().putBoolean(PaidPauseManager.KEY_ENABLED, enabled)
                        .putInt("paid_pause_price_cents", clampPrice(price))
                        .putInt("paid_pause_duration_minutes", clampDuration(duration)).apply();
            }
        };
    }
    boolean isEnabled();
    int getPriceCents();
    int getDurationMinutes();
    void configure(boolean enabled, int priceCents, int durationMinutes);
    private static int clampPrice(int value) { return Math.max(PaidPauseManager.MIN_PRICE_CENTS, Math.min(PaidPauseManager.MAX_PRICE_CENTS, value)); }
    private static int clampDuration(int value) { return Math.max(PaidPauseManager.MIN_DURATION_MINUTES, Math.min(PaidPauseManager.MAX_DURATION_MINUTES, value)); }
}
