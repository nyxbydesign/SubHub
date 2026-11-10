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
    @Test public void clockSplitsMidnightAndIgnoresDowntimeAndStaleHeartbeats() {
        String name="daily-test-"+UUID.randomUUID()+".db"; long start=Instant.parse("2026-10-08T23:59:50Z").toEpochMilli();
        try(DailyStatsStore store=new DailyStatsStore(context,name)) {
            store.heartbeat("first",1,start,1000,"UTC");
            store.heartbeat("first",1,start+30000,31000,"UTC");
            store.heartbeat("first",1,start+1000,2000,"UTC");
            store.heartbeat("first",0,start+40000,41000,"UTC");
            store.heartbeat("new-process",1,start+3600000,3601000,"UTC");
            store.heartbeat("new-process",0,start+3610000,3611000,"UTC");
            Map<String,Map<String,Long>> days=store.days("2026-10-08","2026-10-09");
            assertEquals(Long.valueOf(10000),days.get("2026-10-08").get("service_ms"));
            assertEquals(Long.valueOf(40000),days.get("2026-10-09").get("service_ms"));
        } finally { context.deleteDatabase(name); }
    }
    @Test public void timezoneChangeUsesRecordedZoneWithoutMovingPreviousDays() {
        String name="daily-test-"+UUID.randomUUID()+".db"; long start=Instant.parse("2026-10-08T22:59:50Z").toEpochMilli();
        try(DailyStatsStore store=new DailyStatsStore(context,name)) {
            store.heartbeat("p",1,start,0,"UTC");
            store.heartbeat("p",1,start+10000,10000,"Asia/Tokyo");
            store.heartbeat("p",0,start+20000,20000,"Asia/Tokyo");
            assertEquals(Long.valueOf(10000),store.days("2026-10-08","2026-10-09").get("2026-10-08").get("service_ms"));
            assertEquals(Long.valueOf(10000),store.days("2026-10-08","2026-10-09").get("2026-10-09").get("service_ms"));
        } finally { context.deleteDatabase(name); }
    }
}
