package com.subhub.app.remote;

import com.subhub.app.relationships.RelationshipRecoveryUi;
import com.subhub.app.relationships.RelationshipUi;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;

/** Inert application boundary. It has no installation hook, readiness toggle or internal-engine constructor. */
final class FormalRelationshipProvider implements RelationshipRecoveryUi.Provider {
    private static RelationshipUi.Snapshot unsupported() {
        return new RelationshipUi.Snapshot(false, null, Collections.emptyList(), Collections.emptyList(), null, Collections.emptyList());
    }
    private static <T> CompletableFuture<T> unavailable() {
        CompletableFuture<T> result = new CompletableFuture<>();
        result.completeExceptionally(new RelationshipUi.Failure(RelationshipUi.Reason.UNAVAILABLE)); return result;
    }
    @Override public CompletableFuture<RelationshipUi.Snapshot> snapshot() { return CompletableFuture.completedFuture(unsupported()); }
    @Override public CompletableFuture<RelationshipRecoveryUi.RecoverySnapshot> recoverySnapshot() {
        return CompletableFuture.completedFuture(new RelationshipRecoveryUi.RecoverySnapshot(unsupported(), Collections.emptyList()));
    }
    @Override public CompletableFuture<RelationshipUi.LocalReview> prepareLocal(String key, String dom, String sub, String revision) { return unavailable(); }
    @Override public CompletableFuture<RelationshipUi.Operation> confirmLocal(String id, String digest) { return unavailable(); }
    @Override public CompletableFuture<RelationshipUi.Operation> resume(String id) { return unavailable(); }
    @Override public CompletableFuture<RelationshipUi.Operation> cancel(String id) { return unavailable(); }
    @Override public CompletableFuture<RelationshipRecoveryUi.ReviewResult> reviewLocal(String id, String digest) {
        return CompletableFuture.completedFuture(new RelationshipRecoveryUi.ReviewResult(RelationshipRecoveryUi.ReviewResult.Kind.UNAVAILABLE, null, null));
    }
    @Override public CompletableFuture<RelationshipRecoveryUi.PreparationLookup> lookupPreparation(String key, String dom, String sub, String revision) {
        return CompletableFuture.completedFuture(new RelationshipRecoveryUi.PreparationLookup(RelationshipRecoveryUi.PreparationLookup.Kind.UNAVAILABLE, null));
    }
}
