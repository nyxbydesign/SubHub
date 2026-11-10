package com.subhub.app.remote;

import static org.junit.Assert.*;
import android.content.Context;
import android.database.Cursor;
import com.subhub.app.security.SubFeaturePolicy;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Owns its database and inert transport; the application's endpoint and receipts remain intact. */
public final class DummyPreviewEndpointFixture implements AutoCloseable {
    private final Context context;
    private final RemoteFeature original, fixture;
    private final Field singleton, endpoint;
    private final AtomicInteger leases;
    private final AtomicInteger transportCalls = new AtomicInteger();
    private final String database = "dummy-preview-" + UUID.randomUUID() + ".db";
    private final PeerTransport transport = new PeerTransport() {
        public String localIdentity() { transportCalls.incrementAndGet(); return new String(new char[64]).replace('\0', 'a'); }
        public String inviteRoute() throws IOException { transportCalls.incrementAndGet(); throw new IOException("Owned preview fixture is offline"); }
        public Connection connect(String peer, String route, long timeout) throws IOException { transportCalls.incrementAndGet(); throw new IOException("No network in preview fixture"); }
        public Connection accept(long timeout) throws IOException { transportCalls.incrementAndGet(); throw new IOException("No network in preview fixture"); }
        public void close() { }
    };
    private final Map<String, List<String>> originalRows;
    public DummyPreviewEndpointFixture(Context context) throws Exception {
        this.context = context;
        original = RemoteFeature.get(context);
        originalRows = rows(original.store);
        fixture = new RemoteFeature(context, new RemoteStore(context, database), new RemoteSecrets(context),
                PairingLifecycleClock.source(context), new RemoteFeature.BackgroundScheduling() {
                    public void synchronize(Context selected, boolean needed) { }
                    public void kick(Context selected) { }
                });
        assertTrue(fixture.recoveryReady());
        installWriteAudit();
        Field leaseField = RemoteFeature.class.getDeclaredField("leases"); leaseField.setAccessible(true);
        leases = (AtomicInteger) leaseField.get(fixture); assertEquals(1, leases.incrementAndGet());
        endpoint = RemoteFeature.class.getDeclaredField("endpoint"); endpoint.setAccessible(true); endpoint.set(fixture, transport);
        singleton = RemoteFeature.class.getDeclaredField("instance"); singleton.setAccessible(true);
        synchronized (RemoteFeature.class) { assertSame(original, singleton.get(null)); singleton.set(null, fixture); }
        synchronized (SubFeaturePolicy.LOCK) {
            SubFeaturePolicy.unregisterHardcoreAuthority(context, original.store);
            SubFeaturePolicy.registerHardcoreAuthority(context, fixture.store);
        }
    }
    /** SQLite triggers count actual writes, including a write later undone by another write. */
    private void installWriteAudit() {
        android.database.sqlite.SQLiteDatabase db = fixture.store.getWritableDatabase();
        List<String> tables = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null)) {
            while (cursor.moveToNext()) tables.add(cursor.getString(0));
        }
        db.execSQL("CREATE TABLE preview_write_audit (operation TEXT NOT NULL)");
        for (String table : tables) {
            assertTrue(table.matches("[A-Za-z0-9_]+"));
            for (String operation : new String[]{"INSERT", "UPDATE", "DELETE"})
                db.execSQL("CREATE TRIGGER preview_audit_" + table + "_" + operation
                        + " AFTER " + operation + " ON \"" + table + "\" BEGIN INSERT INTO preview_write_audit VALUES ('"
                        + operation + "'); END");
        }
    }
    public int transportCalls() { return transportCalls.get(); }
    public long writeCount() {
        try (Cursor cursor = fixture.store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM preview_write_audit", null)) {
            assertTrue(cursor.moveToFirst()); return cursor.getLong(0);
        }
    }
    /** A direct preview must never acquire an endpoint lease or submit endpoint work. */
    public long completedEndpointWork() throws Exception {
        List<java.util.concurrent.ThreadPoolExecutor> pools = new ArrayList<>();
        for (String name : new String[]{"operations", "sessions"}) {
            Field field = RemoteFeature.class.getDeclaredField(name); field.setAccessible(true);
            pools.add((java.util.concurrent.ThreadPoolExecutor) field.get(fixture));
        }
        long deadline = android.os.SystemClock.uptimeMillis() + 5000;
        while ((leases.get() != 1 || pools.stream().anyMatch(pool -> pool.getActiveCount() != 0))
                && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(20);
        long completed = 0;
        for (java.util.concurrent.ThreadPoolExecutor pool : pools) {
            assertEquals("No preview endpoint work may be running", 0, pool.getActiveCount());
            assertEquals("No preview endpoint work may be queued", 0, pool.getQueue().size());
            completed += pool.getCompletedTaskCount();
        }
        assertEquals("Only the fixture owns an endpoint lease", 1, leases.get());
        assertSame("No replacement transport was opened", transport, endpoint.get(fixture));
        assertSame("Preview must not replace the application singleton", fixture, singleton.get(null));
        return completed;
    }
    public Map<String, List<String>> snapshot() throws Exception {
        fixture.submit(() -> null).get(10, TimeUnit.SECONDS);
        assertSame(transport, endpoint.get(fixture));
        return rows(fixture.store);
    }
    private static Map<String, List<String>> rows(RemoteStore store) {
        Map<String, List<String>> result = new TreeMap<>();
        try (Cursor tables = store.getReadableDatabase().rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", null)) {
            while (tables.moveToNext()) {
                String table = tables.getString(0); assertTrue(table.matches("[A-Za-z0-9_]+"));
                List<String> contents = new ArrayList<>();
                try (Cursor row = store.getReadableDatabase().rawQuery("SELECT * FROM \"" + table + "\"", null)) {
                    while (row.moveToNext()) {
                        StringBuilder value = new StringBuilder();
                        for (int i = 0; i < row.getColumnCount(); i++) {
                            String cell = row.isNull(i) ? "NULL" : row.getType(i) == Cursor.FIELD_TYPE_BLOB
                                    ? android.util.Base64.encodeToString(row.getBlob(i), android.util.Base64.NO_WRAP) : row.getString(i);
                            value.append(cell.length()).append(':').append(cell).append(';');
                        }
                        contents.add(value.toString());
                    }
                }
                Collections.sort(contents); result.put(table, contents);
            }
        }
        return result;
    }
    @Override public void close() throws Exception {
        try {
            synchronized (RemoteFeature.class) { assertSame(fixture, singleton.get(null)); singleton.set(null, original); }
        } finally {
            try {
                synchronized (SubFeaturePolicy.LOCK) {
                    SubFeaturePolicy.unregisterHardcoreAuthority(context, fixture.store);
                    SubFeaturePolicy.registerHardcoreAuthority(context, original.store);
                }
            } finally {
                try {
                    fixture.submit(() -> null).get(10, TimeUnit.SECONDS);
                    long end = android.os.SystemClock.uptimeMillis() + 5000;
                    while (leases.get() != 1 && android.os.SystemClock.uptimeMillis() < end) Thread.sleep(20);
                    assertEquals(0, leases.decrementAndGet());
                } finally {
                    fixture.shutdownForTest(); assertTrue(context.deleteDatabase(database));
                }
            }
        }
        assertSame(original, RemoteFeature.get(context));
        Field sessions = RemoteFeature.class.getDeclaredField("sessions"); sessions.setAccessible(true);
        assertFalse(((java.util.concurrent.ThreadPoolExecutor) sessions.get(original)).isShutdown());
        assertTrue("No original endpoint rows changed", originalRows.equals(rows(original.store)));
    }
}
