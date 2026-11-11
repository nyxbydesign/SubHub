package com.subhub.app.popup;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.subhub.app.internal.FileInternalSubBackend;
import com.subhub.app.internal.InternalProfile;
import com.subhub.app.internal.InternalStorageScope;
import com.subhub.app.remote.RemoteAssets;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Authored native decoder contract; no session, production store, role or permission mutation. */
@RunWith(AndroidJUnit4.class)
public final class OwnedPopupArtworkAndroidTest {
    private Context context;
    private Path root;
    private InternalStorageScope storage;

    @Before public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        root = Files.createTempDirectory(context.getCacheDir().toPath(), "owned-popup-test-");
        storage = FileInternalSubBackend.createNew(root.toFile(),
                new InternalProfile("Popup fixture", "Synthetic artwork", "", "", 0), Map.of()).scope();
    }
    @After public void tearDown() throws Exception {
        if (root == null) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                Files.delete(path); // Only this test's newly created owned directory.
        }
    }

    @Test public void emptyAndFullyDisabledManifestUseOnlyTheNormalBundledGuardian() throws Exception {
        PopupBitmapCache cache = new PopupBitmapCache(context);
        PopupLibrary deviceDefault = new PopupLibrary(cache);
        deviceDefault.rescan("", List.of());
        assertEquals(1, deviceDefault.count());
        PopupLibrary.BitmapSource normal = deviceDefault.randomBitmap();
        assertNotNull(normal);
        String absentDisabledHash = "a".repeat(64);
        for (PopupArtworkManifest manifest : List.of(new PopupArtworkManifest(Set.of(), Set.of()),
                new PopupArtworkManifest(Set.of(absentDisabledHash), Set.of(absentDisabledHash)))) {
            try (OwnedPopupArtwork images = OwnedPopupArtwork.load(context, storage, manifest, () -> true)) {
                assertEquals(1, images.count());
                PopupLibrary.BitmapSource source = images.randomBitmap();
                assertEquals("asset://popup_storm/guardian_shield.webp", source.path);
                assertEquals(normal.path, source.path);
                assertFalse(source.bitmap.isRecycled());
            }
        }
        cache.clear();
    }

    @Test public void missingOrCorruptEnabledBlobFailsWithoutGuardianFallback() throws Exception {
        String missing = "b".repeat(64);
        assertThrows(IOException.class, () -> OwnedPopupArtwork.load(context, storage,
                new PopupArtworkManifest(Set.of(missing), Set.of()), () -> true));
        String hash = stageMagentaImage();
        File blob = new RemoteAssets(storage.directory(InternalStorageScope.Area.ASSETS)).finish(hash);
        Files.write(blob.toPath(), new byte[]{1, 2, 3});
        assertThrows(IOException.class, () -> OwnedPopupArtwork.load(context, storage,
                new PopupArtworkManifest(Set.of(hash), Set.of()), () -> true));
    }

    @Test public void enabledOwnedImageDecodesAndReleasesOnlyItsOwnBitmap() throws Exception {
        String hash = stageMagentaImage();
        Bitmap decoded;
        try (OwnedPopupArtwork images = OwnedPopupArtwork.load(context, storage,
                new PopupArtworkManifest(Set.of(hash), Set.of()), () -> true)) {
            assertEquals(1, images.count());
            PopupLibrary.BitmapSource source = images.randomBitmap();
            assertEquals(hash, source.path);
            decoded = source.bitmap;
            assertEquals(Color.MAGENTA, decoded.getPixel(0, 0));
        }
        assertTrue(decoded.isRecycled());
    }

    @Test public void canceledDecodeCannotPublishEvenTheBuiltinFallback() throws Exception {
        try (OwnedPopupArtwork images = OwnedPopupArtwork.load(context, storage,
                new PopupArtworkManifest(Set.of(), Set.of()), () -> false)) {
            assertEquals(0, images.count());
            assertNull(images.randomBitmap());
        }
    }

    private String stageMagentaImage() throws Exception {
        Bitmap image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try { image.eraseColor(Color.MAGENTA); assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, bytes)); }
        finally { image.recycle(); }
        byte[] data = bytes.toByteArray();
        Path staging = root.resolve("synthetic.png");
        Files.write(staging, data);
        String hash = RemoteAssets.digest(staging.toFile());
        RemoteAssets assets = new RemoteAssets(storage.directory(InternalStorageScope.Area.ASSETS));
        assets.reserve(hash, data.length);
        assets.append(hash, 0, data);
        assets.finish(hash);
        return hash;
    }
}
