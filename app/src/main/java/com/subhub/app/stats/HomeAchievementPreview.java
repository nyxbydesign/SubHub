package com.subhub.app.stats;

import android.content.Context;

/** Computes achievement eligibility away from drawing and the one-second Home timer. */
public final class HomeAchievementPreview {
    public final AchievementManager achievements;
    public final AchievementManager.Achievement next;
    public final AchievementManager.Progress progress;
    public final String fingerprint;

    private HomeAchievementPreview(
            AchievementManager manager,
            AchievementManager.Achievement next,
            AchievementManager.Progress progress) {
        this.achievements = manager;
        this.next = next;
        this.progress = progress;
        fingerprint =
                manager.getRole() + ":" + manager.getUnlockedCount()
                        + ":"
                        + (next == null
                                ? "complete"
                                : next.getId()
                                        + ":"
                                        + progress.getCurrent()
                                        + ":"
                                        + progress.getTarget());
    }

    public static HomeAchievementPreview load(Context app, StatsSnapshot stats) {
        AchievementManager manager = AchievementManager.forWorkspace(app.getApplicationContext());
        try {
            if (manager.getRole() == AchievementRole.DOM) {
                DomStatsRepository.Loaded aggregate = new DomStatsRepository(app).loadWithHistory();
                HistoricalAchievementBackfill.dom(app, manager, aggregate);
                manager.checkDomAchievements(aggregate.stats);
            } else return loadLocal(app, LocalRetainedStatistics.load(app));
        } catch (Exception unavailable) {
            throw new IllegalStateException("Achievement history unavailable", unavailable);
        }
        return from(manager, stats);
    }

    static HomeAchievementPreview loadLocal(Context app, LocalRetainedStatistics facts) {
        AchievementManager manager = AchievementManager.forWorkspace(app);
        if (manager.getRole() != AchievementRole.SUB || !manager.isAccessible())
            throw new SecurityException("Sub achievements unavailable");
        HistoricalAchievementBackfill.local(app, manager, facts);
        manager.checkRetainedAchievements(facts.stats);
        return from(manager, facts.stats);
    }

    static HomeAchievementPreview loadDom(Context app, DomStatsRepository.Loaded stats) {
        AchievementManager manager = AchievementManager.forWorkspace(app);
        if (manager.getRole() != AchievementRole.DOM || !manager.isAccessible())
            throw new SecurityException("Dom achievements unavailable");
        try { HistoricalAchievementBackfill.dom(app, manager, stats); }
        catch (Exception unavailable) { throw new IllegalStateException("Achievement history unavailable", unavailable); }
        manager.checkDomAchievements(stats.stats);
        return from(manager, null);
    }

    static HomeAchievementPreview from(AchievementManager manager, StatsSnapshot stats) {
        for (AchievementManager.Achievement candidate : manager.all()) {
            if (manager.isUnlocked(candidate.getId()) || candidate.isHidden()) continue;
            AchievementManager.Progress progress = manager.progress(candidate, stats);
            if (progress.isCountable())
                return new HomeAchievementPreview(manager, candidate, progress);
        }
        return new HomeAchievementPreview(manager, null, null);
    }
}
