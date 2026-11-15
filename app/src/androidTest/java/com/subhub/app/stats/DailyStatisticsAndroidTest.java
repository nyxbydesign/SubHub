package com.subhub.app.stats;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DailyStatisticsAndroidTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    @Test public void migrationDoesNotInventDaysAndCheckpointsReplayAfterReopen() {
        String name="daily-test-"+UUID.randomUUID()+".db"; long now=System.currentTimeMillis(); String day=LocalDate.now().toString(), zone=ZoneId.systemDefault().getId();
        try {
            try(DailyStatsStore store=new DailyStatsStore(context,name)) {
                store.checkpoint("fixture",Map.of("censors",900L,"assessed_EUR",200L,"assessed_USD",300L,"paid_EUR",50L),now,zone);
                assertTrue(store.days(day,day).isEmpty());
                store.checkpoint("fixture",Map.of("censors",903L,"assessed_EUR",250L,"assessed_USD",340L,"paid_EUR",80L),now,zone);
            }
            try(DailyStatsStore store=new DailyStatsStore(context,name)) {
                store.checkpoint("fixture",Map.of("censors",903L,"assessed_EUR",250L,"assessed_USD",340L,"paid_EUR",80L),now,zone);
                Map<String,Long> values=store.days(day,day).get(day);
                assertEquals(Long.valueOf(3),values.get("censors")); assertEquals(Long.valueOf(50),values.get("assessed_EUR"));
                assertEquals(Long.valueOf(40),values.get("assessed_USD")); assertEquals(Long.valueOf(30),values.get("paid_EUR"));
            }
        } finally { context.deleteDatabase(name); }
    }
    @Test public void concurrentReceiptsAreAtomicAndIdempotent() throws Exception {
        String name="daily-test-"+UUID.randomUUID()+".db"; ExecutorService pool=Executors.newFixedThreadPool(4);
        try(DailyStatsStore store=new DailyStatsStore(context,name)) {
            List<Future<?>> tasks=new ArrayList<>();
            for(int i=0;i<40;i++) { final int id=i%20; tasks.add(pool.submit(()->store.exported("job:"+id,id%2==0?"photos":"videos",System.currentTimeMillis(),ZoneId.systemDefault().getId()))); }
            for(Future<?> task:tasks) task.get(10,TimeUnit.SECONDS);
            String day=LocalDate.now().toString(); Map<String,Long> values=store.days(day,day).get(day);
            assertEquals(Long.valueOf(10),values.get("photos")); assertEquals(Long.valueOf(10),values.get("videos"));
        } finally { pool.shutdownNow(); context.deleteDatabase(name); }
    }
    @Test public void legacyClockRowDoesNotReplayIntoRetainedDailyTotals() {
        String name = "daily-clock-" + UUID.randomUUID() + ".db";
        long start = Instant.parse("2026-10-08T00:00:00Z").toEpochMilli();
        try {
            try (DailyStatsStore old = new DailyStatsStore(context, name)) {
                old.getWritableDatabase().execSQL("CREATE TABLE clock(id INTEGER PRIMARY KEY, "
                        + "process TEXT, mono INTEGER, wall INTEGER, zone TEXT, session INTEGER)");
                old.getWritableDatabase().execSQL("INSERT INTO clock VALUES(1,'old',1,?,'UTC',1)",
                        new Object[]{start});
                old.getWritableDatabase().execSQL("INSERT INTO daily VALUES('2026-10-08','service_ms',40000)");
            }
            try (DailyStatsStore current = new DailyStatsStore(context, name)) {
                current.syncStatsSnapshot(Map.of(), start + 60_000);
                assertEquals(Map.of("2026-10-08", Map.of("service_ms", 40000L)),
                        current.days("2026-10-08", "2026-10-09"));
                Map<String, Object> active = Map.of(
                        "active_session_identity", UUID.randomUUID().toString(),
                        "active_session_start_ms", start + 60_000,
                        StatsRepository.ACTIVE_SESSION_ZONE, "UTC");
                current.syncStatsSnapshot(active, start + 70_000);
                current.syncStatsSnapshot(active, start + 70_000);
                assertEquals(Map.of("2026-10-08", Map.of("service_ms", 50000L)),
                        current.days("2026-10-08", "2026-10-09"));
            }
        } finally {
            context.deleteDatabase(name);
        }
    }
}
