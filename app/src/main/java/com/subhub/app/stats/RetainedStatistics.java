package com.subhub.app.stats;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** A lower-bound projection of retained facts; overlapping representations are never added twice. */
final class RetainedStatistics {
    static final String[] METRICS = {"service_ms", "sessions", "censors", "limits_ms", "limit_stops",
            "tributes", "whispers", "popups", "photos", "videos", "paid_EUR", "paid_USD", "assessed_EUR", "assessed_USD"};
    private final ZoneId zone;
    private final long now;
    private final Map<String, Map<String, Long>> recorded = new TreeMap<>();
    private final Map<String, Map<String, Map<String, Long>>> recovered = new HashMap<>();
    private final Set<String> receipts = new LinkedHashSet<>();
    private final Map<String, StatsRepository.SessionEntry> sessions = new LinkedHashMap<>();
    private final Set<String> activeDates = new java.util.TreeSet<>();
    private Map<String, Map<String, Long>> materialized;
    private final Map<String, Long> undated = new LinkedHashMap<>();

    RetainedStatistics(ZoneId zone, long now, Map<String, Map<String, Long>> days) {
        this.zone = zone;
        this.now = now;
        mergeRecorded(days);
    }

    void mergeRecorded(Map<String, Map<String, Long>> days) {
        materialized = null;
        for (Map.Entry<String, Map<String, Long>> day : days.entrySet()) {
            if (!validDay(day.getKey())) continue;
            Map<String, Long> values = recorded.computeIfAbsent(day.getKey(), ignored -> new LinkedHashMap<>());
            for (Map.Entry<String, Long> value : day.getValue().entrySet())
                values.merge(value.getKey(), value.getValue(), Math::max);
        }
    }

    void activeDates(Set<String> dates) {
        for (String day : dates) if (validDay(day)) activeDates.add(day);
    }

    void session(StatsRepository.SessionEntry entry) {
        materialized = null;
        long start = entry.getStartMillis(), seconds = entry.getDurationSeconds();
        if (start <= 0 || start > now || seconds < 0 || seconds > (Long.MAX_VALUE - start) / 1000
                || start + seconds * 1000 > now) return;
        // Legacy history has no UUID. Use the original immutable tuple, not its timestamp alone.
        String key = start + ":" + seconds + ":" + entry.getBlocks();
        StatsRepository.SessionEntry previous = sessions.get(key);
        if (previous == null) sessions.put(key, entry);
        else sessions.put(key, new StatsRepository.SessionEntry(start, seconds, entry.getBlocks(),
                Math.max(previous.getLimitedAppMillis(), entry.getLimitedAppMillis()),
                Math.max(previous.getLimitInterventions(), entry.getLimitInterventions()),
                Math.max(previous.getTributeEvents(), entry.getTributeEvents()),
                Math.max(previous.getTributeCents(), entry.getTributeCents()),
                Math.max(previous.getSubliminals(), entry.getSubliminals()),
                Math.max(previous.getPopupImpressions(), entry.getPopupImpressions()),
                Math.max(previous.getTamperEvents(), entry.getTamperEvents())));
    }

    void event(String family, String id, String day, String metric, long amount) {
        if (id == null || id.isEmpty() || amount < 0 || !validDay(day)) return;
        if (!receipts.add(family + ":" + id + ":" + metric)) return;
        materialized = null;
        recovered.computeIfAbsent(family, ignored -> new TreeMap<>())
                .computeIfAbsent(day, ignored -> new LinkedHashMap<>())
                .merge(metric, amount, DomStatsSnapshot::add);
    }

    String day(long timestamp) {
        return Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate().toString();
    }

    Map<String, Map<String, Long>> days() {
        if (materialized != null) return materialized;
        Map<String, Map<String, Long>> result = new TreeMap<>();
        for (Map.Entry<String, Map<String, Long>> day : recorded.entrySet())
            result.put(day.getKey(), new LinkedHashMap<>(day.getValue()));
        Map<String, Map<String, Long>> sessionDays = new TreeMap<>();
        for (Map.Entry<String, StatsRepository.SessionEntry> saved : sessions.entrySet()) {
            StatsRepository.SessionEntry entry = saved.getValue();
            String date = day(entry.getStartMillis());
            add(sessionDays, date, "sessions", 1);
            for (Map.Entry<String, Long> slice : DailyTimeSlices.split(entry.getStartMillis(),
                    entry.getDurationSeconds() * 1000, zone).entrySet())
                add(sessionDays, slice.getKey(), "service_ms", slice.getValue());
            // A session-wide count has no event clock within a session crossing midnight.
            long lastObserved = entry.getStartMillis() + entry.getDurationSeconds() * 1000;
            if (entry.getDurationSeconds() > 0) lastObserved--;
            if (date.equals(day(lastObserved))) {
                add(sessionDays, date, "censors", entry.getBlocks());
                add(sessionDays, date, "limits_ms", entry.getLimitedAppMillis());
                add(sessionDays, date, "limit_stops", entry.getLimitInterventions());
                add(sessionDays, date, "tributes", entry.getTributeEvents());
                add(sessionDays, date, "assessed_EUR", entry.getTributeCents());
                add(sessionDays, date, "whispers", entry.getSubliminals());
                add(sessionDays, date, "popups", entry.getPopupImpressions());
            }
        }
        mergeMax(result, sessionDays);
        for (Map<String, Map<String, Long>> family : recovered.values()) mergeMax(result, family);
        Map<String, Map<String, Long>> frozen = new TreeMap<>();
        for (Map.Entry<String, Map<String, Long>> day : result.entrySet())
            frozen.put(day.getKey(), Collections.unmodifiableMap(day.getValue()));
        materialized = Collections.unmodifiableMap(frozen);
        return materialized;
    }

    long total(String metric) {
        long result = 0;
        for (Map<String, Long> day : days().values())
            result = DomStatsSnapshot.add(result, Math.max(0, day.getOrDefault(metric, 0L)));
        return Math.max(result, undated.getOrDefault(metric, 0L));
    }

    void undated(String metric, long amount) {
        if (amount >= 0) undated.merge(metric, amount, Math::max);
    }

    long sessionSeconds() {
        long retained = 0;
        for (StatsRepository.SessionEntry entry : sessions.values())
            retained = DomStatsSnapshot.add(retained, entry.getDurationSeconds());
        return Math.max(retained, total("service_ms") / 1000);
    }

    long peak() {
        long result = 0;
        for (StatsRepository.SessionEntry entry : sessions.values()) result = Math.max(result, entry.getBlocks());
        return result;
    }

    long longest() {
        long result = 0;
        for (StatsRepository.SessionEntry entry : sessions.values()) result = Math.max(result, entry.getDurationSeconds());
        return result;
    }

    java.util.List<StatsRepository.SessionEntry> sessions() {
        java.util.List<StatsRepository.SessionEntry> result = new java.util.ArrayList<>(sessions.values());
        result.sort(java.util.Comparator.comparingLong(StatsRepository.SessionEntry::getStartMillis));
        return Collections.unmodifiableList(result);
    }

    Set<String> activityDates() {
        Set<String> result = new java.util.TreeSet<>(activeDates);
        for (StatsRepository.SessionEntry entry : sessions.values()) result.add(day(entry.getStartMillis()));
        for (Map.Entry<String, Map<String, Long>> day : days().entrySet())
            if (day.getValue().getOrDefault("service_ms", 0L) > 0) result.add(day.getKey());
        return result;
    }

    /** First date supported by the retained lower bound, never a fabricated lifetime date. */
    long threshold(String metric, long target) {
        long running = 0;
        for (Map.Entry<String, Map<String, Long>> day : days().entrySet()) {
            running = DomStatsSnapshot.add(running, Math.max(0, day.getValue().getOrDefault(metric, 0L)));
            if (running >= target) return endOfDay(day.getKey());
        }
        return 0;
    }

    Map<String, Long> awards(boolean dom) {
        Map<String, Long> result = new LinkedHashMap<>();
        if (dom) {
            for (String id : new String[]{"dom_time_1hr", "dom_time_24hr", "dom_time_7days", "dom_time_30days"})
                result.put(id, threshold("service_ms", multiply(AchievementManager.target(id), 1000)));
            for (String id : new String[]{"dom_sessions_10", "dom_sessions_100"})
                result.put(id, threshold("sessions", AchievementManager.target(id)));
            for (String id : new String[]{"dom_tribute_10", "dom_tribute_250", "dom_tribute_1000", "dom_tribute_10000"})
                result.put(id, earliest(threshold("paid_EUR", AchievementManager.target(id)),
                        threshold("paid_USD", AchievementManager.target(id))));
            return result;
        }
        for (String id : new String[]{"first_block", "blocks_10", "blocks_100", "blocks_1000", "blocks_10000"})
            result.put(id, threshold("censors", AchievementManager.target(id)));
        for (String id : new String[]{"first_session", "sessions_10", "sessions_100"})
            result.put(id, threshold("sessions", AchievementManager.target(id)));
        for (String id : new String[]{"time_1hr", "time_10hr", "time_50hr", "time_200hr"})
            result.put(id, threshold("service_ms", multiply(AchievementManager.target(id), 1000)));
        for (String id : new String[]{"export_first", "export_artist"})
            result.put(id, threshold("photos", AchievementManager.target(id)));
        for (String id : new String[]{"subliminal_1", "subliminal_100", "subliminal_1000", "subliminal_10000"})
            result.put(id, threshold("whispers", AchievementManager.target(id)));
        for (String id : new String[]{"wallet_paid_10", "wallet_paid_100", "wallet_paid_500", "wallet_paid_1000"})
            result.put(id, earliest(threshold("paid_EUR", AchievementManager.target(id)),
                    threshold("paid_USD", AchievementManager.target(id))));
        for (String id : new String[]{"peak_50", "peak_500"}) {
            long date = 0;
            for (StatsRepository.SessionEntry entry : sessions.values())
                if (entry.getBlocks() >= AchievementManager.target(id))
                    date = earliest(date, entry.getStartMillis() + entry.getDurationSeconds() * 1000);
            result.put(id, date);
        }
        int streak = 0;
        LocalDate previous = null;
        Set<String> active = activityDates();
        for (String date : active) {
            LocalDate day = LocalDate.parse(date);
            streak = previous != null && previous.plusDays(1).equals(day) ? streak + 1 : 1;
            if (streak >= 7) result.putIfAbsent("streak_7", endOfDay(date));
            if (streak >= 30) result.putIfAbsent("streak_30", endOfDay(date));
            if (day.getDayOfWeek() == java.time.DayOfWeek.SUNDAY && active.contains(day.minusDays(1).toString()))
                result.putIfAbsent("weekend_warrior", endOfDay(date));
            previous = day;
        }
        for (Map.Entry<String, Map<String, Long>> date : days().entrySet()) {
            if (date.getValue().getOrDefault("censors", 0L) <= 0) continue;
            LocalDate day = LocalDate.parse(date.getKey());
            String id = day.getMonthValue() == 1 && day.getDayOfMonth() == 1 ? "new_year"
                    : day.getMonthValue() == 10 && day.getDayOfMonth() == 31 ? "halloween_block"
                    : day.getMonthValue() == 12 && day.getDayOfMonth() == 25 ? "christmas_block"
                    : day.getMonthValue() == 2 && day.getDayOfMonth() == 14 ? "valentine_block" : null;
            if (id != null) result.putIfAbsent(id, endOfDay(date.getKey()));
        }
        return result;
    }

    private boolean validDay(String date) {
        try { return !LocalDate.parse(date).isAfter(Instant.ofEpochMilli(now).atZone(zone).toLocalDate()); }
        catch (RuntimeException invalid) { return false; }
    }

    private long endOfDay(String date) {
        return Math.min(now, LocalDate.parse(date).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1);
    }

    private static long earliest(long a, long b) { return a <= 0 ? b : b <= 0 ? a : Math.min(a, b); }
    private static long multiply(long a, long b) { return a > Long.MAX_VALUE / b ? Long.MAX_VALUE : a * b; }
    private static void add(Map<String, Map<String, Long>> target, String day, String metric, long amount) {
        target.computeIfAbsent(day, ignored -> new LinkedHashMap<>()).merge(metric, amount, DomStatsSnapshot::add);
    }
    private static void mergeMax(Map<String, Map<String, Long>> target, Map<String, Map<String, Long>> source) {
        for (Map.Entry<String, Map<String, Long>> day : source.entrySet()) {
            Map<String, Long> values = target.computeIfAbsent(day.getKey(), ignored -> new LinkedHashMap<>());
            for (Map.Entry<String, Long> value : day.getValue().entrySet()) values.merge(value.getKey(), value.getValue(), Math::max);
        }
    }
}
