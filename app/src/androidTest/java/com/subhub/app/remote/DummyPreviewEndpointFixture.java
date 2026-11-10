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
    private final RemoteFeature original;
    private RemoteFeature fixture;
    private RemoteStore store;
    private FormalRelationshipProvider relationships;
    private LinkingWitness witness;
    private com.subhub.app.util.RemoteAppIcons icons;
    private Runnable iconChanged;
    private final Map<Field, Object> originalBindings = new LinkedHashMap<>();
    private final Map<Field, Object> ownedBindings = new LinkedHashMap<>();
    private Field singleton, endpoint;
    private AtomicInteger leases;
    private boolean leased, singletonPublished, closed;
    private final RemoteStore originalAuthority;
    private final AtomicInteger transportCalls = new AtomicInteger();
    private String database;
    private final PeerTransport transport = new PeerTransport() {
        public String localIdentity() { transportCalls.incrementAndGet(); return new String(new char[64]).replace('\0', 'a'); }
        public String inviteRoute() throws IOException { transportCalls.incrementAndGet(); throw new IOException("Owned preview fixture is offline"); }
        public Connection connect(String peer, String route, long timeout) throws IOException { transportCalls.incrementAndGet(); throw new IOException("No network in preview fixture"); }
        public Connection accept(long timeout) throws IOException { transportCalls.incrementAndGet(); throw new IOException("No network in preview fixture"); }
        public void close() { }
    };
    private final Map<String, List<String>> originalRows;
    public DummyPreviewEndpointFixture(Context context) throws Exception { this(context, fixture -> {}); }

    /** Failure injection is confined to the test fixture, after the actual adapters are published. */
    DummyPreviewEndpointFixture(Context context, java.util.function.Consumer<DummyPreviewEndpointFixture> afterBindings) throws Exception {
        this.context = context;
        original = RemoteFeature.get(context);
        originalRows = rows(original.store);
        originalAuthority = registeredAuthority();
        try {
            singleton = field(RemoteFeature.class, "instance"); endpoint = field(RemoteFeature.class, "endpoint");
            for (String name : new String[]{"remote", "remoteAuthority", "connectionUi", "localProjection", "keyholder"})
                capture(com.subhub.app.people.People.class, name);
            capture(com.subhub.app.relationships.RelationshipUi.class, "provider");
            capture(com.subhub.app.activity.ActivitySources.class, "repository");
            capture(RemoteActivityRecorder.class, "installed");
            capture(RemoteUi.class, "iconProvider"); capture(RemoteUi.class, "iconBinder");
            store = FormalRelationshipCoordinator.createOwnedFixture(context); database = store.getDatabaseName();
            FormalRelationshipCoordinator.requireOwnedFixture(store);
            assertFalse("The fixture must never own the application database", store.isApplicationDatabase());
            fixture = new RemoteFeature(context, store, new RemoteSecrets(context),
                    PairingLifecycleClock.source(context), new RemoteFeature.BackgroundScheduling() {
                        public void synchronize(Context selected, boolean needed) { }
                        public void kick(Context selected) { }
                    });
            assertTrue(fixture.recoveryReady());
            leases = (AtomicInteger) field(RemoteFeature.class, "leases").get(fixture);
            leases.incrementAndGet(); leased = true;
            assertEquals(1, leases.get()); endpoint.set(fixture, transport);
            synchronized (RemoteFeature.class) {
                assertSame(original, singleton.get(null)); singleton.set(null, fixture); singletonPublished = true;
            }
            synchronized (SubFeaturePolicy.LOCK) {
                if (originalAuthority != null) SubFeaturePolicy.unregisterHardcoreAuthority(context, originalAuthority);
                SubFeaturePolicy.registerHardcoreAuthority(context, store);
            }
            relationships = new FormalRelationshipProvider(context, store);
            witness = LinkingWitness.attachedWithoutIo(store);
            RemotePersonRepository repository = new RemotePersonRepository(context, fixture);
            com.subhub.app.people.People.installRemoteRepository(repository);
            com.subhub.app.people.People.installRemoteAuthority(fixture::controlsLocal);
            com.subhub.app.people.People.installLocalProjection(repository::localProjection);
            com.subhub.app.people.People.installKeyholder(RemoteUi.keyholderProvider(fixture));
            com.subhub.app.people.People.installConnectionUi(new RemoteUi());
            com.subhub.app.relationships.RelationshipUi.install(relationships);
            // Publish the real repositories without reinstalling application lifecycle callbacks.
            field(com.subhub.app.activity.ActivitySources.class, "repository").set(null, fixture.activity);
            RemoteActivityRecorder.install(fixture.activity);
            iconChanged = (Runnable) field(RemoteUi.class, "iconChanged").get(null);
            main(() -> {
                icons = new com.subhub.app.util.RemoteAppIcons(fixture.appIcons);
                field(RemoteUi.class, "iconProvider").set(null, fixture.appIcons);
                field(RemoteUi.class, "iconBinder").set(null, icons);
                fixture.appIcons.addInvalidationListener(iconChanged);
            });
            for (Field binding : originalBindings.keySet()) ownedBindings.put(binding, binding.get(null));
            assertOwnedBindings(); afterBindings.accept(this);
            // Ordinary roster scope establishment belongs to setup, never to preview assertions.
            assertTrue(relationships.snapshot().get(15, TimeUnit.SECONDS).supported);
            fixture.submit(() -> null).get(10, TimeUnit.SECONDS);
            installWriteAudit();
        } catch (Exception | Error failure) {
            try { close(); } catch (Exception | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    private static Field field(Class<?> owner, String name) throws Exception {
        Field result = owner.getDeclaredField(name); result.setAccessible(true); return result;
    }
    private void capture(Class<?> owner, String name) throws Exception {
        Field binding = field(owner, name); originalBindings.put(binding, binding.get(null));
    }
    @SuppressWarnings("unchecked") private RemoteStore registeredAuthority() throws Exception {
        synchronized (SubFeaturePolicy.LOCK) {
            Map<Context, RemoteStore> authorities = (Map<Context, RemoteStore>) field(SubFeaturePolicy.class, "authorities").get(null);
            return authorities.get(context.getApplicationContext());
        }
    }
    private void assertOwnedBindings() throws Exception {
        for (Map.Entry<Field, Object> binding : ownedBindings.entrySet())
            assertSame("Owned adapter changed: " + binding.getKey().getName(), binding.getValue(), binding.getKey().get(null));
        assertSame(fixture, singleton.get(null)); assertSame(store, registeredAuthority());
    }
    private interface CleanupAction { void run() throws Exception; }
    private static void main(CleanupAction action) throws Exception {
        final Throwable[] failure = new Throwable[1];
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try { action.run(); } catch (Throwable rejected) { failure[0] = rejected; }
        });
        if (failure[0] != null) rethrow(failure[0]);
    }
    private static void clean(List<Throwable> failures, CleanupAction action) {
        try { action.run(); } catch (Throwable failure) { failures.add(failure); }
    }
    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Error) throw (Error) failure;
        if (failure instanceof Exception) throw (Exception) failure;
        throw new AssertionError(failure);
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
        assertOwnedBindings(); return completed;
    }
    public Map<String, List<String>> snapshot() throws Exception {
        fixture.submit(() -> null).get(10, TimeUnit.SECONDS);
        assertSame(transport, endpoint.get(fixture)); assertOwnedBindings();
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
    @Override public void close() throws Exception { closeWithWitness(() -> {}); }

    void closeWithWitness(Runnable invariant) throws Exception {
        if (closed) return; closed = true;
        List<Throwable> failures = new ArrayList<>();
        // A failed invariant must not skip restoration, owned shutdown, or ownership-checked cleanup.
        clean(failures, invariant::run);
        clean(failures, () -> { if (!ownedBindings.isEmpty()) assertOwnedBindings(); });
        boolean[] providerStopped = {relationships == null};
        clean(failures, () -> {
            if (relationships != null) { relationships.close(); providerStopped[0] = true; }
        });
        // The caller drains its Activities first. Restore each captured identity independently.
        for (Map.Entry<Field, Object> binding : originalBindings.entrySet())
            clean(failures, () -> main(() -> binding.getKey().set(null, binding.getValue())));
        clean(failures, () -> {
            if (singletonPublished) synchronized (RemoteFeature.class) {
                Object current = singleton.get(null); singleton.set(null, original);
                assertSame("Only the fixture may occupy its singleton slot", fixture, current);
            }
        });
        clean(failures, () -> {
            if (fixture != null && iconChanged != null) fixture.appIcons.removeInvalidationListener(iconChanged);
        });
        clean(failures, () -> { if (icons != null) main(icons::close); });
        boolean[] iconsStopped = {icons == null};
        clean(failures, () -> {
            if (icons != null) {
                java.util.concurrent.ExecutorService worker = (java.util.concurrent.ExecutorService) field(
                        com.subhub.app.util.RemoteAppIcons.class, "worker").get(icons);
                assertTrue("Owned icon work must stop before its database is removed", worker.awaitTermination(10, TimeUnit.SECONDS));
                iconsStopped[0] = true;
            }
        });
        clean(failures, () -> {
            if (fixture != null) fixture.submit(() -> null).get(10, TimeUnit.SECONDS);
        });
        clean(failures, () -> {
            if (leased) {
                try {
                    long end = android.os.SystemClock.uptimeMillis() + 5000;
                    while (leases.get() != 1 && android.os.SystemClock.uptimeMillis() < end) Thread.sleep(20);
                } finally {
                    int remaining = leases.decrementAndGet(); leased = false;
                    assertEquals("Only the owned fixture lease may remain", 0, remaining);
                }
            }
        });
        boolean[] endpointStopped = {fixture == null};
        clean(failures, () -> {
            if (fixture != null) { fixture.shutdownForTest(); endpointStopped[0] = true; }
            else if (store != null) store.close();
        });
        clean(failures, () -> {
            synchronized (SubFeaturePolicy.LOCK) {
                if (store != null) SubFeaturePolicy.unregisterHardcoreAuthority(context, store);
                if (originalAuthority != null) SubFeaturePolicy.registerHardcoreAuthority(context, originalAuthority);
                assertSame("Restore the exact original Hardcore authority", originalAuthority, registeredAuthority());
            }
        });
        boolean[] released = {false};
        if (store != null && endpointStopped[0] && providerStopped[0] && iconsStopped[0]) clean(failures, () -> {
            FormalRelationshipCoordinator.requireOwnedFixture(store);
            // Also handle a constructor failure between attaching the witness and assigning the provider.
            LinkingWitness attached = witness != null ? witness : LinkingWitness.attachedWithoutIo(store);
            try { if (attached != null) attached.cleanupOwnedFixture(store); }
            finally { store.close(); } // Cleanup may inspect/reopen the helper; close it again before deletion.
            FormalRelationshipCoordinator.releaseOwnedFixture(store); released[0] = true;
        });
        if (released[0]) clean(failures, () -> {
            assertTrue("Delete only the ownership-verified fixture database", context.deleteDatabase(database));
        });
        clean(failures, this::assertOriginalRestored);
        if (!failures.isEmpty()) {
            Throwable first = failures.get(0);
            for (int i = 1; i < failures.size(); i++) first.addSuppressed(failures.get(i));
            rethrow(first);
        }
    }
    void assertOriginalRestored() throws Exception {
        assertSame(original, RemoteFeature.get(context));
        for (Map.Entry<Field, Object> binding : originalBindings.entrySet())
            assertSame("Restore original adapter: " + binding.getKey().getName(), binding.getValue(), binding.getKey().get(null));
        assertSame(originalAuthority, registeredAuthority());
        assertFalse(((java.util.concurrent.ThreadPoolExecutor) field(RemoteFeature.class, "sessions").get(original)).isShutdown());
        assertTrue("No original endpoint rows changed", originalRows.equals(rows(original.store)));
    }
    void assertOwnedDisposed() throws Exception {
        assertTrue(closed);
        assertFalse("Owned database removed", context.getDatabasePath(database).exists());
        if (fixture != null) for (String name : new String[]{"operations", "sessions", "incomingSessions"})
            assertTrue("Owned worker stopped: " + name,
                    ((java.util.concurrent.ThreadPoolExecutor) field(RemoteFeature.class, name).get(fixture)).isTerminated());
    }
}
