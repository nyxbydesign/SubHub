package com.subhub.app.penance;

import android.content.SharedPreferences;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/** Editable internal rule values. No ledger, payment account, scheduler, statistics or Context. */
public final class InternalPenanceRules implements PenanceRuleSettings {
    private final SharedPreferences preferences;
    public InternalPenanceRules(SharedPreferences preferences) { this.preferences = Objects.requireNonNull(preferences); }
    @Override public String getCurrency() {
        String code = preferences.getString("wallet_currency", PenanceManager.CURRENCY);
        return WalletCurrency.isSupported(code) ? code : PenanceManager.CURRENCY;
    }
    @Override public String money(long cents) { return WalletCurrency.format(getCurrency(), cents); }
    @Override public boolean isEnabled() {
        for (PenanceInfraction rule : PenanceInfraction.values())
            if (rule != PenanceInfraction.PAID_PAUSE && isInfractionEnabled(rule)) return true;
        return preferences.getBoolean(PaidPauseManager.KEY_ENABLED, false);
    }
    @Override public boolean isInfractionEnabled(PenanceInfraction rule) {
        if (rule == PenanceInfraction.PAID_PAUSE) return preferences.getBoolean(PaidPauseManager.KEY_ENABLED, false);
        return preferences.getBoolean(enabled(rule), rule == PenanceInfraction.NEW_DETECTION && rule.enabledByDefault());
    }
    @Override public void disableInfraction(PenanceInfraction rule) {
        if (rule == PenanceInfraction.PAID_PAUSE) preferences.edit().putBoolean(PaidPauseManager.KEY_ENABLED, false).apply();
        else preferences.edit().putBoolean(enabled(rule), false).apply();
    }
    @Override public int getInfractionCents(PenanceInfraction rule) {
        if (rule == PenanceInfraction.PAID_PAUSE)
            return preferences.getInt("paid_pause_price_cents", PaidPauseManager.DEFAULT_PRICE_CENTS);
        return PenancePolicy.clampStrikeCents(preferences.getInt(cents(rule), rule.defaultCents()));
    }
    @Override public int getDailyCapCents() { return preferences.getInt("daily_cap_cents", PenanceManager.DEFAULT_DAILY_CAP_CENTS); }
    @Override public int getWeeklyCapCents() { return preferences.getInt("weekly_cap_cents", PenanceManager.DEFAULT_WEEKLY_CAP_CENTS); }
    @Override public int getMercyMinutes() { return preferences.getInt("mercy_minutes", PenanceManager.DEFAULT_MERCY_MINUTES); }
    @Override public int getDwellSeconds() { return PenancePolicy.clampDwellSeconds(preferences.getInt("dwell_seconds", PenanceManager.DEFAULT_DWELL_SECONDS)); }
    @Override public int getDetectionBatch() { return PenancePolicy.clampDetectionBatch(preferences.getInt("detection_batch", PenanceManager.DEFAULT_DETECTION_BATCH)); }
    @Override public int getTamperCooldownMinutes() { return clampTamper(preferences.getInt("tamper_cooldown_minutes", PenanceManager.DEFAULT_TAMPER_COOLDOWN_MINUTES)); }
    @Override public void configure(boolean enabled, Map<PenanceInfraction, Integer> enabledRuleCosts,
            int dailyCapCents, int weeklyCapCents, int mercyMinutes, int dwellSeconds,
            int detectionBatch, int tamperCooldownMinutes) {
        Map<PenanceInfraction, Integer> rules = enabledRuleCosts == null ? Collections.emptyMap() : enabledRuleCosts;
        int largest = PenancePolicy.MIN_STRIKE_CENTS;
        for (Map.Entry<PenanceInfraction, Integer> rule : rules.entrySet()) {
            if (rule.getKey() != null && rule.getKey() != PenanceInfraction.PAID_PAUSE && rule.getValue() != null)
                largest = Math.max(largest, PenancePolicy.clampStrikeCents(rule.getValue()));
        }
        int daily = PenancePolicy.clampDailyCapCents(dailyCapCents, largest);
        SharedPreferences.Editor edit = preferences.edit()
                .putInt("daily_cap_cents", daily)
                .putInt("weekly_cap_cents", PenancePolicy.clampWeeklyCapCents(weeklyCapCents, daily))
                .putInt("mercy_minutes", PenancePolicy.clampMercyMinutes(mercyMinutes))
                .putInt("dwell_seconds", PenancePolicy.clampDwellSeconds(dwellSeconds))
                .putInt("detection_batch", PenancePolicy.clampDetectionBatch(detectionBatch))
                .putInt("tamper_cooldown_minutes", clampTamper(tamperCooldownMinutes));
        for (PenanceInfraction rule : PenanceInfraction.values()) {
            if (rule == PenanceInfraction.PAID_PAUSE) continue;
            Integer price = rules.get(rule);
            edit.putBoolean(enabled(rule), enabled && rules.containsKey(rule))
                    .putInt(cents(rule), price == null ? getInfractionCents(rule) : PenancePolicy.clampStrikeCents(price));
        }
        edit.apply(); // Internal editor stages these catalog values; its authorized broker owns durable CAS.
    }
    private static int clampTamper(int minutes) {
        return Math.max(PenanceManager.MIN_TAMPER_COOLDOWN_MINUTES, Math.min(PenanceManager.MAX_TAMPER_COOLDOWN_MINUTES, minutes));
    }
    private static String enabled(PenanceInfraction rule) { return "rule_" + Objects.requireNonNull(rule).preferenceKey() + "_enabled"; }
    private static String cents(PenanceInfraction rule) { return "rule_" + Objects.requireNonNull(rule).preferenceKey() + "_cents"; }
}
