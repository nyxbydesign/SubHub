package com.subhub.app.remote;

import static org.junit.Assert.*;
import com.subhub.app.relationships.RelationshipUi;
import org.junit.Test;

public final class FormalRelationshipContractTest {
    @Test public void originalClockCannotRenewAcrossBootOrWallChanges() {
        FormalRelationshipClock.Sample start = new FormalRelationshipClock.Sample(1000,100,2);
        assertTrue(FormalRelationshipClock.live(start,new FormalRelationshipClock.Sample(120999,120099,2)));
        assertTrue(FormalRelationshipClock.expired(start,new FormalRelationshipClock.Sample(121000,120100,2)));
        assertFalse(FormalRelationshipClock.expired(start,new FormalRelationshipClock.Sample(121000,120100,3)));
        assertFalse(FormalRelationshipClock.live(start,new FormalRelationshipClock.Sample(1000,120100,2)));
    }
    @Test public void terminalRetentionRequiresOriginalDurableAgeAndCertainClock() {
        long age = FormalRelationshipClock.RETENTION_MILLIS;
        FormalRelationshipClock.Sample terminal = new FormalRelationshipClock.Sample(1000,100,2);
        assertFalse(FormalRelationshipClock.oldTerminal(terminal,new FormalRelationshipClock.Sample(1000+age-1,100+age-1,2)));
        assertTrue(FormalRelationshipClock.oldTerminal(terminal,new FormalRelationshipClock.Sample(1000+age,100+age,2)));
        assertFalse(FormalRelationshipClock.oldTerminal(terminal,new FormalRelationshipClock.Sample(1000+age,100+age,3)));
        assertFalse(FormalRelationshipClock.oldTerminal(terminal,new FormalRelationshipClock.Sample(1000+age,100,2)));
    }
    @Test public void clockComparisonDoesNotOverflowAtLargeAnchors() {
        assertTrue(FormalRelationshipClock.consistent(new FormalRelationshipClock.Sample(1,0,1),
                new FormalRelationshipClock.Sample(Long.MAX_VALUE,Long.MAX_VALUE-1,1)));
    }
    @Test public void applicationBoundaryCannotExposeInternalReadinessOrMutate() {
        FormalRelationshipProvider provider = new FormalRelationshipProvider();
        RelationshipUi.Snapshot snapshot = provider.snapshot().join();
        assertFalse(snapshot.supported); assertNull(snapshot.revision); assertTrue(snapshot.operations.isEmpty());
        assertFalse(provider.recoverySnapshot().join().snapshot.supported);
        assertTrue(provider.prepareLocal("k","d","s","r").isCompletedExceptionally());
        assertTrue(provider.confirmLocal("id","digest").isCompletedExceptionally());
        assertTrue(provider.cancel("id").isCompletedExceptionally());
        assertTrue(provider.resume("id").isCompletedExceptionally());
        assertEquals(com.subhub.app.relationships.RelationshipRecoveryUi.ReviewResult.Kind.UNAVAILABLE,provider.reviewLocal("id","digest").join().kind);
        assertEquals(com.subhub.app.relationships.RelationshipRecoveryUi.PreparationLookup.Kind.UNAVAILABLE,provider.lookupPreparation("k","d","s","r").join().kind);
    }
}
