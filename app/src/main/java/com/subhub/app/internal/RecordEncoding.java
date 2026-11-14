package com.subhub.app.internal;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Byte primitives shared by the internal record formats. */
final class RecordEncoding {
    private RecordEncoding() {}

    static void writeText(DataOutputStream out, String value, int maximumBytes) throws IOException {
        ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
        if (bytes.remaining() > maximumBytes) throw new IllegalArgumentException("Oversized text");
        out.writeInt(bytes.remaining());
        byte[] encoded = new byte[bytes.remaining()];
        bytes.get(encoded);
        out.write(encoded);
    }

    static String readText(DataInputStream in, int length) throws IOException {
        if (length > in.available()) throw new IOException("Truncated text");
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    static final class BoundedBytes extends ByteArrayOutputStream {
        private final int limit;
        private final String overflowMessage;

        BoundedBytes(int limit, String overflowMessage) {
            this.limit = limit;
            this.overflowMessage = overflowMessage;
        }

        @Override public synchronized void write(int value) {
            if (count >= limit) throw new IllegalArgumentException(overflowMessage);
            super.write(value);
        }

        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            if (length > limit - count) throw new IllegalArgumentException(overflowMessage);
            super.write(bytes, offset, length);
        }
    }
}
