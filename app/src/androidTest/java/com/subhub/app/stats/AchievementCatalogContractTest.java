package com.subhub.app.stats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.subhub.app.R;
import com.subhub.app.penance.PenanceManager;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

/** Contract coverage for the expanded illustrated achievement catalog. */
@RunWith(AndroidJUnit4.class)
public final class AchievementCatalogContractTest {
    @Test
    public void catalogKeepsLegacyIdsAndAddsFeatureMilestones() {
        Context context = ApplicationProvider.getApplicationContext();
        AchievementManager manager = new AchievementManager(context);

        assertEquals(58, manager.getTotalCount());
        Set<String> ids = new HashSet<>();
        Set<Integer> badgeResources = new HashSet<>();
        Set<String> badgeDigests = new HashSet<>();
        for (AchievementManager.Achievement achievement : manager.all()) {
            assertTrueNonEmpty(achievement.getId());
            assertTrueNonEmpty(achievement.getCategory());
            assertNotEquals(0, achievement.getBadgeArtRes());
            if (!ids.add(achievement.getId())) {
                throw new AssertionError("Duplicate achievement " + achievement.getId());
            }
            if (!badgeResources.add(achievement.getBadgeArtRes())) {
                throw new AssertionError("Reused badge artwork for " + achievement.getId());
            }
            assertEquals("achievement_badge_" + achievement.getId(),
                    context.getResources().getResourceEntryName(achievement.getBadgeArtRes()));
            if (!badgeDigests.add(sha256(context, achievement.getBadgeArtRes()))) {
                throw new AssertionError("Duplicate badge pixels for " + achievement.getId());
            }
        }

        assertEquals(58, badgeResources.size());
        assertEquals(58, badgeDigests.size());
        assertFalse(ids.contains("profile_creator"));
        assertFalse(ids.contains("profile_organizer"));
        assertEquals(R.drawable.achievement_badge_app_mode_guardian,
                find(manager, "app_mode_guardian").getBadgeArtRes());
        assertEquals(R.drawable.achievement_badge_limits_setter,
                find(manager, "limits_setter").getBadgeArtRes());
        assertEquals(R.drawable.achievement_badge_pact_sealed,
                find(manager, "pact_sealed").getBadgeArtRes());
        assertEquals(R.drawable.achievement_badge_hardcore_guardian,
                find(manager, "hardcore_guardian").getBadgeArtRes());
        assertEquals(R.drawable.achievement_badge_paypal_vault,
                find(manager, "paypal_vault").getBadgeArtRes());
        assertEquals(R.drawable.achievement_badge_wallet_paid_1000,
                find(manager, "wallet_paid_1000").getBadgeArtRes());
        assertFalse(find(manager, "app_mode_guardian").getCategory().isEmpty());
        assertFalse(find(manager, "paid_pause").getCategory().isEmpty());
    }

    @Test
    public void protectedTimeAndConfirmedWalletPaymentTargetsUseTheExpandedRequirements() {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(PenanceManager.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit();
        AchievementManager manager = new AchievementManager(context);
        StatsSnapshot stats = new StatsRepository(context).load();

        assertEquals(60L * 60L, manager.progress(find(manager, "time_1hr"), stats).getTarget());
        assertEquals(24L * 60L * 60L,
                manager.progress(find(manager, "time_10hr"), stats).getTarget());
        assertEquals(7L * 24L * 60L * 60L,
                manager.progress(find(manager, "time_50hr"), stats).getTarget());
        assertEquals(30L * 24L * 60L * 60L,
                manager.progress(find(manager, "time_200hr"), stats).getTarget());
        assertEquals(1_000L,
                manager.progress(find(manager, "wallet_paid_10"), stats).getTarget());
        assertEquals(25_000L,
                manager.progress(find(manager, "wallet_paid_100"), stats).getTarget());
        assertEquals(100_000L,
                manager.progress(find(manager, "wallet_paid_500"), stats).getTarget());
        assertEquals(1_000_000L,
                manager.progress(find(manager, "wallet_paid_1000"), stats).getTarget());
    }

    @Test
    public void catalogStartsWithServiceProgressAndKeepsWhispersTiered() {
        Context context = ApplicationProvider.getApplicationContext();
        AchievementManager manager = new AchievementManager(context);

        assertEquals("first_session", manager.all().get(0).getId());
        assertBefore(manager, "subliminal_1", "subliminal_100");
        assertBefore(manager, "subliminal_100", "subliminal_1000");
        assertBefore(manager, "subliminal_1000", "subliminal_10000");
        assertBefore(manager, "wallet_paid_10", "wallet_paid_100");
        assertBefore(manager, "wallet_paid_100", "wallet_paid_500");
        assertBefore(manager, "wallet_paid_500", "wallet_paid_1000");
    }

    private static AchievementManager.Achievement find(AchievementManager manager, String id) {
        for (AchievementManager.Achievement achievement : manager.all()) {
            if (id.equals(achievement.getId())) return achievement;
        }
        throw new AssertionError("Missing achievement " + id);
    }

    private static void assertBefore(AchievementManager manager, String first, String second) {
        assertTrue(manager.all().indexOf(find(manager, first))
                < manager.all().indexOf(find(manager, second)));
    }

    private static void assertTrueNonEmpty(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new AssertionError("Expected non-empty achievement metadata");
        }
    }

    private static String sha256(Context context, int resourceId) {
        try (InputStream input = context.getResources().openRawResource(resourceId)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (Exception error) {
            throw new AssertionError("Could not hash achievement badge resource", error);
        }
    }
}
