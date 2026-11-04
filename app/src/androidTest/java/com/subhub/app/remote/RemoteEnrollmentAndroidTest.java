package com.subhub.app.remote;

import static org.junit.Assert.*;
import android.content.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import com.subhub.app.R;
import com.subhub.app.people.People;
import com.subhub.app.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RemoteEnrollmentAndroidTest {
    @Rule public final com.subhub.app.UiAutomationStateRule automation = new com.subhub.app.UiAutomationStateRule();
    private Context context;private RemoteFeature feature,previous;private PeerTransport other;
    private KeyholderProvider provider;private java.lang.reflect.Field singleton;private String database;
    private PairingLifecycleClock.Source fixtureClock;
    private final java.util.concurrent.atomic.AtomicBoolean clockUnavailable=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicLong clockAdvance=new java.util.concurrent.atomic.AtomicLong();
    private SharedPreferences main,legacy;private Map<String,?> mainBefore,legacyBefore;
    @Before public void setup()throws Exception {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().getUiAutomation();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        main=context.getSharedPreferences(com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME,0);legacy=context.getSharedPreferences("subhub_controller_auth",0);
        mainBefore=new HashMap<>(main.getAll());legacyBefore=new HashMap<>(legacy.getAll());
        main.edit().remove("controller_pin_salt").remove("controller_pin_hash").remove("controller_keyholder_optional").commit();legacy.edit().clear().commit();
        provider=People.keyholder();People.installKeyholder(new KeyholderProvider(){public State state(){return State.NONE;}public String assignedDomName(){return "";}public ControllerAuthenticator.Result verifyCode(String code){return ControllerAuthenticator.Result.UNAVAILABLE;}});
        ControllerPinManager.enterSubMode();database="enrollment-"+UUID.randomUUID();
        PairingLifecycleClock.Source systemClock=PairingLifecycleClock.source(context);
        fixtureClock=new PairingLifecycleClock.Source(){
            public FormalRelationshipClock.Sample observe(){if(clockUnavailable.get())throw new IllegalStateException("Synthetic clock read failure");FormalRelationshipClock.Sample now=systemClock.observe();return new FormalRelationshipClock.Sample(now.wall+clockAdvance.get(),now.elapsed+1_000_000+clockAdvance.get(),now.boot);}
            public FormalRelationshipClock.Sample transition(FormalRelationshipClock.Sample observed){return new FormalRelationshipClock.Sample(System.currentTimeMillis()+clockAdvance.get(),android.os.SystemClock.elapsedRealtime()+1_000_000+clockAdvance.get(),observed.boot);}
        };
        feature=new RemoteFeature(context,new RemoteStore(context,database+".db"),new RemoteSecrets(context),fixtureClock);
        byte[] identity=new RemoteSecrets(context).identitySeed();PeerTransport endpoint;
        try{endpoint=IrohPeerTransport.open(context,identity,new String[0],10_000);}finally{Arrays.fill(identity,(byte)0);}other=IrohPeerTransport.open(context,random(),new String[0],10_000);
        java.lang.reflect.Field transport=RemoteFeature.class.getDeclaredField("endpoint");transport.setAccessible(true);transport.set(feature,endpoint);
        singleton=RemoteFeature.class.getDeclaredField("instance");singleton.setAccessible(true);previous=(RemoteFeature)singleton.get(null);singleton.set(null,feature);
    }
    @After public void cleanup()throws Exception {
        for(RemoteStore.Peer peer:feature.store.peers(false))feature.store.revoke(peer.identity,peer.generation);
        RemoteScheduler.synchronize(context,false);singleton.set(null,previous);feature.shutdownForTest();other.close();context.deleteDatabase(database+".db");
        restore(main,mainBefore);restore(legacy,legacyBefore);People.installKeyholder(provider);ControllerPinManager.enterSubMode();
    }
    @Test public void unkeyedEnrollmentRequiresExactOneUseConfirmationWithoutCreatingPinOrDomSession()throws Exception {
        PairingInvite invite=PairingInvite.create(other.localIdentity(),other.inviteRoute(),System.currentTimeMillis());
        assertFalse(ControllerPinManager.hasCredentials(context));RemoteFeature.Enrollment approval=feature.prepareEnrollment(invite.code()).get(15,TimeUnit.SECONDS);RemoteFeature.Enrollment duplicate=feature.prepareEnrollment(invite.code()).get(15,TimeUnit.SECONDS);
        assertTrue(feature.store.peers(false).isEmpty());assertEquals(invite.domIdentity,approval.domIdentity);assertFalse(ControllerPinManager.isDomModeActive());
        feature.confirmEnrollment(approval).get(15,TimeUnit.SECONDS);assertFalse(ControllerPinManager.isConfigured(context));assertFalse(ControllerPinManager.isDomModeActive());
        assertEquals(RemoteStore.State.PENDING,feature.store.peers(false).get(0).state);
        try{feature.confirmEnrollment(approval).get(15,TimeUnit.SECONDS);fail("Enrollment approval was reused");}catch(ExecutionException expected){assertTrue(expected.getCause() instanceof SecurityException);}
        try{feature.confirmEnrollment(duplicate).get(15,TimeUnit.SECONDS);fail("Duplicate invitation was accepted");}catch(ExecutionException expected){assertTrue(expected.getCause() instanceof SecurityException);}
        assertNotNull(new RemoteSecrets(context).pendingLink(invite.id));
        feature.cancelEnrollment(feature.store.peers(false).get(0));assertTrue(feature.store.peers(false).isEmpty());
        assertFalse(ControllerPinManager.isConfigured(context));assertFalse(ControllerPinManager.isDomModeActive());
    }
    @Test public void existingCredentialCannotEnrollWhileLocked()throws Exception {
        assertTrue(ControllerPinManager.setPin(context,"2468"));ControllerPinManager.enterSubMode();PairingInvite invite=PairingInvite.create(other.localIdentity(),other.inviteRoute(),System.currentTimeMillis());
        try{feature.prepareEnrollment(invite.code()).get(15,TimeUnit.SECONDS);fail("Existing keyholder authentication was bypassed");}catch(ExecutionException expected){assertTrue(expected.getCause() instanceof SecurityException);}
        assertTrue(feature.store.peers(false).isEmpty());assertFalse(ControllerPinManager.isDomModeActive());
    }
    @Test public void recreatedInvitationRestoresFromIdentifierWithoutSavingSecretInInstanceState()throws Exception {
        assertTrue(ControllerPinManager.setPin(context,"2468"));
        try(ActivityScenario<PairingActivity> page=ActivityScenario.launch(new Intent(context,PairingActivity.class).putExtra("asDom",true))) {
            AtomicReference<View> button=new AtomicReference<>();page.onActivity(a->button.set(find(a.body,context.getString(R.string.remote_create_invite))));tap(button.get());
            await(()->invite(page)!=null);String id=invite(page).id;page.onActivity(a->{Bundle state=new Bundle();a.onSaveInstanceState(state);assertEquals(id,state.getString("invitationId"));assertFalse(state.toString().contains(inviteUnchecked(a).secret()));});
            page.recreate();await(()->invite(page)!=null);assertEquals(id,invite(page).id);
            page.onActivity(a->{assertNotNull(find(a.body,context.getString(R.string.remote_share_invite)));assertNotNull(find(a.body,context.getString(R.string.remote_copy_invite)));});
            feature.cancel(id);
        }
    }
    @Test public void addSubFromPeopleStartsInvitationDirectlyAndBackReturnsToPeople() throws Exception {
        directEntry(com.subhub.app.people.PeopleActivity.class, false);
    }
    @Test public void addSubFromEmptyTasksStartsInvitationDirectlyAndBackReturnsToTasks() throws Exception {
        directEntry(com.subhub.app.tasks.TasksOverviewActivity.class, false);
    }
    @Test public void directCreationSurvivesRotationWhilePendingWithoutCreatingAnotherInvitation() throws Exception {
        directEntry(com.subhub.app.people.PeopleActivity.class, true);
    }
    @Test public void addSubReplacesRetainedExpiredInvitationWithoutNestedCreateAction() throws Exception {
        retainedEntry("expired");
    }
    @Test public void addSubRestoresLiveInvitationWithoutChangingCodeOrDeadline() throws Exception {
        retainedEntry("live");
    }
    @Test public void legacyInvitationAutomaticallyOpensFreshQr() throws Exception {
        retainedEntry("legacy");
    }
    @Test public void uncertainInvitationAutomaticallyOpensFreshQr() throws Exception {
        retainedEntry("uncertain");
    }
    @Test public void missingInvitationSecretAutomaticallyOpensFreshQr() throws Exception {
        retainedEntry("missing");
    }
    @Test public void approvedLiveCodeRetainsOriginalApprovalAndPublicDeadline() throws Exception {retainedEntry("approved");}
    @Test public void expiredPublicCodeNeverRevivesAnApprovedPendingRelationship() throws Exception {retainedEntry("approved-expired");}
    private void retainedEntry(String state) throws Exception {
        main.edit().putString(DeviceRolePreferences.KEY_MODE,DeviceRolePreferences.Mode.DOM_ONLY.name()).commit();
        assertTrue(ControllerPinManager.setPin(context,"2468"));assertTrue(ControllerPinManager.verify(context,"2468"));ControllerPinManager.enterDomMode();
        com.subhub.app.people.ConnectionUi prior=People.connectionUi();People.installConnectionUi(new RemoteUi());
        FormalRelationshipClock.Sample now=fixtureClock.observe();long age=("expired".equals(state)||"approved-expired".equals(state))?PairingInvite.LIFETIME_MILLIS+1000:1000;
        FormalRelationshipClock.Sample original=new FormalRelationshipClock.Sample(now.wall-age,now.elapsed-age,now.boot+("uncertain".equals(state)?1:0));
        java.lang.reflect.Field transport=RemoteFeature.class.getDeclaredField("endpoint");transport.setAccessible(true);PeerTransport endpoint=(PeerTransport)transport.get(feature);
        PairingInvite retained=PairingInvite.create(endpoint.localIdentity(),endpoint.inviteRoute(),original.wall);
        PairingLifecycleClock.Source seedClock=new PairingLifecycleClock.Source(){public FormalRelationshipClock.Sample observe(){return original;}public FormalRelationshipClock.Sample transition(FormalRelationshipClock.Sample observed){return original;}};
        feature.submit(()->{
            if("legacy".equals(state))feature.store.saveInvite(retained);else feature.store.saveTimedInvite(retained,original,seedClock);
            if(!"missing".equals(state))new RemoteSecrets(context).savePending(retained);
            if("legacy".equals(state)){feature.store.bindInvite(retained.id,other.localIdentity(),retained.tokenHash(),original.wall);feature.store.describeCandidate(retained.id,other.localIdentity(),"Synthetic Sub",other.inviteRoute());}
            else if("missing".equals(state)||"uncertain".equals(state)||(state.startsWith("approved")||"revoked-timed".equals(state))){feature.store.bindTimedInvite(retained.id,other.localIdentity(),retained.tokenHash(),original,seedClock);feature.store.describeCandidate(retained.id,other.localIdentity(),"Synthetic Sub",other.inviteRoute());}
            if(state.startsWith("approved")||"revoked-timed".equals(state)){
                long advance="approved-expired".equals(state)?PairingInvite.LIFETIME_MILLIS-1000:100;
                FormalRelationshipClock.Sample approved=new FormalRelationshipClock.Sample(original.wall+advance,original.elapsed+advance,original.boot);
                PairingLifecycleClock.Source approvalClock=new PairingLifecycleClock.Source(){public FormalRelationshipClock.Sample observe(){return approved;}public FormalRelationshipClock.Sample transition(FormalRelationshipClock.Sample sampled){return approved;}};
                feature.store.approveTimedInvite(retained.id,other.localIdentity(),endpoint.localIdentity(),PairingInvite.fingerprint(endpoint.localIdentity(),other.localIdentity(),retained.id),approved,approvalClock);
                if("revoked-timed".equals(state)){feature.store.revoke(other.localIdentity(),retained.id);new RemoteSecrets(context).savePending(retained);}
            }
            return null;
        }).get(15,TimeUnit.SECONDS);
        PairingLifecycleLedger.Operation before=feature.store.pairingOperation(retained.id,retained.id,PairingLifecycleClock.Direction.CREATE);
        try(ActivityScenario<?> origin=ActivityScenario.launch(new Intent(context,com.subhub.app.people.PeopleActivity.class))){
            await(()->resumed(com.subhub.app.people.PeopleActivity.class)!=null);
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(R.string.role_add_sub))
                    .perform(com.subhub.app.NativeUiActions.revealAboveNavigation(),androidx.test.espresso.action.ViewActions.click());
            await(()->resumed(PairingActivity.class)!=null);
            await(()->resumed(PairingActivity.class)!=null&&inviteUnchecked(resumed(PairingActivity.class))!=null);
            PairingActivity page=resumed(PairingActivity.class);PairingInvite visible=inviteUnchecked(page);
            if("live".equals(state)||"approved".equals(state)){
                assertEquals(retained.code(),visible.code());assertEquals(retained.expiresAt,visible.expiresAt);
                PairingLifecycleLedger.Operation after=feature.store.pairingOperation(retained.id,retained.id,PairingLifecycleClock.Direction.CREATE);
                assertEquals(before.bounds.publicElapsed,after.bounds.publicElapsed);
                if(before.approval!=null){assertEquals(before.approval.first.elapsed,after.approval.first.elapsed);assertEquals(before.approval.deadlineElapsed,after.approval.deadlineElapsed);}
            }
            else assertNotEquals(retained.id,visible.id);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
                assertNull(find(page.body,context.getString(R.string.remote_create_invite)));
                assertNull(find(page.body,context.getString(R.string.pairing_background)));
                assertNotNull(find(page.body,context.getString(R.string.remote_copy_invite)));
                assertNotNull(find(page.body,context.getString(R.string.remote_share_invite)));
            });
            assertEquals("approved".equals(state)?0:1,feature.store.invitations(System.currentTimeMillis()).size());
            if("approved".equals(state))assertEquals(RemoteStore.State.PENDING,feature.store.peer(other.localIdentity(),retained.id).state);else assertTrue(feature.store.peers(false).isEmpty());
            await(()->feature.pairingServiceState(visible.id,visible.id,true)==RemoteFeature.PairingServiceState.RUNNING);
            assertFalse(hasText(page,R.string.pairing_recovery));
            await(()->hasQr(page.body));
            if("live".equals(state))capture(page,"add-sub-live.png");
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(R.string.remote_copy_invite))
                    .perform(com.subhub.app.NativeUiActions.revealAboveNavigation(),androidx.test.espresso.action.ViewActions.click());
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
                ClipboardManager clipboard=(ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE);
                assertEquals(visible.code(),clipboard.getPrimaryClip().getItemAt(0).getText().toString());clipboard.setPrimaryClip(ClipData.newPlainText("",""));
            });
            if("revoked-timed".equals(state))assertEquals(RemoteStore.State.REVOKED,feature.store.peer(other.localIdentity(),retained.id).state);
            if("expired".equals(state)||"approved-expired".equals(state)||"revoked-timed".equals(state))assertEquals(PairingLifecycleLedger.Stage.CANCELLED,feature.store.pairingOperation(retained.id,retained.id,PairingLifecycleClock.Direction.CREATE).stage);
            feature.cancel(visible.id);
        } finally {
            feature.cancel(retained.id);People.installConnectionUi(prior);
        }
    }
    @Test public void revokedBoundTimedInvitationAutomaticallyCreatesAUsableNewQr() throws Exception {retainedEntry("revoked-timed");}
    @Test public void liveCandidateBindingRejectsAnotherParticipantWithoutRenewingTheCode() throws Exception {
        configureDom();PairingInvite invite=feature.ensureInvitation().get(15,TimeUnit.SECONDS);PeerTransport endpoint=endpoint();
        feature.submit(()->{feature.store.bindTimedInvite(invite.id,other.localIdentity(),invite.tokenHash(),fixtureClock.observe(),fixtureClock);feature.store.describeCandidate(invite.id,other.localIdentity(),"Synthetic Sub",other.inviteRoute());return null;}).get(15,TimeUnit.SECONDS);
        String before=generationSnapshot(invite.id);PairingInvite reused=feature.ensureInvitation().get(15,TimeUnit.SECONDS);assertEquals(invite.code(),reused.code());
        String wrong=new String(new char[64]).replace('\0','d');ExecutionException rejected=assertThrows(ExecutionException.class,()->feature.approve(invite.id,wrong,PairingInvite.fingerprint(invite.domIdentity,wrong,invite.id)).get(15,TimeUnit.SECONDS));assertTrue(rejected.getCause() instanceof SecurityException);
        assertEquals(before,generationSnapshot(invite.id));assertTrue(feature.store.peers(false).isEmpty());feature.cancel(invite.id);
    }
    @Test public void returningToQrAfterOriginalExpiryAutomaticallyReconcilesAndResumes() throws Exception {
        configureDom();com.subhub.app.people.ConnectionUi prior=People.connectionUi();People.installConnectionUi(new RemoteUi());String replacement=null;
        try(ActivityScenario<?> origin=addSubFrom(com.subhub.app.people.PeopleActivity.class)){
            await(()->inviteUnchecked(resumed(PairingActivity.class))!=null);PairingActivity page=resumed(PairingActivity.class);String original=inviteUnchecked(page).id;
            await(()->feature.pairingServiceState(original,original,true)==RemoteFeature.PairingServiceState.RUNNING);
            int task=page.getTaskId();InstrumentationRegistry.getInstrumentation().runOnMainSync(()->assertTrue(page.moveTaskToBack(true)));await(()->resumed(PairingActivity.class)==null);
            clockAdvance.set(PairingInvite.LIFETIME_MILLIS+1000);
            android.app.ActivityManager manager=(android.app.ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);android.app.ActivityManager.AppTask owned=null;
            for(android.app.ActivityManager.AppTask candidate:manager.getAppTasks())if(candidate.getTaskInfo().id==task)owned=candidate;
            assertNotNull(owned);android.app.ActivityManager.AppTask resumedTask=owned;InstrumentationRegistry.getInstrumentation().runOnMainSync(resumedTask::moveToFront);
            await(()->resumed(PairingActivity.class)!=null&&inviteUnchecked(resumed(PairingActivity.class))!=null&&!original.equals(inviteUnchecked(resumed(PairingActivity.class)).id));
            PairingInvite ready=inviteUnchecked(resumed(PairingActivity.class));replacement=ready.id;assertTrue(ready.expiresAt>fixtureClock.observe().wall);
            assertFalse(hasText(resumed(PairingActivity.class),R.string.remote_create_invite));assertFalse(hasText(resumed(PairingActivity.class),R.string.pairing_recovery));
            await(()->feature.pairingServiceState(ready.id,ready.id,true)==RemoteFeature.PairingServiceState.RUNNING);assertEquals(PairingLifecycleLedger.Stage.CANCELLED,feature.store.pairingOperation(original,original,PairingLifecycleClock.Direction.CREATE).stage);
        }finally{clockAdvance.set(0);if(replacement!=null)feature.cancel(replacement);People.installConnectionUi(prior);}
    }
    @Test public void manyMixedLegacyRequestsOpenQrAndPreserveCurrentActiveGenerationOfSamePeer() throws Exception {
        configureDom();PeerTransport endpoint=endpoint();List<PairingInvite> old=new ArrayList<>();AtomicReference<PairingInvite> active=new AtomicReference<>();
        feature.submit(()->{
            // The identity uniqueness rule permits revoked history beside one current active generation.
            PairingInvite olderPending=legacy(endpoint,1);feature.store.revoke(other.localIdentity(),olderPending.id);old.add(olderPending);
            old.add(legacy(endpoint,2));
            PairingInvite current=legacy(endpoint,3);active.set(current);
            new RemoteSecrets(context).saveControllerSecret(RemoteProtocol.Role.DOM,endpoint.localIdentity(),other.localIdentity(),current.id,"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
            feature.store.putMetadata(other.localIdentity(),current.id,"synthetic_profile","retained profile");
            feature.store.publish(other.localIdentity(),current.id,RemoteProtocol.Stream.CONFIGURATION,"synthetic_pending",new org.json.JSONObject().put("value","retained outbox"),false,System.currentTimeMillis());
            for(int i=0;i<5;i++)old.add(legacy(endpoint,0));return null;
        }).get(15,TimeUnit.SECONDS);
        String before=generationSnapshot(active.get().id);String activeCode=new RemoteSecrets(context).pendingLink(active.get().id);
        com.subhub.app.people.ConnectionUi prior=People.connectionUi();People.installConnectionUi(new RemoteUi());String readyId=null;
        try(ActivityScenario<?> origin=addSubFrom(com.subhub.app.people.PeopleActivity.class)){
            await(()->inviteUnchecked(resumed(PairingActivity.class))!=null);PairingActivity page=resumed(PairingActivity.class);PairingInvite ready=inviteUnchecked(page);readyId=ready.id;
            assertFalse(hasText(page,R.string.pairing_recovery));assertFalse(hasText(page,R.string.remote_create_invite));await(()->hasQr(page.body));
            await(()->feature.pairingServiceState(ready.id,ready.id,true)==RemoteFeature.PairingServiceState.RUNNING);
            assertEquals(before,generationSnapshot(active.get().id));assertEquals(activeCode,new RemoteSecrets(context).pendingLink(active.get().id));
            assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",new RemoteSecrets(context).controllerSecret(RemoteProtocol.Role.DOM,endpoint.localIdentity(),other.localIdentity(),active.get().id));
            assertEquals(RemoteStore.State.ACTIVE,feature.store.peer(other.localIdentity(),active.get().id).state);
            for(PairingInvite priorInvite:old){assertNotEquals(priorInvite.id,ready.id);assertNull(new RemoteSecrets(context).pendingLink(priorInvite.id));
                RemoteStore.Peer peer=feature.store.peer(other.localIdentity(),priorInvite.id);if(peer!=null)assertEquals(RemoteStore.State.REVOKED,peer.state);
            }
            assertEquals(ready.id,feature.currentPairingOperation(true).get(15,TimeUnit.SECONDS).generation);
            capture(page,"add-sub-mixed-recovered.png");feature.cancel(ready.id);
        }finally{if(readyId!=null)feature.cancel(readyId);People.installConnectionUi(prior);}
    }
    @Test public void cancelFromPeopleReturnsToTheExistingSubsMenuAndNextAddSubIsFresh() throws Exception {cancelReturnsSubs(com.subhub.app.people.PeopleActivity.class);}
    @Test public void cancelFromTasksReturnsToSubsWithoutAnIntermediatePairingPage() throws Exception {cancelReturnsSubs(com.subhub.app.tasks.TasksOverviewActivity.class);}
    private void cancelReturnsSubs(Class<? extends android.app.Activity> entry) throws Exception {
        configureDom();com.subhub.app.people.ConnectionUi prior=People.connectionUi();People.installConnectionUi(new RemoteUi());
        try(ActivityScenario<?> origin=ActivityScenario.launch(new Intent(context,entry))){
            await(()->resumed(entry)!=null);android.app.Activity original=resumed(entry);
            clickAddSub();await(()->resumed(PairingActivity.class)!=null&&inviteUnchecked(resumed(PairingActivity.class))!=null);
            PairingActivity first=resumed(PairingActivity.class);String firstId=inviteUnchecked(first).id;
            cancelVisiblePairing();await(()->resumed(com.subhub.app.people.PeopleActivity.class)!=null&&first.isDestroyed());
            com.subhub.app.people.PeopleActivity menu=resumed(com.subhub.app.people.PeopleActivity.class);
            if(entry==com.subhub.app.people.PeopleActivity.class)assertSame(original,menu);
            assertEquals(1,peopleActivities());assertNull(resumed(PairingActivity.class));assertNull(feature.currentPairingOperation(true).get(15,TimeUnit.SECONDS));
            assertEquals(PairingLifecycleLedger.Stage.CANCELLED,feature.store.pairingOperation(firstId,firstId,PairingLifecycleClock.Direction.CREATE).stage);
            clickAddSub();await(()->resumed(PairingActivity.class)!=null&&inviteUnchecked(resumed(PairingActivity.class))!=null);
            PairingActivity second=resumed(PairingActivity.class);PairingInvite replacement=inviteUnchecked(second);assertNotEquals(firstId,replacement.id);
            assertFalse(hasText(second,R.string.remote_create_invite));assertFalse(hasText(second,R.string.pairing_recovery));
            cancelVisiblePairing();await(()->resumed(com.subhub.app.people.PeopleActivity.class)!=null&&second.isDestroyed());assertSame(menu,resumed(com.subhub.app.people.PeopleActivity.class));assertEquals(1,peopleActivities());
            if(entry!=com.subhub.app.people.PeopleActivity.class)InstrumentationRegistry.getInstrumentation().runOnMainSync(menu::finish);
        }finally{People.installConnectionUi(prior);}
    }
    @Test public void activationBeforeRetirementWinsWithoutChangingActiveRows() throws Exception {
        configureDom();PeerTransport endpoint=endpoint();PairingInvite invite=feature.submit(()->legacy(endpoint,1)).get(15,TimeUnit.SECONDS);
        List<String> observed=feature.store.creationGenerations();blockOutgoing(other.localIdentity(),invite.id);
        feature.submit(()->{feature.store.activate(other.localIdentity(),invite.id);return null;}).get(15,TimeUnit.SECONDS);String active=generationSnapshot(invite.id);
        List<String> retired=feature.submit(()->feature.store.retirePendingInvitations(observed,null,fixtureClock.observe(),fixtureClock,ignored->{})).get(15,TimeUnit.SECONDS);
        assertTrue(retired.isEmpty());assertEquals(active,generationSnapshot(invite.id));assertEquals(RemoteStore.State.ACTIVE,feature.store.peer(other.localIdentity(),invite.id).state);
        PairingInvite ready=feature.ensureInvitation().get(15,TimeUnit.SECONDS);assertNotEquals(invite.id,ready.id);assertEquals(active,generationSnapshot(invite.id));feature.cancel(ready.id);
    }
    @Test public void staleCleanupFailureRollsBackTheWholeInventoryAndRetainsSecrets() throws Exception {
        configureDom();PeerTransport endpoint=endpoint();PairingInvite first=feature.submit(()->legacy(endpoint,1)).get(15,TimeUnit.SECONDS);PairingInvite second=feature.submit(()->legacy(endpoint,0)).get(15,TimeUnit.SECONDS);
        String before=pairingSnapshot();String firstSecret=new RemoteSecrets(context).pendingLink(first.id),secondSecret=new RemoteSecrets(context).pendingLink(second.id);
        feature.store.getWritableDatabase().execSQL("CREATE TRIGGER owned_recovery_failure BEFORE UPDATE OF consumed ON invites WHEN NEW.consumed=1 BEGIN SELECT RAISE(ABORT,'owned recovery rollback'); END");
        try{assertThrows(ExecutionException.class,()->feature.ensureInvitation().get(15,TimeUnit.SECONDS));assertEquals(before,pairingSnapshot());assertEquals(firstSecret,new RemoteSecrets(context).pendingLink(first.id));assertEquals(secondSecret,new RemoteSecrets(context).pendingLink(second.id));}
        finally{feature.store.getWritableDatabase().execSQL("DROP TRIGGER owned_recovery_failure");}
        PairingInvite ready=feature.ensureInvitation().get(15,TimeUnit.SECONDS);feature.cancel(ready.id);
    }
    @Test public void boundedOverflowAndLockedWorkspaceDoNotPartiallyResetPendingData() throws Exception {
        configureDom();PeerTransport endpoint=endpoint();
        feature.submit(()->{for(int i=0;i<129;i++)legacy(endpoint,0);return null;}).get(45,TimeUnit.SECONDS);String before=pairingSnapshot();
        assertThrows(ExecutionException.class,()->feature.ensureInvitation().get(15,TimeUnit.SECONDS));assertEquals(before,pairingSnapshot());
        ControllerPinManager.enterSubMode();assertThrows(ExecutionException.class,()->feature.ensureInvitation().get(15,TimeUnit.SECONDS));assertEquals(before,pairingSnapshot());
        assertTrue(ControllerPinManager.verify(context,"2468"));ControllerPinManager.enterDomMode();
    }
    @Test public void committedRetirementClosesItsLeaseEvenWhenNotificationCleanupFails() throws Exception {
        configureDom();PairingInvite invite=feature.createInvite().get(15,TimeUnit.SECONDS);PeerTransport endpoint=endpoint();
        feature.submit(()->{feature.store.bindTimedInvite(invite.id,other.localIdentity(),invite.tokenHash(),fixtureClock.observe(),fixtureClock);feature.store.describeCandidate(invite.id,other.localIdentity(),"Synthetic Sub",other.inviteRoute());feature.store.approveTimedInvite(invite.id,other.localIdentity(),invite.domIdentity,PairingInvite.fingerprint(invite.domIdentity,other.localIdentity(),invite.id),fixtureClock.observe(),fixtureClock);return null;}).get(15,TimeUnit.SECONDS);
        RemoteFeature.PairingStart start=feature.preparePairingStart(invite.id,invite.id,true).get(15,TimeUnit.SECONDS);PairingOperationLease lease=feature.acquirePairingLease(start.ticket()).get(15,TimeUnit.SECONDS);
        new RemoteSecrets(context).removePending(invite.id);
        java.lang.reflect.Field field=RemoteStore.class.getDeclaredField("revoked");field.setAccessible(true);java.util.function.BiConsumer<String,String> original=(java.util.function.BiConsumer<String,String>)field.get(feature.store);
        feature.store.onRevoked((identity,generation)->{throw new IllegalStateException("Synthetic post-commit notification failure");});
        try{assertThrows(ExecutionException.class,()->feature.ensureInvitation().get(15,TimeUnit.SECONDS));assertTrue(lease.isClosed());assertEquals(PairingLifecycleLedger.Stage.CANCELLED,feature.store.pairingOperation(invite.id,invite.id,PairingLifecycleClock.Direction.CREATE).stage);assertEquals(RemoteStore.State.REVOKED,feature.store.peer(other.localIdentity(),invite.id).state);}
        finally{feature.store.onRevoked(original);}
        PairingInvite ready=feature.ensureInvitation().get(15,TimeUnit.SECONDS);feature.cancel(ready.id);
    }
    @Test public void originalCodeExpiringDuringCleanupIsReplacedBeforeReturningQr() throws Exception {
        configureDom();PairingInvite retained=feature.createInvite().get(15,TimeUnit.SECONDS);PeerTransport endpoint=endpoint();PairingInvite old=feature.submit(()->legacy(endpoint,1)).get(15,TimeUnit.SECONDS);
        java.lang.reflect.Field field=RemoteStore.class.getDeclaredField("revoked");field.setAccessible(true);java.util.function.BiConsumer<String,String> original=(java.util.function.BiConsumer<String,String>)field.get(feature.store);
        feature.store.onRevoked((identity,generation)->{original.accept(identity,generation);if(old.id.equals(generation))clockAdvance.set(PairingInvite.LIFETIME_MILLIS);});
        PairingInvite ready=null;
        try{ready=feature.ensureInvitation().get(15,TimeUnit.SECONDS);assertNotEquals(retained.id,ready.id);assertTrue(ready.expiresAt>fixtureClock.observe().wall);assertEquals(PairingLifecycleLedger.Stage.CANCELLED,feature.store.pairingOperation(retained.id,retained.id,PairingLifecycleClock.Direction.CREATE).stage);}
        finally{feature.store.onRevoked(original);clockAdvance.set(0);if(ready!=null)feature.cancel(ready.id);}
    }
    private void configureDom(){main.edit().putString(DeviceRolePreferences.KEY_MODE,DeviceRolePreferences.Mode.DOM_ONLY.name()).commit();assertTrue(ControllerPinManager.setPin(context,"2468"));assertTrue(ControllerPinManager.verify(context,"2468"));ControllerPinManager.enterDomMode();}
    private PeerTransport endpoint() throws Exception {java.lang.reflect.Field field=RemoteFeature.class.getDeclaredField("endpoint");field.setAccessible(true);return (PeerTransport)field.get(feature);}
    private void blockOutgoing(String identity,String generation) throws Exception {java.lang.reflect.Field field=RemoteFeature.class.getDeclaredField("busy");field.setAccessible(true);((Set<String>)field.get(feature)).add(identity+":"+generation);}
    private PairingInvite legacy(PeerTransport endpoint,int state) throws Exception {
        PairingInvite invite=PairingInvite.create(endpoint.localIdentity(),endpoint.inviteRoute(),System.currentTimeMillis()-1000);feature.store.saveInvite(invite);
        if(state!=0){feature.store.bindInvite(invite.id,other.localIdentity(),invite.tokenHash(),System.currentTimeMillis());feature.store.describeCandidate(invite.id,other.localIdentity(),"Synthetic Sub",other.inviteRoute());feature.store.approveInvite(invite.id,other.localIdentity(),"Synthetic Sub",System.currentTimeMillis());
            if(state==2)feature.store.revoke(other.localIdentity(),invite.id);
            if(state==3){blockOutgoing(other.localIdentity(),invite.id);feature.store.activate(other.localIdentity(),invite.id);}
            if(state==2||state==3)feature.store.authorityTransition("owned-legacy-orphan",invite.id,db->{db.execSQL("UPDATE invites SET consumed=0 WHERE id=?",new Object[]{invite.id});return null;});
        }
        new RemoteSecrets(context).savePending(invite);return invite;
    }
    private ActivityScenario<?> addSubFrom(Class<? extends android.app.Activity> entry) throws Exception {ActivityScenario<?> origin=ActivityScenario.launch(new Intent(context,entry));await(()->resumed(entry)!=null);clickAddSub();await(()->resumed(PairingActivity.class)!=null);return origin;}
    private void clickAddSub() throws Exception {await(()->hasText(resumed(com.subhub.app.people.PeopleActivity.class),R.string.role_add_sub)||hasText(resumed(com.subhub.app.tasks.TasksOverviewActivity.class),R.string.role_add_sub));androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(R.string.role_add_sub)).perform(com.subhub.app.NativeUiActions.revealAboveNavigation(),androidx.test.espresso.action.ViewActions.click());}
    private void cancelVisiblePairing() throws Exception {await(()->{PairingActivity page=resumed(PairingActivity.class);if(page==null)return false;PairingInvite shown=inviteUnchecked(page);if(shown==null||feature.pairingServiceState(shown.id,shown.id,true)!=RemoteFeature.PairingServiceState.RUNNING)return false;java.util.concurrent.atomic.AtomicBoolean ready=new java.util.concurrent.atomic.AtomicBoolean();InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{View action=page.body.findViewWithTag("pairing_lifecycle_cancel");ready.set(action!=null&&action.isEnabled()&&action.getHeight()>0);});return ready.get();});androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withTagValue(org.hamcrest.Matchers.is("pairing_lifecycle_cancel"))).perform(com.subhub.app.NativeUiActions.revealAboveNavigation(),androidx.test.espresso.action.ViewActions.click());}
    private static int peopleActivities(){java.util.concurrent.atomic.AtomicInteger count=new java.util.concurrent.atomic.AtomicInteger();InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{Set<android.app.Activity> all=new HashSet<>();for(androidx.test.runner.lifecycle.Stage stage:androidx.test.runner.lifecycle.Stage.values())if(stage!=androidx.test.runner.lifecycle.Stage.DESTROYED)for(android.app.Activity activity:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage))if(activity instanceof com.subhub.app.people.PeopleActivity)all.add(activity);count.set(all.size());});return count.get();}
    private String generationSnapshot(String generation){return feature.store.authorityObservation(()->{synchronized(feature.store){
        android.database.sqlite.SQLiteDatabase db=feature.store.getReadableDatabase();org.json.JSONArray snapshot=new org.json.JSONArray();
        try(android.database.Cursor tables=db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",null)){
            while(tables.moveToNext()){
                String table=tables.getString(0),quoted="\""+table.replace("\"","\"\"")+"\"";List<String> columns=new ArrayList<>();
                try(android.database.Cursor info=db.rawQuery("PRAGMA table_info("+quoted+")",null)){while(info.moveToNext())if(info.getString(1).toLowerCase(java.util.Locale.ROOT).contains("generation"))columns.add("\""+info.getString(1).replace("\"","\"\"")+"\"");}
                if(table.equals("invites"))columns.add("id");if(columns.isEmpty())continue;
                List<String> conditions=new ArrayList<>();for(String column:columns)conditions.add(column+"=?");String[] args=new String[columns.size()];Arrays.fill(args,generation);
                try(android.database.Cursor rows=db.rawQuery("SELECT * FROM "+quoted+" WHERE "+String.join(" OR ",conditions),args)){snapshot.put(table).put(cursorRows(rows));}
            }
        }return snapshot.toString();
    }});}
    private String pairingSnapshot(){return feature.store.authorityObservation(()->{synchronized(feature.store){org.json.JSONArray snapshot=new org.json.JSONArray();for(String table:new String[]{"invites","pairing_lifecycle","peers"})try(android.database.Cursor rows=feature.store.getReadableDatabase().rawQuery("SELECT * FROM "+table,null)){snapshot.put(table).put(cursorRows(rows));}return snapshot.toString();}});}
    private static org.json.JSONArray cursorRows(android.database.Cursor rows){List<String> encoded=new ArrayList<>();while(rows.moveToNext()){org.json.JSONArray row=new org.json.JSONArray();for(int i=0;i<rows.getColumnCount();i++){if(rows.isNull(i))row.put(org.json.JSONObject.NULL);else if(rows.getType(i)==android.database.Cursor.FIELD_TYPE_BLOB)row.put(android.util.Base64.encodeToString(rows.getBlob(i),android.util.Base64.NO_WRAP));else row.put(rows.getString(i));}encoded.add(row.toString());}Collections.sort(encoded);org.json.JSONArray result=new org.json.JSONArray();for(String row:encoded)result.put(row);return result;}
    @Test public void initialDiscoveryFailureOffersWorkingRetryWithoutMintingInvitation() throws Exception {
        main.edit().putString(DeviceRolePreferences.KEY_MODE,DeviceRolePreferences.Mode.DOM_ONLY.name()).commit();
        assertTrue(ControllerPinManager.setPin(context,"2468"));assertTrue(ControllerPinManager.verify(context,"2468"));ControllerPinManager.enterDomMode();
        com.subhub.app.people.ConnectionUi prior=People.connectionUi();People.installConnectionUi(new RemoteUi());clockUnavailable.set(true);
        try(ActivityScenario<?> origin=ActivityScenario.launch(new Intent(context,com.subhub.app.people.PeopleActivity.class))){
            await(()->resumed(com.subhub.app.people.PeopleActivity.class)!=null);
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(R.string.role_add_sub))
                    .perform(com.subhub.app.NativeUiActions.revealAboveNavigation(),androidx.test.espresso.action.ViewActions.click());
            await(()->hasText(resumed(PairingActivity.class),R.string.role_retry));
            assertTrue(feature.store.invitations(System.currentTimeMillis()).isEmpty());clockUnavailable.set(false);
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(R.string.role_retry))
                    .perform(com.subhub.app.NativeUiActions.revealAboveNavigation(),androidx.test.espresso.action.ViewActions.click());
            await(()->resumed(PairingActivity.class)!=null&&inviteUnchecked(resumed(PairingActivity.class))!=null);
            assertTrue(hasText(resumed(PairingActivity.class),R.string.remote_copy_invite));
            feature.cancel(inviteUnchecked(resumed(PairingActivity.class)).id);
        } finally{clockUnavailable.set(false);People.installConnectionUi(prior);}
    }
    private static boolean hasQr(View view){
        java.util.concurrent.atomic.AtomicBoolean found=new java.util.concurrent.atomic.AtomicBoolean();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->found.set(findQr(view)));return found.get();
    }
    private static boolean findQr(View view){
        if(view instanceof ImageView&&view.getWidth()>0&&view.getHeight()>0&&((ImageView)view).getDrawable()!=null)return true;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(findQr(((ViewGroup)view).getChildAt(i)))return true;
        return false;
    }
    private static void capture(PairingActivity activity,String name){
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            View view=activity.getWindow().getDecorView();android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(view.getWidth(),view.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
            view.draw(new android.graphics.Canvas(bitmap));java.io.File file=new java.io.File(activity.getExternalFilesDir(null),name);
            try(java.io.FileOutputStream output=new java.io.FileOutputStream(file)){assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output));}
            catch(java.io.IOException failure){throw new AssertionError(failure);}finally{bitmap.recycle();}
        });
    }
    private static PairingActivity resumedOnMain(){
        for(android.app.Activity page:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(page instanceof PairingActivity)return (PairingActivity)page;
        throw new AssertionError("Pairing page is not resumed");
    }
    private boolean hasText(android.app.Activity activity,int resource){
        if(activity==null)return false;java.util.concurrent.atomic.AtomicBoolean found=new java.util.concurrent.atomic.AtomicBoolean();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->found.set(find(activity.findViewById(android.R.id.content),context.getString(resource))!=null));return found.get();
    }
    private void directEntry(Class<? extends android.app.Activity> origin, boolean rotatePending) throws Exception {
        assertTrue(feature.store.peers(false).isEmpty()); assertTrue(feature.store.invitations(System.currentTimeMillis()).isEmpty());
        main.edit().putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.DOM_ONLY.name()).commit();
        assertTrue(ControllerPinManager.setPin(context, "2468")); assertTrue(ControllerPinManager.verify(context, "2468")); ControllerPinManager.enterDomMode();
        com.subhub.app.people.ConnectionUi prior = People.connectionUi(); People.installConnectionUi(new RemoteUi());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean gateExpired=new java.util.concurrent.atomic.AtomicBoolean();
        java.lang.reflect.Field operationField=RemoteFeature.class.getDeclaredField("operations");operationField.setAccessible(true);
        ThreadPoolExecutor originalOperations=(ThreadPoolExecutor)operationField.get(feature);
        ThreadPoolExecutor gatedOperations=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->new Thread(r,"owned-creation-gate"),new ThreadPoolExecutor.AbortPolicy()){
            @Override public void execute(Runnable command){
                boolean creation=false;
                for(StackTraceElement frame:Thread.currentThread().getStackTrace())if(frame.getClassName().equals(RemoteFeature.class.getName())&&frame.getMethodName().equals("ensureInvitation")){creation=true;break;}
                if(!creation){super.execute(command);return;}
                // Gate the accepted task before its authority/SQLite lock, so recreation can read safely.
                super.execute(()->{entered.countDown();try{if(!release.await(90,TimeUnit.SECONDS))gateExpired.set(true);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();gateExpired.set(true);}command.run();});
            }
        };
        if(rotatePending)operationField.set(feature,gatedOperations);else gatedOperations.shutdown();
        String created = null;
        androidx.test.espresso.intent.Intents.init();
        try {
            try (ActivityScenario<?> page = ActivityScenario.launch(new Intent(context, origin))) {
                await(() -> resumed(origin) != null);
                clickAddSub();
                await(() -> resumed(PairingActivity.class) != null); PairingActivity initial = resumed(PairingActivity.class);
                assertEquals(context.getPackageName(), initial.getCallingActivity().getPackageName());
                assertEquals(origin.getName(), initial.getCallingActivity().getClassName());
                if (rotatePending) {
                    assertTrue("Accepted creation must reach its task gate",entered.await(5,TimeUnit.SECONDS));
                    await(() -> creation(initial) != null); CompletableFuture<PairingInvite> accepted = creation(initial);
                    InstrumentationRegistry.getInstrumentation().runOnMainSync(initial::recreate);
                    await(() -> resumed(PairingActivity.class) != null && resumed(PairingActivity.class) != initial);
                    assertSame(accepted, creation(resumed(PairingActivity.class)));
                    assertFalse("Creation must remain pending behind the fixture gate", accepted.isDone());
                    assertEquals(1, release.getCount());
                    assertFalse("Fixture gate timed out",gateExpired.get());
                    assertTrue(feature.store.invitations(System.currentTimeMillis()).isEmpty());
                    release.countDown();
                    accepted.get(15,TimeUnit.SECONDS);
                }
                await(() -> resumed(PairingActivity.class) != null && inviteUnchecked(resumed(PairingActivity.class)) != null);
                PairingActivity ready = resumed(PairingActivity.class); PairingInvite invitation = inviteUnchecked(ready); created = invitation.id;
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    assertNull(find(ready.body, context.getString(R.string.remote_create_invite)));
                    assertNotNull(find(ready.body, context.getString(R.string.remote_copy_invite)));
                    assertNotNull(find(ready.body, context.getString(R.string.remote_share_invite)));
                    assertTrue((ready.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                });
                List<RemoteStore.Invitation> invitations = feature.store.invitations(System.currentTimeMillis()); assertEquals(1, invitations.size());
                assertFalse(invitations.get(0).approved); assertTrue(invitations.get(0).candidate == null || invitations.get(0).candidate.isEmpty());
                assertTrue(feature.store.peers(false).isEmpty());
                for (Intent intent : androidx.test.espresso.intent.Intents.getIntents())
                    assertFalse(Intent.ACTION_SEND.equals(intent.getAction()) || Intent.ACTION_CHOOSER.equals(intent.getAction()));
                androidx.test.espresso.Espresso.pressBack(); await(() -> resumed(origin) != null);
                assertTrue(feature.store.peers(false).isEmpty());
            }
        } finally {
            release.countDown();
            feature.submit(() -> null).get(15, TimeUnit.SECONDS);
            // The isolated fixture began without invitations; reconcile accepted creation even after a failed observer.
            for (RemoteStore.Invitation owned : feature.store.invitations(System.currentTimeMillis())) feature.cancel(owned.id);
            feature.submit(() -> null).get(15, TimeUnit.SECONDS);
            if(rotatePending){operationField.set(feature,originalOperations);gatedOperations.shutdown();assertTrue(gatedOperations.awaitTermination(15,TimeUnit.SECONDS));}
            androidx.test.espresso.intent.Intents.release(); People.installConnectionUi(prior);
        }
    }
    @Test public void untrustedDirectExtraKeepsTheExplicitInvitationAction() throws Exception {
        assertTrue(ControllerPinManager.setPin(context, "2468")); ControllerPinManager.enterDomMode();
        try (ActivityScenario<PairingActivity> page = ActivityScenario.launch(new Intent(context, PairingActivity.class)
                .putExtra("asDom", true).putExtra(PairingActivity.EXTRA_DIRECT_INVITATION, true))) {
            page.onActivity(activity -> {
                assertTrue(activity.getCallingActivity() == null || !context.getPackageName().equals(activity.getCallingActivity().getPackageName()));
                assertNotNull(find(activity.body, context.getString(R.string.remote_create_invite)));
                assertNull(inviteUnchecked(activity));
            });
            page.recreate(); page.onActivity(activity -> assertNull(inviteUnchecked(activity)));
            assertTrue(feature.store.invitations(System.currentTimeMillis()).isEmpty()); assertTrue(feature.store.peers(false).isEmpty());
        }
    }
    @Test public void inboundLinkCannotSelectDirectDomCreationThroughExtras() throws Exception {
        PairingInvite supplied = PairingInvite.create(other.localIdentity(), other.inviteRoute(), System.currentTimeMillis());
        try (ActivityScenario<PairingActivity> page = ActivityScenario.launch(new Intent(context, PairingActivity.class)
                .setData(android.net.Uri.parse(supplied.link())).putExtra("asDom", true)
                .putExtra(PairingActivity.EXTRA_DIRECT_INVITATION, true))) {
            page.onActivity(activity -> {
                assertNotNull(find(activity.body, context.getString(R.string.remote_approve)));
                assertNull(find(activity.body, context.getString(R.string.remote_create_invite))); assertNull(inviteUnchecked(activity));
            });
            assertTrue(feature.store.peers(false).isEmpty()); assertTrue(feature.store.invitations(System.currentTimeMillis()).isEmpty());
        }
    }
    private static CompletableFuture<PairingInvite> creation(PairingActivity activity) {
        try { java.lang.reflect.Field field = PairingActivity.class.getDeclaredField("creation"); field.setAccessible(true);
            return ((PairingActivity.InvitationCreation) field.get(activity)).request;
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
    private static <T extends android.app.Activity> T resumed(Class<T> expected) {
        AtomicReference<T> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (android.app.Activity activity : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))
                if (expected.isInstance(activity)) result.set(expected.cast(activity));
        }); return result.get();
    }

    private static PairingInvite inviteUnchecked(PairingActivity activity){try{java.lang.reflect.Field field=PairingActivity.class.getDeclaredField("invitation");field.setAccessible(true);return (PairingInvite)field.get(activity);}catch(Exception error){throw new AssertionError(error);}}
    private static PairingInvite invite(ActivityScenario<PairingActivity> page){AtomicReference<PairingInvite> result=new AtomicReference<>();page.onActivity(a->result.set(inviteUnchecked(a)));return result.get();}
    private static View find(View root,String text){if(root instanceof TextView&&text.contentEquals(((TextView)root).getText())&&root.isShown())return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=find(((ViewGroup)root).getChildAt(i),text);if(found!=null)return found;}return null;}
    private void tap(View target){assertNotNull(target);int[] point=new int[2];InstrumentationRegistry.getInstrumentation().runOnMainSync(()->target.getLocationOnScreen(point));assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).click(point[0]+target.getWidth()/2,point[1]+target.getHeight()/2));InstrumentationRegistry.getInstrumentation().waitForIdleSync();}
    private interface Check{boolean ready()throws Exception;}
    private static void await(Check check)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(!check.ready()&&System.nanoTime()<deadline)Thread.sleep(100);assertTrue(check.ready());}
    private static byte[] random(){byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);return bytes;}
    private static void restore(SharedPreferences preferences,Map<String,?> values){SharedPreferences.Editor edit=preferences.edit().clear();for(Map.Entry<String,?> entry:values.entrySet()){Object value=entry.getValue();String key=entry.getKey();if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Integer)edit.putInt(key,(Integer)value);else if(value instanceof Long)edit.putLong(key,(Long)value);else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);else if(value instanceof Float)edit.putFloat(key,(Float)value);else if(value instanceof Set)edit.putStringSet(key,new HashSet<>((Set<String>)value));else throw new AssertionError("Unsupported preference type");}assertTrue(edit.commit());}
}
