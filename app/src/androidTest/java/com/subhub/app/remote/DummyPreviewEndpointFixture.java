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
    private final String database = "dummy-preview-" + UUID.randomUUID() + ".db";
    private final PeerTransport transport = new PeerTransport() {
        public String localIdentity() { return new String(new char[64]).replace('\0', 'a'); }
        public String inviteRoute() throws IOException { throw new IOException("Owned preview fixture is offline"); }
        public Connection connect(String peer, String route, long timeout) throws IOException { throw new IOException("No network in preview fixture"); }
        public Connection accept(long timeout) throws IOException { throw new IOException("No network in preview fixture"); }
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
