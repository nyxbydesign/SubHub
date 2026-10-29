package com.subhub.app.stats;

import android.content.Context;
import android.content.SharedPreferences;

import com.subhub.app.R;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.AppTimerManager;
import com.subhub.app.commitment.CommitmentManager;
import com.subhub.app.detection.text.TextSmutConfig;
import com.subhub.app.penance.PaidPauseManager;
import com.subhub.app.penance.PayPalCredentialStore;
import com.subhub.app.penance.PenanceInfraction;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.settings.SettingsRepository;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Persistent implementation of the recovered and expanded achievement contracts. */
public final class AchievementManager {
    private static final Object UNLOCK_LOCK = new Object();
    private static final String PREFS_NAME = "betablocker_achievements";
    private static final String KEY_UNLOCKED = "unlocked";
    private static final String KEY_PENDING = "pending_notifications";
    private static final String KEY_UNLOCKED_AT_PREFIX = "unlocked_at.";
    private static final List<Achievement> ACHIEVEMENTS = achievements();
    private final Context context;
    private final SharedPreferences preferences;
    private final Set<String> unlocked;
    private final AchievementRole role;
    private final boolean workspaceBound;
    private final List<Achievement> catalog;
    private DomStatsSnapshot domStats;
    private Long historicalPaid;
    private String historicalPaidCurrency = "EUR";

    public AchievementManager(Context context) {
        this(context, AchievementRole.SUB, false);
    }

    public static AchievementManager forWorkspace(Context context) {
        return new AchievementManager(context, AchievementRole.current(context), true);
    }

    private AchievementManager(Context context, AchievementRole role, boolean workspaceBound) {
        this.context = context.getApplicationContext();
        this.role = role;
        this.workspaceBound = workspaceBound;
        catalog = role == AchievementRole.DOM ? DomAchievementCatalog.all() : ACHIEVEMENTS;
        preferences = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> stored = preferences.getStringSet(KEY_UNLOCKED, Collections.emptySet());
        unlocked = new LinkedHashSet<>(stored == null ? Collections.emptySet() : stored);
    }

    public AchievementRole getRole() { return role; }
    public boolean isAccessible() {
        return workspaceBound ? role.accessible(context)
                : com.subhub.app.security.DeviceRolePreferences.supportsSub(context);
    }
    public List<Achievement> all() { return isAccessible() ? catalog : Collections.emptyList(); }
    public boolean isUnlocked(String id) { return isAccessible() && role.owns(id) && unlocked.contains(id); }
    public long getUnlockedAt(String id) {
        if (!isUnlocked(id)) return 0L;
        if (preferences.contains("history_earned_at." + id)) return preferences.getLong("history_earned_at." + id, 0L);
        long stored = preferences.getLong(KEY_UNLOCKED_AT_PREFIX + id, 0L);
        long placeholder = LocalDate.of(2026, 8, 27).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return stored == placeholder ? 0L : stored;
    }
    public int getUnlockedCount() {
        int count = 0;
        for (Achievement value : all()) if (unlocked.contains(value.id)) count++;
        return count;
    }
    public int getTotalCount() { return all().size(); }

    public Progress progress(Achievement value, StatsSnapshot stats) {
        if (!isAccessible() || !catalog.contains(value)) return new Progress(0, 0, false, false);
        if (role == AchievementRole.DOM) return new Progress(
                domStats == null ? 0 : DomAchievementCatalog.current(value.id, domStats),
                DomAchievementCatalog.target(value.id), isUnlocked(value.id),
                DomAchievementCatalog.known(value.id, domStats));
        long current = current(value.id, stats);
        long target = target(value.id);
        return new Progress(current, target, isUnlocked(value.id));
    }

    public List<Achievement> checkAchievements(StatsSnapshot stats) {
        if (role != AchievementRole.SUB || !isAccessible()) return Collections.emptyList();
        return unlock(value -> qualifies(value.id, stats));
    }

    List<Achievement> checkRetainedAchievements(StatsSnapshot stats) {
        if (role != AchievementRole.SUB || !isAccessible()) return Collections.emptyList();
        return unlock(value -> qualifies(value.id, stats, true));
    }

    public List<Achievement> checkDomAchievements(DomStatsSnapshot stats) {
        if (role != AchievementRole.DOM || !isAccessible()) return Collections.emptyList();
        domStats = java.util.Objects.requireNonNull(stats);
        return unlock(value -> DomAchievementCatalog.known(value.id, stats)
                && DomAchievementCatalog.current(value.id, stats) >= DomAchievementCatalog.target(value.id));
    }

    public String progressCurrency() { return domStats == null ? historicalPaidCurrency : domStats.leadingPaidCurrency(); }

    void historicalPaid(long eur, long usd) {
        historicalPaid = Math.max(eur, usd);
        historicalPaidCurrency = usd > eur ? "USD" : "EUR";
    }

    private List<Achievement> unlock(java.util.function.Predicate<Achievement> qualifies) {
        synchronized (UNLOCK_LOCK) {
            if (!isAccessible()) return Collections.emptyList();
            Set<String> current = preferences.getStringSet(KEY_UNLOCKED, Collections.emptySet());
            if (current != null) unlocked.addAll(current);
            List<Achievement> newlyUnlocked = new ArrayList<>();
            for (Achievement value : catalog) {
                if (unlocked.contains(value.id) || !qualifies.test(value)) continue;
                unlocked.add(value.id);
                newlyUnlocked.add(value);
            }
            if (!newlyUnlocked.isEmpty() && isAccessible()) {
                Set<String> pending = preferences.getStringSet(KEY_PENDING, Collections.emptySet());
                Set<String> updatedPending = new LinkedHashSet<>(
                        pending == null ? Collections.emptySet() : pending);
                long unlockedAt = System.currentTimeMillis();
                SharedPreferences.Editor editor = preferences.edit()
                        .putStringSet(KEY_UNLOCKED, new LinkedHashSet<>(unlocked));
                for (Achievement value : newlyUnlocked) {
                    updatedPending.add(value.id);
                    editor.putLong(KEY_UNLOCKED_AT_PREFIX + value.id, unlockedAt);
                }
                editor.putStringSet(KEY_PENDING, updatedPending).apply();
            }
            return newlyUnlocked;

        }
    }

    /** Historical eligibility updates presentation state only, never replaying product effects. */
    boolean reconcileHistory(java.util.Map<String, Long> dates, Set<String> sources, StatsSnapshot stats,
            DomStatsSnapshot aggregate) {
        synchronized (UNLOCK_LOCK) {
            if (!isAccessible()) return false;
            String prefix = "history_v1." + role.name() + ".";
            long now = System.currentTimeMillis();
            boolean initial = !preferences.contains(prefix + "cutoff");
            long cutoff = preferences.getLong(prefix + "cutoff", now);
            Set<String> oldSources = preferences.getStringSet(prefix + "sources", Collections.emptySet());
            boolean added = oldSources == null || !oldSources.containsAll(sources);
            Set<String> remembered = new LinkedHashSet<>(oldSources == null ? Collections.emptySet() : oldSources);
            remembered.addAll(sources);
            Set<String> saved = preferences.getStringSet(KEY_UNLOCKED, Collections.emptySet());
            Set<String> updated = new LinkedHashSet<>(saved == null ? Collections.emptySet() : saved);
            Set<String> storedPending = preferences.getStringSet(KEY_PENDING, Collections.emptySet());
            Set<String> pending = new LinkedHashSet<>(storedPending == null ? Collections.emptySet() : storedPending);
            SharedPreferences.Editor editor = preferences.edit();
            long today = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
            if (role == AchievementRole.DOM) domStats = java.util.Objects.requireNonNull(aggregate);
            for (Achievement value : catalog) {
                long proof = Math.max(0, dates.getOrDefault(value.id, 0L));
                if (proof > now) proof = 0;
                boolean wasUnlocked = updated.contains(value.id);
                boolean datedHistory = proof > 0 && (proof <= cutoff || proof < today);
                boolean eligible;
                if (role == AchievementRole.DOM)
                    eligible = DomAchievementCatalog.known(value.id, aggregate)
                            && DomAchievementCatalog.current(value.id, aggregate) >= DomAchievementCatalog.target(value.id);
                else if ("legend".equals(value.id)) {
                    eligible = true;
                    for (Achievement other : catalog)
                        if (!"legend".equals(other.id) && !updated.contains(other.id)) eligible = false;
                } else eligible = historicalClockOnly(value.id) ? proof > 0 : qualifies(value.id, stats, true);
                boolean quietUnlock = !wasUnlocked && (initial || added || datedHistory) && (eligible || proof > 0);
                if (quietUnlock) updated.add(value.id);
                if (!updated.contains(value.id)) continue;
                long originalDate = preferences.getLong(KEY_UNLOCKED_AT_PREFIX + value.id, 0L);
                long placeholder = LocalDate.of(2026, 8, 27).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
                long effectiveDate = preferences.contains("history_earned_at." + value.id)
                        ? preferences.getLong("history_earned_at." + value.id, 0L)
                        : originalDate == placeholder ? 0 : originalDate;
                if (proof > 0 && (effectiveDate <= 0 || proof < effectiveDate))
                    editor.putLong("history_earned_at." + value.id, proof);
                if (quietUnlock || initial || added || proof > 0 && proof <= cutoff) pending.remove(value.id);
            }
            if (!isAccessible()) return false;
            editor.putStringSet(KEY_UNLOCKED, updated).putStringSet(KEY_PENDING, pending)
                    .putLong(prefix + "cutoff", cutoff).putStringSet(prefix + "sources", remembered);
            // Always obtain a persistence acknowledgement, including a retry after a failed commit.
            if (!editor.commit()) throw new IllegalStateException("Historical achievement persistence unavailable");
            unlocked.clear(); unlocked.addAll(updated);
            return initial;
        }
    }

    private static boolean historicalClockOnly(String id) {
        return "midnight_blocker".equals(id) || "early_bird".equals(id) || "night_owl".equals(id)
                || "new_year".equals(id) || "halloween_block".equals(id)
                || "christmas_block".equals(id) || "valentine_block".equals(id);
    }

    public List<Achievement> takePendingNotifications() {
        synchronized (UNLOCK_LOCK) {
            if (!isAccessible()) return Collections.emptyList();
            Set<String> stored = preferences.getStringSet(KEY_PENDING, Collections.emptySet());
            Set<String> pending = new LinkedHashSet<>(stored == null ? Collections.emptySet() : stored);
            if (pending.isEmpty()) return Collections.emptyList();
            List<Achievement> result = new ArrayList<>();
            for (Achievement value : catalog) if (pending.remove(value.id)) result.add(value);
            SharedPreferences.Editor editor = preferences.edit();
            if (pending.isEmpty()) editor.remove(KEY_PENDING); else editor.putStringSet(KEY_PENDING, pending);
            editor.apply();
            return result;

        }
    }

    private boolean qualifies(String id, StatsSnapshot stats) {
        return qualifies(id, stats, false);
    }

    private boolean qualifies(String id, StatsSnapshot stats, boolean observedOnly) {
        switch (id) {
            case "midnight_blocker": return LocalTime.now().getHour() == 0 && stats.getCurrentSessionBlocks() > 0;
            case "early_bird": return hourBetween(4, 6) && stats.getCurrentSessionBlocks() > 0;
            case "night_owl": return hourBetween(2, 4) && stats.getCurrentSessionBlocks() > 0;
            case "new_year": return dateIs(1, 1) && stats.getCurrentSessionBlocks() > 0;
            case "halloween_block": return dateIs(10, 31) && stats.getCurrentSessionBlocks() > 0;
            case "christmas_block": return dateIs(12, 25) && stats.getCurrentSessionBlocks() > 0;
            case "valentine_block": return dateIs(2, 14) && stats.getCurrentSessionBlocks() > 0;
            case "weekend_warrior": return hasCompleteWeekend(stats.getActiveDates());
            case "style_explorer": return stats.getCensorStylesTried().containsAll(Arrays.asList(
                    "box", "blur", "pixelate", "custom", "static", "glitch", "tape", "error_popup"));
            case "border_artist": return stats.getBorderEffectsTried().containsAll(Arrays.asList(
                    "classic", "gradient", "glow", "rainbow"));
            case "color_picker": return stats.isBorderColorChanged();
            case "legend":
                for (Achievement value : ACHIEVEMENTS) {
                    if (!"legend".equals(value.id) && !unlocked.contains(value.id)) return false;
                }
                return true;
            default:
                long target = target(id);
                return target > 0 && current(id, stats, observedOnly) >= target;
        }
    }

    private long current(String id, StatsSnapshot stats) {
        return current(id, stats, true);
    }

    private long current(String id, StatsSnapshot stats, boolean observedOnly) {
        switch (id) {
            case "first_block": case "blocks_10": case "blocks_100": case "blocks_1000": case "blocks_10000":
                return stats.getTotalBlocks();
            case "time_1hr": case "time_10hr": case "time_50hr": case "time_200hr":
                return stats.getTotalProtectedSeconds();
            case "first_session": case "sessions_10": case "sessions_100": return stats.getSessions();
            case "streak_7": case "streak_30": return stats.getCurrentStreak();
            case "peak_50": case "peak_500": return stats.getPeakSessionBlocks();
            case "style_explorer": return stats.getCensorStylesTried().size();
            case "border_artist": return stats.getBorderEffectsTried().size();
            case "color_picker": return stats.isBorderColorChanged() ? 1 : 0;
            case "first_custom_phrase": case "phrase_library": return stats.getCustomPhrases();
            case "export_first": case "export_artist": return stats.getExportedImages();
            case "ntr_50": case "ntr_100": case "ntr_200": case "ntr_500": return stats.getAllCategoryCensors();
            case "legend": return getUnlockedCount();
            case "app_mode_guardian":
                return context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0)
                        .getBoolean(AppModeManager.KEY_ARMED, false) ? 1 : 0;
            case "app_assignment_curator":
                return (observedOnly ? AppModeManager.observedIncludedPackages(context)
                        : new AppModeManager(context).getIncludedPackages()).size();
            case "limits_setter":
                return new AppTimerManager(context).loadSettings().anyEnabled() ? 1 : 0;
            case "limits_dual_guard": {
                AppTimerManager.Settings settings = new AppTimerManager(context).loadSettings();
                return settings.perAppEnabled && settings.totalEnabled ? 1 : 0;
            }
            case "pact_sealed":
                return (observedOnly ? CommitmentManager.observedActive(context) : CommitmentManager.isActive(context)) ? 1 : 0;
            case "pact_long_haul":
                return CommitmentManager.originalDurationMillis(context)
                        >= 7L * 24L * 60L * 60L * 1000L ? 1 : 0;
            case "hardcore_guardian":
                return new HardcoreModeManager(context).isEnabled() ? 1 : 0;
            case "text_filter_enabled": {
                SettingsRepository settings = new SettingsRepository(context);
                // The default is enabled for compatibility, but only an explicit saved
                // configuration represents the user's deliberate text-filter milestone.
                return settings.preferences().contains(SettingsRepository.KEY_TEXT_SMUT_ENABLED)
                        && settings.loadTextSmutConfig().isEnabled() ? 1 : 0;
            }
            case "text_filter_catalog": {
                SettingsRepository settings = new SettingsRepository(context);
                TextSmutConfig config = settings.loadTextSmutConfig();
                return settings.preferences().contains(SettingsRepository.KEY_TEXT_SMUT_CATEGORIES)
                        && config.isEnabled()
                        && config.getEnabledCategories().containsAll(TextSmutConfig.DEFAULT_CATEGORIES)
                        ? config.getEnabledCategories().size() : 0;
            }
            case "wallet_keeper":
                return (observedOnly ? PenanceManager.observedEnabled(context) : new PenanceManager(context).isEnabled()) ? 1 : 0;
            case "tribute_rulesmith": {
                if (observedOnly) return PenanceManager.observedRuleCount(context);
                PenanceManager penance = new PenanceManager(context);
                if (!penance.isEnabled()) return 0;
                int enabledRules = 0;
                for (PenanceInfraction infraction : PenanceInfraction.values()) {
                    if (penance.isInfractionEnabled(infraction)) enabledRules++;
                }
                return enabledRules;
            }
            case "paypal_vault":
                return new PayPalCredentialStore(context).vaultState().isReady() ? 1 : 0;
            case "paid_pause":
                return new PaidPauseManager(context).isEnabled() ? 1 : 0;
            case "wallet_paid_10": case "wallet_paid_100":
            case "wallet_paid_500": case "wallet_paid_1000":
                if (historicalPaid != null) return historicalPaid;
                if (observedOnly) {
                    java.util.Map<String, Long> paid = PenanceManager.readRetainedStatistics(context).paidCents;
                    return Math.max(paid.get("EUR"), paid.get("USD"));
                }
                return Math.max(new PenanceManager(context).getTotalPaidCents("EUR"),
                        new PenanceManager(context).getTotalPaidCents("USD"));
            case "subliminal_1": case "subliminal_100": case "subliminal_1000":
            case "subliminal_10000": return stats.getSubliminalImpressions();
            default: return isUnlocked(id) ? 1 : 0;
        }
    }

    static long target(String id) {
        if (id.startsWith("dom_")) return DomAchievementCatalog.target(id);
        switch (id) {
            case "first_block": case "first_session": case "first_custom_phrase":
            case "export_first": case "color_picker": return 1;
            case "sessions_10": case "phrase_library": return 10;
            case "sessions_100": case "blocks_10": return 100;
            case "blocks_100": return 1000;
            case "blocks_1000": return 10000;
            case "blocks_10000": return 100000;
            case "time_1hr": return 3600;
            case "time_10hr": return 86400;
            case "time_50hr": return 604800;
            case "time_200hr": return 2592000;
            case "streak_7": return 7;
            case "streak_30": return 30;
            case "export_artist": case "ntr_50": return 50;
            case "peak_50": case "ntr_500": return 500;
            case "peak_500": return 5000;
            case "style_explorer": return 8;
            case "border_artist": return 4;
            case "ntr_100": return 100;
            case "ntr_200": return 200;
            case "app_mode_guardian": case "limits_setter": case "limits_dual_guard":
            case "pact_sealed": case "pact_long_haul": case "hardcore_guardian":
            case "text_filter_enabled": case "wallet_keeper":
            case "paypal_vault": case "paid_pause": return 1;
            case "app_assignment_curator": return 3;
            case "text_filter_catalog": return 3;
            case "tribute_rulesmith": return 3;
            case "wallet_paid_10": return 1_000;
            case "wallet_paid_100": return 25_000;
            case "wallet_paid_500": return 100_000;
            case "wallet_paid_1000": return 1_000_000;
            case "subliminal_1": return 1;
            case "subliminal_100": return 100;
            case "subliminal_1000": return 1_000;
            case "subliminal_10000": return 10_000;
            default: return 0;
        }
    }

    private static boolean dateIs(int month, int day) {
        LocalDate now = LocalDate.now();
        return now.getMonthValue() == month && now.getDayOfMonth() == day;
    }
    private static boolean hourBetween(int start, int end) {
        int hour = LocalTime.now().getHour();
        return hour >= start && hour < end;
    }
    private static boolean hasCompleteWeekend(Collection<String> encodedDates) {
        Set<String> saturdays = new HashSet<>();
        Set<String> sundays = new HashSet<>();
        WeekFields iso = WeekFields.ISO;
        for (String encoded : encodedDates) {
            try {
                LocalDate date = LocalDate.parse(encoded);
                String week = date.get(iso.weekBasedYear()) + ":" + date.get(iso.weekOfWeekBasedYear());
                if (date.getDayOfWeek() == DayOfWeek.SATURDAY) saturdays.add(week);
                if (date.getDayOfWeek() == DayOfWeek.SUNDAY) sundays.add(week);
            } catch (Exception ignored) { }
        }
        saturdays.retainAll(sundays);
        return !saturdays.isEmpty();
    }

    private static List<Achievement> achievements() {
        List<Achievement> values = Arrays.asList(
                a("first_session", R.string.achievement_first_session, R.string.achievement_desc_first_session, "▶", "sessions", R.drawable.achievement_badge_first_session, false),
                a("sessions_10", R.string.achievement_regular, R.string.achievement_desc_sessions_10, "↻", "sessions", R.drawable.achievement_badge_sessions_10, false),
                a("sessions_100", R.string.achievement_veteran, R.string.achievement_desc_sessions_100, "✪", "sessions", R.drawable.achievement_badge_sessions_100, false),
                a("time_1hr", R.string.achievement_first_hour, R.string.achievement_desc_time_1hr, "◷", "time", R.drawable.achievement_badge_time_1hr, false),
                a("time_10hr", R.string.achievement_dedicated, R.string.achievement_desc_time_10hr, "◷", "time", R.drawable.achievement_badge_time_10hr, false),
                a("time_50hr", R.string.achievement_committed, R.string.achievement_desc_time_50hr, "◷", "time", R.drawable.achievement_badge_time_50hr, false),
                a("time_200hr", R.string.achievement_platinum_protection, R.string.achievement_desc_time_200hr, "◆", "time", R.drawable.achievement_badge_time_200hr, false),
                a("streak_7", R.string.achievement_week_warrior, R.string.achievement_desc_streak_7, "♨", "streaks", R.drawable.achievement_badge_streak_7, false),
                a("streak_30", R.string.achievement_month_master, R.string.achievement_desc_streak_30, "♨", "streaks", R.drawable.achievement_badge_streak_30, false),
                a("app_mode_guardian", R.string.achievement_app_mode_guardian, R.string.achievement_desc_app_mode_guardian, "▶", "app_mode", R.drawable.achievement_badge_app_mode_guardian, false),
                a("app_assignment_curator", R.string.achievement_app_assignment_curator, R.string.achievement_desc_app_assignment_curator, "▦", "app_mode", R.drawable.achievement_badge_app_assignment_curator, false),
                a("limits_setter", R.string.achievement_limits_setter, R.string.achievement_desc_limits_setter, "⌛", "limits", R.drawable.achievement_badge_limits_setter, false),
                a("limits_dual_guard", R.string.achievement_limits_dual_guard, R.string.achievement_desc_limits_dual_guard, "⌛", "limits", R.drawable.achievement_badge_limits_dual_guard, false),
                a("pact_sealed", R.string.achievement_pact_sealed, R.string.achievement_desc_pact_sealed, "🔒", "pact", R.drawable.achievement_badge_pact_sealed, false),
                a("pact_long_haul", R.string.achievement_pact_long_haul, R.string.achievement_desc_pact_long_haul, "⛓", "pact", R.drawable.achievement_badge_pact_long_haul, false),
                a("hardcore_guardian", R.string.achievement_hardcore_guardian, R.string.achievement_desc_hardcore_guardian, "♜", "hardcore", R.drawable.achievement_badge_hardcore_guardian, false),
                a("first_block", R.string.achievement_first_block, R.string.achievement_desc_first_block, "★", "blocks", R.drawable.achievement_badge_first_block, false),
                a("blocks_10", R.string.achievement_getting_started, R.string.achievement_desc_blocks_10, "10", "blocks", R.drawable.achievement_badge_blocks_10, false),
                a("blocks_100", R.string.achievement_centurion, R.string.achievement_desc_blocks_100, "100", "blocks", R.drawable.achievement_badge_blocks_100, false),
                a("blocks_1000", R.string.achievement_block_master, R.string.achievement_desc_blocks_1000, "♛", "blocks", R.drawable.achievement_badge_blocks_1000, false),
                a("blocks_10000", R.string.achievement_ultimate_blocker, R.string.achievement_desc_blocks_10000, "♚", "blocks", R.drawable.achievement_badge_blocks_10000, false),
                a("peak_50", R.string.achievement_peak_50, R.string.achievement_desc_peak_50, "▥", "peaks", R.drawable.achievement_badge_peak_50, false),
                a("peak_500", R.string.achievement_peak_500, R.string.achievement_desc_peak_500, "▥", "peaks", R.drawable.achievement_badge_peak_500, false),
                a("text_filter_enabled", R.string.achievement_text_filter_enabled, R.string.achievement_desc_text_filter_enabled, "Aa", "censor", R.drawable.achievement_badge_text_filter_enabled, false),
                a("text_filter_catalog", R.string.achievement_text_filter_catalog, R.string.achievement_desc_text_filter_catalog, "Aa", "censor", R.drawable.achievement_badge_text_filter_catalog, false),
                a("style_explorer", R.string.achievement_style_explorer, R.string.achievement_desc_style_explorer, "◒", "custom", R.drawable.achievement_badge_style_explorer, false),
                a("border_artist", R.string.achievement_border_artist, R.string.achievement_desc_border_artist, "▣", "custom", R.drawable.achievement_badge_border_artist, false),
                a("color_picker", R.string.achievement_color_picker, R.string.achievement_desc_color_picker, "◉", "custom", R.drawable.achievement_badge_color_picker, false),
                a("first_custom_phrase", R.string.achievement_first_custom_phrase, R.string.achievement_desc_first_custom_phrase, "✎", "custom", R.drawable.achievement_badge_first_custom_phrase, false),
                a("phrase_library", R.string.achievement_phrase_library, R.string.achievement_desc_phrase_library, "▤", "custom", R.drawable.achievement_badge_phrase_library, false),
                a("export_first", R.string.achievement_export_first, R.string.achievement_desc_export_first, "⇩", "export", R.drawable.achievement_badge_export_first, false),
                a("export_artist", R.string.achievement_export_artist, R.string.achievement_desc_export_artist, "⇩", "export", R.drawable.achievement_badge_export_artist, false),
                a("subliminal_1", R.string.achievement_subliminal_1, R.string.achievement_desc_subliminal_1, "◉", "subliminal", R.drawable.achievement_badge_subliminal_1, false),
                a("subliminal_100", R.string.achievement_subliminal_100, R.string.achievement_desc_subliminal_100, "◉", "subliminal", R.drawable.achievement_badge_subliminal_100, false),
                a("subliminal_1000", R.string.achievement_subliminal_1000, R.string.achievement_desc_subliminal_1000, "◉", "subliminal", R.drawable.achievement_badge_subliminal_1000, false),
                a("subliminal_10000", R.string.achievement_subliminal_10000, R.string.achievement_desc_subliminal_10000, "◉", "subliminal", R.drawable.achievement_badge_subliminal_10000, false),
                a("wallet_keeper", R.string.achievement_wallet_keeper, R.string.achievement_desc_wallet_keeper, "¤", "wallet", R.drawable.achievement_badge_wallet_keeper, false),
                a("tribute_rulesmith", R.string.achievement_tribute_rulesmith, R.string.achievement_desc_tribute_rulesmith, "¤", "wallet", R.drawable.achievement_badge_tribute_rulesmith, false),
                a("paypal_vault", R.string.achievement_paypal_vault, R.string.achievement_desc_paypal_vault, "¤", "wallet", R.drawable.achievement_badge_paypal_vault, false),
                a("paid_pause", R.string.achievement_paid_pause, R.string.achievement_desc_paid_pause, "Ⅱ", "wallet", R.drawable.achievement_badge_paid_pause, false),
                a("wallet_paid_10", R.string.achievement_wallet_paid_10, R.string.achievement_desc_wallet_paid_10, "◆", "wallet", R.drawable.achievement_badge_wallet_paid_10, false),
                a("wallet_paid_100", R.string.achievement_wallet_paid_100, R.string.achievement_desc_wallet_paid_100, "◆", "wallet", R.drawable.achievement_badge_wallet_paid_100, false),
                a("wallet_paid_500", R.string.achievement_wallet_paid_500, R.string.achievement_desc_wallet_paid_500, "◆", "wallet", R.drawable.achievement_badge_wallet_paid_500, false),
                a("wallet_paid_1000", R.string.achievement_wallet_paid_1000, R.string.achievement_desc_wallet_paid_1000, "◆", "wallet", R.drawable.achievement_badge_wallet_paid_1000, false),
                a("midnight_blocker", R.string.achievement_midnight_blocker, R.string.achievement_desc_midnight, "☾", "hidden", R.drawable.achievement_badge_midnight_blocker, true),
                a("early_bird", R.string.achievement_early_bird, R.string.achievement_desc_early_bird, "☀", "hidden", R.drawable.achievement_badge_early_bird, true),
                a("night_owl", R.string.achievement_night_owl, R.string.achievement_desc_night_owl, "◉", "hidden", R.drawable.achievement_badge_night_owl, true),
                a("weekend_warrior", R.string.achievement_weekend_warrior, R.string.achievement_desc_weekend_warrior, "☀", "hidden", R.drawable.achievement_badge_weekend_warrior, true),
                a("new_year", R.string.achievement_new_year, R.string.achievement_desc_new_year, "✦", "hidden", R.drawable.achievement_badge_new_year, true),
                a("halloween_block", R.string.achievement_spooky, R.string.achievement_desc_halloween, "◈", "hidden", R.drawable.achievement_badge_halloween_block, true),
                a("christmas_block", R.string.achievement_holiday, R.string.achievement_desc_christmas, "♣", "hidden", R.drawable.achievement_badge_christmas_block, true),
                a("valentine_block", R.string.achievement_love, R.string.achievement_desc_valentine, "♥", "hidden", R.drawable.achievement_badge_valentine_block, true),
                a("ntr_50", R.string.achievement_get_cucked, R.string.achievement_desc_get_cucked, "♈", "hidden", R.drawable.achievement_badge_ntr_50, true),
                a("ntr_100", R.string.achievement_get_cucked_2, R.string.achievement_desc_get_cucked_2, "♈", "hidden", R.drawable.achievement_badge_ntr_100, true),
                a("ntr_200", R.string.achievement_get_cucked_3, R.string.achievement_desc_get_cucked_3, "♈", "hidden", R.drawable.achievement_badge_ntr_200, true),
                a("ntr_500", R.string.achievement_get_cucked_4, R.string.achievement_desc_get_cucked_4, "♈", "hidden", R.drawable.achievement_badge_ntr_500, true),
                a("legend", R.string.achievement_legend, R.string.achievement_desc_legend, "★", "special", R.drawable.achievement_badge_legend, false));
        return Collections.unmodifiableList(values);
    }

    private static Achievement a(String id, int name, int description, String icon,
            String category, int badgeArtRes, boolean hidden) {
        return new Achievement(id, name, description, icon, category, badgeArtRes, hidden);
    }

    public static final class Achievement {
        private final String id; private final int name; private final int description;
        private final String icon; private final String category; private final int badgeArtRes;
        private final boolean hidden;
        Achievement(String id, int name, int description, String icon, String category,
                int badgeArtRes, boolean hidden) {
            this.id = id; this.name = name; this.description = description; this.icon = icon;
            this.category = category; this.badgeArtRes = badgeArtRes; this.hidden = hidden;
        }
        public String getId() { return id; }
        public AchievementRole getRole() { return id.startsWith("dom_") ? AchievementRole.DOM : AchievementRole.SUB; }
        public int getName() { return name; }
        public int getDescription() { return description; }
        public String getIcon() { return icon; }
        public String getCategory() { return category; }

        /**
         * @return the generated illustrated badge drawable for this achievement.
         */
        public int getBadgeArtRes() { return badgeArtRes; }

        /**
         * Legacy asset accessor retained for binary/source compatibility.
         *
         * <p>Badges no longer use licensed asset filenames; callers should use {@link
         * #getBadgeArtRes()} instead.
         */
        @Deprecated public String getBadge() { return null; }
        public boolean isHidden() { return hidden; }
    }

    public static final class Progress {
        private final long current; private final long target; private final boolean unlocked;
        private final boolean known;
        Progress(long current, long target, boolean unlocked) {
            this(current, target, unlocked, true);
        }
        Progress(long current, long target, boolean unlocked, boolean known) {
            this.current = unlocked && target > 0 ? target : current;
            this.target = target; this.unlocked = unlocked;
            this.known = known || unlocked;
        }
        public long getCurrent() { return current; }
        public long getTarget() { return target; }
        public boolean isUnlocked() { return unlocked; }
        public boolean isKnown() { return known; }
        public boolean isCountable() { return target > 0; }
        public int percent() {
            return target <= 0 ? (unlocked ? 100 : 0)
                    : (int) Math.min(100, Math.round(current * 100f / target));
        }
    }
}
