package com.subhub.app.stats;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.time.*;
import java.util.*;

/** Transactional daily aggregates. Cumulative checkpoints and export receipts make replay harmless. */
public final class DailyStatsStore extends SQLiteOpenHelper {
    public static final String STAMP = "daily_event_wall_v1", ZONE = "daily_event_zone_v1";
    private static DailyStatsStore instance;
    private final Context context;
    private final Map<String, Map<String,Long>> cachedTotals=new HashMap<>();
    private String importedHistory;
    private static final java.util.concurrent.ExecutorService counterWriter = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "subhub-daily-counters"));
    public void queueStats(SharedPreferences prefs) {
        Map<String, ?> snapshot = prefs.getAll();
        counterWriter.execute(() -> {
            try { syncStatsSnapshot(snapshot); }
            catch (RuntimeException unavailable) { android.util.Log.w("DailyStats", "Counter checkpoint pending"); }
        });
    }
    public static void awaitWrites() throws Exception { counterWriter.submit(() -> {}).get(10, java.util.concurrent.TimeUnit.SECONDS); }

    public static synchronized DailyStatsStore get(Context context) {
        if (instance == null) instance = new DailyStatsStore(context, "daily_statistics.db");
        return instance;
    }
    DailyStatsStore(Context context, String name) { super(context.getApplicationContext(), name, null, 1); this.context = context.getApplicationContext(); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE daily(day TEXT NOT NULL, metric TEXT NOT NULL, value INTEGER NOT NULL, PRIMARY KEY(day,metric))");
        db.execSQL("CREATE TABLE checkpoint(name TEXT PRIMARY KEY, value INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE receipts(id TEXT PRIMARY KEY)");
        db.execSQL("CREATE TABLE clock(id INTEGER PRIMARY KEY, process TEXT, mono INTEGER, wall INTEGER, zone TEXT, session INTEGER)");
        db.execSQL("CREATE TABLE metadata(name TEXT PRIMARY KEY,value TEXT)");
        db.execSQL("INSERT INTO metadata VALUES('since',?)", new Object[] {LocalDate.now().toString()});
        db.execSQL("INSERT INTO metadata VALUES('collection_started_ms',?)", new Object[] {Long.toString(System.currentTimeMillis())});
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { throw new IllegalStateException("Unsupported daily statistics schema"); }
    public synchronized String since() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT value FROM metadata WHERE name='since'", null)) { return c.moveToFirst() ? c.getString(0) : LocalDate.now().toString(); }
    }
    public synchronized Map<String, Map<String, Long>> days(String first, String last) {
        Map<String, Map<String, Long>> result = new LinkedHashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT day,metric,value FROM daily WHERE day>=? AND day<=? ORDER BY day", new String[]{first,last})) {
            while (c.moveToNext()) result.computeIfAbsent(c.getString(0), key -> new LinkedHashMap<>()).put(c.getString(1), c.getLong(2));
        }
        return result;
    }
    /** An absent checkpoint is migration, not evidence that old totals happened today. */
    synchronized void checkpoint(String source, Map<String, Long> totals, long wall, String zone) {
        if(totals.equals(cachedTotals.get(source)))return;
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            String day = Instant.ofEpochMilli(wall).atZone(ZoneId.of(zone)).toLocalDate().toString();
            for (Map.Entry<String, Long> entry : totals.entrySet()) {
                String key = source + ":" + entry.getKey(); long value = Math.max(0, entry.getValue());
                long previous = -1;
                try (Cursor c = db.rawQuery("SELECT value FROM checkpoint WHERE name=?", new String[]{key})) { if (c.moveToFirst()) previous = c.getLong(0); }
                // Ignore stale snapshots; never replay an already committed counter range.
                if (previous >= 0 && value > previous) add(db, day, entry.getKey(), value - previous);
                if (value > previous) db.execSQL("INSERT OR REPLACE INTO checkpoint VALUES(?,?)", new Object[]{key,value});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        cachedTotals.put(source,new LinkedHashMap<>(totals));
    }
    public void syncStats(SharedPreferences prefs) { syncStatsSnapshot(prefs.getAll()); }
    private void syncStatsSnapshot(Map<String, ?> raw) {
        Map<String, Long> totals = new LinkedHashMap<>();
        String[][] keys = {{"censors","total_blocks_all_time"},{"limits_ms","limited_app_millis"},{"limit_stops","limit_interventions"},
                {"whispers","subliminal_impressions"},{"popups","popup_impressions"},{"assessed_EUR","tribute_cents"},
                {"assessed_USD","tribute_cents_USD"},{"tributes","tribute_events"},{"sessions","sessions_count"}};
        for (String[] key : keys) { Object value = raw.get(key[1]); totals.put(key[0], value instanceof Number ? ((Number) value).longValue() : 0); }
        Object stamp = raw.get(STAMP); Object zone = raw.get(ZONE);
        String recordedZone = zone instanceof String ? (String) zone : ZoneId.systemDefault().getId();
        Object history = raw.get("session_history");
        if (history instanceof String) backfillSessions((String) history, recordedZone);
        checkpoint("stats", totals, stamp instanceof Long ? (Long) stamp : System.currentTimeMillis(), recordedZone);
    }
    /** Import only the saved portion before daily collection, never replaying live aggregates. */
    synchronized void backfillSessions(String history, String zone) {
        if (history.equals(importedHistory)) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String cutoffValue = metadata(db, "legacy_cutoff_ms");
            String importZone = metadata(db, "legacy_zone");
            if (cutoffValue == null) {
                cutoffValue = metadata(db, "collection_started_ms");
                // Older daily databases did not keep an exact collection timestamp. Their
                // first recorded midnight is the conservative boundary for avoiding overlap.
                if (cutoffValue == null)
                    cutoffValue =
                            Long.toString(
                                    LocalDate.parse(metadata(db, "since"))
                                            .atStartOfDay(ZoneId.of(zone))
                                            .toInstant()
                                            .toEpochMilli());
                importZone = zone;
                db.execSQL(
                        "INSERT INTO metadata VALUES('legacy_cutoff_ms',?)",
                        new Object[] {cutoffValue});
                db.execSQL(
                        "INSERT INTO metadata VALUES('legacy_zone',?)", new Object[] {importZone});
            }
            long cutoff = Long.parseLong(cutoffValue);
            ZoneId localZone = ZoneId.of(importZone);
            for (StatsRepository.SessionEntry session :
                    StatsRepository.parseSessionHistory(history)) {
                long start = session.getStartMillis(), seconds = session.getDurationSeconds();
                if (start >= cutoff || seconds > (Long.MAX_VALUE - start) / 1000) continue;
                long end = start + seconds * 1000;
                ContentValues receipt = new ContentValues();
                receipt.put("id", "legacy-session:" + start);
                if (db.insertWithOnConflict(
                                "receipts", null, receipt, SQLiteDatabase.CONFLICT_IGNORE)
                        == -1) continue;
                for (Map.Entry<String, Long> slice :
                        DailyTimeSlices.split(start, Math.min(end, cutoff) - start, localZone)
                                .entrySet())
                    add(db, slice.getKey(), "service_ms", slice.getValue());
                String day = Instant.ofEpochMilli(start).atZone(localZone).toLocalDate().toString();
                add(db, day, "sessions", 1);
                // Legacy counters have a session timestamp, but no individual event times.
                // A session that crosses the collection boundary cannot safely add counters.
                if (end <= cutoff) {
                    legacyValue(db, day, "censors", session.getBlocks());
                    legacyValue(db, day, "limits_ms", session.getLimitedAppMillis());
                    legacyValue(db, day, "limit_stops", session.getLimitInterventions());
                    legacyValue(db, day, "tributes", session.getTributeEvents());
                    legacyValue(db, day, "assessed_EUR", session.getTributeCents());
                    legacyValue(db, day, "whispers", session.getSubliminals());
                    legacyValue(db, day, "popups", session.getPopupImpressions());
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        importedHistory = history;
    }

    private static String metadata(SQLiteDatabase db, String name) {
        try (Cursor c =
                db.rawQuery("SELECT value FROM metadata WHERE name=?", new String[] {name})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    private static void legacyValue(SQLiteDatabase db, String day, String metric, long value) {
        if (value > 0) add(db, day, metric, value);
    }

    public void syncWallet() {
        com.subhub.app.penance.PenanceManager wallet = new com.subhub.app.penance.PenanceManager(context);
        Map<String, Long> totals = new LinkedHashMap<>();
        totals.put("paid_EUR", wallet.getTotalPaidCents("EUR")); totals.put("paid_USD", wallet.getTotalPaidCents("USD"));
        SharedPreferences prefs = context.getSharedPreferences(com.subhub.app.penance.PenanceManager.PREFS_NAME, 0);
        checkpoint("wallet", totals, prefs.getLong(STAMP, System.currentTimeMillis()), prefs.getString(ZONE, ZoneId.systemDefault().getId()));
    }
    public synchronized void exported(String receipt, String metric, long wall, String zone) {
        if (wall <= 0 || !(metric.equals("photos") || metric.equals("videos"))) return;
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            ContentValues id = new ContentValues(); id.put("id", receipt);
            if (db.insertWithOnConflict("receipts", null, id, SQLiteDatabase.CONFLICT_IGNORE) != -1)
                add(db, Instant.ofEpochMilli(wall).atZone(ZoneId.of(zone)).toLocalDate().toString(), metric, 1);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    /** Elapsed realtime prevents clock changes from adding time; process identity excludes downtime. */
    synchronized void heartbeat(String process, long session, long wall, long mono, String zone) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            try (Cursor c = db.rawQuery("SELECT process,mono,wall,zone,session FROM clock WHERE id=1", null)) {
                boolean exists = c.moveToFirst();
                if (exists && process.equals(c.getString(0)) && mono < c.getLong(1)) { db.setTransactionSuccessful(); return; }
                if (exists && process.equals(c.getString(0)) && c.getLong(4) > 0
                        && (session == c.getLong(4) || session == 0) && mono >= c.getLong(1)) {
                    for (Map.Entry<String, Long> part : DailyTimeSlices.split(c.getLong(2), mono - c.getLong(1), ZoneId.of(c.getString(3))).entrySet())
                        add(db, part.getKey(), "service_ms", part.getValue());
                }
            }
            db.execSQL("INSERT OR REPLACE INTO clock VALUES(1,?,?,?,?,?)", new Object[]{process,mono,wall,zone,session});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    private static void add(SQLiteDatabase db, String day, String metric, long amount) {
        db.execSQL("UPDATE metadata SET value=? WHERE name='since' AND value>?",new Object[]{day,day});
        db.execSQL("INSERT OR IGNORE INTO daily VALUES(?,?,0)", new Object[]{day,metric});
        db.execSQL("UPDATE daily SET value=CASE WHEN value>? THEN ? ELSE value+? END WHERE day=? AND metric=?",
                new Object[]{Long.MAX_VALUE-amount,Long.MAX_VALUE,amount,day,metric});
    }
}
