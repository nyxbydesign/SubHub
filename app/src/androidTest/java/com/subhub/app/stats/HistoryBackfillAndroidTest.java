package com.subhub.app.stats;

import static org.junit.Assert.*;
import android.content.*;
import android.database.Cursor;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.remote.*;
import com.subhub.app.security.*;
import com.subhub.app.settings.SettingsRepository;
import java.io.File;
import java.lang.reflect.Field;
import java.time.*;
import java.util.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class HistoryBackfillAndroidTest {
    @Rule public final com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture();
    @Rule public final com.subhub.app.UiAutomationStateRule automation = new com.subhub.app.UiAutomationStateRule();
    private Scoped context;
    private DailyStatsStore daily, previous;
    private Field singleton;
    private static final String DAY = "2025-12-31";
    private static final long WALL = Instant.parse("2025-12-31T12:00:00Z").toEpochMilli();

    @Before public void setup() throws Exception {
        assertIdle();
        context = new Scoped(InstrumentationRegistry.getInstrumentation().getTargetContext());
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                .putString(DeviceRolePreferences.KEY_MODE, "BOTH").commit();
        ControllerPinManager.enterDomMode();
        DailyStatsStore.awaitWrites();
        singleton = DailyStatsStore.class.getDeclaredField("instance"); singleton.setAccessible(true);
        previous = (DailyStatsStore) singleton.get(null);
        daily = new DailyStatsStore(context, "daily_statistics.db"); singleton.set(null, daily);
        daily.getWritableDatabase();
    }
    @After public void teardown() throws Exception {
        DailyStatsStore.awaitWrites(); singleton.set(null, previous); daily.close();
        context.clean(); assertIdle();
    }
    private SharedPreferences prefs(String name) { return context.getSharedPreferences(name, 0); }
    private void session() {
        String old = WALL + ",3600,100";
        prefs(StatsRepository.PREFS_NAME).edit().putString("session_history", old + ";" + old
                + ";2," + WALL + ",3600,100,10000,2,1,250,3,4,0").commit();
    }

    @Test public void localOnlyBothCountsOnceAndNeverImportsSourceRows() throws Exception {
        session();
        Map<String, ?> before = prefs(StatsRepository.PREFS_NAME).getAll();
        Map<String, Map<String, Long>> dailyBefore = daily.days("0000-01-01", "9999-12-31");
        DomStatsSnapshot facts = new DomStatsRepository(context).load();
        assertEquals(1, facts.subs); assertEquals(3600, facts.sessionSeconds); assertEquals(1, facts.sessions);
        LocalRetainedStatistics local = LocalRetainedStatistics.load(context);
        assertEquals(100, local.stats.getTotalBlocks()); assertEquals(1, local.history.sessions().size());
        assertEquals(before, prefs(StatsRepository.PREFS_NAME).getAll());
        DailyStatsStore.awaitWrites(); assertEquals(dailyBefore, daily.days("0000-01-01", "9999-12-31"));
        prefs(SettingsRepository.PREFERENCES_NAME).edit().putString(DeviceRolePreferences.KEY_MODE, "DOM_ONLY").commit();
        assertEquals(0, new DomStatsRepository(context).load().subs);
        prefs(SettingsRepository.PREFERENCES_NAME).edit().putString(DeviceRolePreferences.KEY_MODE, "SUB_ONLY").commit();
        ControllerPinManager.enterSubMode();
        assertEquals(100, LocalRetainedStatistics.load(context).stats.getTotalBlocks());
        try { new DomStatsRepository(context).load(); fail("Sub cannot read Dom aggregate"); }
        catch (SecurityException expected) { }
    }

    @Test public void quietUpgradePreservesDatesCountersAndNotifiesOnlyNewLiveAwards() throws Exception {
        session();
        long manual = WALL + 1234;
        prefs("betablocker_achievements").edit().putStringSet("unlocked", new LinkedHashSet<>(Arrays.asList("first_block")))
                .putLong("unlocked_at.first_block", manual).commit();
        Map<String, ?> source = prefs(StatsRepository.PREFS_NAME).getAll();
        LocalRetainedStatistics local = LocalRetainedStatistics.load(context);
        AchievementManager manager = new AchievementManager(context);
        HistoricalAchievementBackfill.local(context, manager, local);
        assertTrue(manager.isUnlocked("blocks_10")); assertTrue(manager.isUnlocked("first_session"));
        assertTrue(manager.getUnlockedAt("blocks_10") > 0); assertTrue(manager.getUnlockedAt("blocks_10") < System.currentTimeMillis());
        assertEquals(manual, prefs("betablocker_achievements").getLong("unlocked_at.first_block", 0));
        assertTrue(manager.takePendingNotifications().isEmpty());
        Map<String, ?> acknowledged = prefs("betablocker_achievements").getAll();
        HistoricalAchievementBackfill.local(context, new AchievementManager(context), LocalRetainedStatistics.load(context));
        assertEquals(acknowledged, prefs("betablocker_achievements").getAll());
        assertStatsUnchanged(source);
        prefs(StatsRepository.PREFS_NAME).edit().putLong("total_blocks_all_time", 1000).commit();
        manager = new AchievementManager(context);
        manager.checkRetainedAchievements(new StatsRepository(context, false).retainedSnapshot());
        assertTrue(manager.takePendingNotifications().stream().anyMatch(a -> a.getId().equals("blocks_100")));
    }

    @Test public void undatedPaidLedgerDeduplicatesIdsWithoutDatingPaymentsOrMigrating() throws Exception {
        String row = "original," + WALL + "," + (WALL + 60000) + ",1200,1,PAID,,NEW_DETECTION,USD";
        prefs("subhub_penance").edit().putString("events_v1", row + ";" + row)
                .putLong("total_paid_cents_v1", 2200).commit();
        Map<String, ?> before = prefs("subhub_penance").getAll();
        LocalRetainedStatistics local = LocalRetainedStatistics.load(context);
        assertEquals(Long.valueOf(1200), local.paid.get("USD")); assertEquals(Long.valueOf(2200), local.paid.get("EUR"));
        assertEquals(Long.valueOf(1200), local.assessed.get("USD"));
        assertEquals(0, local.history.threshold("paid_USD", 1));
        assertFalse(local.history.days().get(DAY).containsKey("paid_USD"));
        AchievementManager manager = new AchievementManager(context);
        HistoricalAchievementBackfill.local(context, manager, local);
        assertTrue(manager.isUnlocked("wallet_paid_10")); assertEquals(0, manager.getUnlockedAt("wallet_paid_10"));
        assertTrue(manager.takePendingNotifications().isEmpty());
        assertEquals(before, prefs("subhub_penance").getAll());
    }

    @Test public void limitsAndExportMetadataRecoverDatesWithoutOpeningMedia() throws Exception {
        try (DailyAppUsageStore usage = new DailyAppUsageStore(context, "daily_app_usage.db")) {
            String epoch = UUID.randomUUID().toString(), other = UUID.randomUUID().toString();
            usage.checkpoint(Arrays.asList(new DailyAppUsageStore.Entry(epoch, DAY, "UTC", "", 100),
                    new DailyAppUsageStore.Entry(epoch, DAY, "UTC", "com.example.one", 80),
                    new DailyAppUsageStore.Entry(epoch, DAY, "UTC", "com.example.two", 70),
                    new DailyAppUsageStore.Entry(other, DAY, "UTC", "com.example.one", 30),
                    new DailyAppUsageStore.Entry(other, DAY, "UTC", "com.example.two", 20)));
        }
        try (com.subhub.app.capture.export.ExportJobStore exports = new com.subhub.app.capture.export.ExportJobStore(context)) {
            SQLiteDatabase db = exports.getWritableDatabase();
            db.execSQL("INSERT INTO items(job,source,state,kind,saved_at,saved_zone) VALUES('one','content://invalid/no-access','SAVED','photos',?,'UTC')", new Object[]{WALL});
            db.execSQL("INSERT INTO items(job,source,state,kind) VALUES('two','content://invalid/no-access','SAVED','photos')");
            db.execSQL("INSERT INTO items(job,source,state,kind) VALUES('three','content://invalid/no-access','PENDING','photos')");
            String before = rows(db, "items");
            LocalRetainedStatistics local = LocalRetainedStatistics.load(context);
            assertEquals(130, local.stats.getLimitedAppMillis()); assertEquals(2, local.stats.getExportedImages());
            assertEquals(Long.valueOf(1), local.history.days().get(DAY).get("photos"));
            assertEquals(-1L, (long)local.range(DAY, DAY).get(DAY).get("paid_EUR"));
            assertFalse(local.range("2025-12-30", "2025-12-30").containsKey("2025-12-30"));
            assertEquals(before, rows(db, "items"));
        }
    }

    @Test public void expiredEffectsAndInvalidActiveClockRemainUntouched() throws Exception {
        prefs(SettingsRepository.PREFERENCES_NAME).edit().putLong("commitment_ends_at", 1L)
                .putLong("commitment_started_at", 1L).putBoolean("app_mode_armed", false).commit();
        prefs("subhub_penance").edit().putLong("paid_pause_active_until", 1L).putBoolean("paid_pause_rearm", true).commit();
        prefs(StatsRepository.PREFS_NAME).edit().putLong("active_session_start_ms", Long.MAX_VALUE)
                .putInt("active_session_blocks", 4).commit();
        Map<String, ?> settings = prefs(SettingsRepository.PREFERENCES_NAME).getAll(), penance = prefs("subhub_penance").getAll(), stats = prefs(StatsRepository.PREFS_NAME).getAll();
        DomStatsRepository.Loaded loaded = new DomStatsRepository(context).loadWithHistory();
        HomeAchievementPreview.loadDom(context, loaded);
        assertEquals(settings, prefs(SettingsRepository.PREFERENCES_NAME).getAll());
        assertEquals(penance, prefs("subhub_penance").getAll());
        assertStatsUnchanged(stats); assertIdle();
    }

    @Test public void mixedLocalAndInboundHistoryUsesCurrentScopeAndMaxOfCopies() throws Exception {
        session();
        try (RemoteStore store = new RemoteStore(context)) {
            String id = String.join("", Collections.nCopies(64, "a"));
            store.getWritableDatabase().execSQL("INSERT INTO peers(peer,generation,name,local_role,state) VALUES(?,'current','Synthetic','DOM','ACTIVE')",new Object[]{id});
            JSONObject day = new JSONObject().put("schema",1).put("day",DAY).put("zone","UTC").put("metrics",new JSONObject().put("service_ms",3600000).put("sessions",1));
            ContentValues row = new ContentValues(); row.put("peer",id); row.put("generation","current"); row.put("stream","DAILY"); row.put("item",DAY); row.put("seq",1); row.put("body",day.toString()); row.put("deleted",0); row.put("observed",WALL);
            store.getWritableDatabase().insertOrThrow("remote_objects",null,row);
            String before = rows(store.getReadableDatabase(),"remote_objects");
            DomStatsSnapshot facts = new DomStatsRepository(context).load();
            assertEquals(2,facts.subs); assertEquals(7200,facts.sessionSeconds); assertEquals(2,facts.sessions);
            assertEquals(before,rows(store.getReadableDatabase(),"remote_objects"));
            store.getWritableDatabase().execSQL("UPDATE peers SET state='REVOKED'");
            assertEquals(1,new DomStatsRepository(context).load().subs);
            assertEquals(before,rows(store.getReadableDatabase(),"remote_objects"));
        }
    }

    @Test public void originalActivityFactsAndPeerEchoesDoNotDoubleCountOrAdvanceProtocol() throws Exception {
        try(RemoteStore store = new RemoteStore(context)) {
            SQLiteDatabase db = store.getWritableDatabase();
            String id = String.join("",Collections.nCopies(64,"b"));
            db.execSQL("INSERT INTO peers(peer,generation,name,local_role,state) VALUES(?,'current','Synthetic','DOM','ACTIVE')",new Object[]{id});
            JSONObject event = new JSONObject().put("schema",1).put("id","tribute_original")
                    .put("kind","TRIBUTE_TRIGGERED").put("observedAt",WALL).put("day",DAY).put("zone","UTC")
                    .put("packageName", "").put("amountCents",250).put("currency","EUR")
                    .put("reason","NEW_DETECTION").put("limitMinutes",0).put("configuration", "");
            db.execSQL("INSERT INTO activity_source(event_id,source_key,body,observed) VALUES('tribute_original','original',?,?)",new Object[]{event.toString(),WALL});
            db.execSQL("INSERT INTO remote_objects(peer,generation,stream,item,seq,body,deleted,observed) VALUES(?,'current','EVENTS','tribute_original',1,?,0,?)",new Object[]{id,event.toString(),WALL});
            String source = rows(db,"activity_source"), inbound=rows(db,"remote_objects"), outbox=rows(db,"outbox");
            LocalRetainedStatistics local=LocalRetainedStatistics.load(context);
            assertEquals(Long.valueOf(250),local.assessed.get("EUR"));
            List<DomDailySnapshot.History> histories=new DomDailyStatsSource(context).histories(store,local);
            long total=0;
            for(DomDailySnapshot.History history:histories)
                for(Map<String,Long> day:history.days.values()) total+=Math.max(0,day.getOrDefault("assessed_EUR",0L));
            assertEquals(250,total);
            assertEquals(source,rows(db,"activity_source")); assertEquals(inbound,rows(db,"remote_objects")); assertEquals(outbox,rows(db,"outbox"));
            assertEquals("",rows(db,"outgoing")); assertEquals("",rows(db,"incoming"));
        }
    }

    @Test public void datedRepairsPreserveOriginalReceiptsAndForeignRolePending() throws Exception {
        session();
        long original = System.currentTimeMillis(), placeholder = LocalDate.of(2026,8,27)
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        prefs("betablocker_achievements").edit()
                .putStringSet("unlocked",new LinkedHashSet<>(Arrays.asList("first_block","blocks_10","dom_subs_1")))
                .putStringSet("pending_notifications",new LinkedHashSet<>(Arrays.asList("first_block","dom_subs_1")))
                .putLong("unlocked_at.first_block",original).putLong("unlocked_at.blocks_10",placeholder).commit();
        AchievementManager manager = new AchievementManager(context);
        HistoricalAchievementBackfill.local(context,manager,LocalRetainedStatistics.load(context));
        assertTrue(manager.getUnlockedAt("first_block") < original);
        assertTrue(manager.getUnlockedAt("blocks_10") > 0);
        assertEquals(original,prefs("betablocker_achievements").getLong("unlocked_at.first_block",0));
        assertEquals(placeholder,prefs("betablocker_achievements").getLong("unlocked_at.blocks_10",0));
        assertTrue(manager.takePendingNotifications().isEmpty());
        assertEquals(Collections.singleton("dom_subs_1"),prefs("betablocker_achievements").getStringSet("pending_notifications",Collections.emptySet()));
        assertEquals(manager.getUnlockedAt("first_block"),new AchievementManager(context).getUnlockedAt("first_block"));
    }

    @Test public void historicalCalendarCrossesYearBoundaryAndRestoresSelection() throws Exception {
        session();
        android.os.Bundle state = new android.os.Bundle();
        state.putInt("daily_range", 365); state.putInt("daily_year", 2026); state.putInt("daily_month", 1);
        state.putString("daily_selected", "2026-01-01");
        try (androidx.test.core.app.ActivityScenario<com.subhub.app.help.HelpActivity> scenario =
                androidx.test.core.app.ActivityScenario.launch(new Intent(context.getBaseContext(), com.subhub.app.help.HelpActivity.class))) {
            mount(scenario, state); awaitCalendar(scenario, "January 2026");
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription(com.subhub.app.R.string.stats_previous_month))
                    .perform(androidx.test.espresso.action.ViewActions.scrollTo(), androidx.test.espresso.action.ViewActions.click());
            awaitCalendar(scenario, "December 2025");
            scenario.onActivity(activity -> ((DailyStatsPanel)activity.findViewById(com.subhub.app.R.id.daily_statistics_panel)).save(state));
            assertEquals(2025, state.getInt("daily_year")); assertEquals(12, state.getInt("daily_month"));
            scenario.recreate(); mount(scenario, state); awaitCalendar(scenario, "December 2025");
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription(com.subhub.app.R.string.stats_next_month))
                    .perform(androidx.test.espresso.action.ViewActions.scrollTo(), androidx.test.espresso.action.ViewActions.click());
            awaitCalendar(scenario, "January 2026");
        }
    }
    private void mount(androidx.test.core.app.ActivityScenario<?> scenario, android.os.Bundle state) {
        scenario.onActivity(activity -> {
            android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
            scroll.addView(new DailyStatsPanel(activity, false, state, new LocalDailyStatsSource(context)));
            activity.setContentView(scroll);
        });
    }
    private void awaitCalendar(androidx.test.core.app.ActivityScenario<?> scenario, String expected) throws Exception {
        long end = android.os.SystemClock.elapsedRealtime() + 10000;
        while(android.os.SystemClock.elapsedRealtime() < end) {
            java.util.concurrent.atomic.AtomicBoolean found = new java.util.concurrent.atomic.AtomicBoolean();
            scenario.onActivity(activity -> found.set(texts(activity.findViewById(android.R.id.content)).contains(expected)));
            if(found.get()) return;
            Thread.sleep(40);
        }
        throw new AssertionError("Calendar did not render " + expected);
    }
    private static String texts(android.view.View view) {
        StringBuilder text = new StringBuilder();
        if(view instanceof android.widget.TextView) text.append(((android.widget.TextView)view).getText());
        if(view instanceof android.view.ViewGroup) for(int i=0;i<((android.view.ViewGroup)view).getChildCount();i++) text.append(texts(((android.view.ViewGroup)view).getChildAt(i)));
        return text.toString();
    }
    private void assertStatsUnchanged(Map<String, ?> before) {
        Map<String,Object> actual = new LinkedHashMap<>(prefs(StatsRepository.PREFS_NAME).getAll());
        actual.remove("last_milestone_shown");
        Map<String,Object> expected = new LinkedHashMap<>(before); expected.remove("last_milestone_shown");
        assertEquals(expected,actual);
    }

    private static String rows(SQLiteDatabase db, String table) {
        StringBuilder result = new StringBuilder();
        try (Cursor c = db.rawQuery("SELECT * FROM " + table + " ORDER BY rowid", null)) {
            while(c.moveToNext()) { for(int i=0;i<c.getColumnCount();i++) result.append(c.getString(i)).append('|'); result.append('\n'); }
        }
        return result.toString();
    }
    private static void assertIdle() {
        assertFalse(com.subhub.app.service.ScreenCaptureService.isRunning());
        assertFalse(com.subhub.app.service.ScreenshotAccessibilityService.isRunning());
    }
    private static final class Scoped extends ContextWrapper {
        private final String prefix = "history-" + UUID.randomUUID() + "-";
        private final Set<String> databases = new LinkedHashSet<>(), preferences = new LinkedHashSet<>();
        Scoped(Context base) { super(base); }
        @Override public Context getApplicationContext() { return this; }
        @Override public SharedPreferences getSharedPreferences(String name,int mode) { preferences.add(name); return super.getSharedPreferences(prefix+name,mode); }
        @Override public File getDatabasePath(String name) { databases.add(name); return super.getDatabasePath(prefix+name); }
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory) { databases.add(name); return super.openOrCreateDatabase(prefix+name,mode,factory); }
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler) { databases.add(name); return super.openOrCreateDatabase(prefix+name,mode,factory,handler); }
        @Override public boolean deleteDatabase(String name) { return super.deleteDatabase(prefix+name); }
        @Override public ComponentName startService(Intent intent) { throw new AssertionError("History started a service"); }
        @Override public ComponentName startForegroundService(Intent intent) { throw new AssertionError("History started a foreground service"); }
        @Override public boolean stopService(Intent intent) { throw new AssertionError("History stopped a service"); }
        void clean() { for(String name:databases) super.deleteDatabase(prefix+name); for(String name:preferences) super.deleteSharedPreferences(prefix+name); }
    }
}
