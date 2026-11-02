package com.subhub.app.commitment;

import android.content.Context;
import android.content.SharedPreferences;

import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.appmode.SessionExpiryAlarms;
import com.subhub.app.appmode.SessionExpiryEffects;
import com.subhub.app.remote.RemoteSessionClock;
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

    private CommitmentManager() {}

    /** Adds the chosen native pact to the same editor as its armed intent and application marker. */
    public static void appendNativeStart(SharedPreferences.Editor edit,
            com.subhub.app.remote.RemoteSessionNative.PreparedStart prepared) {
        if (!Thread.holdsLock(com.subhub.app.appmode.RuntimeSessionIdentity.LOCK))
            throw new IllegalStateException("Native runtime transaction required");
        edit.remove(KEY_SALT).remove(KEY_HASH);
        if (prepared.selectedDuration == -1) {
            edit.remove("commitment_session_identity").remove(KEY_STARTED_AT).remove(KEY_ENDS_AT).remove(KEY_DURATION)
                    .remove("commitment_hide_countdown").remove("commitment_range_min").remove("commitment_range_max")
                    .remove("commitment_ends_elapsed").remove("commitment_boot");
        } else {
            edit.putString("commitment_session_identity", prepared.pactIdentity)
                    .putLong(KEY_STARTED_AT, prepared.statistics.startedAt).putLong(KEY_ENDS_AT, prepared.endsAt)
                    .putLong(KEY_DURATION, prepared.selectedDuration).putBoolean("commitment_hide_countdown", prepared.request.service.hidden)
                    .putLong("commitment_range_min", prepared.request.service.minimum)
                    .putLong("commitment_range_max", prepared.request.service.maximum)
                    .putLong("commitment_ends_elapsed", prepared.endsElapsed).putInt("commitment_boot", prepared.boot);
        }
    }

    /** A native Stop clears the pact in the same main-preference commit as its application marker. */
    public static void appendNativeStop(SharedPreferences.Editor edit) {
        if (!Thread.holdsLock(RuntimeSessionIdentity.LOCK)) throw new IllegalStateException("Runtime transaction required");
        edit.remove("commitment_session_identity").remove(KEY_STARTED_AT).remove(KEY_ENDS_AT).remove(KEY_DURATION)
                .remove("commitment_hide_countdown").remove("commitment_range_min").remove("commitment_range_max")
                .remove("commitment_ends_elapsed").remove("commitment_boot").remove(KEY_SALT).remove(KEY_HASH);
    }

    public static boolean start(Context context, long requestedDurationMs) {
        long duration = Math.max(MIN_DURATION_MS,
                Math.min(MAX_DURATION_MS, requestedDurationMs));
        return start(context, duration, duration, false);
    }

    /** Selects once; neither UI recreation nor reboot draws another duration. */
    public static boolean start(Context context, long minimum, long maximum, boolean hideCountdown) {
        synchronized (RuntimeSessionIdentity.LOCK) {
            if (!com.subhub.app.remote.RemoteSessionNative.beforeLocalStart(context) || isActive(context)) return false;
            long duration = PactDuration.choose(minimum, maximum, new java.security.SecureRandom());
            RemoteSessionClock.Sample now = RemoteSessionClock.local();
            String identity = java.util.UUID.randomUUID().toString();
            String retired = preferences(context).getString("commitment_session_identity", "");
            boolean saved = preferences(context).edit()
                    .putLong(KEY_STARTED_AT, now.wall).putString("commitment_session_identity", identity)
                    .putLong(KEY_ENDS_AT, now.wall + duration).putLong(KEY_DURATION, duration)
                    .putBoolean("commitment_hide_countdown", hideCountdown)
                    .putLong("commitment_range_min", minimum).putLong("commitment_range_max", maximum)
                    .putLong("commitment_ends_elapsed", now.elapsed + duration).putInt("commitment_boot", now.boot)
                    .remove(KEY_SALT).remove(KEY_HASH).commit();
            if (!saved) return false;
            RuntimeSessionIdentity.changed();
            scheduleExpiry(context, retired);
            reinforceProtection(context);
            return true;
        }
    }

    /** Compatibility for older callers; keeper codes are no longer part of a pact. */
    public static boolean start(Context context, long requestedDurationMs, String ignoredCode) {
        return start(context, requestedDurationMs);
    }

    public static boolean isActive(Context context) {
        synchronized (RuntimeSessionIdentity.LOCK) {
            SharedPreferences values = preferences(context);
            if (remaining(values, RemoteSessionClock.local()) > 0) return true;
            if (values.getLong(KEY_ENDS_AT, 0L) != 0L)
                expireLocked(context, values.getString("commitment_session_identity", ""), true);
            return false;
        }
    }

    /** Statistics may observe expiry without releasing a pact or running its cleanup effects. */
    public static boolean observedActive(Context context) {
        return remaining(preferences(context), RemoteSessionClock.local()) > 0;
    }

    public static long remainingMillis(Context context) {
        synchronized (RuntimeSessionIdentity.LOCK) {
            return isActive(context) ? Math.max(0L, remaining(preferences(context), RemoteSessionClock.local())) : 0L;
        }
    }

    private static long remaining(SharedPreferences values, RemoteSessionClock.Sample clock) {
        if (values.getLong(KEY_ENDS_AT, 0L) == 0L) return 0L;
        if (clock.boot >= 0 && values.contains("commitment_ends_elapsed")
                && values.getInt("commitment_boot", -2) == clock.boot)
            return values.getLong("commitment_ends_elapsed", 0L) - clock.elapsed;
        return values.getLong(KEY_ENDS_AT, 0L) - clock.wall;
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
        synchronized (RuntimeSessionIdentity.LOCK) {
            RuntimeSessionIdentity.Observed current = RuntimeSessionIdentity.observe(context, RemoteSessionClock.local());
            if (!current.pact || current.paused) return;
            new AppModeManager(context).setArmed(true);
            SessionExpiryEffects.post(context, RuntimeSessionIdentity.componentObservation(context), null, false, true);
        }
    }

    /** A sealed pact always re-arms; otherwise App Mode retains its stored state. */
    public static void applyBootPolicy(Context context) {
        synchronized (RuntimeSessionIdentity.LOCK) {
            if (!isActive(context)) return;
            String identity = preferences(context).getString("commitment_session_identity", "");
            if (!SessionExpiryAlarms.validIdentity(identity)) {
                identity = java.util.UUID.randomUUID().toString();
                if (!preferences(context).edit().putString("commitment_session_identity", identity).commit()) return;
                RuntimeSessionIdentity.changed();
            }
            scheduleExpiry(context, "");
            reinforceProtection(context);
        }
    }

    public static long originalDurationMillis(Context context) {
        return preferences(context).getLong(KEY_DURATION, 0L);
    }

    public static long startedAtMillis(Context context) {
        return preferences(context).getLong(KEY_STARTED_AT, 0L);
    }

    /** A delayed or legacy alarm can end only a currently due pact. */
    public static boolean expireIfDue(Context context, String expectedIdentity) {
        if (expectedIdentity != null && !SessionExpiryAlarms.validIdentity(expectedIdentity)) return false;
        synchronized (RuntimeSessionIdentity.LOCK) {
            return expireLocked(context, expectedIdentity, true);
        }
    }

    static void expire(Context context) {
        synchronized (RuntimeSessionIdentity.LOCK) { expireLocked(context, null, false); }
    }

    private static boolean expireLocked(Context context, String expectedIdentity, boolean dueOnly) {
        SharedPreferences values = preferences(context);
        String identity = values.getString("commitment_session_identity", "");
        if (expectedIdentity != null && !expectedIdentity.equals(identity)) return false;
        if (dueOnly && (values.getLong(KEY_ENDS_AT, 0L) == 0L
                || remaining(values, RemoteSessionClock.local()) > 0L)) return false;
        ScreenCaptureService.StopTarget capture = ScreenCaptureService.observedStopTarget(context);
        String statistics = context.getSharedPreferences(com.subhub.app.stats.StatsRepository.PREFS_NAME, 0)
                .getString("active_session_identity", "");
        SharedPreferences.Editor edit = values.edit().putBoolean(AppModeManager.KEY_ARMED, false);
        appendNativeStop(edit);
        com.subhub.app.remote.RemoteSessionNative.appendLocalEnd(context, edit);
        boolean saved = edit.commit();
        RuntimeSessionIdentity.changed();
        if (!saved) return false;
        com.subhub.app.remote.RemoteSessionNative.reconcile(context);
        if (SessionExpiryAlarms.validIdentity(statistics))
            com.subhub.app.remote.RemoteSessionNative.statistics(context).endSessionForIdentity(statistics);
        SessionExpiryAlarms.update(context, SessionExpiryAlarms.Kind.PACT, identity, "", 0L);
        SessionExpiryEffects.post(context, RuntimeSessionIdentity.componentObservation(context), capture, false, false);
        return true;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    private static void scheduleExpiry(Context context, String retired) {
        SharedPreferences values = preferences(context);
        RemoteSessionClock.Sample clock = RemoteSessionClock.local();
        SessionExpiryAlarms.update(context, SessionExpiryAlarms.Kind.PACT, retired,
                values.getString("commitment_session_identity", ""),
                clock.elapsed + Math.max(0L, remaining(values, clock)));
    }
}
