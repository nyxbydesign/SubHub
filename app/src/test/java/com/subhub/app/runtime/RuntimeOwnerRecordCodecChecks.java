package com.subhub.app.runtime;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/** Dependency-free codec contracts, also invoked by the canonical JUnit wrapper. */
public final class RuntimeOwnerRecordCodecChecks {
    private static final String UUID = "00000000-0000-4000-8000-000000000001";
    private static final String DIGEST = "a".repeat(64);
    private RuntimeOwnerRecordCodecChecks() { }

    public static void main(String[] args) {
        runAll(); System.out.println("Runtime owner codec: 8 contracts passed");
    }
    public static void runAll() {
        idleRoundTrips(); ownerPhaseMatrixRoundTrips(); componentOrderIsCanonical();
        malformedAndTruncatedRecordsRefuse(); oversizedInputRefuses(); schemaAndRevisionsRefuse();
        maximumComponentsRoundTrip(); decodedIdentityCannotBeAnExecutableLease();
    }
    private static void idleRoundTrips() {
        roundTrip(RuntimeOwnerStore.Record.idle(0));
        roundTrip(RuntimeOwnerStore.Record.idle(Long.MAX_VALUE));
    }
    private static void ownerPhaseMatrixRoundTrips() {
        for (RuntimeTarget target : new RuntimeTarget[]{RuntimeTarget.DEVICE, RuntimeTarget.internal("dummy_sub_preview")}) {
            for (RuntimeOwnerStore.Phase phase : RuntimeOwnerStore.Phase.values()) {
                if (phase == RuntimeOwnerStore.Phase.IDLE) continue;
                roundTrip(record(target, RuntimeOrigin.LOCAL_UI, phase, Set.of("capture", "overlay")));
                if (target.kind == RuntimeTarget.Kind.DEVICE) {
                    for (long revision : new long[]{Long.MIN_VALUE, -1, 0, Long.MAX_VALUE}) {
                        RuntimeOrigin origin = RuntimeOrigin.remote(new RuntimeOrigin.RemoteProof(DIGEST,
                                "relationship_1", UUID, DIGEST, DIGEST, revision, UUID, "", UUID));
                        roundTrip(record(target, origin, phase, Set.of("capture", "overlay")));
                    }
                }
            }
        }
    }
    private static RuntimeOwnerStore.Record record(RuntimeTarget target, RuntimeOrigin origin,
            RuntimeOwnerStore.Phase phase, Set<String> required) {
        RuntimeLease.Identity identity = new RuntimeLease.Identity(target, origin, "profile_generation_1",
                UUID, UUID, UUID, 3);
        Set<String> ready = phase == RuntimeOwnerStore.Phase.ACTIVE ? required
                : phase == RuntimeOwnerStore.Phase.RESERVED ? Set.of() : Set.of(required.iterator().next());
        return new RuntimeOwnerStore.Record(7, phase, identity, 5, 3, required, ready);
    }
    private static void componentOrderIsCanonical() {
        Set<String> first = new LinkedHashSet<>(Arrays.asList("overlay", "capture"));
        Set<String> second = new LinkedHashSet<>(Arrays.asList("capture", "overlay"));
        check(Arrays.equals(RuntimeOwnerRecordCodec.encode(record(RuntimeTarget.DEVICE, RuntimeOrigin.LOCAL_UI,
                        RuntimeOwnerStore.Phase.ACTIVE, first)),
                RuntimeOwnerRecordCodec.encode(record(RuntimeTarget.DEVICE, RuntimeOrigin.LOCAL_UI,
                        RuntimeOwnerStore.Phase.ACTIVE, second))), "Unstable set encoding");
    }
    private static void malformedAndTruncatedRecordsRefuse() {
        byte[] bytes = RuntimeOwnerRecordCodec.encode(record(RuntimeTarget.internal("dummy_sub_preview"),
                RuntimeOrigin.LOCAL_UI, RuntimeOwnerStore.Phase.ACTIVE, Set.of("capture")));
        for (int length = 0; length < bytes.length; length++) refuses(Arrays.copyOf(bytes, length));
        refuses(Arrays.copyOf(bytes, bytes.length + 1));
        byte[] invalidUtf = bytes.clone(); invalidUtf[18] = (byte) 0xff; refuses(invalidUtf);
        // Owner boolean follows the fixed fields and the phase string; non-1 true is noncanonical.
        byte[] noncanonical = bytes.clone(); noncanonical[34 + "ACTIVE".length()] = 2; refuses(noncanonical);
    }
    private static void oversizedInputRefuses() {
        refuses(null); refuses(new byte[RuntimeOwnerRecordCodec.MAX_BYTES + 1]);
        byte[] bytes = RuntimeOwnerRecordCodec.encode(RuntimeOwnerStore.Record.idle(0));
        bytes[16] = 0x7f; bytes[17] = (byte) 0xff; refuses(bytes);
    }
    private static void schemaAndRevisionsRefuse() {
        byte[] bytes = RuntimeOwnerRecordCodec.encode(RuntimeOwnerStore.Record.idle(0));
        byte[] badMagic = bytes.clone(); badMagic[0] ^= 1; refuses(badMagic);
        byte[] badVersion = bytes.clone(); badVersion[7] = 2; refuses(badVersion);
        byte[] badRevision = bytes.clone(); badRevision[8] = (byte) 0xff; refuses(badRevision);
        byte[] badPhase = bytes.clone(); badPhase[18] = 'X'; refuses(badPhase);
    }
    private static void maximumComponentsRoundTrip() {
        Set<String> components = new LinkedHashSet<>();
        for (int i = 0; i < 64; i++) components.add("x".repeat(125) + String.format(java.util.Locale.ROOT, "%03d", i));
        RuntimeOwnerStore.Record record = record(RuntimeTarget.DEVICE, RuntimeOrigin.LOCAL_UI,
                RuntimeOwnerStore.Phase.ACTIVE, components);
        check(RuntimeOwnerRecordCodec.encode(record).length < RuntimeOwnerRecordCodec.MAX_BYTES, "Legal components exceed bound");
        roundTrip(record);
    }
    private static void decodedIdentityCannotBeAnExecutableLease() {
        RuntimeOwnerStore.Record record = record(RuntimeTarget.DEVICE, RuntimeOrigin.LOCAL_UI,
                RuntimeOwnerStore.Phase.RESERVED, Set.of("capture"));
        RuntimeOwnerStore.Record decoded = RuntimeOwnerRecordCodec.decode(RuntimeOwnerRecordCodec.encode(record));
        check(decoded.owner != record.owner, "Decoded owner reused a process object");
        check(decoded.owner.getClass() == RuntimeLease.Identity.class, "Decoded executable authority");
    }
    private static void roundTrip(RuntimeOwnerStore.Record record) {
        byte[] encoded = RuntimeOwnerRecordCodec.encode(record);
        RuntimeOwnerStore.Record restored = RuntimeOwnerRecordCodec.decode(encoded);
        check(record.equals(restored), "Record evidence changed during round trip");
        check(Arrays.equals(encoded, RuntimeOwnerRecordCodec.encode(restored)), "Encoding changed during round trip");
    }
    private static void refuses(byte[] bytes) {
        try { RuntimeOwnerRecordCodec.decode(bytes); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Malformed owner record was accepted");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
