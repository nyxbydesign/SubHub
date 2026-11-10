package com.subhub.app.remote;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static com.subhub.app.NativeUiActions.revealAboveNavigation;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;
import android.app.ActivityManager;
import android.content.Context;
import android.widget.CompoundButton;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import com.subhub.app.UiAutomationStateRule;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.KeyholderPresentationAndroidTest;
import com.subhub.app.settings.GlobalSettingsActivity;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import com.subhub.app.util.AsyncUiScope;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.rules.RuleChain;

/** Native local choice in both workspaces; an owned unpaired endpoint supplies Settings queries. */
public final class BackgroundSyncSettingsAndroidTest {
    @Rule public final RuleChain rules=RuleChain.outerRule(new KeyholderPresentationAndroidTest.IntegrationFixture()).around(new UiAutomationStateRule());
    private Context context;
    private Map<String,?> originalChoice,originalNotices;
    private final Set<AsyncUiScope> readers=Collections.newSetFromMap(new IdentityHashMap<>());
    @Before public void setup()throws Exception{
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        for(ActivityManager.RunningServiceInfo service:context.getSystemService(ActivityManager.class).getRunningServices(Integer.MAX_VALUE))
            org.junit.Assume.assumeFalse("Preserve an incumbent background connection",PeerSyncForegroundService.class.getName().equals(service.service.getClassName()));
        org.junit.Assume.assumeTrue("Use an owned unpaired guest",RemoteFeature.get(context).store.peers(false).isEmpty());
        for(String name:List.of(RemoteScheduler.PERIODIC,RemoteScheduler.IMMEDIATE))
            for(WorkInfo work:WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get(5,TimeUnit.SECONDS))
                org.junit.Assume.assumeTrue("Preserve an incumbent peer worker",work.getState().isFinished());
        originalChoice=new LinkedHashMap<>(context.getSharedPreferences(BackgroundSyncPreferences.STORE,0).getAll());
        originalNotices=new LinkedHashMap<>(context.getSharedPreferences(com.subhub.app.notifications.NotificationPreferences.STORE,0).getAll());
        assertTrue(context.getSharedPreferences(BackgroundSyncPreferences.STORE,0).edit().clear().commit());
        assertTrue(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit().putString(DeviceRolePreferences.KEY_MODE,DeviceRolePreferences.Mode.BOTH.name()).commit());
        assertTrue(ControllerPinManager.setPin(context,"5729"));ControllerPinManager.enterSubMode();
    }
    @After public void restore(){
        if(originalChoice!=null)SharedPreferenceTestRestore.restore(context.getSharedPreferences(BackgroundSyncPreferences.STORE,0),originalChoice);
        if(originalNotices!=null)assertEquals(originalNotices,context.getSharedPreferences(com.subhub.app.notifications.NotificationPreferences.STORE,0).getAll());
    }
    @Test public void nativeChoiceDefaultsOnPersistsOffAndCanBeReenabledInEitherWorkspace()throws Exception{
        RemoteUnpairedUiFixture endpoint=new RemoteUnpairedUiFixture(context);
        try{
            try(ActivityScenario<GlobalSettingsActivity> page=ActivityScenario.launch(GlobalSettingsActivity.class)){
                page.onActivity(activity->{observe(activity);assertToggle(activity,true);assertFalse(ControllerPinManager.isDomModeActive());});
                onView(withTagValue(is((Object)"background_sync"))).perform(revealAboveNavigation(),click());
                assertFalse(BackgroundSyncPreferences.enabled(context));
                page.recreate();page.onActivity(activity->{observe(activity);assertToggle(activity,false);});
            }
            try(ActivityScenario<GlobalSettingsActivity> page=ActivityScenario.launch(GlobalSettingsActivity.class)){
                page.onActivity(activity->{observe(activity);assertToggle(activity,false);assertTrue(ControllerPinManager.verifyLocalDomWorkspace(context,"5729"));});
                page.recreate();page.onActivity(activity->{observe(activity);assertTrue(ControllerPinManager.isLocalDomWorkspaceUnlocked());assertToggle(activity,false);});
                onView(withTagValue(is((Object)"background_sync"))).perform(revealAboveNavigation(),click());
                assertTrue(BackgroundSyncPreferences.enabled(context));
                page.recreate();page.onActivity(activity->{observe(activity);assertToggle(activity,true);});
            }
        }finally{
            // A failed drain preserves the owned endpoint rather than exposing canonical state to unfinished readers.
            awaitReaders();endpoint.close();
        }
    }
    private void observe(GlobalSettingsActivity activity){readers.add(AsyncUiScope.forPage(activity));}
    private static void assertToggle(GlobalSettingsActivity activity,boolean checked){
        CompoundButton toggle=activity.findViewById(android.R.id.content).findViewWithTag("background_sync");
        assertNotNull(toggle);assertTrue(toggle.isEnabled());assertEquals(checked,toggle.isChecked());
    }
    private void awaitReaders()throws Exception{
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        for(String name:List.of("worker","serialWorker")){
            Field field=AsyncUiScope.class.getDeclaredField(name);field.setAccessible(true);
            for(AsyncUiScope scope:readers){ExecutorService worker=(ExecutorService)field.get(scope);if(worker!=null){assertTrue(worker.isShutdown());assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}}
        }
    }
}
