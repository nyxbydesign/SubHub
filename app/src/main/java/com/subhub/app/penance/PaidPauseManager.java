package com.subhub.app.penance;

import android.content.Context;
import android.content.SharedPreferences;

import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.appmode.SessionExpiryAlarms;
import com.subhub.app.appmode.SessionExpiryEffects;
import com.subhub.app.remote.RemoteSessionClock;
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
    private static final String KEY_SETTLEMENT = "paid_pause_settlement_v1";

    private final Context context;
    private final SharedPreferences preferences;

    public PaidPauseManager(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(
                PenanceManager.PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean isEnabled() {
        return com.subhub.app.security.SubFeaturePolicy.allowed(context, "wallet") && preferences.getBoolean(KEY_ENABLED, false);
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
        String identity;
        long until;
        synchronized (RuntimeSessionIdentity.LOCK) {
            until = preferences.getLong(KEY_ACTIVE_UNTIL, 0L);
            if (until <= 0L) return false;
            identity = preferences.getString("paid_pause_session_identity", "");
        }
        if (until <= System.currentTimeMillis()) {
            finishMatching(identity, until, true);
            return false;
        }
        // A nested runtime observation must not introduce an Android policy lookup under Runtime.
        if (!Thread.holdsLock(RuntimeSessionIdentity.LOCK) && !new PenanceManager(context).isEnabled()) {
            finishMatching(identity, until, false);
            return false;
        }
        synchronized (RuntimeSessionIdentity.LOCK) {
            return until == preferences.getLong(KEY_ACTIVE_UNTIL, 0L)
                    && identity.equals(preferences.getString("paid_pause_session_identity", ""));
        }
    }

    /** A history read must not call finish(), re-arm capture or cancel payment state. */
    public boolean observedActive() {
        return preferences.getLong(KEY_ACTIVE_UNTIL, 0L) > System.currentTimeMillis()
                && PenanceManager.observedEnabled(context);
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
        synchronized (RuntimeSessionIdentity.LOCK) {
            if (!com.subhub.app.remote.RemoteSessionNative.beforeLocalStart(context)) return false;
            if (!canPurchase()) return false;
            boolean rearm = isProtectionRunning();
            long until = System.currentTimeMillis() + getDurationMinutes() * 60_000L;
            String retired = preferences.getString("paid_pause_session_identity", "");
            String identity = java.util.UUID.randomUUID().toString();
            ScreenCaptureService.StopTarget capture = ScreenCaptureService.observedStopTarget(context);
            if (!preferences.edit().putLong(KEY_ACTIVE_UNTIL, until)
                    .putString("paid_pause_session_identity", identity).putBoolean(KEY_REARM, rearm).remove(KEY_SETTLEMENT).commit()) return false;
            RuntimeSessionIdentity.changed();
            new AppModeManager(context).setArmed(false);
            SessionExpiryAlarms.update(context, SessionExpiryAlarms.Kind.PAUSE, retired, identity, until);
            SessionExpiryEffects.post(context, RuntimeSessionIdentity.componentObservation(context), capture, false, false);
            return true;
        }
    }

    static final class SettlementActivation {
        final String identity, retired;
        final long until;
        final ScreenCaptureService.StopTarget capture;
        SettlementActivation(String identity, String retired, long until, ScreenCaptureService.StopTarget capture) {
            this.identity = identity; this.retired = retired; this.until = until; this.capture = capture;
        }
    }
    /** The verified Wallet transaction owns both its paid rows and the exact pause grant in this store. */
    SettlementActivation prepareSettlement(String settlement, SharedPreferences.Editor completion) {
        if (!Thread.holdsLock(RuntimeSessionIdentity.LOCK)) throw new IllegalStateException("Runtime transaction required");
        if (!com.subhub.app.remote.RemoteSessionNative.beforeLocalStart(context) || !canPurchase()) return null;
        String identity = java.util.UUID.randomUUID().toString();
        String retired = preferences.getString("paid_pause_session_identity", "");
        long until = System.currentTimeMillis() + getDurationMinutes() * 60_000L;
        completion.putLong(KEY_ACTIVE_UNTIL, until).putString("paid_pause_session_identity", identity)
                .putBoolean(KEY_REARM, isProtectionRunning()).putString(KEY_SETTLEMENT, settlement);
        return new SettlementActivation(identity, retired, until, ScreenCaptureService.observedStopTarget(context));
    }
    void finishSettlement(SettlementActivation activation) {
        if (!Thread.holdsLock(RuntimeSessionIdentity.LOCK)) throw new IllegalStateException("Runtime transaction required");
        if (!activation.identity.equals(preferences.getString("paid_pause_session_identity", ""))
                || activation.until != preferences.getLong(KEY_ACTIVE_UNTIL, 0)) return;
        RuntimeSessionIdentity.changed();
        new AppModeManager(context).setArmed(false);
        SessionExpiryAlarms.update(context, SessionExpiryAlarms.Kind.PAUSE, activation.retired, activation.identity, activation.until);
        SessionExpiryEffects.post(context, RuntimeSessionIdentity.componentObservation(context), activation.capture, false, false);
    }
    /** Reuse the acknowledged grant; a replay never chooses a new identity or extends its original deadline. */
    void reconcileSettlement(String settlement) {
        synchronized (RuntimeSessionIdentity.LOCK) {
            if (!settlement.equals(preferences.getString(KEY_SETTLEMENT, ""))) return;
            if (preferences.getLong(KEY_ACTIVE_UNTIL, 0) <= 0) return;
            applyBootPolicy();
        }
    }

    public void applyBootPolicy() {
        if (!isActive()) return;
        synchronized (RuntimeSessionIdentity.LOCK) {
            long until = preferences.getLong(KEY_ACTIVE_UNTIL, 0L);
            if (until <= System.currentTimeMillis()) return;
            String identity = preferences.getString("paid_pause_session_identity", "");
            if (!SessionExpiryAlarms.validIdentity(identity)) {
                identity = java.util.UUID.randomUUID().toString();
                if (!preferences.edit().putString("paid_pause_session_identity", identity).commit()) return;
                RuntimeSessionIdentity.changed();
            }
            ScreenCaptureService.StopTarget capture = ScreenCaptureService.observedStopTarget(context);
            new AppModeManager(context).setArmed(false);
            SessionExpiryAlarms.update(context, SessionExpiryAlarms.Kind.PAUSE, "", identity, until);
            SessionExpiryEffects.post(context, RuntimeSessionIdentity.componentObservation(context), capture, false, false);
        }
    }

    /** Explicit local recovery may finish a future pause; alarms use finishIfDue instead. */
    public void finish() {
        finishMatching(null, 0L, false);
    }

    public boolean finishIfDue(String expectedIdentity) {
        if (expectedIdentity != null && !SessionExpiryAlarms.validIdentity(expectedIdentity)) return false;
        return finishMatching(expectedIdentity, 0L, true);
    }

    /** Native Stop bookkeeping can remove only its recorded pause and must never re-arm protection. */
    public boolean finishNativeStop(String expectedIdentity) {
        if (expectedIdentity == null || !expectedIdentity.isEmpty() && !SessionExpiryAlarms.validIdentity(expectedIdentity))
            throw new IllegalArgumentException("Pause identity required");
        synchronized (RuntimeSessionIdentity.LOCK) {
            String current = preferences.getString("paid_pause_session_identity", "");
            long until = preferences.getLong(KEY_ACTIVE_UNTIL, 0L);
            if (current.isEmpty() && until == 0L) {
                // A failed commit may already have removed the pause from memory, but not from disk.
                return preferences.edit().putString("remote_native_pause_fence", java.util.UUID.randomUUID().toString()).commit();
            }
            if (expectedIdentity.isEmpty() || !expectedIdentity.equals(current)) return false;
            boolean saved = preferences.edit().remove("paid_pause_session_identity")
                    .remove(KEY_ACTIVE_UNTIL).remove(KEY_REARM).commit();
            RuntimeSessionIdentity.changed();
            if (saved) SessionExpiryAlarms.update(context, SessionExpiryAlarms.Kind.PAUSE, expectedIdentity, "", 0L);
            return saved;
        }
    }

    private boolean finishMatching(String expectedIdentity, long expectedUntil, boolean dueOnly) {
        Boolean hardcore = null;
        if (!Thread.holdsLock(RuntimeSessionIdentity.LOCK)
                && !Thread.holdsLock(com.subhub.app.security.SubFeaturePolicy.LOCK)) {
            // Android admin status is an observation, never a query made inside the runtime transaction.
            hardcore = context.getSharedPreferences(com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME, 0)
                    .getBoolean(HardcoreModeManager.KEY_REQUESTED, false) && new HardcoreModeManager(context).isEnabled();
        }
        synchronized (RuntimeSessionIdentity.LOCK) {
            return finishLocked(expectedIdentity, expectedUntil, dueOnly, hardcore);
        }
    }

    private boolean finishLocked(String expectedIdentity, long expectedUntil, boolean dueOnly, Boolean hardcore) {
        String identity = preferences.getString("paid_pause_session_identity", "");
        long until = preferences.getLong(KEY_ACTIVE_UNTIL, 0L);
        if (until <= 0L || expectedIdentity != null && !expectedIdentity.equals(identity)
                || expectedUntil > 0L && expectedUntil != until
                || dueOnly && until > System.currentTimeMillis()) return false;
        if (com.subhub.app.remote.RemoteSessionNative.requiresNonRearmingPauseCleanup(context, identity)) {
            return finishNativeStop(identity) && com.subhub.app.remote.RemoteSessionNative.reconcile(context);
        }
        boolean rearm = preferences.getBoolean(KEY_REARM, false);
        boolean saved = preferences.edit().remove("paid_pause_session_identity")
                .remove(KEY_ACTIVE_UNTIL).remove(KEY_REARM).commit();
        RuntimeSessionIdentity.changed();
        if (!saved) return false;
        SessionExpiryAlarms.update(context, SessionExpiryAlarms.Kind.PAUSE, identity, "", 0L);
        boolean pact = RuntimeSessionIdentity.observe(context, RemoteSessionClock.local()).pact;
        boolean observedHardcore = Boolean.TRUE.equals(hardcore)
                && context.getSharedPreferences(com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME, 0)
                        .getBoolean(HardcoreModeManager.KEY_REQUESTED, false);
        boolean arm = rearm && (pact || observedHardcore);
        if (arm) new AppModeManager(context).setArmed(true);
        SessionExpiryEffects.post(context, RuntimeSessionIdentity.componentObservation(context), null,
                rearm && !pact && hardcore == null, arm);
        return true;
    }

    private boolean isProtectionRunning() {
        return ScreenCaptureService.isRunning()
                || ScreenshotAccessibilityService.isRecognitionActive()
                || new AppModeManager(context).isArmed();
    }

    public static int clampPrice(int cents) {
        return Math.max(MIN_PRICE_CENTS, Math.min(MAX_PRICE_CENTS, cents));
    }

    public static int clampMinutes(int minutes) {
        return Math.max(MIN_DURATION_MINUTES, Math.min(MAX_DURATION_MINUTES, minutes));
    }
}
