package com.subhub.app.storage;

import com.subhub.app.remote.RemoteAssets;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class InternalAssetDurabilityTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final byte[] BYTES = { 7, 11, 19, 23 };
    private String stage(File root) throws Exception {
        File source = temporary.newFile(); Files.write(source.toPath(), BYTES);
        String hash = RemoteAssets.digest(source);
        RemoteAssets assets = new RemoteAssets(root); assets.reserve(hash, BYTES.length);
        assets.append(hash, 0, BYTES); return hash;
    }
    @Test public void sealsBytesMetadataAndReopensAcknowledgedAsset() throws Exception {
        File root = temporary.newFolder(); String hash = stage(root);
        File sealed = InternalAssetDurability.seal(root, new RemoteAssets(root), hash, BYTES.length);
        assertArrayEquals(BYTES, Files.readAllBytes(sealed.toPath()));
        assertFalse(new File(root, hash + ".part").exists());
        assertEquals(sealed, InternalAssetDurability.seal(root, new RemoteAssets(root), hash, BYTES.length));
    }
    @Test public void rejectsMissingPublishedBytesWithoutRecreatingThem() throws Exception {
        File root = temporary.newFolder(); String hash = stage(root);
        File sealed = InternalAssetDurability.seal(root, new RemoteAssets(root), hash, BYTES.length);
        Files.delete(sealed.toPath());
        assertThrows(IOException.class, () -> InternalAssetDurability.seal(root, new RemoteAssets(root), hash, BYTES.length));
        assertFalse(sealed.exists()); assertTrue(new File(root, hash + ".size").exists());
    }
    @Test public void rejectsCorruptBytesAndMetadataWithoutReplacingThem() throws Exception {
        File root = temporary.newFolder(); String hash = stage(root);
        File sealed = InternalAssetDurability.seal(root, new RemoteAssets(root), hash, BYTES.length);
        byte[] corrupt = { 3, 3, 3, 3 }; Files.write(sealed.toPath(), corrupt);
        assertThrows(IOException.class, () -> InternalAssetDurability.seal(root, new RemoteAssets(root), hash, BYTES.length));
        assertArrayEquals(corrupt, Files.readAllBytes(sealed.toPath()));
        Files.write(sealed.toPath(), BYTES);
        Files.write(new File(root, hash + ".size").toPath(), new byte[]{'9'});
        assertThrows(IOException.class, () -> InternalAssetDurability.seal(root, new RemoteAssets(root), hash, BYTES.length));
        assertArrayEquals(BYTES, Files.readAllBytes(sealed.toPath()));
    }
    @Test public void rejectsDifferentNamespaceAndLinkedStagedFile() throws Exception {
        File root = temporary.newFolder(); String hash = stage(root);
        assertThrows(IOException.class, () -> InternalAssetDurability.seal(temporary.newFolder(), new RemoteAssets(root), hash, BYTES.length));
        File source = temporary.newFile(); Files.write(source.toPath(), BYTES);
        File part = new File(root, hash + ".part"); Files.delete(part.toPath());
        Files.createSymbolicLink(part.toPath(), source.toPath());
        assertThrows(IOException.class, () -> InternalAssetDurability.seal(root, new RemoteAssets(root), hash, BYTES.length));
        assertArrayEquals(BYTES, Files.readAllBytes(source.toPath()));
        assertFalse(new File(root, hash + ".blob").exists());
    }
}
