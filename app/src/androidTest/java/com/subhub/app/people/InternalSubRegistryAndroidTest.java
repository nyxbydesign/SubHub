package com.subhub.app.people;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ExecutorService;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Registry tests own both provisioning evidence and filesystem roots; no real profile is replaced. */
@RunWith(AndroidJUnit4.class)
public final class InternalSubRegistryAndroidTest {
    private Context physical;
    private OwnedContext owned;
    private File root;
    private InternalVisibilityIdleFixture visibility;
    private boolean originalDom;
    private DeviceRolePreferences.Mode originalMode;
    private Field tokenField, persistenceField;
    private Object originalToken;
    private boolean originalPersistenceFailure;

    @Before public void prepare() throws Exception {
        physical = ApplicationProvider.getApplicationContext();
        originalDom = ControllerPinManager.isDomModeActive();
        originalMode = DeviceRolePreferences.get(physical);
        tokenField = DummySubPreview.class.getDeclaredField("token"); tokenField.setAccessible(true); originalToken = tokenField.get(null);
        persistenceField = DummySubPreview.class.getDeclaredField("persistenceFailed"); persistenceField.setAccessible(true);
        originalPersistenceFailure = persistenceField.getBoolean(null); persistenceField.setBoolean(null, false);
        assertTrue(ControllerPinManager.verify(physical, "2468"));
        assertTrue(DeviceRolePreferences.set(physical, DeviceRolePreferences.Mode.BOTH));
        root = new File(physical.getCacheDir(), "internal-registry-" + UUID.randomUUID());
        assertTrue(root.mkdir());
        owned = new OwnedContext(physical, root);
        visibility = new InternalVisibilityIdleFixture(owned, candidate -> true);
    }

    @After public void restore() throws Exception {
        try {
            if (owned != null) {
                forgetCached();
                owned.registry.edit().clear().commit(); owned.legacy.edit().clear().commit(); owned.ownerRegistry.edit().clear().commit();
                Field failed = InternalSubData.class.getDeclaredField("FAILED"); failed.setAccessible(true);
                synchronized (InternalSubData.class) { ((Set<?>) failed.get(null)).remove(owned); }
            }
            if (root != null && root.exists()) try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(root.toPath())) {
                for (java.nio.file.Path path : paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())) Files.delete(path);
            }
        } finally {
            if (visibility != null) visibility.close();
            if (tokenField != null) tokenField.set(null, originalToken);
            if (persistenceField != null) persistenceField.setBoolean(null, originalPersistenceFailure);
            if (physical != null) {
                assertTrue(ControllerPinManager.verify(physical, "2468"));
                assertTrue(DeviceRolePreferences.set(physical, originalMode));
                if (!originalDom) ControllerPinManager.enterSubMode();
            }
        }
    }

    @Test public void enabledLegacyMigratesVisibilityAndFirstToggleRemovesIt() throws Exception {
        assertTrue(owned.legacy.edit().putBoolean("enabled", true).commit());
        assertTrue(InternalSubData.loadIfProvisioned(owned));
        InternalSubData data = InternalSubData.get(owned);
        assertTrue(data.acknowledged().visible);
        assertEquals("ready", owned.registry.getString("provisioning", ""));
        assertEquals("Dummy Sub", data.acknowledged().profile.name);
        String generation = data.acknowledged().generation;
        assertFalse(DummySubPreview.toggle(owned));
        assertNotEquals(generation, data.acknowledged().generation);
        assertTrue(owned.legacy.getBoolean("enabled", false));
        forgetCached(); assertTrue(InternalSubData.loadIfProvisioned(owned));
        assertFalse(InternalSubData.get(owned).acknowledged().visible);
    }

    @Test public void hiddenLegacyMigratesHiddenAndExplicitToggleAddsIt() {
        assertTrue(owned.legacy.edit().putBoolean("enabled", false).commit());
        assertTrue(InternalSubData.loadIfProvisioned(owned));
        assertFalse(DummySubPreview.isEnabled(owned));
        assertTrue(DummySubPreview.toggle(owned));
    }

    @Test public void establishedMissingRootCannotBeReseededByLegacyOrToggle() throws Exception {
        assertTrue(owned.legacy.edit().putBoolean("enabled", true).commit());
        assertTrue(InternalSubData.loadIfProvisioned(owned));
        forgetCached();
        File existing = new File(root, "internal_subs/dummy_sub_preview");
        File retained = new File(root, "retained-profile");
        Files.move(existing.toPath(), retained.toPath());
        assertThrows(IllegalStateException.class, () -> InternalSubData.loadIfProvisioned(owned));
        assertThrows(IllegalStateException.class, () -> DummySubPreview.toggle(owned));
        assertFalse(existing.exists()); assertTrue(retained.isDirectory());
        assertEquals("ready", owned.registry.getString("provisioning", ""));
    }

    @Test public void partialOrUnrecognizedEvidenceNeverCreatesDefaults() {
        assertTrue(owned.registry.edit().putString("provisioning", "started").commit());
        assertTrue(owned.legacy.edit().putBoolean("enabled", true).commit());
        assertThrows(IllegalStateException.class, () -> InternalSubData.loadIfProvisioned(owned));
        assertThrows(IllegalStateException.class, () -> DummySubPreview.toggle(owned));
        assertFalse(new File(root, "internal_subs").exists());
        assertTrue(owned.registry.edit().clear().commit());
        assertTrue(owned.legacy.edit().putString("unknown", "not a recognized migration").commit());
        assertThrows(IllegalStateException.class, () -> InternalSubData.loadIfProvisioned(owned));
        assertFalse(new File(root, "internal_subs").exists());
    }

    @Test public void parentDirectorySyncFailureNeverAcknowledgesReadyOrReseeds() throws Exception {
        java.util.concurrent.atomic.AtomicInteger syncs = new java.util.concurrent.atomic.AtomicInteger();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> InternalSubData.provision(owned, directory -> {
                    assertEquals(root.getCanonicalFile(), directory);
                    assertTrue(new File(directory, "internal_subs").isDirectory());
                    assertEquals("started", owned.registry.getString("provisioning", ""));
                    assertFalse(new File(directory, "internal_subs/dummy_sub_preview").exists());
                    syncs.incrementAndGet();
                    throw new java.io.IOException("Injected owned directory sync failure");
                }));
        assertTrue(failure.getCause() instanceof java.io.IOException);
        assertEquals(1, syncs.get());
        assertEquals("started", owned.registry.getString("provisioning", ""));
        assertNull(InternalSubData.peek(owned));
        assertFalse(new File(root, "internal_subs/dummy_sub_preview").exists());
        assertThrows(IllegalStateException.class, () -> InternalSubData.provision(owned));
        assertThrows(IllegalStateException.class, () -> InternalSubData.loadIfProvisioned(owned));
        assertEquals("started", owned.registry.getString("provisioning", ""));
        assertFalse(new File(root, "internal_subs/dummy_sub_preview").exists());
    }

    @Test public void ownerDataInitializationFailureLeavesProfileStartedAndUnavailable() {
        owned.failOwnerWrites = true;
        assertThrows(IllegalStateException.class, () -> InternalSubData.provision(owned));
        assertEquals("started", owned.registry.getString("provisioning", ""));
        assertNull(InternalSubData.peek(owned));
        assertFalse(new File(root, "internal_subs/dummy_sub_preview/runtime-data").exists());
        owned.failOwnerWrites = false;
        assertThrows(IllegalStateException.class, () -> InternalSubData.provision(owned));
        assertEquals("started", owned.registry.getString("provisioning", ""));
    }

    @Test public void concurrentReadyOpenPublishesOnlyOneInitializedBroker() throws Exception {
        assertTrue(DummySubPreview.toggle(owned));
        assertTrue(InternalSubData.get(owned).ownerDataReady());
        assertEquals("ready", owned.ownerRegistry.getString("runtime_v1", ""));
        forgetCached();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<InternalSubData> load = () -> { start.await(); return InternalSubData.get(owned); };
            java.util.concurrent.Future<InternalSubData> one = pool.submit(load), two = pool.submit(load); start.countDown();
            InternalSubData first = one.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertSame(first, two.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(first.ownerDataReady()); assertSame(first.ownerData(), InternalSubData.get(owned).ownerData());
        } finally { pool.shutdownNow(); }
    }

    @Test public void readyProfileWithoutOwnerEvidenceStaysReadableAndNeverProvisionsRuntimeData() throws Exception {
        File directory = new File(root, "internal_subs"); assertTrue(directory.mkdir());
        com.subhub.app.internal.FileInternalSubBackend backend = com.subhub.app.internal.FileInternalSubBackend.createNew(directory,
                new com.subhub.app.internal.InternalProfile("Readable profile", "", "", "", 0),
                com.subhub.app.internal.InternalConfigurationStore.defaults());
        com.subhub.app.internal.InternalSubStore store = com.subhub.app.internal.InternalConfigurationStore.open(backend);
        store.setVisible(store.read(), true, 1000);
        assertTrue(owned.registry.edit().putString("provisioning", "ready").commit());
        InternalSubData loaded = InternalSubData.get(owned);
        assertEquals("Readable profile", loaded.acknowledged().profile.name);
        assertFalse(loaded.ownerDataReady()); assertSame(loaded, InternalSubData.get(owned));
        assertThrows(IllegalStateException.class, loaded::ownerData);
        assertTrue(owned.ownerRegistry.getAll().isEmpty());
        assertFalse(new File(root, "internal_subs/dummy_sub_preview/runtime-data").exists());
    }

    @Test public void unavailableVisibilityProofLeavesEstablishedRecordUnchanged() throws Exception {
        InternalSubData data = InternalSubData.provision(owned);
        com.subhub.app.internal.InternalSubRecord before = data.acknowledged();
        Field provider = InternalVisibilityBarrier.class.getDeclaredField("provider"); provider.setAccessible(true);
        Field unavailable = InternalVisibilityBarrier.class.getDeclaredField("UNAVAILABLE"); unavailable.setAccessible(true);
        Object idle = provider.get(null);
        try {
            provider.set(null, unavailable.get(null));
            assertThrows(InternalVisibilityBarrier.UnavailableException.class, () -> DummySubPreview.toggle(owned));
            assertEquals(before, data.store.read());
            assertFalse(persistenceField.getBoolean(null));
        } finally { provider.set(null, idle); }
    }

    @Test public void delayedVisibilityProofCannotHideAfterControllerRevocation() throws Exception {
        assertTrue(DummySubPreview.toggle(owned));
        InternalSubData data = InternalSubData.get(owned);
        com.subhub.app.internal.InternalSubRecord before = data.acknowledged();
        Field provider = InternalVisibilityBarrier.class.getDeclaredField("provider"); provider.setAccessible(true);
        InternalVisibilityBarrier.Provider idle = (InternalVisibilityBarrier.Provider) provider.get(null);
        java.util.concurrent.CompletableFuture<InternalVisibilityBarrier.Permit> pending = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.CompletableFuture<InternalVisibilityBarrier.Permit> prepared = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            provider.set(null, (InternalVisibilityBarrier.Provider) (context, request) -> {
                idle.prepare(context, request).whenComplete((permit, failure) -> {
                    if (failure == null) prepared.complete(permit); else prepared.completeExceptionally(failure);
                });
                return pending;
            });
            java.util.concurrent.Future<Boolean> hiding = pool.submit(() -> DummySubPreview.toggle(owned));
            InternalVisibilityBarrier.Permit permit = prepared.get(10, java.util.concurrent.TimeUnit.SECONDS);
            ControllerPinManager.enterSubMode(); pending.complete(permit);
            assertThrows(java.util.concurrent.ExecutionException.class, () -> hiding.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(before, data.store.read());
        } finally {
            pending.completeExceptionally(new IllegalStateException("Owned fixture closed"));
            provider.set(null, idle); pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    private void forgetCached() throws Exception {
        Field open = InternalSubData.class.getDeclaredField("OPEN"); open.setAccessible(true);
        InternalSubData data;
        synchronized (InternalSubData.class) { data = (InternalSubData) ((Map<?, ?>) open.get(null)).remove(owned); }
        if (data != null) {
            com.subhub.app.internal.InternalOwnerDataBroker.attached(data).closeAfterDrain().get(10, java.util.concurrent.TimeUnit.SECONDS);
            Field writes = InternalSubData.class.getDeclaredField("writes"); writes.setAccessible(true);
            ((ExecutorService) writes.get(data)).shutdown();
        }
    }

    private static final class OwnedContext extends ContextWrapper {
        final File root;
        final SharedPreferences registry, legacy, ownerRegistry;
        boolean failOwnerWrites;
        OwnedContext(Context physical, File root) {
            super(physical); this.root = root;
            String namespace = "internal_registry_fixture_" + UUID.randomUUID();
            registry = physical.getSharedPreferences(namespace + "_registry", 0);
            legacy = physical.getSharedPreferences(namespace + "_legacy", 0);
            ownerRegistry = physical.getSharedPreferences(namespace + "_owner_data", 0);
        }
        @Override public Context getApplicationContext() { return this; }
        @Override public File getNoBackupFilesDir() { return root; }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            if ("subhub_internal_registry".equals(name)) return registry;
            if (DummySubPreview.PREFERENCES_NAME.equals(name)) return legacy;
            if ("subhub_internal_owner_data_registry".equals(name)) {
                if (!failOwnerWrites) return ownerRegistry;
                return (SharedPreferences) java.lang.reflect.Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                        new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("edit")) return method.invoke(ownerRegistry, args);
                    SharedPreferences.Editor edit = ownerRegistry.edit();
                    return java.lang.reflect.Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                            new Class<?>[]{SharedPreferences.Editor.class}, (editor, operation, values) -> {
                        if (operation.getName().equals("commit")) return false;
                        Object result = operation.invoke(edit, values);
                        return result instanceof SharedPreferences.Editor ? editor : result;
                    });
                });
            }
            return super.getSharedPreferences(name, mode);
        }
    }
}
