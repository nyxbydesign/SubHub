package com.subhub.app.people;

import android.content.Context;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.ProtectionSessionManager;
import com.subhub.app.appmode.ResumeNotificationManager;
import com.subhub.app.commitment.CommitmentManager;
import com.subhub.app.penance.PaidPauseManager;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.service.ScreenCaptureService;
import com.subhub.app.service.ScreenshotAccessibilityService;

/** Local runtime actions require the selected local identity and the Dom role. */
public final class LocalSessionControls {
    private LocalSessionControls() {}

    public static boolean isActive(Context context) {
        return CommitmentManager.isActive(context) || new PaidPauseManager(context).isActive()
                || new AppModeManager(context).isArmed() || ScreenCaptureService.isRunning()
                || ScreenshotAccessibilityService.isRecognitionActive();
    }

    public static boolean end(Context context, PersonId person) {
        if (person == null || !person.isLocal() || !ControllerPinManager.isDomModeActive()) return false;
        ProtectionSessionManager.markMediaProjectionExplicitlyStopped(context);
        CommitmentManager.emergencyRelease(context);
        new PaidPauseManager(context).finish();
        new AppModeManager(context).setArmed(false);
        ResumeNotificationManager.cancel(context);
        return true;
    }
}
