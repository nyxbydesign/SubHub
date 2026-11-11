package com.subhub.app.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Atomic, revocable start witnesses, not a PIN verifier. Trusted adapters begin a publication
 * BEFORE starting its observation/storage work, and complete that same token only after an
 * acknowledged result. Beginning invalidates previous authority immediately; a completion must
 * never start a new publication to revive a stale result. Storage transactions finish before
 * either call. External revisions are evidence, not ordering clocks for asynchronous publishers.
 */
public final class RuntimeAdmissionAuthority {
    final RuntimeMutex mutex;
    private final Map<RuntimeTarget, Availability> availability = new HashMap<>();
    private final Map<RuntimeTarget, Availability> lastAcknowledgedAvailability = new HashMap<>();
    private final Map<RuntimeTarget, AvailabilityPublication> availabilityPublications = new HashMap<>();
    private final Map<AuthorizationKey, Authorization> authorizations = new HashMap<>();
    private final Map<AuthorizationKey, AuthorizationPublication> authorizationPublications = new HashMap<>();

    public RuntimeAdmissionAuthority(RuntimeMutex mutex) { this.mutex = Objects.requireNonNull(mutex); }

    /** Also serves as immediate invalidation when no completion is supplied. */
    public AvailabilityPublication beginAvailability(RuntimeTarget target) {
        Objects.requireNonNull(target);
        return mutex.runtime(() -> {
            AvailabilityPublication next = new AvailabilityPublication(this, target,
                    lastAcknowledgedAvailability.get(target));
            availabilityPublications.put(target, next);
            Availability revoked = availability.remove(target);
            if (revoked != null) revoked.revoked = true;
            return next;
        });
    }

    public boolean completeAvailability(AvailabilityPublication publication, String generation,
            long configurationRevision, boolean enabled) {
        Availability next = new Availability(generation, configurationRevision, enabled);
        return mutex.runtime(() -> {
            if (publication == null || publication.source != this || publication.completed
                    || availabilityPublications.get(publication.target) != publication) return false;
            // Consume even a refused result. Recovery needs a fresh observation, not a reused token.
            publication.completed = true;
            Availability previous = publication.previous;
            if (previous != null && previous.generation.equals(generation)
                    && configurationRevision < previous.configurationRevision) return false;
            availability.put(publication.target, next);
            lastAcknowledgedAvailability.put(publication.target, next);
            return true;
        });
    }

    /** Target + principal scopes DEVICE, each internal profile, and incoming peer authority separately. */
    public AuthorizationPublication beginAuthorization(RuntimeTarget target, RuntimeOrigin origin) {
        AuthorizationKey key = new AuthorizationKey(target, origin);
        return mutex.runtime(() -> {
            AuthorizationPublication next = new AuthorizationPublication(this, key, availability.get(target));
            authorizationPublications.put(key, next);
            Authorization revoked = authorizations.remove(key);
            if (revoked != null) revoked.revoked = true;
            return next;
        });
    }

    public boolean completeAuthorization(AuthorizationPublication publication, long revision, boolean allowed) {
        return mutex.runtime(() -> {
            if (publication == null || publication.source != this || publication.completed
                    || authorizationPublications.get(publication.key) != publication) return false;
            publication.completed = true;
            Availability observed = publication.availability;
            if (observed == null || !observed.enabled || availability.get(publication.key.target) != observed)
                return false;
            authorizations.put(publication.key, new Authorization(revision, allowed, observed));
            return true;
        });
    }

    public Witness capture(RuntimeTarget target, RuntimeOrigin origin) {
        AuthorizationKey key = new AuthorizationKey(target, origin);
        return mutex.runtime(() -> new Witness(this, target, origin, availability.get(target), authorizations.get(key)));
    }
    boolean current(Witness witness) {
        mutex.requireHeld();
        return witness != null && witness.source == this && witness.authorization != null
                && witness.authorization == authorizations.get(new AuthorizationKey(witness.target, witness.origin))
                && witness.authorization.allowed && witness.availability != null && witness.availability.enabled
                && witness.authorization.availability == witness.availability
                && witness.availability == availability.get(witness.target);
    }

    /** Runtime-issued, process-local, one-use publication token. It cannot be reconstructed from revisions. */
    public static final class AvailabilityPublication {
        private final RuntimeAdmissionAuthority source;
        private final RuntimeTarget target;
        private final Availability previous;
        private boolean completed;
        private AvailabilityPublication(RuntimeAdmissionAuthority source, RuntimeTarget target, Availability previous) {
            this.source = source; this.target = target; this.previous = previous;
        }
    }
    /** Bound to both the principal and the exact acknowledged profile/configuration observation. */
    public static final class AuthorizationPublication {
        private final RuntimeAdmissionAuthority source;
        private final AuthorizationKey key;
        private final Availability availability;
        private boolean completed;
        private AuthorizationPublication(RuntimeAdmissionAuthority source, AuthorizationKey key, Availability availability) {
            this.source = source; this.key = key; this.availability = availability;
        }
    }
    public static final class Witness {
        private final RuntimeAdmissionAuthority source;
        private final Availability availability;
        private final Authorization authorization;
        public final RuntimeTarget target;
        public final RuntimeOrigin origin;
        private Witness(RuntimeAdmissionAuthority source, RuntimeTarget target, RuntimeOrigin origin, Availability availability,
                Authorization authorization) {
            this.source = source; this.target = target; this.origin = origin; this.availability = availability; this.authorization = authorization;
        }
        /**
         * Monotonic process-local permission-launch fence. No map/RuntimeMutex/IO access.
         * Only a coordinator-issued RESERVED obligation may consume this observation. An
         * invalidated witness never becomes current again, even if later revisions are equal.
         */
        boolean permitsLaunch() {
            return availability != null && authorization != null && availability.enabled
                    && authorization.allowed && authorization.availability == availability
                    && !availability.revoked && !authorization.revoked;
        }
        public String profileGeneration() { return availability == null ? "" : availability.generation; }
        public long configurationRevision() { return availability == null ? -1 : availability.configurationRevision; }
        public long authorizationRevision() {
            if (authorization == null) throw new IllegalStateException("No start authorization");
            return authorization.revision;
        }
    }
    private static final class AuthorizationKey {
        final RuntimeTarget target;
        final RuntimeOrigin.Kind kind;
        final String peer, relationshipGeneration;
        AuthorizationKey(RuntimeTarget target, RuntimeOrigin origin) {
            this.target = Objects.requireNonNull(target); kind = Objects.requireNonNull(origin).kind;
            if (target.kind == RuntimeTarget.Kind.INTERNAL && kind == RuntimeOrigin.Kind.REMOTE)
                throw new IllegalArgumentException("Remote control cannot authorize an internal Sub");
            peer = origin.remote == null ? "" : origin.remote.peer;
            relationshipGeneration = origin.remote == null ? "" : origin.remote.relationshipGeneration;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof AuthorizationKey)) return false;
            AuthorizationKey value = (AuthorizationKey) other;
            return target.equals(value.target) && kind == value.kind && peer.equals(value.peer)
                    && relationshipGeneration.equals(value.relationshipGeneration);
        }
        @Override public int hashCode() { return Objects.hash(target, kind, peer, relationshipGeneration); }
    }
    private static final class Authorization {
        volatile boolean revoked;
        final long revision;
        final boolean allowed;
        final Availability availability;
        Authorization(long revision, boolean allowed, Availability availability) {
            this.revision = revision; this.allowed = allowed; this.availability = availability;
        }
    }
    private static final class Availability {
        volatile boolean revoked;
        final String generation;
        final long configurationRevision;
        final boolean enabled;
        Availability(String generation, long configurationRevision, boolean enabled) {
            this.generation = RuntimeOrigin.key(generation);
            if (configurationRevision < 0) throw new IllegalArgumentException("Invalid configuration revision");
            this.configurationRevision = configurationRevision; this.enabled = enabled;
        }
    }
}
