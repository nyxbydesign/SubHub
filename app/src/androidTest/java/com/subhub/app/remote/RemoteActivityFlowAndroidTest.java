package com.subhub.app.remote;

import static org.junit.Assert.*;
import android.app.NotificationManager;
import android.content.*;
import android.database.Cursor;
import android.os.*;
import android.service.notification.StatusBarNotification;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.activity.*;
import com.subhub.app.appmode.*;
import com.subhub.app.penance.*;
import com.subhub.app.people.*;
import com.subhub.app.security.*;
import com.subhub.app.settings.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

/** Real transport and installed Accessibility producer on two explicitly selected owned guests. */
@RunWith(AndroidJUnit4.class)
public final class RemoteActivityFlowAndroidTest {
    private Context context;private RemoteFeature feature;private File directory;
    private final Map<String,Map<String,?>> preferences=new LinkedHashMap<>();
    private static final String SELECTED="com.android.chrome";
    @Before public void setup()throws Exception {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("true",InstrumentationRegistry.getArguments().getString("activity_flow_fixture"));
        assertTrue(Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"));
        String run=InstrumentationRegistry.getArguments().getString("flow_run");UUID.fromString(run);
        directory=new File(context.getFilesDir(),"remote-activity-flow/"+run);assertTrue(directory.isDirectory()||directory.mkdirs());
        feature=RemoteFeature.get(context);assertTrue(feature.recoveryReady());
        if(People.keyholder().state()==KeyholderProvider.State.READY) {
            assertNotNull(active(RemoteProtocol.Role.SUB));ControllerPinManager.enterSubMode();
        } else {
            assertEquals(KeyholderProvider.State.NONE,People.keyholder().state());
            preferences.put(SettingsRepository.PREFERENCES_NAME,new HashMap<>(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).getAll()));
            assertTrue(ControllerPinManager.setPin(context,"2468"));
            assertTrue(ControllerPinManager.verify(context,"2468"));ControllerPinManager.enterDomMode();
        }
        assertTrue(DeviceRolePreferences.supportsDom(context));assertTrue(DeviceRolePreferences.supportsSub(context));
    }
    @After public void restoreSourcePreferences() {
        for(Map.Entry<String,Map<String,?>> saved:preferences.entrySet()) {
            SharedPreferences.Editor edit=context.getSharedPreferences(saved.getKey(),0).edit().clear();
            for(Map.Entry<String,?> value:saved.getValue().entrySet()) {
                Object item=value.getValue();String key=value.getKey();
                if(item instanceof Boolean)edit.putBoolean(key,(Boolean)item);else if(item instanceof Integer)edit.putInt(key,(Integer)item);
                else if(item instanceof Long)edit.putLong(key,(Long)item);else if(item instanceof Float)edit.putFloat(key,(Float)item);
                else if(item instanceof String)edit.putString(key,(String)item);else if(item instanceof Set)edit.putStringSet(key,new HashSet<>((Set<String>)item));
                else throw new AssertionError("Unsupported preference type");
            }
            assertTrue(edit.commit());
        }
    }
    @Test public void domPairsAndReceivesActualForegroundTributeAndLimitFacts()throws Exception {
        assertTrue(feature.store.peers(false).isEmpty());
        try(ActivityScenario<PairingActivity> page=ActivityScenario.launch(new Intent(context,PairingActivity.class).putExtra("asDom",true))) {
            for(RemoteStore.Invitation old:feature.store.invitations(System.currentTimeMillis()))feature.cancel(old.id);
            PairingInvite invite=feature.createInvite().get(30,TimeUnit.SECONDS);write("invite",invite.link());
            await(()->candidate(invite.id)!=null,90_000);RemoteStore.Invitation candidate=candidate(invite.id);
            feature.approve(invite.id,candidate.candidate,PairingInvite.fingerprint(invite.domIdentity,candidate.candidate,invite.id)).get(30,TimeUnit.SECONDS);
            await(()->active(RemoteProtocol.Role.DOM)!=null,90_000);RemoteStore.Peer sub=active(RemoteProtocol.Role.DOM);
            write("generation",sub.generation);write("dom-state","paired");
            await(()->kinds(sub).containsAll(EnumSet.of(ActivityEvent.Kind.APP_OPENED,ActivityEvent.Kind.TRIBUTE_TRIGGERED,ActivityEvent.Kind.APP_LIMIT_REACHED)),120_000);
            List<ActivityEntry> entries=feature.activity.recent(RemotePersonRepository.id(sub),100);assertEquals(3,entries.size());
            for(ActivityEntry entry:entries) {
                assertTrue(entry.unread);assertEquals(RemotePersonRepository.id(sub),entry.personId);
                if(entry.event.kind==ActivityEvent.Kind.TRIBUTE_TRIGGERED){assertEquals(123,entry.event.amountCents);assertEquals("watched_app_open",entry.event.reason);}
                if(entry.event.kind==ActivityEvent.Kind.APP_LIMIT_REACHED)assertEquals(1,entry.event.limitMinutes);
            }
            assertTrue(ActivityNotifications.enabled(context));await(()->notificationCount(sub)==3,10_000);
            await(()->notifiedCount(sub)==3,10_000);
            JSONObject posts=new JSONObject();for(StatusBarNotification posted:notifications(sub))posts.put(posted.getTag(),posted.getPostTime());
            write("initial-notification-times",posts.toString());
            JSONArray keys=new JSONArray();for(ActivityEntry entry:entries)keys.put(entry.key);write("initial-keys",keys.toString());
            write("dom-state","online-received");
        }
    }
    @Test @SdkSuppress(minSdkVersion=30)
    public void subProducesLiveFactsThenQueuesARealOfflineReentry()throws Exception {
        assertTrue(feature.store.peers(false).isEmpty());
        assertFalse(com.subhub.app.censor.android.ProductCensorQualification.enabled(context));
        assertFalse(com.subhub.app.diagnostics.CensorLabCensorSession.isActive());
        for(String name:List.of(SettingsRepository.PREFERENCES_NAME,PenanceManager.PREFS_NAME,"subhub_app_timer_usage"))
            preferences.putIfAbsent(name,new HashMap<>(context.getSharedPreferences(name,0).getAll()));
        try(ActivityScenario<PairingActivity> page=ActivityScenario.launch(new Intent(context,PairingActivity.class).putExtra("asDom",false))) {
            await(()->!read("invite").isEmpty(),60_000);
            RemoteFeature.Enrollment enrollment=feature.prepareEnrollment(read("invite")).get(30,TimeUnit.SECONDS);
            feature.confirmEnrollment(enrollment).get(30,TimeUnit.SECONDS);await(()->active(RemoteProtocol.Role.SUB)!=null,90_000);
            RemoteStore.Peer dom=active(RemoteProtocol.Role.SUB);write("generation",dom.generation);
            AppModeManager mode=new AppModeManager(context);mode.setAllAppsEnabled(false);mode.saveIncludedPackages(Collections.singleton(SELECTED));
            new FeatureModuleManager(context).save(false,true,true,false);
            AppTimerManager timers=new AppTimerManager(context);timers.saveSettings(true,1,false,60);
            timers.saveAllowances(Collections.singleton(SELECTED),Collections.singletonMap(SELECTED,1));
            Map<PenanceInfraction,Integer> rules=new EnumMap<>(PenanceInfraction.class);rules.put(PenanceInfraction.WATCHED_APP_OPEN,123);
            new PenanceManager(context).configure(true,rules,1000,5000,10,10,1);
            timers.recordUsage(SELECTED,60_000,Collections.singleton(SELECTED),System.currentTimeMillis());mode.setArmed(true);
            write("sub-state","enable-live-service");await(()->"service-enabled".equals(read("host-state")),30_000);
            await(()->"com.subhub.app".equals(ForegroundAppState.snapshot().packageName),20_000);
            launchSelected();await(()->sourceKinds(dom).containsAll(EnumSet.of(ActivityEvent.Kind.APP_OPENED,ActivityEvent.Kind.TRIBUTE_TRIGGERED,ActivityEvent.Kind.APP_LIMIT_REACHED)),30_000);
            assertEquals(3,sourceCount(dom));write("sub-state","online-produced");
            await(()->"dom-offline".equals(read("host-state")),120_000);
            timers.saveSettings(false,1,false,60);new PenanceManager(context).configure(false,rules,1000,5000,10,10,1);
            context.startActivity(new Intent(context,RemoteConnectionActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(PersonRoutes.EXTRA_PERSON_ID,RemotePersonRepository.id(dom).value()));
            await(()->"com.subhub.app".equals(ForegroundAppState.snapshot().packageName),20_000);
            launchSelected();await(()->sourceCount(dom)==4,30_000);write("sub-state","offline-queued");
            await(()->feature.store.peer(dom.identity,dom.generation).state==RemoteStore.State.REVOKED,120_000);
            assertEquals(4,sourceCount(dom));assertEquals(KeyholderProvider.State.NONE,People.keyholder().state());
            write("sub-state","revoked");await(()->"complete".equals(read("dom-state")),100_000);write("sub-state","complete");
        }
    }
    @Test public void domResumesEventReceiptsAndRevokesTheirTargets()throws Exception {
        RemoteStore.Peer sub=active(RemoteProtocol.Role.DOM);assertNotNull(sub);assertEquals(read("generation"),sub.generation);
        try(ActivityScenario<RemoteConnectionActivity> page=ActivityScenario.launch(new Intent(context,RemoteConnectionActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID,RemotePersonRepository.id(sub).value()))) {
            JSONArray initial=new JSONArray(read("initial-keys"));int expected=initial.length()+1;
            feature.changed();await(()->feature.activity.recent(RemotePersonRepository.id(sub),100).size()==expected,120_000);
            List<ActivityEntry> entries=feature.activity.recent(RemotePersonRepository.id(sub),100);Set<String> keys=new HashSet<>();
            for(ActivityEntry entry:entries){assertTrue(entry.unread);assertTrue(keys.add(entry.key));}
            for(int i=0;i<initial.length();i++)assertTrue(keys.contains(initial.getString(i)));
            await(()->notifiedCount(sub)==expected,10_000);
            JSONObject originalPosts=new JSONObject(read("initial-notification-times"));String newKey=null;
            for(String key:keys){boolean old=false;for(int i=0;i<initial.length();i++)if(key.equals(initial.getString(i)))old=true;if(!old)newKey=key;}
            assertNotNull(newKey);final String deliveredKey=newKey;
            await(()->{for(StatusBarNotification item:notifications(sub))if(item.getTag().endsWith(deliveredKey))return true;return false;},10_000);
            int retained=0;for(StatusBarNotification item:notifications(sub))if(!item.getTag().endsWith(deliveredKey)) {
                assertTrue(originalPosts.has(item.getTag()));assertEquals("Old unread notifications must never be reposted",originalPosts.getLong(item.getTag()),item.getPostTime());retained++;
            }
            write("restart-notification-witness",new JSONObject().put("oldDurableReceipts",initial.length()).put("oldPostsRetainedByOs",retained)
                    .put("newPost",1).put("oldPostTimesUnchanged",true).toString());
            feature.activity.markRead(new ArrayList<>(keys));
            await(()->notificationCount(sub)==0,10_000);assertEquals(0,feature.activity.unreadCount());
            feature.changed();assertEquals(expected,feature.activity.recent(RemotePersonRepository.id(sub),100).size());
            feature.revoke(sub);await(()->"true".equals(feature.store.metadata(sub.identity,sub.generation,"revocation_delivered","")),100_000);
            assertTrue(feature.activity.recent(RemotePersonRepository.id(sub),100).isEmpty());assertEquals(0,notificationCount(sub));
            write("dom-state","complete");
        }
    }
    @Test public void domContinuesThePairedSourceEpisodeWithoutRepeatingApproval()throws Exception {
        RemoteStore.Peer sub=active(RemoteProtocol.Role.DOM);assertNotNull(sub);assertEquals(read("generation"),sub.generation);
        try(ActivityScenario<RemoteConnectionActivity> page=ActivityScenario.launch(new Intent(context,RemoteConnectionActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID,RemotePersonRepository.id(sub).value()))) {
            write("dom-state","paired-resume");feature.changed();await(()->!read("episode-events").isEmpty()&&read("episode-count").matches("[0-9]+"),120_000);
            JSONArray sourceEvents=new JSONArray(read("episode-events"));assertEquals(3,sourceEvents.length());Set<String> ids=new HashSet<>();
            for(int i=0;i<sourceEvents.length();i++)assertTrue(ids.add(sourceEvents.getString(i)));
            int expected=Integer.parseInt(read("episode-count"));assertTrue(expected>=3&&expected<100);
            await(()->feature.activity.recent(RemotePersonRepository.id(sub),100).size()==expected,120_000);
            List<ActivityEntry> entries=feature.activity.recent(RemotePersonRepository.id(sub),100);Set<ActivityEvent.Kind> episodeKinds=EnumSet.noneOf(ActivityEvent.Kind.class);int episodeCount=0;
            for(ActivityEntry entry:entries) {
                assertTrue(entry.unread);
                if(ids.contains(entry.event.id)) {
                    episodeCount++;assertTrue(episodeKinds.add(entry.event.kind));
                    if(entry.event.kind==ActivityEvent.Kind.TRIBUTE_TRIGGERED){assertEquals(123,entry.event.amountCents);assertEquals("watched_app_open",entry.event.reason);}
                    if(entry.event.kind==ActivityEvent.Kind.APP_LIMIT_REACHED)assertEquals(2,entry.event.limitMinutes);
                    await(()->{for(StatusBarNotification item:notifications(sub))if(item.getTag().endsWith(entry.key))return true;return false;},10_000);
                }
            }
            assertEquals(3,episodeCount);assertEquals(EnumSet.of(ActivityEvent.Kind.APP_OPENED,ActivityEvent.Kind.TRIBUTE_TRIGGERED,ActivityEvent.Kind.APP_LIMIT_REACHED),episodeKinds);
            await(()->notifiedCount(sub)==expected,10_000);
            JSONObject posts=new JSONObject();for(StatusBarNotification posted:notifications(sub))posts.put(posted.getTag(),posted.getPostTime());write("initial-notification-times",posts.toString());
            JSONArray keys=new JSONArray();for(ActivityEntry entry:entries)keys.put(entry.key);write("initial-keys",keys.toString());write("dom-state","online-received");
        }
    }
    @Test @SdkSuppress(minSdkVersion=30)
    public void subContinuesTheLiveEpisodeWithoutReplacingItsGeneration()throws Exception {
        RemoteStore.Peer dom=active(RemoteProtocol.Role.SUB);assertNotNull(dom);assertEquals(read("generation"),dom.generation);
        assertFalse(com.subhub.app.censor.android.ProductCensorQualification.enabled(context));assertFalse(com.subhub.app.diagnostics.CensorLabCensorSession.isActive());
        for(String name:List.of(SettingsRepository.PREFERENCES_NAME,PenanceManager.PREFS_NAME,"subhub_app_timer_usage"))
            preferences.putIfAbsent(name,new HashMap<>(context.getSharedPreferences(name,0).getAll()));
        try(ActivityScenario<RemoteConnectionActivity> page=ActivityScenario.launch(new Intent(context,RemoteConnectionActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID,RemotePersonRepository.id(dom).value()))) {
            assertTrue("An already recorded episode must be continued without reproducing it",read("episode-witness").isEmpty());
            int prior=sourceCount(dom);assertTrue(prior<=96);long boundary=sourceBoundary(dom);
            Set<String> priorLedger=new HashSet<>();for(PenanceEvent event:new PenanceManager(context).durableActivityEvents())priorLedger.add(event.getId());
            AppModeManager mode=new AppModeManager(context);mode.setAllAppsEnabled(false);mode.saveIncludedPackages(Collections.singleton(SELECTED));
            new FeatureModuleManager(context).save(false,true,true,false);AppTimerManager timers=new AppTimerManager(context);timers.saveSettings(true,2,false,60);
            timers.saveAllowances(Collections.singleton(SELECTED),Collections.singletonMap(SELECTED,2));Map<PenanceInfraction,Integer> rules=new EnumMap<>(PenanceInfraction.class);rules.put(PenanceInfraction.WATCHED_APP_OPEN,123);
            new PenanceManager(context).configure(true,rules,1000,5000,10,10,1);timers.recordUsage(SELECTED,120_000,Collections.singleton(SELECTED),System.currentTimeMillis());mode.setArmed(true);
            write("sub-state","enable-live-service");await(()->"service-enabled".equals(read("host-state")),30_000);await(()->"com.subhub.app".equals(ForegroundAppState.snapshot().packageName),20_000);
            launchSelected();await(()->sourceCount(dom)>=prior+3,30_000);
            android.content.pm.ResolveInfo home=context.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0);assertNotNull(home);
            await(()->home.activityInfo.packageName.equals(ForegroundAppState.snapshot().packageName),20_000);drainActivity();
            assertEquals(prior+3,sourceCount(dom));List<ActivityEvent> episode=sourceAfter(dom,boundary);assertEquals(3,episode.size());
            Set<ActivityEvent.Kind> kinds=EnumSet.noneOf(ActivityEvent.Kind.class);JSONArray ids=new JSONArray();for(ActivityEvent event:episode){assertTrue(kinds.add(event.kind));ids.put(event.id);}
            assertEquals(EnumSet.of(ActivityEvent.Kind.APP_OPENED,ActivityEvent.Kind.TRIBUTE_TRIGGERED,ActivityEvent.Kind.APP_LIMIT_REACHED),kinds);
            int assessed=0;for(PenanceEvent event:new PenanceManager(context).durableActivityEvents())if(!priorLedger.contains(event.getId()))assessed++;assertEquals(1,assessed);
            write("episode-events",ids.toString());write("episode-count",Integer.toString(prior+3));write("episode-witness",new JSONObject().put("preservedPriorFacts",prior).put("newFacts",3)
                    .put("newAssessments",assessed).put("limitMinutes",2).put("observedHome",home.activityInfo.packageName).toString());write("sub-state","online-produced");
            await(()->"dom-offline".equals(read("host-state")),120_000);timers.saveSettings(false,2,false,60);new PenanceManager(context).configure(false,rules,1000,5000,10,10,1);
            context.startActivity(new Intent(context,RemoteConnectionActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(PersonRoutes.EXTRA_PERSON_ID,RemotePersonRepository.id(dom).value()));
            await(()->"com.subhub.app".equals(ForegroundAppState.snapshot().packageName),20_000);launchSelected();await(()->sourceCount(dom)==prior+4,30_000);write("sub-state","offline-queued");
            await(()->feature.store.peer(dom.identity,dom.generation).state==RemoteStore.State.REVOKED,120_000);assertEquals(prior+4,sourceCount(dom));assertEquals(KeyholderProvider.State.NONE,People.keyholder().state());
            write("sub-state","revoked");await(()->"complete".equals(read("dom-state")),100_000);write("sub-state","complete");
        }
    }
    @Test @SdkSuppress(minSdkVersion=30)
    public void subResumesOnlyTheOfflineReentryAfterTheRecordedLiveEpisode()throws Exception {
        RemoteStore.Peer dom=active(RemoteProtocol.Role.SUB);assertNotNull(dom);assertEquals(read("generation"),dom.generation);
        int expected=Integer.parseInt(read("episode-count"));assertTrue(expected>=3&&expected<100);assertEquals(expected,sourceCount(dom));
        JSONObject witnessed=new JSONObject(read("episode-witness"));assertEquals(3,witnessed.getInt("newFacts"));assertEquals(1,witnessed.getInt("newAssessments"));assertEquals(2,witnessed.getInt("limitMinutes"));
        assertEquals(expected,witnessed.getInt("preservedPriorFacts")+witnessed.getInt("newFacts"));
        JSONArray ids=new JSONArray(read("episode-events"));assertEquals(3,ids.length());
        synchronized(feature.store){for(int i=0;i<ids.length();i++)try(Cursor row=feature.store.getReadableDatabase().rawQuery("SELECT body FROM activity_source WHERE peer=? AND generation=? AND event_id=?",new String[]{dom.identity,dom.generation,ids.getString(i)})){
            assertTrue(row.moveToFirst());RemoteActivityWire.decode(new JSONObject(com.subhub.app.storage.StoredPayloads.read(row,0)));assertFalse(row.moveToNext());
        }}
        for(String name:List.of(SettingsRepository.PREFERENCES_NAME,PenanceManager.PREFS_NAME,"subhub_app_timer_usage"))preferences.putIfAbsent(name,new HashMap<>(context.getSharedPreferences(name,0).getAll()));
        try(ActivityScenario<RemoteConnectionActivity> page=ActivityScenario.launch(new Intent(context,RemoteConnectionActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID,RemotePersonRepository.id(dom).value()))) {
            AppModeManager mode=new AppModeManager(context);mode.setAllAppsEnabled(false);mode.saveIncludedPackages(Collections.singleton(SELECTED));new FeatureModuleManager(context).save(false,true,true,false);
            new AppTimerManager(context).saveSettings(false,2,false,60);Map<PenanceInfraction,Integer> rules=new EnumMap<>(PenanceInfraction.class);rules.put(PenanceInfraction.WATCHED_APP_OPEN,123);
            new PenanceManager(context).configure(false,rules,1000,5000,10,10,1);mode.setArmed(true);feature.changed();
            write("sub-state","enable-offline-service");await(()->"service-enabled".equals(read("host-state")),30_000);await(()->"com.subhub.app".equals(ForegroundAppState.snapshot().packageName),20_000);
            await(()->"dom-offline".equals(read("host-state")),120_000);launchSelected();await(()->sourceCount(dom)==expected+1,30_000);write("sub-state","offline-queued");
            await(()->feature.store.peer(dom.identity,dom.generation).state==RemoteStore.State.REVOKED,120_000);assertEquals(expected+1,sourceCount(dom));assertEquals(KeyholderProvider.State.NONE,People.keyholder().state());
            write("sub-state","revoked");await(()->"complete".equals(read("dom-state")),100_000);write("sub-state","complete");
        }
    }
    @Test public void subKeepsThePersistedOutboxAvailableThroughRevocation()throws Exception {
        RemoteStore.Peer dom=active(RemoteProtocol.Role.SUB);assertNotNull(dom);assertEquals(read("generation"),dom.generation);
        int expected=Integer.parseInt(read("episode-count"))+1;assertEquals(expected,sourceCount(dom));
        try(ActivityScenario<RemoteConnectionActivity> page=ActivityScenario.launch(new Intent(context,RemoteConnectionActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID,RemotePersonRepository.id(dom).value()))) {
            feature.changed();write("sub-state","awaiting-delivery");
            await(()->feature.store.peer(dom.identity,dom.generation).state==RemoteStore.State.REVOKED,120_000);
            assertEquals(expected,sourceCount(dom));assertEquals(KeyholderProvider.State.NONE,People.keyholder().state());
            write("sub-state","revoked");await(()->"complete".equals(read("dom-state")),100_000);write("sub-state","complete");
        }
    }
    @Test @SdkSuppress(minSdkVersion=29)
    public void domCompletesArtworkOnTheAlreadyPairedRelationship()throws Exception {
        RemoteStore.Peer sub=active(RemoteProtocol.Role.DOM);assertNotNull(sub);assertEquals(read("generation"),sub.generation);
        try(ActivityScenario<RemoteConnectionActivity> page=ActivityScenario.launch(new Intent(context,RemoteConnectionActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID,RemotePersonRepository.id(sub).value()))) {
            feature.changed();await(()->"artwork-ready".equals(read("sub-state")),30_000);
            await(()->new RemotePersonRepository(context,feature).load(RemotePersonRepository.id(sub)).sync.contract==PersonSnapshot.Freshness.CURRENT,90_000);
            applyArtworkFromProfile(sub);await(()->"artwork-verified".equals(read("sub-state")),90_000);
            feature.revoke(sub);await(()->"true".equals(feature.store.metadata(sub.identity,sub.generation,"revocation_delivered","")),100_000);
            write("dom-state","complete");
        }
    }
    @Test public void subVerifiesArtworkOnTheAlreadyPairedRelationship()throws Exception {
        RemoteStore.Peer dom=active(RemoteProtocol.Role.SUB);assertNotNull(dom);assertEquals(read("generation"),dom.generation);
        try(ActivityScenario<RemoteConnectionActivity> page=ActivityScenario.launch(new Intent(context,RemoteConnectionActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID,RemotePersonRepository.id(dom).value()))) {
            feature.changed();write("sub-state","artwork-ready");
            await(()->read("artwork-hash").matches("[0-9a-f]{64}"),120_000);String hash=read("artwork-hash");
            await(()->Long.parseLong(feature.store.metadata(dom.identity,dom.generation,"applied_revision","0"))>0,120_000);
            assertAppliedBytes(hash);write("sub-state","artwork-verified");
            await(()->feature.store.peer(dom.identity,dom.generation).state==RemoteStore.State.REVOKED,120_000);
            assertAppliedBytes(hash);assertEquals(KeyholderProvider.State.NONE,People.keyholder().state());
            write("sub-state","revoked");await(()->"complete".equals(read("dom-state")),100_000);write("sub-state","complete");
        }
    }
    private void assertAppliedBytes(String expected)throws Exception {
        boolean matched=false;for(File image:new com.subhub.app.capture.CustomImageManager(context).enabledConfigurationFiles())
            if(expected.equals(RemoteAssets.digest(image)))matched=true;
        assertTrue("Applied image points at the verified received bytes",matched);
    }
    @androidx.annotation.RequiresApi(29)
    private void applyArtworkFromProfile(RemoteStore.Peer sub)throws Exception {
        String name="subhub-activity-"+directory.getName()+".png";
        android.content.ContentValues values=new android.content.ContentValues();
        values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,name);values.put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/png");
        values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,"Pictures/");
        android.net.Uri image=context.getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);assertNotNull(image);
        try {
            ByteArrayOutputStream encoded=new ByteArrayOutputStream();android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(48,48,android.graphics.Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(0xff000000|(directory.getName().hashCode()&0x00ffffff));assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,encoded));bitmap.recycle();
            try(OutputStream output=context.getContentResolver().openOutputStream(image)){assertNotNull(output);output.write(encoded.toByteArray());}
            String hash=RemoteProtocol.sha256(encoded.toByteArray());write("artwork-hash",hash);
            JSONArray baselineAssets=feature.contracts.current(sub).getJSONObject("contract").getJSONArray("assets");int original=baselineAssets.length();
            for(int index=0;index<original;index++)assertNotEquals("Fixture must require new image bytes",hash,baselineAssets.getJSONObject(index).getString("hash"));
            try(ActivityScenario<PersonActivity> profile=ActivityScenario.launch(new Intent(context,PersonActivity.class)
                    .putExtra(PersonRoutes.EXTRA_PERSON_ID,RemotePersonRepository.id(sub).value()))) {
                await(()->hasTag(profile,"person_tab:contracts"),15_000);
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withTagValue(org.hamcrest.Matchers.is("person_tab:contracts")))
                        .perform(androidx.test.espresso.action.ViewActions.scrollTo(),androidx.test.espresso.action.ViewActions.click());
                await(()->hasTag(profile,"person_section:censor"),15_000);
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withTagValue(org.hamcrest.Matchers.is("person_section:censor")))
                        .perform(androidx.test.espresso.action.ViewActions.scrollTo(),androidx.test.espresso.action.ViewActions.click());
                await(()->resumedEditor()!=null,15_000);
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(com.subhub.app.R.string.remote_add_censor_image))
                        .perform(androidx.test.espresso.action.ViewActions.scrollTo(),androidx.test.espresso.action.ViewActions.click());
                androidx.test.uiautomator.UiDevice device=androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
                assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.desc("Show roots")),10_000));
                device.findObject(androidx.test.uiautomator.By.desc("Show roots")).click();
                assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.res("android:id/title").text("Recent")),10_000));
                device.findObject(androidx.test.uiautomator.By.res("android:id/title").text("Recent")).click();
                assertTrue(device.wait(androidx.test.uiautomator.Until.gone(androidx.test.uiautomator.By.text("Open from")),10_000));
                androidx.test.uiautomator.BySelector tile=androidx.test.uiautomator.By.descStartsWith(name+", ");
                assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(tile),15_000));device.findObject(tile).click();
                assertTrue(device.wait(androidx.test.uiautomator.Until.gone(androidx.test.uiautomator.By.pkg("com.google.android.documentsui").depth(0)),10_000));
                await(()->{RemoteContractActivity editor=resumedEditor();if(editor==null)return false;
                    java.util.concurrent.atomic.AtomicBoolean ready=new java.util.concurrent.atomic.AtomicBoolean();
                    InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{java.lang.reflect.Field draft=RemoteContractActivity.class.getDeclaredField("draft");draft.setAccessible(true);JSONObject doc=(JSONObject)draft.get(editor);ready.set(doc!=null&&doc.getJSONObject("contract").getJSONArray("assets").length()==original+1);}catch(Exception failure){throw new AssertionError(failure);}});return ready.get();},20_000);
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(com.subhub.app.R.string.remote_apply))
                        .perform(androidx.test.espresso.action.ViewActions.scrollTo(),androidx.test.espresso.action.ViewActions.click());
                await(()->{RemoteStore.Command command=feature.store.latestCommand(sub.identity,sub.generation);return command!=null&&command.state==RemoteStore.CommandState.APPLIED;},120_000);
                RemoteStore.Command command=feature.store.latestCommand(sub.identity,sub.generation);assertNotNull(command);
                write("artwork-receipt",new JSONObject().put("command",command.id).put("sha256",hash).put("state",command.state.name()).toString());
            }
        } finally {context.getContentResolver().delete(image,null,null);}
    }
    private boolean hasTag(ActivityScenario<PersonActivity> page,String tag) {java.util.concurrent.atomic.AtomicBoolean result=new java.util.concurrent.atomic.AtomicBoolean();page.onActivity(a->result.set(a.findViewById(android.R.id.content).findViewWithTag(tag)!=null));return result.get();}
    private RemoteContractActivity resumedEditor(){java.util.concurrent.atomic.AtomicReference<RemoteContractActivity> result=new java.util.concurrent.atomic.AtomicReference<>();InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{for(android.app.Activity activity:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(activity instanceof RemoteContractActivity)result.set((RemoteContractActivity)activity);});return result.get();}
    private void launchSelected(){Intent launch=context.getPackageManager().getLaunchIntentForPackage(SELECTED);assertNotNull(launch);context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
    private RemoteStore.Invitation candidate(String id){for(RemoteStore.Invitation item:feature.store.invitations(System.currentTimeMillis()))if(id.equals(item.id)&&item.candidate!=null)return item;return null;}
    private RemoteStore.Peer active(RemoteProtocol.Role role){for(RemoteStore.Peer peer:feature.store.peers(false))if(peer.localRole==role&&peer.state==RemoteStore.State.ACTIVE)return peer;return null;}
    private Set<ActivityEvent.Kind> kinds(RemoteStore.Peer peer)throws Exception {Set<ActivityEvent.Kind> result=EnumSet.noneOf(ActivityEvent.Kind.class);for(ActivityEntry item:feature.activity.recent(RemotePersonRepository.id(peer),100))result.add(item.event.kind);return result;}
    private Set<ActivityEvent.Kind> sourceKinds(RemoteStore.Peer peer)throws Exception {Set<ActivityEvent.Kind> result=EnumSet.noneOf(ActivityEvent.Kind.class);synchronized(feature.store){try(Cursor rows=feature.store.getReadableDatabase().rawQuery("SELECT body FROM activity_source WHERE peer=? AND generation=?",new String[]{peer.identity,peer.generation})){while(rows.moveToNext())result.add(RemoteActivityWire.decode(new JSONObject(com.subhub.app.storage.StoredPayloads.read(rows,0))).kind);}}return result;}
    private int sourceCount(RemoteStore.Peer peer){synchronized(feature.store){try(Cursor rows=feature.store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM activity_source WHERE peer=? AND generation=?",new String[]{peer.identity,peer.generation})){assertTrue(rows.moveToFirst());return rows.getInt(0);}}}
    private long sourceBoundary(RemoteStore.Peer peer){synchronized(feature.store){try(Cursor rows=feature.store.getReadableDatabase().rawQuery("SELECT MAX(id) FROM activity_source WHERE peer=? AND generation=?",new String[]{peer.identity,peer.generation})){assertTrue(rows.moveToFirst());return rows.getLong(0);}}}
    private List<ActivityEvent> sourceAfter(RemoteStore.Peer peer,long boundary)throws Exception {List<ActivityEvent> result=new ArrayList<>();synchronized(feature.store){try(Cursor rows=feature.store.getReadableDatabase().rawQuery("SELECT body FROM activity_source WHERE peer=? AND generation=? AND id>? ORDER BY id",new String[]{peer.identity,peer.generation,Long.toString(boundary)})){while(rows.moveToNext())result.add(RemoteActivityWire.decode(new JSONObject(com.subhub.app.storage.StoredPayloads.read(rows,0))));}}return result;}
    private void drainActivity()throws Exception {java.lang.reflect.Field worker=RemoteActivityRepository.class.getDeclaredField("worker");worker.setAccessible(true);((java.util.concurrent.ExecutorService)worker.get(feature.activity)).submit(()->{}).get(15,TimeUnit.SECONDS);}
    private List<StatusBarNotification> notifications(RemoteStore.Peer peer){List<StatusBarNotification> result=new ArrayList<>();String prefix="subhub.activity:"+android.net.Uri.encode(RemotePersonRepository.id(peer).value())+":";for(StatusBarNotification item:context.getSystemService(NotificationManager.class).getActiveNotifications())if(item.getTag()!=null&&item.getTag().startsWith(prefix))result.add(item);return result;}
    private int notificationCount(RemoteStore.Peer peer){return notifications(peer).size();}
    private int notifiedCount(RemoteStore.Peer peer){synchronized(feature.store){try(Cursor rows=feature.store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM activity_receipts WHERE peer=? AND generation=? AND notified=1",new String[]{peer.identity,peer.generation})){assertTrue(rows.moveToFirst());return rows.getInt(0);}}}
    private void write(String name,String value)throws IOException {try(OutputStream output=new FileOutputStream(new File(directory,name))){output.write(value.getBytes(StandardCharsets.UTF_8));}}
    private String read(String name){try{return new String(java.nio.file.Files.readAllBytes(new File(directory,name).toPath()),StandardCharsets.UTF_8);}catch(IOException absent){return "";}}
    private interface Check{boolean ready()throws Exception;}
    private void await(Check check,long milliseconds)throws Exception {long end=SystemClock.elapsedRealtime()+milliseconds;while(!check.ready()&&SystemClock.elapsedRealtime()<end)Thread.sleep(100);assertTrue("Activity flow deadline exceeded",check.ready());}
}
