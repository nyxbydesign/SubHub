package com.subhub.app.relationships;

import com.subhub.app.people.ProfileNames;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Recovery presentation values; structural validation grants no relationship authority. */
public final class RelationshipRecoveryUi {
    public enum OperationKind { LOCAL_LINK, PEER_INVITE_CREATE, PEER_INVITE_ACCEPT }

    public interface Provider extends RelationshipUi.Provider {
        /** Supply both projections from one authorized, consistent coordinator read. */
        CompletableFuture<RecoverySnapshot> recoverySnapshot();

        /** Revalidate original scope and current authority; never renew or reprepare a review. */
        CompletableFuture<ReviewResult> reviewLocal(String operationId, String expectedDigest);

        /** Missing rows alone do not establish absence, including after pruning or restore. */
        CompletableFuture<PreparationLookup> lookupPreparation(String operationKey, String domRef,
                String subRef, String originalSnapshotRevision);
    }

    public static final class OperationSummary {
        public final RelationshipUi.Operation operation;
        public final OperationKind kind;
        public final RelationshipUi.Delivery delivery;
        public final String domParticipantRef, subParticipantRef, domLabel, subLabel;

        /** Nonnull refs and labels still require backend authentication and disclosure authority. */
        public OperationSummary(RelationshipUi.Operation operation, OperationKind kind,
                RelationshipUi.Delivery delivery, String domParticipantRef, String subParticipantRef,
                String domLabel, String subLabel) {
            this.operation = Objects.requireNonNull(operation);
            this.kind = Objects.requireNonNull(kind);
            this.delivery = Objects.requireNonNull(delivery);
            this.domParticipantRef = optionalRef(domParticipantRef);
            this.subParticipantRef = optionalRef(subParticipantRef);
            if (kind == OperationKind.LOCAL_LINK) {
                if (delivery != RelationshipUi.Delivery.LOCAL || domParticipantRef == null
                        || subParticipantRef == null || domParticipantRef.equals(subParticipantRef)) {
                    throw new IllegalArgumentException("Invalid local participants or delivery");
                }
            } else if (delivery != RelationshipUi.Delivery.PEER
                    || kind == OperationKind.PEER_INVITE_CREATE && domParticipantRef == null
                    || kind == OperationKind.PEER_INVITE_ACCEPT && subParticipantRef == null) {
                throw new IllegalArgumentException("Invalid peer participants or delivery");
            }
            if (operation.state == RelationshipUi.OperationState.ACTIVE
                    && (domParticipantRef == null || subParticipantRef == null)) {
                throw new IllegalArgumentException("Active operation requires both participants");
            }
            this.domLabel = label(domParticipantRef, domLabel);
            this.subLabel = label(subParticipantRef, subLabel);
        }
    }

    public static final class RecoverySnapshot {
        public final RelationshipUi.Snapshot snapshot;
        public final List<OperationSummary> summaries;

        /** Exact structural correspondence cannot prove a common SQL read or revision provenance. */
        public RecoverySnapshot(RelationshipUi.Snapshot snapshot, List<OperationSummary> summaries) {
            this.snapshot = Objects.requireNonNull(snapshot);
            List<OperationSummary> copied = new ArrayList<>(Objects.requireNonNull(summaries));
            for (OperationSummary value : copied) Objects.requireNonNull(value);
            if (snapshot.operations.size() != copied.size()) {
                throw new IllegalArgumentException("Operation summary count differs");
            }
            Set<String> operationIds = new HashSet<>();
            Set<String> summaryIds = new HashSet<>();
            for (int index = 0; index < copied.size(); index++) {
                RelationshipUi.Operation operation = snapshot.operations.get(index);
                RelationshipUi.Operation summary = copied.get(index).operation;
                if (!operationIds.add(operation.operationId) || !summaryIds.add(summary.operationId)
                        || !sameOperation(operation, summary)) {
                    throw new IllegalArgumentException("Operation summaries differ or repeat");
                }
            }
            this.summaries = Collections.unmodifiableList(copied);
        }
    }

    public static final class ReviewResult {
        public enum Kind { REVIEW, STATUS, REFUSED, NOT_FOUND, UNKNOWN, UNAVAILABLE }
        public final Kind kind;
        public final OperationSummary summary;
        public final RelationshipUi.LocalReview review;

        /** Only REVIEW carries confirmable display data; current scope and authorization stay backend duties. */
        public ReviewResult(Kind kind, OperationSummary summary, RelationshipUi.LocalReview review) {
            this.kind = Objects.requireNonNull(kind);
            if (kind == Kind.REVIEW) {
                Objects.requireNonNull(summary);
                Objects.requireNonNull(review);
                RelationshipUi.Operation operation = summary.operation;
                if (summary.kind != OperationKind.LOCAL_LINK
                        || summary.delivery != RelationshipUi.Delivery.LOCAL
                        || operation.state != RelationshipUi.OperationState.PREPARED
                        || !operation.operationId.equals(review.operationId)
                        || !operation.requestDigest.equals(review.requestDigest)
                        || operation.originalDeadline != review.originalDeadline
                        || !summary.domParticipantRef.equals(review.domParticipantRef)
                        || !summary.subParticipantRef.equals(review.subParticipantRef)) {
                    throw new IllegalArgumentException("Review differs from prepared local operation");
                }
            } else if (kind == Kind.STATUS) {
                Objects.requireNonNull(summary);
                if (review != null) throw new IllegalArgumentException("Status contains a review");
            } else if (summary != null || review != null) {
                throw new IllegalArgumentException("Query outcome contains operation data");
            }
            this.summary = summary;
            this.review = review;
        }
    }

    public static final class PreparationLookup {
        public enum Kind { FOUND, ABSENT_NEVER_CREATED, UNKNOWN, REFUSED, UNAVAILABLE }
        public final Kind kind;
        public final OperationSummary summary;

        /** ABSENT_NEVER_CREATED requires backend nonrollback/restore proof; this value supplies none. */
        public PreparationLookup(Kind kind, OperationSummary summary) {
            this.kind = Objects.requireNonNull(kind);
            if (kind == Kind.FOUND) Objects.requireNonNull(summary);
            else if (summary != null) throw new IllegalArgumentException("Lookup outcome contains operation data");
            this.summary = summary;
        }
    }

    private RelationshipRecoveryUi() { }

    private static String optionalRef(String value) {
        if (value != null && value.isEmpty()) throw new IllegalArgumentException("Empty participant ref");
        return value;
    }

    private static String label(String ref, String value) {
        if (ref == null && value != null) throw new IllegalArgumentException("Label has no participant");
        return value == null ? null : ProfileNames.clean(value);
    }

    private static boolean sameOperation(RelationshipUi.Operation first, RelationshipUi.Operation second) {
        return first.operationId.equals(second.operationId) && first.requestDigest.equals(second.requestDigest)
                && first.state == second.state && first.originalDeadline == second.originalDeadline
                && Objects.equals(first.relationshipId, second.relationshipId)
                && Objects.equals(first.generation, second.generation) && first.reason == second.reason;
    }
}
