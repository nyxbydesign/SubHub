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
    private static volatile String idleFence=hash(java.util.UUID.randomUUID().toString());
    private RuntimeSessionIdentity(){}
    public static void changed() {
        synchronized (LOCK) {
            idleFence = hash(java.util.UUID.randomUUID().toString());
            // Retained views keep their old draw scope; a new session must admit new tap geometry.
            CensorTapTracker.shared().clear();
        }
    }
    /** Raw component ownership survives expiry and distinguishes replacement pacts sharing statistics. */
    public static String componentSignature(Context context) {
        return componentObservation(context).signature;
    }
    public static final class ComponentObservation {
        public final String signature;
        private final String fence;
        private ComponentObservation(String signature, String fence) { this.signature = signature; this.fence = fence; }
        /** Pure validation avoids acquiring Runtime while another subsystem owns its rendering lock. */
        public boolean isCurrent() { return fence.equals(idleFence); }
    }
    public static ComponentObservation componentObservation(Context context) {
        synchronized (LOCK) {
            SharedPreferences main = context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0);
            String signature = main.getString("commitment_session_identity", "") + ":"
                    + context.getSharedPreferences(PenanceManager.PREFS_NAME, 0).getString("paid_pause_session_identity", "") + ":"
                    + context.getSharedPreferences(StatsRepository.PREFS_NAME, 0).getString("active_session_identity", "");
            return new ComponentObservation(signature, idleFence);
        }
    }
    public static final class Observed {
        public final boolean active,armed,pact,paused,screenCapture,recognition;
        public final String identity,statsIdentity,pactIdentity,pauseIdentity,idleFence;
        private Observed(boolean active,boolean armed,boolean pact,boolean paused,boolean screen,boolean recognition,
                String identity,String stats,String pactId,String pauseId,String fence) {
            this.active=active;this.armed=armed;this.pact=pact;this.paused=paused;this.screenCapture=screen;this.recognition=recognition;
            this.identity=identity;this.statsIdentity=stats;this.pactIdentity=pactId;this.pauseIdentity=pauseId;this.idleFence=fence;
        }
    }
    /** Pure app-owned observation. It never expires a pact, re-arms a pause or queries a system service. */
    public static Observed observe(Context context,com.subhub.app.remote.RemoteSessionClock.Sample clock) {
        synchronized(LOCK) {
            SharedPreferences main=context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0);
            SharedPreferences penance=context.getSharedPreferences(PenanceManager.PREFS_NAME,0);
            SharedPreferences statistics=context.getSharedPreferences(StatsRepository.PREFS_NAME,0);
            long end=main.getLong("commitment_ends_at",0);
            boolean pact=end>0 && (clock.boot>=0 && main.getInt("commitment_boot",-2)==clock.boot
                    && main.contains("commitment_ends_elapsed") ? main.getLong("commitment_ends_elapsed",0)>clock.elapsed
                    : clock.wall<=0 || end>clock.wall);
            long pauseEnd=penance.getLong("paid_pause_active_until",0);
            boolean pause=pauseEnd>0 && (clock.wall<=0 || pauseEnd>clock.wall);
            boolean armed=main.getBoolean(AppModeManager.KEY_ARMED,false),screen=ScreenCaptureService.isRunning(),recognition=ScreenshotAccessibilityService.isRecognitionActive();
            boolean capture=armed||screen||recognition,active=pact||pause||capture;
            String pactId=main.getString("commitment_session_identity",""),pauseId=penance.getString("paid_pause_session_identity","");
            String statsId=statistics.getString("active_session_identity","");
            String identity=!active || pact&&!uuid(pactId)||pause&&!uuid(pauseId)||capture&&!uuid(statsId) ? ""
                    : hash((pact?pactId:"")+":"+(pause?pauseId:"")+":"+(capture?statsId:""));
            return new Observed(active,armed,pact,pause,screen,recognition,identity,statsId,pactId,pauseId,idleFence);
        }
    }
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
    private static String hash(String source) {
        try {byte[] digest=MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.US_ASCII));StringBuilder value=new StringBuilder();for(byte item:digest)value.append(String.format(java.util.Locale.ROOT,"%02x",item&255));return value.toString();}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
