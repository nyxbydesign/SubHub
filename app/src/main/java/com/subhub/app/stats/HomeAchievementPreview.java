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
        if (manager.getRole() == AchievementRole.DOM)
            manager.checkDomAchievements(new DomStatsRepository(app).load());
        else {
            if (stats == null) stats = new StatsRepository(app).load();
            manager.checkAchievements(stats);
        }
        return from(manager, stats);
    }

    static HomeAchievementPreview loadDom(Context app, DomStatsSnapshot stats) {
        AchievementManager manager = AchievementManager.forWorkspace(app);
        if (manager.getRole() != AchievementRole.DOM || !manager.isAccessible())
            throw new SecurityException("Dom achievements unavailable");
        manager.checkDomAchievements(stats);
        return from(manager, null);
    }

    private static HomeAchievementPreview from(AchievementManager manager, StatsSnapshot stats) {
        for (AchievementManager.Achievement candidate : manager.all()) {
            if (manager.isUnlocked(candidate.getId()) || candidate.isHidden()) continue;
            AchievementManager.Progress progress = manager.progress(candidate, stats);
            if (progress.isCountable())
                return new HomeAchievementPreview(manager, candidate, progress);
        }
        return new HomeAchievementPreview(manager, null, null);
    }
}
