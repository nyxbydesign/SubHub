package com.subhub.app.remote;

import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.*;
import android.content.*;
import android.view.View;
import android.widget.Spinner;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.people.*;
import com.subhub.app.security.*;
import com.subhub.app.settings.SettingsRepository;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RemoteDurationAndroidTest {
    @Rule public final KeyholderPresentationAndroidTest.IntegrationFixture integration=new KeyholderPresentationAndroidTest.IntegrationFixture();
    @Rule public final com.subhub.app.UiAutomationStateRule automation=new com.subhub.app.UiAutomationStateRule();
    private Context context;private RemoteFeature feature,previous;private RemoteStore source;private RemoteStore.Peer peer;
    private String database,mode;private boolean hadMode;private java.lang.reflect.Field singleton;private JSONObject original;
    @Before public void setup()throws Exception {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();SharedPreferences prefs=context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0);
        hadMode=prefs.contains(DeviceRolePreferences.KEY_MODE);mode=prefs.getString(DeviceRolePreferences.KEY_MODE,DeviceRolePreferences.Mode.BOTH.name());
        prefs.edit().putString(DeviceRolePreferences.KEY_MODE,DeviceRolePreferences.Mode.BOTH.name()).commit();
        com.subhub.app.configuration.LegacyPackMigration.recoverAndMigrate(context);assertTrue(ControllerPinManager.setPin(context,"2468"));assertTrue(ControllerPinManager.verify(context,"2468"));ControllerPinManager.enterDomMode();
        database="inline-duration-"+UUID.randomUUID();feature=new RemoteFeature(context,new RemoteStore(context,database+".db"));source=new RemoteStore(context,database+"source.db");
        String dom=String.join("",Collections.nCopies(64,"a")),sub=String.join("",Collections.nCopies(64,"b"));
        PairingInvite invite=PairingInvite.create(dom,"fixture",1000);feature.store.saveInvite(invite);feature.store.bindInvite(invite.id,sub,invite.tokenHash(),1001);
        feature.store.approveInvite(invite.id,sub,"Fixture Sub",1001);feature.store.activate(sub,invite.id);source.acceptInvite(invite,"Dom");source.activate(dom,invite.id);peer=feature.store.peer(sub,invite.id);
        original=RemoteConfigurationAndroidTest.configuration();source.publish(dom,invite.id,RemoteProtocol.Stream.BASELINE,"active",original,false,1);feature.store.receive(sub,source.nextBatch(dom,invite.id,RemoteProtocol.Stream.BASELINE));
        singleton=RemoteFeature.class.getDeclaredField("instance");singleton.setAccessible(true);previous=(RemoteFeature)singleton.get(null);singleton.set(null,feature);
        People.installRemoteRepository(new RemotePersonRepository(context,feature));People.installConnectionUi(new RemoteUi());People.installRemoteAuthority(feature::controlsLocal);
    }
    @After public void cleanup()throws Exception {
        if(singleton!=null)singleton.set(null,previous);if(feature!=null)feature.shutdownForTest();if(source!=null)source.close();
        if(database!=null){context.deleteDatabase(database+".db");context.deleteDatabase(database+"source.db");}
        if(context!=null){SharedPreferences.Editor edit=context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit();if(hadMode)edit.putString(DeviceRolePreferences.KEY_MODE,mode);else edit.remove(DeviceRolePreferences.KEY_MODE);assertTrue(edit.commit());}
        ControllerPinManager.enterSubMode();
    }
    private ActivityScenario<PersonActivity> open(){return ActivityScenario.launch(new Intent(context,PersonActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID,RemotePersonRepository.id(peer).value()));}
    @Test public void nativeInlineChoiceSurvivesRecreationAndQueuesOnlyTheSelectedPeersDuration()throws Exception {
        try(ActivityScenario<PersonActivity> page=open()) {
            ready(page);onView(withTagValue(is("remote_duration_choice"))).perform(scrollTo(),click());onData(anything()).atPosition(2).perform(click());
            page.recreate();ready(page);page.onActivity(a->assertEquals(2,((Spinner)a.findViewById(android.R.id.content).findViewWithTag("remote_duration_choice")).getSelectedItemPosition()));
            onView(withTagValue(is("remote_duration_apply"))).perform(scrollTo(),click());await(()->feature.store.latestCommand(peer.identity,peer.generation)!=null);
            RemoteStore.Command command=feature.store.latestCommand(peer.identity,peer.generation);JSONObject queued=new JSONObject(command.body).getJSONObject("configuration");
            assertEquals(3_600_000,queued.getJSONObject("service").getLong("duration"));
            assertEquals(RemoteProtocol.canonical(original.getJSONObject("apps")),RemoteProtocol.canonical(queued.getJSONObject("apps")));
            assertEquals(RemoteProtocol.canonical(original.getJSONObject("contract")),RemoteProtocol.canonical(queued.getJSONObject("contract")));
            assertEquals(RemoteStore.CommandState.QUEUED,command.state);
        }
    }
    @Test public void staleInlineApplyCannotQueueAfterDeviceCapabilityOrRelationshipRevocation()throws Exception {
        AtomicReference<View> stale=new AtomicReference<>();
        try(ActivityScenario<PersonActivity> page=open()) {
            ready(page);page.onActivity(a->stale.set(a.findViewById(android.R.id.content).findViewWithTag("remote_duration_apply")));
            context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit().putString(DeviceRolePreferences.KEY_MODE,DeviceRolePreferences.Mode.SUB_ONLY.name()).commit();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->stale.get().performClick());assertNull(feature.store.latestCommand(peer.identity,peer.generation));
        }
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit().putString(DeviceRolePreferences.KEY_MODE,DeviceRolePreferences.Mode.BOTH.name()).commit();ControllerPinManager.enterDomMode();
        try(ActivityScenario<PersonActivity> page=open()) {
            ready(page);page.onActivity(a->stale.set(a.findViewById(android.R.id.content).findViewWithTag("remote_duration_apply")));feature.store.revoke(peer.identity,peer.generation);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->stale.get().performClick());assertNull(feature.store.latestCommand(peer.identity,peer.generation));
        }
    }
    @Test @androidx.test.filters.SdkSuppress(minSdkVersion=29)
    public void inlineRemoteProfileFitsTheRequestedNativeFontAndShowsRandomBoundsInPlace()throws Exception {
        AtomicReference<PersonActivity> activity=new AtomicReference<>();
        try(ActivityScenario<PersonActivity> page=open()) {
            ready(page);page.onActivity(a->{
                activity.set(a);android.os.Bundle args=InstrumentationRegistry.getArguments();
                String font=args.getString("shell_expected_font_scale"),width=args.getString("shell_expected_width_dp");
                if(font!=null)assertEquals(Float.parseFloat(font),a.getResources().getConfiguration().fontScale,.01f);
                if(width!=null)assertEquals(Integer.parseInt(width),a.getResources().getConfiguration().screenWidthDp);
                assertNotNull(a.findViewById(android.R.id.content).findViewWithTag("remote_session_duration"));
            });
            ProfileStatsActivityAndroidTest.captureNativeProfile(activity.get(),"remote-person-top",null);
            ProfileStatsActivityAndroidTest.captureNativeProfile(activity.get(),"remote-person-duration","remote_duration_choice");
            onView(withTagValue(is("remote_duration_choice"))).perform(scrollTo(),click());onData(anything()).atPosition(1).perform(click());
            page.onActivity(a->{View root=a.findViewById(android.R.id.content);
                assertTrue(root.findViewWithTag("remote_duration_minimum").isShown());assertTrue(root.findViewWithTag("remote_duration_maximum").isShown());
                assertNull(feature.store.latestCommand(peer.identity,peer.generation));
            });
            ProfileStatsActivityAndroidTest.captureNativeProfile(activity.get(),"remote-person-random-minimum","remote_duration_minimum");
            ProfileStatsActivityAndroidTest.captureNativeProfile(activity.get(),"remote-person-random-apply","remote_duration_apply");
        }
    }
    private static void ready(ActivityScenario<PersonActivity> page)throws Exception {await(()->{AtomicReference<Boolean> ready=new AtomicReference<>(false);page.onActivity(a->ready.set(a.findViewById(android.R.id.content).findViewWithTag("remote_duration_apply")!=null));return ready.get();});}
    private interface Check{boolean ready()throws Exception;}
    private static void await(Check check)throws Exception{long deadline=android.os.SystemClock.elapsedRealtime()+15_000;while(android.os.SystemClock.elapsedRealtime()<deadline){if(check.ready())return;Thread.sleep(50);}assertTrue(check.ready());}
}
