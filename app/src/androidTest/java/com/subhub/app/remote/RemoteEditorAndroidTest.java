package com.subhub.app.remote;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.*;
import android.graphics.Rect;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import com.subhub.app.R;
import com.subhub.app.people.*;
import com.subhub.app.security.ControllerPinManager;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class RemoteEditorAndroidTest {
    @Rule public final org.junit.rules.RuleChain fixtures = org.junit.rules.RuleChain
            .outerRule(new com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture())
            .around(new com.subhub.app.UiAutomationStateRule());
    private Context context;private RemoteFeature feature,previous;private RemoteStore source;private RemoteStore.Peer peer;private String database,generation;private java.lang.reflect.Field singleton;
    @Before public void setup()throws Exception {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertTrue(context.getSharedPreferences(com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME,0).edit()
                .putString(com.subhub.app.security.DeviceRolePreferences.KEY_MODE,com.subhub.app.security.DeviceRolePreferences.Mode.BOTH.name()).commit());
        assertTrue(ControllerPinManager.setPin(context,"2468"));
        assertTrue(ControllerPinManager.verify(context,"2468"));ControllerPinManager.enterDomMode();
        database="editor-"+UUID.randomUUID();feature=new RemoteFeature(context,new RemoteStore(context,database+".db"));source=new RemoteStore(context,database+"source.db");
        char[] a=new char[64],b=new char[64];Arrays.fill(a,'a');Arrays.fill(b,'b');String dom=new String(a),sub=new String(b);
        PairingInvite invite=PairingInvite.create(dom,"ticket",1000);generation=invite.id;feature.store.saveInvite(invite);feature.store.bindInvite(generation,sub,invite.tokenHash(),1001);feature.store.approveInvite(generation,sub,"Fixture Sub",1001);feature.store.activate(sub,generation);source.acceptInvite(invite,"Dom");source.activate(dom,generation);peer=feature.store.peer(sub,generation);
        JSONObject doc=RemoteConfigurationAndroidTest.configuration();doc.getJSONObject("apps").getJSONArray("packages").put("com.android.settings");doc.getJSONObject("apps").getJSONObject("allowances").put("com.android.settings",37);
        source.publish(dom,generation,RemoteProtocol.Stream.BASELINE,"active",doc,false,1);feature.store.receive(sub,source.nextBatch(dom,generation,RemoteProtocol.Stream.BASELINE));
        source.publish(dom,generation,RemoteProtocol.Stream.APP_CATALOG,"com.android.settings",new JSONObject().put("label","Settings"),false,1);feature.store.receive(sub,source.nextBatch(dom,generation,RemoteProtocol.Stream.APP_CATALOG));
        singleton=RemoteFeature.class.getDeclaredField("instance");singleton.setAccessible(true);previous=(RemoteFeature)singleton.get(null);singleton.set(null,feature);
    }
    @After public void cleanup()throws Exception {singleton.set(null,previous);feature.shutdownForTest();source.close();context.deleteDatabase(database+".db");context.deleteDatabase(database+"source.db");File assets=new File(context.getNoBackupFilesDir(),"remote-assets/"+peer.identity+"/"+generation);if(assets.isDirectory())try(java.util.stream.Stream<java.nio.file.Path> paths=java.nio.file.Files.walk(assets.toPath())){for(java.nio.file.Path path:paths.sorted(Comparator.reverseOrder()).toArray(java.nio.file.Path[]::new))java.nio.file.Files.delete(path);}ControllerPinManager.enterSubMode();}
    @Test public void draftAndInvalidRawBudgetSurviveRecreation()throws Exception {
        try(ActivityScenario<RemoteContractActivity> page=open("apps")) {
            ready(page,"budget:com.android.settings");page.onActivity(a->{((EditText)controls(a).get("budget:com.android.settings")).setText("");});page.recreate();
            ready(page,"budget:com.android.settings");page.onActivity(a->{assertEquals("",((EditText)controls(a).get("budget:com.android.settings")).getText().toString());});
            tap(page,R.string.remote_apply);assertNull(feature.store.latestCommand(peer.identity,generation));
        }
    }
    @Test public void nativeSpinnerChoiceAndApplyUseRemoteDuration()throws Exception {
        try(ActivityScenario<RemoteContractActivity> page=open("duration")) {
            ready(page,"service_duration");AtomicReference<View> picker=new AtomicReference<>();page.onActivity(a->picker.set(controls(a).get("service_duration")));tapView(picker.get());
            UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());String choice=context.getResources().getStringArray(R.array.remote_duration_choices)[2];
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text(choice)),5000));device.findObject(androidx.test.uiautomator.By.text(choice)).click();InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            try{await(()->{AtomicReference<Integer> selected=new AtomicReference<>(-1);page.onActivity(a->selected.set(((Spinner)controls(a).get("service_duration")).getSelectedItemPosition()));return selected.get()==2;});}catch(AssertionError failed){diagnose(page,"duration-choice");throw failed;}
            tap(page,R.string.remote_apply);try{await(()->feature.store.latestCommand(peer.identity,generation)!=null);}catch(AssertionError failed){diagnose(page,"duration-apply");throw failed;}JSONObject saved=new JSONObject(feature.store.latestCommand(peer.identity,generation).body).getJSONObject("configuration");assertEquals(3_600_000,saved.getJSONObject("service").getLong("duration"));
        }
    }

    @Test public void atmosphereRouteOpensSubliminalAndRoleLossClosesEditor()throws Exception {
        try(ActivityScenario<RemoteContractActivity> page=open("atmosphere")) {
            ready(page,"name");page.onActivity(a->{try{java.lang.reflect.Field section=RemoteContractActivity.class.getDeclaredField("section");section.setAccessible(true);assertEquals("subliminal",section.get(a));}catch(Exception e){throw new AssertionError(e);}});
            ControllerPinManager.enterSubMode();page.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);page.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);await(()->page.getState()==androidx.lifecycle.Lifecycle.State.DESTROYED);
        }
    }
    @Test public void dailyStatisticsRenderSourceUnitsAndUnknownDataHonestly()throws Exception {
        try(ActivityScenario<RemoteStatsActivity> page=ActivityScenario.launch(new Intent(context,RemoteStatsActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID,RemotePersonRepository.id(peer).value()))) {
            page.onActivity(a->{assertNotNull(find(a.body,context.getString(R.string.remote_unknown)));assertNotNull(find(a.body,context.getString(R.string.role_stale_data)));});
        }
        JSONObject metrics=new JSONObject().put("service_ms",3_661_000).put("assessed_EUR",123).put("photos",4).put("videos",5).put("limit_stops",2);
        source.publish(source.peers(false).get(0).identity,generation,RemoteProtocol.Stream.DAILY,"2026-10-25",new JSONObject().put("schema",1).put("day","2026-10-25").put("zone",JSONObject.NULL).put("metrics",metrics),false,1);
        String dom=source.peers(false).get(0).identity;feature.store.receive(peer.identity,source.nextBatch(dom,generation,RemoteProtocol.Stream.DAILY));feature.store.putMetadata(peer.identity,generation,"confirmed_DAILY",Long.toString(System.currentTimeMillis()));
        try(ActivityScenario<RemoteStatsActivity> page=ActivityScenario.launch(new Intent(context,RemoteStatsActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID,RemotePersonRepository.id(peer).value()))) {
            page.onActivity(a->{View daily=a.body.findViewWithTag("remote_stats_DAILY");assertNotNull(daily);assertFalse(daily instanceof Button);});
            await(()->{AtomicReference<Boolean> rendered=new AtomicReference<>(false);page.onActivity(a->rendered.set(find(a.body,"2026-10-25")!=null));return rendered.get();});
            page.onActivity(a->{assertNotNull(find(a.body,context.getString(R.string.remote_time,1L,1L,1L)));assertNotNull(find(a.body,"1.23 EUR"));assertNotNull(find(a.body,context.getString(R.string.daily_photos)));assertNotNull(find(a.body,context.getString(R.string.daily_videos)));assertNotNull(find(a.body,context.getString(R.string.remote_unknown_zone)));assertNull(find(a.body,context.getString(R.string.remote_incomplete)));});
            File directory=new File(context.getFilesDir(),"remote-editor-evidence");directory.mkdirs();assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(new File(directory,"daily-stats.png")));
        }
    }

    private void diagnose(ActivityScenario<RemoteContractActivity> page,String name)throws Exception {
        File directory=new File(context.getFilesDir(),"remote-editor-failures");directory.mkdirs();UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());device.takeScreenshot(new File(directory,name+".png"));device.dumpWindowHierarchy(new File(directory,name+".xml"));
        AtomicReference<String> values=new AtomicReference<>();page.onActivity(a->{JSONObject data=new JSONObject();try{data.put("mayEdit",a.mayEdit());for(Map.Entry<String,View> entry:controls(a).entrySet()){View v=entry.getValue();if(v instanceof EditText)data.put(entry.getKey(),new JSONObject().put("value",((EditText)v).getText()).put("error",((EditText)v).getError()));else if(v instanceof Spinner)data.put(entry.getKey(),((Spinner)v).getSelectedItemPosition());}values.set(data.toString());}catch(Exception e){throw new AssertionError(e);}});try(OutputStream out=new FileOutputStream(new File(directory,name+".json"))){out.write(values.get().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    }
    @Test public void focusedSectionsExposeOnlyTheirOwnDirectProfileActions()throws Exception {
        try(ActivityScenario<RemoteContractActivity> page=open("apps")) {
            ready(page,"apps_all");page.onActivity(a->{assertNull(find(a.body,context.getString(R.string.remote_import)));assertNull(find(a.body,context.getString(R.string.remote_add_censor_image)));assertNull(controls(a).get("module_censor_enabled"));});
        }
        try(ActivityScenario<RemoteContractActivity> page=open("censor")) {
            ready(page,"module_censor_enabled");page.onActivity(a->{assertNotNull(find(a.body,context.getString(R.string.remote_add_censor_image)));assertNull(find(a.body,context.getString(R.string.remote_add_popup_image)));assertNull(find(a.body,context.getString(R.string.remote_apps)));});
        }
    }
    @Test public void removingCensorArtworkPreservesPopupArtworkAndPendingPickerGroup()throws Exception {
        ByteArrayOutputStream image=new ByteArrayOutputStream();android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(2,2,android.graphics.Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xffd19bff);assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,image));bitmap.recycle();
        byte[] bytes=image.toByteArray();String hash=RemoteProtocol.sha256(bytes);RemoteContracts.ingest(feature.contracts.assets(peer),hash,bytes.length,new ByteArrayInputStream(bytes));
        JSONObject document=feature.contracts.current(peer);document.getJSONObject("contract").getJSONArray("assets").put(new JSONObject().put("path","assets/censor/fixture.png").put("hash",hash).put("length",bytes.length)).put(new JSONObject().put("path","assets/popup/fixture.png").put("hash",hash).put("length",bytes.length));
        String dom=source.peers(false).get(0).identity;source.publish(dom,generation,RemoteProtocol.Stream.BASELINE,"active",document,false,2);feature.store.receive(peer.identity,source.nextBatch(dom,generation,RemoteProtocol.Stream.BASELINE));
        try(ActivityScenario<RemoteContractActivity> page=open("censor")) {
            ready(page,"module_censor_enabled");tap(page,R.string.remote_remove_images);tap(page,R.string.remote_apply);await(()->feature.store.latestCommand(peer.identity,generation)!=null);
            JSONArray kept=new JSONObject(feature.store.latestCommand(peer.identity,generation).body).getJSONObject("configuration").getJSONObject("contract").getJSONArray("assets");assertEquals(1,kept.length());assertEquals("assets/popup/fixture.png",kept.getJSONObject(0).getString("path"));
        }
        try(ActivityScenario<RemoteContractActivity> page=open("popup")) {
            ready(page,"name");AtomicReference<RemoteContractActivity> current=new AtomicReference<>();page.onActivity(current::set);tap(page,R.string.remote_add_popup_image);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->current.get().recreate());
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack();ready(page,"name");
            page.onActivity(a->{try{java.lang.reflect.Field group=RemoteContractActivity.class.getDeclaredField("artGroup");group.setAccessible(true);assertEquals("popup",group.get(a));java.lang.reflect.Field section=RemoteContractActivity.class.getDeclaredField("section");section.setAccessible(true);assertEquals("popup",section.get(a));}catch(Exception e){throw new AssertionError(e);}});
        }
    }


    @Test public void fixedDurationIgnoresHiddenRandomInputsAndDraftHasNoDestructiveRetry()throws Exception {
        try(ActivityScenario<RemoteContractActivity> page=open("duration")) {
            ready(page,"service_duration");page.onActivity(a->{
                assertNull(controls(a).get("name"));assertNull(find(a.body,context.getString(R.string.role_retry)));
                EditText minimum=(EditText)controls(a).get("service_minimum"),maximum=(EditText)controls(a).get("service_maximum");
                assertFalse(minimum.isShown());assertFalse(maximum.isShown());minimum.setText("");maximum.setText("invalid");
            });
            tap(page,R.string.remote_apply);await(()->feature.store.latestCommand(peer.identity,generation)!=null);
        }
    }
    @Test public void failedInitialLoadOffersRetryAndStatisticsRetainSelectedPageAndExpire()throws Exception {
        feature.store.getWritableDatabase().delete("remote_objects","stream=?",new String[]{RemoteProtocol.Stream.BASELINE.name()});
        try(ActivityScenario<RemoteContractActivity> page=open("duration")) {
            await(()->{AtomicReference<Boolean> found=new AtomicReference<>(false);page.onActivity(a->found.set(find(a.body,context.getString(R.string.role_retry))!=null));return found.get();});
            JSONObject restored=RemoteConfigurationAndroidTest.configuration();restored.getJSONObject("contract").put("name","Restored baseline");
            String dom=source.peers(false).get(0).identity;source.publish(dom,generation,RemoteProtocol.Stream.BASELINE,"active",restored,false,2);feature.store.receive(peer.identity,source.nextBatch(dom,generation,RemoteProtocol.Stream.BASELINE));
            tap(page,R.string.role_retry);ready(page,"service_duration");
        }
        feature.store.putMetadata(peer.identity,generation,"confirmed_RUNTIME",Long.toString(System.currentTimeMillis()));
        try(ActivityScenario<RemoteStatsActivity> page=ActivityScenario.launch(new Intent(context,RemoteStatsActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID,RemotePersonRepository.id(peer).value()))) {
            page.onActivity(a->{assertNotNull(a.body.findViewWithTag("remote_stats_DAILY"));assertNotNull(a.body.findViewWithTag("remote_stats_APP_USAGE"));assertNotNull(a.body.findViewWithTag("remote_stats_RUNTIME"));android.widget.ScrollView scroll=statsScroll(a);scroll.scrollTo(0,200);});
            page.recreate();
            await(()->{AtomicReference<Boolean> restored=new AtomicReference<>(false);page.onActivity(a->restored.set(statsScroll(a).getScrollY()>0));return restored.get();});
            page.onActivity(a->assertEquals(View.GONE,a.body.findViewWithTag("remote_freshness_RUNTIME").getVisibility()));
            feature.store.putMetadata(peer.identity,generation,"confirmed_RUNTIME",Long.toString(System.currentTimeMillis()-91_000));
            await(()->{AtomicReference<Boolean> found=new AtomicReference<>(false);page.onActivity(a->found.set(a.body.findViewWithTag("remote_freshness_RUNTIME")!=null&&a.body.findViewWithTag("remote_freshness_RUNTIME").getVisibility()==View.VISIBLE));return found.get();});
        }
    }

    @Test @androidx.test.filters.SdkSuppress(minSdkVersion=29)
    public void pairedScreensFitCurrentDisplayAndFontConfiguration()throws Exception {
        for(String section:Arrays.asList("apps","duration","censor"))try(ActivityScenario<RemoteContractActivity> page=open(section)) {
            ready(page,"apps".equals(section)?"apps_all":"duration".equals(section)?"service_duration":"name");inspectLayout(page,"contract-"+section);
        }
        try(ActivityScenario<RemoteConnectionActivity> page=ActivityScenario.launch(new Intent(context,RemoteConnectionActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID,RemotePersonRepository.id(peer).value()))){inspectLayout(page,"connection");}
        try(ActivityScenario<RemoteStatsActivity> page=ActivityScenario.launch(new Intent(context,RemoteStatsActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID,RemotePersonRepository.id(peer).value()))){inspectLayout(page,"stats");}
    }
    @androidx.annotation.RequiresApi(29)
    private <T extends Activity> void inspectLayout(ActivityScenario<T> page,String name)throws Exception {
        android.app.Instrumentation instrument=InstrumentationRegistry.getInstrumentation();AtomicReference<T> activity=new AtomicReference<>();page.onActivity(activity::set);
        await(()->{AtomicReference<Boolean> settled=new AtomicReference<>(false);page.onActivity(a->settled.set(a.hasWindowFocus()&&!a.getWindow().getDecorView().isLayoutRequested()));return settled.get();});
        java.util.concurrent.CountDownLatch frame=new java.util.concurrent.CountDownLatch(1);page.onActivity(a->{View root=a.getWindow().getDecorView();root.getViewTreeObserver().registerFrameCommitCallback(frame::countDown);root.invalidate();});assertTrue("No hardware frame commit",frame.await(5,java.util.concurrent.TimeUnit.SECONDS));
        List<String> failures=new ArrayList<>();page.onActivity(a->inspectText(a.findViewById(android.R.id.content),failures));
        String configuration=activity.get().getResources().getConfiguration().screenWidthDp+"-"+activity.get().getResources().getConfiguration().fontScale;
        File directory=new File(context.getFilesDir(),"remote-layout/"+configuration+"-"+database);assertTrue(directory.isDirectory()||directory.mkdirs());UiDevice device=UiDevice.getInstance(instrument);assertTrue(device.takeScreenshot(new File(directory,name+".png")));
        try(OutputStream out=new FileOutputStream(new File(directory,name+".json"))){out.write(new JSONArray(failures).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}assertTrue(failures.toString(),failures.isEmpty());
    }
    private static void inspectText(View root,List<String> failures) {
        if(!root.isShown())return;
        if(root instanceof TextView&&root.getHeight()>0){TextView text=(TextView)root;android.text.Layout layout=text.getLayout();if(layout!=null&&text.getText().length()>0){
            int width=text.getWidth()-text.getCompoundPaddingLeft()-text.getCompoundPaddingRight(),height=text.getHeight()-text.getCompoundPaddingTop()-text.getCompoundPaddingBottom();
            int drawn=root instanceof EditText?layout.getLineBottom(0)-layout.getLineTop(0):layout.getHeight();if(drawn>height+1)failures.add("Clipped height: "+text.getText());
            if(!(root instanceof EditText))for(int line=0;line<layout.getLineCount();line++)if(layout.getLineMax(line)>width+1||layout.getEllipsisCount(line)>0){failures.add("Clipped width: "+text.getText());break;}
        }}
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)inspectText(((ViewGroup)root).getChildAt(i),failures);
    }
    private static EditText firstEditor(View root){if(root instanceof EditText)return (EditText)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){EditText found=firstEditor(((ViewGroup)root).getChildAt(i));if(found!=null)return found;}return null;}


    @Test public void sharedNativeFeaturePagesUseRemoteDraftsAndPreserveDomDeviceState() throws Exception {
        SharedPreferences local=context.getSharedPreferences(com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME,0);
        SharedPreferences wallet=context.getSharedPreferences(com.subhub.app.penance.PenanceManager.PREFS_NAME,0);
        Map<String,?> before=new HashMap<>(local.getAll()),walletBefore=new HashMap<>(wallet.getAll());
        Class<?>[] destinations={com.subhub.app.settings.SettingsActivity.class,com.subhub.app.appmode.AppModeActivity.class,
                com.subhub.app.penance.PenanceActivity.class,com.subhub.app.subliminal.SubliminalSettingsActivity.class,
                com.subhub.app.popup.PopupStormActivity.class,com.subhub.app.people.LocalAppsActivity.class,
                com.subhub.app.people.ServiceDurationActivity.class};
        for(Class<?> destination:destinations)try(ActivityScenario<?> page=ActivityScenario.launch(new Intent(context,destination)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID,RemotePersonRepository.id(peer).value()))) {
            page.onActivity(a->{
                assertTrue(destination.getSimpleName(),com.subhub.app.configuration.FeatureEditorSession.remote(a));
                assertNotNull(a.findViewById(android.R.id.content).findViewWithTag("feature_editor_apply"));
                View navigation=a.findViewById(R.id.bottom_navigation);
                assertTrue(navigation==null || navigation.getVisibility()==View.GONE);
                android.content.Context draft=com.subhub.app.configuration.FeatureEditorSession.context(a);
                assertNotSame(local,draft.getSharedPreferences(com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME,0));
                draft.getSharedPreferences(com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME,0).edit().putString("local_only_witness","remote draft").commit();
            });
            page.recreate();
            page.onActivity(a->assertTrue(com.subhub.app.configuration.FeatureEditorSession.remote(a)));
        }
        assertEquals(before,local.getAll());assertEquals(walletBefore,wallet.getAll());
        assertNull(feature.store.latestCommand(peer.identity,generation));
    }
    @Test public void sharedCensorNativeSelectionAppliesOnlyToItsPairedIdentity() throws Exception {
        SharedPreferences local=context.getSharedPreferences(com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME,0);
        Map<String,?> before=new HashMap<>(local.getAll());
        try(ActivityScenario<com.subhub.app.settings.SettingsActivity> page=ActivityScenario.launch(new Intent(context,com.subhub.app.settings.SettingsActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID,RemotePersonRepository.id(peer).value()))) {
            AtomicReference<View> selection=new AtomicReference<>();
            page.onActivity(a->selection.set(a.findViewById(R.id.button_appearance_details)));tapView(selection.get());
            page.onActivity(a->selection.set(a.findViewById(R.id.radio_blur)));tapView(selection.get());
            page.recreate();
            page.onActivity(a->{assertTrue(((RadioButton)a.findViewById(R.id.radio_blur)).isChecked());selection.set(a.findViewById(android.R.id.content).findViewWithTag("feature_editor_apply"));});
            tapView(selection.get());await(()->feature.store.latestCommand(peer.identity,generation)!=null);
        }
        JSONObject saved=new JSONObject(feature.store.latestCommand(peer.identity,generation).body).getJSONObject("configuration");
        assertEquals("blur",saved.getJSONObject("contract").getJSONObject("sections").getJSONObject("censor").getString("censor_type"));
        assertEquals(before,local.getAll());
    }
    @Test public void sharedStatisticsPageUsesMirroredHistoryWithoutReadingLocalTotals() throws Exception {
        String dom=source.peers(false).get(0).identity;
        source.publish(dom,generation,RemoteProtocol.Stream.DAILY,"2026-10-25",new JSONObject().put("schema",1).put("day","2026-10-25")
                .put("zone",JSONObject.NULL).put("metrics",new JSONObject().put("service_ms",3661000)),false,1);
        feature.store.receive(peer.identity,source.nextBatch(dom,generation,RemoteProtocol.Stream.DAILY));
        Map<String,?> before=new HashMap<>(context.getSharedPreferences(com.subhub.app.stats.StatsRepository.PREFS_NAME,0).getAll());
        try(ActivityScenario<com.subhub.app.stats.StatsActivity> page=ActivityScenario.launch(new Intent(context,com.subhub.app.stats.StatsActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID,RemotePersonRepository.id(peer).value()))) {
            page.onActivity(a->{assertNotNull(a.findViewById(R.id.daily_statistics_panel));
                assertEquals(View.GONE,a.findViewById(R.id.bottom_navigation).getVisibility());
                assertNotNull(find(a.findViewById(R.id.stats_lifetime_summary),context.getString(R.string.role_unknown_data)));});
            page.recreate();
            page.onActivity(a->assertNotNull(a.findViewById(R.id.daily_statistics_panel)));
        }
        assertEquals(before,context.getSharedPreferences(com.subhub.app.stats.StatsRepository.PREFS_NAME,0).getAll());
    }
    @Test public void sharedWhispersEnableIsQueuedWithoutChangingTheDomModule() throws Exception {
        JSONObject saved=nativeToggle(com.subhub.app.subliminal.SubliminalSettingsActivity.class,R.id.switch_whispers);
        assertTrue(saved.getJSONObject("contract").getJSONObject("sections").getJSONObject("modules").getBoolean("module_subliminal_enabled"));
    }
    @Test public void sharedPopupEnableDoesNotStartOrConfigureTheDomRuntime() throws Exception {
        boolean running=com.subhub.app.popup.PopupStormManager.get().isRunning();
        JSONObject saved=nativeToggle(com.subhub.app.popup.PopupStormActivity.class,R.id.switch_popup_storm);
        assertTrue(saved.getJSONObject("contract").getJSONObject("sections").getJSONObject("popup").getBoolean("popup_storm_enabled"));
        assertEquals(running,com.subhub.app.popup.PopupStormManager.get().isRunning());
    }
    @Test public void sharedTributeRuleSavesWithoutOpeningOrChangingPaymentCredentials() throws Exception {
        JSONObject saved=nativeToggle(com.subhub.app.penance.PenanceActivity.class,R.id.rule_app_open_enabled);
        assertTrue(saved.getJSONObject("contract").getJSONObject("sections").getJSONObject("wallet")
                .getBoolean("rule_"+com.subhub.app.penance.PenanceInfraction.WATCHED_APP_OPEN.preferenceKey()+"_enabled"));
    }
    private JSONObject nativeToggle(Class<?> target,int toggle) throws Exception {
        Map<String,?> before=new HashMap<>(context.getSharedPreferences(com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME,0).getAll());
        Map<String,?> wallet=new HashMap<>(context.getSharedPreferences(com.subhub.app.penance.PenanceManager.PREFS_NAME,0).getAll());
        try(ActivityScenario<?> page=ActivityScenario.launch(new Intent(context,target).putExtra(PersonRoutes.EXTRA_PERSON_ID,RemotePersonRepository.id(peer).value()))) {
            AtomicReference<View> view=new AtomicReference<>();page.onActivity(a->view.set(a.findViewById(toggle)));tapView(view.get());
            page.onActivity(a->view.set(a.findViewById(android.R.id.content).findViewWithTag("feature_editor_apply")));tapView(view.get());
            await(()->feature.store.latestCommand(peer.identity,generation)!=null);
        }
        assertEquals(before,context.getSharedPreferences(com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME,0).getAll());
        assertEquals(wallet,context.getSharedPreferences(com.subhub.app.penance.PenanceManager.PREFS_NAME,0).getAll());
        return new JSONObject(feature.store.latestCommand(peer.identity,generation).body).getJSONObject("configuration");
    }

    @Test public void peerNameChangesKeepRelationshipAndPersonIdentitiesStable() throws Exception {
        PersonId id=RemotePersonRepository.id(peer);
        feature.store.updateDisplayName(peer.identity,generation,"Updated Sub");
        assertEquals(id,RemotePersonRepository.id(feature.store.peer(peer.identity,generation)));
        assertEquals("Updated Sub",new RemotePersonRepository(context,feature).load(id).name);
        assertEquals(generation,feature.store.peer(peer.identity,generation).generation);
    }

    private ActivityScenario<RemoteContractActivity> open(String section){return ActivityScenario.launch(new Intent(context,RemoteContractActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID,RemotePersonRepository.id(peer).value()).putExtra("section",section));}
    @SuppressWarnings("unchecked") private static Map<String,View> controls(RemoteContractActivity activity){try{java.lang.reflect.Field field=RemoteContractActivity.class.getDeclaredField("controls");field.setAccessible(true);return (Map<String,View>)field.get(activity);}catch(Exception e){throw new AssertionError(e);}}
    private void ready(ActivityScenario<RemoteContractActivity> page,String key)throws Exception {await(()->{AtomicReference<Boolean> value=new AtomicReference<>(false);page.onActivity(a->value.set(controls(a).containsKey(key)));return value.get();});}
    private static android.widget.ScrollView statsScroll(RemoteStatsActivity activity){View view=activity.body;while(view.getParent() instanceof View){view=(View)view.getParent();if(view instanceof android.widget.ScrollView)return (android.widget.ScrollView)view;}throw new AssertionError("Statistics scroll unavailable");}
    private <T extends Activity> void tap(ActivityScenario<T> page,int label)throws Exception {AtomicReference<View> view=new AtomicReference<>();page.onActivity(a->view.set(find(a.findViewById(R.id.role_page_body),context.getString(label))));assertNotNull(view.get());tapView(view.get());}
    private void tapView(View view)throws Exception {
        assertNotNull("Native tap target is missing",view);
        android.app.Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
        AtomicReference<Rect> bounds=new AtomicReference<>();
        instrument.runOnMainSync(()->view.requestRectangleOnScreen(new Rect(0,0,view.getWidth(),view.getHeight()),true));
        instrument.waitForIdleSync();
        instrument.runOnMainSync(()->{Rect r=new Rect();if(view.getGlobalVisibleRect(r))bounds.set(r);});
        if(bounds.get()==null){
            File directory=new File(context.getFilesDir(),"remote-editor-failures");directory.mkdirs();
            UiDevice device=UiDevice.getInstance(instrument);device.takeScreenshot(new File(directory,"native-tap.png"));
            device.dumpWindowHierarchy(new File(directory,"native-tap.xml"));
            fail("Native tap target is not visible: "+view.getClass().getSimpleName());
        }
        assertTrue(UiDevice.getInstance(instrument).click(bounds.get().centerX(),bounds.get().centerY()));
        instrument.waitForIdleSync();
    }
    private static View find(View root,String text){if(root instanceof TextView&&text.contentEquals(((TextView)root).getText()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View value=find(((ViewGroup)root).getChildAt(i),text);if(value!=null)return value;}return null;}
    private interface Check{boolean ready()throws Exception;}
    private void await(Check check)throws Exception {long deadline=android.os.SystemClock.uptimeMillis()+15_000;while(!check.ready()&&android.os.SystemClock.uptimeMillis()<deadline)Thread.sleep(50);assertTrue(check.ready());}
}
