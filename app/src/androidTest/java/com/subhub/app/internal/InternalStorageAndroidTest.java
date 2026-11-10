package com.subhub.app.internal;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.subhub.app.people.ProfileData;
import com.subhub.app.runtime.SQLiteRuntimeOwnerBackend;
import com.subhub.app.settings.SettingsRepository;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** All writes/cleanup stay under one newly owned cache fixture; never reset or edit guest data. */
@RunWith(AndroidJUnit4.class)
public final class InternalStorageAndroidTest {
    private Path fixture;
    private Context physical;
    private FileInternalSubBackend backend;
    private InternalSubStore store;
    private InternalConfigurationStore configuration;

    @Before public void setUp() throws Exception {
        physical = ApplicationProvider.getApplicationContext();
        fixture = Files.createTempDirectory(physical.getCacheDir().toPath(), "internal-storage-fixture-");
        backend = FileInternalSubBackend.createNew(fixture.toFile(),
                new InternalProfile("Owned fixture", "", "", "", 0), InternalConfigurationStore.defaults());
        store = InternalConfigurationStore.open(backend); configuration = new InternalConfigurationStore(store);
    }
    @After public void tearDown() throws Exception {
        if (fixture != null) try (java.util.stream.Stream<Path> paths = Files.walk(fixture)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
        }
    }

    @Test public void acknowledgedConfigurationAndProfileSurviveReopen() throws Exception {
        InternalConfigurationStore.EditorDraft draft = configuration.edit(configuration.read());
        new SettingsRepository(scoped(draft)).preferences().edit().putInt("censor_intensity", 69).apply();
        InternalSubRecord saved = configuration.flush(draft);
        ProfileData edited = new ProfileData("Changed fixture", "Retained details", "", "", 0);
        saved = store.saveProfile(saved, InternalProfileAdapter.fromProfileData(edited));
        InternalSubRecord reopened = FileInternalSubBackend.openExisting(fixture.toFile()).read();
        assertEquals(saved, reopened); assertEquals(edited, InternalProfileAdapter.toProfileData(reopened.profile));
        assertEquals(1, reopened.configurationRevision);
    }

    @Test public void canonicalApplicationContextPreservesScopeAndNeverReadsPhysicalPreferences() {
        AtomicInteger physicalReads = new AtomicInteger();
        Context sentinel = new ContextWrapper(physical) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                physicalReads.incrementAndGet(); throw new AssertionError("Physical preferences accessed");
            }
        };
        InternalSubContext context = new InternalSubContext(sentinel, backend.scope(), configuration.edit(configuration.read()));
        assertSame(context, context.getApplicationContext());
        assertEquals(50, new SettingsRepository(context.getApplicationContext()).preferences().getInt("censor_intensity", 0));
        assertThrows(SecurityException.class, () -> context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0)
                .getString("controller_pin_hash", "fallback"));
        assertThrows(SecurityException.class, () -> context.getSharedPreferences("subhub_profile_names", 0));
        assertEquals(0, physicalReads.get());
        assertNotNull(context.getAssets()); assertNotNull(context.getPackageManager());
    }

    @Test public void ownerJournalAndPlatformEffectsCannotUseEditorContext() {
        InternalSubContext context = scoped(configuration.edit(configuration.read()));
        assertThrows(IllegalArgumentException.class, () -> new SQLiteRuntimeOwnerBackend(context));
        assertThrows(SecurityException.class, () -> context.getDatabasePath("subhub_runtime_owner.db"));
        assertThrows(SecurityException.class, () -> context.startService(new Intent()));
        assertThrows(SecurityException.class, () -> context.startForegroundService(new Intent()));
        assertThrows(SecurityException.class, () -> context.sendBroadcast(new Intent()));
        assertThrows(SecurityException.class, context::getContentResolver);
        assertThrows(SecurityException.class, context::createDeviceProtectedStorageContext);
    }

    @Test public void hidingAndReenablingPreserveFilesTasksAndHistory() throws Exception {
        InternalSubContext context = scoped(configuration.edit(configuration.read()));
        try (java.io.FileOutputStream file = context.openFileOutput("owned-file", Context.MODE_PRIVATE)) {
            file.write(new byte[]{1, 2, 3});
        }
        for (InternalStorageScope.Area area : new InternalStorageScope.Area[]{InternalStorageScope.Area.ASSETS,
                InternalStorageScope.Area.TASKS, InternalStorageScope.Area.HISTORY})
            Files.write(backend.scope().file(area, "sentinel").toPath(), new byte[]{9});
        InternalSubRecord shown = store.setVisible(store.read(), true), hidden = store.setVisible(shown, false);
        InternalSubRecord restored = store.setVisible(hidden, true);
        assertNotEquals(shown.generation, restored.generation);
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(context.getFileStreamPath("owned-file").toPath()));
        for (InternalStorageScope.Area area : new InternalStorageScope.Area[]{InternalStorageScope.Area.ASSETS,
                InternalStorageScope.Area.TASKS, InternalStorageScope.Area.HISTORY})
            assertArrayEquals(new byte[]{9}, Files.readAllBytes(backend.scope().file(area, "sentinel").toPath()));
        assertThrows(IllegalArgumentException.class, () -> context.getFileStreamPath("../outside"));
    }

    @Test public void missingOrCorruptEstablishedEvidenceNeverBecomesDefaults() throws Exception {
        Path record = backend.scope().root().resolve("profile.configuration");
        Files.write(record, new byte[]{0, 1});
        assertThrows(java.io.IOException.class, () -> FileInternalSubBackend.openExisting(fixture.toFile()));
        Files.delete(record);
        assertThrows(java.io.IOException.class, () -> FileInternalSubBackend.openExisting(fixture.toFile()));
        assertThrows(java.io.IOException.class, () -> FileInternalSubBackend.createNew(fixture.toFile(),
                new InternalProfile("Unwanted reset", "", "", "", 0), InternalConfigurationStore.defaults()));
    }

    @Test public void trustedParentAliasAndConcurrentOwnedDirectoryCreationRemainIsolated() throws Exception {
        Path trusted = Files.createDirectory(fixture.resolve("trusted-physical"));
        Path alias = Files.createSymbolicLink(fixture.resolve("trusted-alias"), trusted);
        FileInternalSubBackend aliased = FileInternalSubBackend.createNew(alias.toFile(),
                new InternalProfile("Alias fixture", "", "", "", 0), InternalConfigurationStore.defaults());
        assertEquals(aliased.read(), FileInternalSubBackend.openExisting(trusted.toFile()).read());
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread[] workers = new Thread[12];
        for (int i = 0; i < workers.length; i++) {
            workers[i] = new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 8; j++) assertTrue(aliased.scope().directory(InternalStorageScope.Area.TASKS).isDirectory());
                } catch (Throwable error) { failure.compareAndSet(null, error); }
            });
            workers[i].start();
        }
        start.countDown();
        for (Thread worker : workers) { worker.join(10000); assertFalse(worker.isAlive()); }
        if (failure.get() != null) throw new AssertionError("Owned directory race", failure.get());
        Files.createSymbolicLink(aliased.scope().root().resolve("history"), backend.scope().root());
        assertThrows(java.io.IOException.class, () -> aliased.scope().directory(InternalStorageScope.Area.HISTORY));
    }

    private InternalSubContext scoped(InternalConfigurationStore.EditorDraft draft) {
        return new InternalSubContext(physical, backend.scope(), draft);
    }
}
