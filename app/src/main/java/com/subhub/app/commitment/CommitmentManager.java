package com.subhub.app.commitment;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.ResumeNotificationManager;
import com.subhub.app.penance.HardcoreAutoPayManager;
import com.subhub.app.penance.PaidPauseManager;
import com.subhub.app.service.ScreenCaptureService;
import com.subhub.app.settings.SettingsRepository;

/** Bounded, app-local commitment pact guarded by the controller PIN. */
public final class CommitmentManager {
    public static final long MIN_DURATION_MS = 30L * 60L * 1000L;
    public static final long MAX_DURATION_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final String KEY_ENDS_AT = "commitment_ends_at";
    private static final String KEY_STARTED_AT = "commitment_started_at";
    private static final String KEY_SALT = "commitment_code_salt";
    private static final String KEY_HASH = "commitment_code_hash";
    private static final String KEY_DURATION = "commitment_duration";
    private static final int EXPIRY_REQUEST = 8317;

    private CommitmentManager() {}

    public static boolean start(Context context, long requestedDurationMs) {
        long duration = Math.max(MIN_DURATION_MS,
                Math.min(MAX_DURATION_MS, requestedDurationMs));
        return start(context, duration, duration, false);
    }

    /** Selects once; neither UI recreation nor reboot draws another duration. */
    public static synchronized boolean start(Context context, long minimum, long maximum, boolean hideCountdown) {
        if (isActive(context)) return false;
        long duration = PactDuration.choose(minimum, maximum, new java.security.SecureRandom());
        long now = System.currentTimeMillis();
        long endsAt = now + duration;
        boolean saved = preferences(context).edit()
                .putLong(KEY_STARTED_AT, now)
                .putLong(KEY_ENDS_AT, endsAt)
                .putLong(KEY_DURATION, duration)
                .putBoolean("commitment_hide_countdown", hideCountdown)
                .putLong("commitment_range_min", minimum).putLong("commitment_range_max", maximum)
                .putLong("commitment_ends_elapsed", android.os.SystemClock.elapsedRealtime() + duration)
                .putInt("commitment_boot", android.provider.Settings.Global.getInt(context.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1))
                .remove(KEY_SALT).remove(KEY_HASH)
                .commit();
        if (!saved) return false;
        scheduleExpiry(context, endsAt);
        reinforceProtection(context);
        HardcoreAutoPayManager.schedule(context);
        return true;
    }

    /** Compatibility for older callers; keeper codes are no longer part of a pact. */
    public static boolean start(Context context, long requestedDurationMs, String ignoredCode) {
        return start(context, requestedDurationMs);
    }

    public static boolean isActive(Context context) {
        SharedPreferences values = preferences(context);
        long end = values.getLong(KEY_ENDS_AT, 0L);
        if (remaining(values, context) <= 0) {
            if (end != 0L) expire(context);
            return false;
        }
        return true;
    }

    public static long remainingMillis(Context context) {
        return isActive(context)
                ? Math.max(0L, remaining(preferences(context), context))
                : 0L;
    }

    private static long remaining(SharedPreferences values, Context context) {
        if (values.getLong(KEY_ENDS_AT, 0L) == 0) return 0;
        int boot = android.provider.Settings.Global.getInt(context.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1);
        if (boot >= 0 && values.contains("commitment_ends_elapsed") && values.getInt("commitment_boot", -2) == boot)
            return values.getLong("commitment_ends_elapsed", 0) - android.os.SystemClock.elapsedRealtime();
        return values.getLong(KEY_ENDS_AT, 0) - System.currentTimeMillis();
    }

    public static boolean isCountdownHidden(Context context) {
        return isActive(context) && preferences(context).getBoolean("commitment_hide_countdown", false);
    }

    public static String countdownLabel(Context context) {
        return isCountdownHidden(context) ? context.getString(com.subhub.app.R.string.pact_time_hidden)
                : CommitmentActivity.formatDuration(remainingMillis(context));
    }

    public static boolean verifyAndRelease(Context context, String code) {
        return !isActive(context);
    }

    /** Dom mode may release the pact without the separate keeper code. */
    public static void emergencyRelease(Context context) {
        expire(context);
    }

    /** Sub mode cannot stop protection while the pact is active. */
    public static boolean mayStopProtection(Context context) {
        return !isActive(context);
    }

    /** Re-arms the persistent capture path when Android has already granted it. */
    public static void reinforceProtection(Context context) {
        if (!isActive(context) || new PaidPauseManager(context).isActive()) return;
        new AppModeManager(context).setArmed(true);
        ResumeNotificationManager.show(context);
    }

    /** A sealed pact always re-arms; otherwise App Mode retains its stored state. */
    public static void applyBootPolicy(Context context) {
        if (!isActive(context)) return;
        scheduleExpiry(context, preferences(context).getLong(KEY_ENDS_AT, 0L));
        reinforceProtection(context);
    }

    public static long originalDurationMillis(Context context) {
        return preferences(context).getLong(KEY_DURATION, 0L);
    }

    public static long startedAtMillis(Context context) {
        return preferences(context).getLong(KEY_STARTED_AT, 0L);
    }

    static void expire(Context context) {
        preferences(context).edit()
                .remove(KEY_STARTED_AT).remove(KEY_ENDS_AT).remove(KEY_DURATION)
                .remove("commitment_hide_countdown").remove("commitment_range_min").remove("commitment_range_max")
                .remove("commitment_ends_elapsed").remove("commitment_boot")
                .remove(KEY_SALT).remove(KEY_HASH).apply();
        cancelExpiry(context);
        new AppModeManager(context).setArmed(false);
        context.stopService(new Intent(context, ScreenCaptureService.class));
        ResumeNotificationManager.cancel(context);
        HardcoreAutoPayManager.cancel(context);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(
                SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    private static void scheduleExpiry(Context context, long endsAt) {
        alarm(context).setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + Math.max(0, remaining(preferences(context), context)),
                expiryIntent(context));
    }

    private static void cancelExpiry(Context context) {
        alarm(context).cancel(expiryIntent(context));
    }

    private static AlarmManager alarm(Context context) {
        return (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    }

    private static PendingIntent expiryIntent(Context context) {
        Intent intent = new Intent(context, CommitmentExpiryReceiver.class)
                .setAction("com.subhub.app.action.PACT_EXPIRED");
        return PendingIntent.getBroadcast(context, EXPIRY_REQUEST, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
