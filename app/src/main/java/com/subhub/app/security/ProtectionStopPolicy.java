package com.subhub.app.security;

import android.content.Context;

import com.subhub.app.commitment.CommitmentManager;
import com.subhub.app.penance.PaidPauseManager;

/** One stop boundary shared by Home, foreground capture, and notification actions. */
public final class ProtectionStopPolicy {
    public enum Decision { ALLOW, REQUIRE_CONTROLLER, TIMER_LOCKED }

    private ProtectionStopPolicy() {}

    public static Decision decision(Context context) {
        return evaluate(
                CommitmentManager.isActive(context),
                new HardcoreModeManager(context).isEnabled(),
                ControllerPinManager.isDomModeActive(),
                new PaidPauseManager(context).isActive());
    }

    static Decision evaluate(
            boolean pactActive,
            boolean hardcoreActive,
            boolean domMode,
            boolean paidPauseActive) {
        if (paidPauseActive) return Decision.ALLOW;
        if (pactActive && !domMode) return Decision.TIMER_LOCKED;
        if (!domMode) return Decision.REQUIRE_CONTROLLER;
        return Decision.ALLOW;
    }

    /** Notification actions cannot present the controller-PIN UI. */
    public static boolean showNotificationStop(Context context) {
        return ControllerPinManager.isDomModeActive()
                && !CommitmentManager.isActive(context)
                && !new HardcoreModeManager(context).isEnabled();
    }
}
