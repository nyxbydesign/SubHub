package com.subhub.app.stats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.ViewGroup;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.subhub.app.R;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
public final class AchievementGroupOrderTest {
    @Test public void currentCatalogKeepsAllFeatureGroupsAheadOfCompletion() {
        Context context = ApplicationProvider.getApplicationContext();
        List<AchievementManager.Achievement> catalog = new AchievementManager(context).all();
        Map<String, List<AchievementManager.Achievement>> groups =
                AchievementsActivity.orderedGroups(catalog);
        List<String> categories = new ArrayList<>(groups.keySet());
        assertEquals("special", categories.get(categories.size() - 1));
        assertTrue(categories.indexOf("subliminal") < categories.indexOf("hidden"));
        List<AchievementManager.Achievement> displayed = flatten(groups);
        assertEquals(catalog.size(), displayed.size());
        assertTrue(displayed.containsAll(catalog));
        assertEquals("legend", displayed.get(displayed.size() - 1).getId());
        assertEquals("sessions", categories.get(0));
        assertEquals("first_session", displayed.get(0).getId());
        assertEquals(List.of("color_picker", "border_artist", "style_explorer"),
                ids(groups.get("appearance")));
        assertEquals(List.of("first_custom_phrase", "phrase_library"),
                ids(groups.get("phrases")));
        assertEquals(List.of("pack_curator"), ids(groups.get("packs")));
        assertTrue(categories.indexOf("appearance") < categories.indexOf("phrases"));
        assertTrue(categories.indexOf("phrases") < categories.indexOf("packs"));
        assertTrue(categories.indexOf("wallet_setup") < categories.indexOf("wallet_payments"));
        for (AchievementManager.Achievement achievement : catalog) {
            assertTrue(groups.get(AchievementsActivity.displayCategory(achievement))
                    .contains(achievement));
            if (List.of("color_picker", "border_artist", "style_explorer",
                    "first_custom_phrase", "phrase_library", "pack_curator")
                    .contains(achievement.getId())) assertEquals("custom", achievement.getCategory());
        }
    }

    @Test public void progressionUsesRequirementsRatherThanCatalogAppendOrder() {
        Context context = ApplicationProvider.getApplicationContext();
        List<AchievementManager.Achievement> reversed = new ArrayList<>(
                new AchievementManager(context).all());
        Collections.reverse(reversed);
        Map<String, List<AchievementManager.Achievement>> groups =
                AchievementsActivity.orderedGroups(reversed);
        for (String category : List.of("sessions", "time", "streaks", "app_mode", "blocks",
                "peaks", "censor", "appearance", "phrases", "export", "subliminal",
                "wallet_payments")) {
            long previous = 0;
            for (AchievementManager.Achievement achievement : groups.get(category)) {
                long requirement = AchievementManager.target(achievement.getId());
                assertTrue("Requirements must ascend within " + category, requirement >= previous);
                previous = requirement;
            }
        }
        assertEquals(List.of("color_picker", "border_artist", "style_explorer"),
                ids(groups.get("appearance")));
        // Equal/non-comparable milestones keep their existing order instead of arbitrary sorting.
        List<AchievementManager.Achievement> hidden = new ArrayList<>();
        for (AchievementManager.Achievement achievement : reversed) {
            if ("hidden".equals(achievement.getCategory())) hidden.add(achievement);
        }
        assertEquals(hidden, groups.get("hidden"));
        assertEquals("legend", flatten(groups).get(reversed.size() - 1).getId());
    }

    @Test public void equalAndUnknownRequirementsKeepStableOrderingWithinTiers() {
        AchievementManager.Achievement future = milestone("future_session", "sessions", false);
        AchievementManager.Achievement first = milestone("first_session", "sessions", false);
        AchievementManager.Achievement tie = milestone("first_block", "sessions", true);
        List<AchievementManager.Achievement> catalog = List.of(future, tie, first);
        List<AchievementManager.Achievement> ordered =
                AchievementsActivity.orderedGroups(catalog).get("sessions");
        assertEquals(List.of(tie, first, future), ordered);
        assertSame(future, catalog.get(0));
        assertTrue(ordered.get(0).isHidden());
    }

    @Test public void futureCategoriesPrecedeCompletionAndKeepFirstSeenOrder() {
        AchievementManager.Achievement legend = milestone("legend", "special", false);
        AchievementManager.Achievement future = milestone("future", "future_feature", true);
        AchievementManager.Achievement control = milestone("control", "control", false);
        List<AchievementManager.Achievement> catalog = List.of(legend, future,
                milestone("other", "another_feature", false), control);
        Map<String, List<AchievementManager.Achievement>> groups =
                AchievementsActivity.orderedGroups(catalog);
        assertEquals(List.of("control", "future_feature", "another_feature", "special"),
                new ArrayList<>(groups.keySet()));
        assertSame(future, groups.get("future_feature").get(0));
        assertTrue(groups.get("future_feature").get(0).isHidden());
        assertSame(legend, catalog.get(0));
        assertSame(legend, flatten(groups).get(catalog.size() - 1));
    }

    @Test public void futureSpecialMilestonesCannotFollowLegend() {
        AchievementManager.Achievement legend = milestone("legend", "special", false);
        AchievementManager.Achievement future = milestone("future_special", "special", true);
        List<AchievementManager.Achievement> catalog = List.of(legend, future,
                milestone("feature", "new_category", false));
        Map<String, List<AchievementManager.Achievement>> groups =
                AchievementsActivity.orderedGroups(catalog);
        assertEquals(List.of(future, legend), groups.get("special"));
        assertSame(legend, catalog.get(0));
        assertEquals("legend", flatten(groups).get(2).getId());
    }

    @Test public void absentCompletionDoesNotCreateAnEmptyGroup() {
        assertTrue(AchievementsActivity.orderedGroups(List.of()).isEmpty());
        assertFalse(AchievementsActivity.orderedGroups(
                List.of(milestone("future", "future_feature", false)))
                .containsKey("special"));
    }

    @Test public void renderedCatalogActuallyEndsWithLegend() {
        try (ActivityScenario<AchievementsActivity> scenario =
                     ActivityScenario.launch(AchievementsActivity.class)) {
            scenario.onActivity(activity -> {
                ViewGroup list = activity.findViewById(R.id.achievement_list);
                ViewGroup finalCard = (ViewGroup) list.getChildAt(list.getChildCount() - 1);
                ViewGroup row = (ViewGroup) finalCard.getChildAt(0);
                assertTrue(row.getChildAt(0) instanceof AchievementBadgeView);
                assertEquals(activity.getString(R.string.achievement_legend),
                        row.getChildAt(0).getContentDescription().toString());
            });
        }
    }

    private static AchievementManager.Achievement milestone(String id, String category,
            boolean hidden) {
        return new AchievementManager.Achievement(id, R.string.achievement_legend,
                R.string.achievement_desc_legend, "", category,
                R.drawable.achievement_badge_legend, hidden);
    }

    private static List<AchievementManager.Achievement> flatten(
            Map<String, List<AchievementManager.Achievement>> groups) {
        List<AchievementManager.Achievement> result = new ArrayList<>();
        for (List<AchievementManager.Achievement> group : groups.values()) result.addAll(group);
        return result;
    }

    private static List<String> ids(List<AchievementManager.Achievement> achievements) {
        List<String> result = new ArrayList<>();
        for (AchievementManager.Achievement achievement : achievements) result.add(achievement.getId());
        return result;
    }
}
