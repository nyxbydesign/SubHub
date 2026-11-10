package com.subhub.app.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.zip.CRC32;

/** Exact remote scope, including when an older catalog supplies no image descriptor. */
public final class RemoteAppIcon {
    public static final int MAX_PIXELS = 128, MAX_BYTES = 131072;
    public final String peer, generation, packageName;
    public final Descriptor descriptor;

    public RemoteAppIcon(String peer, String generation, String packageName, Descriptor descriptor) {
        this.peer = scope(peer);
        this.generation = scope(generation);
        if (packageName == null || packageName.length() > 255
                || !packageName.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*"))
            throw new IllegalArgumentException("Invalid remote package");
        this.packageName = packageName;
        this.descriptor = descriptor;
    }

    private static String scope(String value) {
        if (value == null || value.isEmpty() || value.length() > 255 || !value.equals(value.trim()))
            throw new IllegalArgumentException("Missing or invalid remote scope");
        for (int i = 0; i < value.length(); i++)
            if (Character.isISOControl(value.charAt(i))) throw new IllegalArgumentException("Invalid remote scope");
        return value;
    }

    public static final class Descriptor {
        public final String sha256;
        public final int byteLength, width, height;

        public Descriptor(String sha256, int byteLength, int width, int height) {
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}") || byteLength < 57 || byteLength > MAX_BYTES
                    || width < 1 || width > MAX_PIXELS || height < 1 || height > MAX_PIXELS)
                throw new IllegalArgumentException("Invalid remote PNG descriptor");
            this.sha256 = sha256; this.byteLength = byteLength; this.width = width; this.height = height;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Descriptor)) return false;
            Descriptor that = (Descriptor) other;
            return byteLength == that.byteLength && width == that.width && height == that.height && sha256.equals(that.sha256);
        }
        @Override public int hashCode() { return Objects.hash(sha256, byteLength, width, height); }
    }

    /** Runs on bounded owned bytes before invoking any platform image decoder. */
    public void validatePng(byte[] bytes) {
        Descriptor d = descriptor;
        if (d == null || bytes == null || bytes.length != d.byteLength || bytes.length > MAX_BYTES)
            throw new IllegalArgumentException("Remote PNG length mismatch");
        byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        for (int i = 0; i < signature.length; i++)
            if (bytes[i] != signature[i]) throw new IllegalArgumentException("Remote PNG signature mismatch");
        if (number(bytes, 8) != 13 || number(bytes, 12) != 0x49484452
                || number(bytes, 16) != d.width || number(bytes, 20) != d.height
                || !validDepth(bytes[24] & 255, bytes[25] & 255) || bytes[26] != 0 || bytes[27] != 0
                || (bytes[28] != 0 && bytes[28] != 1))
            throw new IllegalArgumentException("Remote PNG header mismatch");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            if (!d.sha256.contentEquals(hex)) throw new IllegalArgumentException("Remote PNG digest mismatch");
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        boolean data = false;
        for (int offset = 8; offset <= bytes.length - 12;) {
            int length = number(bytes, offset), type = number(bytes, offset + 4);
            if (length < 0 || length > bytes.length - offset - 12 || (offset != 8 && type == 0x49484452))
                throw new IllegalArgumentException("Invalid remote PNG chunk");
            CRC32 crc = new CRC32(); crc.update(bytes, offset + 4, length + 4);
            if ((int) crc.getValue() != number(bytes, offset + 8 + length))
                throw new IllegalArgumentException("Remote PNG checksum mismatch");
            if (type == 0x49444154) data = true;
            offset += length + 12;
            if (type == 0x49454e44) {
                if (length == 0 && data && offset == bytes.length) return;
                throw new IllegalArgumentException("Invalid remote PNG end");
            }
        }
        throw new IllegalArgumentException("Incomplete remote PNG");
    }

    private static boolean validDepth(int depth, int color) {
        switch (color) {
            case 0: return depth == 1 || depth == 2 || depth == 4 || depth == 8 || depth == 16;
            case 2: case 4: case 6: return depth == 8 || depth == 16;
            case 3: return depth == 1 || depth == 2 || depth == 4 || depth == 8;
            default: return false;
        }
    }

    private static int number(byte[] bytes, int offset) {
        return (bytes[offset] & 255) << 24 | (bytes[offset + 1] & 255) << 16
                | (bytes[offset + 2] & 255) << 8 | bytes[offset + 3] & 255;
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof RemoteAppIcon)) return false;
        RemoteAppIcon that = (RemoteAppIcon) other;
        return peer.equals(that.peer) && generation.equals(that.generation) && packageName.equals(that.packageName)
                && Objects.equals(descriptor, that.descriptor);
    }
    @Override public int hashCode() { return Objects.hash(peer, generation, packageName, descriptor); }
}
