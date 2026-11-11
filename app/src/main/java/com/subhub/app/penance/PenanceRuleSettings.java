package com.subhub.app.penance;

import android.content.Context;
import com.subhub.app.configuration.FeatureEditorSession;
import java.util.Map;

/** Rule configuration only. Financial and runtime actions are deliberately absent. */
public interface PenanceRuleSettings {
    static PenanceRuleSettings forEditor(Context context) {
        Context scope = context.getApplicationContext();
        return FeatureEditorSession.internal(scope)
                ? new InternalPenanceRules(scope.getSharedPreferences(PenanceManager.PREFS_NAME, Context.MODE_PRIVATE))
                : new PenanceManager(scope);
    }
    String getCurrency();
    String money(long cents);
    boolean isEnabled();
    boolean isInfractionEnabled(PenanceInfraction infraction);
    void disableInfraction(PenanceInfraction infraction);
    int getInfractionCents(PenanceInfraction infraction);
    int getDailyCapCents();
    int getWeeklyCapCents();
    int getMercyMinutes();
    int getDwellSeconds();
    int getDetectionBatch();
    int getTamperCooldownMinutes();
    void configure(boolean enabled, Map<PenanceInfraction, Integer> enabledRuleCosts,
            int dailyCapCents, int weeklyCapCents, int mercyMinutes, int dwellSeconds,
            int detectionBatch, int tamperCooldownMinutes);
}
