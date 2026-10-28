package com.subhub.app.storage;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Lossless local payload encoding. This format never crosses the peer protocol. */
public final class PayloadCodec {
    public static final int MAX_DECODED_BYTES = 2 * 1024 * 1024;
    private static final int HEADER = 8;
    private static final byte[] MAGIC = {'S', 'H', 'Z', 1};
    // Version 1 is immutable: a dictionary change requires a new format version/reader.
    private static final byte[] DICTIONARY = ("schema id kind observedAt packageName amountCents "
            + "currency reason limitMinutes zone day configuration APP_OPENED TRIBUTE_TRIGGERED "
            + "APP_LIMIT_REACHED SHARED_LIMIT_REACHED Europe/Berlin schema protectedSeconds "
            + "serviceSeconds sessions activityEvents censors limitMillis limitStops whispers "
            + "popups tamperEvents tributes assessedEUR assessedUSD paidEUR paidUSD "
            + "currentSeconds currentEvents currentCensors").getBytes(StandardCharsets.UTF_8);

    private PayloadCodec() { }

    /** Null means retain the original text; tiny, large and incompressible values stay plain. */
    public static byte[] compressIfUseful(String value) {
        if (value == null) throw new IllegalArgumentException("Missing payload");
        if (value.length() < 192 || value.length() > MAX_DECODED_BYTES) return null;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) return null;
            } else if (Character.isLowSurrogate(current)) return null;
        }
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        if (raw.length > MAX_DECODED_BYTES) return null;
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            deflater.setDictionary(DICTIONARY);
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(raw.length, 8192));
            output.write(MAGIC, 0, MAGIC.length);
            byte[] length = ByteBuffer.allocate(4).putInt(raw.length).array();
            output.write(length, 0, length.length);
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                if (count <= 0) throw new IllegalStateException("Payload compression stalled");
                output.write(buffer, 0, count);
                if (output.size() >= raw.length - Math.max(32, raw.length / 8)) return null;
            }
            return output.toByteArray();
        } finally { deflater.end(); }
    }

    /** Reject corrupt, unknown, oversized or trailing encodings rather than inventing empty data. */
    public static String decompress(byte[] encoded) {
        if (encoded == null || encoded.length <= HEADER || encoded.length > MAX_DECODED_BYTES
                || encoded[0] != MAGIC[0] || encoded[1] != MAGIC[1]
                || encoded[2] != MAGIC[2] || encoded[3] != MAGIC[3])
            throw new IllegalStateException("Unsupported stored payload");
        int expected = ByteBuffer.wrap(encoded, 4, 4).getInt();
        if (expected < 0 || expected > MAX_DECODED_BYTES)
            throw new IllegalStateException("Stored payload size is invalid");
        byte[] raw = new byte[expected];
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(encoded, HEADER, encoded.length - HEADER);
            int offset = 0;
            boolean dictionarySet = false;
            byte[] overflow = new byte[1];
            while (!inflater.finished()) {
                int count = offset == raw.length ? inflater.inflate(overflow)
                        : inflater.inflate(raw, offset, raw.length - offset);
                if (offset == raw.length && count != 0)
                    throw new IllegalStateException("Stored payload exceeds its declared size");
                offset += count;
                if (inflater.needsDictionary()) {
                    if (dictionarySet) throw new IllegalStateException("Invalid stored dictionary");
                    inflater.setDictionary(DICTIONARY);
                    dictionarySet = true;
                } else if (!inflater.finished() && count == 0) {
                    throw new IllegalStateException("Stored payload is incomplete");
                }
            }
            if (offset != expected || inflater.getRemaining() != 0 || !dictionarySet)
                throw new IllegalStateException("Stored payload length is invalid");
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
        } catch (DataFormatException | IllegalArgumentException | CharacterCodingException invalid) {
            throw new IllegalStateException("Stored payload failed integrity validation", invalid);
        } finally { inflater.end(); }
    }
}
