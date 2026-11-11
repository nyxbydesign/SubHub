package com.subhub.app.censor.android;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.subhub.app.internal.*;
import com.subhub.app.remote.RemoteAssets;
import com.subhub.app.runtime.*;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Owned file/bitmap contracts only; this fixture never registers a physical session or service. */
@RunWith(AndroidJUnit4.class)
public final class OwnedCensorArtworkAndroidTest {
    private Path root;
    @Before public void setUp() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        root = Files.createTempDirectory(context.getCacheDir().toPath(), "owned-censor-art-");
    }
    @After public void tearDown() throws Exception {
        if (root == null) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
        }
    }

    @Test public void decodedSoftwarePixelsBelongOnlyToTheCaller() throws Exception {
        Fixture f = fixture(true, false); List<Bitmap> images = f.loader.load(new SnapshotPaintPolicy(64, 1), 0, () -> false);
        try {
            assertEquals(1, images.size()); assertNotEquals(Bitmap.Config.HARDWARE, images.get(0).getConfig());
            assertEquals(Color.MAGENTA, images.get(0).getPixel(0, 0)); assertFalse(images.get(0).isRecycled());
        } finally { for (Bitmap image : images) image.recycle(); }
        assertTrue(images.get(0).isRecycled());
    }
    @Test public void missingEnabledBlobFailsWithoutAnyDeviceLibraryFallback() throws Exception {
        Fixture f = fixture(false, false);
        assertThrows(IllegalStateException.class, () -> f.loader.load(new SnapshotPaintPolicy(64, 1), 0, () -> false));
    }
    @Test public void disabledAndCancelledManifestsNeverOpenMissingBlob() throws Exception {
        Fixture f = fixture(false, true);
        assertTrue(f.loader.load(new SnapshotPaintPolicy(64, 1), 0, () -> false).isEmpty());
        assertTrue(f.loader.load(new SnapshotPaintPolicy(64, 1), 0, () -> true).isEmpty());
        assertTrue(f.loader.load(new SnapshotPaintPolicy(64, 1), 1, () -> false).isEmpty());
    }
    @Test public void canceledDecodeRecyclesItsPartialResult() throws Exception {
        Fixture f = fixture(true, false); int[] checks = { 0 };
        List<Bitmap> images = f.loader.load(new SnapshotPaintPolicy(64, 1), 0, () -> ++checks[0] >= 3);
        assertTrue(images.isEmpty()); assertTrue(checks[0] >= 3);
        // A later independent read still receives owned pixels, not a recycled shared cache entry.
        List<Bitmap> retry = f.loader.load(new SnapshotPaintPolicy(64, 1), 0, () -> false);
        try { assertEquals(1, retry.size()); assertFalse(retry.get(0).isRecycled()); }
        finally { for (Bitmap image : retry) image.recycle(); }
    }

    @Test public void unrelatedConfigurationAdoptionRetainsAlreadyDecodedArtwork() throws Exception {
        Fixture f = fixture(true, false);
        RuntimeConfiguration next = f.nextChanged(); int[] reads = { 0 };
        List<Bitmap> images = f.loader.load(new SnapshotPaintPolicy(64, 1), 0, () -> {
            if (++reads[0] == 3) f.loader.update(next); // Actual decode completed before this check.
            return false;
        });
        try { assertEquals(1, images.size()); assertEquals(Color.MAGENTA, images.get(0).getPixel(0, 0)); }
        finally { for (Bitmap image : images) image.recycle(); }
        assertTrue(reads[0] >= 3);
        assertThrows(SecurityException.class, () -> f.loader.update(f.initial));
    }
    @Test public void changedArtworkAdoptionRetiresHeldDecodeWithoutPublishingIt() throws Exception {
        Fixture f = fixture(true, false);
        InternalConfigurationStore.EditorDraft draft = f.configuration.edit(f.configuration.read());
        draft.setArtwork(Set.of(), Set.of(), Set.of());
        RuntimeConfiguration next = RuntimeConfiguration.internal(f.lease, f.configuration, f.configuration.flush(draft));
        int[] reads = { 0 };
        assertTrue(f.loader.load(new SnapshotPaintPolicy(64, 1), 0, () -> {
            if (++reads[0] == 3) f.loader.update(next);
            return false;
        }).isEmpty());
        assertTrue(reads[0] >= 3);
        assertTrue(f.loader.load(new SnapshotPaintPolicy(64, 1), next.artworkRevision, () -> false).isEmpty());
    }
    @Test public void successiveUnchangedManifestsStillRejectOlderConfigurationPublication() throws Exception {
        Fixture f = fixture(true, false);
        RuntimeConfiguration two = f.nextChanged(), three = f.nextChanged();
        f.loader.update(two); f.loader.update(three);
        assertThrows(SecurityException.class, () -> f.loader.update(two));
        List<Bitmap> images = f.loader.load(new SnapshotPaintPolicy(64, 1), 0, () -> false);
        try { assertEquals(1, images.size()); }
        finally { for (Bitmap image : images) image.recycle(); }
    }

    private Fixture fixture(boolean stage, boolean disabled) throws Exception {
        Bitmap input = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888); ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try { input.eraseColor(Color.MAGENTA); assertTrue(input.compress(Bitmap.CompressFormat.PNG, 100, bytes)); }
        finally { input.recycle(); }
        byte[] payload = bytes.toByteArray(); Path source = root.resolve("synthetic.png"); Files.write(source, payload);
        String hash = RemoteAssets.digest(source.toFile());
        Map<String, Map<String, Object>> settings = new LinkedHashMap<>();
        InternalConfigurationStore.defaults().forEach((name, values) -> settings.put(name, new LinkedHashMap<>(values)));
        settings.get(InternalConfigurationStore.ARTWORK).put("censor_assets", Set.of(hash));
        settings.get(InternalConfigurationStore.ARTWORK).put("disabled_assets", disabled ? Set.of(hash) : Set.of());
        FileInternalSubBackend backend = FileInternalSubBackend.createNew(root.toFile(),
                new InternalProfile("Censor fixture", "Owned artwork only", "", "", 0), settings);
        InternalSubStore store = InternalConfigurationStore.open(backend); InternalSubRecord record = store.read();
        if (stage) {
            RemoteAssets assets = new RemoteAssets(backend.scope().directory(InternalStorageScope.Area.ASSETS));
            assets.reserve(hash, payload.length); assets.append(hash, 0, payload); assets.finish(hash);
        }
        RuntimeMutex mutex = new RuntimeMutex(new Object());
        RuntimeAdmissionAuthority authority = new RuntimeAdmissionAuthority(mutex);
        RuntimeTarget target = RuntimeTarget.internal(record.targetId);
        authority.completeAvailability(authority.beginAvailability(target), record.generation, record.configurationRevision, true);
        authority.completeAuthorization(authority.beginAuthorization(target, RuntimeOrigin.LOCAL_UI), 1, true);
        RuntimeOwnerStore.Backend memory = new RuntimeOwnerStore.Backend() {
            RuntimeOwnerStore.Record row = RuntimeOwnerStore.Record.idle(0);
            @Override public RuntimeOwnerStore.Record read() { return row; }
            @Override public RuntimeOwnerStore.Outcome compareAndSet(RuntimeOwnerStore.Record expected, RuntimeOwnerStore.Record next) {
                if (!row.equals(expected)) return RuntimeOwnerStore.Outcome.CONFLICT;
                row = next; return RuntimeOwnerStore.Outcome.COMMITTED;
            }
        };
        ActiveRuntimeCoordinator coordinator = new ActiveRuntimeCoordinator(new RuntimeOwnerStore(mutex, memory), authority,
                () -> ActiveRuntimeCoordinator.Occupancy.CLEAR, UUID.randomUUID().toString());
        assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.reconcileStartup());
        ActiveRuntimeCoordinator.Admission admission = coordinator.reserve(authority.capture(target, RuntimeOrigin.LOCAL_UI), Set.of("fixture"));
        assertEquals(ActiveRuntimeCoordinator.Result.OK, admission.result);
        InternalConfigurationStore configuration = new InternalConfigurationStore(store);
        RuntimeConfiguration initial = RuntimeConfiguration.internal(admission.lease, configuration, record);
        return new Fixture(new OwnedCensorArtwork(backend.scope(), initial), admission.lease, configuration, initial);
    }
    private static final class Fixture {
        final OwnedCensorArtwork loader;
        final RuntimeLease lease;
        final InternalConfigurationStore configuration;
        final RuntimeConfiguration initial;
        Fixture(OwnedCensorArtwork loader, RuntimeLease lease, InternalConfigurationStore configuration, RuntimeConfiguration initial) {
            this.loader = loader; this.lease = lease; this.configuration = configuration; this.initial = initial;
        }
        RuntimeConfiguration nextChanged() {
            InternalConfigurationStore.EditorDraft draft = configuration.edit(configuration.read());
            android.content.SharedPreferences preferences = draft.preferences(com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME);
            String key = com.subhub.app.settings.FeatureModuleManager.KEY_CENSOR_ENABLED;
            preferences.edit().putBoolean(key, !preferences.getBoolean(key, true)).apply();
            return RuntimeConfiguration.internal(lease, configuration, configuration.flush(draft));
        }
    }
}
