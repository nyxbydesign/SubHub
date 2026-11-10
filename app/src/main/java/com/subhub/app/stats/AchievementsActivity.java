package com.subhub.app.stats;

import java.util.List;
import java.util.Map;

/** Direct catalog entries use the same retained statistics host. */
public final class AchievementsActivity extends StatsActivity {
    @Override protected boolean initiallyShowAchievements() { return true; }

    static Map<String, List<AchievementManager.Achievement>> orderedGroups(
            List<AchievementManager.Achievement> achievements) {
        return AchievementsPane.orderedGroups(achievements);
    }

    static String displayCategory(AchievementManager.Achievement achievement) {
        return AchievementsPane.displayCategory(achievement);
    }
}
