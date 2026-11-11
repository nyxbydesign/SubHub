package com.subhub.app.appmode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.subhub.app.commitment.CommitmentManager;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.security.HardcoreReadinessNotificationManager;
import com.subhub.app.security.ProtectionStopPolicy;
import com.subhub.app.penance.HardcoreAutoPayManager;
import com.subhub.app.penance.PaidPauseManager;
import com.subhub.app.runtime.AppRuntimeGateway;

/** Restores persisted intent after boot without silently starting MediaProjection. */
public final class BootReceiver extends BroadcastReceiver {
    public static final String ACTION_DISARM = "com.subhub.app.action.DISARM_APP_MODE";

    @Override public void onReceive(Context context, Intent intent) {
        try (AppRuntimeGateway.LegacyWork admitted = AppRuntimeGateway.tryBeginLegacyWork(context)) {
            if (admitted == null) return;
            SessionReceiverLifetime.Work work = SessionReceiverLifetime.begin(this);
            try { receive(context, intent); work.afterQueues(); }
            catch (RuntimeException | Error failure) { work.failed(); throw failure; }
        }
    }

    private void receive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        AppModeManager mode = new AppModeManager(context);
        if (ACTION_DISARM.equals(action)) {
            if (ProtectionStopPolicy.decision(context)
                    != ProtectionStopPolicy.Decision.ALLOW) {
                CommitmentManager.reinforceProtection(context);
                new HardcoreModeManager(context).applyBootPolicy();
                return;
            }
            mode.setArmed(false);
            ProtectionSessionManager.markMediaProjectionExplicitlyStopped(context);
            ResumeNotificationManager.cancel(context);
            return;
        }
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            ControllerPinManager.enterSubMode();
            PaidPauseManager paidPause = new PaidPauseManager(context);
            if (paidPause.isActive()) {
                paidPause.applyBootPolicy();
            } else {
                mode.applyBootPolicy();
                CommitmentManager.applyBootPolicy(context);
                new HardcoreModeManager(context).applyBootPolicy();
                HardcoreAutoPayManager.schedule(context);
            }
        }
        if (!new PaidPauseManager(context).isActive()) {
            ResumeNotificationManager.show(context);
        }
        HardcoreReadinessNotificationManager.refresh(context);
    }
}
