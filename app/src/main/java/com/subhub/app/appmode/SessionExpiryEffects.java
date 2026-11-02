package com.subhub.app.appmode;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.subhub.app.penance.HardcoreAutoPayManager;
import com.subhub.app.remote.RemoteSessionClock;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.service.ScreenCaptureService;

/** External effects follow the committed transition and cannot carry a global Stop into a new run. */
public final class SessionExpiryEffects {
    private SessionExpiryEffects() { }
    public static void post(Context context, RuntimeSessionIdentity.ComponentObservation scope,
            ScreenCaptureService.StopTarget capture, boolean rearmIfHardcore, boolean showResume) {
        new Handler(Looper.getMainLooper()).post(() -> {
            // Resource retirement is historical ownership; later native components need not stay idle.
            if (capture != null) ScreenCaptureService.requestStop(capture);
            if (!scope.isCurrent()) return;
            RuntimeSessionIdentity.ComponentObservation current = scope;
            boolean notify = showResume;
            if (rearmIfHardcore) {
                RuntimeSessionIdentity.ComponentObservation rearmed = rearmHardcore(context, scope,
                        new HardcoreModeManager(context).isEnabled());
                if (rearmed != null) { current = rearmed; notify = true; }
            }
            if (!current.isCurrent()) return;
            if (notify) ResumeNotificationManager.show(context); else ResumeNotificationManager.cancel(context);
            if (current.isCurrent()) HardcoreAutoPayManager.schedule(context);
        });
    }

    private static RuntimeSessionIdentity.ComponentObservation rearmHardcore(Context context,
            RuntimeSessionIdentity.ComponentObservation scope, boolean observedEnabled) {
        if (!observedEnabled) return null;
        synchronized (RuntimeSessionIdentity.LOCK) {
            // Hardcore off changes policy without rotating the runtime component fence.
            if (!scope.isCurrent() || !context.getSharedPreferences(
                    com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME, 0)
                    .getBoolean(HardcoreModeManager.KEY_REQUESTED, false)) return null;
            RuntimeSessionIdentity.Observed runtime = RuntimeSessionIdentity.observe(context, RemoteSessionClock.local());
            if (runtime.paused) return null;
            new AppModeManager(context).setArmed(true);
            return RuntimeSessionIdentity.componentObservation(context);
        }
    }
}
