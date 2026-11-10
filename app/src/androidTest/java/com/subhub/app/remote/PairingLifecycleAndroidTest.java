package com.subhub.app.remote;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.Cursor;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.settings.SettingsRepository;
import java.io.IOException;
import java.security.KeyStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Owned SQLite/Keystore clock and transition fixture; live Iroh handshake remains in PairingServiceAndroidTest. */
public final class PairingLifecycleAndroidTest {
    private static final String DOM=repeat('a'),SUB=repeat('b'),OTHER=repeat('c');
    private static final String SECRET=new String(new char[32]).replace('\0','A');

    @Test public void originalClocksOneUseCancellationAndCommittedOutcomeStaySeparate()throws Exception {
        try(Fixture f=new Fixture(DOM)){
            PairingInvite invite=f.service.create(f.clock.wall);
            String original=f.rows();
            f.clock.advance(100);assertEquals(invite.link(),f.service.create(f.clock.wall).link());assertEquals(original,f.rows());
            f.store.bindTimedInvite(invite.id,SUB,invite.tokenHash(),f.clock.observe(),f.clock);
            f.store.describeCandidate(invite.id,SUB,"Synthetic Sub","owned");
            String pinned=f.rows();
            assertThrows(SecurityException.class,()->f.store.bindTimedInvite(invite.id,OTHER,invite.tokenHash(),f.clock.observe(),f.clock));assertEquals(pinned,f.rows());
            String fingerprint=PairingInvite.fingerprint(DOM,SUB,invite.id);
            f.service.approve(invite.id,SUB,fingerprint,f.clock.wall);
            PairingLifecycleLedger.Operation approved=f.operation(invite,PairingLifecycleClock.Direction.CREATE);
            assertNotNull(approved.approval);long first=approved.approval.first.elapsed,deadline=approved.approval.deadlineElapsed;
            f.clock.advance(100);f.service.approve(invite.id,SUB,fingerprint,f.clock.wall);
            PairingLifecycleLedger.Operation repeated=f.operation(invite,PairingLifecycleClock.Direction.CREATE);
            assertEquals(first,repeated.approval.first.elapsed);assertEquals(deadline,repeated.approval.deadlineElapsed);
            String cancel=repeat('d');f.store.authorizePairingStart(invite.id,invite.id,PairingLifecycleClock.Direction.CREATE,cancel,f.clock.observe(),f.clock);
            String beforeWrong=f.rows();
            assertFalse(f.store.cancelPairingOperation(invite.id,invite.id,PairingLifecycleClock.Direction.CREATE,repeat('e'),f.clock.observe(),f.clock));assertEquals(beforeWrong,f.rows());
            f.clock.boot++;
            String beforeUncertain=f.rows();
            assertThrows(SecurityException.class,()->f.store.requireTimedCompletion(f.store.peer(SUB,invite.id),invite.tokenHash(),f.clock.observe(),f.clock));
            assertFalse(f.store.expirePairingOperation(invite.id,invite.id,PairingLifecycleClock.Direction.CREATE,f.clock.observe(),f.clock));assertEquals(beforeUncertain,f.rows());
            assertTrue(f.store.cancelPairingOperation(invite.id,invite.id,PairingLifecycleClock.Direction.CREATE,cancel,f.clock.observe(),f.clock));
            assertEquals(PairingLifecycleLedger.Stage.CANCELLED,f.operation(invite,PairingLifecycleClock.Direction.CREATE).stage);
            assertEquals(RemoteStore.State.REVOKED,f.store.peer(SUB,invite.id).state);
            try(Cursor row=f.store.getReadableDatabase().rawQuery("SELECT terminal_wall,terminal_elapsed,terminal_boot FROM pairing_lifecycle WHERE operation_id=?",new String[]{invite.id})){
                assertTrue(row.moveToFirst());assertTrue(row.isNull(0)&&row.isNull(1)&&row.isNull(2));
            }
            assertNull(f.secrets.controllerSecret(RemoteProtocol.Role.DOM,DOM,SUB,invite.id));
            PairingInvite replacement=f.service.create(f.clock.wall);assertNotEquals(invite.id,replacement.id);
            String replacementState=f.rows();
            assertFalse(f.store.cancelPairingOperation(invite.id,invite.id,PairingLifecycleClock.Direction.CREATE,cancel,f.clock.observe(),f.clock));assertEquals(replacementState,f.rows());
        }
        try(Fixture f=new Fixture(DOM)){
            PairingInvite invite=f.service.create(f.clock.wall);f.clock.advance(PairingInvite.LIFETIME_MILLIS);
            String before=f.rows();assertThrows(SecurityException.class,()->f.store.bindTimedInvite(invite.id,SUB,invite.tokenHash(),f.clock.observe(),f.clock));assertEquals(before,f.rows());
            assertTrue(f.store.expirePairingOperation(invite.id,invite.id,PairingLifecycleClock.Direction.CREATE,f.clock.observe(),f.clock));
            assertEquals(PairingLifecycleLedger.Stage.EXPIRED,f.operation(invite,PairingLifecycleClock.Direction.CREATE).stage);assertNull(f.store.peer(SUB,invite.id));
        }
        try(Fixture f=new Fixture(DOM)){
            PairingInvite invite=f.service.create(f.clock.wall);f.store.bindTimedInvite(invite.id,SUB,invite.tokenHash(),f.clock.observe(),f.clock);
            f.store.describeCandidate(invite.id,SUB,"Synthetic Sub","owned");
            f.service.approve(invite.id,SUB,PairingInvite.fingerprint(DOM,SUB,invite.id),f.clock.wall);
            f.clock.advance(PairingLifecycleClock.COMPLETION_MILLIS);
            String before=f.rows();assertThrows(SecurityException.class,()->f.store.activateTimedPeer(f.store.peer(SUB,invite.id),invite.tokenHash(),f.clock.observe(),f.clock));assertEquals(before,f.rows());
            assertNull(f.secrets.controllerSecret(RemoteProtocol.Role.DOM,DOM,SUB,invite.id));
        }
        try(Fixture f=new Fixture(SUB)){
            PairingInvite invite=PairingInvite.create(DOM,"owned",f.clock.wall);f.service.accept(invite.link(),"Synthetic Dom",f.clock.wall);
            String accepted=f.rows();assertThrows(SecurityException.class,()->f.service.accept(invite.link(),"Synthetic Dom",f.clock.wall));assertEquals(accepted,f.rows());
            f.clock.advance(PairingInvite.LIFETIME_MILLIS);
            assertTrue(f.store.expirePairingOperation(invite.id,invite.id,PairingLifecycleClock.Direction.ACCEPT,f.clock.observe(),f.clock));
            assertEquals(RemoteStore.State.PENDING,f.store.peer(DOM,invite.id).state);
            assertThrows(SecurityException.class,()->f.store.observeTimedApproval(f.store.peer(DOM,invite.id),invite.tokenHash(),SUB,PairingInvite.fingerprint(DOM,SUB,invite.id),f.clock.observe(),f.clock));
            assertNull(f.secrets.controllerSecret(RemoteProtocol.Role.SUB,SUB,DOM,invite.id));
            f.provisionExisting(invite);Map<String,String> metadata=f.metadata(invite);Map<String,?> ciphertext=f.ciphertext();
            AtomicInteger activated=new AtomicInteger();f.store.onActivated(peer->activated.incrementAndGet());
            PairingCompletionProof.Exchange exchange=PairingCompletionProof.offer(DOM,SUB,invite.id,f.clock.observe()).challenge(PairingCompletionProof.freshNonce());
            exchange.verifyActive(SECRET,exchange.active(SECRET),f.clock.observe());exchange.verifyPending(SECRET,exchange.pending(SECRET),f.clock.observe());
            PairingCompletionProof.Verified proof=exchange.verifyReady(SECRET,exchange.ready(SECRET),f.clock.observe());String digest=PairingCompletionProof.credentialDigest(SECRET);
            String before=f.rows();
            assertThrows(SecurityException.class,()->f.store.activateRecoveredPeer(OTHER,proof,digest,f.clock.observe(),f.clock));
            assertThrows(SecurityException.class,()->f.store.activateRecoveredPeer(SUB,proof,repeat('f'),f.clock.observe(),f.clock));assertEquals(before,f.rows());
            f.store.activateRecoveredPeer(SUB,proof,digest,f.clock.observe(),f.clock);
            assertEquals(RemoteStore.State.ACTIVE,f.store.peer(DOM,invite.id).state);assertEquals(0,f.store.peer(DOM,invite.id).lastSyncedAt);
            assertEquals(PairingLifecycleLedger.Stage.EXPIRED,f.operation(invite,PairingLifecycleClock.Direction.ACCEPT).stage);
            assertNull(f.operation(invite,PairingLifecycleClock.Direction.ACCEPT).approval);assertEquals(metadata,f.metadata(invite));assertEquals(ciphertext,f.ciphertext());
            assertEquals(1,activated.get());f.store.activateRecoveredPeer(SUB,proof,digest,f.clock.observe(),f.clock);assertEquals(1,activated.get());
            String active=f.rows();assertFalse(f.store.cancelPairingOperation(invite.id,invite.id,PairingLifecycleClock.Direction.ACCEPT,null,f.clock.observe(),f.clock));assertEquals(active,f.rows());
        }
        try(Fixture f=new Fixture(SUB)){
            PairingInvite legacy=PairingInvite.create(DOM,"owned",f.clock.wall);f.store.acceptInvite(legacy,"Legacy synthetic Dom");f.provisionExisting(legacy);
            assertNull(f.operation(legacy,PairingLifecycleClock.Direction.ACCEPT));Map<String,String> metadata=f.metadata(legacy);Map<String,?> ciphertext=f.ciphertext();
            f.clock.advance(PairingInvite.LIFETIME_MILLIS+1000);
            PairingCompletionProof.Exchange exchange=PairingCompletionProof.offer(DOM,SUB,legacy.id,f.clock.observe()).challenge(PairingCompletionProof.freshNonce());
            PairingCompletionProof.Verified proof=exchange.verifyReady(SECRET,exchange.ready(SECRET),f.clock.observe());
            f.store.activateRecoveredPeer(SUB,proof,PairingCompletionProof.credentialDigest(SECRET),f.clock.observe(),f.clock);
            assertEquals(RemoteStore.State.ACTIVE,f.store.peer(DOM,legacy.id).state);assertEquals(0,f.store.peer(DOM,legacy.id).lastSyncedAt);
            assertNull(f.operation(legacy,PairingLifecycleClock.Direction.ACCEPT));assertEquals(metadata,f.metadata(legacy));assertEquals(ciphertext,f.ciphertext());
        }
        for(boolean cancelled:new boolean[]{false,true})try(Fixture f=new Fixture(SUB)){
            PairingInvite invite=PairingInvite.create(DOM,"owned",f.clock.wall);f.store.acceptInvite(invite,"Legacy synthetic Dom");f.provisionExisting(invite);
            assertNull(f.operation(invite,PairingLifecycleClock.Direction.ACCEPT));assertEquals(invite.id,f.store.legacyPendingGeneration(PairingLifecycleClock.Direction.ACCEPT));
            PairingCompletionProof.Exchange exchange=PairingCompletionProof.offer(DOM,SUB,invite.id,f.clock.observe()).challenge(PairingCompletionProof.freshNonce());
            PairingCompletionProof.Verified proof=exchange.verifyReady(SECRET,exchange.ready(SECRET),f.clock.observe());String digest=PairingCompletionProof.credentialDigest(SECRET);
            if(cancelled){assertTrue(f.store.cancelPairingOperation(invite.id,invite.id,PairingLifecycleClock.Direction.ACCEPT,null,f.clock.observe(),f.clock));}
            else f.clock.advance(PairingLifecycleClock.PROOF_MILLIS);
            String before=f.rows();assertThrows(SecurityException.class,()->f.store.activateRecoveredPeer(SUB,proof,digest,f.clock.observe(),f.clock));assertEquals(before,f.rows());
            assertNull(f.operation(invite,PairingLifecycleClock.Direction.ACCEPT));
        }
    }

    @Test public void cheapLeaseDeadlinePublishesClosureWithoutSqlMutation()throws Exception {
        try(Fixture f=new Fixture(DOM)){
            PairingInvite invite=f.service.create(f.clock.wall);PairingLifecycleLedger.Operation bounds=f.operation(invite,PairingLifecycleClock.Direction.CREATE);
            // The handle does not need a UI snapshot to enforce its immutable backend deadline.
            AtomicInteger closed=new AtomicInteger();PairingOperationLease lease=new PairingOperationLease(null,"synthetic",bounds,f.clock.observe(),f.clock,ignored->closed.incrementAndGet());
            f.clock.advance(PairingInvite.LIFETIME_MILLIS);lease.checkDeadline();assertTrue(lease.isClosed());assertEquals(1,closed.get());lease.close();assertEquals(1,closed.get());
            assertEquals(PairingLifecycleLedger.Stage.PENDING,f.operation(invite,PairingLifecycleClock.Direction.CREATE).stage);
        }
    }

    @Test public void observedFirstApprovalRearmsCheapDeadlineOnceWithoutRenewingItsBound()throws Exception {
        try(Fixture f=new Fixture(DOM)){
            PairingInvite invite=f.service.create(f.clock.wall);PairingLifecycleLedger.Operation original=f.operation(invite,PairingLifecycleClock.Direction.CREATE);
            AtomicInteger closures=new AtomicInteger();PairingOperationLease lease=new PairingOperationLease(null,"synthetic",original,f.clock.observe(),f.clock,ignored->closures.incrementAndGet());
            f.clock.advance(PairingInvite.LIFETIME_MILLIS-1000);f.store.bindTimedInvite(invite.id,SUB,invite.tokenHash(),f.clock.observe(),f.clock);
            f.store.describeCandidate(invite.id,SUB,"Synthetic Sub","owned");
            f.service.approve(invite.id,SUB,PairingInvite.fingerprint(DOM,SUB,invite.id),f.clock.wall);
            PairingLifecycleLedger.Operation approved=f.operation(invite,PairingLifecycleClock.Direction.CREATE);lease.observeApproval(approved,f.clock.observe());
            f.clock.advance(1001);lease.checkDeadline();assertFalse(lease.isClosed());
            lease.observeApproval(approved,f.clock.observe());
            f.clock.advance(PairingLifecycleClock.COMPLETION_MILLIS-1001);lease.checkDeadline();assertTrue(lease.isClosed());assertEquals(1,closures.get());
            assertEquals(approved.approval.deadlineElapsed,f.operation(invite,PairingLifecycleClock.Direction.CREATE).approval.deadlineElapsed);
        }
    }

    @Test public void stagedFirstApprovalDefeatsStaleExpiryAndFailedSqlCannotRetainItsLease()throws Exception {
        try(Fixture f=new Fixture(DOM)){
            PairingInvite invite=f.service.create(f.clock.wall);f.store.bindTimedInvite(invite.id,SUB,invite.tokenHash(),f.clock.observe(),f.clock);
            f.store.describeCandidate(invite.id,SUB,"Synthetic Sub","owned");
            PairingLifecycleLedger.Operation pending=f.operation(invite,PairingLifecycleClock.Direction.CREATE);
            java.util.concurrent.CountDownLatch sampled=new java.util.concurrent.CountDownLatch(1),publish=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicBoolean first=new java.util.concurrent.atomic.AtomicBoolean(true);
            PairingLifecycleClock.Source delayedExpiry=new PairingLifecycleClock.Source(){
                public FormalRelationshipClock.Sample observe(){return f.clock.observe();}
                public FormalRelationshipClock.Sample transition(FormalRelationshipClock.Sample ignored){
                    if(first.compareAndSet(true,false)){
                        sampled.countDown();awaitFixture(publish);
                        return new FormalRelationshipClock.Sample(pending.bounds.publicWall,pending.bounds.publicElapsed,pending.bounds.created.boot);
                    }
                    return f.clock.observe();
                }
            };
            AtomicInteger closes=new AtomicInteger();PairingOperationLease lease=new PairingOperationLease(null,"synthetic",pending,f.clock.observe(),delayedExpiry,ignored->closes.incrementAndGet());
            f.store.onPairingApproval(lease::stageApproval);
            f.clock.advance(PairingInvite.LIFETIME_MILLIS-1);
            java.util.concurrent.ExecutorService watcher=java.util.concurrent.Executors.newSingleThreadExecutor();
            try{
                java.util.concurrent.Future<?> expiry=watcher.submit(lease::checkDeadline);
                assertTrue(sampled.await(3,java.util.concurrent.TimeUnit.SECONDS));
                f.service.approve(invite.id,SUB,PairingInvite.fingerprint(DOM,SUB,invite.id),f.clock.wall);
                publish.countDown();expiry.get(3,java.util.concurrent.TimeUnit.SECONDS);
                assertFalse(lease.isClosed());assertEquals(0,closes.get());
                f.clock.advance(PairingLifecycleClock.COMPLETION_MILLIS);lease.checkDeadline();assertTrue(lease.isClosed());assertEquals(1,closes.get());
            }finally{publish.countDown();watcher.shutdownNow();assertTrue(watcher.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS));}
        }
        try(Fixture f=new Fixture(DOM)){
            PairingInvite invite=f.service.create(f.clock.wall);f.store.bindTimedInvite(invite.id,SUB,invite.tokenHash(),f.clock.observe(),f.clock);
            f.store.describeCandidate(invite.id,SUB,"Synthetic Sub","owned");
            PairingLifecycleLedger.Operation pending=f.operation(invite,PairingLifecycleClock.Direction.CREATE);
            PairingOperationLease lease=new PairingOperationLease(null,"synthetic",pending,f.clock.observe(),f.clock,ignored->{});f.store.onPairingApproval(lease::stageApproval);
            FormalRelationshipClock.Sample admission=new FormalRelationshipClock.Sample(pending.bounds.publicWall-1,pending.bounds.publicElapsed-1,1);
            f.clock.advance(PairingInvite.LIFETIME_MILLIS);lease.checkDeadline();assertTrue(lease.isClosed());
            PairingLifecycleClock.Source admittedBeforeExpiry=new PairingLifecycleClock.Source(){
                public FormalRelationshipClock.Sample observe(){return admission;}
                public FormalRelationshipClock.Sample transition(FormalRelationshipClock.Sample ignored){return admission;}
            };
            String before=f.rows();
            // Model an already sampled admission preempted before publication: expiry wins and SQL must roll back.
            assertThrows(SecurityException.class,()->f.store.approveTimedInvite(invite.id,SUB,DOM,PairingInvite.fingerprint(DOM,SUB,invite.id),admission,admittedBeforeExpiry));
            assertEquals(before,f.rows());assertEquals(PairingLifecycleLedger.Stage.PENDING,f.operation(invite,PairingLifecycleClock.Direction.CREATE).stage);
        }
        try(Fixture f=new Fixture(DOM)){
            PairingInvite invite=f.service.create(f.clock.wall);f.store.bindTimedInvite(invite.id,SUB,invite.tokenHash(),f.clock.observe(),f.clock);
            f.store.describeCandidate(invite.id,SUB,"Synthetic Sub","owned");
            PairingLifecycleLedger.Operation pending=f.operation(invite,PairingLifecycleClock.Direction.CREATE);
            AtomicInteger closes=new AtomicInteger();PairingOperationLease lease=new PairingOperationLease(null,"synthetic",pending,f.clock.observe(),f.clock,ignored->closes.incrementAndGet());
            f.store.onPairingApproval(lease::stageApproval);
            f.store.getWritableDatabase().execSQL("CREATE TRIGGER owned_approval_failure BEFORE UPDATE OF approved ON invites WHEN NEW.approved=1 BEGIN SELECT RAISE(ABORT,'owned approval rollback'); END");
            String before=f.rows();
            assertThrows(android.database.sqlite.SQLiteException.class,()->f.service.approve(invite.id,SUB,PairingInvite.fingerprint(DOM,SUB,invite.id),f.clock.wall));
            assertEquals(before,f.rows());assertTrue(lease.isClosed());assertEquals(1,closes.get());
        }
    }

    @Test public void productionDeadlineProducerRunsWhileOtherWorkQueuesAreBlocked()throws Exception {
        try(Fixture f=new Fixture(DOM)){
            PairingInvite invite=f.service.create(f.clock.wall);PairingLifecycleLedger.Operation pending=f.operation(invite,PairingLifecycleClock.Direction.CREATE);
            java.util.concurrent.CountDownLatch closed=new java.util.concurrent.CountDownLatch(1),parked=new java.util.concurrent.CountDownLatch(2),release=new java.util.concurrent.CountDownLatch(1);
            AtomicInteger unparked=new AtomicInteger();
            PairingOperationLease lease=new PairingOperationLease(null,"synthetic",pending,f.clock.observe(),f.clock,ignored->closed.countDown());
            java.util.concurrent.ScheduledExecutorService shared=java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            java.util.concurrent.ExecutorService operations=java.util.concurrent.Executors.newSingleThreadExecutor();
            java.lang.reflect.Method factory=RemoteFeature.class.getDeclaredMethod("newPairingDeadlineTimer");factory.setAccessible(true);
            java.lang.reflect.Method start=RemoteFeature.class.getDeclaredMethod("startPairingDeadlineChecks",java.util.concurrent.ScheduledExecutorService.class,java.util.Collection.class);start.setAccessible(true);
            java.util.concurrent.ScheduledExecutorService deadline=(java.util.concurrent.ScheduledExecutorService)factory.invoke(null);
            Runnable blocker=()->{parked.countDown();awaitFixture(release);unparked.incrementAndGet();};
            try{
                shared.execute(blocker);operations.execute(blocker);assertTrue(parked.await(3,java.util.concurrent.TimeUnit.SECONDS));
                f.clock.advance(PairingInvite.LIFETIME_MILLIS);
                start.invoke(null,deadline,java.util.Collections.singletonList(lease));
                assertTrue(closed.await(3,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,unparked.get());assertTrue(lease.isClosed());
                assertEquals(PairingLifecycleLedger.Stage.PENDING,f.operation(invite,PairingLifecycleClock.Direction.CREATE).stage);
            }finally{
                release.countDown();deadline.shutdownNow();shared.shutdown();operations.shutdown();
                assertTrue(deadline.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS));assertTrue(shared.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS));assertTrue(operations.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS));
            }
        }
    }
    private static void awaitFixture(java.util.concurrent.CountDownLatch release){
        try{if(!release.await(6,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Owned fixture barrier timed out");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Owned fixture barrier interrupted",interrupted);}
    }

    private static final class Clock implements PairingLifecycleClock.Source {
        volatile long wall=1000,elapsed=10_000;volatile int boot=1;
        void advance(long millis){wall+=millis;elapsed+=millis;}
        public FormalRelationshipClock.Sample observe(){return new FormalRelationshipClock.Sample(wall,elapsed,boot);}
        public FormalRelationshipClock.Sample transition(FormalRelationshipClock.Sample ignored){return observe();}
    }
    private static final class Fixture implements AutoCloseable {
        final Context target=InstrumentationRegistry.getInstrumentation().getTargetContext(),context;
        final String prefix="pairing-lifecycle-"+UUID.randomUUID()+"-",database=prefix+"owned.db",alias="subhub.test."+prefix;
        final Clock clock=new Clock();final RemoteStore store;final RemoteSecrets secrets;final PairingService service;
        Fixture(String identity)throws Exception {
            context=new ContextWrapper(target){
                @Override public Context getApplicationContext(){return this;}
                @Override public SharedPreferences getSharedPreferences(String name,int mode){return target.getSharedPreferences(prefix+name,mode);}
            };
            assertFalse(context.getDatabasePath(database).exists());assertTrue(context.getSharedPreferences("secrets",0).getAll().isEmpty());
            KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);assertFalse(keys.containsAlias(alias));
            assertTrue(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit().putString(DeviceRolePreferences.KEY_MODE,"BOTH").commit());
            store=new RemoteStore(context,database);assertFalse(store.isApplicationDatabase());secrets=new RemoteSecrets(context,"secrets",alias);
            PeerTransport transport=new PeerTransport(){
                public String localIdentity(){return identity;}public String inviteRoute(){return "owned";}
                public Connection connect(String expected,String route,long timeout)throws IOException{throw new IOException("No live transport in storage fixture");}
                public Connection accept(long timeout)throws IOException{throw new IOException("No live transport in storage fixture");}public void close(){}
            };
            service=new PairingService(store,secrets,transport,new RemoteKeyholder(context,store,secrets),()->true,()->true,"Synthetic device",clock);
        }
        PairingLifecycleLedger.Operation operation(PairingInvite invite,PairingLifecycleClock.Direction direction){return store.pairingOperation(invite.id,invite.id,direction);}
        void provisionExisting(PairingInvite invite)throws Exception {
            secrets.saveControllerSecret(RemoteProtocol.Role.SUB,SUB,DOM,invite.id,SECRET);
            for(String[] entry:new String[][]{{"provisioned","1"},{"last_step","17"},{"attempts","3"},{"blocked_wall","1500"},{"blocked_elapsed","10500"},{"blocked_boot","1"}})
                store.putMetadata(DOM,invite.id,"controller_auth_v1_"+entry[0],entry[1]);
        }
        Map<String,String> metadata(PairingInvite invite){
            Map<String,String> result=new LinkedHashMap<>();try(Cursor rows=store.getReadableDatabase().rawQuery("SELECT name,value FROM remote_metadata WHERE peer=? AND generation=? ORDER BY name",new String[]{DOM,invite.id})){
                while(rows.moveToNext())result.put(rows.getString(0),rows.getString(1));
            }return result;
        }
        Map<String,?> ciphertext(){return new LinkedHashMap<>(context.getSharedPreferences("secrets",0).getAll());}
        String rows(){StringBuilder result=new StringBuilder();for(String table:new String[]{"peers","invites","pairing_lifecycle","remote_metadata"}){
            try(Cursor rows=store.getReadableDatabase().rawQuery("SELECT * FROM "+table+" ORDER BY rowid",null)){
                result.append(table);while(rows.moveToNext())for(int i=0;i<rows.getColumnCount();i++)result.append('|').append(rows.getType(i)).append(':').append(rows.getString(i));
            }
        }return result.toString();}
        @Override public void close()throws Exception {
            store.close();assertTrue(target.deleteDatabase(database));
            assertTrue(context.getSharedPreferences("secrets",0).edit().clear().commit());assertTrue(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit().clear().commit());
            KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);keys.deleteEntry(alias);
        }
    }
    private static String repeat(char value){return new String(new char[64]).replace('\0',value);}
}
