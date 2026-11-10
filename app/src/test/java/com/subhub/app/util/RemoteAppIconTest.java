package com.subhub.app.util;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import org.junit.Test;

public final class RemoteAppIconTest {
    private static final String PACKAGE = "com.example.absent";

    @Test public void missingDescriptorStillRequiresAndRetainsTheCompleteRemoteScope() {
        RemoteAppIcon legacy = new RemoteAppIcon("peer-a", "generation-a", PACKAGE, null);
        assertEquals("peer-a", legacy.peer); assertEquals("generation-a", legacy.generation);
        assertEquals(PACKAGE, legacy.packageName); assertNull(legacy.descriptor);
        assertEquals(legacy, new RemoteAppIcon("peer-a", "generation-a", PACKAGE, null));
        assertEquals(legacy.hashCode(), new RemoteAppIcon("peer-a", "generation-a", PACKAGE, null).hashCode());
        assertNotEquals(legacy, new RemoteAppIcon("peer-b", "generation-a", PACKAGE, null));
        assertNotEquals(legacy, new RemoteAppIcon("peer-a", "generation-b", PACKAGE, null));
        assertNotEquals(legacy, new RemoteAppIcon("peer-a", "generation-a", PACKAGE + ".other", null));
        for (String invalid : new String[]{null, "", " ", " leading", "trailing ", "bad\nvalue"}) {
            assertThrows(IllegalArgumentException.class, () -> new RemoteAppIcon(invalid, "generation", PACKAGE, null));
            assertThrows(IllegalArgumentException.class, () -> new RemoteAppIcon("peer", invalid, PACKAGE, null));
        }
        for (String invalid : new String[]{null, "", "a..b", "local/app", " app"})
            assertThrows(IllegalArgumentException.class, () -> new RemoteAppIcon("peer", "generation", invalid, null));
        assertThrows(IllegalArgumentException.class, () -> legacy.validatePng(new byte[57]));
    }

    @Test public void identityAndDescriptorAreFinalImmutableValuesWithoutByteArrays() throws Exception {
        for (Class<?> type : Arrays.asList(RemoteAppIcon.class, RemoteAppIcon.Descriptor.class)) {
            assertTrue(Modifier.isFinal(type.getModifiers()));
            for (Field field : type.getDeclaredFields()) {
                assertTrue(Modifier.isFinal(field.getModifiers()));
                assertFalse(field.getType().isArray());
            }
        }
        byte[] bytes = png(3, 4);
        RemoteAppIcon first = icon(bytes, 3, 4);
        assertEquals(first, icon(bytes.clone(), 3, 4));
        assertEquals(first.hashCode(), icon(bytes, 3, 4).hashCode());
        assertNotEquals(first, new RemoteAppIcon(first.peer, first.generation, first.packageName,
                new RemoteAppIcon.Descriptor(first.descriptor.sha256, bytes.length, 4, 3)));
        assertNotEquals(first, new RemoteAppIcon(first.peer, first.generation, first.packageName, null));
    }

    @Test public void authoritySnapshotCopiesExactIdentitiesIncludingMissingDescriptors() throws Exception {
        RemoteAppIcon allowed = icon(png(2, 2), 2, 2);
        RemoteAppIcon legacy = new RemoteAppIcon("peer", "old-generation", PACKAGE, null);
        java.util.List<RemoteAppIcon> mutable = new java.util.ArrayList<>(Arrays.asList(allowed, legacy));
        RemoteAppIcons.Authority snapshot = new RemoteAppIcons.Authority(mutable); mutable.clear();
        assertTrue(snapshot.permits(allowed)); assertTrue(snapshot.permits(legacy));
        assertFalse(snapshot.permits(new RemoteAppIcon("other-peer", allowed.generation, PACKAGE, allowed.descriptor)));
        assertFalse(snapshot.permits(new RemoteAppIcon(allowed.peer, "other-generation", PACKAGE, allowed.descriptor)));
        assertFalse(snapshot.permits(new RemoteAppIcon(allowed.peer, allowed.generation, PACKAGE + ".other", allowed.descriptor)));
        assertFalse(snapshot.permits(new RemoteAppIcon(allowed.peer, allowed.generation, PACKAGE, null)));
        assertFalse(RemoteAppIcons.Authority.EMPTY.permits(allowed));
    }

    @Test public void descriptorRejectsUnboundedImagesAndNoncanonicalHashes() throws Exception {
        String hash = hash(png(1, 1));
        for (int length : new int[]{-1, 0, 56, RemoteAppIcon.MAX_BYTES + 1})
            assertThrows(IllegalArgumentException.class, () -> new RemoteAppIcon.Descriptor(hash, length, 1, 1));
        for (int size : new int[]{-1, 0, 129, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new RemoteAppIcon.Descriptor(hash, 100, size, 1));
            assertThrows(IllegalArgumentException.class, () -> new RemoteAppIcon.Descriptor(hash, 100, 1, size));
        }
        for (String bad : new String[]{null, "", hash.substring(1), hash.toUpperCase(java.util.Locale.ROOT), "g" + hash.substring(1)})
            assertThrows(IllegalArgumentException.class, () -> new RemoteAppIcon.Descriptor(bad, 100, 1, 1));
        new RemoteAppIcon.Descriptor(hash, RemoteAppIcon.MAX_BYTES, 128, 128);
    }

    @Test public void validatesRealPngAtBothDimensionLimitsBeforeAnyAndroidDecoder() throws Exception {
        for (int[] dimensions : new int[][]{{1, 1}, {128, 128}, {1, 128}, {128, 1}}) {
            byte[] bytes = png(dimensions[0], dimensions[1]);
            icon(bytes, dimensions[0], dimensions[1]).validatePng(bytes);
        }
    }

    @Test public void exactLengthHashSignatureAndHeaderDimensionsAreRequired() throws Exception {
        byte[] valid = png(4, 5); RemoteAppIcon icon = icon(valid, 4, 5);
        assertThrows(IllegalArgumentException.class, () -> icon.validatePng(null));
        assertThrows(IllegalArgumentException.class, () -> icon.validatePng(Arrays.copyOf(valid, valid.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> icon.validatePng(new byte[RemoteAppIcon.MAX_BYTES + 1]));
        byte[] changed = valid.clone(); changed[changed.length - 1] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> icon.validatePng(changed));
        byte[] badSignature = valid.clone(); badSignature[0] = 0;
        assertThrows(IllegalArgumentException.class, () -> icon(badSignature, 4, 5).validatePng(badSignature));
        assertThrows(IllegalArgumentException.class, () -> icon(valid, 5, 4).validatePng(valid));
        for (int offset : new int[]{8, 12, 16, 20, 24, 25, 26, 27, 28}) {
            byte[] badHeader = valid.clone(); badHeader[offset] = (byte) 255;
            assertThrows(IllegalArgumentException.class, () -> icon(badHeader, 4, 5).validatePng(badHeader));
        }
    }

    @Test public void chunkChecksumsTruncationDuplicateHeaderAndTrailingBytesFailClosed() throws Exception {
        byte[] valid = png(2, 2);
        byte[] badCrc = valid.clone(); badCrc[29] ^= 1;
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> icon(badCrc, 2, 2).validatePng(badCrc)).getMessage().contains("checksum"));
        byte[] truncated = Arrays.copyOf(valid, valid.length - 12);
        assertThrows(IllegalArgumentException.class, () -> icon(truncated, 2, 2).validatePng(truncated));
        byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
        assertThrows(IllegalArgumentException.class, () -> icon(trailing, 2, 2).validatePng(trailing));
        ByteArrayOutputStream duplicate = new ByteArrayOutputStream(); duplicate.write(valid, 0, 33);
        duplicate.write(valid, 8, 25); duplicate.write(valid, 33, valid.length - 33);
        byte[] twice = duplicate.toByteArray();
        assertThrows(IllegalArgumentException.class, () -> icon(twice, 2, 2).validatePng(twice));
    }

    private static RemoteAppIcon icon(byte[] bytes, int width, int height) throws Exception {
        return new RemoteAppIcon("peer", "generation", PACKAGE,
                new RemoteAppIcon.Descriptor(hash(bytes), bytes.length, width, height));
    }
    private static String hash(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return hex.toString();
    }
    private static byte[] png(int width, int height) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
        ByteArrayOutputStream header = new ByteArrayOutputStream(); DataOutputStream fields = new DataOutputStream(header);
        fields.writeInt(width); fields.writeInt(height); fields.write(new byte[]{8, 6, 0, 0, 0});
        chunk(output, "IHDR", header.toByteArray());
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream zlib = new DeflaterOutputStream(compressed)) {
            for (int y = 0; y < height; y++) {
                zlib.write(0);
                for (int x = 0; x < width; x++) zlib.write(new byte[]{(byte) 255, 0, 0, (byte) 255});
            }
        }
        chunk(output, "IDAT", compressed.toByteArray()); chunk(output, "IEND", new byte[0]);
        return output.toByteArray();
    }
    private static void chunk(ByteArrayOutputStream output, String type, byte[] body) throws Exception {
        DataOutputStream fields = new DataOutputStream(output); byte[] name = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        fields.writeInt(body.length); fields.write(name); fields.write(body);
        CRC32 crc = new CRC32(); crc.update(name); crc.update(body); fields.writeInt((int) crc.getValue());
    }
}
