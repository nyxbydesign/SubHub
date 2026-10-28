package com.subhub.app.stats;

import android.content.Context;
import com.subhub.app.security.DeviceRolePreferences;

/** A dated gap before collection remains unknown, even when an older session is retained. */
final class LocalDailyStatsSource implements DailyStatsPanel.Source {
    private final Context context;
    LocalDailyStatsSource(Context context) { this.context = context.getApplicationContext(); }
    @Override public boolean accessible() {
        return DeviceRolePreferences.supportsSub(context)
                && (AchievementRole.current(context) == AchievementRole.SUB || AchievementRole.DOM.accessible(context));
    }
    @Override public DailyStatsPanel.DailyData load(String first, String last) throws Exception {
        if (!accessible()) throw new SecurityException("Local statistics unavailable");
        LocalRetainedStatistics facts = LocalRetainedStatistics.load(context);
        if (!accessible()) throw new SecurityException("Local statistics unavailable");
        return new DailyStatsPanel.DailyData(facts.since, facts.range(first, last));
    }
}
