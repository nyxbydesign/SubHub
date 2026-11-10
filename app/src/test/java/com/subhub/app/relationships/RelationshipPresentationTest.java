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

    @Test public void passiveObservationPreservesAnUnchangedReviewWithoutJournalWritesOrActions() {
        Fixture fixture = ready(); fixture.model.prepare();
        fixture.provider.preparation.complete(review("dom", "sub"));
        RelationshipUi.LocalReview reviewed = fixture.model.review();
        long epoch = fixture.model.reviewEpoch(); int marks = fixture.journal.marks;
        fixture.model.observe(() -> { });
        assertSame(reviewed, fixture.model.review()); assertEquals(epoch, fixture.model.reviewEpoch());
        assertEquals(marks, fixture.journal.marks);
        assertTrue(fixture.provider.resumes.isEmpty()); assertTrue(fixture.provider.confirmations.isEmpty());
        assertTrue(fixture.provider.cancellations.isEmpty()); assertEquals(1, fixture.provider.preparations.size());
    }

    @Test public void passiveObservationInvalidatesChangedReviewsAndRecoversTheSameProvider() {
        Fixture fixture = ready(); fixture.model.prepare();
        fixture.provider.preparation.complete(review("dom", "sub"));
        RelationshipUi.LocalReview old = fixture.model.review(); long epoch = fixture.model.reviewEpoch();
        fixture.provider.snapshot = CompletableFuture.completedFuture(snapshot("changed", "dom", "sub"));
        fixture.model.observe(() -> { });
        assertEquals("changed", fixture.model.snapshot().revision); assertNull(fixture.model.review());
        fixture.model.confirm(old, epoch); assertTrue(fixture.provider.confirmations.isEmpty());
        fixture.provider.snapshot = new CompletableFuture<>();
        fixture.model.observe(() -> { });
        fixture.provider.snapshot.completeExceptionally(new IllegalStateException("Unavailable"));
        assertEquals(RelationshipPresentation.Mode.UNAVAILABLE, fixture.model.mode());
        fixture.provider.snapshot = CompletableFuture.completedFuture(snapshot("recovered", "dom", "sub"));
        fixture.model.observe(() -> { });
        assertEquals(RelationshipPresentation.Mode.READY, fixture.model.mode());
        assertEquals("recovered", fixture.model.snapshot().revision);
        assertTrue(fixture.provider.resumes.isEmpty()); assertTrue(fixture.provider.confirmations.isEmpty());
        assertTrue(fixture.provider.cancellations.isEmpty()); assertEquals(1, fixture.provider.preparations.size());
    }

    @Test public void passiveObservationCoalescesAndCannotOverwriteAnInFlightActionOrDetachedContext() {
        Fixture fixture = ready();
        CompletableFuture<RelationshipUi.Snapshot> delayed = new CompletableFuture<>();
        fixture.provider.snapshot = delayed;
        int reads = fixture.provider.reads;
        fixture.model.observe(() -> { }); fixture.model.observe(() -> { });
        assertEquals(reads + 1, fixture.provider.reads);
        fixture.model.prepare(); fixture.model.observe(() -> { });
        assertEquals(reads + 1, fixture.provider.reads);
        delayed.complete(snapshot("stale", "dom", "sub"));
        assertTrue(fixture.model.busy());
        fixture.provider.preparation.complete(review("dom", "sub"));
        assertNotNull(fixture.model.review());
        fixture.provider.snapshot = new CompletableFuture<>();
        fixture.model.observe(() -> { }); fixture.model.detach();
        fixture.provider.snapshot.complete(snapshot("detached", "dom", "sub"));
        assertNull(fixture.model.snapshot()); assertNull(fixture.model.review());
        assertTrue(fixture.provider.confirmations.isEmpty());
    }

    @Test public void passiveRecoveryKeepsOriginalReferencesAndNeverObtainsOrConfirmsReview() {
        RecoveryFixture fixture = new RecoveryFixture(); fixture.open();
        RelationshipPresentation.Saved original = RelationshipPresentation.Saved.preparing("retained-key", "dom", "sub", "original-revision");
        fixture.journal.value = original;
        fixture.provider.projection = CompletableFuture.completedFuture(recoverySnapshot(REVISION + "-changed"));
        fixture.model.observe(() -> { });
        assertEquals(original, fixture.journal.value);
        assertEquals(RelationshipPresentation.RecoveryState.UNKNOWN, fixture.model.recoveryState());
        assertTrue(fixture.provider.lookups.isEmpty()); assertTrue(fixture.provider.reviews.isEmpty());
        assertTrue(fixture.provider.preparations.isEmpty()); assertTrue(fixture.provider.confirmations.isEmpty());
        assertTrue(fixture.provider.resumes.isEmpty()); assertTrue(fixture.provider.unlinks.isEmpty());
    }

    @Test public void passiveObservationRejectsChangedTypedOperationScopeAndDeadline() {
        RecoveryFixture fixture = new RecoveryFixture();
        fixture.journal.value = RelationshipPresentation.Saved.operation("operation", DIGEST);
        fixture.provider.projection = CompletableFuture.completedFuture(recoverySnapshot(REVISION,
                localSummary(operation(RelationshipUi.OperationState.PREPARED))));
        fixture.open();
        fixture.provider.projection = CompletableFuture.completedFuture(recoverySnapshot(REVISION + "-changed",
                localSummary(new RelationshipUi.Operation("operation", DIGEST,
                        RelationshipUi.OperationState.PENDING, 6000, null, null, null))));
        fixture.model.observe(() -> { });
        assertEquals(RelationshipPresentation.Mode.UNAVAILABLE, fixture.model.mode());
        assertEquals("operation", fixture.journal.value.operationId);
        assertTrue(fixture.provider.reviews.isEmpty()); assertTrue(fixture.provider.confirmations.isEmpty());
    }

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

    @Test public void recoveryLostResponseLooksUpOriginalKeyAndKeepsUnknownAcrossRecreation() {
        RecoveryFixture fixture = new RecoveryFixture(); fixture.open();
        fixture.provider.onPrepare = () -> assertNotNull(fixture.journal.value);
        fixture.model.prepare();
        RelationshipPresentation.Saved original = fixture.journal.value;
        List<String> operands = fixture.provider.preparations.get(0);
        assertTrue(original.operationKey.matches("fr1\\.0123456789abcdef0123456789abcdef\\.7\\.[0-9a-f]{32}"));
        assertTrue(original.operationKey.length() <= 89);
        fixture.model.detach(); fixture.provider.preparation.complete(review("dom", "sub"));
        assertEquals(original, fixture.journal.value);
        fixture.provider.projection = CompletableFuture.completedFuture(recoverySnapshot(REVISION.replace(":7", ":8")));
        fixture.open();
        assertEquals(Collections.singletonList(operands), fixture.provider.lookups);
        assertEquals(1, fixture.provider.preparations.size());
        fixture.provider.lookup.complete(lookup(RelationshipRecoveryUi.PreparationLookup.Kind.UNKNOWN));
        assertEquals(RelationshipPresentation.RecoveryState.UNKNOWN, fixture.model.recoveryState());
        assertEquals(original, fixture.journal.value); assertFalse(fixture.model.canPrepare());
        fixture.provider.lookup = new CompletableFuture<>(); fixture.model.prepare();
        assertEquals(operands, fixture.provider.lookups.get(1));
        fixture.provider.lookup.complete(lookup(RelationshipRecoveryUi.PreparationLookup.Kind.UNAVAILABLE));
        assertEquals(RelationshipPresentation.RecoveryState.UNAVAILABLE, fixture.model.recoveryState());
        assertEquals(original, fixture.journal.value); assertEquals(1, fixture.provider.preparations.size());
        assertEquals(0, fixture.provider.baseSnapshots); assertTrue(fixture.provider.confirmations.isEmpty());
    }

    @Test public void foundOriginalGetsFreshReviewAndRecreationRequiresAnotherReviewAndExplicitConfirm() {
        RecoveryFixture fixture = new RecoveryFixture();
        fixture.journal.value = RelationshipPresentation.Saved.preparing("retained-legacy-key", "dom", "sub", "original-revision");
        RelationshipRecoveryUi.OperationSummary original = localSummary(operation(RelationshipUi.OperationState.PREPARED));
        fixture.provider.lookup = CompletableFuture.completedFuture(new RelationshipRecoveryUi.PreparationLookup(
                RelationshipRecoveryUi.PreparationLookup.Kind.FOUND, original));
        fixture.open();
        assertEquals(Collections.singletonList(Arrays.asList("operation", DIGEST)), fixture.provider.reviews);
        assertEquals("operation", fixture.journal.value.operationId); assertNull(fixture.model.review());
        fixture.provider.localReview.complete(reviewResult(original));
        assertEquals("Original Dom", fixture.model.summary().domLabel);
        assertEquals("Original Sub", fixture.model.summary().subLabel);
        assertSame(original, fixture.model.summary());
        RelationshipUi.LocalReview old = fixture.model.review(); long oldEpoch = fixture.model.reviewEpoch();
        assertEquals(5000, old.originalDeadline); assertTrue(fixture.model.canConfirm());
        fixture.model.detach(); fixture.provider.localReview = new CompletableFuture<>(); fixture.open();
        assertNull(fixture.model.review()); assertEquals(1, fixture.provider.reviews.size());
        fixture.model.confirm(old, oldEpoch); assertTrue(fixture.provider.confirmations.isEmpty());
        fixture.model.resume();
        assertEquals(2, fixture.provider.reviews.size());
        fixture.provider.localReview.complete(reviewResult(original));
        assertEquals(old.scopeDigest, fixture.model.review().scopeDigest);
        assertEquals(old.originalDeadline, fixture.model.review().originalDeadline);
        assertTrue(fixture.provider.preparations.isEmpty()); assertTrue(fixture.provider.confirmations.isEmpty());
        fixture.model.confirm(fixture.model.review(), fixture.model.reviewEpoch());
        assertEquals(Collections.singletonList(Arrays.asList("operation", DIGEST)), fixture.provider.confirmations);
        fixture.provider.result.complete(operation(RelationshipUi.OperationState.PENDING));
        assertEquals(RelationshipUi.OperationState.PENDING, fixture.model.operation().state);
        assertEquals("operation", fixture.journal.value.operationId);
    }

    @Test public void retainedMissingOperationAndTypedQueryFailuresNeverInventOrClearAnIntent() {
        for (RelationshipRecoveryUi.ReviewResult.Kind kind : Arrays.asList(RelationshipRecoveryUi.ReviewResult.Kind.NOT_FOUND,
                RelationshipRecoveryUi.ReviewResult.Kind.UNKNOWN, RelationshipRecoveryUi.ReviewResult.Kind.UNAVAILABLE,
                RelationshipRecoveryUi.ReviewResult.Kind.REFUSED)) {
            RecoveryFixture fixture = new RecoveryFixture();
            RelationshipPresentation.Saved saved = RelationshipPresentation.Saved.operation("operation", DIGEST);
            fixture.journal.value = saved; fixture.open();
            assertEquals(RelationshipPresentation.RecoveryState.UNKNOWN, fixture.model.recoveryState());
            assertEquals(saved, fixture.journal.value); assertTrue(fixture.provider.reviews.isEmpty());
            fixture.model.resume();
            fixture.provider.localReview.complete(new RelationshipRecoveryUi.ReviewResult(kind, null, null));
            assertEquals(kind.name(), saved, fixture.journal.value); assertNull(fixture.model.review());
            assertFalse(fixture.model.canPrepare()); assertTrue(fixture.provider.preparations.isEmpty());
            assertTrue(fixture.provider.confirmations.isEmpty());
        }
        RecoveryFixture terminal = new RecoveryFixture();
        terminal.journal.value = RelationshipPresentation.Saved.operation("operation", DIGEST); terminal.open(); terminal.model.resume();
        RelationshipUi.Operation active = new RelationshipUi.Operation("operation", DIGEST,
                RelationshipUi.OperationState.ACTIVE, 5000, "relationship", "generation", null);
        RelationshipRecoveryUi.RecoverySnapshot current = linkedSnapshot(localEdge("generation"), localSummary(active));
        terminal.provider.projection = CompletableFuture.completedFuture(current);
        terminal.provider.localReview.complete(new RelationshipRecoveryUi.ReviewResult(RelationshipRecoveryUi.ReviewResult.Kind.STATUS,
                localSummary(active), null));
        assertNull(terminal.journal.value); assertSame(current.snapshot, terminal.model.snapshot());
        assertTrue(terminal.provider.confirmations.isEmpty());
    }

    @Test public void activeConfirmationRefreshesAuthoritativeRosterWithoutRepeatingMutation() {
        RecoveryFixture fixture = new RecoveryFixture(); fixture.open(); fixture.model.prepare();
        fixture.provider.preparation.complete(review("dom", "sub"));
        RelationshipUi.LocalReview shown = fixture.model.review(); long shownEpoch = fixture.model.reviewEpoch();
        fixture.provider.projection = new CompletableFuture<>();
        fixture.model.confirm(shown, shownEpoch);
        RelationshipUi.Operation active = new RelationshipUi.Operation("operation", DIGEST,
                RelationshipUi.OperationState.ACTIVE, 5000, "relationship", "generation", null);
        fixture.provider.result.complete(active);
        assertNull(fixture.journal.value); assertEquals(RelationshipPresentation.Mode.LOADING, fixture.model.mode());
        assertEquals(2, fixture.provider.projectionReads); assertEquals(1, fixture.model.rosterChanges());
        RelationshipUi.Edge edge = localEdge("generation");
        RelationshipRecoveryUi.RecoverySnapshot current = linkedSnapshot(edge, localSummary(active));
        fixture.provider.projection.complete(current);
        assertSame(current.snapshot, fixture.model.snapshot()); assertTrue(fixture.model.canUnlink(edge));
        assertEquals(Collections.singletonList(PersonId.LOCAL), RelationshipPresentation.rosterIds(fixture.model.snapshot()));
        fixture.model.confirm(shown, shownEpoch);
        assertEquals(Collections.singletonList(Arrays.asList("operation", DIGEST)), fixture.provider.confirmations);
        assertEquals(1, fixture.provider.preparations.size()); assertTrue(fixture.provider.resumes.isEmpty());
        assertTrue(fixture.provider.reviews.isEmpty());
    }

    @Test public void recreatedKnownActiveClearsAcknowledgedJournalAndKeepsAuthoritativeRosterWithoutDispatch() {
        RelationshipUi.Operation active = new RelationshipUi.Operation("operation", DIGEST,
                RelationshipUi.OperationState.ACTIVE, 5000, "relationship", "generation", null);
        RelationshipUi.Edge edge = localEdge("generation");
        RelationshipRecoveryUi.RecoverySnapshot current = linkedSnapshot(edge, localSummary(active));
        RelationshipPresentation.Saved saved = RelationshipPresentation.Saved.operation("operation", DIGEST);
        RecoveryFixture fixture = new RecoveryFixture(); fixture.journal.value = saved;
        fixture.provider.projection = CompletableFuture.completedFuture(current); fixture.open();
        assertNull(fixture.journal.value); assertSame(current.snapshot, fixture.model.snapshot());
        assertEquals(1, fixture.provider.projectionReads); assertEquals(1, fixture.model.rosterChanges());
        assertEquals(Collections.singletonList(PersonId.LOCAL), RelationshipPresentation.rosterIds(fixture.model.snapshot()));
        assertTrue(fixture.model.canUnlink(edge)); assertFalse(fixture.model.canPrepare());
        assertTrue(fixture.provider.preparations.isEmpty()); assertTrue(fixture.provider.confirmations.isEmpty());
        assertTrue(fixture.provider.reviews.isEmpty()); assertTrue(fixture.provider.resumes.isEmpty());
        fixture.provider.projection = CompletableFuture.completedFuture(recoverySnapshot(REVISION.replace(":7", ":8")));
        fixture.model.unlink(edge, fixture.model.reviewEpoch()); fixture.provider.unlinkResult.complete(null);
        assertTrue(fixture.model.canPrepare()); assertNull(fixture.journal.value);

        RecoveryFixture failed = new RecoveryFixture(); failed.journal.value = saved;
        failed.provider.projection = new CompletableFuture<>(); failed.open(); failed.journal.writable = false;
        failed.provider.projection.complete(current);
        assertEquals(saved, failed.journal.value); assertEquals(RelationshipPresentation.Mode.UNAVAILABLE, failed.model.mode());
        assertEquals(0, failed.model.rosterChanges()); assertTrue(failed.provider.confirmations.isEmpty());
        assertTrue(failed.provider.preparations.isEmpty());
        RecoveryFixture unavailable = new RecoveryFixture(); unavailable.journal.value = saved;
        RelationshipUi.Operation uncertain = new RelationshipUi.Operation("operation", DIGEST,
                RelationshipUi.OperationState.UNAVAILABLE, 5000, null, null, RelationshipUi.Reason.UNAVAILABLE);
        unavailable.provider.projection = CompletableFuture.completedFuture(linkedSnapshot(edge, localSummary(uncertain)));
        unavailable.open(); assertEquals(saved, unavailable.journal.value);
        assertEquals(0, unavailable.model.rosterChanges()); assertTrue(unavailable.provider.preparations.isEmpty());
        assertTrue(unavailable.provider.confirmations.isEmpty());
        for (String change : Arrays.asList("detach", "provider", "authority")) {
            RecoveryFixture stale = new RecoveryFixture(); stale.journal.value = saved;
            stale.provider.projection = new CompletableFuture<>(); stale.open();
            if (change.equals("detach")) stale.model.detach();
            if (change.equals("provider")) stale.source = RelationshipUi.unavailable();
            if (change.equals("authority")) stale.context = "both/dom/changed-authority";
            stale.provider.projection.complete(current);
            assertEquals(change, saved, stale.journal.value); assertEquals(change, 0, stale.model.rosterChanges());
        }
    }

    @Test public void foundOrReviewedScopeMismatchKeepsTheOriginalReferenceAndRefusesConfirm() {
        RecoveryFixture lookup = new RecoveryFixture();
        RelationshipPresentation.Saved key = RelationshipPresentation.Saved.preparing("old-key", "dom", "sub", "old-revision");
        lookup.journal.value = key; lookup.open();
        lookup.provider.lookup.complete(new RelationshipRecoveryUi.PreparationLookup(RelationshipRecoveryUi.PreparationLookup.Kind.FOUND,
                new RelationshipRecoveryUi.OperationSummary(operation(RelationshipUi.OperationState.PREPARED),
                        RelationshipRecoveryUi.OperationKind.LOCAL_LINK, RelationshipUi.Delivery.LOCAL,
                        "other-dom", "sub", "Wrong Dom", "Original Sub")));
        assertEquals(key, lookup.journal.value); assertEquals(RelationshipPresentation.RecoveryState.REFUSED, lookup.model.recoveryState());
        assertTrue(lookup.provider.reviews.isEmpty());
        for (String change : Arrays.asList("digest", "deadline", "participants", "scope")) {
            RecoveryFixture fixture = new RecoveryFixture(); fixture.open(); fixture.model.prepare();
            fixture.provider.preparation.complete(review("dom", "sub"));
            RelationshipPresentation.Saved saved = fixture.journal.value;
            fixture.model.resume();
            String digest = change.equals("digest") ? String.join("", Collections.nCopies(64, "a")) : DIGEST;
            long deadline = change.equals("deadline") ? 6000 : 5000;
            String dom = change.equals("participants") ? "other-dom" : "dom";
            RelationshipUi.Operation operation = new RelationshipUi.Operation("operation", digest,
                    RelationshipUi.OperationState.PREPARED, deadline, null, null, null);
            RelationshipRecoveryUi.OperationSummary summary = new RelationshipRecoveryUi.OperationSummary(operation,
                    RelationshipRecoveryUi.OperationKind.LOCAL_LINK, RelationshipUi.Delivery.LOCAL, dom, "sub", "Dom", "Sub");
            RelationshipUi.LocalReview review = new RelationshipUi.LocalReview("operation", digest, dom, "sub",
                    change.equals("scope") ? String.join("", Collections.nCopies(64, "b")) : DIGEST, deadline, "Allowed features");
            fixture.provider.localReview.complete(new RelationshipRecoveryUi.ReviewResult(RelationshipRecoveryUi.ReviewResult.Kind.REVIEW, summary, review));
            assertEquals(change, saved, fixture.journal.value); assertNull(change, fixture.model.review());
            assertEquals(change, RelationshipPresentation.RecoveryState.REFUSED, fixture.model.recoveryState());
            fixture.model.confirm(review); assertTrue(change, fixture.provider.confirmations.isEmpty());
        }
    }

    @Test public void recoveryCallbacksAndCredentialEpochAreFencedByDetachRoleLockProviderAndAuthority() {
        for (String phase : Arrays.asList("lookup", "review")) for (String change : Arrays.asList("detach", "role", "lock", "provider", "authority")) {
            RecoveryFixture fixture = new RecoveryFixture();
            RelationshipPresentation.Saved saved = phase.equals("lookup")
                    ? RelationshipPresentation.Saved.preparing("old-key", "dom", "sub", "old-revision")
                    : RelationshipPresentation.Saved.operation("operation", DIGEST);
            fixture.journal.value = saved; fixture.open();
            if (phase.equals("review")) fixture.model.resume();
            if (change.equals("detach")) fixture.model.detach();
            if (change.equals("role")) fixture.context = "sub-only";
            if (change.equals("lock")) fixture.context = null;
            if (change.equals("provider")) fixture.source = RelationshipUi.unavailable();
            if (change.equals("authority")) fixture.context = "both/dom/changed-authority";
            RelationshipRecoveryUi.OperationSummary original = localSummary(operation(RelationshipUi.OperationState.PREPARED));
            if (phase.equals("lookup")) fixture.provider.lookup.complete(new RelationshipRecoveryUi.PreparationLookup(
                    RelationshipRecoveryUi.PreparationLookup.Kind.FOUND, original));
            else fixture.provider.localReview.complete(reviewResult(original));
            assertEquals(phase + change, saved, fixture.journal.value); assertNull(fixture.model.review());
            assertTrue(fixture.provider.confirmations.isEmpty()); assertTrue(fixture.provider.preparations.isEmpty());
        }
        RecoveryFixture fixture = new RecoveryFixture(); fixture.open(); fixture.model.prepare();
        fixture.provider.preparation.complete(review("dom", "sub"));
        RelationshipUi.LocalReview original = fixture.model.review(); long epoch = fixture.model.reviewEpoch();
        fixture.model.refresh(); fixture.model.resume();
        // Even an adapter returning the same immutable review instance cannot revive an old credential callback.
        fixture.provider.localReview.complete(new RelationshipRecoveryUi.ReviewResult(RelationshipRecoveryUi.ReviewResult.Kind.REVIEW,
                localSummary(operation(RelationshipUi.OperationState.PREPARED)), original));
        fixture.model.confirm(original, epoch); assertTrue(fixture.provider.confirmations.isEmpty());
        fixture.model.confirm(original, fixture.model.reviewEpoch()); assertEquals(1, fixture.provider.confirmations.size());
    }

    @Test public void multipleTypedSummariesSelectExactKindLabelsAndIdentityWithoutPreparation() {
        RecoveryFixture fixture = new RecoveryFixture();
        RelationshipRecoveryUi.OperationSummary local = localSummary(operation(RelationshipUi.OperationState.PREPARED));
        RelationshipUi.Operation peerOperation = new RelationshipUi.Operation("peer-operation", DIGEST,
                RelationshipUi.OperationState.PREPARED, 5000, null, null, null);
        RelationshipRecoveryUi.OperationSummary peer = new RelationshipRecoveryUi.OperationSummary(peerOperation,
                RelationshipRecoveryUi.OperationKind.PEER_INVITE_CREATE, RelationshipUi.Delivery.PEER, "dom", null, "Invitation Dom", null);
        fixture.provider.projection = CompletableFuture.completedFuture(recoverySnapshot(REVISION, local, peer));
        fixture.open(); fixture.model.prepare(); assertFalse(fixture.model.canPrepare());
        fixture.model.resumeSummary(localSummary(operation(RelationshipUi.OperationState.PREPARED)));
        assertNull(fixture.journal.value); assertTrue(fixture.provider.reviews.isEmpty());
        fixture.model.resumeSummary(peer);
        assertEquals(Collections.singletonList("peer-operation"), fixture.provider.resumes);
        fixture.provider.result.complete(peerOperation);
        assertSame(peer, fixture.model.summary()); assertEquals("Invitation Dom", fixture.model.summary().domLabel);
        assertEquals(RelationshipRecoveryUi.OperationKind.PEER_INVITE_CREATE, fixture.model.summary().kind);
        assertEquals("peer-operation", fixture.journal.value.operationId);
        assertNull(fixture.model.review()); assertTrue(fixture.provider.reviews.isEmpty());
        assertTrue(fixture.provider.preparations.isEmpty()); assertTrue(fixture.provider.confirmations.isEmpty());
    }

    @Test public void crashBeforeFirstSubmissionNeedsProvedAbsenceThenExplicitSameKeyRetry() {
        RecoveryFixture fixture = new RecoveryFixture();
        RelationshipPresentation.Saved beforeCrash = RelationshipPresentation.Saved.preparing(
                RelationshipPreparationKey.create(REVISION), "dom", "sub", REVISION);
        fixture.journal.value = beforeCrash; fixture.open();
        List<String> original = Arrays.asList(beforeCrash.operationKey, "dom", "sub", REVISION);
        assertEquals(Collections.singletonList(original), fixture.provider.lookups);
        // Synthetic proof DTO qualifies UI consumption only, not the storage proof that emits it.
        fixture.provider.lookup.complete(lookup(RelationshipRecoveryUi.PreparationLookup.Kind.ABSENT_NEVER_CREATED));
        assertTrue(fixture.provider.preparations.isEmpty()); assertTrue(fixture.model.canRetryPreparation());
        fixture.model.detach(); fixture.open();
        assertTrue(fixture.provider.preparations.isEmpty()); assertEquals(beforeCrash, fixture.journal.value);
        fixture.provider.preparation = new CompletableFuture<>(); fixture.model.prepare();
        assertEquals(Collections.singletonList(original), fixture.provider.preparations);
        assertEquals(beforeCrash, fixture.journal.value);
        RecoveryFixture legacy = new RecoveryFixture();
        RelationshipPresentation.Saved saved = RelationshipPresentation.Saved.preparing(
                "550e8400-e29b-41d4-a716-446655440000", "dom", "sub", "legacy-revision");
        legacy.journal.value = saved; legacy.open();
        legacy.provider.lookup.complete(lookup(RelationshipRecoveryUi.PreparationLookup.Kind.ABSENT_NEVER_CREATED));
        legacy.model.prepare(); assertFalse(legacy.model.canRetryPreparation());
        assertEquals(saved, legacy.journal.value); assertTrue(legacy.provider.preparations.isEmpty());
        assertEquals(2, legacy.provider.lookups.size());
    }

    @Test public void recoveryReadAndJournalFailuresNeverFallBackOrDispatchUnacknowledgedWork() {
        RecoveryFixture failed = new RecoveryFixture();
        failed.provider.projection = new CompletableFuture<>(); failed.open();
        failed.provider.projection.completeExceptionally(new IllegalStateException("Unavailable"));
        assertEquals(RelationshipPresentation.Mode.UNAVAILABLE, failed.model.mode());
        failed.source = RelationshipUi.unavailable(); failed.open();
        assertEquals(RelationshipPresentation.Mode.UNAVAILABLE, failed.model.mode());
        assertEquals(0, failed.provider.baseSnapshots);
        RecoveryFixture journal = new RecoveryFixture(); journal.open(); journal.journal.writable = false;
        journal.model.prepare(); assertTrue(journal.provider.preparations.isEmpty());
        journal.journal.writable = true;
        journal.journal.value = RelationshipPresentation.Saved.operation("operation", DIGEST); journal.open();
        journal.journal.writable = false; journal.model.resume();
        assertTrue(journal.provider.reviews.isEmpty()); assertEquals("operation", journal.journal.value.operationId);
        RecoveryFixture invalid = new RecoveryFixture();
        invalid.provider.projection = CompletableFuture.completedFuture(recoverySnapshot("not-a-formal-revision"));
        invalid.open(); invalid.model.prepare();
        assertNull(invalid.journal.value); assertTrue(invalid.provider.preparations.isEmpty());
    }

    @Test public void localUnlinkBindsExactCurrentEdgeAndRetainsObservedRosterUntilAuthoritativeSuccess() {
        RelationshipUi.Edge edge = localEdge("generation");
        RecoveryFixture fixture = new RecoveryFixture();
        RelationshipRecoveryUi.RecoverySnapshot before = linkedSnapshot(edge);
        fixture.provider.projection = CompletableFuture.completedFuture(before); fixture.open();
        assertTrue(fixture.model.canUnlink(edge));
        assertFalse(fixture.model.canUnlink(localEdge("generation")));
        fixture.model.unlink(localEdge("changed-generation"), fixture.model.reviewEpoch());
        assertTrue(fixture.provider.unlinks.isEmpty());
        long credentialEpoch = fixture.model.reviewEpoch();
        fixture.model.unlink(edge, credentialEpoch);
        assertEquals(Collections.singletonList(Arrays.asList("relationship", "generation")), fixture.provider.unlinks);
        fixture.provider.unlinkResult.completeExceptionally(new IllegalStateException("Outcome unavailable"));
        assertSame(before.snapshot, fixture.model.snapshot()); assertSame(edge, fixture.model.snapshot().eligibleSubs.get(0));
        assertTrue(fixture.model.unlinkUnavailable()); assertEquals(0, fixture.model.completedUnlinks());
        fixture.model.unlink(edge, credentialEpoch); assertEquals(1, fixture.provider.unlinks.size());
        fixture.provider.unlinkResult = new CompletableFuture<>();
        fixture.model.unlink(edge, fixture.model.reviewEpoch());
        fixture.provider.projection = new CompletableFuture<>(); fixture.provider.unlinkResult.complete(null);
        assertEquals(RelationshipPresentation.Mode.LOADING, fixture.model.mode());
        fixture.provider.projection.complete(recoverySnapshot(REVISION.replace(":7", ":8")));
        assertTrue(fixture.model.snapshot().eligibleSubs.isEmpty()); assertEquals(1, fixture.model.completedUnlinks());
        assertTrue(fixture.provider.preparations.isEmpty()); assertTrue(fixture.provider.confirmations.isEmpty());
        for (String change : Arrays.asList("detach", "role", "lock", "provider")) {
            RecoveryFixture late = new RecoveryFixture(); late.provider.projection = CompletableFuture.completedFuture(before); late.open();
            late.model.unlink(edge, late.model.reviewEpoch());
            if (change.equals("detach")) late.model.detach();
            if (change.equals("role")) late.context = "sub-only";
            if (change.equals("lock")) late.context = null;
            if (change.equals("provider")) late.source = RelationshipUi.unavailable();
            late.provider.unlinkResult.complete(null);
            assertEquals(change, 0, late.model.completedUnlinks());
        }
        RelationshipUi.Edge peer = new RelationshipUi.Edge("peer", "generation", "dom", "sub", RelationshipUi.Delivery.PEER,
                "ACTIVE", 1, new PersonId("paired"), RelationshipUi.Availability.OFFLINE, PersonSnapshot.Freshness.STALE);
        RecoveryFixture remote = new RecoveryFixture(); remote.provider.projection = CompletableFuture.completedFuture(linkedSnapshot(peer)); remote.open();
        assertFalse(remote.model.canUnlink(peer)); remote.model.unlink(peer, remote.model.reviewEpoch());
        assertTrue(remote.provider.unlinks.isEmpty());
    }

    private static RelationshipUi.Edge localEdge(String generation) {
        return new RelationshipUi.Edge("relationship", generation, "dom", "sub", RelationshipUi.Delivery.LOCAL,
                "ACTIVE", 1, PersonId.LOCAL, RelationshipUi.Availability.AVAILABLE, PersonSnapshot.Freshness.CURRENT);
    }
    private static RelationshipRecoveryUi.RecoverySnapshot linkedSnapshot(RelationshipUi.Edge edge,
            RelationshipRecoveryUi.OperationSummary... summaries) {
        List<RelationshipUi.Operation> operations = new ArrayList<>();
        for (RelationshipRecoveryUi.OperationSummary summary : summaries) operations.add(summary.operation);
        return new RelationshipRecoveryUi.RecoverySnapshot(new RelationshipUi.Snapshot(true, REVISION,
                Arrays.asList(participant("dom", RelationshipUi.Role.DOM), participant("sub", RelationshipUi.Role.SUB)),
                Collections.singletonList(edge), edge, operations), Arrays.asList(summaries));
    }

    private static final String REVISION = "fr1:0123456789abcdef0123456789abcdef:7";
    private static RelationshipRecoveryUi.PreparationLookup lookup(RelationshipRecoveryUi.PreparationLookup.Kind kind) {
        return new RelationshipRecoveryUi.PreparationLookup(kind, null);
    }
    private static RelationshipRecoveryUi.OperationSummary localSummary(RelationshipUi.Operation operation) {
        return new RelationshipRecoveryUi.OperationSummary(operation, RelationshipRecoveryUi.OperationKind.LOCAL_LINK,
                RelationshipUi.Delivery.LOCAL, "dom", "sub", "Original Dom", "Original Sub");
    }
    private static RelationshipRecoveryUi.ReviewResult reviewResult(RelationshipRecoveryUi.OperationSummary summary) {
        return new RelationshipRecoveryUi.ReviewResult(RelationshipRecoveryUi.ReviewResult.Kind.REVIEW, summary, review("dom", "sub"));
    }
    private static RelationshipRecoveryUi.RecoverySnapshot recoverySnapshot(String revision, RelationshipRecoveryUi.OperationSummary... summaries) {
        List<RelationshipUi.Operation> operations = new ArrayList<>();
        for (RelationshipRecoveryUi.OperationSummary value : summaries) operations.add(value.operation);
        return new RelationshipRecoveryUi.RecoverySnapshot(new RelationshipUi.Snapshot(true, revision,
                Arrays.asList(participant("dom", RelationshipUi.Role.DOM), participant("sub", RelationshipUi.Role.SUB)),
                Collections.emptyList(), null, operations), Arrays.asList(summaries));
    }

    private static final class RecoveryFixture {
        final MemoryJournal journal = new MemoryJournal();
        final RecoveryProvider provider = new RecoveryProvider();
        RelationshipUi.Provider source = provider;
        String context = "both/dom/unlocked";
        RelationshipPresentation model;
        void open() {
            model = new RelationshipPresentation(() -> source, journal, Runnable::run, () -> context,
                    () -> "both/dom/unlocked".equals(context), ignored -> { }); model.refresh();
        }
    }

    private static final class RecoveryProvider extends Provider implements RelationshipRecoveryUi.Provider {
        CompletableFuture<RelationshipRecoveryUi.RecoverySnapshot> projection = CompletableFuture.completedFuture(RelationshipPresentationTest.recoverySnapshot(REVISION));
        CompletableFuture<RelationshipRecoveryUi.PreparationLookup> lookup = new CompletableFuture<>();
        CompletableFuture<RelationshipRecoveryUi.ReviewResult> localReview = new CompletableFuture<>();
        final List<List<String>> lookups = new ArrayList<>(), reviews = new ArrayList<>();
        final List<List<String>> unlinks = new ArrayList<>();
        CompletableFuture<Void> unlinkResult = new CompletableFuture<>();
        int baseSnapshots;
        int projectionReads;
        Runnable onPrepare;
        @Override public CompletableFuture<RelationshipUi.Snapshot> snapshot() { baseSnapshots++; return super.snapshot(); }
        @Override public CompletableFuture<RelationshipUi.LocalReview> prepareLocal(String key, String dom, String sub, String revision) {
            if (onPrepare != null) onPrepare.run(); return super.prepareLocal(key, dom, sub, revision);
        }
        public CompletableFuture<RelationshipRecoveryUi.RecoverySnapshot> recoverySnapshot() { projectionReads++; return projection; }
        public CompletableFuture<RelationshipRecoveryUi.PreparationLookup> lookupPreparation(String key, String dom, String sub, String revision) {
            lookups.add(Arrays.asList(key, dom, sub, revision)); return lookup;
        }
        public CompletableFuture<RelationshipRecoveryUi.ReviewResult> reviewLocal(String id, String digest) {
            reviews.add(Arrays.asList(id, digest)); return localReview;
        }
        public CompletableFuture<Void> unlink(String id, String generation) {
            unlinks.add(Arrays.asList(id, generation)); return unlinkResult;
        }
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
        int marks;
        RelationshipPresentation.Saved value;
        public boolean authoritativeSeen() { return seen; }
        public boolean markAuthoritativeSeen() { marks++; if (!writable) return false; seen = true; return true; }
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
    private static class Provider implements RelationshipUi.Provider {
        int reads;
        CompletableFuture<RelationshipUi.Snapshot> snapshot = new CompletableFuture<>();
        CompletableFuture<RelationshipUi.LocalReview> preparation = new CompletableFuture<>();
        CompletableFuture<RelationshipUi.Operation> result = new CompletableFuture<>();
        final List<List<String>> preparations = new ArrayList<>(), confirmations = new ArrayList<>();
        final List<String> resumes = new ArrayList<>(), cancellations = new ArrayList<>();
        public CompletableFuture<RelationshipUi.Snapshot> snapshot() { reads++; return snapshot; }
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
