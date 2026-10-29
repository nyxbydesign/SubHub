package com.subhub.app.stats;

import com.subhub.app.R;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class DomAchievementCatalog {
    private DomAchievementCatalog() { }

    private static final List<AchievementManager.Achievement> VALUES = Collections.unmodifiableList(Arrays.asList(
            a("dom_subs_1", R.string.dom_subs_1, R.string.dom_desc_subs_1, "dom_subs", R.drawable.achievement_badge_dom_subs_1),
            a("dom_subs_3", R.string.dom_subs_3, R.string.dom_desc_subs_3, "dom_subs", R.drawable.achievement_badge_dom_subs_3),
            a("dom_subs_5", R.string.dom_subs_5, R.string.dom_desc_subs_5, "dom_subs", R.drawable.achievement_badge_dom_subs_5),
            a("dom_subs_10", R.string.dom_subs_10, R.string.dom_desc_subs_10, "dom_subs", R.drawable.achievement_badge_dom_subs_10),
            a("dom_time_1hr", R.string.dom_time_1hr, R.string.dom_desc_time_1hr, "dom_time", R.drawable.achievement_badge_dom_time_1hr),
            a("dom_time_24hr", R.string.dom_time_24hr, R.string.dom_desc_time_24hr, "dom_time", R.drawable.achievement_badge_dom_time_24hr),
            a("dom_time_7days", R.string.dom_time_7days, R.string.dom_desc_time_7days, "dom_time", R.drawable.achievement_badge_dom_time_7days),
            a("dom_time_30days", R.string.dom_time_30days, R.string.dom_desc_time_30days, "dom_time", R.drawable.achievement_badge_dom_time_30days),
            a("dom_sessions_10", R.string.dom_sessions_10, R.string.dom_desc_sessions_10, "dom_sessions", R.drawable.achievement_badge_dom_sessions_10),
            a("dom_sessions_100", R.string.dom_sessions_100, R.string.dom_desc_sessions_100, "dom_sessions", R.drawable.achievement_badge_dom_sessions_100),
            a("dom_tribute_10", R.string.dom_tribute_10, R.string.dom_desc_tribute_10, "dom_tribute", R.drawable.achievement_badge_dom_tribute_10),
            a("dom_tribute_250", R.string.dom_tribute_250, R.string.dom_desc_tribute_250, "dom_tribute", R.drawable.achievement_badge_dom_tribute_250),
            a("dom_tribute_1000", R.string.dom_tribute_1000, R.string.dom_desc_tribute_1000, "dom_tribute", R.drawable.achievement_badge_dom_tribute_1000),
            a("dom_tribute_10000", R.string.dom_tribute_10000, R.string.dom_desc_tribute_10000, "dom_tribute", R.drawable.achievement_badge_dom_tribute_10000),
            a("dom_hardcore_1", R.string.dom_hardcore_1, R.string.dom_desc_hardcore_1, "dom_hardcore", R.drawable.achievement_badge_dom_hardcore_1),
            a("dom_hardcore_3", R.string.dom_hardcore_3, R.string.dom_desc_hardcore_3, "dom_hardcore", R.drawable.achievement_badge_dom_hardcore_3),
            a("dom_hardcore_5", R.string.dom_hardcore_5, R.string.dom_desc_hardcore_5, "dom_hardcore", R.drawable.achievement_badge_dom_hardcore_5)));

    static List<AchievementManager.Achievement> all() { return VALUES; }

    // Stable IDs name their original tier; requirements evolve independently of those IDs.
    static long target(String id) {
        switch (id) {
            case "dom_subs_1": case "dom_hardcore_1": return 2;
            case "dom_subs_3": case "dom_hardcore_3": return 5;
            case "dom_subs_5": case "dom_hardcore_5": return 10;
            case "dom_subs_10": return 25;
            case "dom_sessions_10": return 250;
            case "dom_sessions_100": return 2_500;
            case "dom_time_1hr": return 100L * 3_600;
            case "dom_time_24hr": return 500L * 3_600;
            case "dom_time_7days": return 2_500L * 3_600;
            case "dom_time_30days": return 10_000L * 3_600;
            case "dom_tribute_10": return 10_000;
            case "dom_tribute_250": return 100_000;
            case "dom_tribute_1000": return 1_000_000;
            case "dom_tribute_10000": return 10_000_000;
            default: return 0;
        }
    }

    static long earlierTarget(String id) {
        switch (id) {
            case "dom_subs_1": case "dom_hardcore_1": return 1;
            case "dom_subs_3": case "dom_hardcore_3": return 3;
            case "dom_subs_5": case "dom_hardcore_5": return 5;
            case "dom_subs_10": case "dom_sessions_10": return 10;
            case "dom_sessions_100": return 100;
            case "dom_time_1hr": return 3_600;
            case "dom_time_24hr": return 86_400;
            case "dom_time_7days": return 604_800;
            case "dom_time_30days": return 2_592_000;
            case "dom_tribute_10": return 1_000;
            case "dom_tribute_250": return 25_000;
            case "dom_tribute_1000": return 100_000;
            case "dom_tribute_10000": return 1_000_000;
            default: return 0;
        }
    }

    static long current(String id, DomStatsSnapshot stats) {
        if (id.startsWith("dom_subs_")) return stats.subs;
        if (id.startsWith("dom_time_")) return stats.sessionSeconds;
        if (id.startsWith("dom_sessions_")) return stats.sessions;
        if (id.startsWith("dom_tribute_")) return stats.leadingPaidCents();
        if (id.startsWith("dom_hardcore_")) return stats.hardcoreSubs;
        return 0;
    }

    static boolean known(String id, DomStatsSnapshot stats) {
        if (stats == null) return false;
        if (id.startsWith("dom_time_") || id.startsWith("dom_sessions_")) return stats.hasTotals();
        if (id.startsWith("dom_tribute_")) return stats.hasWalletTotals();
        if (id.startsWith("dom_hardcore_")) return stats.hasHardcoreStatus();
        return id.startsWith("dom_subs_");
    }

    private static AchievementManager.Achievement a(String id, int name, int description, String category, int art) {
        return new AchievementManager.Achievement(id, name, description, "", category, art, false);
    }
}
