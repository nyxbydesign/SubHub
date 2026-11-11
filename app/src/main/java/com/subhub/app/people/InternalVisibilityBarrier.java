package com.subhub.app.people;

import android.content.Context;
import com.subhub.app.internal.InternalSubRecord;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Exact-owner stop/drain proof, separate from profile storage or an unbound data broker. */
public final class InternalVisibilityBarrier {
    private InternalVisibilityBarrier() { }

    public static final class Request {
        public final InternalSubRecord expected;
        public final boolean visible;
        public final long controllerRevision, transitionAtMillis;
        public Request(InternalSubRecord expected, boolean visible, long controllerRevision, long transitionAtMillis) {
            this.expected = Objects.requireNonNull(expected);
            if (expected.visible == visible || transitionAtMillis <= 0)
                throw new IllegalArgumentException("Explicit visibility transition required");
            this.visible = visible; this.controllerRevision = controllerRevision; this.transitionAtMillis = transitionAtMillis;
        }
    }
    public interface Permit {
        /** Called on the owner worker immediately before CAS. Must not block or acquire a storage lock. */
        void requireCurrent();
        /**
         * Called once after CAS acknowledgement or failure, including uncertain interrupted waits.
         * The provider must retain availability revocation on an uncertain outcome, never infer idle
         * from a missing owner. A successful return acknowledges the new publication boundary.
         */
        CompletableFuture<Void> complete(Completion completion);
    }
    public enum Outcome { ACKNOWLEDGED, NOT_ADMITTED, NOT_COMMITTED, UNCERTAIN }
    public static final class Completion {
        public final Outcome outcome;
        public final InternalSubRecord acknowledged;
        public final Throwable failure;
        private Completion(Outcome outcome, InternalSubRecord acknowledged, Throwable failure) {
            this.outcome = outcome; this.acknowledged = acknowledged; this.failure = failure;
        }
    }
    /** Unforgeable wrapper issued only by this provider path, consumed at the broker CAS boundary. */
    public static final class Proof {
        public final Request request;
        private final Context physical;
        private final Permit permit;
        private boolean consumed, completed;
        private Proof(Context physical, Request request, Permit permit) {
            this.physical = physical; this.request = request; this.permit = Objects.requireNonNull(permit);
        }
        public Request request() { return request; }
        public void consume(Context context, InternalSubRecord expected, long controllerRevision,
                boolean visible, long transitionAtMillis) {
            if (context != physical || !request.expected.equals(expected) || request.controllerRevision != controllerRevision
                    || request.visible != visible || request.transitionAtMillis != transitionAtMillis)
                throw new SecurityException("Visibility proof does not match the exact request");
            synchronized (this) {
                if (consumed || completed) throw new SecurityException("Visibility proof already consumed or completed");
            }
            // No external provider callback or subsequent persistence runs under this small token lock.
            permit.requireCurrent();
            synchronized (this) {
                if (consumed || completed) throw new SecurityException("Visibility proof already consumed or completed");
                consumed = true;
            }
        }
        public CompletableFuture<Void> complete(InternalSubRecord acknowledged, Throwable failure) {
            final boolean admitted;
            synchronized (this) {
                if (completed) throw new IllegalStateException("Visibility proof already completed");
                completed = true; admitted = consumed;
            }
            Outcome outcome;
            if (acknowledged != null && failure == null) {
                if (!admitted || acknowledged.visible != request.visible
                        || acknowledged.generation.equals(request.expected.generation)
                        || acknowledged.revision != request.expected.revision + 1)
                    throw new IllegalStateException("Visibility acknowledgement does not match the admitted request");
                outcome = Outcome.ACKNOWLEDGED;
            } else if (!admitted) {
                // Completion fences a still-queued consume, so this exact proof can never mutate later.
                outcome = Outcome.NOT_ADMITTED;
            } else if (failure instanceof com.subhub.app.internal.InternalSubStore.PersistenceException
                    && ((com.subhub.app.internal.InternalSubStore.PersistenceException) failure).outcome
                            != com.subhub.app.internal.InternalSubStore.Outcome.UNCERTAIN) {
                outcome = Outcome.NOT_COMMITTED;
            } else {
                // Includes an interrupted await after consume. Keep runtime availability revoked.
                outcome = Outcome.UNCERTAIN;
            }
            return Objects.requireNonNull(permit.complete(new Completion(outcome, acknowledged, failure)));
        }
    }
    @FunctionalInterface public interface Provider {
        /**
         * Revoke target availability before returning a permit. Prove exact owner journal idle, or
         * stop and durably drain that exact INTERNAL owner. Never stop DEVICE/remote or hold a
         * runtime/editor lock across the subsequent storage CAS. Refuse if proof is unavailable.
         */
        CompletableFuture<Permit> prepare(Context physical, Request request);
    }
    public static final class UnavailableException extends IllegalStateException {
        public UnavailableException() { super("Internal visibility awaits exact runtime owner reconciliation"); }
    }
    private static final Provider UNAVAILABLE = (physical, request) -> {
        CompletableFuture<Permit> refused = new CompletableFuture<>();
        refused.completeExceptionally(new UnavailableException()); return refused;
    };
    // Inactive checkpoint: no production provider is installed until the runtime gateway is qualified.
    private static volatile Provider provider = UNAVAILABLE;
    public static synchronized void install(Provider qualifiedRuntimeProvider) {
        if (provider != UNAVAILABLE) throw new IllegalStateException("Visibility provider already installed");
        provider = Objects.requireNonNull(qualifiedRuntimeProvider);
    }
    static CompletableFuture<Proof> prepare(Context physical, Request request) {
        Objects.requireNonNull(physical); Objects.requireNonNull(request);
        return Objects.requireNonNull(provider.prepare(physical, request)).thenApply(permit -> new Proof(physical, request, permit));
    }
}
