package com.subhub.app.internal;

import java.io.IOException;
import java.util.Objects;

/**
 * Durable first-create intent supplied by a trusted host, never inferred from missing owned files.
 * Evidence must live outside runtime-data, survive hide/re-add, and be independently durable.
 * No failure path resets evidence, deletes owned bytes, retries creation or resumes a runtime.
 */
public final class OwnerDataProvisioning {
    public enum Decision { OPEN_EXISTING, FIRST_INTERNAL_CREATE, PROFILE_ONLY_V1_UPGRADE }
    public interface Evidence {
        /** Exactly absent (empty), started, or ready. Reject all other stored values. */
        String read() throws Exception;
        boolean commit(String state) throws Exception;
    }
    public interface Factory<H extends AutoCloseable> {
        H openExisting() throws Exception;
        H provisionNew() throws Exception;
        /** Validate the sole opened handle before acknowledging READY. */
        void validate(H handle) throws Exception;
    }
    private boolean attempted;
    public <H extends AutoCloseable> H open(Decision decision, Evidence evidence, Factory<H> factory) throws Exception {
        Objects.requireNonNull(decision); Objects.requireNonNull(evidence); Objects.requireNonNull(factory);
        synchronized (this) {
            if (attempted) throw new IllegalStateException("Provision/open attempt already consumed");
            attempted = true;
        }
        String state = Objects.requireNonNull(evidence.read());
        if (state.equals("ready")) return Objects.requireNonNull(factory.openExisting());
        if (!state.isEmpty()) throw new IOException("Owner-data provisioning needs recovery");
        if (decision == Decision.OPEN_EXISTING) throw new IOException("Owner-data provisioning evidence absent");
        // Explicit host decision is mandatory even when all owned bytes and evidence were lost.
        if (!evidence.commit("started")) throw new IOException("Owner-data creation intent not acknowledged");
        H handle = Objects.requireNonNull(factory.provisionNew());
        try {
            factory.validate(handle);
            if (!evidence.commit("ready")) throw new IOException("Owner-data READY not acknowledged");
            return handle;
        } catch (Throwable failure) {
            try { handle.close(); } catch (Throwable closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }
}
