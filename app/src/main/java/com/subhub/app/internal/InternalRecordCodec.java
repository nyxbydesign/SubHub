package com.subhub.app.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Bounded, typed, canonical storage encoding. No serialization of Android or runtime objects. */
final class InternalRecordCodec {
    static final int MAX_BYTES = 1024 * 1024;
    private static final int MAGIC = 0x53484953;
    private InternalRecordCodec() { }

    static byte[] encode(InternalSubRecord record) {
        try {
            ByteArrayOutputStream bytes = new BoundedBytes();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC); out.writeInt(1); text(out, record.targetId); text(out, record.generation);
            out.writeLong(record.revision); out.writeLong(record.configurationRevision); out.writeBoolean(record.visible);
            text(out, record.profile.name); text(out, record.profile.about); text(out, record.profile.avatar);
            text(out, record.profile.gender); out.writeInt(record.profile.age);
            out.writeInt(record.configuration().size());
            for (Map.Entry<String, Map<String, Object>> section : new TreeMap<>(record.configuration()).entrySet()) {
                text(out, section.getKey()); out.writeInt(section.getValue().size());
                for (Map.Entry<String, Object> entry : new TreeMap<>(section.getValue()).entrySet()) {
                    text(out, entry.getKey()); value(out, entry.getValue());
                }
            }
            out.flush();
            if (bytes.size() > MAX_BYTES - 32) throw new IllegalArgumentException("Oversized internal record");
            out.write(digest(bytes.toByteArray())); out.flush();
            return bytes.toByteArray();
        } catch (IOException invalid) { throw new IllegalArgumentException("Invalid internal record encoding", invalid); }
    }

    static InternalSubRecord decode(byte[] bytes) {
        if (bytes == null || bytes.length < 40 || bytes.length > MAX_BYTES)
            throw new IllegalArgumentException("Invalid internal record size");
        byte[] body = Arrays.copyOf(bytes, bytes.length - 32);
        if (!MessageDigest.isEqual(digest(body), Arrays.copyOfRange(bytes, body.length, bytes.length)))
            throw new IllegalArgumentException("Corrupt internal record");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
            if (in.readInt() != MAGIC || in.readInt() != 1) throw new IOException("Unknown schema");
            String target = text(in), generation = text(in);
            long revision = in.readLong(), configRevision = in.readLong();
            boolean visible = in.readBoolean();
            InternalProfile profile = new InternalProfile(text(in), text(in), text(in), text(in), in.readInt());
            Map<String, Map<String, Object>> sections = new LinkedHashMap<>();
            int count = count(in, 16);
            for (int i = 0; i < count; i++) {
                String section = text(in); Map<String, Object> values = new LinkedHashMap<>();
                int fields = count(in, InternalSubRecord.MAX_FIELDS);
                for (int j = 0; j < fields; j++)
                    if (values.put(text(in), value(in)) != null) throw new IOException("Duplicate setting");
                if (sections.put(section, values) != null) throw new IOException("Duplicate section");
            }
            InternalSubRecord record = new InternalSubRecord(target, generation, revision, configRevision,
                    visible, profile, sections);
            if (in.available() != 0 || !Arrays.equals(bytes, encode(record))) throw new IOException("Noncanonical record");
            return record;
        } catch (IOException | IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Unreadable internal record", invalid);
        }
    }

    private static void value(DataOutputStream out, Object value) throws IOException {
        if (value instanceof String) { out.writeByte(1); text(out, (String) value); }
        else if (value instanceof Boolean) { out.writeByte(2); out.writeBoolean((Boolean) value); }
        else if (value instanceof Integer) { out.writeByte(3); out.writeInt((Integer) value); }
        else if (value instanceof Long) { out.writeByte(4); out.writeLong((Long) value); }
        else if (value instanceof Float) { out.writeByte(5); out.writeFloat((Float) value); }
        else if (value instanceof Set<?>) {
            out.writeByte(6); Set<String> sorted = new TreeSet<>();
            for (Object item : (Set<?>) value) sorted.add((String) item);
            out.writeInt(sorted.size()); for (String item : sorted) text(out, item);
        } else throw new IllegalArgumentException("Unsupported setting");
    }
    private static Object value(DataInputStream in) throws IOException {
        switch (in.readUnsignedByte()) {
            case 1: return text(in);
            case 2: return in.readBoolean();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6:
                Set<String> values = new LinkedHashSet<>(); int count = count(in, InternalSubRecord.MAX_SET_ENTRIES);
                for (int i = 0; i < count; i++) if (!values.add(text(in))) throw new IOException("Duplicate set value");
                return values;
            default: throw new IOException("Unknown value type");
        }
    }
    private static int count(DataInputStream in, int maximum) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > maximum) throw new IOException("Invalid count");
        return count;
    }
    private static void text(DataOutputStream out, String value) throws IOException {
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value));
        if (encoded.remaining() > MAX_BYTES - 64) throw new IllegalArgumentException("Oversized text");
        byte[] bytes = new byte[encoded.remaining()]; encoded.get(bytes);
        out.writeInt(bytes.length); out.write(bytes);
    }
    private static String text(DataInputStream in) throws IOException {
        int length = count(in, MAX_BYTES - 64);
        if (length > in.available()) throw new IOException("Truncated text");
        byte[] bytes = new byte[length]; in.readFully(bytes);
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static final class BoundedBytes extends ByteArrayOutputStream {
        @Override public synchronized void write(int value) {
            if (count >= MAX_BYTES) throw new IllegalArgumentException("Oversized internal record");
            super.write(value);
        }
        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            if (length > MAX_BYTES - count) throw new IllegalArgumentException("Oversized internal record");
            super.write(bytes, offset, length);
        }
    }
}
