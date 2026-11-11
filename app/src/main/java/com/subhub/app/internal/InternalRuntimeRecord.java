package com.subhub.app.internal;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Canonical, bounded, typed record for one internal owner's atomic runtime-data lanes. */
public final class InternalRuntimeRecord {
    static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int MAGIC = 0x53485244, MAX_NAMESPACES = 64, MAX_FIELDS = 100_000;
    public final String identity;
    public final long revision;
    private final Map<String, Map<String, Object>> namespaces;

    InternalRuntimeRecord(String identity, long revision, Map<String, ? extends Map<String, ?>> values) {
        if (identity == null || !UUID.fromString(identity).toString().equals(identity) || revision < 0)
            throw new IllegalArgumentException("Invalid runtime-data identity or revision");
        if (values.size() > MAX_NAMESPACES) throw new IllegalArgumentException("Too many runtime-data namespaces");
        this.identity = identity; this.revision = revision;
        Map<String, Map<String, Object>> result = new TreeMap<>();
        for (Map.Entry<String, ? extends Map<String, ?>> section : values.entrySet()) {
            requireNamespace(section.getKey());
            if (section.getValue().size() > MAX_FIELDS) throw new IllegalArgumentException("Too many runtime-data fields");
            Map<String, Object> fields = new TreeMap<>();
            for (Map.Entry<String, ?> entry : section.getValue().entrySet()) {
                requireKey(entry.getKey()); fields.put(entry.getKey(), copyValue(entry.getValue()));
            }
            // Empty namespaces are canonical too: callers may need a schema/provisioning witness.
            result.put(section.getKey(), Collections.unmodifiableMap(fields));
        }
        namespaces = Collections.unmodifiableMap(result);
    }
    public Map<String, Map<String, Object>> namespaces() { return namespaces; }
    static void requireNamespace(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]{1,120}") || value.equals(".") || value.equals(".."))
            throw new IllegalArgumentException("Invalid runtime-data namespace");
    }
    static void requireKey(String key) {
        if (key == null || key.length() > 4096 || key.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid runtime-data key");
    }
    static Object copyValue(Object value) {
        if (value instanceof String || value instanceof Integer || value instanceof Long
                || value instanceof Boolean || value instanceof Float) return value;
        if (value instanceof Set<?>) {
            Set<String> copy = new TreeSet<>();
            if (((Set<?>) value).size() > MAX_FIELDS) throw new IllegalArgumentException("Oversized runtime-data set");
            for (Object entry : (Set<?>) value) {
                if (!(entry instanceof String)) throw new IllegalArgumentException("Invalid runtime-data set member");
                copy.add((String) entry);
            }
            return Collections.unmodifiableSet(copy);
        }
        throw new IllegalArgumentException("Unsupported runtime-data value");
    }
    @Override public boolean equals(Object value) {
        if (!(value instanceof InternalRuntimeRecord)) return false;
        InternalRuntimeRecord other = (InternalRuntimeRecord) value;
        return revision == other.revision && identity.equals(other.identity) && namespaces.equals(other.namespaces);
    }
    @Override public int hashCode() { return Objects.hash(identity, revision, namespaces); }

    static byte[] encode(InternalRuntimeRecord record) {
        try {
            BoundedBytes bytes = new BoundedBytes(); DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC); out.writeInt(1); text(out, InternalStorageScope.TARGET_ID);
            text(out, record.identity); out.writeLong(record.revision); out.writeInt(record.namespaces.size());
            for (Map.Entry<String, Map<String, Object>> section : record.namespaces.entrySet()) {
                text(out, section.getKey()); out.writeInt(section.getValue().size());
                for (Map.Entry<String, Object> entry : section.getValue().entrySet()) {
                    text(out, entry.getKey()); value(out, entry.getValue());
                }
            }
            out.flush(); byte[] body = bytes.toByteArray();
            out.write(digest(body)); out.flush(); return bytes.toByteArray();
        } catch (IOException invalid) { throw new IllegalArgumentException("Invalid runtime-data encoding", invalid); }
    }
    static InternalRuntimeRecord decode(byte[] bytes) {
        if (bytes == null || bytes.length < 40 || bytes.length > MAX_BYTES)
            throw new IllegalArgumentException("Invalid runtime-data record size");
        byte[] body = Arrays.copyOf(bytes, bytes.length - 32);
        if (!MessageDigest.isEqual(digest(body), Arrays.copyOfRange(bytes, body.length, bytes.length)))
            throw new IllegalArgumentException("Corrupt runtime-data checksum");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
            if (in.readInt() != MAGIC || in.readInt() != 1 || !InternalStorageScope.TARGET_ID.equals(text(in)))
                throw new IOException("Invalid runtime-data schema or owner");
            String identity = text(in); long revision = in.readLong();
            int sections = count(in, MAX_NAMESPACES); Map<String, Map<String, Object>> values = new TreeMap<>();
            for (int i = 0; i < sections; i++) {
                String section = text(in); int size = count(in, MAX_FIELDS); Map<String, Object> fields = new TreeMap<>();
                for (int j = 0; j < size; j++)
                    if (fields.put(text(in), value(in)) != null) throw new IOException("Duplicate runtime-data key");
                if (values.put(section, fields) != null) throw new IOException("Duplicate runtime-data namespace");
            }
            InternalRuntimeRecord record = new InternalRuntimeRecord(identity, revision, values);
            if (in.available() != 0 || !Arrays.equals(bytes, encode(record))) throw new IOException("Noncanonical runtime data");
            return record;
        } catch (IOException | IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Unreadable runtime data", invalid);
        }
    }
    private static void value(DataOutputStream out, Object value) throws IOException {
        if (value instanceof String) { out.writeByte(1); text(out, (String) value); }
        else if (value instanceof Boolean) { out.writeByte(2); out.writeBoolean((Boolean) value); }
        else if (value instanceof Integer) { out.writeByte(3); out.writeInt((Integer) value); }
        else if (value instanceof Long) { out.writeByte(4); out.writeLong((Long) value); }
        else if (value instanceof Float) { out.writeByte(5); out.writeFloat((Float) value); }
        else if (value instanceof Set<?>) {
            out.writeByte(6); out.writeInt(((Set<?>) value).size());
            for (Object item : (Set<?>) value) text(out, (String) item);
        } else throw new IllegalArgumentException("Unsupported runtime-data type");
    }
    private static Object value(DataInputStream in) throws IOException {
        switch (in.readUnsignedByte()) {
            case 1: return text(in);
            case 2: return in.readBoolean();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6:
                Set<String> values = new TreeSet<>(); int count = count(in, MAX_FIELDS);
                for (int i = 0; i < count; i++) if (!values.add(text(in))) throw new IOException("Duplicate runtime-data set value");
                return values;
            default: throw new IOException("Unknown runtime-data value type");
        }
    }
    private static int count(DataInputStream in, int maximum) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > maximum) throw new IOException("Invalid runtime-data length");
        return count;
    }
    private static void text(DataOutputStream out, String value) throws IOException {
        if (value.length() > MAX_BYTES - 64) throw new IOException("Oversized runtime-data text");
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value));
        out.writeInt(encoded.remaining()); byte[] bytes = new byte[encoded.remaining()]; encoded.get(bytes); out.write(bytes);
    }
    private static String text(DataInputStream in) throws IOException {
        int length = count(in, MAX_BYTES - 64);
        if (length > in.available()) throw new IOException("Truncated runtime-data text");
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
            if (count >= MAX_BYTES) throw new IllegalArgumentException("Oversized runtime data"); super.write(value);
        }
        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            if (length > MAX_BYTES - count) throw new IllegalArgumentException("Oversized runtime data");
            super.write(bytes, offset, length);
        }
    }
}
