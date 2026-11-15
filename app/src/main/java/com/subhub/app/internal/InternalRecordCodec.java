package com.subhub.app.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
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

    static byte[] encode(InternalSubRecord record) { return encode(record, 2); }
    private static byte[] encode(InternalSubRecord record, int version) {
        if (version == 1 && record.tasksResumeAfterMillis != 0) throw new IllegalArgumentException("Legacy boundary loss");
        try {
            ByteArrayOutputStream bytes = new RecordEncoding.BoundedBytes(MAX_BYTES, "Oversized internal record");
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC); out.writeInt(version); text(out, record.targetId); text(out, record.generation);
            out.writeLong(record.revision); out.writeLong(record.configurationRevision); out.writeBoolean(record.visible);
            if (version == 2) out.writeLong(record.tasksResumeAfterMillis);
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
            out.write(RecordEncoding.digest(bytes.toByteArray())); out.flush();
            return bytes.toByteArray();
        } catch (IOException invalid) { throw new IllegalArgumentException("Invalid internal record encoding", invalid); }
    }

    static InternalSubRecord decode(byte[] bytes) {
        if (bytes == null || bytes.length < 40 || bytes.length > MAX_BYTES)
            throw new IllegalArgumentException("Invalid internal record size");
        byte[] body = Arrays.copyOf(bytes, bytes.length - 32);
        if (!MessageDigest.isEqual(RecordEncoding.digest(body), Arrays.copyOfRange(bytes, body.length, bytes.length)))
            throw new IllegalArgumentException("Corrupt internal record");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
            if (in.readInt() != MAGIC) throw new IOException("Unknown schema");
            int version = in.readInt();
            if (version != 1 && version != 2) throw new IOException("Unknown schema");
            String target = text(in), generation = text(in);
            long revision = in.readLong(), configRevision = in.readLong();
            boolean visible = in.readBoolean();
            long tasksResumeAfterMillis = version == 2 ? in.readLong() : 0;
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
                    visible, tasksResumeAfterMillis, profile, sections);
            if (in.available() != 0 || !Arrays.equals(bytes, encode(record, version))) throw new IOException("Noncanonical record");
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
        RecordEncoding.writeText(out, value, MAX_BYTES - 64);
    }

    private static String text(DataInputStream in) throws IOException {
        return RecordEncoding.readText(in, count(in, MAX_BYTES - 64));
    }
}
