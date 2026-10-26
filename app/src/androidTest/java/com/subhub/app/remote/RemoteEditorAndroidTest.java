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
    private Context context;private RemoteFeature feature,previous;private RemoteStore source;private RemoteStore.Peer peer;private String database,generation;private java.lang.reflect.Field singleton;
    @Before public void setup()throws Exception {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
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
            ready(page,"budget:com.android.settings");page.onActivity(a->{((EditText)controls(a).get("name")).setText("Draft remains");((EditText)controls(a).get("budget:com.android.settings")).setText("");});page.recreate();
            ready(page,"budget:com.android.settings");page.onActivity(a->{assertEquals("Draft remains",((EditText)controls(a).get("name")).getText().toString());assertEquals("",((EditText)controls(a).get("budget:com.android.settings")).getText().toString());});
            tap(page,R.string.remote_save_contract);assertTrue(feature.contracts.library(peer).isEmpty());
        }
    }
    @Test public void nativeSpinnerChoiceAndSaveUseRemoteDuration()throws Exception {
        try(ActivityScenario<RemoteContractActivity> page=open("duration")) {
            ready(page,"service_duration");AtomicReference<View> picker=new AtomicReference<>();page.onActivity(a->picker.set(controls(a).get("service_duration")));tapView(picker.get());
            UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());String choice=context.getResources().getStringArray(R.array.remote_duration_choices)[2];
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text(choice)),5000));device.findObject(androidx.test.uiautomator.By.text(choice)).click();InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            try{await(()->{AtomicReference<Integer> selected=new AtomicReference<>(-1);page.onActivity(a->selected.set(((Spinner)controls(a).get("service_duration")).getSelectedItemPosition()));return selected.get()==2;});}catch(AssertionError failed){diagnose(page,"duration-choice");throw failed;}
            tap(page,R.string.remote_save_contract);try{await(()->!feature.contracts.library(peer).isEmpty());}catch(AssertionError failed){diagnose(page,"duration-save");throw failed;}JSONObject saved=feature.contracts.library(peer).values().iterator().next();assertEquals(3_600_000,saved.getJSONObject("service").getLong("duration"));
        }
    }
    @Test public void revokedPeerCannotTruncateExportAndPendingExportSurvivesRecreation()throws Exception {
        File output=new File(context.getCacheDir(),database+".sub");try(FileOutputStream stream=new FileOutputStream(output)){stream.write(new byte[]{11,22,33});}
        try(ActivityScenario<RemoteLibraryActivity> page=openLibrary()) {
            readyLibrary(page);page.onActivity(a->{try{java.lang.reflect.Field export=RemoteLibraryActivity.class.getDeclaredField("exportDoc");export.setAccessible(true);export.set(a,new JSONObject(feature.contracts.current(peer).toString()));}catch(Exception e){throw new AssertionError(e);}});
            page.recreate();readyLibrary(page);page.onActivity(a->{try{java.lang.reflect.Field export=RemoteLibraryActivity.class.getDeclaredField("exportDoc");export.setAccessible(true);assertNotNull(export.get(a));}catch(Exception e){throw new AssertionError(e);}});
            feature.store.revoke(peer.identity,peer.generation);page.onActivity(a->a.onActivityResult(2,Activity.RESULT_OK,new Intent().setData(Uri.fromFile(output))));
            assertArrayEquals(new byte[]{11,22,33},java.nio.file.Files.readAllBytes(output.toPath()));
        }finally{assertTrue(output.delete());}
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
            AtomicReference<View> daily=new AtomicReference<>();page.onActivity(a->daily.set(find(a.body,context.getString(R.string.remote_daily_history))));tapView(daily.get());
            await(()->{AtomicReference<Boolean> rendered=new AtomicReference<>(false);page.onActivity(a->rendered.set(find(a.body,"2026-10-25")!=null));return rendered.get();});
            page.onActivity(a->{assertNotNull(find(a.body,context.getString(R.string.remote_time,1L,1L,1L)));assertNotNull(find(a.body,"1.23 EUR"));assertNotNull(find(a.body,context.getString(R.string.daily_photos)));assertNotNull(find(a.body,context.getString(R.string.daily_videos)));assertNotNull(find(a.body,context.getString(R.string.remote_unknown_zone)));assertNull(find(a.body,context.getString(R.string.remote_incomplete)));});
            File directory=new File(context.getFilesDir(),"remote-editor-evidence");directory.mkdirs();assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(new File(directory,"daily-stats.png")));
        }
    }
    @Test public void documentPickerExportsAndImportsTheRemoteContract()throws Exception {
        UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());String name="subhub-editor-"+UUID.randomUUID()+".sub";boolean complete=false;
        JSONObject saved=feature.contracts.current(peer);saved.getJSONObject("contract").put("name","SAF round trip");feature.contracts.save(peer,saved);
        try(ActivityScenario<RemoteLibraryActivity> page=openLibrary()) {
            readyLibrary(page);tap(page,R.string.remote_export);
            androidx.test.uiautomator.UiObject2 filename=device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.clazz(EditText.class)),5000);if(filename==null){File directory=new File(context.getFilesDir(),"remote-editor-failures");directory.mkdirs();device.dumpWindowHierarchy(new File(directory,"export-picker.xml"));device.takeScreenshot(new File(directory,"export-picker.png"));fail("Export picker filename field was not visible");}filename.setText(name);
            androidx.test.uiautomator.UiObject2 save=device.findObject(androidx.test.uiautomator.By.text(java.util.regex.Pattern.compile("(?i)save")));assertNotNull(save);save.click();
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.pkg(context.getPackageName()).depth(0)),5000));readyLibrary(page);
            tap(page,R.string.remote_import);
            androidx.test.uiautomator.BySelector file=androidx.test.uiautomator.By.text(name);
            if(!device.wait(androidx.test.uiautomator.Until.hasObject(file),5000)){
                androidx.test.uiautomator.UiObject2 list=device.findObject(androidx.test.uiautomator.By.desc("List view"));if(list!=null)list.click();
            }
            androidx.test.uiautomator.UiObject2 imported=device.wait(androidx.test.uiautomator.Until.findObject(file),5000);if(imported==null){File directory=new File(context.getFilesDir(),"remote-editor-failures");directory.mkdirs();device.dumpWindowHierarchy(new File(directory,"archive-picker.xml"));device.takeScreenshot(new File(directory,"archive-picker.png"));fail("Exported fixture archive not visible in document picker");}imported.click();
            await(()->!feature.contracts.library(peer).isEmpty());assertEquals("SAF round trip",feature.contracts.library(peer).values().iterator().next().getJSONObject("contract").getString("name"));complete=true;
        }finally{if(complete)device.executeShellCommand("rm -f /sdcard/Download/"+name);}
    }
    private void diagnose(ActivityScenario<RemoteContractActivity> page,String name)throws Exception {
        File directory=new File(context.getFilesDir(),"remote-editor-failures");directory.mkdirs();UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());device.takeScreenshot(new File(directory,name+".png"));device.dumpWindowHierarchy(new File(directory,name+".xml"));
        AtomicReference<String> values=new AtomicReference<>();page.onActivity(a->{JSONObject data=new JSONObject();try{data.put("mayEdit",a.mayEdit());for(Map.Entry<String,View> entry:controls(a).entrySet()){View v=entry.getValue();if(v instanceof EditText)data.put(entry.getKey(),new JSONObject().put("value",((EditText)v).getText()).put("error",((EditText)v).getError()));else if(v instanceof Spinner)data.put(entry.getKey(),((Spinner)v).getSelectedItemPosition());}values.set(data.toString());}catch(Exception e){throw new AssertionError(e);}});try(OutputStream out=new FileOutputStream(new File(directory,name+".json"))){out.write(values.get().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    }
    @Test public void focusedSectionsAndSavedLibraryKeepTheirOwnActions()throws Exception {
        try(ActivityScenario<RemoteContractActivity> page=open("apps")) {
            ready(page,"apps_all");page.onActivity(a->{assertNull(find(a.body,context.getString(R.string.remote_import)));assertNull(find(a.body,context.getString(R.string.remote_add_censor_image)));assertNull(controls(a).get("module_censor_enabled"));});
        }
        try(ActivityScenario<RemoteContractActivity> page=open("censor")) {
            ready(page,"module_censor_enabled");page.onActivity(a->{assertNotNull(find(a.body,context.getString(R.string.remote_add_censor_image)));assertNull(find(a.body,context.getString(R.string.remote_add_popup_image)));assertNull(find(a.body,context.getString(R.string.remote_apps)));});
        }
        JSONObject saved=feature.contracts.current(peer);saved.getJSONObject("contract").put("name","Saved fixture");feature.contracts.save(peer,saved);
        try(ActivityScenario<RemoteLibraryActivity> page=openLibrary()) {
            readyLibrary(page);page.onActivity(a->{assertNotNull(find(a.body,"Saved fixture"));assertNotNull(find(a.body,context.getString(R.string.remote_export)));assertNull(firstEditor(a.body));});
            tap(page,R.string.remote_apply);await(()->feature.store.latestCommand(peer.identity,generation)!=null);assertTrue(feature.store.latestCommand(peer.identity,generation).pending());
        }
    }
    @Test public void removingCensorArtworkPreservesPopupArtworkAndPendingPickerGroup()throws Exception {
        ByteArrayOutputStream image=new ByteArrayOutputStream();android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(2,2,android.graphics.Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xffd19bff);assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,image));bitmap.recycle();
        byte[] bytes=image.toByteArray();String hash=RemoteProtocol.sha256(bytes);RemoteContracts.ingest(feature.contracts.assets(peer),hash,bytes.length,new ByteArrayInputStream(bytes));
        JSONObject document=feature.contracts.current(peer);document.getJSONObject("contract").getJSONArray("assets").put(new JSONObject().put("path","assets/censor/fixture.png").put("hash",hash).put("length",bytes.length)).put(new JSONObject().put("path","assets/popup/fixture.png").put("hash",hash).put("length",bytes.length));
        String dom=source.peers(false).get(0).identity;source.publish(dom,generation,RemoteProtocol.Stream.BASELINE,"active",document,false,2);feature.store.receive(peer.identity,source.nextBatch(dom,generation,RemoteProtocol.Stream.BASELINE));
        try(ActivityScenario<RemoteContractActivity> page=open("censor")) {
            ready(page,"module_censor_enabled");tap(page,R.string.remote_remove_images);tap(page,R.string.remote_save_contract);await(()->!feature.contracts.library(peer).isEmpty());
            JSONArray kept=feature.contracts.library(peer).values().iterator().next().getJSONObject("contract").getJSONArray("assets");assertEquals(1,kept.length());assertEquals("assets/popup/fixture.png",kept.getJSONObject(0).getString("path"));
        }
        try(ActivityScenario<RemoteContractActivity> page=open("popup")) {
            ready(page,"name");AtomicReference<RemoteContractActivity> current=new AtomicReference<>();page.onActivity(current::set);tap(page,R.string.remote_add_popup_image);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->current.get().recreate());
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack();ready(page,"name");
            page.onActivity(a->{try{java.lang.reflect.Field group=RemoteContractActivity.class.getDeclaredField("artGroup");group.setAccessible(true);assertEquals("popup",group.get(a));java.lang.reflect.Field section=RemoteContractActivity.class.getDeclaredField("section");section.setAccessible(true);assertEquals("popup",section.get(a));}catch(Exception e){throw new AssertionError(e);}});
        }
    }
    private static EditText firstEditor(View root){if(root instanceof EditText)return (EditText)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){EditText found=firstEditor(((ViewGroup)root).getChildAt(i));if(found!=null)return found;}return null;}
    private ActivityScenario<RemoteLibraryActivity> openLibrary(){return ActivityScenario.launch(new Intent(context,RemoteLibraryActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID,RemotePersonRepository.id(peer).value()));}
    private void readyLibrary(ActivityScenario<RemoteLibraryActivity> page)throws Exception {await(()->{AtomicReference<Boolean> ready=new AtomicReference<>(false);page.onActivity(a->ready.set(find(a.body,context.getString(R.string.remote_import))!=null));return ready.get();});}
    private ActivityScenario<RemoteContractActivity> open(String section){return ActivityScenario.launch(new Intent(context,RemoteContractActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID,RemotePersonRepository.id(peer).value()).putExtra("section",section));}
    @SuppressWarnings("unchecked") private static Map<String,View> controls(RemoteContractActivity activity){try{java.lang.reflect.Field field=RemoteContractActivity.class.getDeclaredField("controls");field.setAccessible(true);return (Map<String,View>)field.get(activity);}catch(Exception e){throw new AssertionError(e);}}
    private void ready(ActivityScenario<RemoteContractActivity> page,String key)throws Exception {await(()->{AtomicReference<Boolean> value=new AtomicReference<>(false);page.onActivity(a->value.set(controls(a).containsKey(key)));return value.get();});}
    private <T extends Activity> void tap(ActivityScenario<T> page,int label)throws Exception {AtomicReference<View> view=new AtomicReference<>();page.onActivity(a->view.set(find(a.findViewById(R.id.role_page_body),context.getString(label))));assertNotNull(view.get());tapView(view.get());}
    private void tapView(View view)throws Exception {android.app.Instrumentation instrument=InstrumentationRegistry.getInstrumentation();AtomicReference<Rect> bounds=new AtomicReference<>();instrument.runOnMainSync(()->view.requestRectangleOnScreen(new Rect(0,0,view.getWidth(),view.getHeight()),true));instrument.waitForIdleSync();instrument.runOnMainSync(()->{Rect r=new Rect();assertTrue(view.getGlobalVisibleRect(r));bounds.set(r);});assertTrue(UiDevice.getInstance(instrument).click(bounds.get().centerX(),bounds.get().centerY()));instrument.waitForIdleSync();}
    private static View find(View root,String text){if(root instanceof TextView&&text.contentEquals(((TextView)root).getText()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View value=find(((ViewGroup)root).getChildAt(i),text);if(value!=null)return value;}return null;}
    private interface Check{boolean ready()throws Exception;}
    private void await(Check check)throws Exception {long deadline=android.os.SystemClock.uptimeMillis()+15_000;while(!check.ready()&&android.os.SystemClock.uptimeMillis()<deadline)Thread.sleep(50);assertTrue(check.ready());}
}
