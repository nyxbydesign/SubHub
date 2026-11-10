package com.subhub.app.remote;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** One typed coverage upgrade, never a serializable application command or a source of user authority. */
final class LinkingStructuralReservation {
    private static final String DOMAIN = "subhub.linking.structural-coverage.v1";
    static final int MAX_BYTES = 1024;
    final String database, epoch, oldCommand, oldDigest, initial, snapshotScope;
    final long oldRevision, readyRevision, nextRevision;
    private final byte[] encoded;
    final String command;

    LinkingStructuralReservation(String database, String epoch, long oldRevision, String oldCommand,
            String oldDigest, String initial, boolean initialized, long readyRevision,
            String snapshotScope, long migrationComplete) {
        this.database = hash(database); this.epoch = hex(epoch, 32);
        this.oldCommand = hash(oldCommand); this.oldDigest = hash(oldDigest); this.initial = hash(initial);
        if (!initialized || oldRevision < 1 || readyRevision < 1 || readyRevision > oldRevision || migrationComplete != 1)
            throw new IllegalArgumentException("Initialized structural before-image required");
        this.oldRevision = oldRevision; this.readyRevision = readyRevision;
        nextRevision = Math.addExact(oldRevision, 1);
        this.snapshotScope = snapshotScope == null ? null : hash(snapshotScope);
        encoded = encode(); command = sha256(encoded);
    }

    byte[] signingBytes() { return encoded.clone(); }

    boolean forDatabase(String expected) { return database.equals(expected); }

    static LinkingStructuralReservation decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES)
            throw new IllegalArgumentException("Invalid structural payload size");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (!DOMAIN.equals(text(input, 80, false)) || input.readInt() != 1
                    || input.readInt() != 8 || input.readInt() != 9 || input.readInt() != 1 || input.readInt() != 2)
                throw new IllegalArgumentException("Unsupported structural payload");
            String database = text(input, 64, false), epoch = text(input, 32, false);
            long revision = input.readLong();
            String command = text(input, 64, false), digest = text(input, 64, false), initial = text(input, 64, false);
            if (input.readUnsignedByte() != 1) throw new IllegalArgumentException("Initialized before-image required");
            long ready = input.readLong(); String scope = text(input, 64, true);
            long complete = input.readLong(), next = input.readLong();
            LinkingStructuralReservation result = new LinkingStructuralReservation(database, epoch, revision,
                    command, digest, initial, true, ready, scope, complete);
            if (next != result.nextRevision || input.available() != 0 || !Arrays.equals(bytes, result.encoded))
                throw new IllegalArgumentException("Noncanonical structural payload");
            return result;
        } catch (IOException malformed) {
            throw new IllegalArgumentException("Truncated structural payload", malformed);
        }
    }

    private byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                text(output, DOMAIN); output.writeInt(1);
                output.writeInt(8); output.writeInt(9); output.writeInt(1); output.writeInt(2);
                text(output, database); text(output, epoch); output.writeLong(oldRevision);
                text(output, oldCommand); text(output, oldDigest); text(output, initial);
                output.writeByte(1); output.writeLong(readyRevision); text(output, snapshotScope);
                output.writeLong(1); output.writeLong(nextRevision);
            }
            byte[] result = bytes.toByteArray();
            if (result.length > MAX_BYTES) throw new IllegalArgumentException("Structural payload too large");
            return result;
        } catch (IOException impossible) { throw new AssertionError(impossible); }
    }

    private static void text(DataOutputStream output, String value) throws IOException {
        if (value == null) { output.writeInt(-1); return; }
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII); output.writeInt(bytes.length); output.write(bytes);
    }

    private static String text(DataInputStream input, int limit, boolean nullable) throws IOException {
        int length = input.readInt();
        if (nullable && length == -1) return null;
        if (length < 1 || length > limit || length > input.available())
            throw new IllegalArgumentException("Invalid structural text length");
        byte[] bytes = new byte[length]; input.readFully(bytes);
        for (byte value : bytes) if (value < 0) throw new IllegalArgumentException("Structural ASCII required");
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static String hash(String value) { return hex(value, 64); }

    private static String hex(String value, int length) {
        if (value == null || value.length() != length || !value.matches("[0-9a-f]+"))
            throw new IllegalArgumentException("Invalid structural digest");
        return value;
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) result.append(Character.forDigit((value >>> 4) & 15, 16))
                    .append(Character.forDigit(value & 15, 16));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
