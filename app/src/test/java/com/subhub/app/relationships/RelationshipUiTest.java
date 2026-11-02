package com.subhub.app.relationships;

import static org.junit.Assert.*;

import com.subhub.app.people.PersonId;
import com.subhub.app.people.PersonSnapshot;
import com.subhub.app.people.ProfileData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.Test;

public class RelationshipUiTest {
    private static final String DIGEST =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    @Test public void unavailableHasNoFormalIdentityAndActionsCannotSucceed() throws Exception {
        RelationshipUi.Provider provider = RelationshipUi.unavailable();
        assertSame(provider, RelationshipUi.provider());
        RelationshipUi.Snapshot snapshot = provider.snapshot().get();
        assertFalse(snapshot.supported);
        assertNull(snapshot.revision);
        assertTrue(snapshot.localParticipants.isEmpty());
        assertTrue(snapshot.eligibleSubs.isEmpty());
        assertNull(snapshot.governingDom);
        assertTrue(snapshot.operations.isEmpty());
        for (CompletableFuture<?> future : Arrays.asList(
                provider.prepareLocal("retry-key", "dom", "sub", "revision"),
                provider.confirmLocal("operation", "digest"), provider.resume("operation"),
                provider.cancel("operation"))) {
            try {
                future.get();
                fail("Unavailable action succeeded");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof RelationshipUi.Failure);
                assertEquals(RelationshipUi.Reason.UNAVAILABLE,
                        ((RelationshipUi.Failure) expected.getCause()).reason);
            }
        }
    }

    @Test public void snapshotCopiesCollectionsAndKeepsBackendOrderAndOfflineEligibility() {
        RelationshipUi.Participant participant = new RelationshipUi.Participant(
                "dom", RelationshipUi.Role.DOM, ProfileData.empty("Owner"));
        RelationshipUi.Edge offline = edge("first", 10, RelationshipUi.Availability.OFFLINE);
        RelationshipUi.Edge online = edge("second", 20, RelationshipUi.Availability.AVAILABLE);
        RelationshipUi.Operation operation = new RelationshipUi.Operation(
                "operation", DIGEST, RelationshipUi.OperationState.PENDING, 4000, null, null, null);
        List<RelationshipUi.Participant> participants = new ArrayList<>(Collections.singletonList(participant));
        List<RelationshipUi.Edge> edges = new ArrayList<>(Arrays.asList(offline, online));
        List<RelationshipUi.Operation> operations = new ArrayList<>(Collections.singletonList(operation));
        RelationshipUi.Snapshot snapshot = new RelationshipUi.Snapshot(
                true, "revision", participants, edges, null, operations);
        participants.clear();
        edges.clear();
        operations.clear();
        assertSame(participant, snapshot.localParticipants.get(0));
        assertEquals(Arrays.asList(offline, online), snapshot.eligibleSubs);
        assertSame(operation, snapshot.operations.get(0));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.localParticipants.clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.eligibleSubs.clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.operations.clear());
    }

    @Test public void unsupportedSnapshotRejectsFormalDataInsteadOfInventingFallback() {
        assertThrows(IllegalArgumentException.class, () -> new RelationshipUi.Snapshot(false,
                "revision", Collections.emptyList(), Collections.emptyList(), null, Collections.emptyList()));
        assertThrows(IllegalArgumentException.class, () -> new RelationshipUi.Snapshot(false,
                null, Collections.emptyList(), Collections.singletonList(
                        edge("edge", 1, RelationshipUi.Availability.OFFLINE)),
                null, Collections.emptyList()));
    }

    @Test public void pendingOrRevokedEdgesCannotBecomeEligibleEvenWhenOnline() {
        for (String state : Arrays.asList("PENDING", "REVOKED", "UNLINKED")) {
            assertThrows(IllegalArgumentException.class, () -> new RelationshipUi.Edge(
                    "relationship", "generation", "dom", "sub", RelationshipUi.Delivery.PEER,
                    state, 1, new PersonId("peer"), RelationshipUi.Availability.AVAILABLE,
                    PersonSnapshot.Freshness.CURRENT));
        }
    }

    @Test public void activeRequiresCompleteIdentityAndNoFailureReason() {
        RelationshipUi.Operation operation = operation(RelationshipUi.OperationState.ACTIVE,
                "relationship", "generation", null);
        assertEquals(1234, operation.originalDeadline);
        assertEquals(DIGEST, operation.requestDigest);
        for (String[] identity : new String[][] {{null, null}, {"relationship", null},
                {null, "generation"}, {"", "generation"}, {"relationship", ""}}) {
            assertThrows(IllegalArgumentException.class, () -> operation(
                    RelationshipUi.OperationState.ACTIVE, identity[0], identity[1], null));
        }
        for (RelationshipUi.Reason reason : RelationshipUi.Reason.values()) {
            assertThrows(IllegalArgumentException.class, () -> operation(
                    RelationshipUi.OperationState.ACTIVE, "relationship", "generation", reason));
        }
    }

    @Test public void preparedAndPendingCannotCarryFinalIdentityOrFailure() {
        for (RelationshipUi.OperationState state : Arrays.asList(
                RelationshipUi.OperationState.PREPARED, RelationshipUi.OperationState.PENDING)) {
            assertEquals(state, operation(state, null, null, null).state);
            for (String[] identity : new String[][] {{"relationship", "generation"},
                    {"relationship", null}, {null, "generation"}}) {
                assertThrows(IllegalArgumentException.class,
                        () -> operation(state, identity[0], identity[1], null));
            }
            for (RelationshipUi.Reason reason : RelationshipUi.Reason.values()) {
                assertThrows(IllegalArgumentException.class,
                        () -> operation(state, null, null, reason));
            }
        }
    }

    @Test public void terminalFailuresRequireMatchingReasonAndNoFinalIdentity() {
        for (RelationshipUi.Reason reason : RelationshipUi.Reason.values()) {
            RelationshipUi.OperationState state = RelationshipUi.OperationState.valueOf(reason.name());
            assertEquals(reason, operation(state, null, null, reason).reason);
            assertThrows(IllegalArgumentException.class, () -> operation(state, null, null, null));
            for (RelationshipUi.Reason other : RelationshipUi.Reason.values()) {
                if (other != reason) assertThrows(IllegalArgumentException.class,
                        () -> operation(state, null, null, other));
            }
            for (String[] identity : new String[][] {{"relationship", "generation"},
                    {"relationship", null}, {null, "generation"}}) {
                assertThrows(IllegalArgumentException.class,
                        () -> operation(state, identity[0], identity[1], reason));
            }
        }
    }

    @Test public void digestsMustBeExactly64LowercaseHexForReviewsAndOperations() {
        assertEquals(DIGEST, review(DIGEST, DIGEST, 1).requestDigest);
        for (String invalid : Arrays.asList(null, "", DIGEST.substring(1), DIGEST + "0",
                DIGEST.toUpperCase(java.util.Locale.ROOT), "g" + DIGEST.substring(1))) {
            assertThrows(IllegalArgumentException.class, () -> review(invalid, DIGEST, 1));
            assertThrows(IllegalArgumentException.class, () -> review(DIGEST, invalid, 1));
            assertThrows(IllegalArgumentException.class, () -> new RelationshipUi.Operation(
                    "opaque operation", invalid, RelationshipUi.OperationState.PREPARED,
                    1, null, null, null));
        }
    }

    @Test public void deadlinesAndInsertionOrderMustBePositive() {
        for (long invalid : new long[] {0, -1, Long.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> review(DIGEST, DIGEST, invalid));
            assertThrows(IllegalArgumentException.class, () -> new RelationshipUi.Operation(
                    "operation", DIGEST, RelationshipUi.OperationState.PREPARED,
                    invalid, null, null, null));
            assertThrows(IllegalArgumentException.class,
                    () -> edge("edge", invalid, RelationshipUi.Availability.UNKNOWN));
        }
    }

    @Test public void eligibleOrderRejectsDuplicatesAndDescendingWithoutSorting() {
        RelationshipUi.Edge first = edge("first", 10, RelationshipUi.Availability.OFFLINE);
        RelationshipUi.Edge second = edge("second", 20, RelationshipUi.Availability.UNKNOWN);
        RelationshipUi.Edge duplicateOrder = edge("third", 10, RelationshipUi.Availability.AVAILABLE);
        for (List<RelationshipUi.Edge> edges : Arrays.asList(Arrays.asList(second, first),
                Arrays.asList(first, duplicateOrder), Arrays.asList(first, first))) {
            assertThrows(IllegalArgumentException.class, () -> new RelationshipUi.Snapshot(true,
                    "revision", Collections.emptyList(), edges, null, Collections.emptyList()));
        }
    }

    @Test public void availabilityAndActualPersonFreshnessRemainIndependentOfDelivery() {
        for (RelationshipUi.Availability availability : RelationshipUi.Availability.values()) {
            for (PersonSnapshot.Freshness freshness : PersonSnapshot.Freshness.values()) {
                RelationshipUi.Edge edge = new RelationshipUi.Edge("relationship", "generation",
                        "dom", "sub", RelationshipUi.Delivery.PEER, "ACTIVE", 1,
                        new PersonId("peer"), availability, freshness);
                assertSame(availability, edge.availability);
                assertSame(freshness, edge.freshness);
                assertEquals("ACTIVE", edge.state);
            }
        }
        RelationshipUi.Edge local = new RelationshipUi.Edge("local relationship", "generation",
                "dom", "sub", RelationshipUi.Delivery.LOCAL, "ACTIVE", 1, PersonId.LOCAL,
                RelationshipUi.Availability.AVAILABLE, PersonSnapshot.Freshness.CURRENT);
        assertEquals(RelationshipUi.Delivery.LOCAL, local.delivery);
        assertEquals(PersonId.LOCAL, local.personId);
    }

    private static RelationshipUi.LocalReview review(String requestDigest, String scopeDigest,
            long deadline) {
        return new RelationshipUi.LocalReview("opaque operation", requestDigest, "opaque dom",
                "opaque sub", scopeDigest, deadline, "Scope summary");
    }

    private static RelationshipUi.Operation operation(RelationshipUi.OperationState state,
            String relationshipId, String generation, RelationshipUi.Reason reason) {
        return new RelationshipUi.Operation("operation", DIGEST, state, 1234,
                relationshipId, generation, reason);
    }

    private static RelationshipUi.Edge edge(String id, long order,
            RelationshipUi.Availability availability) {
        return new RelationshipUi.Edge(id, "generation", "dom", "sub",
                RelationshipUi.Delivery.PEER, "ACTIVE", order, new PersonId("peer-" + id),
                availability, PersonSnapshot.Freshness.STALE);
    }
}
