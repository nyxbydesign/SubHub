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
    private Context context;private RemoteFeature feature,previous;private PeerTransport other;
    private KeyholderProvider provider;private java.lang.reflect.Field singleton;private String database;
    private SharedPreferences main,legacy;private Map<String,?> mainBefore,legacyBefore;
    @Before public void setup()throws Exception {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        main=context.getSharedPreferences(com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME,0);legacy=context.getSharedPreferences("subhub_controller_auth",0);
        mainBefore=new HashMap<>(main.getAll());legacyBefore=new HashMap<>(legacy.getAll());
        main.edit().remove("controller_pin_salt").remove("controller_pin_hash").remove("controller_keyholder_optional").commit();legacy.edit().clear().commit();
        provider=People.keyholder();People.installKeyholder(new KeyholderProvider(){public State state(){return State.NONE;}public String assignedDomName(){return "";}public ControllerAuthenticator.Result verifyCode(String code){return ControllerAuthenticator.Result.UNAVAILABLE;}});
        ControllerPinManager.enterSubMode();database="enrollment-"+UUID.randomUUID();feature=new RemoteFeature(context,new RemoteStore(context,database+".db"));
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
    private static PairingInvite inviteUnchecked(PairingActivity activity){try{java.lang.reflect.Field field=PairingActivity.class.getDeclaredField("invitation");field.setAccessible(true);return (PairingInvite)field.get(activity);}catch(Exception error){throw new AssertionError(error);}}
    private static PairingInvite invite(ActivityScenario<PairingActivity> page){AtomicReference<PairingInvite> result=new AtomicReference<>();page.onActivity(a->result.set(inviteUnchecked(a)));return result.get();}
    private static View find(View root,String text){if(root instanceof TextView&&text.contentEquals(((TextView)root).getText())&&root.isShown())return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=find(((ViewGroup)root).getChildAt(i),text);if(found!=null)return found;}return null;}
    private void tap(View target){assertNotNull(target);int[] point=new int[2];InstrumentationRegistry.getInstrumentation().runOnMainSync(()->target.getLocationOnScreen(point));assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).click(point[0]+target.getWidth()/2,point[1]+target.getHeight()/2));InstrumentationRegistry.getInstrumentation().waitForIdleSync();}
    private interface Check{boolean ready()throws Exception;}
    private static void await(Check check)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(!check.ready()&&System.nanoTime()<deadline)Thread.sleep(100);assertTrue(check.ready());}
    private static byte[] random(){byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);return bytes;}
    private static void restore(SharedPreferences preferences,Map<String,?> values){SharedPreferences.Editor edit=preferences.edit().clear();for(Map.Entry<String,?> entry:values.entrySet()){Object value=entry.getValue();String key=entry.getKey();if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Integer)edit.putInt(key,(Integer)value);else if(value instanceof Long)edit.putLong(key,(Long)value);else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);else if(value instanceof Float)edit.putFloat(key,(Float)value);else if(value instanceof Set)edit.putStringSet(key,new HashSet<>((Set<String>)value));else throw new AssertionError("Unsupported preference type");}assertTrue(edit.commit());}
}
