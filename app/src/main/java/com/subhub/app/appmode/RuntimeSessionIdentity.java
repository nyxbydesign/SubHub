package com.subhub.app.appmode;

import android.content.*;
import com.subhub.app.commitment.CommitmentManager;
import com.subhub.app.penance.*;
import com.subhub.app.service.*;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.stats.StatsRepository;
import java.nio.charset.StandardCharsets;
import java.security.*;

/** Session starts and targeted termination share one lock; wall clocks never identify a new session. */
public final class RuntimeSessionIdentity {
    public static final Object LOCK=new Object();
    private RuntimeSessionIdentity(){}
    public static boolean active(Context context) {
        return CommitmentManager.isActive(context)||new PaidPauseManager(context).isActive()||new AppModeManager(context).isArmed()
            ||ScreenCaptureService.isRunning()||ScreenshotAccessibilityService.isRecognitionActive();
    }
    public static String current(Context context) {
        synchronized(LOCK) {
            boolean pact=CommitmentManager.isActive(context),pause=new PaidPauseManager(context).isActive();
            boolean capture=new AppModeManager(context).isArmed()||ScreenCaptureService.isRunning()||ScreenshotAccessibilityService.isRecognitionActive();
            if(!pact&&!pause&&!capture)return "";
            String commitment=context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).getString("commitment_session_identity","");
            String paused=context.getSharedPreferences(PenanceManager.PREFS_NAME,0).getString("paid_pause_session_identity","");
            String stats=context.getSharedPreferences(StatsRepository.PREFS_NAME,0).getString("active_session_identity","");
            if(pact&&!uuid(commitment)||pause&&!uuid(paused)||capture&&!uuid(stats))return "";
            String source=(pact?commitment:"")+":"+(pause?paused:"")+":"+(capture?stats:"");
            try {byte[] digest=MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.US_ASCII));StringBuilder value=new StringBuilder();for(byte item:digest)value.append(String.format(java.util.Locale.ROOT,"%02x",item&255));return value.toString();}
            catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        }
    }
    private static boolean uuid(String value){return value!=null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");}
}
