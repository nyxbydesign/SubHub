package com.subhub.app.runtime;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/**
 * One-use physical journal provisioning, subordinate to the real legacy-quiescence barrier.
 * The PENDING receipt is written only in the same registry commit as the first internal profile's
 * READY publication. Opening a profile, absent files, a legacy visibility flag, or this receipt
 * alone is NOT permission to manufacture IDLE. No state is reset or repaired here.
 */
public final class RuntimeJournalMigration {
    public static final String REGISTRY = "subhub_internal_registry";
    public static final String PROFILE_STATE = "provisioning";
    public static final String RECEIPT = "physical_owner_creation_v1";
    public static final String ORIGINAL_GENERATION = "physical_owner_original_generation_v1";
    private enum Phase { PENDING, STARTED, READY }
    interface Evidence {
        String profileState();
        String receipt();
        String originalGeneration();
        /** Synchronous acknowledged commit. A false/throw outcome must poison the host until recovery. */
        boolean compareAndSetReceipt(String expectedProfileState, String expectedOriginalGeneration,
                String expectedReceipt, String replacement);
    }
    interface Journal<H extends AutoCloseable> {
        H openExisting() throws Exception;
        H provisionNew() throws Exception;
        /** Validate schema/identity and a readable owner record, without changing it. */
        void validateExisting(H handle) throws Exception;
        /** Must prove the newly created journal's exact IDLE revision zero, not coerce it. */
        void validateFirstCreation(H handle) throws Exception;
    }
    /** Concrete factory holds its own exact sealed installation proof; never a caller boolean. */
    @FunctionalInterface interface CreationAdmission { void beforeFirstWrite(); }
    private final RuntimeMutex mutex;
    private final RuntimeInstallationBarrier.Installation installation;
    RuntimeJournalMigration(RuntimeMutex mutex, RuntimeInstallationBarrier.Installation installation) {
        this.mutex = Objects.requireNonNull(mutex); this.installation = Objects.requireNonNull(installation);
    }
    /** Corrupt or established metadata is not a fresh profile, even when its primary key vanished. */
    public static void requirePristineRegistries(java.util.Map<String, ?> profile, java.util.Map<String, ?> ownerData) {
        if (!Objects.requireNonNull(profile).isEmpty() || !Objects.requireNonNull(ownerData).isEmpty())
            throw new IllegalStateException("Established internal metadata needs recovery, not first creation");
    }

    /** Append only during authorized FIRST create; original generation remains immutable evidence. */
    public static String firstCreationReceipt(String originalGeneration) {
        UUID generation = UUID.fromString(Objects.requireNonNull(originalGeneration));
        if (!generation.toString().equals(originalGeneration)) throw new IllegalArgumentException("Invalid original generation");
        return encode(Phase.PENDING, generation);
    }
    static boolean matchesEstablishedReceipt(String encoded, String originalGeneration) {
        try {
            Receipt receipt = decode(encoded);
            return receipt.phase == Phase.READY && receipt.originalGeneration.toString().equals(originalGeneration);
        } catch (IOException invalid) { return false; }
    }

    <H extends AutoCloseable> H open(Evidence evidence, Journal<H> journal,
            CreationAdmission admission) throws Exception {
        Objects.requireNonNull(evidence); Objects.requireNonNull(journal); Objects.requireNonNull(admission);
        mutex.requireHeld();
        // This is the actual physical installer's one-shot proof, not a per-helper attempted flag.
        // A second helper cannot reenter or retry after an uncertain READY memory update. A failed
        // begin has performed no work and must not poison a different already-admitted helper.
        installation.beginProvisioning();
        try {
            if (!"ready".equals(evidence.profileState())) throw new IOException("Internal profile creation is not acknowledged");
            String original = evidence.receipt(); Receipt receipt = decode(original);
            if (!receipt.originalGeneration.toString().equals(evidence.originalGeneration()))
                throw new IOException("First-create receipt does not match its original-generation witness");
            if (receipt.phase == Phase.STARTED) throw new IOException("Physical journal provisioning needs recovery");
            if (receipt.phase == Phase.READY) {
                H existing = Objects.requireNonNull(journal.openExisting());
                try { journal.validateExisting(existing); return existing; }
                catch (Throwable failure) { close(existing, failure); throw failure; }
            }
            admission.beforeFirstWrite();
            String started = encode(Phase.STARTED, receipt.originalGeneration);
            if (!evidence.compareAndSetReceipt("ready", receipt.originalGeneration.toString(), original, started))
                throw new IOException("Physical journal creation claim was not acknowledged");
            H created = Objects.requireNonNull(journal.provisionNew());
            try {
                journal.validateFirstCreation(created);
                if (!evidence.compareAndSetReceipt("ready", receipt.originalGeneration.toString(), started, encode(Phase.READY, receipt.originalGeneration)))
                    throw new IOException("Physical journal creation READY was not acknowledged");
                return created;
            } catch (Throwable failure) { close(created, failure); throw failure; }
        } catch (Exception | Error failed) {
            // SharedPreferences may already expose READY in memory after commit=false. The real
            // installation proof is permanently failed BEFORE any caller can try another helper.
            installation.failed(); throw failed;
        }
    }
    private static void close(AutoCloseable handle, Throwable failure) {
        try { handle.close(); } catch (Throwable closeFailure) { failure.addSuppressed(closeFailure); }
    }
    private static String encode(Phase phase, UUID generation) { return "v1:" + phase + ":" + generation; }
    private static Receipt decode(String encoded) throws IOException {
        if (encoded == null || encoded.length() > 64) throw new IOException("Missing or invalid first-create receipt");
        String[] fields = encoded.split(":", -1);
        if (fields.length != 3 || !"v1".equals(fields[0])) throw new IOException("Unproven physical journal lineage");
        try {
            Phase phase = Phase.valueOf(fields[1]); UUID generation = UUID.fromString(fields[2]);
            if (!generation.toString().equals(fields[2])) throw new IllegalArgumentException("Noncanonical generation");
            return new Receipt(phase, generation);
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid physical journal lineage", invalid); }
    }
    private static final class Receipt {
        final Phase phase; final UUID originalGeneration;
        Receipt(Phase phase, UUID generation) { this.phase = phase; originalGeneration = generation; }
    }
}
