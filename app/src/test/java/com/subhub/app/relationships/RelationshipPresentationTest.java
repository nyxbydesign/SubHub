package com.subhub.app.relationships;

import static org.junit.Assert.*;

import com.subhub.app.people.PersonId;
import com.subhub.app.people.PersonSnapshot;
import com.subhub.app.people.ProfileData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

public class RelationshipPresentationTest {
    private static final String DIGEST =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test public void onlyExactDefaultWithoutAuthoritativeHistoryUsesLegacy() {
        Fixture fixture = new Fixture();
        fixture.source = RelationshipUi.unavailable();
        fixture.open();
        assertEquals(RelationshipPresentation.Mode.LEGACY, fixture.model.mode());
        fixture.source = fixture.provider;
        fixture.provider.snapshot.completeExceptionally(new IllegalStateException("Unavailable"));
        fixture.model.refresh();
        assertEquals(RelationshipPresentation.Mode.UNAVAILABLE, fixture.model.mode());
        assertTrue(fixture.journal.seen);
        fixture.source = RelationshipUi.unavailable();
        fixture.open();
        assertEquals(RelationshipPresentation.Mode.UNAVAILABLE, fixture.model.mode());
    }

    @Test public void unsupportedRealProviderNeverMeansDefaultCompatibility() {
        Fixture fixture = new Fixture();
        fixture.provider.snapshot.complete(new RelationshipUi.Snapshot(false, null,
                Collections.emptyList(), Collections.emptyList(), null, Collections.emptyList()));
        fixture.open();
        assertEquals(RelationshipPresentation.Mode.UNAVAILABLE, fixture.model.mode());
        assertTrue(fixture.journal.seen);
        assertEquals(0, fixture.provider.confirmations.size());
    }

    @Test public void priorLegacyReadBecomesStaleWhenAnyAuthoritativeProviderIsUsed() {
        MemoryJournal journal = new MemoryJournal();
        RelationshipPresentation.Access legacy = RelationshipPresentation.access(RelationshipUi.unavailable(), journal).join();
        assertTrue(legacy.current(RelationshipUi::unavailable));
        journal.markAuthoritativeSeen();
        assertFalse(legacy.current(RelationshipUi::unavailable));
    }

    @Test public void rosterUsesOnlyExplicitActiveEdgesEvenWithSwitchProfileAndOffline() {
        RelationshipUi.Participant owner = new RelationshipUi.Participant("dom", RelationshipUi.Role.DOM,
                new ProfileData("Owner", "", "", "", 0, true));
        RelationshipUi.Edge offline = new RelationshipUi.Edge("relationship", "generation", "dom", "sub",
                RelationshipUi.Delivery.PEER, "ACTIVE", 1, new PersonId("paired"),
                RelationshipUi.Availability.OFFLINE, PersonSnapshot.Freshness.STALE);
        RelationshipUi.Snapshot empty = new RelationshipUi.Snapshot(true, "rev", Collections.singletonList(owner),
                Collections.emptyList(), null, Collections.emptyList());
        assertTrue(RelationshipPresentation.rosterIds(empty).isEmpty());
        RelationshipUi.Snapshot linked = new RelationshipUi.Snapshot(true, "rev", Collections.singletonList(owner),
                Collections.singletonList(offline), null, Collections.emptyList());
        assertEquals(Collections.singletonList(new PersonId("paired")), RelationshipPresentation.rosterIds(linked));
        assertThrows(UnsupportedOperationException.class, () -> RelationshipPresentation.rosterIds(linked).clear());
    }

    @Test public void lostPreparationResponseReusesOriginalKeyAndOperandsAfterRecreation() {
        Fixture fixture = ready();
        fixture.model.prepare();
        List<String> original = fixture.provider.preparations.get(0);
        fixture.model.detach();
        fixture.provider.preparation.complete(review("dom", "sub"));
        assertTrue(fixture.journal.value.unresolved());
        fixture.provider.preparation = new CompletableFuture<>();
        fixture.provider.snapshot = CompletableFuture.completedFuture(snapshot("new-revision", "dom", "sub"));
        fixture.open();
        assertEquals(1, fixture.provider.preparations.size());
        fixture.model.prepare();
        assertEquals(original, fixture.provider.preparations.get(1));
        assertEquals("revision", original.get(3));
        fixture.provider.preparation.complete(review("dom", "sub"));
        RelationshipPresentation.Saved saved = fixture.journal.value;
        assertNull(saved.operationKey); assertNull(saved.domRef); assertNull(saved.subRef); assertNull(saved.revision);
        assertEquals("operation", saved.operationId); assertEquals(DIGEST, saved.digest);
        assertEquals(5000, fixture.model.review().originalDeadline);
    }

    @Test public void recreationResumesOriginalOperationWithoutRecreatingReviewOrConfirming() {
        Fixture fixture = ready();
        fixture.model.prepare(); fixture.provider.preparation.complete(review("dom", "sub"));
        fixture.model.detach(); fixture.open();
        assertNull(fixture.model.review());
        assertEquals(0, fixture.provider.resumes.size());
        fixture.model.resume();
        assertEquals(Collections.singletonList("operation"), fixture.provider.resumes);
        fixture.provider.result.complete(operation(RelationshipUi.OperationState.PREPARED));
        assertEquals(5000, fixture.model.operation().originalDeadline);
        assertNull(fixture.model.review());
        fixture.model.confirm(null);
        assertTrue(fixture.provider.confirmations.isEmpty());
        assertEquals("operation", fixture.journal.value.operationId);
        assertEquals(DIGEST, fixture.journal.value.digest);
    }

    @Test public void detachRoleLockOrProviderChangeFencesLatePreparation() {
        for (String change : Arrays.asList("detach", "role", "lock", "provider")) {
            Fixture fixture = ready(); fixture.model.prepare();
            if (change.equals("detach")) fixture.model.detach();
            if (change.equals("role")) fixture.context = "sub-only";
            if (change.equals("lock")) fixture.context = null;
            if (change.equals("provider")) fixture.source = RelationshipUi.unavailable();
            fixture.provider.preparation.complete(review("dom", "sub"));
            assertNull(change, fixture.model.review());
            assertTrue(change, fixture.journal.value.unresolved());
            assertTrue(change, fixture.provider.confirmations.isEmpty());
        }
    }

    @Test public void changedChoiceCannotReuseOldKeyForDifferentParticipants() {
        Fixture fixture = ready(); fixture.model.prepare();
        fixture.model.detach();
        fixture.provider.snapshot = CompletableFuture.completedFuture(snapshot("new-revision", "other-dom", "sub"));
        fixture.open();
        fixture.model.choose("other-dom", "sub");
        fixture.model.prepare();
        assertEquals(1, fixture.provider.preparations.size());
        assertEquals("dom", fixture.journal.value.domRef);
        assertEquals("revision", fixture.journal.value.revision);
    }

    @Test public void staleCredentialCallbackCannotConfirmAfterChoiceChanges() {
        Fixture fixture = ready();
        fixture.provider.snapshot = CompletableFuture.completedFuture(new RelationshipUi.Snapshot(true, "revision",
                Arrays.asList(participant("dom", RelationshipUi.Role.DOM), participant("other-dom", RelationshipUi.Role.DOM),
                        participant("sub", RelationshipUi.Role.SUB)), Collections.emptyList(), null, Collections.emptyList()));
        fixture.model.refresh(); fixture.model.prepare(); fixture.provider.preparation.complete(review("dom", "sub"));
        RelationshipUi.LocalReview shown = fixture.model.review();
        fixture.model.choose("other-dom", "sub");
        fixture.model.confirm(shown);
        assertNull(fixture.model.review());
        assertTrue(fixture.provider.confirmations.isEmpty());
    }

    @Test public void confirmationAndCancellationDelegateOnlyTheExactStoredOperation() {
        Fixture fixture = ready(); fixture.model.prepare(); fixture.provider.preparation.complete(review("dom", "sub"));
        RelationshipUi.LocalReview shown = fixture.model.review(); fixture.model.confirm(shown);
        assertEquals(Collections.singletonList(Arrays.asList("operation", DIGEST)), fixture.provider.confirmations);
        fixture.provider.result.complete(operation(RelationshipUi.OperationState.PENDING));
        assertEquals(RelationshipUi.OperationState.PENDING, fixture.model.operation().state);
        fixture.model.confirm(shown);
        assertEquals(1, fixture.provider.confirmations.size());
        fixture.provider.result = new CompletableFuture<>(); fixture.model.cancel();
        assertEquals(Collections.singletonList("operation"), fixture.provider.cancellations);
        fixture.provider.result.complete(new RelationshipUi.Operation("operation", DIGEST,
                RelationshipUi.OperationState.CANCELLED, 5000, null, null, RelationshipUi.Reason.CANCELLED));
        assertNull(fixture.journal.value);
        assertEquals(RelationshipUi.OperationState.CANCELLED, fixture.model.operation().state);
    }

    @Test public void persistenceFailureAndMismatchedReviewNeverReachConfirmation() {
        Fixture failed = ready(); failed.journal.writable = false; failed.model.prepare();
        assertTrue(failed.provider.preparations.isEmpty());
        assertEquals(RelationshipPresentation.Mode.UNAVAILABLE, failed.model.mode());
        Fixture mismatch = ready(); mismatch.model.prepare();
        mismatch.provider.preparation.complete(review("wrong-dom", "sub"));
        assertEquals(RelationshipPresentation.Mode.UNAVAILABLE, mismatch.model.mode());
        assertTrue(mismatch.journal.value.unresolved());
        assertTrue(mismatch.provider.confirmations.isEmpty());
    }

    @Test public void wrongOperationResultDoesNotReplaceStoredOperation() {
        Fixture fixture = ready(); fixture.model.prepare(); fixture.provider.preparation.complete(review("dom", "sub"));
        fixture.model.resume();
        fixture.provider.result.complete(new RelationshipUi.Operation("different", DIGEST,
                RelationshipUi.OperationState.PENDING, 5000, null, null, null));
        assertEquals(RelationshipPresentation.Mode.UNAVAILABLE, fixture.model.mode());
        assertEquals("operation", fixture.journal.value.operationId);
    }

    @Test public void staleResumeAndCancelCannotTakeOverANewerStoredOperation() {
        Fixture fixture = ready();
        fixture.model.prepare(); fixture.provider.preparation.complete(review("dom", "sub"));
        RelationshipPresentation.Saved shown = fixture.model.saved();
        fixture.journal.value = RelationshipPresentation.Saved.operation("new-operation", DIGEST);
        fixture.model.resume(shown); fixture.model.cancel(shown);
        assertTrue(fixture.provider.resumes.isEmpty());
        assertTrue(fixture.provider.cancellations.isEmpty());
        assertEquals("new-operation", fixture.journal.value.operationId);
    }

    @Test public void currentPendingSummaryResumesOnlyAfterExplicitSelectionWithoutPreparing() {
        Fixture fixture = new Fixture();
        RelationshipUi.Operation summary = operation(RelationshipUi.OperationState.PENDING);
        fixture.provider.snapshot.complete(new RelationshipUi.Snapshot(true, "revision",
                Arrays.asList(participant("dom", RelationshipUi.Role.DOM), participant("sub", RelationshipUi.Role.SUB)),
                Collections.emptyList(), null, Collections.singletonList(summary)));
        fixture.open();
        assertFalse(fixture.model.canPrepare()); fixture.model.prepare();
        assertTrue(fixture.provider.preparations.isEmpty()); assertTrue(fixture.provider.resumes.isEmpty());
        fixture.model.resumeSummary(operation(RelationshipUi.OperationState.PENDING));
        assertNull(fixture.journal.value);
        fixture.model.resumeSummary(summary);
        assertEquals(Collections.singletonList("operation"), fixture.provider.resumes);
        fixture.provider.result.complete(operation(RelationshipUi.OperationState.PREPARED));
        assertNull(fixture.model.review()); assertTrue(fixture.provider.confirmations.isEmpty());
        assertEquals("operation", fixture.journal.value.operationId);
    }

    @Test public void retainedReferencesMustBeAcknowledgedBeforeRetryOrStatusDispatch() {
        Fixture fixture = ready(); fixture.model.prepare(); fixture.model.detach();
        fixture.open(); fixture.journal.writable = false; fixture.model.prepare();
        assertEquals(1, fixture.provider.preparations.size());
        assertTrue(fixture.journal.value.unresolved());
        fixture.journal.writable = true;
        fixture.journal.value = RelationshipPresentation.Saved.operation("operation", DIGEST);
        fixture.open(); fixture.journal.writable = false;
        fixture.model.resume();
        assertTrue(fixture.provider.resumes.isEmpty());
        assertEquals("operation", fixture.journal.value.operationId);
    }

    private static Fixture ready() {
        Fixture fixture = new Fixture(); fixture.provider.snapshot.complete(snapshot("revision", "dom", "sub"));
        fixture.open(); return fixture;
    }
    private static RelationshipUi.Participant participant(String ref, RelationshipUi.Role role) {
        return new RelationshipUi.Participant(ref, role, ProfileData.empty(ref));
    }
    private static RelationshipUi.Snapshot snapshot(String revision, String dom, String sub) {
        return new RelationshipUi.Snapshot(true, revision, Arrays.asList(participant(dom, RelationshipUi.Role.DOM),
                participant(sub, RelationshipUi.Role.SUB)), Collections.emptyList(), null, Collections.emptyList());
    }
    private static RelationshipUi.LocalReview review(String dom, String sub) {
        return new RelationshipUi.LocalReview("operation", DIGEST, dom, sub, DIGEST, 5000, "Allowed features");
    }
    private static RelationshipUi.Operation operation(RelationshipUi.OperationState state) {
        return new RelationshipUi.Operation("operation", DIGEST, state, 5000, null, null, null);
    }
    private static final class MemoryJournal implements RelationshipPresentation.Journal {
        boolean seen, writable = true;
        RelationshipPresentation.Saved value;
        public boolean authoritativeSeen() { return seen; }
        public boolean markAuthoritativeSeen() { if (!writable) return false; seen = true; return true; }
        public RelationshipPresentation.Saved read() { return value; }
        public boolean replace(RelationshipPresentation.Saved expected, RelationshipPresentation.Saved next) {
            if (!writable || !Objects.equals(expected, value)) return false;
            value = next; return true;
        }
    }
    private static final class Fixture {
        final MemoryJournal journal = new MemoryJournal();
        final Provider provider = new Provider();
        RelationshipUi.Provider source = provider;
        String context = "both/dom/unlocked";
        RelationshipPresentation model;
        void open() {
            model = new RelationshipPresentation(() -> source, journal, Runnable::run, () -> context,
                    () -> context != null && context.equals("both/dom/unlocked"), ignored -> { });
            model.refresh();
        }
    }
    private static final class Provider implements RelationshipUi.Provider {
        CompletableFuture<RelationshipUi.Snapshot> snapshot = new CompletableFuture<>();
        CompletableFuture<RelationshipUi.LocalReview> preparation = new CompletableFuture<>();
        CompletableFuture<RelationshipUi.Operation> result = new CompletableFuture<>();
        final List<List<String>> preparations = new ArrayList<>(), confirmations = new ArrayList<>();
        final List<String> resumes = new ArrayList<>(), cancellations = new ArrayList<>();
        public CompletableFuture<RelationshipUi.Snapshot> snapshot() { return snapshot; }
        public CompletableFuture<RelationshipUi.LocalReview> prepareLocal(String key, String dom, String sub, String revision) {
            preparations.add(Arrays.asList(key, dom, sub, revision)); return preparation;
        }
        public CompletableFuture<RelationshipUi.Operation> confirmLocal(String id, String digest) {
            confirmations.add(Arrays.asList(id, digest)); return result;
        }
        public CompletableFuture<RelationshipUi.Operation> resume(String id) { resumes.add(id); return result; }
        public CompletableFuture<RelationshipUi.Operation> cancel(String id) { cancellations.add(id); return result; }
    }
}
