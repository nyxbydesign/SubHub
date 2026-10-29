package com.subhub.app.stats;

import static org.junit.Assert.*;
import android.content.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.security.*;
import com.subhub.app.settings.SettingsRepository;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class DomGoalMigrationAndroidTest {
    @Rule public final com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture();
    @Rule public final com.subhub.app.UiAutomationStateRule automation = new com.subhub.app.UiAutomationStateRule();
    private Context context;
    private final String prefix="goal-migration-"+UUID.randomUUID()+"-";
    private final Set<String> names=new HashSet<>();
    @Before public void setup() {
        Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();
        context=new ContextWrapper(base) {
            @Override public Context getApplicationContext(){return this;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode){names.add(name);return super.getSharedPreferences(prefix+name,mode);}
        };
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit().putString(DeviceRolePreferences.KEY_MODE,"DOM_ONLY").commit();
        ControllerPinManager.enterDomMode();
    }
    @After public void cleanup(){for(String name:names)((ContextWrapper)context).getBaseContext().deleteSharedPreferences(prefix+name);}
    private SharedPreferences prefs(){return context.getSharedPreferences("betablocker_achievements",0);}

    @Test public void everyExistingEarnedBadgeKeepsItsReceiptAndOldGoalAcrossReopen() {
        Set<String> ids=new LinkedHashSet<>();for(AchievementManager.Achievement a:DomAchievementCatalog.all())ids.add(a.getId());ids.add("first_block");
        SharedPreferences.Editor seed=prefs().edit().putStringSet("unlocked",ids).putStringSet("pending_notifications",Set.of("first_block","dom_time_1hr"));
        for(String id:ids)seed.putLong("unlocked_at."+id,123456L).putLong("history_earned_at."+id,123000L);
        seed.commit();
        Map<String,?> before=prefs().getAll();
        AchievementManager manager=AchievementManager.forWorkspace(context);assertTrue(manager.checkDomAchievements(facts(1,268920,100,9999)).isEmpty());
        for(String id:ids){assertEquals(before.get("unlocked_at."+id),prefs().getAll().get("unlocked_at."+id));assertEquals(before.get("history_earned_at."+id),prefs().getAll().get("history_earned_at."+id));}
        assertEquals(ids,prefs().getStringSet("unlocked",Set.of()));assertEquals(before.get("pending_notifications"),prefs().getAll().get("pending_notifications"));
        for(AchievementManager.Achievement a:manager.all()){assertTrue(manager.isUnlocked(a.getId()));assertEquals(DomAchievementCatalog.earlierTarget(a.getId()),manager.earlierEarnedGoal(a.getId()));}
        Map<String,?> migrated=prefs().getAll();
        manager=AchievementManager.forWorkspace(context);manager.checkDomAchievements(facts(1,268920,100,9999));assertEquals(migrated,prefs().getAll());
        assertEquals(268920,manager.progress(manager.all().get(4),null).getCurrent());
    }

    @Test public void newEligibilityAndHistoricalDatesUseRaisedGoalsWithoutGrandfatheringNewUnlocks() {
        AchievementManager manager=AchievementManager.forWorkspace(context);
        assertTrue(manager.checkDomAchievements(facts(1,268920,100,9999)).isEmpty());
        assertTrue(prefs().getStringSet("unlocked",Set.of()).isEmpty());
        assertFalse(manager.checkDomAchievements(facts(2,360000,250,10000)).isEmpty());
        assertTrue(manager.isUnlocked("dom_time_1hr"));assertFalse(manager.isUnlocked("dom_time_24hr"));
        assertTrue(manager.isUnlocked("dom_sessions_10"));assertFalse(manager.isUnlocked("dom_sessions_100"));
        for(AchievementManager.Achievement a:manager.all())assertEquals(0,manager.earlierEarnedGoal(a.getId()));
        assertFalse(manager.takePendingNotifications().isEmpty());
        long clock=java.time.Instant.parse("2025-12-31T12:00:00Z").toEpochMilli();
        RetainedStatistics history=new RetainedStatistics(java.time.ZoneId.of("UTC"),clock,Map.of("2025-12-30",Map.of("service_ms",268920000L)));
        assertEquals(Long.valueOf(0),history.awards(true).get("dom_time_1hr"));
        history.event("daily","more","2025-12-31","service_ms",360000000L);
        assertTrue(history.awards(true).get("dom_time_1hr")>0);
    }

    @Test public void subRoleDoesNotMigrateDomStateAndUnknownDatesStayUnknown() {
        prefs().edit().putStringSet("unlocked",Set.of("dom_time_1hr","first_block")).commit();
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit().putString(DeviceRolePreferences.KEY_MODE,"BOTH").commit();ControllerPinManager.enterSubMode();
        AchievementManager sub=AchievementManager.forWorkspace(context);assertEquals(0,sub.earlierEarnedGoal("dom_time_1hr"));assertFalse(prefs().contains(DomGoalMigration.REVISION));
        ControllerPinManager.enterDomMode();AchievementManager dom=AchievementManager.forWorkspace(context);dom.checkDomAchievements(facts(1,0,0,0));
        assertTrue(dom.isUnlocked("dom_time_1hr"));assertEquals(0,dom.getUnlockedAt("dom_time_1hr"));assertFalse(prefs().contains("unlocked_at.dom_time_1hr"));
    }

    @Test public void failedPersistenceWithVisibleMemoryMarkerStillRequiresAcknowledgedRetry() {
        prefs().edit().putStringSet("unlocked",Set.of("dom_subs_1")).putLong("unlocked_at.dom_subs_1",123L).commit();
        SharedPreferences actual=prefs();java.util.concurrent.atomic.AtomicInteger writes=new java.util.concurrent.atomic.AtomicInteger();
        SharedPreferences failing=(SharedPreferences)java.lang.reflect.Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
                    if(!method.getName().equals("edit"))return method.invoke(actual,args);
                    SharedPreferences.Editor pending=actual.edit();
                    return java.lang.reflect.Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},(editorProxy,editMethod,editArgs)->{
                        if(editMethod.getName().equals("commit")) {
                            boolean committed=pending.commit();
                            // Expose the updated preference map before reporting an unacknowledged write.
                            return writes.incrementAndGet()==1?false:committed;
                        }
                        Object value=editMethod.invoke(pending,editArgs);return value instanceof SharedPreferences.Editor?editorProxy:value;
                    });
                });
        try{DomGoalMigration.apply(failing);fail("Unacknowledged migration must fail");}catch(IllegalStateException expected){ }
        assertEquals(2,actual.getInt(DomGoalMigration.REVISION,0));assertEquals(1,writes.get());
        assertEquals(123,actual.getLong("unlocked_at.dom_subs_1",0));
        DomGoalMigration.apply(failing);assertEquals(2,writes.get());
        assertEquals(1,actual.getLong(DomGoalMigration.EARLIER+"dom_subs_1",0));
        Map<String,?> migrated=actual.getAll();DomGoalMigration.apply(actual);assertEquals(migrated,actual.getAll());
    }

    @Test public void earnedBadgeRetainsItsDateWhenCurrentTotalsBecomeUnavailable() {
        prefs().edit().putStringSet("unlocked",Set.of("dom_time_1hr"))
                .putLong("unlocked_at.dom_time_1hr",123L).commit();
        AchievementManager manager=AchievementManager.forWorkspace(context);
        DomStatsSnapshot unknown=DomStatsSnapshot.aggregate(Collections.singletonList(
                new DomStatsSnapshot.Sub("fixture-unknown","generation",0,0,0,null,null)),
                System.currentTimeMillis());
        manager.checkDomAchievements(unknown);
        AchievementManager.Achievement time=manager.all().get(4);
        assertTrue(manager.progress(time,null).isUnlocked());
        assertFalse(manager.progress(time,null).isKnown());
        manager.checkDomAchievements(facts(1,268920,100,9999));
        assertTrue(manager.progress(time,null).isKnown());
        assertEquals(268920,manager.progress(time,null).getCurrent());
        manager.checkDomAchievements(unknown);
        assertFalse(manager.progress(time,null).isKnown());
        assertEquals(123,prefs().getLong("unlocked_at.dom_time_1hr",0));
        assertEquals(3600,manager.earlierEarnedGoal("dom_time_1hr"));
    }

    private static DomStatsSnapshot facts(int count,long totalSeconds,long totalSessions,long paid) {
        List<DomStatsSnapshot.Sub> values=new ArrayList<>();long now=System.currentTimeMillis();
        for(int i=0;i<count;i++)values.add(new DomStatsSnapshot.Sub("fixture-"+i,"generation",i,now,now,
                new DomStatsSnapshot.Totals(totalSeconds/count,totalSessions/count+(i==0?totalSessions%count:0),paid/count,0,0,0),new DomStatsSnapshot.Observation(true,true,true)));
        return DomStatsSnapshot.aggregate(values,now);
    }
}
