package com.subhub.app.remote;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Bounded application frames carried inside an authenticated encrypted peer connection. */
public final class RemoteProtocol {
    public static final int VERSION = 1;
    public static final String ALPN = "subhub/pair-sync/1";
    public static final int MAX_FRAME_BYTES = 1024 * 1024;
    public static final int MAX_RECORD_BYTES = 256 * 1024;
    public static final int MAX_BATCH_RECORDS = 128;
    private static final int MAGIC = 0x53554831;

    public enum Role { DOM, SUB }
    public enum Stream {
        STATS(Role.SUB), DAILY(Role.SUB), APP_USAGE(Role.SUB), APP_CATALOG(Role.SUB), BASELINE(Role.SUB),
        RUNTIME(Role.SUB), CONTRACTS(Role.DOM), CONFIGURATION(Role.DOM), COMMAND_RESULT(Role.SUB);
        public final Role writer;
        Stream(Role writer) { this.writer = writer; }
    }

    private RemoteProtocol() { }

    public static String peerId(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid endpoint identity");
        }
        return value;
    }

    public static String identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Invalid protocol identifier");
        }
        return value;
    }

    public static String deviceName(String value) {
        if(value==null || value.trim().isEmpty() || value.length()>80) throw new IllegalArgumentException("Invalid device name");
        for(int i=0;i<value.length();i++) {
            char character=value.charAt(i);
            if(Character.isISOControl(character) || character>=0x202a && character<=0x202e
                    || character>=0x2066 && character<=0x2069) throw new IllegalArgumentException("Invalid device name");
        }
        return value.trim();
    }

    public static final class Change {
        public final long sequence;
        public final String key;
        public final String body;
        public final boolean deleted;
        public final long observedAt;

        public Change(long sequence, String key, String body, boolean deleted, long observedAt) {
            if (sequence <= 0 || observedAt < 0 || key == null || key.isEmpty()
                    || key.length() > 512 || body == null
                    || body.getBytes(StandardCharsets.UTF_8).length > MAX_RECORD_BYTES) {
                throw new IllegalArgumentException("Invalid change record");
            }
            try { new JSONObject(body); }
            catch (JSONException invalid) { throw new IllegalArgumentException("Invalid record JSON"); }
            if (deleted && !"{}".equals(body)) throw new IllegalArgumentException("Invalid deletion");
            this.sequence = sequence;
            this.key = key;
            this.body = body;
            this.deleted = deleted;
            this.observedAt = observedAt;
        }

        JSONObject json() throws JSONException {
            return new JSONObject().put("seq", sequence).put("key", key)
                    .put("body", new JSONObject(body)).put("deleted", deleted)
                    .put("observedAt", observedAt);
        }
    }

    public static final class Batch {
        public final String generation;
        public final Stream stream;
        public final long after, through;
        public final List<Change> changes;

        public Batch(String generation, Stream stream, long after, long through,
                List<Change> changes) {
            this.generation = identifier(generation);
            if (stream == null || after < 0 || through < after || changes == null
                    || changes.size() > MAX_BATCH_RECORDS) {
                throw new IllegalArgumentException("Invalid batch range");
            }
            long previous = after;
            for (Change change : changes) {
                if (change.sequence <= previous || change.sequence > through) {
                    throw new IllegalArgumentException("Unordered batch");
                }
                previous = change.sequence;
            }
            if (through > after && (changes.isEmpty() || previous != through)) {
                throw new IllegalArgumentException("Incomplete batch boundary");
            }
            this.stream = stream;
            this.after = after;
            this.through = through;
            this.changes = Collections.unmodifiableList(new ArrayList<>(changes));
        }

        public JSONObject json() throws JSONException {
            JSONArray records = new JSONArray();
            for (Change change : changes) records.put(change.json());
            return new JSONObject().put("version", VERSION).put("type", "batch")
                    .put("generation", generation).put("stream", stream.name())
                    .put("after", after).put("through", through).put("changes", records);
        }

        public String digest() {
            try { return sha256(canonical(json()).getBytes(StandardCharsets.UTF_8)); }
            catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        }

        public static Batch parse(JSONObject json) throws JSONException {
            try {
                requireVersion(json);
                if (!"batch".equals(json.getString("type"))) throw new JSONException("Expected batch");
                JSONArray rows = json.getJSONArray("changes");
                if (rows.length() > MAX_BATCH_RECORDS) throw new JSONException("Too many records");
                List<Change> changes = new ArrayList<>();
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.getJSONObject(i);
                    changes.add(new Change(integer(row, "seq"), row.getString("key"),
                            row.getJSONObject("body").toString(), row.getBoolean("deleted"),
                            integer(row, "observedAt")));
                }
                return new Batch(json.getString("generation"),
                        Stream.valueOf(json.getString("stream")), integer(json, "after"),
                        integer(json, "through"), changes);
            } catch (IllegalArgumentException invalid) { throw new JSONException("Invalid batch"); }
        }
    }

    public static final class Ack {
        public final String generation, digest;
        public final Stream stream;
        public final long through;
        public Ack(Batch batch) {
            generation = batch.generation;
            digest = batch.digest();
            stream = batch.stream;
            through = batch.through;
        }
        public JSONObject json() throws JSONException {
            return new JSONObject().put("version", VERSION).put("type", "ack")
                    .put("generation", generation).put("stream", stream.name())
                    .put("through", through).put("digest", digest);
        }
        private Ack(String generation, Stream stream, long through, String digest) {
            this.generation = identifier(generation);
            this.stream = stream;
            this.through = through;
            if (through < 0 || digest == null || !digest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid acknowledgement");
            }
            this.digest = digest;
        }
        public static Ack parse(JSONObject json) throws JSONException {
            requireVersion(json);
            if (!"ack".equals(json.getString("type"))) throw new JSONException("Expected acknowledgement");
            try {
                return new Ack(json.getString("generation"),
                        Stream.valueOf(json.getString("stream")), integer(json, "through"),
                        json.getString("digest"));
            } catch (IllegalArgumentException invalid) { throw new JSONException("Invalid acknowledgement"); }
        }
    }

    public static void write(OutputStream output, JSONObject message) throws IOException {
        byte[] plain = canonical(message).getBytes(StandardCharsets.UTF_8);
        if (plain.length > MAX_FRAME_BYTES) throw new IOException("Frame too large");
        byte[] payload = plain;
        boolean compressed = false;
        if (plain.length >= 4096) {
            ByteArrayOutputStream zipped = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(zipped)) { gzip.write(plain); }
            if (zipped.size() < plain.length) { payload = zipped.toByteArray(); compressed = true; }
        }
        DataOutputStream frame = new DataOutputStream(output);
        frame.writeInt(MAGIC);
        frame.writeByte(compressed ? 1 : 0);
        frame.writeInt(payload.length);
        frame.write(payload);
        frame.flush();
    }

    public static JSONObject read(InputStream input) throws IOException {
        DataInputStream frame = new DataInputStream(input);
        if (frame.readInt() != MAGIC) throw new IOException("Unsupported frame");
        int flags = frame.readUnsignedByte();
        int length = frame.readInt();
        if (flags > 1 || length <= 0 || length > MAX_FRAME_BYTES) throw new IOException("Invalid frame");
        byte[] payload = new byte[length];
        frame.readFully(payload);
        if (flags == 1) {
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(payload))) {
                ByteArrayOutputStream expanded = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = gzip.read(buffer)) != -1) {
                    if (expanded.size() > MAX_FRAME_BYTES - count) throw new IOException("Expanded frame too large");
                    expanded.write(buffer, 0, count);
                }
                payload = expanded.toByteArray();
            }
        }
        try {
            String encoded = new String(payload, StandardCharsets.UTF_8);
            if (!java.util.Arrays.equals(payload, encoded.getBytes(StandardCharsets.UTF_8))) {
                throw new IOException("Invalid UTF-8");
            }
            JSONObject result = new JSONObject(encoded);
            requireVersion(result);
            return result;
        } catch (JSONException invalid) { throw new IOException("Invalid protocol message"); }
    }

    static void requireVersion(JSONObject json) throws JSONException {
        if (integer(json, "version") != VERSION) throw new JSONException("Unsupported protocol version");
    }

    static long integer(JSONObject object, String key) throws JSONException {
        Object value = object.get(key);
        if (!(value instanceof Integer || value instanceof Long)) throw new JSONException("Expected integer");
        return ((Number) value).longValue();
    }

    public static String sha256(byte[] value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder result = new StringBuilder(64);
            for (byte item : hash) result.append(String.format(Locale.ROOT, "%02x", item & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static String canonical(Object value) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            java.util.SortedSet<String> keys = new java.util.TreeSet<>();
            object.keys().forEachRemaining(keys::add);
            StringBuilder result = new StringBuilder("{");
            for (String key : keys) {
                if (result.length() > 1) result.append(',');
                result.append(JSONObject.quote(key)).append(':').append(canonical(object.opt(key)));
            }
            return result.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder result = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) result.append(',');
                result.append(canonical(array.opt(i)));
            }
            return result.append(']').toString();
        }
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof String) return JSONObject.quote((String) value);
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        throw new IllegalArgumentException("Unsupported JSON value");
    }
}
