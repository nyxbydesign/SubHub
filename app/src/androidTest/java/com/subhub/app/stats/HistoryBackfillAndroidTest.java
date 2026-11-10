package com.subhub.app.stats;

import static org.junit.Assert.*;
import android.content.*;
import android.database.Cursor;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.relationships.DomScopeObserverFixture;
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
    private DomScopeObserverFixture scope;
    private DailyStatsStore daily, previous;
    private Field singleton;
    private static final String DAY = "2025-12-31";
    private static final long WALL = Instant.parse("2025-12-31T12:00:00Z").toEpochMilli();

    @Before public void setup() throws Exception {
        assertIdle();
        context = new Scoped(InstrumentationRegistry.getInstrumentation().getTargetContext());
        scope = new DomScopeObserverFixture(context);
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
        try { DailyStatsStore.awaitWrites(); }
        finally {
            try { if (singleton != null) singleton.set(null, previous); }
            finally {
                try { if (daily != null) daily.close(); }
                finally {
                    try { if (scope != null) scope.close(); }
                    finally { if (context != null) context.clean(); assertIdle(); }
                }
            }
        }
    }
    private SharedPreferences prefs(String name) { return context.getSharedPreferences(name, 0); }
    private void session() {
        String old = WALL + ",3600,100";
        prefs(StatsRepository.PREFS_NAME).edit().putString("session_history", old + ";" + old
                + ";2," + WALL + ",3600,100,10000,2,1,250,3,4,0").commit();
    }

    @Test public void stoppedTokyoStreakDoesNotReturnThroughLosAngelesHistoryAfterExpiry() throws Exception {
        TimeZone originalZone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            long start = Instant.parse("2026-11-03T00:00:00Z").toEpochMilli();
            long end = Instant.parse("2026-11-10T01:00:00Z").toEpochMilli();
            StatsRepository.NativeSession session = new StatsRepository.NativeSession(
                    UUID.randomUUID().toString(), start, "Asia/Tokyo");
            assertTrue(prefs(SettingsRepository.PREFERENCES_NAME).edit()
                    .putString(StatsRepository.NATIVE_OWNER_KEY, session.identity).commit());
            StatsRepository repository = new StatsRepository(context, false);
            assertTrue(repository.ensureNativeSession(session));
            assertEquals(8, LocalRetainedStatistics.load(context, end).stats.getCurrentStreak());
            assertTrue(repository.endSessionForIdentity(session.identity, end, (end - start) / 1000));
            assertFalse(prefs(StatsRepository.PREFS_NAME).contains(StatsRepository.ACTIVE_SESSION_ZONE));
            assertEquals("Asia/Tokyo", prefs(StatsRepository.PREFS_NAME).getString(StatsRepository.LAST_SESSION_ZONE, ""));
            LocalRetainedStatistics stopped = LocalRetainedStatistics.load(context, end);
            assertEquals(8, stopped.stats.getCurrentStreak());
            AchievementManager manager = new AchievementManager(context);
            manager.checkRetainedAchievements(stopped.stats);
            assertTrue(manager.isUnlocked("streak_7"));
            long earnedAt = manager.getUnlockedAt("streak_7");
            long before = Instant.parse("2026-11-11T14:59:59Z").toEpochMilli();
            long boundary = Instant.parse("2026-11-11T15:00:00Z").toEpochMilli();
            long later = Instant.parse("2026-11-12T01:00:00Z").toEpochMilli();
            assertEquals(8, LocalRetainedStatistics.load(context, before).stats.getCurrentStreak());
            assertEquals(0, LocalRetainedStatistics.load(context, boundary).stats.getCurrentStreak());
            LocalRetainedStatistics reloaded = LocalRetainedStatistics.load(context, later);
            assertEquals(0, reloaded.repository.retainedSnapshot(later).getCurrentStreak());
            assertEquals(8, reloaded.history.currentStreak(Instant.ofEpochMilli(later)
                    .atZone(ZoneId.systemDefault()).toLocalDate()));
            assertEquals(0, reloaded.stats.getCurrentStreak());
            manager.checkRetainedAchievements(reloaded.stats);
            assertTrue(manager.isUnlocked("streak_7")); assertEquals(earnedAt, manager.getUnlockedAt("streak_7"));
            assertEquals(0, LocalRetainedStatistics.load(context, later).stats.getCurrentStreak());
        } finally { TimeZone.setDefault(originalZone); }
    }

    @Test public void localOnlyBothCountsOnceAndNeverImportsSourceRows() throws Exception {
        session();
        Map<String, ?> before = prefs(StatsRepository.PREFS_NAME).getAll();
        Map<String, Map<String, Long>> dailyBefore = daily.days("0000-01-01", "9999-12-31");
        DomStatsSnapshot unlinked = new DomStatsRepository(context).load();
        assertEquals(0, unlinked.subs); assertEquals(0, unlinked.sessionSeconds); assertEquals(0, unlinked.sessions);
        scope.outgoing(DomScopeObserverFixture.local("approved-local", 1));
        DomStatsRepository.Loaded approved = new DomStatsRepository(context).loadWithHistory();
        DomStatsSnapshot facts = approved.stats;
        assertEquals(1, facts.subs); assertEquals(3600, facts.sessionSeconds); assertEquals(1, facts.sessions);
        assertEquals("approved-local", approved.histories.get(0).identity.generation);
        LocalRetainedStatistics local = LocalRetainedStatistics.load(context);
        assertEquals(100, local.stats.getTotalBlocks()); assertEquals(1, local.history.sessions().size());
        assertEquals(before, prefs(StatsRepository.PREFS_NAME).getAll());
        DailyStatsStore.awaitWrites(); assertEquals(dailyBefore, daily.days("0000-01-01", "9999-12-31"));
        scope.outgoing(DomScopeObserverFixture.local("replacement-local", 1));
        assertThrows(IllegalStateException.class, approved.scope::revalidate);
        scope.outgoing();
        assertEquals(0, new DomStatsRepository(context).load().subs);
        assertEquals(1, LocalRetainedStatistics.load(context).history.sessions().size());
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
            DomScopeObserverFixture.mapOwnedPeer(context, store, id, "current");
            JSONObject day = new JSONObject().put("schema",1).put("day",DAY).put("zone","UTC").put("metrics",new JSONObject().put("service_ms",3600000).put("sessions",1));
            ContentValues row = new ContentValues(); row.put("peer",id); row.put("generation","current"); row.put("stream","DAILY"); row.put("item",DAY); row.put("seq",1); row.put("body",day.toString()); row.put("deleted",0); row.put("observed",WALL);
            store.getWritableDatabase().insertOrThrow("remote_objects",null,row);
            String before = rows(store.getReadableDatabase(),"remote_objects");
            scope.outgoing(DomScopeObserverFixture.peer(store.peer(id, "current"), 1));
            DomStatsSnapshot peerOnly = new DomStatsRepository(context).load();
            assertEquals(1, peerOnly.subs); assertEquals(3600, peerOnly.sessionSeconds); assertEquals(1, peerOnly.sessions);
            assertEquals(1, LocalRetainedStatistics.load(context).history.sessions().size());
            // No incoming Dom governs this fixture's Sub: combined projection requires explicit LOCAL approval.
            scope.outgoing(DomScopeObserverFixture.local("approved-local", 1),
                    DomScopeObserverFixture.peer(store.peer(id, "current"), 2));
            DomStatsSnapshot facts = new DomStatsRepository(context).load();
            assertEquals(2,facts.subs); assertEquals(7200,facts.sessionSeconds); assertEquals(2,facts.sessions);
            assertEquals(before,rows(store.getReadableDatabase(),"remote_objects"));
            scope.outgoing(DomScopeObserverFixture.local("approved-local", 1),
                    DomScopeObserverFixture.peer(id, "unapproved-generation", 2));
            DomStatsSnapshot wrongGeneration = new DomStatsRepository(context).load();
            assertEquals(1, wrongGeneration.subs); assertEquals(3600, wrongGeneration.sessionSeconds);
            scope.outgoing();
            assertEquals(0, new DomStatsRepository(context).load().subs);
            scope.outgoing(DomScopeObserverFixture.local("approved-local", 1),
                    DomScopeObserverFixture.peer(store.peer(id, "current"), 2));
            store.getWritableDatabase().execSQL("UPDATE peers SET state='REVOKED'");
            assertEquals(1,new DomStatsRepository(context).load().subs);
            assertEquals(before,rows(store.getReadableDatabase(),"remote_objects"));
        }
    }

    @Test public void originalActivityFactsAndPeerEchoesDoNotDoubleCountOrAdvanceProtocol() throws Exception {
        try(RemoteStore store = new RemoteStore(context)) {
            SQLiteDatabase db = store.getWritableDatabase();
            String id = String.join("",Collections.nCopies(64,"b"));
            db.execSQL("INSERT INTO peers(peer,generation,name,local_role,state) VALUES(?,'current','Synthetic','SUB','ACTIVE')",new Object[]{id});
            DomScopeObserverFixture.mapOwnedPeer(context, store, id, "current");
            scope.scope(DomScopeObserverFixture.incomingDom(store.peer(id, "current")));
            JSONObject event = new JSONObject().put("schema",1).put("id","tribute_original")
                    .put("kind","TRIBUTE_TRIGGERED").put("observedAt",WALL).put("day",DAY).put("zone","UTC")
                    .put("packageName", "").put("amountCents",250).put("currency","EUR")
                    .put("reason","NEW_DETECTION").put("limitMinutes",0).put("configuration", "");
            db.execSQL("INSERT INTO activity_source(event_id,source_key,body,observed) VALUES('tribute_original','original',?,?)",new Object[]{event.toString(),WALL});
            db.execSQL("INSERT INTO remote_objects(peer,generation,stream,item,seq,body,deleted,observed) VALUES(?,'current','EVENTS','tribute_original',1,?,0,?)",new Object[]{id,event.toString(),WALL});
            String outgoing = String.join("",Collections.nCopies(64,"d"));
            db.execSQL("INSERT INTO peers(peer,generation,name,local_role,state) VALUES(?,'current','Outgoing Sub','DOM','ACTIVE')",new Object[]{outgoing});
            DomScopeObserverFixture.mapOwnedPeer(context, store, outgoing, "current");
            db.execSQL("INSERT INTO remote_objects(peer,generation,stream,item,seq,body,deleted,observed) VALUES(?,'current','EVENTS','tribute_original',1,?,0,?)",new Object[]{outgoing,event.toString(),WALL});
            String source = rows(db,"activity_source"), inbound=rows(db,"remote_objects"), outbox=rows(db,"outbox");
            LocalRetainedStatistics local=LocalRetainedStatistics.load(context);
            assertEquals(Long.valueOf(250),local.assessed.get("EUR"));
            assertEquals(1, local.activityCopies.size());
            assertTrue(new DomDailyStatsSource(context).histories(store,local).isEmpty());
            assertEquals(0, new DomStatsRepository(context).load().subs);
            long total=0;
            for(Map<String,Long> day:local.history.days().values()) total+=Math.max(0,day.getOrDefault("assessed_EUR",0L));
            assertEquals(250,total);
            // An incoming Dom and local self facts do not enter the outgoing Sub's aggregate.
            scope.scope(DomScopeObserverFixture.incomingDom(store.peer(id, "current")),
                    DomScopeObserverFixture.peer(store.peer(outgoing, "current"), 1));
            List<DomDailySnapshot.History> histories=new DomDailyStatsSource(context).histories(store,local);
            assertEquals(1, histories.size()); assertEquals(outgoing, histories.get(0).identity.identity);
            total=0;
            for(DomDailySnapshot.History history:histories)
                for(Map<String,Long> day:history.days.values()) total+=Math.max(0,day.getOrDefault("assessed_EUR",0L));
            assertEquals(250,total);
            assertEquals(local.assessed, LocalRetainedStatistics.load(context).assessed);
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

    @Test public void mixedStoredPayloadsPreserveLocalAndPairedHistoryWithoutAdvancingSync() throws Exception {
        try (RemoteStore store = new RemoteStore(context)) {
            SQLiteDatabase db = store.getWritableDatabase();
            String peer = String.join("", Collections.nCopies(64, "c"));
            db.execSQL("INSERT INTO peers(peer,generation,name,local_role,state) VALUES(?,'current','Synthetic','DOM','ACTIVE')", new Object[]{peer});
            DomScopeObserverFixture.mapOwnedPeer(context, store, peer, "current");
            JSONObject first = tribute("tribute_text", 250, "EUR"), second = tribute("tribute_blob", 450, "USD");
            for (JSONObject event : Arrays.asList(first, second))
                db.execSQL("INSERT INTO activity_source(event_id,source_key,body,observed) VALUES(?,?,?,?)",
                        new Object[]{event.getString("id"), event.getString("id"), event.toString(), WALL});
            JSONObject day = new JSONObject().put("schema", 1).put("day", DAY).put("zone", "UTC")
                    .put("metrics", new JSONObject().put("service_ms", 60000).put("sessions", 1)
                            .put("paid_EUR", 0).put("paid_USD", 0).put("censors", 0).put("limits_ms", 0)
                            .put("limit_stops", 0).put("whispers", 0).put("popups", 0).put("photos", 0).put("videos", 0));
            JSONObject runtime = new JSONObject().put("schema", 1).put("device", "Synthetic history fixture");
            for (String key : new String[]{"observedAt", "sessionStart", "sessionEnd", "appliedRevision"}) runtime.put(key, 0);
            for (String key : new String[]{"serviceActive", "screenCapture", "recognitionActive", "accessibilityBound",
                    "accessibilityEnabled", "overlayPermission", "notifications", "armed", "paused"}) runtime.put(key, false);
            JSONObject consent = new JSONObject();
            for (String feature : SubFeaturePolicy.FEATURES) consent.put(feature, true);
            runtime.put("featureConsent", consent);
            putMirror(db, peer, "DAILY", DAY, day);
            putMirror(db, peer, "RUNTIME", "current", runtime);
            putMirror(db, peer, "EVENTS", first.getString("id"), first);
            putMirror(db, peer, "EVENTS", "tribute_remote", tribute("tribute_remote", 700, "USD"));
            db.execSQL("INSERT INTO outgoing(peer,generation,stream,next_seq,acked,offered) VALUES(?,'current','DAILY',11,5,8)", new Object[]{peer});
            db.execSQL("INSERT INTO incoming(peer,generation,stream,through_seq) VALUES(?,'current','EVENTS',9)", new Object[]{peer});

            scope.outgoing(DomScopeObserverFixture.peer(store.peer(peer, "current"), 1));
            String unlinkedBefore = storageWitness(db);
            LocalRetainedStatistics plain = LocalRetainedStatistics.load(context);
            DomDailyStatsSource source = new DomDailyStatsSource(context);
            DailyStatsPanel.DailyData outgoingOnly = source.load(store, DAY, DAY, plain);
            assertEquals(Long.valueOf(250), outgoingOnly.days.get(DAY).get("assessed_EUR"));
            assertEquals(Long.valueOf(700), outgoingOnly.days.get(DAY).get("assessed_USD"));
            assertEquals(Long.valueOf(60000), outgoingOnly.days.get(DAY).get("service_ms"));
            assertEquals(unlinkedBefore, storageWitness(db));
            scope.outgoing(DomScopeObserverFixture.local("approved-local", 1),
                    DomScopeObserverFixture.peer(store.peer(peer, "current"), 2));
            DailyStatsPanel.DailyData plainDays = source.load(store, DAY, DAY, plain);
            assertEquals(Long.valueOf(250), plain.assessed.get("EUR"));
            assertEquals(Long.valueOf(450), plain.assessed.get("USD"));
            assertEquals(Long.valueOf(250), plainDays.days.get(DAY).get("assessed_EUR"));
            assertEquals(Long.valueOf(1150), plainDays.days.get(DAY).get("assessed_USD"));
            assertEquals(Long.valueOf(-1), plainDays.days.get(DAY).get("service_ms"));
            DailyStatsPanel.DailyData plainPeerDays = source.load(store, DAY, DAY);
            assertEquals(Long.valueOf(60000), plainPeerDays.days.get(DAY).get("service_ms"));

            Object encoded = com.subhub.app.storage.StoredPayloads.bind(second.toString());
            assertTrue("The history accessor must read an actual compressed BLOB", encoded instanceof byte[]);
            db.execSQL("UPDATE activity_source SET body=? WHERE event_id='tribute_blob'", new Object[]{encoded});
            for (String stream : Arrays.asList("DAILY", "EVENTS", "RUNTIME")) {
                try (Cursor cursor = db.rawQuery("SELECT item,body FROM remote_objects WHERE stream=? ORDER BY item", new String[]{stream})) {
                    while (cursor.moveToNext()) {
                        String text = cursor.getString(1);
                        byte[] compressed = com.subhub.app.storage.PayloadCodec.compressIfUseful(text);
                        assertNotNull("Compressed mirror fixture: " + stream, compressed);
                        db.execSQL("UPDATE remote_objects SET body=? WHERE stream=? AND item=?",
                                new Object[]{compressed, stream, cursor.getString(0)});
                    }
                }
            }
            try (Cursor types = db.rawQuery("SELECT typeof(body) FROM activity_source ORDER BY id", null)) {
                assertTrue(types.moveToNext()); assertEquals("text", types.getString(0));
                assertTrue(types.moveToNext()); assertEquals("blob", types.getString(0)); assertFalse(types.moveToNext());
            }
            String before = storageWitness(db);
            List<String> read = new ArrayList<>();
            store.readRetainedLocalStatistics(event -> read.add(event.toString()));
            assertEquals(Arrays.asList(first.toString(), second.toString()), read);
            LocalRetainedStatistics mixed = LocalRetainedStatistics.load(context);
            assertEquals(plain.assessed, mixed.assessed); assertEquals(plain.activityCopies, mixed.activityCopies);
            assertEquals(plain.history.days(), mixed.history.days());
            assertEquals(plainDays.days, source.load(store, DAY, DAY, mixed).days);
            assertEquals(plainPeerDays.days, source.load(store, DAY, DAY).days);
            assertEquals(before, storageWitness(db));

            consent.put("wallet", false);
            putMirror(db, peer, "RUNTIME", "current", runtime);
            String withdrawn = storageWitness(db);
            DailyStatsPanel.DailyData hidden = source.load(store, DAY, DAY, mixed);
            assertEquals(Long.valueOf(450), hidden.days.get(DAY).get("assessed_USD"));
            assertEquals(Long.valueOf(-1), hidden.days.get(DAY).get("service_ms"));
            assertEquals(withdrawn, storageWitness(db));
            scope.outgoing(DomScopeObserverFixture.peer(store.peer(peer, "current"), 1));
            DailyStatsPanel.DailyData noLocal = source.load(store, DAY, DAY, mixed);
            assertEquals(Long.valueOf(-1), noLocal.days.get(DAY).get("assessed_USD"));
            assertEquals(Long.valueOf(60000), noLocal.days.get(DAY).get("service_ms"));
            assertEquals(Long.valueOf(450), LocalRetainedStatistics.load(context).assessed.get("USD"));
            assertEquals(withdrawn, storageWitness(db));
            scope.outgoing(DomScopeObserverFixture.local("approved-local", 1),
                    DomScopeObserverFixture.peer(store.peer(peer, "current"), 2));
            db.execSQL("UPDATE peers SET state='REVOKED'");
            assertEquals(Long.valueOf(450), source.load(store, DAY, DAY, mixed).days.get(DAY).get("assessed_USD"));
            assertEquals(withdrawn, storageWitness(db));
            assertEquals("", rows(db, "outbox"));
            assertEquals("", rows(db, "payload_maintenance"));
            assertTrue(prefs(SettingsRepository.PREFERENCES_NAME).edit().putString(DeviceRolePreferences.KEY_MODE, "DOM_ONLY").commit());
            try { store.readRetainedLocalStatistics(event -> fail("Dom-only workspace read local Sub facts")); fail("Local Sub access admitted"); }
            catch (SecurityException expected) { }
        }
    }

    private static JSONObject tribute(String id, long amount, String currency) throws Exception {
        return new JSONObject().put("schema", 1).put("id", id).put("kind", "TRIBUTE_TRIGGERED")
                .put("observedAt", WALL).put("day", DAY).put("zone", "UTC").put("packageName", "")
                .put("amountCents", amount).put("currency", currency).put("reason", "NEW_DETECTION")
                .put("limitMinutes", 0).put("configuration", "");
    }
    private static void putMirror(SQLiteDatabase db, String peer, String stream, String key, JSONObject body) {
        db.execSQL("INSERT OR REPLACE INTO remote_objects(peer,generation,stream,item,seq,body,deleted,observed) VALUES(?,'current',?,?,7,?,0,?)",
                new Object[]{peer, stream, key, body.toString(), WALL});
    }
    private static String storageWitness(SQLiteDatabase db) {
        StringBuilder witness = new StringBuilder();
        for (String table : Arrays.asList("activity_source", "remote_objects", "outgoing", "incoming", "outbox", "payload_maintenance")) {
            witness.append(table).append(':');
            try (Cursor rows = db.rawQuery("SELECT * FROM " + table + " ORDER BY rowid", null)) {
                while (rows.moveToNext()) {
                    for (int i = 0; i < rows.getColumnCount(); i++) {
                        witness.append(rows.getType(i)).append('=');
                        witness.append(rows.getType(i) == Cursor.FIELD_TYPE_BLOB
                                ? android.util.Base64.encodeToString(rows.getBlob(i), android.util.Base64.NO_WRAP) : rows.getString(i)).append('|');
                    }
                    witness.append('\n');
                }
            }
        }
        return witness.toString();
    }

    @Test public void historicalCalendarCrossesYearBoundaryAndRestoresSelection() throws Exception {
        session();
        android.os.Bundle state = new android.os.Bundle();
        state.putInt("daily_range", 365); state.putInt("daily_year", 2026); state.putInt("daily_month", 1);
        state.putString("daily_selected", "2026-01-01");
        try (androidx.test.core.app.ActivityScenario<com.subhub.app.help.HelpActivity> scenario =
                androidx.test.core.app.ActivityScenario.launch(new Intent(context.getBaseContext(), com.subhub.app.help.HelpActivity.class))) {
            mount(scenario, state); awaitCalendar(scenario, 2026, 1);
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription(com.subhub.app.R.string.stats_previous_month))
                    .perform(androidx.test.espresso.action.ViewActions.scrollTo(), androidx.test.espresso.action.ViewActions.click());
            awaitCalendar(scenario, 2025, 12);
            scenario.onActivity(activity -> ((DailyStatsPanel)activity.findViewById(com.subhub.app.R.id.daily_statistics_panel)).save(state));
            assertEquals(2025, state.getInt("daily_year")); assertEquals(12, state.getInt("daily_month"));
            scenario.recreate(); mount(scenario, state); awaitCalendar(scenario, 2025, 12);
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription(com.subhub.app.R.string.stats_next_month))
                    .perform(androidx.test.espresso.action.ViewActions.scrollTo(), androidx.test.espresso.action.ViewActions.click());
            awaitCalendar(scenario, 2026, 1);
        }
    }
    private void mount(androidx.test.core.app.ActivityScenario<?> scenario, android.os.Bundle state) {
        scenario.onActivity(activity -> {
            android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
            scroll.addView(new DailyStatsPanel(activity, false, state, new LocalDailyStatsSource(context)));
            activity.setContentView(scroll);
        });
    }
    private void awaitCalendar(androidx.test.core.app.ActivityScenario<?> scenario, int year, int month) throws Exception {
        long end = android.os.SystemClock.elapsedRealtime() + 10000;
        java.util.concurrent.atomic.AtomicReference<JSONObject> last = new java.util.concurrent.atomic.AtomicReference<>();
        while(android.os.SystemClock.elapsedRealtime() < end) {
            java.util.concurrent.atomic.AtomicBoolean found = new java.util.concurrent.atomic.AtomicBoolean();
            scenario.onActivity(activity -> {
                // Match the production formatter on the actual Activity thread without changing its locale.
                String expected = LocalDate.of(year, month, 1).format(
                        java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()));
                DailyStatsPanel panel = activity.findViewById(com.subhub.app.R.id.daily_statistics_panel);
                android.os.Bundle actual = new android.os.Bundle();
                if (panel != null) panel.save(actual);
                found.set(panel != null && actual.getInt("daily_range") == 365
                        && actual.getInt("daily_year") == year && actual.getInt("daily_month") == month
                        && hasCalendarText(panel, expected));
                if (!found.get()) last.set(calendarObservation(activity, panel, actual, expected, year, month));
            });
            if(found.get()) return;
            Thread.sleep(40);
        }
        String observation = last.get() == null ? "No Activity observation completed before the deadline" : last.get().toString();
        AssertionError failure = new AssertionError("Calendar did not render " + YearMonth.of(year, month)
                + "; see CALENDAR_OBSERVER_FAILURE snapshot");
        android.util.Log.e("HistoryCalendar", observation);
        android.os.Bundle evidence = new android.os.Bundle();
        evidence.putString("stream", "\nCALENDAR_OBSERVER_FAILURE " + observation + "\n");
        try {
            // In-progress diagnostics enter the retained instrumentation log before the failing result.
            InstrumentationRegistry.getInstrumentation().sendStatus(2, evidence);
        } catch (RuntimeException unavailable) { failure.addSuppressed(unavailable); }
        throw failure;
    }
    private JSONObject calendarObservation(android.app.Activity activity, DailyStatsPanel panel,
            android.os.Bundle state, String expected, int year, int month) {
        try {
            String observed = texts(activity.findViewById(android.R.id.content));
            JSONObject result = new JSONObject().put("expected_year", year).put("expected_month", month)
                    .put("expected_label", calendarBounded(expected, 128))
                    .put("default_locale", calendarBounded(Locale.getDefault().toLanguageTag(), 128))
                    .put("activity_locales", calendarBounded(activity.getResources().getConfiguration().getLocales().toLanguageTags(), 256))
                    .put("observed_elapsed_ms", android.os.SystemClock.elapsedRealtime())
                    .put("today", LocalDate.now().toString()).put("zone", calendarBounded(ZoneId.systemDefault().getId(), 128))
                    .put("scoped_mode", DeviceRolePreferences.get(context).name())
                    .put("activity_mode", DeviceRolePreferences.get(activity).name())
                    .put("source_accessible", new LocalDailyStatsSource(context).accessible())
                    .put("workspace", AchievementRole.current(context).name())
                    .put("dom_unlocked", ControllerPinManager.isDomWorkspaceUnlocked())
                    .put("activity_state", activity instanceof androidx.lifecycle.LifecycleOwner
                            ? ((androidx.lifecycle.LifecycleOwner) activity).getLifecycle().getCurrentState().name() : "no LifecycleOwner")
                    .put("window_focus", activity.hasWindowFocus()).put("panel_present", panel != null)
                    .put("panel_children", panel == null ? 0 : panel.getChildCount())
                    .put("actual_range", state.getInt("daily_range", -1))
                    .put("actual_year", state.getInt("daily_year", -1)).put("actual_month", state.getInt("daily_month", -1))
                    .put("actual_selected", calendarBounded(state.getString("daily_selected", "absent"), 64))
                    .put("view_text_chars", observed.length()).put("view_text_truncated", observed.length() > 4096)
                    .put("view_texts", calendarBounded(observed, 4096));
            if (panel != null) {
                result.put("unavailable_label", hasCalendarText(panel, activity.getString(com.subhub.app.R.string.daily_unavailable)));
                try {
                    Field work = DailyStatsPanel.class.getDeclaredField("uiData"), key = DailyStatsPanel.class.getDeclaredField("taskKey");
                    work.setAccessible(true); key.setAccessible(true);
                    result.put("async_pending", ((com.subhub.app.util.AsyncUiScope) work.get(panel)).isPending((String) key.get(panel)));
                } catch (Exception unavailable) { result.put("async_observation_error", unavailable.getClass().getName()); }
            }
            return result;
        } catch (org.json.JSONException invalid) { throw new AssertionError("Cannot record calendar observation", invalid); }
    }
    private static String calendarBounded(String value, int limit) {
        return value.substring(0, Math.min(value.length(), limit));
    }
    private static boolean hasCalendarText(android.view.View view, String expected) {
        if (view instanceof android.widget.TextView && expected.contentEquals(((android.widget.TextView)view).getText())) return true;
        if (view instanceof android.view.ViewGroup) for (int i = 0; i < ((android.view.ViewGroup)view).getChildCount(); i++)
            if (hasCalendarText(((android.view.ViewGroup)view).getChildAt(i), expected)) return true;
        return false;
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
        @Override public android.content.pm.ApplicationInfo getApplicationInfo() {
            android.content.pm.ApplicationInfo info = new android.content.pm.ApplicationInfo(super.getApplicationInfo());
            info.dataDir += "/" + prefix; return info;
        }
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
