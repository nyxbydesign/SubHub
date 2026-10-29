package com.subhub.app.stats;

import android.content.SharedPreferences;
import java.util.Collections;
import java.util.Set;

/** Records the requirement under which an existing badge was earned, without rewriting its receipt. */
final class DomGoalMigration {
    static final String REVISION = "dom_goal_revision";
    static final String EARLIER = "earned_goal_v1.";
    private DomGoalMigration() { }

    static void apply(SharedPreferences preferences) {
        if (preferences.getInt(REVISION, 1) >= 2) {
            // A failed disk commit can already be visible in memory. A retry still needs an acknowledgement.
            if (!preferences.edit().putInt(REVISION, 2).commit())
                throw new IllegalStateException("Achievement goal migration was not persisted");
            return;
        }
        Set<String> saved = preferences.getStringSet("unlocked", Collections.emptySet());
        SharedPreferences.Editor editor = preferences.edit();
        for (String id : saved == null ? Collections.<String>emptySet() : saved) {
            long target = DomAchievementCatalog.earlierTarget(id);
            if (target > 0 && !preferences.contains(EARLIER + id)) editor.putLong(EARLIER + id, target);
        }
        if (!editor.putInt(REVISION, 2).commit())
            throw new IllegalStateException("Achievement goal migration was not persisted");
    }
}
