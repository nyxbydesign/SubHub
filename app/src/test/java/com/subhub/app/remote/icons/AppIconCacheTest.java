package com.subhub.app.remote.icons;

import static com.subhub.app.remote.icons.IconFixtures.*;
import static org.junit.Assert.*;
import com.subhub.app.util.RemoteAppIcon;
import com.subhub.app.util.RemoteAppIcons;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AppIconCacheTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void offlineAuthorizedReadRequiresExactCurrentDescriptorAndMissingReturnsNull() throws Exception {
        AppIconCache cache = cache(temporary.newFolder());
        byte[] png = png(128, 128, 1);
        RemoteAppIcon icon = icon(SUB, GENERATION, PACKAGE, png, 128, 128);
        assertNull(cache.load(icon));
        cache.publishReferences(Collections.singleton(icon));
        assertNull(cache.load(icon));
        cache.complete(cache.snapshot(), icon, png, () -> { });
        assertArrayEquals(png, cache.load(icon));
        assertNull(cache.load(bare()));
        cache.publishReferences(Collections.emptyList());
        assertNull(cache.load(icon));
        assertFalse(cache.currentAuthority().permits(icon));
    }

    @Test public void samePackageIsSeparatedByPeerAndGenerationAndOfflineRefDoesNotDiscoverFiles() throws Exception {
        File directory = temporary.newFolder();
        AppIconCache cache = cache(directory);
        byte[] one = png(16, 16, 1), two = png(16, 16, 2), three = png(16, 16, 3);
        RemoteAppIcon a = icon(SUB, GENERATION, PACKAGE, one, 16, 16);
        RemoteAppIcon b = icon(DOM, GENERATION, PACKAGE, two, 16, 16);
        RemoteAppIcon c = icon(SUB, "generation_b", PACKAGE, three, 16, 16);
        cache.publishReferences(java.util.Arrays.asList(a, b, c));
        AppIconCache.Snapshot token = cache.snapshot();
        cache.complete(token, a, one, () -> { }); cache.complete(token, b, two, () -> { }); cache.complete(token, c, three, () -> { });
        assertArrayEquals(one, cache.load(a)); assertArrayEquals(two, cache.load(b)); assertArrayEquals(three, cache.load(c));
        AppIconCache reopened = cache(directory);
        assertNull(reopened.load(a));
        reopened.publishReferences(Collections.singleton(a));
        assertArrayEquals(one, reopened.load(a));
        assertNull(reopened.load(b));
    }

    @Test public void descriptorChangePublishesImmutableSnapshotAndNotifiesProjectionRefresh() throws Exception {
        AppIconCache cache = cache(temporary.newFolder());
        cache.publishReferences(Collections.singleton(bare()));
        AppIconCache.Snapshot old = cache.snapshot();
        RemoteAppIcons.Authority oldAuthority = cache.currentAuthority();
        AtomicInteger changes = new AtomicInteger(); Runnable listener = changes::incrementAndGet;
        cache.addInvalidationListener(listener);
        byte[] png = png(8, 8, 1); RemoteAppIcon icon = icon(SUB, GENERATION, PACKAGE, png, 8, 8);
        assertTrue(cache.offered(old, icon));
        assertNull(old.identity(SUB, GENERATION, PACKAGE).descriptor);
        assertEquals(icon, cache.snapshot().identity(SUB, GENERATION, PACKAGE));
        assertTrue(oldAuthority.permits(bare())); assertFalse(oldAuthority.permits(icon));
        assertTrue(cache.currentAuthority().permits(icon)); assertEquals(1, changes.get());
        cache.removeInvalidationListener(listener);
        cache.publishReferences(Collections.emptyList()); assertEquals(1, changes.get());
    }

    @Test public void revokeThenRegrantCannotReuseAnOldRequestToken() throws Exception {
        AppIconCache cache = cache(temporary.newFolder());
        byte[] png = png(8, 8, 1); RemoteAppIcon icon = icon(SUB, GENERATION, PACKAGE, png, 8, 8);
        cache.publishReferences(Collections.singleton(icon)); AppIconCache.Snapshot old = cache.snapshot();
        cache.partial(old, icon, new byte[] {1});
        cache.publishReferences(Collections.emptyList()); cache.publishReferences(Collections.singleton(icon));
        assertFalse(cache.offered(old, icon));
        assertThrows(SecurityException.class, () -> cache.complete(old, icon, png, () -> { }));
        assertEquals(0, cache.resume(cache.snapshot(), icon).length);
    }

    @Test public void badHashLengthDimensionAndMagicNeverReplaceACompleteBlob() throws Exception {
        AppIconCache cache = cache(temporary.newFolder());
        byte[] png = png(8, 8, 1); RemoteAppIcon icon = icon(SUB, GENERATION, PACKAGE, png, 8, 8);
        cache.publishReferences(Collections.singleton(icon)); AppIconCache.Snapshot token = cache.snapshot();
        cache.complete(token, icon, png, () -> { });
        byte[] bad = png.clone(); bad[bad.length / 2] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> cache.complete(token, icon, bad, () -> { }));
        assertThrows(IllegalArgumentException.class, () -> cache.complete(token, icon, new byte[57], () -> { }));
        RemoteAppIcon dimensions = icon(SUB, GENERATION, PACKAGE, png, 7, 8);
        assertThrows(IllegalArgumentException.class, () -> cache.complete(token, dimensions, png, () -> { }));
        byte[] magic = png.clone(); magic[0] = 0;
        RemoteAppIcon wrongMagic = icon(SUB, GENERATION, PACKAGE, magic, 8, 8);
        assertThrows(IllegalArgumentException.class, () -> cache.complete(token, wrongMagic, magic, () -> { }));
        assertArrayEquals(png, cache.load(icon));
    }

    @Test public void interruptedWritePreservesCompleteAndRevocationDuringReadDiscardsDelivery() throws Exception {
        File directory = temporary.newFolder(); AppIconCache cache = cache(directory);
        byte[] png = png(8, 8, 1); RemoteAppIcon icon = icon(SUB, GENERATION, PACKAGE, png, 8, 8);
        cache.publishReferences(Collections.singleton(icon)); AppIconCache.Snapshot token = cache.snapshot();
        cache.complete(token, icon, png, () -> { });
        byte[] replacement = png(8, 8, 2);
        RemoteAppIcon newer = icon(SUB, GENERATION, PACKAGE, replacement, 8, 8);
        cache.offered(token, newer);
        AtomicInteger checkpoints = new AtomicInteger();
        assertThrows(IOException.class, () -> cache.complete(token, newer, replacement, () -> {
            if (checkpoints.incrementAndGet() == 3) throw new IOException("Interrupted before rename");
        }));
        assertNull(cache.load(newer)); cache.offered(token, icon);
        assertArrayEquals(png, cache.load(icon));
        AppIconCache[] holder = new AppIconCache[1];
        holder[0] = new AppIconCache(directory, (identity, bytes) -> {
            identity.validatePng(bytes); holder[0].publishReferences(Collections.emptyList());
        });
        holder[0].publishReferences(Collections.singleton(icon));
        assertNull(holder[0].load(icon));
    }

    @Test public void corruptSameLengthFileIsMissingAndNextCompleteRepairsIt() throws Exception {
        File directory = temporary.newFolder(); AppIconCache cache = cache(directory);
        byte[] png = png(8, 8, 1); RemoteAppIcon icon = icon(SUB, GENERATION, PACKAGE, png, 8, 8);
        cache.publishReferences(Collections.singleton(icon)); AppIconCache.Snapshot token = cache.snapshot();
        cache.complete(token, icon, png, () -> { });
        try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(directory.toPath())) {
            java.nio.file.Path file = paths.filter(p -> p.toString().endsWith(".png")).findFirst().orElseThrow(AssertionError::new);
            Files.write(file, new byte[png.length]);
        }
        assertNull(cache.load(icon));
        cache.complete(token, icon, png, () -> { }); assertArrayEquals(png, cache.load(icon));
    }

    @Test public void perGenerationEntryAndDiskBudgetsIncludeTemporaryReservation() throws Exception {
        File directory = temporary.newFolder(); AppIconCache cache = cache(directory);
        byte[] png = png(128, 128, 1);
        List<RemoteAppIcon> refs = new ArrayList<>();
        for (int i = 0; i < 260; i++) refs.add(icon(SUB, GENERATION, "example.app" + i, png, 128, 128));
        cache.publishReferences(refs); AppIconCache.Snapshot token = cache.snapshot();
        for (RemoteAppIcon ref : refs) cache.complete(token, ref, png, () -> { });
        try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(directory.toPath())) {
            List<java.nio.file.Path> files = new ArrayList<>(); paths.filter(Files::isRegularFile).forEach(files::add);
            assertTrue(files.size() <= AppIconCache.MAX_ENTRIES);
            long bytes = 0; for (java.nio.file.Path path : files) bytes += Files.size(path);
            assertTrue(bytes <= AppIconCache.MAX_DISK_BYTES);
            for (java.nio.file.Path path : files) assertTrue(path.getFileName().toString().matches("[0-9a-f]{64}\\.png"));
        }
        // Exercise the entry cap independently from the byte cap with a small PNG.
        File smallRoot = temporary.newFolder(); AppIconCache small = cache(smallRoot); byte[] tiny = png(1, 1, 1);
        List<RemoteAppIcon> smallRefs = new ArrayList<>();
        for (int i = 0; i < 260; i++) smallRefs.add(icon(SUB, GENERATION, "example.tiny" + i, tiny, 1, 1));
        small.publishReferences(smallRefs);
        for (RemoteAppIcon ref : smallRefs) small.complete(small.snapshot(), ref, tiny, () -> { });
        try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(smallRoot.toPath())) {
            assertEquals(256, paths.filter(Files::isRegularFile).count());
        }
    }

    @Test public void partialsAreHashBoundBoundedAndClearedOnReferenceRemoval() throws Exception {
        AppIconCache cache = cache(temporary.newFolder()); byte[] png = png(8, 8, 1);
        List<RemoteAppIcon> refs = new ArrayList<>();
        for (int i = 0; i < 9; i++) refs.add(icon(SUB, GENERATION, "example.app" + i, png, 8, 8));
        cache.publishReferences(refs); AppIconCache.Snapshot token = cache.snapshot();
        for (RemoteAppIcon ref : refs) cache.partial(token, ref, new byte[] {1});
        assertEquals(0, cache.resume(token, refs.get(0)).length); assertEquals(1, cache.resume(token, refs.get(8)).length);
        byte[] changed = png(8, 8, 2); RemoteAppIcon newer = icon(SUB, GENERATION, "example.app8", changed, 8, 8);
        cache.offered(token, newer); assertEquals(0, cache.resume(token, newer).length);
        cache.publishReferences(Collections.emptyList()); assertEquals(0, cache.resume(token, refs.get(8)).length);
    }

    @Test public void pathTraversalAndDuplicateReferencesFailClosed() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new RemoteAppIcon(SUB, GENERATION, "../outside", null));
        assertThrows(IllegalArgumentException.class, () -> new RemoteAppIcon(SUB, GENERATION, PACKAGE,
                new RemoteAppIcon.Descriptor("https://example.invalid/icon", 57, 1, 1)));
        AppIconCache cache = cache(temporary.newFolder());
        assertThrows(IllegalArgumentException.class, () -> cache.publishReferences(java.util.Arrays.asList(bare(), bare())));
        assertFalse(cache.currentAuthority().permits(bare()));
    }

    @Test public void deadlineGuardBeforeRenamePreventsCompletePublication() throws Exception {
        AppIconCache cache = cache(temporary.newFolder()); byte[] png = png(8, 8, 1);
        RemoteAppIcon icon = icon(SUB, GENERATION, PACKAGE, png, 8, 8);
        cache.publishReferences(Collections.singleton(icon)); AtomicInteger checks = new AtomicInteger();
        assertThrows(java.net.SocketTimeoutException.class, () -> cache.complete(cache.snapshot(), icon, png, () -> {
            if (checks.incrementAndGet() == 3) throw new java.net.SocketTimeoutException("Controlled deadline before rename");
        }));
        assertNull(cache.load(icon));
    }

    @Test public void recentlyReadCompletePngSurvivesEntryEviction() throws Exception {
        File directory = temporary.newFolder(); AppIconCache cache = cache(directory); byte[] png = png(1, 1, 1);
        List<RemoteAppIcon> refs = new ArrayList<>();
        for (int i = 0; i < 257; i++) refs.add(icon(SUB, GENERATION, "example.app" + i, png, 1, 1));
        cache.publishReferences(refs);
        for (int i = 0; i < 256; i++) cache.complete(cache.snapshot(), refs.get(i), png, () -> { });
        try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(directory.toPath())) {
            List<java.nio.file.Path> files = new ArrayList<>(); paths.filter(Files::isRegularFile).forEach(files::add);
            for (java.nio.file.Path path : files) Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(1));
        }
        assertArrayEquals(png, cache.load(refs.get(0)));
        cache.complete(cache.snapshot(), refs.get(256), png, () -> { });
        assertArrayEquals(png, cache.load(refs.get(0)));
        int missing = 0; for (int i = 1; i < 256; i++) if (cache.load(refs.get(i)) == null) missing++;
        assertEquals(1, missing);
    }

    @Test public void strictPngPixelsAcceptLegalDepthColorAndAdam7Geometry() throws Exception {
        int[][] formats = {{0, 1}, {0, 2}, {0, 4}, {0, 8}, {0, 16}, {2, 8}, {2, 16},
                {3, 1}, {3, 2}, {3, 4}, {3, 8}, {4, 8}, {4, 16}, {6, 8}, {6, 16}};
        for (int[] format : formats) for (boolean adam7 : new boolean[] {false, true}) {
            for (int[] size : new int[][] {{1, 1}, {13, 9}}) {
                byte[] rows = pixelRows(size[0], size[1], format[1], format[0], adam7);
                byte[] png = pixelPng(size[0], size[1], format[1], format[0], adam7, deflate(rows), 0, false, 2);
                IconPng.validatePixels(icon(SUB, GENERATION, PACKAGE, png, size[0], size[1]), png);
            }
        }
        byte[] largest = pixelRows(128, 128, 16, 6, true);
        assertEquals(131_312, largest.length); // 16-bit RGBA plus 240 Adam7 filter bytes.
        byte[] png = pixelPng(128, 128, 16, 6, true, deflate(largest), 0, false, 0);
        IconPng.validatePixels(icon(SUB, GENERATION, PACKAGE, png, 128, 128), png);
    }

    @Test public void strictPngPixelsRejectZeroIdatMissingPixelsAndTruncatedZlib() throws Exception {
        byte[] rows = pixelRows(3, 2, 8, 6, false), compressed = deflate(rows);
        for (byte[] invalid : new byte[][] {new byte[16], deflate(java.util.Arrays.copyOf(rows, rows.length - 1)),
                java.util.Arrays.copyOf(compressed, compressed.length - 1), java.util.Arrays.copyOf(compressed, compressed.length - 4)}) {
            byte[] png = pixelPng(3, 2, 8, 6, false, invalid, 0, false, 0);
            RemoteAppIcon identity = icon(SUB, GENERATION, PACKAGE, png, 3, 2);
            identity.validatePng(png); // Every rejection is beyond valid descriptor/hash/header/chunk CRC.
            assertThrows(IllegalArgumentException.class, () -> IconPng.validatePixels(identity, png));
        }
    }

    @Test public void strictPngPixelsRejectExcessPixelsChecksumAndTrailingStreams() throws Exception {
        byte[] rows = pixelRows(3, 2, 8, 6, false), compressed = deflate(rows);
        byte[] checksum = compressed.clone(); checksum[checksum.length - 1] ^= 1;
        byte[] doubled = new byte[compressed.length * 2];
        System.arraycopy(compressed, 0, doubled, 0, compressed.length); System.arraycopy(compressed, 0, doubled, compressed.length, compressed.length);
        for (byte[] invalid : new byte[][] {deflate(java.util.Arrays.copyOf(rows, rows.length + 1)), checksum,
                java.util.Arrays.copyOf(compressed, compressed.length + 1), doubled}) {
            byte[] png = pixelPng(3, 2, 8, 6, false, invalid, 0, false, 0);
            RemoteAppIcon identity = icon(SUB, GENERATION, PACKAGE, png, 3, 2); identity.validatePng(png);
            assertThrows(IllegalArgumentException.class, () -> IconPng.validatePixels(identity, png));
        }
    }

    @Test public void strictPngPixelsAcceptSplitIdatButRejectNonconsecutiveData() throws Exception {
        byte[] compressed = deflate(pixelRows(3, 2, 8, 6, false));
        byte[] split = pixelPng(3, 2, 8, 6, false, compressed, 1, false, 0);
        IconPng.validatePixels(icon(SUB, GENERATION, PACKAGE, split, 3, 2), split);
        byte[] separated = pixelPng(3, 2, 8, 6, false, compressed, 1, true, 0);
        RemoteAppIcon identity = icon(SUB, GENERATION, PACKAGE, separated, 3, 2); identity.validatePng(separated);
        assertThrows(IllegalArgumentException.class, () -> IconPng.validatePixels(identity, separated));
    }

    @Test public void strictPngPixelsCheckFiltersAndUnfilteredPaletteSamples() throws Exception {
        // Two indexed rows: Sub produces [0,1]; Up then produces [1,0].
        byte[] indexed = {1, 0, 1, 2, 1, (byte) 255};
        byte[] png = pixelPng(2, 2, 8, 3, false, deflate(indexed), 0, false, 2);
        IconPng.validatePixels(icon(SUB, GENERATION, PACKAGE, png, 2, 2), png);
        for (int kind = 0; kind <= 4; kind++) {
            byte[] rows = {(byte) kind, 0, 0, (byte) kind, 0, 0};
            byte[] valid = pixelPng(2, 2, 8, 3, false, deflate(rows), 0, false, 2);
            IconPng.validatePixels(icon(SUB, GENERATION, PACKAGE, valid, 2, 2), valid);
        }
        for (byte[] rows : new byte[][] {{5, 0, 0, 0, 0, 0}, {0, 2, 0, 0, 0, 0}}) {
            byte[] invalid = pixelPng(2, 2, 8, 3, false, deflate(rows), 0, false, 2);
            RemoteAppIcon identity = icon(SUB, GENERATION, PACKAGE, invalid, 2, 2); identity.validatePng(invalid);
            assertThrows(IllegalArgumentException.class, () -> IconPng.validatePixels(identity, invalid));
        }
        byte[] packed = pixelPng(1, 1, 2, 3, false, deflate(new byte[] {0, (byte) 0x80}), 0, false, 2);
        assertThrows(IllegalArgumentException.class, () -> IconPng.validatePixels(icon(SUB, GENERATION, PACKAGE, packed, 1, 1), packed));
    }

    @Test public void strictPngPixelsRequireEveryAdam7PassAndNoExtraRows() throws Exception {
        byte[] rows = pixelRows(13, 9, 8, 6, true); assertEquals(487, rows.length);
        for (byte[] invalid : new byte[][] {java.util.Arrays.copyOf(rows, rows.length - 1),
                java.util.Arrays.copyOfRange(rows, 18, rows.length), java.util.Arrays.copyOf(rows, rows.length + 1)}) {
            byte[] png = pixelPng(13, 9, 8, 6, true, deflate(invalid), 0, false, 0);
            RemoteAppIcon identity = icon(SUB, GENERATION, PACKAGE, png, 13, 9); identity.validatePng(png);
            assertThrows(IllegalArgumentException.class, () -> IconPng.validatePixels(identity, png));
        }
    }

    private static byte[] pixelRows(int width, int height, int depth, int color, boolean adam7) {
        int channels = color == 2 ? 3 : color == 4 ? 2 : color == 6 ? 4 : 1;
        int[][] passes = adam7 ? new int[][] {{0, 0, 8, 8}, {4, 0, 8, 8}, {0, 4, 4, 8},
                {2, 0, 4, 4}, {0, 2, 2, 4}, {1, 0, 2, 2}, {0, 1, 1, 2}} : new int[][] {{0, 0, 1, 1}};
        java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
        for (int[] pass : passes) {
            if (pass[0] >= width || pass[1] >= height) continue;
            int pixels = 0; for (int x = pass[0]; x < width; x += pass[2]) pixels++;
            int bytes = (pixels * channels * depth + 7) / 8;
            for (int y = pass[1]; y < height; y += pass[3]) { raw.write(0); for (int b = 0; b < bytes; b++) raw.write(0); }
        }
        return raw.toByteArray();
    }

    private static byte[] deflate(byte[] rows) throws IOException {
        java.io.ByteArrayOutputStream compressed = new java.io.ByteArrayOutputStream();
        try (java.util.zip.DeflaterOutputStream output = new java.util.zip.DeflaterOutputStream(compressed)) { output.write(rows); }
        return compressed.toByteArray();
    }

    private static byte[] pixelPng(int width, int height, int depth, int color, boolean adam7, byte[] compressed,
            int split, boolean separate, int paletteEntries) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); java.io.DataOutputStream png = new java.io.DataOutputStream(bytes);
        png.writeLong(0x89504e470d0a1a0aL);
        java.io.ByteArrayOutputStream header = new java.io.ByteArrayOutputStream(); java.io.DataOutputStream ihdr = new java.io.DataOutputStream(header);
        ihdr.writeInt(width); ihdr.writeInt(height); ihdr.write(new byte[] {(byte) depth, (byte) color, 0, 0, (byte) (adam7 ? 1 : 0)});
        pixelChunk(png, 0x49484452, header.toByteArray());
        if (color == 3) pixelChunk(png, 0x504c5445, new byte[paletteEntries * 3]);
        pixelChunk(png, 0x49444154, new byte[0]);
        for (int offset = 0; offset < compressed.length;) {
            int end = split == 0 ? compressed.length : Math.min(compressed.length, offset + split);
            pixelChunk(png, 0x49444154, java.util.Arrays.copyOfRange(compressed, offset, end));
            if (separate && offset == 0) pixelChunk(png, 0x74455874, new byte[] {'k', 0, 'v'});
            offset = end;
        }
        pixelChunk(png, 0x49444154, new byte[0]); pixelChunk(png, 0x49454e44, new byte[0]);
        return bytes.toByteArray();
    }

    private static void pixelChunk(java.io.DataOutputStream output, int type, byte[] data) throws IOException {
        output.writeInt(data.length); output.writeInt(type); output.write(data);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(new byte[] {(byte) (type >>> 24), (byte) (type >>> 16), (byte) (type >>> 8), (byte) type}); crc.update(data);
        output.writeInt((int) crc.getValue());
    }
}
