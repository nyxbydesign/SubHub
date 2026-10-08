package com.subhub.app.penance;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.subhub.app.commitment.CommitmentManager;
import com.subhub.app.security.HardcoreModeManager;

/** Explicit Dom authorization and one-shot scheduling for saved-wallet Hardcore payments. */
public final class HardcoreAutoPayManager {
    static final String ACTION_AUTO_PAY = "com.subhub.app.action.HARDCORE_AUTO_PAY";
    private static final String PREFS = "subhub_hardcore_auto_pay";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_BOUNDARY = "paypal_boundary";
    private static final String KEY_LAST_STATUS = "last_status";
    private static final String KEY_LAST_ERROR = "last_error";
    private static final int REQUEST_CODE = 9062;
    private static final long RETRY_DELAY_MS = 15L * 60L * 1000L;

    private final Context context;
    private final SharedPreferences preferences;

    public HardcoreAutoPayManager(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean enable() {
        PayPalCredentialStore store = new PayPalCredentialStore(context);
        PayPalCredentialStore.Credentials credentials = store.load();
        if (!credentials.isComplete() || !store.hasVerifiedCredentials()
                || !store.vaultState().isReady()) return false;
        PenanceManager penance = new PenanceManager(context);
        PenanceManager.CheckoutMode checkoutMode = penance.getActiveCheckoutMode();
        if (checkoutMode != PenanceManager.CheckoutMode.NONE
                && checkoutMode != PenanceManager.CheckoutMode.HARDCORE_AUTO) {
            String settlementId = penance.getActiveSettlementId();
            if (!settlementId.isEmpty()) penance.cancelSettlement(settlementId);
        }
        preferences.edit().putBoolean(KEY_ENABLED, true)
                .putString(KEY_BOUNDARY, credentials.boundaryId())
                .putString(KEY_LAST_STATUS, "READY").remove(KEY_LAST_ERROR).commit();
        schedule(context);
        return true;
    }

    public void disable() {
        preferences.edit().putBoolean(KEY_ENABLED, false)
                .remove(KEY_BOUNDARY).putString(KEY_LAST_STATUS, "OFF").commit();
        cancel(context);
    }

    public boolean isEnabled() {
        if (!preferences.getBoolean(KEY_ENABLED, false)) return false;
        PayPalCredentialStore store = new PayPalCredentialStore(context);
        PayPalCredentialStore.Credentials credentials = store.load();
        String boundary = preferences.getString(KEY_BOUNDARY, "");
        if (!credentials.isComplete() || !store.hasVerifiedCredentials()
                || !store.vaultState().isReady()
                || !credentials.boundaryId().equals(boundary)) {
            pause("Saved wallet authorization is no longer ready");
            return false;
        }
        return true;
    }

    public boolean isConfigured() {
        return preferences.getBoolean(KEY_ENABLED, false);
    }

    public boolean isAutomaticContextActive() {
        return new HardcoreModeManager(context).isEnabled()
                && CommitmentManager.isActive(context)
                && new PenanceManager(context).isEnabled();
    }

    public boolean isEligibleNow() {
        return isEnabled() && isAutomaticContextActive();
    }

    public String status() {
        String value = preferences.getString(KEY_LAST_STATUS, "OFF");
        return value == null ? "OFF" : value;
    }

    public String lastError() {
        String value = preferences.getString(KEY_LAST_ERROR, "");
        return value == null ? "" : value;
    }

    void markPaid() {
        preferences.edit().putString(KEY_LAST_STATUS, "PAID")
                .remove(KEY_LAST_ERROR).apply();
    }

    void pause(String reason) {
        preferences.edit().putBoolean(KEY_ENABLED, false)
                .putString(KEY_LAST_STATUS, "PAUSED")
                .putString(KEY_LAST_ERROR, reason == null ? "" : reason)
                .apply();
        cancel(context);
    }

    public static void schedule(Context context) {
        Context app = context.getApplicationContext();
        HardcoreAutoPayManager manager = new HardcoreAutoPayManager(app);
        cancel(app);
        if (!manager.isEligibleNow()) return;
        PenanceManager penance = new PenanceManager(app);
        long now = System.currentTimeMillis();
        long trigger;
        if (penance.getActiveCheckoutMode() == PenanceManager.CheckoutMode.HARDCORE_AUTO) {
            trigger = now + RETRY_DELAY_MS;
        } else {
            trigger = penance.nextAutomaticDueAtMillis(now);
            if (trigger <= 0L) return;
            trigger = Math.max(now + 1_000L, trigger);
        }
        alarm(app).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending(app));
    }

    static void scheduleRetry(Context context) {
        Context app = context.getApplicationContext();
        if (!new HardcoreAutoPayManager(app).isEligibleNow()) return;
        alarm(app).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + RETRY_DELAY_MS, pending(app));
    }

    public static void cancel(Context context) {
        Context app = context.getApplicationContext();
        alarm(app).cancel(pending(app));
    }

    private static AlarmManager alarm(Context context) {
        return (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    }

    private static PendingIntent pending(Context context) {
        Intent intent = new Intent(context, HardcoreAutoPayReceiver.class)
                .setAction(ACTION_AUTO_PAY);
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
