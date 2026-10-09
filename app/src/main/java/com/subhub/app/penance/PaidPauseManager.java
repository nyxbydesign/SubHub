package com.subhub.app.penance;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.ResumeNotificationManager;
import com.subhub.app.commitment.CommitmentManager;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.service.ScreenCaptureService;
import com.subhub.app.service.ScreenshotAccessibilityService;
import com.subhub.app.settings.FeatureModuleManager;

/** Configures and enforces a payment-unlocked, automatically expiring protection pause. */
public final class PaidPauseManager {
    public static final int DEFAULT_PRICE_CENTS = 500;
    public static final int DEFAULT_DURATION_MINUTES = 15;
    public static final int MIN_PRICE_CENTS = 50;
    public static final int MAX_PRICE_CENTS = 100_000;
    public static final int MIN_DURATION_MINUTES = 1;
    public static final int MAX_DURATION_MINUTES = 24 * 60;

    public static final String KEY_ENABLED = "paid_pause_enabled";
    private static final String KEY_PRICE_CENTS = "paid_pause_price_cents";
    private static final String KEY_DURATION_MINUTES = "paid_pause_duration_minutes";
    private static final String KEY_ACTIVE_UNTIL = "paid_pause_active_until";
    private static final String KEY_REARM = "paid_pause_rearm";
    private static final int EXPIRY_REQUEST = 8349;

    private final Context context;
    private final SharedPreferences preferences;

    public PaidPauseManager(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(
                PenanceManager.PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean isEnabled() {
        return preferences.getBoolean(KEY_ENABLED, false);
    }

    public int getPriceCents() {
        return clampPrice(preferences.getInt(KEY_PRICE_CENTS, DEFAULT_PRICE_CENTS));
    }

    public int getDurationMinutes() {
        return clampMinutes(preferences.getInt(
                KEY_DURATION_MINUTES, DEFAULT_DURATION_MINUTES));
    }

    public void configure(boolean enabled, int priceCents, int durationMinutes) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled)
                .putInt(KEY_PRICE_CENTS, clampPrice(priceCents))
                .putInt(KEY_DURATION_MINUTES, clampMinutes(durationMinutes)).apply();
    }

    public boolean isActive() {
        long until = preferences.getLong(KEY_ACTIVE_UNTIL, 0L);
        if (until <= 0L) return false;
        if (!new PenanceManager(context).isEnabled()) {
            finish();
            return false;
        }
        if (until <= System.currentTimeMillis()) {
            finish();
            return false;
        }
        return true;
    }

    public long remainingMillis() {
        return isActive() ? Math.max(0L,
                preferences.getLong(KEY_ACTIVE_UNTIL, 0L) - System.currentTimeMillis()) : 0L;
    }

    public boolean canPurchase() {
        if (!new FeatureModuleManager(context).isWalletEnabled()
                || !new PenanceManager(context).isEnabled() || !isEnabled() || isActive()) {
            return false;
        }
        boolean boundedMode = CommitmentManager.isActive(context)
                || new HardcoreModeManager(context).isEnabled();
        return boundedMode && isProtectionRunning();
    }

    /** Called only after the matching checkout is recorded as paid. */
    public boolean activate() {
        if (!canPurchase()) return false;
        boolean rearm = isProtectionRunning();
        long until = System.currentTimeMillis() + getDurationMinutes() * 60_000L;
        preferences.edit().putLong(KEY_ACTIVE_UNTIL, until)
                .putBoolean(KEY_REARM, rearm).commit();
        new AppModeManager(context).setArmed(false);
        context.stopService(new Intent(context, ScreenCaptureService.class));
        ResumeNotificationManager.cancel(context);
        schedule(until);
        return true;
    }

    public void applyBootPolicy() {
        if (!isActive()) return;
        new AppModeManager(context).setArmed(false);
        ResumeNotificationManager.cancel(context);
        schedule(preferences.getLong(KEY_ACTIVE_UNTIL, 0L));
    }

    public void finish() {
        boolean rearm = preferences.getBoolean(KEY_REARM, false);
        preferences.edit().remove(KEY_ACTIVE_UNTIL).remove(KEY_REARM).commit();
        cancel();
        if (rearm && (CommitmentManager.isActive(context)
                || new HardcoreModeManager(context).isEnabled())) {
            new AppModeManager(context).setArmed(true);
            ResumeNotificationManager.show(context);
        }
    }

    private boolean isProtectionRunning() {
        return ScreenCaptureService.isRunning()
                || ScreenshotAccessibilityService.isRecognitionActive()
                || new AppModeManager(context).isArmed();
    }

    private void schedule(long until) {
        AlarmManager alarm = context.getSystemService(AlarmManager.class);
        if (alarm != null) alarm.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, until, expiryIntent());
    }

    private void cancel() {
        AlarmManager alarm = context.getSystemService(AlarmManager.class);
        if (alarm != null) alarm.cancel(expiryIntent());
    }

    private PendingIntent expiryIntent() {
        Intent intent = new Intent(context, PaidPauseExpiryReceiver.class)
                .setAction("com.subhub.app.action.PAID_PAUSE_EXPIRED");
        return PendingIntent.getBroadcast(context, EXPIRY_REQUEST, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static int clampPrice(int cents) {
        return Math.max(MIN_PRICE_CENTS, Math.min(MAX_PRICE_CENTS, cents));
    }

    public static int clampMinutes(int minutes) {
        return Math.max(MIN_DURATION_MINUTES, Math.min(MAX_DURATION_MINUTES, minutes));
    }
}
