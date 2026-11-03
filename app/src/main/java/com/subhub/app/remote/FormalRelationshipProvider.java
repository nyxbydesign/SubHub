package com.subhub.app.remote;

import android.content.Context;
import com.subhub.app.relationships.RelationshipRecoveryUi;
import com.subhub.app.relationships.RelationshipUi;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Real asynchronous adapter; persistence receipts never substitute for current controller authorization. */
final class FormalRelationshipProvider implements RelationshipRecoveryUi.Provider, AutoCloseable {
    private final RemoteStore store;
    private final LinkingWitness witness;
    private final FormalRelationshipCoordinator coordinator;
    private final ThreadPoolExecutor executor;
    FormalRelationshipProvider(){store=null;witness=null;coordinator=null;executor=null;}
    FormalRelationshipProvider(Context context,RemoteStore store){
        this.store=java.util.Objects.requireNonNull(store);witness=LinkingWitness.attach(context,store);
        coordinator=FormalRelationshipCoordinator.application(context,store,witness);
        executor=new ThreadPoolExecutor(1,1,20,TimeUnit.SECONDS,new java.util.concurrent.ArrayBlockingQueue<>(16),r->new Thread(r,"subhub-linking"),new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
    }
    private static RelationshipUi.Snapshot unsupported(){return new RelationshipUi.Snapshot(false,null,Collections.emptyList(),Collections.emptyList(),null,Collections.emptyList());}
    private static <T>CompletableFuture<T> unavailable(){CompletableFuture<T> f=new CompletableFuture<>();f.completeExceptionally(new RelationshipUi.Failure(RelationshipUi.Reason.UNAVAILABLE));return f;}
    private <T>CompletableFuture<T> submit(java.util.concurrent.Callable<T> work){
        if(executor==null)return unavailable();CompletableFuture<T> result=new CompletableFuture<>();
        try{executor.execute(()->{try{result.complete(work.call());}catch(Throwable failure){result.completeExceptionally(failure instanceof SecurityException?new RelationshipUi.Failure(RelationshipUi.Reason.REFUSED):failure);}});}
        catch(java.util.concurrent.RejectedExecutionException full){result.completeExceptionally(new RelationshipUi.Failure(RelationshipUi.Reason.UNAVAILABLE));}return result;
    }
    @Override public CompletableFuture<RelationshipUi.Snapshot> snapshot(){return executor==null?CompletableFuture.completedFuture(unsupported()):submit(()->coordinator.recoverySnapshot().snapshot);}
    @Override public CompletableFuture<RelationshipRecoveryUi.RecoverySnapshot> recoverySnapshot(){return executor==null?CompletableFuture.completedFuture(new RelationshipRecoveryUi.RecoverySnapshot(unsupported(),Collections.emptyList())):submit(coordinator::recoverySnapshot);}
    @Override public CompletableFuture<RelationshipUi.LocalReview> prepareLocal(String key,String dom,String sub,String revision){return submit(()->coordinator.prepareLocal(key,dom,sub,revision));}
    @Override public CompletableFuture<RelationshipUi.Operation> confirmLocal(String id,String digest){return submit(()->coordinator.confirmLocal(id,digest));}
    @Override public CompletableFuture<RelationshipUi.Operation> resume(String id){return submit(()->coordinator.resume(id));}
    @Override public CompletableFuture<RelationshipUi.Operation> cancel(String id){return submit(()->coordinator.cancel(id));}
    @Override public CompletableFuture<Void> unlink(String id,String generation){return submit(()->{coordinator.unlink(id,generation);return null;});}
    @Override public CompletableFuture<RelationshipRecoveryUi.ReviewResult> reviewLocal(String id,String digest){return executor==null?CompletableFuture.completedFuture(new RelationshipRecoveryUi.ReviewResult(RelationshipRecoveryUi.ReviewResult.Kind.UNAVAILABLE,null,null)):submit(()->coordinator.reviewLocal(id,digest));}
    @Override public CompletableFuture<RelationshipRecoveryUi.PreparationLookup> lookupPreparation(String key,String dom,String sub,String revision){return executor==null?CompletableFuture.completedFuture(new RelationshipRecoveryUi.PreparationLookup(RelationshipRecoveryUi.PreparationLookup.Kind.UNAVAILABLE,null)):submit(()->coordinator.lookupPreparation(key,dom,sub,revision));}
    @Override public void close(){
        if(executor==null)return;LinkingWitness.outside(store);executor.shutdown();
        try{if(!executor.awaitTermination(5,TimeUnit.SECONDS))throw new IllegalStateException("Linking work is still active");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
    }
    void cleanupOwnedFixture(){
        if(executor==null||!executor.isTerminated())throw new IllegalStateException("Close owned provider before cleanup");
        witness.cleanupOwnedFixture(store);
    }
}
