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
                manager.getUnlockedCount()
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
        AchievementManager manager = new AchievementManager(app.getApplicationContext());
        manager.checkAchievements(stats);
        for (AchievementManager.Achievement candidate : manager.all()) {
            if (manager.isUnlocked(candidate.getId()) || candidate.isHidden()) continue;
            AchievementManager.Progress progress = manager.progress(candidate, stats);
            if (progress.isCountable())
                return new HomeAchievementPreview(manager, candidate, progress);
        }
        return new HomeAchievementPreview(manager, null, null);
    }
}
