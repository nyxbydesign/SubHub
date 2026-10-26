package com.subhub.app.remote;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

public class RemoteAssetsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void resumesAcrossInstancesAndAcceptsExactDuplicateChunks() throws Exception {
        File root = temporary.newFolder();
        byte[] bytes = new byte[RemoteAssets.CHUNK_BYTES+123]; Arrays.fill(bytes,(byte)42);
        String hash = RemoteProtocol.sha256(bytes);
        RemoteAssets first = new RemoteAssets(root); first.reserve(hash,bytes.length);
        byte[] chunk = Arrays.copyOf(bytes,RemoteAssets.CHUNK_BYTES);
        assertEquals(chunk.length,first.append(hash,0,chunk));
        RemoteAssets resumed = new RemoteAssets(root);
        assertEquals(chunk.length,resumed.offset(hash));
        assertEquals(chunk.length,resumed.append(hash,0,chunk));
        resumed.append(hash,chunk.length,Arrays.copyOfRange(bytes,chunk.length,bytes.length));
        File complete = resumed.finish(hash);
        assertArrayEquals(bytes,Files.readAllBytes(complete.toPath()));
        assertArrayEquals(Arrays.copyOfRange(bytes,chunk.length,bytes.length),resumed.chunk(hash,chunk.length));
        assertThrows(IOException.class, () -> resumed.restartPartial(hash));
    }

    @Test public void rejectsMissingGappedConflictingAndOversizedChunks() throws Exception {
        RemoteAssets assets = new RemoteAssets(temporary.newFolder());
        String hash = RemoteProtocol.sha256(new byte[]{1,2,3,4});
        assertThrows(IOException.class, () -> assets.append(hash,0,new byte[]{1}));
        assets.reserve(hash,4);
        assertThrows(IOException.class, () -> assets.append(hash,1,new byte[]{1}));
        assets.append(hash,0,new byte[]{1,2});
        assertThrows(IOException.class, () -> assets.append(hash,0,new byte[]{9}));
        assertThrows(IOException.class, () -> assets.append(hash,1,new byte[]{2,3}));
        assertThrows(IOException.class, () -> assets.append(hash,2,new byte[]{3,4,5}));
        assertThrows(IOException.class, () -> assets.finish(hash));
    }

    @Test public void checksumFailureCannotPublishAndExplicitRestartRepairsOnlyPartial() throws Exception {
        RemoteAssets assets = new RemoteAssets(temporary.newFolder());
        byte[] expected = {1,2,3}; String hash = RemoteProtocol.sha256(expected);
        assets.reserve(hash,3); assets.append(hash,0,new byte[]{9,9,9});
        assertThrows(IOException.class, () -> assets.finish(hash));
        assertFalse(assets.isComplete(hash));
        assets.restartPartial(hash); assets.append(hash,0,expected); assets.finish(hash);
        assertTrue(assets.isComplete(hash));
    }

    @Test public void aggregateReservationQuotaDoesNotNeedLargeMemoryAllocations() throws Exception {
        RemoteAssets assets = new RemoteAssets(temporary.newFolder());
        for (int i=0;i<10;i++) assets.reserve(String.format(java.util.Locale.ROOT,"%064x",i),RemoteAssets.MAX_ASSET_BYTES);
        assertThrows(IOException.class, () -> assets.reserve(String.format(java.util.Locale.ROOT,"%064x",10),RemoteAssets.MAX_ASSET_BYTES));
        assertThrows(IOException.class, () -> assets.reserve("../escape",1));
        assertThrows(IOException.class, () -> assets.reserve(String.format(java.util.Locale.ROOT,"%064x",12),RemoteAssets.MAX_ASSET_BYTES+1));
    }
}
