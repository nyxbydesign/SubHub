package com.subhub.app.appmode;

import static org.junit.Assert.*;
import android.content.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.stats.DailyAppUsageStore;
import org.junit.*;
import org.junit.runner.RunWith;
import java.time.*;
import java.util.*;

@RunWith(AndroidJUnit4.class)
public class DailyAppUsageAndroidTest {
    private Context context;private DailyAppUsageStore store;private String prefix;
    private final Set<String> names=new HashSet<>();private TimeZone zone;
    @Before public void setup() {
        prefix="daily-app-test-"+UUID.randomUUID();Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        context=new ContextWrapper(target) {
            @Override public Context getApplicationContext(){return this;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode){names.add(name);return super.getSharedPreferences(prefix+name,mode);}
        };
        store=new DailyAppUsageStore(context,prefix+".db");zone=TimeZone.getDefault();TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }
    @After public void cleanup() {
        store.close();context.deleteDatabase(prefix+".db");for(String name:names)context.getSharedPreferences(name,0).edit().clear().commit();TimeZone.setDefault(zone);
    }
    @Test public void cumulativeCheckpointsIgnoreDuplicatesAndPreserveEpochDate() {
        String epoch=UUID.randomUUID().toString();DailyAppUsageStore.Entry first=new DailyAppUsageStore.Entry(epoch,"2026-10-25","Europe/Berlin","com.example.limited",10);
        store.checkpoint(List.of(first,first));store.checkpoint(List.of(new DailyAppUsageStore.Entry(epoch,"2026-10-25","Europe/Berlin","com.example.limited",7)));
        assertEquals(1,store.entries().size());assertEquals(10,store.entries().get(0).millis);
        assertThrows(IllegalArgumentException.class,()->store.checkpoint(List.of(new DailyAppUsageStore.Entry(epoch,"2026-10-26","UTC","com.example.limited",20))));
        assertEquals("2026-10-25",store.entries().get(0).day);assertEquals("Europe/Berlin",store.entries().get(0).zone);
    }
    @Test public void rolloverCommitsOldDayBeforeSourceResetAndOnlyRecordsLimitedApps() throws Exception {
        AppTimerManager timers=new AppTimerManager(context,store);timers.saveSettings(true,30,false,120);
        long before=Instant.parse("2026-10-25T23:59:50Z").toEpochMilli();
        timers.recordUsage("com.example.selected",8000,Set.of("com.example.selected"),before);
        timers.recordUsage("com.example.unselected",9000,Set.of("com.example.selected"),before);
        timers.snapshot("com.example.selected",before+20000);
        List<DailyAppUsageStore.Entry> first=store.entries();assertEquals(2,first.size());
        for(DailyAppUsageStore.Entry entry:first){assertEquals("2026-10-25",entry.day);assertEquals("UTC",entry.zone);assertEquals(8000,entry.millis);assertNotEquals("com.example.unselected",entry.packageName);}
        assertEquals(0,timers.snapshot("com.example.selected",before+20000).appUsedMillis);
        timers.recordUsage("com.example.selected",3000,Set.of("com.example.selected"),before+20000);store.flush();
        assertEquals(4,store.entries().size());assertNotEquals(store.entries().get(0).epoch,store.entries().get(2).epoch);
    }
    @Test public void processRestartReconcilesTodayAndLegacyZoneRemainsUnknown() throws Exception {
        SharedPreferences source=context.getSharedPreferences("subhub_app_timer_usage",0);
        source.edit().putString("day","2026-10-25").putLong("package_used_ms:com.example.selected",12000).putLong("total_used_ms",12000).commit();
        AppTimerManager timers=new AppTimerManager(context,store);timers.checkpointHistory(Instant.parse("2026-10-25T12:00:00Z").toEpochMilli());
        assertEquals(2,store.entries().size());for(DailyAppUsageStore.Entry entry:store.entries()){assertEquals("",entry.zone);assertEquals(12000,entry.millis);}
        new AppTimerManager(context,store).checkpointHistory(Instant.parse("2026-10-25T12:00:01Z").toEpochMilli());assertEquals(2,store.entries().size());
    }
}
