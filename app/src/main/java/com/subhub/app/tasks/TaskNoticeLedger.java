package com.subhub.app.tasks;

import android.content.SharedPreferences;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Bounded exact OS-notice receipts on the broker's existing owned preferences handle.
 * The broker wraps mutation in its atomic transaction and returns only after that outer ACK.
 * No OS operation, provider, task assessment, runtime capability or automatic trimming lives here.
 */
public final class TaskNoticeLedger {
    public static final int MAX_RECEIPTS = 128;
    public static final int NOTIFICATION_ID = 2161;
    public enum Audience { SUB_TASKS, DOM_REVIEW }
    public static final class CapacityException extends IllegalStateException {
        public CapacityException() { super("Internal notice receipts need acknowledged cancellation before another admission"); }
    }
    public static final class Receipt {
        public final String generation, effectKey;
        public final Audience audience;
        public final long admittedAtMillis;
        public Receipt(String generation, Audience audience, String effectKey, long admittedAtMillis) {
            requireGeneration(generation); requireEffect(effectKey);
            if (admittedAtMillis <= 0) throw new IllegalArgumentException("Invalid notice admission clock");
            this.generation = generation; this.audience = Objects.requireNonNull(audience);
            this.effectKey = effectKey; this.admittedAtMillis = admittedAtMillis;
        }
        public String tag() { return notificationTag(generation, audience == Audience.DOM_REVIEW, effectKey); }
        public int id() { return NOTIFICATION_ID; }
        private String key() { return "notice:" + generation + ":" + audience.name() + ":" + effectIdentity(effectKey); }
        private boolean sameNotice(Receipt other) {
            return generation.equals(other.generation) && audience == other.audience && effectKey.equals(other.effectKey);
        }
        @Override public boolean equals(Object other) {
            return other instanceof Receipt && sameNotice((Receipt) other)
                    && admittedAtMillis == ((Receipt) other).admittedAtMillis;
        }
        @Override public int hashCode() { return Objects.hash(generation, audience, effectKey, admittedAtMillis); }
    }
    private final SharedPreferences preferences;
    private final Runnable requireDataAdmission;
    private boolean unavailable;
    public TaskNoticeLedger(SharedPreferences ownedNamespace, Runnable requireDataAdmission) {
        preferences = Objects.requireNonNull(ownedNamespace); this.requireDataAdmission = Objects.requireNonNull(requireDataAdmission);
    }
    public List<Receipt> list() {
        require();
        try {
            Map<String, ?> saved = preferences.getAll();
            if (saved.size() > MAX_RECEIPTS) throw new IllegalStateException("Oversized internal notice receipts");
            List<Receipt> result = new ArrayList<>();
            for (Map.Entry<String, ?> entry : saved.entrySet()) {
                if (!(entry.getValue() instanceof String)) throw new IllegalStateException("Invalid internal notice receipt");
                Receipt receipt = decode((String) entry.getValue());
                if (!entry.getKey().equals(receipt.key())) throw new IllegalStateException("Different internal notice identity");
                result.add(receipt);
            }
            result.sort(Comparator.comparingLong((Receipt value) -> value.admittedAtMillis).thenComparing(Receipt::key));
            return Collections.unmodifiableList(result);
        } catch (RuntimeException invalid) { unavailable = true; throw invalid; }
    }
    /** Idempotent by logical notice. Replay retains the first acknowledged clock, never overwrites it. */
    public Receipt admit(Receipt requested) {
        Objects.requireNonNull(requested);
        List<Receipt> receipts = list();
        for (Receipt existing : receipts) if (existing.key().equals(requested.key())) {
            if (!existing.sameNotice(requested)) throw new IllegalStateException("Conflicting internal notice identity");
            return existing;
        }
        if (receipts.size() == MAX_RECEIPTS) throw new CapacityException();
        acknowledge(preferences.edit().putString(requested.key(), encode(requested)));
        return requested;
    }
    /** Cleanup-only broker lane calls this after cancelling exactly receipt.tag()/id(). Missing is idempotent. */
    public void removeAfterCancellation(Receipt expected) {
        Objects.requireNonNull(expected);
        for (Receipt saved : list()) if (saved.key().equals(expected.key())) {
            if (!saved.equals(expected)) throw new IllegalStateException("Stale internal notice cancellation receipt");
            acknowledge(preferences.edit().remove(expected.key())); return;
        }
    }
    private void require() {
        requireDataAdmission.run();
        if (unavailable) throw new IllegalStateException("Internal notice receipt persistence is unavailable");
    }
    private void acknowledge(SharedPreferences.Editor edit) {
        require();
        try { if (!edit.commit()) throw new IllegalStateException("Internal notice receipt was not acknowledged"); }
        catch (RuntimeException uncertain) { unavailable = true; throw uncertain; }
    }
    public static String notificationTag(String generation, boolean domReview, String effectKey) {
        requireGeneration(generation); requireEffect(effectKey);
        return (domReview ? "tasks:internal:" : "subhub.optional:SUB:TASKS:internal:")
                + generation + ":" + effectIdentity(effectKey);
    }
    private static String effectIdentity(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static void requireGeneration(String value) {
        if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException("Invalid notice generation");
    }
    private static void requireEffect(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,512}")) throw new IllegalArgumentException("Invalid notice effect key");
    }
    private static String encode(Receipt receipt) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(1); out.writeUTF(receipt.generation); out.writeUTF(receipt.audience.name());
            out.writeUTF(receipt.effectKey); out.writeLong(receipt.admittedAtMillis); out.flush();
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException impossible) { throw new AssertionError(impossible); }
    }
    private static Receipt decode(String value) {
        if (value.length() > 1024) throw new IllegalStateException("Oversized notice receipt");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(value)));
            if (in.readUnsignedByte() != 1) throw new IOException("Unknown notice receipt");
            Receipt receipt = new Receipt(in.readUTF(), Audience.valueOf(in.readUTF()), in.readUTF(), in.readLong());
            if (in.available() != 0 || !encode(receipt).equals(value)) throw new IOException("Noncanonical notice receipt");
            return receipt;
        } catch (IOException | IllegalArgumentException invalid) { throw new IllegalStateException("Invalid internal notice receipt", invalid); }
    }
}
