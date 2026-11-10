package com.subhub.app.security;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;

import com.subhub.app.R;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.ResumeNotificationManager;
import com.subhub.app.penance.HardcoreAutoPayManager;
import com.subhub.app.penance.PaidPauseManager;
import com.subhub.app.penance.TamperTributeReporter;
import com.subhub.app.settings.SettingsRepository;

/**
 * Explicit, reversible Device Admin friction for Hardcore Mode.
 *
 * <p>This class deliberately declares no destructive device policies. Active admin status only
 * adds Android's normal deactivate-before-uninstall step. It cannot grant capture, keep a process
 * alive, hide system settings, or prevent the user from revoking admin access.</p>
 */
public final class HardcoreModeManager {
    public static final String KEY_REQUESTED = "hardcore_mode_requested";

    private final Context context;
    private final SharedPreferences preferences;
    private final DevicePolicyManager policies;
    private final ComponentName admin;

    public HardcoreModeManager(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(
                SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE);
        policies = this.context.getSystemService(DevicePolicyManager.class);
        admin = new ComponentName(this.context, HardcoreModeReceiver.class);
    }

    public ComponentName getAdminComponent() {
        return admin;
    }

    public boolean isAdminActive() {
        return policies != null && policies.isAdminActive(admin);
    }

    public boolean isRequested() {
        return preferences.getBoolean(KEY_REQUESTED, false);
    }

    public boolean isEnabled() {
        return isRequested() && isAdminActive();
    }

    /** True only when both halves of the protected App Info guard are available. */
    public boolean isGuardReady() {
        return isEnabled() && new AppModeManager(context).isAccessibilityEnabled();
    }

    public Intent activationIntent() {
        return new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        context.getString(R.string.hardcore_admin_system_explanation));
    }

    public Intent adminSettingsIntent() {
        return new Intent(Settings.ACTION_SECURITY_SETTINGS);
    }

    public void beginActivation() {
        preferences.edit().putBoolean(KEY_REQUESTED, true).commit();
    }

    public boolean finishActivation() {
        boolean active = isAdminActive();
        preferences.edit().putBoolean(KEY_REQUESTED, active).commit();
        if (active) {
            refreshExistingAutomaticMode();
            HardcoreAutoPayManager.schedule(context);
        }
        HardcoreReadinessNotificationManager.refresh(context);
        return active;
    }

    public void cancelPendingActivation() {
        if (!isAdminActive()) preferences.edit().putBoolean(KEY_REQUESTED, false).commit();
    }

    public void disable() {
        SubFeaturePolicy.consentHardcore(context, false);
        HardcoreAutoPayManager.cancel(context);
        preferences.edit().putBoolean(KEY_REQUESTED, false).commit();
        if (policies != null && policies.isAdminActive(admin)) {
            policies.removeActiveAdmin(admin);
        }
        HardcoreReadinessNotificationManager.refresh(context);
    }

    public void onAdminEnabled() {
        preferences.edit().putBoolean(KEY_REQUESTED, true).commit();
        refreshExistingAutomaticMode();
        HardcoreAutoPayManager.schedule(context);
        HardcoreReadinessNotificationManager.refresh(context);
    }

    public void onAdminDisabled() {
        SubFeaturePolicy.consentHardcore(context, false);
        // disable() clears KEY_REQUESTED before removing admin, so only an out-of-band
        // deactivation reaches this reporter as a tamper signal.
        if (isRequested()) TamperTributeReporter.record(context);
        HardcoreAutoPayManager.cancel(context);
        preferences.edit().putBoolean(KEY_REQUESTED, false).commit();
        HardcoreReadinessNotificationManager.refresh(context);
    }

    /**
     * Refreshes Hardcore's restart bookkeeping without changing the user's protection intent.
     * Device Admin is a capability grant, not an implicit request to start protection.
     */
    public void applyBootPolicy() {
        if (isEnabled()) {
            refreshExistingAutomaticMode();
            HardcoreAutoPayManager.schedule(context);
        }
    }

    private void refreshExistingAutomaticMode() {
        if (new PaidPauseManager(context).isActive()) return;
        AppModeManager mode = new AppModeManager(context);
        // Enabling/reconnecting Device Admin must never turn a previously idle app mode on.
        // An already armed mode is left untouched so enabling Hardcore while protection is
        // running does not interrupt that protection or its persisted session.
        if (!mode.isArmed()) return;
        ResumeNotificationManager.show(context);
    }
}
