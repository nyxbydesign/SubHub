package com.subhub.app.stats;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Instant;
import java.util.*;

@RunWith(AndroidJUnit4.class)
public class LegacyStatisticsBackfillAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    private static long wall(String time) {
        return Instant.parse(time).toEpochMilli();
    }

    private static void cutoff(DailyStatsStore store, String time) {
        store.getWritableDatabase()
                .execSQL(
                        "UPDATE metadata SET value=? WHERE name='collection_started_ms'",
                        new Object[] {Long.toString(wall(time))});
    }

    @Test
    public void datedLegacyAndDetailedSessionsBackfillAcrossMidnightAndReplayAfterReopen() {
        String name = "legacy-daily-" + UUID.randomUUID() + ".db";
        String history =
                wall("2026-10-08T23:59:50Z")
                        + ",20,48;2,"
                        + wall("2026-10-07T12:00:00Z")
                        + ",60,10,12000,2,1,250,3,4,2;malformed;2,1,NaN,0,0,0,0,0,0,0,0";
        try {
            try (DailyStatsStore store = new DailyStatsStore(context, name)) {
                cutoff(store, "2026-10-10T00:00:00Z");
                store.backfillSessions(history, "UTC");
                store.backfillSessions(history, "UTC");
                Map<String, Map<String, Long>> days = store.days("2026-10-07", "2026-10-09");
                assertEquals(Long.valueOf(10000), days.get("2026-10-08").get("service_ms"));
                assertEquals(Long.valueOf(10000), days.get("2026-10-09").get("service_ms"));
                assertEquals(Long.valueOf(48), days.get("2026-10-08").get("censors"));
                Map<String, Long> detailed = days.get("2026-10-07");
                assertEquals(Long.valueOf(60000), detailed.get("service_ms"));
                assertEquals(Long.valueOf(12000), detailed.get("limits_ms"));
                assertEquals(Long.valueOf(2), detailed.get("limit_stops"));
                assertEquals(Long.valueOf(1), detailed.get("tributes"));
                assertEquals(Long.valueOf(250), detailed.get("assessed_EUR"));
                assertEquals(Long.valueOf(3), detailed.get("whispers"));
                assertEquals(Long.valueOf(4), detailed.get("popups"));
                assertEquals("2026-10-07", store.since());
            }
            try (DailyStatsStore reopened = new DailyStatsStore(context, name)) {
                reopened.backfillSessions(history, "Asia/Tokyo");
                assertEquals(
                        Long.valueOf(48),
                        reopened.days("2026-10-08", "2026-10-08").get("2026-10-08").get("censors"));
                assertEquals(
                        Long.valueOf(10000),
                        reopened.days("2026-10-09", "2026-10-09")
                                .get("2026-10-09")
                                .get("service_ms"));
            }
        } finally {
            context.deleteDatabase(name);
        }
    }

    @Test
    public void existingDailyDatabaseFreezesItsBoundaryAndDoesNotAddLiveSessionsAgain() {
        String name = "legacy-daily-" + UUID.randomUUID() + ".db";
        try (DailyStatsStore store = new DailyStatsStore(context, name)) {
            store.getWritableDatabase()
                    .execSQL("DELETE FROM metadata WHERE name='collection_started_ms'");
            store.getWritableDatabase()
                    .execSQL("UPDATE metadata SET value='2026-10-09' WHERE name='since'");
            store.checkpoint("stats", Map.of("censors", 100L), wall("2026-10-09T12:00:00Z"), "UTC");
            store.checkpoint("stats", Map.of("censors", 105L), wall("2026-10-09T12:01:00Z"), "UTC");
            store.heartbeat("live", 1, wall("2026-10-09T12:00:00Z"), 0, "UTC");
            store.heartbeat("live", 0, wall("2026-10-09T12:01:00Z"), 60000, "UTC");
            String old = wall("2026-10-08T12:00:00Z") + ",60,48";
            store.backfillSessions(old + ";" + wall("2026-10-09T12:00:00Z") + ",60,5", "UTC");
            assertEquals(
                    Long.valueOf(5),
                    store.days("2026-10-09", "2026-10-09").get("2026-10-09").get("censors"));
            assertEquals(
                    Long.valueOf(60000),
                    store.days("2026-10-09", "2026-10-09").get("2026-10-09").get("service_ms"));
            store.backfillSessions(old + ";" + wall("2026-10-08T13:00:00Z") + ",30,2", "UTC");
            assertEquals(
                    Long.valueOf(50),
                    store.days("2026-10-08", "2026-10-08").get("2026-10-08").get("censors"));
        } finally {
            context.deleteDatabase(name);
        }
    }

    @Test
    public void spanningTheCollectionBoundaryOnlyImportsUnobservedTimeAndRejectsOverflow() {
        String name = "legacy-daily-" + UUID.randomUUID() + ".db";
        try (DailyStatsStore store = new DailyStatsStore(context, name)) {
            cutoff(store, "2026-10-09T00:00:00Z");
            store.backfillSessions(
                    wall("2026-10-08T23:59:50Z") + ",20,48;1," + Long.MAX_VALUE + ",999", "UTC");
            Map<String, Map<String, Long>> days = store.days("2026-10-08", "2026-10-09");
            assertEquals(Long.valueOf(10000), days.get("2026-10-08").get("service_ms"));
            assertNull(days.get("2026-10-08").get("censors"));
            assertNull(days.get("2026-10-09"));
        } finally {
            context.deleteDatabase(name);
        }
    }

    @Test
    public void failedImportRollsBackItsReceiptAndCanBeRetried() {
        String name = "legacy-daily-" + UUID.randomUUID() + ".db";
        try (DailyStatsStore store = new DailyStatsStore(context, name)) {
            cutoff(store, "2026-10-09T00:00:00Z");
            String history = wall("2026-10-08T12:00:00Z") + ",60,48";
            store.getWritableDatabase()
                    .execSQL(
                            "CREATE TRIGGER reject_legacy BEFORE INSERT ON daily WHEN"
                                + " NEW.metric='censors' BEGIN SELECT RAISE(ABORT,'fixture'); END");
            try {
                store.backfillSessions(history, "UTC");
                fail("Expected transactional failure");
            } catch (android.database.sqlite.SQLiteException expected) {
            }
            assertTrue(store.days("2026-10-08", "2026-10-08").isEmpty());
            store.getWritableDatabase().execSQL("DROP TRIGGER reject_legacy");
            store.backfillSessions(history, "UTC");
            assertEquals(
                    Long.valueOf(48),
                    store.days("2026-10-08", "2026-10-08").get("2026-10-08").get("censors"));
        } finally {
            context.deleteDatabase(name);
        }
    }
}
