package com.subhub.app.remote;

import static org.junit.Assert.*;
import android.database.Cursor;
import com.subhub.app.relationships.RelationshipUi;
import com.subhub.app.relationships.RelationshipRecoveryUi;
import com.subhub.app.security.SubFeaturePolicy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.Test;

public final class FormalRelationshipCoordinatorAndroidTest extends FormalRelationshipFixture {
    @Test public void originalReviewSurvivesRecreationWithoutRenewalAndConfirmsOnce() {
        FormalRelationshipCoordinator engine=coordinator();FormalRelationshipCoordinator.InternalSnapshot s=engine.internalSnapshot();
        Map<String,?> consent=context.getSharedPreferences(SubFeaturePolicy.STORE,0).getAll();
        RelationshipUi.LocalReview first=engine.prepareLocal("original",s.dom,s.sub,s.changeCounter);
        RelationshipUi.LocalReview retry=engine.prepareLocal("original",s.dom,s.sub,s.changeCounter);
        assertEquals(first.operationId,retry.operationId);assertEquals(first.originalDeadline,retry.originalDeadline);assertEquals(first.requestDigest,retry.requestDigest);
        assertEquals(1,count("formal_operations"));assertEquals(1,count("formal_governing_slots"));
        assertThrows(SecurityException.class,()->engine.prepareLocal("original",s.sub,s.dom,s.changeCounter));
        FormalRelationshipCoordinator recreated=coordinator();
        assertThrows(SecurityException.class,()->recreated.confirmLocal(first.operationId,first.requestDigest));
        RelationshipRecoveryUi.ReviewResult review=recreated.reviewLocal(first.operationId,first.requestDigest);
        assertEquals(RelationshipRecoveryUi.ReviewResult.Kind.REVIEW,review.kind);assertEquals(first.originalDeadline,review.review.originalDeadline);
        RelationshipUi.Operation active=recreated.confirmLocal(first.operationId,first.requestDigest);
        assertEquals(RelationshipUi.OperationState.ACTIVE,active.state);
        assertEquals(active.relationshipId,recreated.confirmLocal(first.operationId,first.requestDigest).relationshipId);
        assertEquals(RelationshipUi.OperationState.ACTIVE,recreated.cancel(first.operationId).state);
        assertEquals(consent,context.getSharedPreferences(SubFeaturePolicy.STORE,0).getAll());
        assertFalse(new FormalRelationshipProvider().snapshot().join().supported);
        try(Cursor row=db().rawQuery("SELECT phase FROM formal_authority",null)){assertTrue(row.moveToFirst());assertEquals("INERT",row.getString(0));}
    }
    @Test public void sharedAllocatorSeparatesNewPeerPhysicalRowidFromFormalOrder() {
        FormalRelationshipCoordinator engine=coordinator();FormalRelationshipCoordinator.InternalSnapshot s=engine.internalSnapshot();
        RelationshipUi.LocalReview local=engine.prepareLocal("local",s.dom,s.sub,s.changeCounter);
        PairingInvite invite=PairingInvite.create(DOM,"fixture",1000);store.saveInvite(invite);store.bindInvite(invite.id,SUB,invite.tokenHash(),1001);store.approveInvite(invite.id,SUB,"Sub",1001);
        assertEquals(2,store.additionOrder(SUB,invite.id));
        try(Cursor row=db().rawQuery("SELECT rowid FROM peers",null)){assertTrue(row.moveToFirst());assertEquals(1,row.getLong(0));}
        store.activate(SUB,invite.id);assertEquals(2,store.additionOrder(SUB,invite.id));
        assertEquals(RelationshipUi.OperationState.ACTIVE,engine.confirmLocal(local.operationId,local.requestDigest).state);
    }
    @Test public void concurrentLocalPreparationAndLegacyRemoteAcceptanceHaveOneWinner() throws Exception {
        FormalRelationshipCoordinator engine=coordinator();FormalRelationshipCoordinator.InternalSnapshot s=engine.internalSnapshot();
        RemoteStore second=another();CountDownLatch start=new CountDownLatch(1);ExecutorService workers=Executors.newFixedThreadPool(2);
        PairingInvite invite=PairingInvite.create(DOM,"fixture",1000);
        try {
            Future<Boolean> local=workers.submit(()->{start.await();try{engine.prepareLocal("race",s.dom,s.sub,s.changeCounter);return true;}catch(SecurityException|android.database.sqlite.SQLiteConstraintException refused){return false;}});
            Future<Boolean> peer=workers.submit(()->{start.await();try{second.acceptInvite(invite,"Claim");return true;}catch(SecurityException|android.database.sqlite.SQLiteConstraintException refused){return false;}});
            start.countDown();assertNotEquals(local.get(),peer.get());assertEquals(1,count("formal_governing_slots"));
            assertEquals(1,count("peers")+count("formal_operations"));
        } finally {workers.shutdownNow();}
    }
    @Test public void scopeChangesAndMissingKeysCannotMintNewAuthority() {
        FormalRelationshipCoordinator engine=coordinator();FormalRelationshipCoordinator.InternalSnapshot s=engine.internalSnapshot();
        RelationshipUi.LocalReview original=engine.prepareLocal("scope",s.dom,s.sub,s.changeCounter);
        synchronized(SubFeaturePolicy.LOCK){context.getSharedPreferences(SubFeaturePolicy.STORE,0).edit().putBoolean("tasks",false).commit();}
        assertThrows(SecurityException.class,()->engine.confirmLocal(original.operationId,original.requestDigest));
        assertEquals(RelationshipRecoveryUi.ReviewResult.Kind.STATUS,engine.reviewLocal(original.operationId,original.requestDigest).kind);
        assertEquals(RelationshipRecoveryUi.PreparationLookup.Kind.UNKNOWN,engine.lookupPreparation("missing",s.dom,s.sub,s.changeCounter).kind);
        assertEquals(RelationshipUi.OperationState.PREPARED,engine.resume(original.operationId).state);
    }
    @Test public void reservedTerminalCapacityAllowsCompletionAtFullBudget() {
        FormalRelationshipCoordinator engine=coordinator();
        for(int i=0;i<127;i++){
            FormalRelationshipCoordinator.InternalSnapshot s=engine.internalSnapshot();RelationshipUi.LocalReview review=engine.prepareLocal("retained-"+i,s.dom,s.sub,s.changeCounter);engine.cancel(review.operationId);
        }
        FormalRelationshipCoordinator.InternalSnapshot s=engine.internalSnapshot();RelationshipUi.LocalReview last=engine.prepareLocal("last",s.dom,s.sub,s.changeCounter);
        assertEquals(128,count("formal_operations"));assertEquals(RelationshipUi.OperationState.CANCELLED,engine.cancel(last.operationId).state);
        assertThrows(IllegalStateException.class,()->engine.prepareLocal("no-capacity",s.dom,s.sub,engine.internalSnapshot().changeCounter));
        assertEquals(128,count("formal_operations"));assertEquals(0,count("formal_governing_slots"));
    }
    @Test public void sqlFailureKeepsOriginalPreparedReservationAndRetry() {
        FormalRelationshipCoordinator engine=coordinator();FormalRelationshipCoordinator.InternalSnapshot s=engine.internalSnapshot();RelationshipUi.LocalReview review=engine.prepareLocal("failure",s.dom,s.sub,s.changeCounter);
        db().execSQL("CREATE TRIGGER fail_local BEFORE UPDATE ON formal_relationships BEGIN SELECT RAISE(ABORT,'owned failure'); END");
        assertThrows(android.database.sqlite.SQLiteException.class,()->engine.confirmLocal(review.operationId,review.requestDigest));
        assertEquals(RelationshipUi.OperationState.PREPARED,engine.resume(review.operationId).state);assertEquals(1,count("formal_governing_slots"));
        db().execSQL("DROP TRIGGER fail_local");assertEquals(RelationshipUi.OperationState.ACTIVE,engine.confirmLocal(review.operationId,review.requestDigest).state);
    }
    @Test public void retiringTerminalOperationCannotReleaseActiveRelationshipOrProveAbsence() {
        FormalRelationshipCoordinator engine=coordinator();FormalRelationshipCoordinator.InternalSnapshot s=engine.internalSnapshot();
        RelationshipUi.LocalReview review=engine.prepareLocal("retirement",s.dom,s.sub,s.changeCounter);
        RelationshipUi.Operation active=engine.confirmLocal(review.operationId,review.requestDigest);
        FormalRelationshipClock.Sample terminal;
        try(Cursor row=db().rawQuery("SELECT terminal_wall,terminal_elapsed,terminal_boot FROM formal_operations WHERE id=?",new String[]{review.operationId})){
            assertTrue(row.moveToFirst());terminal=new FormalRelationshipClock.Sample(row.getLong(0),row.getLong(1),row.getInt(2));
        }
        // Advance only the explicit fixture observation; original rows and the OS clock remain unchanged.
        long age=FormalRelationshipClock.RETENTION_MILLIS;
        db().beginTransaction();try{
            FormalRelationshipCoordinator.prune(db(),new FormalRelationshipClock.Sample(terminal.wall+age,terminal.elapsed+age,terminal.boot+1));
            assertEquals(1,count("formal_operations"));
            FormalRelationshipCoordinator.prune(db(),new FormalRelationshipClock.Sample(terminal.wall+age,terminal.elapsed+age,terminal.boot));
            db().setTransactionSuccessful();
        }finally{db().endTransaction();}
        assertEquals(0,count("formal_operations"));assertEquals(1,count("formal_governing_slots"));
        try(Cursor row=db().rawQuery("SELECT state FROM formal_relationships WHERE id=?",new String[]{active.relationshipId})){assertTrue(row.moveToFirst());assertEquals("ACTIVE",row.getString(0));}
        assertEquals(RelationshipRecoveryUi.PreparationLookup.Kind.UNKNOWN,engine.lookupPreparation("retirement",s.dom,s.sub,s.changeCounter).kind);
        assertThrows(SecurityException.class,()->engine.prepareLocal("retirement",s.dom,s.sub,s.changeCounter));
    }
    @Test public void actualCredentialAndRoleChangesInvalidateEarlierReview() {
        FormalRelationshipCoordinator engine=coordinator();FormalRelationshipCoordinator.InternalSnapshot s=engine.internalSnapshot();
        RelationshipUi.LocalReview review=engine.prepareLocal("credentials",s.dom,s.sub,s.changeCounter);
        assertTrue(com.subhub.app.security.ControllerPinManager.setPin(context,"735982"));
        assertThrows(SecurityException.class,()->engine.confirmLocal(review.operationId,review.requestDigest));
        assertEquals(RelationshipRecoveryUi.ReviewResult.Kind.REVIEW,engine.reviewLocal(review.operationId,review.requestDigest).kind);
        context.getSharedPreferences(com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME,0).edit()
                .putString(com.subhub.app.security.DeviceRolePreferences.KEY_MODE,"SUB_ONLY").commit();
        assertThrows(SecurityException.class,()->engine.confirmLocal(review.operationId,review.requestDigest));
        assertEquals(1,count("formal_operations"));
    }
    @Test public void lockContentionRechecksFreshnessAndOriginalDeadlineWithCheapTime() throws Exception {
        FixtureClock clock=new FixtureClock();FormalRelationshipCoordinator engine=new FormalRelationshipCoordinator(context,store,clock);
        FormalRelationshipCoordinator.InternalSnapshot s=engine.internalSnapshot();RelationshipUi.LocalReview review=engine.prepareLocal("contention",s.dom,s.sub,s.changeCounter);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try{
            Future<RelationshipUi.Operation> stale=contendedConfirm(worker,engine,review,clock,6000);
            java.util.concurrent.ExecutionException rejected=assertThrows(java.util.concurrent.ExecutionException.class,()->stale.get(5,java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(rejected.getCause() instanceof SecurityException);assertEquals(RelationshipUi.OperationState.PREPARED,engine.resume(review.operationId).state);
            clock.offset=119000;assertEquals(RelationshipRecoveryUi.ReviewResult.Kind.REVIEW,engine.reviewLocal(review.operationId,review.requestDigest).kind);
            RelationshipUi.Operation expired=contendedConfirm(worker,engine,review,clock,2000).get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(RelationshipUi.OperationState.EXPIRED,expired.state);assertEquals(review.originalDeadline,expired.originalDeadline);assertEquals(0,count("formal_governing_slots"));
        }finally{worker.shutdownNow();}
    }
    private Future<RelationshipUi.Operation> contendedConfirm(ExecutorService executor,FormalRelationshipCoordinator engine,RelationshipUi.LocalReview review,FixtureClock clock,long advance)throws Exception{
        clock.calls.set(0);clock.observed=new CountDownLatch(1);java.util.concurrent.atomic.AtomicReference<Thread> thread=new java.util.concurrent.atomic.AtomicReference<>();Future<RelationshipUi.Operation> future;
        synchronized(com.subhub.app.appmode.RuntimeSessionIdentity.LOCK){
            future=executor.submit(()->{thread.set(Thread.currentThread());return engine.confirmLocal(review.operationId,review.requestDigest);});
            assertTrue(clock.observed.await(5,java.util.concurrent.TimeUnit.SECONDS));
            long limit=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while(thread.get().getState()!=Thread.State.BLOCKED&&System.nanoTime()<limit)Thread.sleep(5);
            assertEquals(Thread.State.BLOCKED,thread.get().getState());clock.offset+=advance;
        }
        return future;
    }
    private final class FixtureClock implements FormalRelationshipClock.Source{
        volatile long offset;volatile CountDownLatch observed;final java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        @Override public FormalRelationshipClock.Sample observe(){
            assertOutside();FormalRelationshipClock.Sample actual=FormalRelationshipClock.observe(context);
            FormalRelationshipClock.Sample result=new FormalRelationshipClock.Sample(actual.wall+offset,actual.elapsed+offset,actual.boot);
            if(calls.incrementAndGet()==2&&observed!=null)observed.countDown();return result;
        }
        @Override public FormalRelationshipClock.Sample transition(FormalRelationshipClock.Sample anchor){
            assertTrue(Thread.holdsLock(store));assertTrue(Thread.holdsLock(SubFeaturePolicy.LOCK));assertTrue(Thread.holdsLock(com.subhub.app.appmode.RuntimeSessionIdentity.LOCK));assertTrue(db().inTransaction());
            return new FormalRelationshipClock.Sample(System.currentTimeMillis()+offset,android.os.SystemClock.elapsedRealtime()+offset,anchor.boot);
        }
    }
    private void assertOutside(){
        assertFalse(Thread.holdsLock(store));assertFalse(Thread.holdsLock(SubFeaturePolicy.LOCK));assertFalse(Thread.holdsLock(com.subhub.app.appmode.RuntimeSessionIdentity.LOCK));assertFalse(db().inTransaction());
    }
    @Test public void enabledHardcoreObservationNeverQueriesPlatformOrProviderInsideSql(){
        int[] platform={0},providerCalls={0};
        android.content.Context spy=new android.content.ContextWrapper(context){
            @Override public android.content.Context getApplicationContext(){return this;}
            @Override public Object getSystemService(String name){assertOutside();if(android.content.Context.DEVICE_POLICY_SERVICE.equals(name))platform[0]++;return super.getSystemService(name);}
            @Override public android.content.ContentResolver getContentResolver(){assertOutside();return super.getContentResolver();}
        };
        com.subhub.app.security.KeyholderProvider owned=com.subhub.app.people.People.keyholder();
        com.subhub.app.people.People.installKeyholder(new com.subhub.app.security.KeyholderProvider(){
            @Override public State state(){assertOutside();providerCalls[0]++;return owned.state();}
            @Override public String assignedDomName(){assertOutside();return owned.assignedDomName();}
            @Override public com.subhub.app.security.ControllerAuthenticator.Result verifyCode(String code){assertOutside();return owned.verifyCode(code);}
        });
        try{
            context.getSharedPreferences(SubFeaturePolicy.STORE,0).edit().putBoolean("hardcore_locally_consented",true).commit();
            context.getSharedPreferences(com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME,0).edit().putBoolean(com.subhub.app.security.HardcoreModeManager.KEY_REQUESTED,true).commit();
            FormalRelationshipCoordinator engine=new FormalRelationshipCoordinator(spy,store);FormalRelationshipCoordinator.InternalSnapshot s=engine.internalSnapshot();
            RelationshipUi.LocalReview review=engine.prepareLocal("hardcore-observation",s.dom,s.sub,s.changeCounter);
            assertTrue(platform[0]>=2);assertTrue(providerCalls[0]>=2);
            context.getSharedPreferences(com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME,0).edit().putBoolean(com.subhub.app.security.HardcoreModeManager.KEY_REQUESTED,false).commit();
            assertThrows(SecurityException.class,()->engine.confirmLocal(review.operationId,review.requestDigest));
            assertEquals(RelationshipUi.OperationState.PREPARED,engine.resume(review.operationId).state);
        }finally{com.subhub.app.people.People.installKeyholder(owned);}
    }
    @Test public void absoluteAndNormalizedProductionAliasesCannotAcquireFixtureAuthority() throws Exception{
        java.io.File production=context.getDatabasePath("remote_sync_v1.db").getCanonicalFile();
        java.util.Map<String,String> before=productionFingerprint(production);
        for(String alias:new String[]{"remote_sync_v1.db",production.getPath(),production.getParent()+"/./remote_sync_v1.db",production.getParent()+"/unused/../remote_sync_v1.db"}){
            RemoteStore candidate=new RemoteStore(context,alias);
            try{assertThrows(IllegalArgumentException.class,()->new FormalRelationshipCoordinator(context,candidate));}finally{candidate.close();}
        }
        String fake="formal-fixture-looks-owned.db";
        android.content.Context redirected=new android.content.ContextWrapper(context){
            @Override public android.content.Context getApplicationContext(){return this;}
            @Override public java.io.File getDatabasePath(String name){return fake.equals(name)?production:super.getDatabasePath(name);}
        };
        RemoteStore candidate=new RemoteStore(redirected,fake);
        try{assertThrows(IllegalArgumentException.class,()->new FormalRelationshipCoordinator(context,candidate));}finally{candidate.close();}
        assertEquals(before,productionFingerprint(production));
        FormalRelationshipCoordinator engine=coordinator();assertNotNull(engine.internalSnapshot());
    }
    private static java.util.Map<String,String> productionFingerprint(java.io.File production)throws Exception{
        java.util.Map<String,String> result=new java.util.LinkedHashMap<>();
        for(String suffix:new String[]{"","-wal"}){java.io.File file=new java.io.File(production.getPath()+suffix);
            result.put(suffix,file.exists()?RemoteProtocol.sha256(java.nio.file.Files.readAllBytes(file.toPath())):"absent");}
        return result;
    }
    @Test public void preheldSqlAndAuthorityLocksRejectBeforeEveryObservation(){
        int[] identity={0},clockCalls={0},providerCalls={0},platform={0};
        android.content.Context spy=new android.content.ContextWrapper(context){
            @Override public android.content.Context getApplicationContext(){return this;}
            @Override public java.io.File getDatabasePath(String name){identity[0]++;return super.getDatabasePath(name);}
            @Override public Object getSystemService(String name){platform[0]++;return super.getSystemService(name);}
            @Override public android.content.ContentResolver getContentResolver(){platform[0]++;return super.getContentResolver();}
        };
        RemoteStore observed=new RemoteStore(spy,database);handles.add(observed);observed.getWritableDatabase();
        FormalRelationshipClock.Source source=new FormalRelationshipClock.Source(){
            @Override public FormalRelationshipClock.Sample observe(){clockCalls[0]++;return FormalRelationshipClock.observe(spy);}
            @Override public FormalRelationshipClock.Sample transition(FormalRelationshipClock.Sample anchor){clockCalls[0]++;return FormalRelationshipClock.source(spy).transition(anchor);}
        };
        FormalRelationshipCoordinator engine=new FormalRelationshipCoordinator(spy,observed,source);
        android.database.sqlite.SQLiteDatabase retired=observed.getWritableDatabase();observed.close();
        assertFalse(retired.isOpen());assertFalse(observed.hasCurrentFormalTransaction());
        android.database.sqlite.SQLiteDatabase reopened=observed.getReadableDatabase();assertNotSame(retired,reopened);assertTrue(reopened.isOpen());
        com.subhub.app.security.KeyholderProvider owned=com.subhub.app.people.People.keyholder();
        com.subhub.app.people.People.installKeyholder(new com.subhub.app.security.KeyholderProvider(){
            @Override public State state(){providerCalls[0]++;return owned.state();}
            @Override public String assignedDomName(){providerCalls[0]++;return owned.assignedDomName();}
            @Override public com.subhub.app.security.ControllerAuthenticator.Result verifyCode(String code){providerCalls[0]++;return owned.verifyCode(code);}
        });
        Runnable rejected=()->{
            for(Runnable operation:new Runnable[]{()->engine.internalSnapshot(),()->engine.prepareLocal("k","d","s","r"),
                    ()->engine.confirmLocal("id","digest"),()->engine.reviewLocal("id","digest"),()->engine.resume("id"),
                    ()->engine.cancel("id"),()->engine.lookupPreparation("k","d","s","r"),
                    ()->new FormalRelationshipCoordinator(spy,observed,source),
                    ()->FormalRelationshipCoordinator.releaseOwnedFixture(observed)})assertThrows(IllegalStateException.class,operation::run);
            assertEquals(0,identity[0]);assertEquals(0,clockCalls[0]);assertEquals(0,providerCalls[0]);assertEquals(0,platform[0]);
        };
        try{
            identity[0]=clockCalls[0]=providerCalls[0]=platform[0]=0;
            observed.getWritableDatabase().beginTransaction();try{rejected.run();}finally{observed.getWritableDatabase().endTransaction();}
            synchronized(observed){rejected.run();}
            synchronized(SubFeaturePolicy.LOCK){rejected.run();}
            synchronized(com.subhub.app.appmode.RuntimeSessionIdentity.LOCK){rejected.run();}
        }finally{com.subhub.app.people.People.installKeyholder(owned);}
    }
    @Test public void concurrentGettersAndCloseReopenRetainCurrentTransactionGuard()throws Exception{
        java.util.concurrent.ExecutorService workers=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{
            Future<?> getters=workers.submit(()->{try{start.await();}catch(InterruptedException e){throw new AssertionError(e);}
                for(int i=0;i<100;i++){store.getReadableDatabase();store.getWritableDatabase();}});
            Future<?> lifecycle=workers.submit(()->{try{start.await();}catch(InterruptedException e){throw new AssertionError(e);}
                for(int i=0;i<100;i++)synchronized(store){
                    store.close();assertFalse(store.hasCurrentFormalTransaction());
                    android.database.sqlite.SQLiteDatabase current=store.getWritableDatabase();current.beginTransaction();
                    try{assertTrue(store.hasCurrentFormalTransaction());assertThrows(IllegalStateException.class,()->FormalRelationshipCoordinator.releaseOwnedFixture(store));}
                    finally{current.endTransaction();}
                }});
            start.countDown();getters.get(20,java.util.concurrent.TimeUnit.SECONDS);lifecycle.get(20,java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(coordinator().internalSnapshot());assertFalse(store.hasCurrentFormalTransaction());
        }finally{workers.shutdownNow();}
    }
}
