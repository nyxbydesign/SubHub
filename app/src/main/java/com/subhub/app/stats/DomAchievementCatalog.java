package com.subhub.app.stats;

import com.subhub.app.R;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

final class DomAchievementCatalog {
    private DomAchievementCatalog() { }

    private static final List<AchievementManager.Achievement> VALUES = Collections.unmodifiableList(Arrays.asList(
            a("dom_subs_1", R.string.dom_subs_1, R.string.dom_desc_subs_1, "dom_subs", R.drawable.achievement_badge_dom_subs_1),
            a("dom_subs_3", R.string.dom_subs_3, R.string.dom_desc_subs_3, "dom_subs", R.drawable.achievement_badge_dom_subs_3),
            a("dom_subs_5", R.string.dom_subs_5, R.string.dom_desc_subs_5, "dom_subs", R.drawable.achievement_badge_dom_subs_5),
            a("dom_subs_10", R.string.dom_subs_10, R.string.dom_desc_subs_10, "dom_subs", R.drawable.achievement_badge_dom_subs_10),
            a("dom_sessions_10", R.string.dom_sessions_10, R.string.dom_desc_sessions_10, "dom_sessions", R.drawable.achievement_badge_dom_sessions_10),
            a("dom_sessions_100", R.string.dom_sessions_100, R.string.dom_desc_sessions_100, "dom_sessions", R.drawable.achievement_badge_dom_sessions_100),
            a("dom_time_1hr", R.string.dom_time_1hr, R.string.dom_desc_time_1hr, "dom_time", R.drawable.achievement_badge_dom_time_1hr),
            a("dom_time_24hr", R.string.dom_time_24hr, R.string.dom_desc_time_24hr, "dom_time", R.drawable.achievement_badge_dom_time_24hr),
            a("dom_time_7days", R.string.dom_time_7days, R.string.dom_desc_time_7days, "dom_time", R.drawable.achievement_badge_dom_time_7days),
            a("dom_time_30days", R.string.dom_time_30days, R.string.dom_desc_time_30days, "dom_time", R.drawable.achievement_badge_dom_time_30days),
            a("first_custom_phrase", R.string.achievement_first_custom_phrase, R.string.achievement_desc_first_custom_phrase, "dom_phrases", R.drawable.achievement_badge_first_custom_phrase),
            a("phrase_library", R.string.achievement_phrase_library, R.string.achievement_desc_phrase_library, "dom_phrases", R.drawable.achievement_badge_phrase_library),
            a("text_filter_enabled", R.string.achievement_text_filter_enabled, R.string.achievement_desc_text_filter_enabled, "censor", R.drawable.achievement_badge_text_filter_enabled),
            a("text_filter_catalog", R.string.achievement_text_filter_catalog, R.string.achievement_desc_text_filter_catalog, "censor", R.drawable.achievement_badge_text_filter_catalog),
            a("style_explorer", R.string.achievement_style_explorer, R.string.achievement_desc_style_explorer, "custom", R.drawable.achievement_badge_style_explorer),
            a("border_artist", R.string.achievement_border_artist, R.string.achievement_desc_border_artist, "custom", R.drawable.achievement_badge_border_artist),
            a("color_picker", R.string.achievement_color_picker, R.string.achievement_desc_color_picker, "custom", R.drawable.achievement_badge_color_picker),
            a("paypal_vault", R.string.achievement_paypal_vault, R.string.achievement_desc_paypal_vault, "dom_wallet", R.drawable.achievement_badge_paypal_vault),
            a("dom_tribute_10", R.string.dom_tribute_10, R.string.dom_desc_tribute_10, "dom_tribute", R.drawable.achievement_badge_dom_tribute_10),
            a("dom_tribute_250", R.string.dom_tribute_250, R.string.dom_desc_tribute_250, "dom_tribute", R.drawable.achievement_badge_dom_tribute_250),
            a("dom_tribute_1000", R.string.dom_tribute_1000, R.string.dom_desc_tribute_1000, "dom_tribute", R.drawable.achievement_badge_dom_tribute_1000),
            a("dom_tribute_10000", R.string.dom_tribute_10000, R.string.dom_desc_tribute_10000, "dom_tribute", R.drawable.achievement_badge_dom_tribute_10000),
            a("dom_hardcore_1", R.string.dom_hardcore_1, R.string.dom_desc_hardcore_1, "dom_hardcore", R.drawable.achievement_badge_dom_hardcore_1),
            a("dom_hardcore_3", R.string.dom_hardcore_3, R.string.dom_desc_hardcore_3, "dom_hardcore", R.drawable.achievement_badge_dom_hardcore_3),
            a("dom_hardcore_5", R.string.dom_hardcore_5, R.string.dom_desc_hardcore_5, "dom_hardcore", R.drawable.achievement_badge_dom_hardcore_5)));

    static List<AchievementManager.Achievement> all() { return VALUES; }

    static long target(String id) {
        switch (id) {
            case "dom_subs_1": case "dom_hardcore_1":
            case "first_custom_phrase": case "paypal_vault":
            case "text_filter_enabled": case "color_picker": return 1;
            case "dom_subs_3": case "dom_hardcore_3": case "text_filter_catalog": return 3;
            case "border_artist": return 4;
            case "style_explorer": return 8;
            case "dom_subs_5": case "dom_hardcore_5": return 5;
            case "dom_subs_10": case "dom_sessions_10": case "phrase_library": return 10;
            case "dom_sessions_100": return 100;
            case "dom_time_1hr": return 36_000;
            case "dom_time_24hr": return 864_000;
            case "dom_time_7days": return 6_048_000;
            case "dom_time_30days": return 25_920_000;
            case "dom_tribute_10": return 10_000;
            case "dom_tribute_250": return 250_000;
            case "dom_tribute_1000": return 1_000_000;
            case "dom_tribute_10000": return 10_000_000;
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

    /** Count required choices only; unknown legacy values cannot replace a missing choice. */
    static long appearanceCurrent(String id, Set<String> tried) {
        List<String> required = "style_explorer".equals(id)
                ? Arrays.asList("box", "blur", "pixelate", "custom", "static", "glitch", "tape", "error_popup")
                : Arrays.asList("classic", "gradient", "glow", "rainbow");
        return required.stream().filter(tried::contains).count();
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
