package com.subhub.app.relationships;

import com.subhub.app.people.PersonId;
import com.subhub.app.people.PersonSnapshot;
import com.subhub.app.people.ProfileData;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Presentation data supplied by the relationship coordinator; none of these values grant authority. */
public final class RelationshipUi {
    public enum Role { DOM, SUB }
    public enum Delivery { LOCAL, PEER }
    public enum Availability { AVAILABLE, OFFLINE, UNKNOWN }
    public enum OperationState { PREPARED, PENDING, ACTIVE, CANCELLED, EXPIRED, REFUSED, UNAVAILABLE }
    public enum Reason { UNAVAILABLE, REFUSED, EXPIRED, CANCELLED }

    public interface Provider {
        CompletableFuture<Snapshot> snapshot();

        /** Reuse operationKey while preparation is unresolved, including after a lost response. */
        CompletableFuture<LocalReview> prepareLocal(String operationKey, String domRef,
                String subRef, String expectedSnapshotRevision);

        /** Look up and revalidate the durable operation; display fields are never approval input. */
        CompletableFuture<Operation> confirmLocal(String operationId, String expectedDigest);

        /** Observe the original operation and deadline without regenerating credentials. */
        CompletableFuture<Operation> resume(String operationId);

        /** Revalidate current role and ownership before cancelling this exact operation. */
        CompletableFuture<Operation> cancel(String operationId);
    }

    public static final class Participant {
        public final String ref;
        public final Role role;
        public final ProfileData profile;

        public Participant(String ref, Role role, ProfileData profile) {
            this.ref = required(ref, "participant ref");
            this.role = Objects.requireNonNull(role);
            this.profile = Objects.requireNonNull(profile);
        }
    }

    /** An eligible ACTIVE edge; availability and freshness never determine membership. */
    public static final class Edge {
        public final String relationshipId, generation, domParticipantRef, subParticipantRef;
        public final Delivery delivery;
        public final String state;
        public final long insertionOrder;
        public final PersonId personId;
        /** The adapter observes usability; LOCAL delivery alone never implies availability. */
        public final Availability availability;
        public final PersonSnapshot.Freshness freshness;

        public Edge(String relationshipId, String generation, String domParticipantRef,
                String subParticipantRef, Delivery delivery, String state, long insertionOrder,
                PersonId personId, Availability availability, PersonSnapshot.Freshness freshness) {
            this.relationshipId = required(relationshipId, "relationship ID");
            this.generation = required(generation, "relationship generation");
            this.domParticipantRef = required(domParticipantRef, "Dom participant ref");
            this.subParticipantRef = required(subParticipantRef, "Sub participant ref");
            this.delivery = Objects.requireNonNull(delivery);
            if (!"ACTIVE".equals(state)) throw new IllegalArgumentException("Ineligible edge");
            this.state = state;
            this.insertionOrder = positive(insertionOrder, "insertion order");
            this.personId = Objects.requireNonNull(personId);
            this.availability = Objects.requireNonNull(availability);
            this.freshness = Objects.requireNonNull(freshness);
        }
    }

    /** Display-only review. Confirmation accepts its operation ID and expected digest only. */
    public static final class LocalReview {
        public final String operationId, requestDigest, domParticipantRef, subParticipantRef;
        public final String scopeDigest, scopeSummary;
        public final long originalDeadline;

        public LocalReview(String operationId, String requestDigest, String domParticipantRef,
                String subParticipantRef, String scopeDigest, long originalDeadline,
                String scopeSummary) {
            this.operationId = required(operationId, "operation ID");
            this.requestDigest = digest(requestDigest, "request digest");
            this.domParticipantRef = required(domParticipantRef, "Dom participant ref");
            this.subParticipantRef = required(subParticipantRef, "Sub participant ref");
            this.scopeDigest = digest(scopeDigest, "scope digest");
            this.originalDeadline = positive(originalDeadline, "original deadline");
            this.scopeSummary = Objects.requireNonNull(scopeSummary);
        }
    }

    /** Secret-free coordinator status; the adapter maps internal nonterminal stages to PENDING. */
    public static final class Operation {
        public final String operationId, requestDigest;
        public final OperationState state;
        public final long originalDeadline;
        /** Both absent until the coordinator supplies a final relationship identity. */
        public final String relationshipId, generation;
        /** Null when the operation has no unavailable/refused/expired/cancelled result. */
        public final Reason reason;

        public Operation(String operationId, String requestDigest, OperationState state,
                long originalDeadline, String relationshipId, String generation, Reason reason) {
            this.operationId = required(operationId, "operation ID");
            this.requestDigest = digest(requestDigest, "request digest");
            this.state = Objects.requireNonNull(state);
            this.originalDeadline = positive(originalDeadline, "original deadline");
            if ((relationshipId == null) != (generation == null)) {
                throw new IllegalArgumentException("Incomplete relationship identity");
            }
            this.relationshipId = relationshipId == null ? null
                    : required(relationshipId, "relationship ID");
            this.generation = generation == null ? null : required(generation, "generation");
            if (state == OperationState.ACTIVE) {
                if (relationshipId == null || reason != null) {
                    throw new IllegalArgumentException("Invalid active result");
                }
            } else {
                if (relationshipId != null) {
                    throw new IllegalArgumentException("Only active results contain a relationship");
                }
                if (state == OperationState.PREPARED || state == OperationState.PENDING) {
                    if (reason != null) throw new IllegalArgumentException("Invalid pending result");
                } else if (reason == null || !state.name().equals(reason.name())) {
                    throw new IllegalArgumentException("Failure state and reason differ");
                }
            }
            this.reason = reason;
        }
    }

    public static final class Snapshot {
        public final boolean supported;
        /** A change witness, never an authorization token; absent when unsupported. */
        public final String revision;
        public final List<Participant> localParticipants;
        public final List<Edge> eligibleSubs;
        public final Edge governingDom;
        public final List<Operation> operations;

        public Snapshot(boolean supported, String revision, List<Participant> localParticipants,
                List<Edge> eligibleSubs, Edge governingDom, List<Operation> operations) {
            this.localParticipants = copy(localParticipants);
            this.eligibleSubs = copy(eligibleSubs);
            this.operations = copy(operations);
            long previousOrder = 0;
            for (Edge edge : this.eligibleSubs) {
                if (edge.insertionOrder <= previousOrder) {
                    throw new IllegalArgumentException("Eligible edges must have increasing insertion order");
                }
                previousOrder = edge.insertionOrder;
            }
            if (!supported && (revision != null || !this.localParticipants.isEmpty()
                    || !this.eligibleSubs.isEmpty() || governingDom != null
                    || !this.operations.isEmpty())) {
                throw new IllegalArgumentException("Unsupported snapshot contains formal data");
            }
            this.supported = supported;
            this.revision = supported ? required(revision, "snapshot revision") : null;
            this.governingDom = governingDom;
        }
    }

    public static final class Failure extends RuntimeException {
        public final Reason reason;

        public Failure(Reason reason) {
            super(Objects.requireNonNull(reason).name());
            this.reason = reason;
        }
    }

    private static final Snapshot UNSUPPORTED = new Snapshot(false, null,
            Collections.emptyList(), Collections.emptyList(), null, Collections.emptyList());
    private static final Provider UNAVAILABLE = new Provider() {
        @Override public CompletableFuture<Snapshot> snapshot() {
            return CompletableFuture.completedFuture(UNSUPPORTED);
        }
        @Override public CompletableFuture<LocalReview> prepareLocal(String operationKey,
                String domRef, String subRef, String expectedSnapshotRevision) {
            return unavailableResult();
        }
        @Override public CompletableFuture<Operation> confirmLocal(String operationId,
                String expectedDigest) {
            return unavailableResult();
        }
        @Override public CompletableFuture<Operation> resume(String operationId) {
            return unavailableResult();
        }
        @Override public CompletableFuture<Operation> cancel(String operationId) {
            return unavailableResult();
        }
    };
    private static volatile Provider provider = UNAVAILABLE;

    private RelationshipUi() { }

    public static Provider provider() { return provider; }

    /** The integration owner supplies the real coordinator adapter. */
    public static void install(Provider value) { provider = Objects.requireNonNull(value); }

    public static Provider unavailable() { return UNAVAILABLE; }

    private static <T> CompletableFuture<T> unavailableResult() {
        CompletableFuture<T> result = new CompletableFuture<>();
        result.completeExceptionally(new Failure(Reason.UNAVAILABLE));
        return result;
    }

    private static String required(String value, String name) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }

    private static String digest(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return value;
    }

    private static long positive(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException("Invalid " + name);
        return value;
    }

    private static <T> List<T> copy(List<T> values) {
        ArrayList<T> result = new ArrayList<>(Objects.requireNonNull(values));
        for (T value : result) Objects.requireNonNull(value);
        return Collections.unmodifiableList(result);
    }
}
