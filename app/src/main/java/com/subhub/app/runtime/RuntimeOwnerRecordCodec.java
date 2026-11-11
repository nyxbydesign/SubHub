package com.subhub.app.runtime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/** Bounded durable evidence encoding. Decoding never creates an executable RuntimeLease. */
final class RuntimeOwnerRecordCodec {
    static final int MAX_BYTES = 32768;
    private static final int MAGIC = 0x53485254;
    private static final int VERSION = 1;
    private RuntimeOwnerRecordCodec() { }

    static byte[] encode(RuntimeOwnerStore.Record record) {
        if (record == null) throw new IllegalArgumentException("Missing runtime owner record");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC); out.writeInt(VERSION);
            out.writeLong(record.revision); string(out, record.phase.name());
            out.writeLong(record.savedConfigurationRevision);
            out.writeLong(record.appliedConfigurationRevision);
            out.writeBoolean(record.owner != null);
            if (record.owner != null) identity(out, record.owner);
            keys(out, record.requiredComponents); keys(out, record.readyComponents);
            out.flush();
            byte[] result = bytes.toByteArray();
            if (result.length > MAX_BYTES) throw new IllegalArgumentException("Oversized runtime owner record");
            return result;
        } catch (IOException impossible) { throw new AssertionError(impossible); }
    }

    static RuntimeOwnerStore.Record decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES)
            throw new IllegalArgumentException("Invalid runtime owner record size");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != MAGIC || in.readInt() != VERSION)
                throw new IllegalArgumentException("Unsupported runtime owner record");
            long revision = in.readLong();
            RuntimeOwnerStore.Phase phase = RuntimeOwnerStore.Phase.valueOf(string(in));
            long saved = in.readLong(), applied = in.readLong();
            RuntimeLease.Identity owner = in.readBoolean() ? identity(in) : null;
            RuntimeOwnerStore.Record record = new RuntimeOwnerStore.Record(
                    revision, phase, owner, saved, applied, keys(in), keys(in));
            if (in.available() != 0 || !Arrays.equals(bytes, encode(record)))
                throw new IllegalArgumentException("Noncanonical runtime owner record");
            return record;
        } catch (IOException | IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Unreadable runtime owner record", invalid);
        }
    }

    private static void identity(DataOutputStream out, RuntimeLease.Identity identity) throws IOException {
        string(out, identity.target.kind.name()); string(out, identity.target.id);
        string(out, identity.origin.kind.name());
        if (identity.origin.kind == RuntimeOrigin.Kind.REMOTE) {
            RuntimeOrigin.RemoteProof proof = identity.origin.remote;
            string(out, proof.peer); string(out, proof.relationshipGeneration); string(out, proof.commandId);
            string(out, proof.commandDigest); string(out, proof.preparedDigest);
            out.writeLong(proof.authorizationRevision);
            string(out, proof.statisticsIdentity); string(out, proof.pactIdentity); string(out, proof.captureIdentity);
        }
        string(out, identity.profileGeneration); string(out, identity.session);
        string(out, identity.activation); string(out, identity.process);
        out.writeLong(identity.initialConfigurationRevision);
    }

    private static RuntimeLease.Identity identity(DataInputStream in) throws IOException {
        RuntimeTarget.Kind kind = RuntimeTarget.Kind.valueOf(string(in));
        String id = string(in);
        RuntimeTarget target;
        if (kind == RuntimeTarget.Kind.DEVICE) {
            if (!RuntimeTarget.DEVICE.id.equals(id)) throw new IllegalArgumentException("Invalid device owner");
            target = RuntimeTarget.DEVICE;
        } else target = RuntimeTarget.internal(id);
        RuntimeOrigin.Kind originKind = RuntimeOrigin.Kind.valueOf(string(in));
        RuntimeOrigin origin = RuntimeOrigin.LOCAL_UI;
        if (originKind == RuntimeOrigin.Kind.REMOTE) {
            origin = RuntimeOrigin.remote(new RuntimeOrigin.RemoteProof(string(in), string(in), string(in),
                    string(in), string(in), in.readLong(), string(in), string(in), string(in)));
        }
        return new RuntimeLease.Identity(target, origin, string(in), string(in), string(in), string(in), in.readLong());
    }

    private static void keys(DataOutputStream out, Set<String> keys) throws IOException {
        out.writeInt(keys.size());
        for (String key : new TreeSet<>(keys)) string(out, key);
    }
    private static Set<String> keys(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > 64) throw new IllegalArgumentException("Invalid runtime component count");
        Set<String> keys = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            if (!keys.add(RuntimeOrigin.key(string(in))))
                throw new IllegalArgumentException("Duplicate runtime component");
        }
        return keys;
    }
    private static void string(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 512) throw new IllegalArgumentException("Oversized runtime owner field");
        out.writeShort(bytes.length); out.write(bytes);
    }
    private static String string(DataInputStream in) throws IOException {
        int size = in.readUnsignedShort();
        if (size > 512) throw new IllegalArgumentException("Oversized runtime owner field");
        byte[] bytes = new byte[size]; in.readFully(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) { throw new IOException("Invalid runtime owner field", invalid); }
    }
}
