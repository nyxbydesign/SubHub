package com.subhub.app.relationships;

import static org.junit.Assert.*;

import com.subhub.app.people.ProfileNames;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

public final class RelationshipRecoveryUiTest {
    private static final String DIGEST = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String OTHER_DIGEST = "1123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test public void localLinkRequiresTwoDistinctLogicalRefsAndLocalDelivery() {
        RelationshipRecoveryUi.OperationSummary value = local(prepared("operation"));
        assertEquals("dom", value.domParticipantRef);
        assertEquals("sub", value.subParticipantRef);
        for (String dom : new String[] {null, ""})
            rejects(() -> summary(prepared("operation"), RelationshipRecoveryUi.OperationKind.LOCAL_LINK,
                    RelationshipUi.Delivery.LOCAL, dom, "sub", null, null));
        for (String sub : new String[] {null, "", "dom"})
            rejects(() -> summary(prepared("operation"), RelationshipRecoveryUi.OperationKind.LOCAL_LINK,
                    RelationshipUi.Delivery.LOCAL, "dom", sub, null, null));
        rejects(() -> summary(prepared("operation"), RelationshipRecoveryUi.OperationKind.LOCAL_LINK,
                RelationshipUi.Delivery.PEER, "dom", "sub", null, null));
        RelationshipRecoveryUi.OperationSummary opaque = summary(prepared("opaque"),
                RelationshipRecoveryUi.OperationKind.LOCAL_LINK, RelationshipUi.Delivery.LOCAL,
                " dom ", "sub\u0001", null, null);
        assertEquals(" dom ", opaque.domParticipantRef);
        assertEquals("sub\u0001", opaque.subParticipantRef);
    }

    @Test public void peerInvitationsPermitOnlyTheirUnboundRemoteSide() {
        RelationshipRecoveryUi.OperationSummary create = summary(prepared("create"),
                RelationshipRecoveryUi.OperationKind.PEER_INVITE_CREATE, RelationshipUi.Delivery.PEER,
                "dom", null, "Dom", null);
        assertNull(create.subParticipantRef); assertNull(create.subLabel);
        RelationshipRecoveryUi.OperationSummary accept = summary(prepared("accept"),
                RelationshipRecoveryUi.OperationKind.PEER_INVITE_ACCEPT, RelationshipUi.Delivery.PEER,
                null, "sub", null, "Sub");
        assertNull(accept.domParticipantRef); assertNull(accept.domLabel);
        for (RelationshipRecoveryUi.OperationKind kind : Arrays.asList(
                RelationshipRecoveryUi.OperationKind.PEER_INVITE_CREATE,
                RelationshipRecoveryUi.OperationKind.PEER_INVITE_ACCEPT)) {
            rejects(() -> summary(prepared("wrong-delivery"), kind, RelationshipUi.Delivery.LOCAL,
                    "dom", "sub", null, null));
            rejects(() -> summary(prepared("no-local"), kind, RelationshipUi.Delivery.PEER,
                    kind == RelationshipRecoveryUi.OperationKind.PEER_INVITE_CREATE ? null : "dom",
                    kind == RelationshipRecoveryUi.OperationKind.PEER_INVITE_ACCEPT ? null : "sub", null, null));
            rejects(() -> summary(prepared("empty-dom"), kind, RelationshipUi.Delivery.PEER,
                    "", "sub", null, null));
            rejects(() -> summary(prepared("empty-sub"), kind, RelationshipUi.Delivery.PEER,
                    "dom", "", null, null));
            assertNotNull(summary(prepared("bound"), kind, RelationshipUi.Delivery.PEER,
                    "dom", "sub", null, null));
        }
    }

    @Test public void activeRequiresBothRefsAndRetainsOriginalFinalIdentity() {
        RelationshipUi.Operation active = operation("active", DIGEST, RelationshipUi.OperationState.ACTIVE,
                100, "relationship", "generation", null);
        for (RelationshipRecoveryUi.OperationKind kind : RelationshipRecoveryUi.OperationKind.values()) {
            RelationshipRecoveryUi.OperationSummary summary = summary(active, kind,
                    kind == RelationshipRecoveryUi.OperationKind.LOCAL_LINK
                            ? RelationshipUi.Delivery.LOCAL : RelationshipUi.Delivery.PEER,
                    "dom", "sub", null, null);
            assertSame(active, summary.operation);
            assertEquals("relationship", summary.operation.relationshipId);
            assertEquals("generation", summary.operation.generation);
        }
        rejects(() -> summary(active, RelationshipRecoveryUi.OperationKind.PEER_INVITE_CREATE,
                RelationshipUi.Delivery.PEER, "dom", null, null, null));
        rejects(() -> summary(active, RelationshipRecoveryUi.OperationKind.PEER_INVITE_ACCEPT,
                RelationshipUi.Delivery.PEER, null, "sub", null, null));
        for (RelationshipUi.OperationState state : RelationshipUi.OperationState.values()) {
            if (state == RelationshipUi.OperationState.ACTIVE) continue;
            RelationshipUi.Reason reason = state == RelationshipUi.OperationState.PREPARED
                    || state == RelationshipUi.OperationState.PENDING ? null : RelationshipUi.Reason.valueOf(state.name());
            assertNotNull(summary(operation("unbound", DIGEST, state, 100, null, null, reason),
                    RelationshipRecoveryUi.OperationKind.PEER_INVITE_CREATE,
                    RelationshipUi.Delivery.PEER, "dom", null, null, null));
        }
    }

    @Test public void labelsUseActualProfileNameCleaningWithoutInventingIdentity() {
        String raw = "  Ada\nLovelace\t\u0000\u2028\u2029  ";
        RelationshipRecoveryUi.OperationSummary cleaned = summary(prepared("labels"),
                RelationshipRecoveryUi.OperationKind.LOCAL_LINK, RelationshipUi.Delivery.LOCAL,
                "dom", "sub", raw, "\n\u2028\u2029");
        assertEquals("Ada Lovelace", cleaned.domLabel);
        assertEquals(ProfileNames.clean(raw), cleaned.domLabel);
        assertEquals("", cleaned.subLabel);
        assertNull(local(prepared("absent-labels")).domLabel);
        assertNull(local(prepared("absent-labels")).subLabel);
        String splitPair = "a".repeat(79) + "\ud83d\ude00";
        String completePair = "a".repeat(78) + "\ud83d\ude00" + "tail";
        RelationshipRecoveryUi.OperationSummary bounded = summary(prepared("long-labels"),
                RelationshipRecoveryUi.OperationKind.LOCAL_LINK, RelationshipUi.Delivery.LOCAL,
                "dom", "sub", splitPair, completePair);
        assertEquals("a".repeat(79), bounded.domLabel);
        assertEquals(80, bounded.subLabel.length());
        assertTrue(bounded.subLabel.endsWith("\ud83d\ude00"));
        assertEquals(ProfileNames.MAX_LENGTH, bounded.subLabel.length());
        rejects(() -> summary(prepared("unbound-sub-label"), RelationshipRecoveryUi.OperationKind.PEER_INVITE_CREATE,
                RelationshipUi.Delivery.PEER, "dom", null, null, ""));
        rejects(() -> summary(prepared("unbound-dom-label"), RelationshipRecoveryUi.OperationKind.PEER_INVITE_ACCEPT,
                RelationshipUi.Delivery.PEER, null, "sub", "\n", null));
    }

    @Test public void snapshotMatchesIndependentStructuralCopiesInOriginalOrder() {
        RelationshipUi.Operation first = prepared("first");
        RelationshipUi.Operation second = operation("second", OTHER_DIGEST, RelationshipUi.OperationState.ACTIVE,
                200, "relationship", "generation", null);
        List<RelationshipRecoveryUi.OperationSummary> summaries = Arrays.asList(local(copy(first)), local(copy(second)));
        RelationshipUi.Snapshot base = snapshot(first, second);
        RelationshipRecoveryUi.RecoverySnapshot result = new RelationshipRecoveryUi.RecoverySnapshot(base, summaries);
        assertSame(base, result.snapshot);
        assertNotSame(first, result.summaries.get(0).operation);
        assertEquals("first", result.summaries.get(0).operation.operationId);
        assertEquals("second", result.summaries.get(1).operation.operationId);
        rejects(() -> new RelationshipRecoveryUi.RecoverySnapshot(base,
                Arrays.asList(summaries.get(1), summaries.get(0))));
    }

    @Test public void snapshotRejectsChangesToEveryOriginalOperationField() {
        RelationshipUi.Operation prepared = prepared("operation");
        for (RelationshipUi.Operation different : Arrays.asList(
                operation("other", DIGEST, RelationshipUi.OperationState.PREPARED, 100, null, null, null),
                operation("operation", OTHER_DIGEST, RelationshipUi.OperationState.PREPARED, 100, null, null, null),
                operation("operation", DIGEST, RelationshipUi.OperationState.PENDING, 100, null, null, null),
                operation("operation", DIGEST, RelationshipUi.OperationState.PREPARED, 101, null, null, null))) {
            rejects(() -> recovery(snapshot(prepared), local(different)));
        }
        RelationshipUi.Operation active = operation("operation", DIGEST, RelationshipUi.OperationState.ACTIVE,
                100, "relationship", "generation", null);
        rejects(() -> recovery(snapshot(active), local(operation("operation", DIGEST, RelationshipUi.OperationState.ACTIVE,
                100, "different-relationship", "generation", null))));
        rejects(() -> recovery(snapshot(active), local(operation("operation", DIGEST, RelationshipUi.OperationState.ACTIVE,
                100, "relationship", "different-generation", null))));
        RelationshipUi.Operation cancelled = operation("operation", DIGEST, RelationshipUi.OperationState.CANCELLED,
                100, null, null, RelationshipUi.Reason.CANCELLED);
        RelationshipUi.Operation expired = operation("operation", DIGEST, RelationshipUi.OperationState.EXPIRED,
                100, null, null, RelationshipUi.Reason.EXPIRED);
        rejects(() -> recovery(snapshot(cancelled), local(expired)));
        // The frozen Operation constructor binds each failure reason to its state; both remain compared.
        assertNotNull(recovery(snapshot(cancelled), local(copy(cancelled))));
    }

    @Test public void snapshotRejectsDuplicateMissingExtraAndUnsupportedSummaries() {
        RelationshipUi.Operation first = prepared("first"), second = prepared("second");
        rejects(() -> recovery(snapshot(first, copy(first)), local(first), local(copy(first))));
        rejects(() -> recovery(snapshot(first, second), local(first), local(copy(first))));
        rejects(() -> recovery(snapshot(first, second), local(first)));
        rejects(() -> recovery(snapshot(first), local(first), local(second)));
        rejects(() -> recovery(snapshot(), local(first)));
        RelationshipUi.Snapshot unsupported = new RelationshipUi.Snapshot(false, null,
                Collections.emptyList(), Collections.emptyList(), null, Collections.emptyList());
        assertTrue(recovery(unsupported).summaries.isEmpty());
        rejects(() -> recovery(unsupported, local(first)));
    }

    @Test public void snapshotCopiesListAndRejectsNullValues() {
        RelationshipRecoveryUi.OperationSummary summary = local(prepared("operation"));
        List<RelationshipRecoveryUi.OperationSummary> input = new ArrayList<>(); input.add(summary);
        RelationshipRecoveryUi.RecoverySnapshot result = new RelationshipRecoveryUi.RecoverySnapshot(
                snapshot(summary.operation), input);
        input.clear();
        assertEquals(1, result.summaries.size()); assertSame(summary, result.summaries.get(0));
        assertThrows(UnsupportedOperationException.class, () -> result.summaries.clear());
        assertThrows(UnsupportedOperationException.class, () -> result.summaries.set(0, summary));
        rejects(() -> new RelationshipRecoveryUi.RecoverySnapshot(null, Collections.emptyList()));
        rejects(() -> new RelationshipRecoveryUi.RecoverySnapshot(snapshot(), null));
        rejects(() -> new RelationshipRecoveryUi.RecoverySnapshot(snapshot(prepared("operation")),
                Collections.singletonList(null)));
    }

    @Test public void reviewAndStatusHaveStrictlyDifferentPayloads() {
        RelationshipRecoveryUi.OperationSummary summary = local(prepared("operation"));
        RelationshipUi.LocalReview review = review("operation", DIGEST, "dom", "sub", 100);
        for (RelationshipRecoveryUi.ReviewResult.Kind kind : RelationshipRecoveryUi.ReviewResult.Kind.values()) {
            if (kind == RelationshipRecoveryUi.ReviewResult.Kind.REVIEW) {
                RelationshipRecoveryUi.ReviewResult value = new RelationshipRecoveryUi.ReviewResult(kind, summary, review);
                assertSame(summary, value.summary); assertSame(review, value.review);
                rejects(() -> new RelationshipRecoveryUi.ReviewResult(kind, null, review));
                rejects(() -> new RelationshipRecoveryUi.ReviewResult(kind, summary, null));
                rejects(() -> new RelationshipRecoveryUi.ReviewResult(kind, null, null));
            } else if (kind == RelationshipRecoveryUi.ReviewResult.Kind.STATUS) {
                RelationshipRecoveryUi.ReviewResult value = new RelationshipRecoveryUi.ReviewResult(kind, summary, null);
                assertEquals(RelationshipUi.OperationState.PREPARED, value.summary.operation.state);
                assertNull(value.review);
                rejects(() -> new RelationshipRecoveryUi.ReviewResult(kind, null, null));
                rejects(() -> new RelationshipRecoveryUi.ReviewResult(kind, summary, review));
                rejects(() -> new RelationshipRecoveryUi.ReviewResult(kind, null, review));
            } else {
                RelationshipRecoveryUi.ReviewResult value = new RelationshipRecoveryUi.ReviewResult(kind, null, null);
                assertNull(value.summary); assertNull(value.review);
                rejects(() -> new RelationshipRecoveryUi.ReviewResult(kind, summary, null));
                rejects(() -> new RelationshipRecoveryUi.ReviewResult(kind, null, review));
                rejects(() -> new RelationshipRecoveryUi.ReviewResult(kind, summary, review));
            }
        }
        rejects(() -> new RelationshipRecoveryUi.ReviewResult(null, null, null));
    }

    @Test public void freshReviewMustMatchTheExactPreparedLocalOperation() {
        RelationshipRecoveryUi.OperationSummary summary = local(prepared("operation"));
        for (RelationshipUi.LocalReview different : Arrays.asList(
                review("other", DIGEST, "dom", "sub", 100),
                review("operation", OTHER_DIGEST, "dom", "sub", 100),
                review("operation", DIGEST, "other-dom", "sub", 100),
                review("operation", DIGEST, "dom", "other-sub", 100),
                review("operation", DIGEST, "dom", "sub", 101))) {
            rejects(() -> new RelationshipRecoveryUi.ReviewResult(
                    RelationshipRecoveryUi.ReviewResult.Kind.REVIEW, summary, different));
        }
        RelationshipUi.LocalReview exact = review("operation", DIGEST, "dom", "sub", 100);
        for (RelationshipUi.OperationState state : RelationshipUi.OperationState.values()) {
            if (state == RelationshipUi.OperationState.PREPARED) continue;
            RelationshipUi.Operation operation = operation("operation", DIGEST, state, 100,
                    state == RelationshipUi.OperationState.ACTIVE ? "relationship" : null,
                    state == RelationshipUi.OperationState.ACTIVE ? "generation" : null,
                    state == RelationshipUi.OperationState.PENDING || state == RelationshipUi.OperationState.ACTIVE
                            ? null : RelationshipUi.Reason.valueOf(state.name()));
            rejects(() -> new RelationshipRecoveryUi.ReviewResult(
                    RelationshipRecoveryUi.ReviewResult.Kind.REVIEW, local(operation), exact));
            assertNull(new RelationshipRecoveryUi.ReviewResult(
                    RelationshipRecoveryUi.ReviewResult.Kind.STATUS, local(operation), null).review);
        }
        for (RelationshipRecoveryUi.OperationKind peer : Arrays.asList(
                RelationshipRecoveryUi.OperationKind.PEER_INVITE_CREATE,
                RelationshipRecoveryUi.OperationKind.PEER_INVITE_ACCEPT)) {
            rejects(() -> new RelationshipRecoveryUi.ReviewResult(RelationshipRecoveryUi.ReviewResult.Kind.REVIEW,
                    summary(prepared("operation"), peer, RelationshipUi.Delivery.PEER, "dom", "sub", null, null), exact));
        }
    }

    @Test public void preparationLookupHasNoReviewAndOnlyFoundHasSummary() {
        RelationshipRecoveryUi.OperationSummary terminal = local(operation("operation", DIGEST,
                RelationshipUi.OperationState.CANCELLED, 100, null, null, RelationshipUi.Reason.CANCELLED));
        for (RelationshipRecoveryUi.PreparationLookup.Kind kind : RelationshipRecoveryUi.PreparationLookup.Kind.values()) {
            if (kind == RelationshipRecoveryUi.PreparationLookup.Kind.FOUND) {
                assertSame(terminal, new RelationshipRecoveryUi.PreparationLookup(kind, terminal).summary);
                rejects(() -> new RelationshipRecoveryUi.PreparationLookup(kind, null));
            } else {
                assertNull(new RelationshipRecoveryUi.PreparationLookup(kind, null).summary);
                rejects(() -> new RelationshipRecoveryUi.PreparationLookup(kind, terminal));
            }
        }
        rejects(() -> new RelationshipRecoveryUi.PreparationLookup(null, null));
    }

    @Test public void missingDiscriminatorsCannotBecomeLocalDefaults() {
        rejects(() -> summary(null, RelationshipRecoveryUi.OperationKind.LOCAL_LINK,
                RelationshipUi.Delivery.LOCAL, "dom", "sub", null, null));
        rejects(() -> summary(prepared("operation"), null, RelationshipUi.Delivery.LOCAL,
                "dom", "sub", null, null));
        rejects(() -> summary(prepared("operation"), RelationshipRecoveryUi.OperationKind.LOCAL_LINK,
                null, "dom", "sub", null, null));
    }

    @Test public void recoveryDtoSurfaceIsFinalWithoutProviderStateOrExtraProfileData() {
        for (Class<?> type : Arrays.asList(RelationshipRecoveryUi.OperationSummary.class,
                RelationshipRecoveryUi.RecoverySnapshot.class, RelationshipRecoveryUi.ReviewResult.class,
                RelationshipRecoveryUi.PreparationLookup.class)) {
            assertTrue(Modifier.isFinal(type.getModifiers()));
            for (Field field : type.getDeclaredFields()) {
                assertTrue(Modifier.isPublic(field.getModifiers()));
                assertTrue(Modifier.isFinal(field.getModifiers()));
                assertFalse(Modifier.isStatic(field.getModifiers()));
            }
        }
        assertEquals(0, RelationshipRecoveryUi.class.getDeclaredFields().length);
        for (Field field : RelationshipRecoveryUi.OperationSummary.class.getDeclaredFields())
            assertTrue(field.getType() == RelationshipUi.Operation.class
                    || field.getType() == RelationshipRecoveryUi.OperationKind.class
                    || field.getType() == RelationshipUi.Delivery.class || field.getType() == String.class);
        assertEquals(3, RelationshipRecoveryUi.Provider.class.getDeclaredMethods().length);
        for (Field field : RelationshipRecoveryUi.PreparationLookup.class.getDeclaredFields())
            assertNotEquals(RelationshipUi.LocalReview.class, field.getType());
    }

    // Compilation of these declarations checks source compatibility without installing a provider.
    private abstract static class RecoveryProviderFixture implements RelationshipRecoveryUi.Provider {
        @Override public abstract CompletableFuture<RelationshipRecoveryUi.RecoverySnapshot> recoverySnapshot();
        @Override public abstract CompletableFuture<RelationshipRecoveryUi.ReviewResult> reviewLocal(String id, String digest);
        @Override public abstract CompletableFuture<RelationshipRecoveryUi.PreparationLookup> lookupPreparation(
                String key, String dom, String sub, String revision);
    }
    @SuppressWarnings("unused") private static RelationshipUi.Provider parentProvider(RecoveryProviderFixture value) {
        return value;
    }

    private static RelationshipRecoveryUi.OperationSummary local(RelationshipUi.Operation operation) {
        return summary(operation, RelationshipRecoveryUi.OperationKind.LOCAL_LINK,
                RelationshipUi.Delivery.LOCAL, "dom", "sub", null, null);
    }
    private static RelationshipRecoveryUi.OperationSummary summary(RelationshipUi.Operation operation,
            RelationshipRecoveryUi.OperationKind kind, RelationshipUi.Delivery delivery,
            String dom, String sub, String domLabel, String subLabel) {
        return new RelationshipRecoveryUi.OperationSummary(operation, kind, delivery, dom, sub, domLabel, subLabel);
    }
    private static RelationshipUi.Operation prepared(String id) {
        return operation(id, DIGEST, RelationshipUi.OperationState.PREPARED, 100, null, null, null);
    }
    private static RelationshipUi.Operation operation(String id, String digest, RelationshipUi.OperationState state,
            long deadline, String relationship, String generation, RelationshipUi.Reason reason) {
        return new RelationshipUi.Operation(id, digest, state, deadline, relationship, generation, reason);
    }
    private static RelationshipUi.Operation copy(RelationshipUi.Operation value) {
        return operation(value.operationId, value.requestDigest, value.state, value.originalDeadline,
                value.relationshipId, value.generation, value.reason);
    }
    private static RelationshipUi.LocalReview review(String id, String digest, String dom, String sub, long deadline) {
        return new RelationshipUi.LocalReview(id, digest, dom, sub, OTHER_DIGEST, deadline, "Original scope");
    }
    private static RelationshipUi.Snapshot snapshot(RelationshipUi.Operation... operations) {
        return new RelationshipUi.Snapshot(true, "revision", Collections.emptyList(), Collections.emptyList(),
                null, Arrays.asList(operations));
    }
    private static RelationshipRecoveryUi.RecoverySnapshot recovery(RelationshipUi.Snapshot snapshot,
            RelationshipRecoveryUi.OperationSummary... summaries) {
        return new RelationshipRecoveryUi.RecoverySnapshot(snapshot, Arrays.asList(summaries));
    }
    private static void rejects(Runnable action) {
        assertThrows(RuntimeException.class, action::run);
    }
}
