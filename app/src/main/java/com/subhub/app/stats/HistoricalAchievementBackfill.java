package com.subhub.app.stats;

import android.content.Context;
import com.subhub.app.remote.RemoteStore;
import com.subhub.app.security.DeviceRolePreferences;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Reconciles retained eligibility without invoking any event, checkout or punishment reducer. */
final class HistoricalAchievementBackfill {
    private HistoricalAchievementBackfill() { }

    static void local(Context context, AchievementManager manager, LocalRetainedStatistics facts) {
        manager.historicalPaid(facts.paid.get("EUR"), facts.paid.get("USD"));
        boolean initial = manager.reconcileHistory(facts.history.awards(false),
                Collections.singleton("local"), facts.stats, null);
        long oldBlocks = 0;
        String today = LocalDate.now().toString();
        for (Map.Entry<String, Map<String, Long>> day : facts.history.days().entrySet())
            if (day.getKey().compareTo(today) < 0)
                oldBlocks = DomStatsSnapshot.add(oldBlocks, Math.max(0, day.getValue().getOrDefault("censors", 0L)));
        MilestoneManager.acknowledgeHistory(context, initial ? facts.stats.getTotalBlocks() : oldBlocks);
    }

    static void dom(Context context, AchievementManager manager, DomStatsRepository.Loaded data) throws Exception {
        if (!AchievementRole.DOM.accessible(context)) throw new SecurityException("Dom statistics unavailable");
        LocalRetainedStatistics local = data.local;
        if (local != null) local(context, new AchievementManager(context), local);
        List<DomDailySnapshot.History> histories = data.histories;
        Map<String, DomDailySnapshot.History> unique = new LinkedHashMap<>();
        for (DomDailySnapshot.History value : histories) {
            DomDailySnapshot.History previous = unique.get(value.identity.identity);
            if (previous == null || value.identity.compareTo(previous.identity) > 0) unique.put(value.identity.identity, value);
        }
        Set<String> sources = new LinkedHashSet<>();
        Map<String, Map<String, Long>> dates = new TreeMap<>();
        for (DomDailySnapshot.History value : unique.values()) {
            sources.add(value.identity.identity + ":" + value.identity.generation);
            for (Map.Entry<String, Map<String, Long>> day : value.days.entrySet()) {
                Map<String, Long> totals = dates.computeIfAbsent(day.getKey(), ignored -> new LinkedHashMap<>());
                for (Map.Entry<String, Long> metric : day.getValue().entrySet()) {
                    boolean financial = metric.getKey().startsWith("paid_") || metric.getKey().startsWith("assessed_");
                    if (metric.getValue() < 0 || financial && !value.walletAllowed) continue;
                    totals.merge(metric.getKey(), metric.getValue(), DomStatsSnapshot::add);
                }
            }
        }
        RetainedStatistics history = new RetainedStatistics(ZoneId.systemDefault(), System.currentTimeMillis(), dates);
        manager.reconcileHistory(history.awards(true), sources, null, data.stats);
    }
}
