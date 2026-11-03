package com.subhub.app.relationships;

import java.security.SecureRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Noncredential idempotency keys retain the exact original snapshot epoch and revision. */
public final class RelationshipPreparationKey {
    private static final Pattern REVISION = Pattern.compile("fr1:([0-9a-f]{32}):(0|[1-9][0-9]{0,18})");
    private static final Pattern KEY = Pattern.compile("fr1\\.([0-9a-f]{32})\\.(0|[1-9][0-9]{0,18})\\.([0-9a-f]{32})");
    private RelationshipPreparationKey() { }
    public static String create(String originalSnapshotRevision) {
        Matcher revision = revision(originalSnapshotRevision);
        byte[] nonce = new byte[16]; new SecureRandom().nextBytes(nonce);
        StringBuilder hex = new StringBuilder(32);
        for (byte value : nonce) hex.append(Character.forDigit((value >>> 4) & 15,16)).append(Character.forDigit(value & 15,16));
        return "fr1." + revision.group(1) + "." + revision.group(2) + "." + hex;
    }
    /** Syntax validation grants no provenance or authority; retained legacy lookup comes first. */
    public static boolean matches(String key, String originalSnapshotRevision) {
        Matcher revision;
        try { revision = revision(originalSnapshotRevision); } catch (IllegalArgumentException invalid) { return false; }
        if (key == null) return false;
        Matcher parsed = KEY.matcher(key);
        return parsed.matches() && parsed.group(1).equals(revision.group(1)) && parsed.group(2).equals(revision.group(2));
    }
    private static Matcher revision(String value) {
        if (value == null) throw new IllegalArgumentException("Original snapshot revision required");
        Matcher parsed = REVISION.matcher(value);
        if (!parsed.matches()) throw new IllegalArgumentException("Invalid original snapshot revision");
        try { Long.parseLong(parsed.group(2)); } catch (NumberFormatException invalid) { throw new IllegalArgumentException("Revision overflow",invalid); }
        return parsed;
    }
}
