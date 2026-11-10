package com.subhub.app.stats;

import static org.junit.Assert.*;

import android.content.*;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.people.*;
import com.subhub.app.relationships.*;
import com.subhub.app.remote.*;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.SettingsRepository;
import java.io.File;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.json.JSONObject;
import org.junit.*;

/** Native SQLite projection checks; synthetic eligible edges represent the provider contract. */
public final class DomSubjectScopeStatsAndroidTest {
    @Rule public final com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture();
    private static final String DAY = "2025-12-31";
    private Scoped context;
    private DailyStatsStore daily, previousDaily;
    private Field dailyField;
    private RemoteStore store;
    private RelationshipUi.Provider previousProvider;
    private final Projection provider = new Projection();

    @Before public void setup() throws Exception {
        assertFalse(com.subhub.app.service.ScreenCaptureService.isRunning());
        assertFalse(com.subhub.app.service.ScreenshotAccessibilityService.isRunning());
        context = new Scoped(InstrumentationRegistry.getInstrumentation().getTargetContext());
        assertTrue(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                .putString(com.subhub.app.security.DeviceRolePreferences.KEY_MODE, "BOTH").commit());
        ControllerPinManager.enterDomMode();
        previousProvider = RelationshipUi.provider(); RelationshipUi.install(provider);
        DailyStatsStore.awaitWrites();
        dailyField = DailyStatsStore.class.getDeclaredField("instance"); dailyField.setAccessible(true);
        previousDaily = (DailyStatsStore) dailyField.get(null);
        daily = new DailyStatsStore(context, "daily_statistics.db"); dailyField.set(null, daily);
        daily.getWritableDatabase().execSQL("INSERT INTO daily(day,metric,value) VALUES(?,'service_ms',3600000)", new Object[]{DAY});
        daily.getWritableDatabase().execSQL("INSERT INTO daily(day,metric,value) VALUES(?,'sessions',1)", new Object[]{DAY});
        long wall = Instant.parse(DAY + "T12:00:00Z").toEpochMilli();
        assertTrue(context.getSharedPreferences(StatsRepository.PREFS_NAME, 0).edit()
                .putString("session_history", wall + ",3600,100").putLong("total_blocks_all_time", 100).commit());
        store = new RemoteStore(context);
        store.getWritableDatabase();
    }

    @After public void restore() throws Exception {
        try {
            if (previousProvider != null) RelationshipUi.install(previousProvider);
            DailyStatsStore.awaitWrites();
        } finally {
            try { if (dailyField != null) dailyField.set(null, previousDaily); }
            finally {
                try { if (daily != null) daily.close(); }
                finally {
                    try { if (store != null) store.close(); }
                    finally { if (context != null) context.clean(); }
                }
            }
        }
    }

    @Test public void unlinkedLocalContributesNoDomCountDailyOrAchievementsButOwnHistoryRemains() throws Exception {
        String before = retained();
        DomStatsRepository.Loaded result = new DomStatsRepository(context).loadWithHistory();
        assertEquals(0, result.stats.subs); assertEquals(0, result.stats.activeSubs);
        assertEquals(0, result.stats.sessions); assertEquals(0, result.stats.sessionSeconds);
        assertNull(result.local); assertTrue(result.histories.isEmpty());
        assertEquals(Long.valueOf(0), new DomDailyStatsSource(context).load(DAY, DAY).days.get(DAY).get("service_ms"));
        AchievementManager dom = AchievementManager.forWorkspace(context);
        HistoricalAchievementBackfill.dom(context, dom, result);
        assertFalse(dom.isUnlocked("dom_subs_1")); assertFalse(dom.isUnlocked("dom_time_1hr"));
        assertFalse(new AchievementManager(context).isUnlocked("first_session"));
        assertFalse(new AchievementManager(context).isUnlocked("blocks_10"));
        assertEquals(before, retained());

        LocalRetainedStatistics own = LocalRetainedStatistics.load(context);
        assertEquals(100, own.stats.getTotalBlocks()); assertEquals(3600, own.stats.getTotalSessionSeconds());
        assertEquals(1, own.history.sessions().size());
        ControllerPinManager.enterSubMode();
        assertEquals(Long.valueOf(3600000), new LocalDailyStatsSource(context).load(DAY, DAY).days.get(DAY).get("service_ms"));
        assertEquals(before, retained());
    }

    @Test public void localApprovedProjectionCountsFullHistoryAndRevocationExcludesItWithoutDeletingData() throws Exception {
        provider.edges = Collections.singletonList(local("approved-generation"));
        String before = retained();
        DomStatsRepository.Loaded result = new DomStatsRepository(context).loadWithHistory();
        assertEquals(1, result.stats.subs); assertEquals(3600, result.stats.sessionSeconds); assertEquals(1, result.stats.sessions);
        assertEquals("approved-generation", result.histories.get(0).identity.generation);
        assertEquals(Long.valueOf(3600000), new DomDailyStatsSource(context).load(DAY, DAY).days.get(DAY).get("service_ms"));
        HistoricalAchievementBackfill.dom(context, AchievementManager.forWorkspace(context), result);
        assertFalse("Dom history must not backfill the Sub catalog", new AchievementManager(context).isUnlocked("first_session"));
        assertEquals(before, retained());
        provider.edges = Collections.emptyList();
        assertThrows(IllegalStateException.class, result.scope::revalidate);
        assertThrows(Exception.class, () -> HistoricalAchievementBackfill.dom(context, AchievementManager.forWorkspace(context), result));
        assertEquals(0, new DomStatsRepository(context).load().subs);
        assertEquals(Long.valueOf(0), new DomDailyStatsSource(context).load(DAY, DAY).days.get(DAY).get("service_ms"));
        assertEquals(before, retained());
    }

    @Test public void offlinePeerRemainsEligibleAndOtherGenerationsOrRevokedPeersDoNotContribute() throws Exception {
        String peer = String.join("", Collections.nCopies(64, "c"));
        store.getWritableDatabase().execSQL("INSERT INTO peers(peer,generation,name,local_role,state) VALUES(?,?,?,'DOM','ACTIVE')",
                new Object[]{peer, "approved", "Offline synthetic Sub"});
        DomScopeObserverFixture.mapOwnedPeer(context, store, peer, "approved");
        for (String generation : new String[]{"approved", "unapproved"}) {
            JSONObject day = new JSONObject().put("schema", 1).put("day", DAY)
                    .put("metrics", new JSONObject().put("service_ms", 60000).put("sessions", 1));
            store.getWritableDatabase().execSQL("INSERT INTO remote_objects(peer,generation,stream,item,seq,body,deleted,observed) VALUES(?,?,'DAILY',?,1,?,0,0)",
                    new Object[]{peer, generation, DAY, day.toString()});
        }
        PersonId approved = RemotePersonRepository.id(store.peer(peer, "approved"));
        provider.edges = Collections.singletonList(new RelationshipUi.Edge("peer-link", "formal-generation", "dom", "sub",
                RelationshipUi.Delivery.PEER, "ACTIVE", 1, approved,
                RelationshipUi.Availability.OFFLINE, PersonSnapshot.Freshness.STALE));
        String before = retained();
        DomStatsSnapshot stats = new DomStatsRepository(context).load(store, System.currentTimeMillis());
        assertEquals(1, stats.subs); assertEquals(1, stats.sessions); assertEquals(60, stats.sessionSeconds);
        assertEquals(Long.valueOf(60000), new DomDailyStatsSource(context).load(store, DAY, DAY).days.get(DAY).get("service_ms"));
        assertEquals(before, retained());
        store.getWritableDatabase().execSQL("UPDATE peers SET state='REVOKED' WHERE generation='approved'");
        assertEquals(0, new DomStatsRepository(context).load(store, System.currentTimeMillis()).subs);
        assertEquals(Long.valueOf(0), new DomDailyStatsSource(context).load(store, DAY, DAY).days.get(DAY).get("service_ms"));
    }

    @Test public void sameProviderRelinkDuringReadIsRejectedOutsideLocks() throws Exception {
        provider.edges = Collections.singletonList(local("first"));
        provider.changeAfterSnapshot = () -> provider.edges = Collections.singletonList(local("replacement"));
        assertThrows(IllegalStateException.class, () -> new DomStatsRepository(context).load());
        provider.edges = Collections.singletonList(local("first"));
        provider.changeAfterSnapshot = () -> provider.edges = Collections.emptyList();
        assertThrows(IllegalStateException.class, () -> new DomDailyStatsSource(context).load(DAY, DAY));
        RelationshipUi.install(RelationshipUi.unavailable());
        assertThrows(IllegalStateException.class, () -> new DomStatsRepository(context).load());
        assertEquals(1, LocalRetainedStatistics.load(context).history.sessions().size());
    }

    @Test public void legacyRosterOmitsLocalWithoutRemovingGlobalSelfIdentity() throws Exception {
        RelationshipUi.install(RelationshipUi.unavailable());
        PersonRepository repository = People.repository(context);
        assertTrue(repository.people().contains(PersonId.LOCAL));
        assertNotNull(repository.load(PersonId.LOCAL));
        assertTrue(RelationshipPanel.roster(context, repository, RelationshipPanel.readAccess(context), Locale.ROOT).isEmpty());
        assertEquals(0, new DomStatsRepository(context).load().subs);
    }

    private static RelationshipUi.Edge local(String generation) {
        return new RelationshipUi.Edge("approved-local", generation, "dom", "sub", RelationshipUi.Delivery.LOCAL,
                "ACTIVE", 1, PersonId.LOCAL, RelationshipUi.Availability.UNKNOWN, PersonSnapshot.Freshness.UNKNOWN);
    }

    private final class Projection implements RelationshipUi.Provider {
        volatile List<RelationshipUi.Edge> edges = Collections.emptyList();
        Runnable changeAfterSnapshot;
        public CompletableFuture<RelationshipUi.Snapshot> snapshot() {
            assertFalse(Thread.holdsLock(store)); assertFalse(store.getReadableDatabase().inTransaction());
            assertFalse(Thread.holdsLock(com.subhub.app.security.SubFeaturePolicy.LOCK));
            assertFalse(Thread.holdsLock(com.subhub.app.appmode.RuntimeSessionIdentity.LOCK));
            RelationshipUi.Snapshot value = new RelationshipUi.Snapshot(true, UUID.randomUUID().toString(),
                    Collections.emptyList(), edges, null, Collections.emptyList());
            Runnable change = changeAfterSnapshot; changeAfterSnapshot = null;
            if (change != null) change.run();
            return CompletableFuture.completedFuture(value);
        }
        public CompletableFuture<RelationshipUi.LocalReview> prepareLocal(String k, String d, String s, String r) { throw new AssertionError(); }
        public CompletableFuture<RelationshipUi.Operation> confirmLocal(String i, String d) { throw new AssertionError(); }
        public CompletableFuture<RelationshipUi.Operation> resume(String i) { throw new AssertionError(); }
        public CompletableFuture<RelationshipUi.Operation> cancel(String i) { throw new AssertionError(); }
    }

    private String retained() {
        StringBuilder value = new StringBuilder(new TreeMap<>(context.getSharedPreferences(StatsRepository.PREFS_NAME, 0).getAll()).toString());
        value.append(daily.days("0000-01-01", "9999-12-31"));
        for (String table : new String[]{"remote_objects", "activity_source", "activity_receipts", "activity_state", "outbox", "incoming", "outgoing"})
            try (Cursor rows = store.getReadableDatabase().rawQuery("SELECT * FROM " + table + " ORDER BY rowid", null)) {
                while (rows.moveToNext()) for (int i = 0; i < rows.getColumnCount(); i++) value.append(rows.getString(i)).append('|');
            }
        return value.toString();
    }

    private static final class Scoped extends ContextWrapper {
        private final String prefix = "dom-scope-" + UUID.randomUUID() + "-";
        private final Set<String> databases = new LinkedHashSet<>(), preferences = new LinkedHashSet<>();
        Scoped(Context context) { super(context); }
        @Override public Context getApplicationContext() { return this; }
        @Override public ApplicationInfo getApplicationInfo() {
            ApplicationInfo info = new ApplicationInfo(super.getApplicationInfo()); info.dataDir += "/" + prefix; return info;
        }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) { preferences.add(name); return super.getSharedPreferences(prefix + name, mode); }
        @Override public File getDatabasePath(String name) { databases.add(name); return super.getDatabasePath(prefix + name); }
        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) { databases.add(name); return super.openOrCreateDatabase(prefix + name, mode, factory); }
        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler handler) { databases.add(name); return super.openOrCreateDatabase(prefix + name, mode, factory, handler); }
        @Override public ComponentName startService(Intent intent) { throw new AssertionError("Unexpected service"); }
        @Override public ComponentName startForegroundService(Intent intent) { throw new AssertionError("Unexpected foreground service"); }
        void clean() {
            for (String name : databases) if (super.getDatabasePath(prefix + name).isFile()) assertTrue(super.deleteDatabase(prefix + name));
            for (String name : preferences) assertTrue(super.getSharedPreferences(prefix + name, 0).edit().clear().commit());
        }
    }
}
