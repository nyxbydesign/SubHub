package com.subhub.app.remote;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.security.SubFeaturePolicy;
import com.subhub.app.settings.SettingsRepository;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Native receiver persistence with explicit synthetic platform observations; no admin grant is made. */
@RunWith(AndroidJUnit4.class)
public class RemoteSessionConsentAndroidTest {
    private static final String DOM=repeat("a"), SELF=repeat("b"), OTHER=repeat("c"), GENERATION="consent_generation";
    private Context target,context;
    private String prefix,database;
    private RemoteStore store;
    private boolean wasDom;
    private final AtomicBoolean failCommitOnce=new AtomicBoolean();
    private final AtomicBoolean failBeforeWriteOnce=new AtomicBoolean();
    private static String repeat(String value) { return String.join("",Collections.nCopies(64,value)); }
    @Before public void setup() {
        target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefix="session-consent-"+UUID.randomUUID()+"-";database=prefix+"remote.db";
        context=new ContextWrapper(target) {
            @Override public Context getApplicationContext(){return this;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode) {
                SharedPreferences saved=target.getSharedPreferences(prefix+name,mode);
                if(!SubFeaturePolicy.STORE.equals(name))return saved;
                return (SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                        new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
                    if(method.getName().equals("edit"))return editor(saved.edit());
                    try{return method.invoke(saved,args);}catch(InvocationTargetException failed){throw failed.getCause();}
                });
            }
        };
        wasDom=ControllerPinManager.isDomModeActive();ControllerPinManager.enterSubMode();
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit()
                .putString(DeviceRolePreferences.KEY_MODE,DeviceRolePreferences.Mode.BOTH.name())
                .putBoolean(HardcoreModeManager.KEY_REQUESTED,true).commit();
        store=new RemoteStore(context,database);
        store.getWritableDatabase().execSQL("INSERT INTO peers(peer,generation,name,local_role,state) VALUES(?,?,'Synthetic Dom','SUB','ACTIVE')",
                new Object[]{DOM,GENERATION});
        SubFeaturePolicy.registerHardcoreAuthority(context,store);
    }
    private SharedPreferences.Editor editor(SharedPreferences.Editor underlying) {
        return (SharedPreferences.Editor)Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.Editor.class},(proxy,method,args)->{
            if(method.getName().equals("commit")&&failBeforeWriteOnce.getAndSet(false))return false;
            Object value;
            try{value=method.invoke(underlying,args);}catch(InvocationTargetException failed){throw failed.getCause();}
            if(method.getName().equals("commit")&&failCommitOnce.getAndSet(false))return false;
            return value==underlying?proxy:value;
        });
    }
    @After public void cleanup() {
        if(store!=null){SubFeaturePolicy.unregisterHardcoreAuthority(context,store);store.close();}
        target.deleteDatabase(database);
        for(String name:new String[]{SubFeaturePolicy.STORE,SettingsRepository.PREFERENCES_NAME})
            target.getSharedPreferences(prefix+name,0).edit().clear().commit();
        if(wasDom)ControllerPinManager.enterDomMode();else ControllerPinManager.enterSubMode();
    }
    private RemoteSessionRuntime.HardcoreReview review() throws Exception {
        return RemoteSessionRuntime.reviewHardcore(context,store,SELF,RemoteSessionClock.observe(context));
    }
    private RemoteSessionRuntime.SystemObservation syntheticAdmin(boolean active,String self) {
        return new RemoteSessionRuntime.SystemObservation(self,active,RemoteSessionClock.observe(context));
    }
    private boolean accept(RemoteSessionRuntime.HardcoreReview review) throws Exception {
        return SubFeaturePolicy.acceptHardcoreStartReview(context,store,review,syntheticAdmin(true,SELF));
    }
    private RemoteSessionRuntime.HardcoreConsent proof() { return SubFeaturePolicy.hardcoreStartConsent(context,store,SELF); }

    @Test public void legacyEnabledHardcoreNeedsExplicitReviewAndExactReceipt() throws Exception {
        assertTrue(SubFeaturePolicy.consentHardcore(context,true));assertNull(proof());
        RemoteSessionRuntime.HardcoreReview review=review();assertTrue(accept(review));
        RemoteSessionRuntime.HardcoreConsent proof=proof();assertNotNull(proof);
        assertEquals(DOM,proof.dom);assertEquals(SELF,proof.recipient);assertEquals(GENERATION,proof.generation);
        assertEquals(review.reviewId,store.metadata(DOM,GENERATION,"hardcore_start_review_ack",""));
        assertTrue(accept(review));assertEquals(proof.acceptedAt,proof().acceptedAt);
    }

    @Test public void firstEnableCommitsLocalHardcoreFlagAndScopedProofTogether() throws Exception {
        assertFalse(context.getSharedPreferences(SubFeaturePolicy.STORE,0).getBoolean("hardcore_locally_consented",false));
        assertTrue(accept(review()));
        assertTrue(context.getSharedPreferences(SubFeaturePolicy.STORE,0).getBoolean("hardcore_locally_consented",false));
        assertNotNull(proof());
    }

    @Test public void actualRequestedFlagAndNativeAdminObservationAreBothRequired() throws Exception {
        RemoteSessionRuntime.HardcoreReview review=review();
        assertFalse(SubFeaturePolicy.acceptHardcoreStartReview(context,store,review,syntheticAdmin(false,SELF)));
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit().putBoolean(HardcoreModeManager.KEY_REQUESTED,false).commit();
        assertFalse(accept(review));assertNull(proof());
    }

    @Test public void changedPolicyGenerationWorkspaceAndRecipientRefuseStaleReviews() throws Exception {
        RemoteSessionRuntime.HardcoreReview stale=review();
        assertTrue(SubFeaturePolicy.setAllowed(context,"popup",false));assertFalse(accept(stale));
        RemoteSessionRuntime.HardcoreReview next=review();
        ControllerPinManager.enterDomMode();assertFalse(accept(next));ControllerPinManager.enterSubMode();
        assertFalse(SubFeaturePolicy.acceptHardcoreStartReview(context,store,next,syntheticAdmin(true,OTHER)));
        store.revoke(DOM,GENERATION);
        store.getWritableDatabase().execSQL("INSERT INTO peers(peer,generation,name,local_role,state) VALUES(?,'new_generation','Synthetic Dom','SUB','ACTIVE')",new Object[]{DOM});
        assertFalse(accept(next));assertNull(proof());
    }

    @Test public void unrelatedFeatureChangesKeepAcceptedScopeButOffClearsItBeforeReenable() throws Exception {
        assertTrue(accept(review()));
        assertTrue(SubFeaturePolicy.setAllowed(context,"popup",false));assertNotNull(proof());
        assertTrue(SubFeaturePolicy.consentHardcore(context,false));assertNull(proof());
        assertEquals("",store.metadata(DOM,GENERATION,"hardcore_start_review_ack",""));
        assertTrue(SubFeaturePolicy.consentHardcore(context,true));assertNull(proof());
    }

    @Test public void proofReopensButPreferencesWithoutAuthorityReceiptDoNotGrant() throws Exception {
        assertTrue(accept(review()));long accepted=proof().acceptedAt;
        SubFeaturePolicy.unregisterHardcoreAuthority(context,store);store.close();
        store=new RemoteStore(context,database);SubFeaturePolicy.registerHardcoreAuthority(context,store);
        assertEquals(accepted,proof().acceptedAt);
        assertNull(SubFeaturePolicy.hardcoreStartConsent(context,store,OTHER));
        store.clearHardcoreStartAcknowledgements();assertNull(proof());
    }

    @Test public void unacknowledgedPreferenceCommitCannotCreateStartAuthority() throws Exception {
        RemoteSessionRuntime.HardcoreReview review=review();failCommitOnce.set(true);
        assertFalse(accept(review));assertNull(proof());
        assertEquals("",store.metadata(DOM,GENERATION,"hardcore_start_review_ack",""));
        assertTrue(accept(review()));assertNotNull(proof());
    }

    @Test public void failedOffPreferenceWriteStillRetiresTheAuthorityReceipt() throws Exception {
        assertTrue(accept(review()));assertNotNull(proof());
        failBeforeWriteOnce.set(true);assertFalse(SubFeaturePolicy.consentHardcore(context,false));
        assertTrue(context.getSharedPreferences(SubFeaturePolicy.STORE,0).getBoolean("hardcore_locally_consented",false));
        assertNull(proof());assertEquals("",store.metadata(DOM,GENERATION,"hardcore_start_review_ack",""));
        assertTrue(SubFeaturePolicy.consentHardcore(context,true));assertNull(proof());
    }

    @Test public void unknownStoredProofVersionIsUnavailable() throws Exception {
        assertTrue(accept(review()));SharedPreferences saved=context.getSharedPreferences(SubFeaturePolicy.STORE,0);
        JSONObject value=new JSONObject(saved.getString("hardcore_start_proof_v1",""));value.put("version",2);
        saved.edit().putString("hardcore_start_proof_v1",value.toString()).commit();assertNull(proof());
    }

    @Test public void observationThatAgesWhileWaitingForConsentCannotGrant() throws Exception {
        RemoteSessionRuntime.HardcoreReview review=review();
        java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.atomic.AtomicReference<Thread> thread=new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.Future<Boolean> result;
        try {
            synchronized(SubFeaturePolicy.LOCK) {
                RemoteSessionRuntime.SystemObservation observed=syntheticAdmin(true,SELF);
                result=worker.submit(()->{thread.set(Thread.currentThread());
                    return SubFeaturePolicy.acceptHardcoreStartReview(context,store,review,observed);});
                long deadline=android.os.SystemClock.elapsedRealtime()+2000;
                while(android.os.SystemClock.elapsedRealtime()<deadline
                        && (thread.get()==null||thread.get().getState()!=Thread.State.BLOCKED))Thread.yield();
                assertNotNull(thread.get());assertEquals(Thread.State.BLOCKED,thread.get().getState());
                android.os.SystemClock.sleep(Math.max(0,observed.clock.elapsed+5100-android.os.SystemClock.elapsedRealtime()));
            }
            assertFalse(result.get(5,java.util.concurrent.TimeUnit.SECONDS));assertNull(proof());
        } finally {worker.shutdownNow();assertTrue(worker.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));}
    }

    @Test public void noAssignedDomIsDistinctFromUnavailableAuthorityStorage() throws Exception {
        store.revoke(DOM,GENERATION);assertNull(review());
        store.getWritableDatabase().execSQL("DROP TABLE peers");
        assertThrows(android.database.sqlite.SQLiteException.class,()->review());
    }
}
