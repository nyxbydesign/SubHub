package com.subhub.app.stats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public final class StatsAndAchievementsTest {
    @Test
    public void recoveredCountersHistoryMilestonesAndAchievementsWork() {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(StatsRepository.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit();
        context.getSharedPreferences("betablocker_achievements", Context.MODE_PRIVATE)
                .edit().clear().commit();

        StatsRepository repository = new StatsRepository(context);
        repository.startSession();
        SystemClock.sleep(1100L);
        Set<String> allCategories = new LinkedHashSet<>(Arrays.asList(
                "genitals_female", "genitals_male", "breasts", "buttocks", "anus",
                "face", "belly", "male_chest", "feet", "armpits", "genitals_covered",
                "breasts_covered", "buttocks_covered", "anus_covered", "belly_covered",
                "feet_covered", "armpits_covered"));
        repository.onTrackIds(Arrays.asList(7, 7, 8), allCategories);
        StatsSnapshot live = repository.load();
        assertEquals(2, live.getTotalBlocks());
        assertEquals(2, live.getCurrentSessionBlocks());
        assertTrue(live.getCurrentSessionSeconds() >= 1);
        assertEquals(2, live.getAllCategoryCensors());
        assertEquals(1, live.getCurrentStreak());
        assertFalse(live.getActiveDates().isEmpty());
        repository.endSession();
        assertEquals(1, repository.getSessionHistory().size());

        for (int index = 0; index < 35; index++) {
            repository.startSession();
            repository.endSession();
        }
        assertEquals(30, repository.getSessionHistory().size());

        AchievementManager achievements = new AchievementManager(context);
        assertFalse(achievements.isUnlocked("first_block"));
        long beforeUnlock = System.currentTimeMillis();
        assertFalse(achievements.checkAchievements(repository.load()).isEmpty());
        AchievementManager reopenedAchievements = new AchievementManager(context);
        assertTrue(reopenedAchievements.isUnlocked("first_block"));
        assertTrue(reopenedAchievements.getUnlockedAt("first_block") >= beforeUnlock);
        assertTrue(reopenedAchievements.getUnlockedAt("first_block")
                <= System.currentTimeMillis());

        MilestoneManager.Result milestone = MilestoneManager.takeUnseen(context, 10);
        assertNotNull(milestone);
        assertEquals(10, milestone.getValue());
        assertTrue(milestone.isMajor());
        assertEquals(15, MilestoneManager.next(10));
    }

    @Test
    public void legacyAchievementDatesStayUnknownWithoutEvidence() {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("betablocker_achievements", Context.MODE_PRIVATE)
                .edit().clear().putStringSet("unlocked",
                        new LinkedHashSet<>(Arrays.asList("first_block"))).commit();

        AchievementManager manager = new AchievementManager(context);
        long expected = LocalDate.of(2026, 8, 27).atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli();
        assertEquals(0L, manager.getUnlockedAt("first_block"));

        context.getSharedPreferences("betablocker_achievements", Context.MODE_PRIVATE)
                .edit().putLong("unlocked_at.first_block", expected + 1234L).commit();
        assertEquals(expected + 1234L,
                new AchievementManager(context).getUnlockedAt("first_block"));
    }

    @Test
    public void activeSessionSurvivesRepositoryRecreationAndIgnoresIdleTracks() {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(StatsRepository.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit();

        StatsRepository first = new StatsRepository(context);
        first.startSession();
        first.onTrackIds(Arrays.asList(31, 32), null);

        StatsRepository reopened = new StatsRepository(context);
        assertEquals(2, reopened.load().getCurrentSessionBlocks());
        reopened.onTrackIds(Arrays.asList(33), null);
        assertEquals(3, reopened.load().getCurrentSessionBlocks());
        reopened.endSession();

        long total = reopened.load().getTotalBlocks();
        reopened.onTrackIds(Arrays.asList(99), null);
        assertEquals(total, reopened.load().getTotalBlocks());
        assertEquals(0, reopened.load().getCurrentSessionBlocks());
    }
}
