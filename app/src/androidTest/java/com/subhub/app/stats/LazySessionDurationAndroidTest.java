package com.subhub.app.stats;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LazySessionDurationAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final long start = Instant.parse("2026-11-08T23:00:00Z").toEpochMilli();
    private Map<String, Object> active(String id) {
        Map<String, Object> raw = new HashMap<>();
        raw.put("active_session_identity", id); raw.put("active_session_start_ms", start);
        raw.put(StatsRepository.ACTIVE_SESSION_ZONE, "UTC"); raw.put(DailyStatsStore.ZONE, "UTC");
        raw.put("session_history", ""); return raw;
    }
    private Map<String, Object> ended(String id, long seconds) {
        return new HashMap<>(Map.of("session_history", "3," + start + "," + seconds + ",0,0,0,0,0,0,0,0," + id + ",UTC",
                DailyStatsStore.ZONE, "UTC"));
    }
    private long total(DailyStatsStore store) {
        long result = 0;
        for (Map<String, Long> day : store.days("0000-01-01", "9999-12-31").values()) result += day.getOrDefault("service_ms", 0L);
        return result;
    }
    @Test public void backgroundSyncOffUnchangedSessionSurvivesReopenAndStopsExactlyOnce() {
        String name = "lazy-duration-" + UUID.randomUUID() + ".db", id = UUID.randomUUID().toString();
        Map<String, Object> raw = active(id);
        // No service, screen, network, background-sync preference or wake worker participates.
        try {
            try (DailyStatsStore store = new DailyStatsStore(context, name)) {
                store.syncStatsSnapshot(raw, start + 3600_000); assertEquals(3600_000, total(store));
                store.syncStatsSnapshot(raw, start + 3600_000); assertEquals(3600_000, total(store));
            }
            try (DailyStatsStore store = new DailyStatsStore(context, name)) {
                store.syncStatsSnapshot(raw, start + 25 * 3600_000L); assertEquals(25 * 3600_000L, total(store));
                assertEquals(Long.valueOf(24 * 3600_000L), store.days("2026-11-09", "2026-11-09").get("2026-11-09").get("service_ms"));
                // A late observed stop can correct an earlier projection, including excess days.
                store.syncStatsSnapshot(ended(id, 7200), start + 26 * 3600_000L); assertEquals(7200_000, total(store));
                store.syncStatsSnapshot(raw, start + 27 * 3600_000L); assertEquals(7200_000, total(store));
                store.syncStatsSnapshot(ended(id, 7200), start + 28 * 3600_000L); assertEquals(7200_000, total(store));
            }
        } finally { context.deleteDatabase(name); }
    }
    @Test public void existingScreenTimeMigratesWithoutOverlapAndNewSessionsAddNormally() {
        String name = "lazy-migration-" + UUID.randomUUID() + ".db", first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
        try (DailyStatsStore store = new DailyStatsStore(context, name)) {
            store.getWritableDatabase().execSQL("INSERT INTO daily VALUES('2026-11-08','service_ms',1000)");
            store.syncStatsSnapshot(active(first), start + 10_000); assertEquals(10_000, total(store));
            store.syncStatsSnapshot(ended(first, 10), start + 10_000);
            Map<String, Object> next = active(second); next.put("active_session_start_ms", start + 10_000);
            store.syncStatsSnapshot(next, start + 20_000); assertEquals(20_000, total(store));
            store.syncStatsSnapshot(next, start + 15_000); assertEquals(20_000, total(store));
        } finally { context.deleteDatabase(name); }
    }
    @Test public void legacyHistoryAndUnknownOlderDailyEvidenceRemainALowerBound() {
        String name = "lazy-history-" + UUID.randomUUID() + ".db", id = UUID.randomUUID().toString();
        try (DailyStatsStore store = new DailyStatsStore(context, name)) {
            store.getWritableDatabase().execSQL("UPDATE metadata SET value=? WHERE name='collection_started_ms'", new Object[]{Long.toString(start - 20_000)});
            store.getWritableDatabase().execSQL("INSERT INTO daily VALUES('2026-11-08','service_ms',15000)");
            Map<String, Object> raw = active(id);
            raw.put("session_history", (start - 10_000) + ",10,0");
            store.syncStatsSnapshot(raw, start + 10_000); assertEquals(20_000, total(store));
            store.syncStatsSnapshot(raw, start + 20_000); assertEquals(30_000, total(store));
        } finally { context.deleteDatabase(name); }
    }
    @Test public void sameTimestampDifferentIdentitiesAndZoneChangesDoNotReplaceHistory() {
        String name = "lazy-identity-" + UUID.randomUUID() + ".db", first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
        try (DailyStatsStore store = new DailyStatsStore(context, name)) {
            store.syncStatsSnapshot(active(first), start + 3600_000);
            Map<String, Object> travelled = active(first); travelled.put(StatsRepository.ACTIVE_SESSION_ZONE, "America/Los_Angeles");
            store.syncStatsSnapshot(travelled, start + 7200_000);
            assertEquals(Long.valueOf(3600_000), store.days("2026-11-09", "2026-11-09").get("2026-11-09").get("service_ms"));
            store.syncStatsSnapshot(ended(first, 7200), start + 7200_000);
            store.syncStatsSnapshot(ended(second, 60), start + 7200_000);
            assertEquals(7260_000, total(store));
        } finally { context.deleteDatabase(name); }
    }
    @Test public void failedLedgerWriteRollsBackMigrationAndCanRetry() {
        String name = "lazy-rollback-" + UUID.randomUUID() + ".db", id = UUID.randomUUID().toString();
        try (DailyStatsStore store = new DailyStatsStore(context, name)) {
            store.getWritableDatabase().execSQL("CREATE TRIGGER fail_duration BEFORE INSERT ON checkpoint WHEN NEW.name LIKE 'session-duration:%' BEGIN SELECT RAISE(ABORT,'fixture'); END");
            try { store.syncStatsSnapshot(active(id), start + 10_000); fail("Expected failure"); }
            catch (android.database.sqlite.SQLiteException expected) { }
            assertEquals(0, total(store));
            store.getWritableDatabase().execSQL("DROP TRIGGER fail_duration");
            store.syncStatsSnapshot(active(id), start + 10_000); assertEquals(10_000, total(store));
        } finally { context.deleteDatabase(name); }
    }
}
