package com.subhub.app.stats;

import static org.junit.Assert.*;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.lang.reflect.Field;
import java.util.*;

/** Read-only witness of the real process history stores, never a substituted singleton. */
public final class DummyPreviewHistoryWitness {
    private final DailyStatsStore daily;
    private final DailyAppUsageStore apps;
    private final Object counterWriter, appWriter;
    private final java.util.concurrent.ThreadPoolExecutor counterPool;
    private Map<String, List<String>> dailyRows, appRows;
    private Map<String, Map<String, Long>> totals;
    private Object importedHistory;
    private boolean strictWriter;
    private long completedCounters;

    public DummyPreviewHistoryWitness(Context context) throws Exception {
        // Initialize the real singleton before any preview can accidentally initialize it with draft data.
        daily = DailyStatsStore.get(context);
        apps = DailyAppUsageStore.get(context);
        daily.getReadableDatabase(); apps.getReadableDatabase();
        counterWriter = field(DailyStatsStore.class, "counterWriter").get(null);
        counterPool = (java.util.concurrent.ThreadPoolExecutor) field(DailyStatsStore.class, "counterPool").get(null);
        appWriter = field(DailyAppUsageStore.class, "worker").get(apps);
    }
    public void capture() throws Exception { capture(false); }
    public void capture(boolean strictWriter) throws Exception {
        this.strictWriter = strictWriter;
        drain();
        if (strictWriter) {
            assertEquals(1, counterPool.getCorePoolSize());
            assertEquals(1, counterPool.getMaximumPoolSize());
            assertEquals(0L, counterPool.getKeepAliveTime(java.util.concurrent.TimeUnit.MILLISECONDS));
            assertFalse(counterPool.allowsCoreThreadTimeOut());
            assertTrue(counterPool.getQueue() instanceof java.util.concurrent.LinkedBlockingQueue);
            assertTrue(counterPool.getRejectedExecutionHandler() instanceof java.util.concurrent.ThreadPoolExecutor.AbortPolicy);
            long beforeProbe = completedCounters();
            assertEquals(Integer.MAX_VALUE, counterPool.getQueue().remainingCapacity());
            // A harmless task through the real facade proves this is its actual backing pool,
            // and that the count witness detects submissions even when no data changes.
            assertEquals("subhub-daily-counters", ((java.util.concurrent.ExecutorService) counterWriter)
                    .submit(() -> Thread.currentThread().getName()).get(10, java.util.concurrent.TimeUnit.SECONDS));
            completedCounters = completedCounters();
            assertEquals("The strict witness observes tasks submitted through the real writer", beforeProbe + 1, completedCounters);
        }
        dailyRows = rows(daily.getReadableDatabase()); appRows = rows(apps.getReadableDatabase());
        totals = cache(); importedHistory = field(DailyStatsStore.class, "importedHistory").get(daily);
    }
    public void assertUnchanged() throws Exception {
        if (dailyRows == null) return;
        drain();
        if (strictWriter) {
            long current = completedCounters();
            assertEquals("Preview cannot queue daily counter writes (excluding this witness's two drain barriers)", completedCounters + 2, current);
            completedCounters = current;
        }
        assertSame("Preview cannot replace the live daily singleton", daily, field(DailyStatsStore.class, "instance").get(null));
        assertSame("Preview cannot replace the live app-history singleton", apps, field(DailyAppUsageStore.class, "instance").get(null));
        assertSame(counterWriter, field(DailyStatsStore.class, "counterWriter").get(null));
        assertSame(counterPool, field(DailyStatsStore.class, "counterPool").get(null));
        assertSame(appWriter, field(DailyAppUsageStore.class, "worker").get(apps));
        assertTrue("Real daily_statistics.db rows unchanged", dailyRows.equals(rows(daily.getReadableDatabase())));
        assertTrue("Real daily_app_usage.db rows unchanged", appRows.equals(rows(apps.getReadableDatabase())));
        assertTrue("Preview must not alter the daily counter cache", totals.equals(cache()));
        assertTrue("Preview must not alter the imported-history cache", Objects.equals(importedHistory,
                field(DailyStatsStore.class, "importedHistory").get(daily)));
        synchronized (apps) {
            assertTrue("App history has no pending preview checkpoints", ((Map<?, ?>) field(DailyAppUsageStore.class, "pending").get(apps)).isEmpty());
        }
    }
    private long completedCounters() throws Exception {
        assertSame(counterWriter, field(DailyStatsStore.class, "counterWriter").get(null));
        assertSame(counterPool, field(DailyStatsStore.class, "counterPool").get(null));
        java.util.concurrent.ThreadPoolExecutor pool = counterPool;
        long deadline = android.os.SystemClock.uptimeMillis() + 5000;
        while (pool.getActiveCount() != 0 && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(10);
        assertEquals(0, pool.getActiveCount()); assertTrue(pool.getQueue().isEmpty());
        return pool.getCompletedTaskCount();
    }
    private void drain() throws Exception { DailyStatsStore.awaitWrites(); apps.flush(); DailyStatsStore.awaitWrites(); }
    @SuppressWarnings("unchecked") private Map<String, Map<String, Long>> cache() throws Exception {
        synchronized (daily) {
            Map<String, Map<String, Long>> copy = new TreeMap<>();
            Map<String, Map<String, Long>> current = (Map<String, Map<String, Long>>) field(DailyStatsStore.class, "cachedTotals").get(daily);
            for (Map.Entry<String, Map<String, Long>> entry : current.entrySet()) copy.put(entry.getKey(), new TreeMap<>(entry.getValue()));
            return copy;
        }
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Map<String, List<String>> rows(SQLiteDatabase db) {
        Map<String, List<String>> result = new TreeMap<>();
        try (Cursor tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", null)) {
            while (tables.moveToNext()) {
                String table = tables.getString(0); assertTrue(table.matches("[A-Za-z0-9_]+"));
                List<String> values = new ArrayList<>();
                try (Cursor row = db.rawQuery("SELECT * FROM \"" + table + "\"", null)) {
                    while (row.moveToNext()) {
                        StringBuilder value = new StringBuilder();
                        for (int i = 0; i < row.getColumnCount(); i++) {
                            String cell = row.isNull(i) ? "NULL" : row.getType(i) == Cursor.FIELD_TYPE_BLOB
                                    ? android.util.Base64.encodeToString(row.getBlob(i), android.util.Base64.NO_WRAP) : row.getString(i);
                            value.append(row.getType(i)).append(':').append(cell.length()).append(':').append(cell).append(';');
                        }
                        values.add(value.toString());
                    }
                }
                Collections.sort(values); result.put(table, values);
            }
        }
        return result;
    }
}
