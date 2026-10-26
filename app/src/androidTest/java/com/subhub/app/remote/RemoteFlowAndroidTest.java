package com.subhub.app.remote;

import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import com.subhub.app.R;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.people.*;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.stats.StatsRepository;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Host coordinates only private invitations and offline transitions between two owned guests. */
@RunWith(AndroidJUnit4.class)
public class RemoteFlowAndroidTest {
    private Context context;private Instrumentation instrument;private UiDevice device;private RemoteFeature feature;private File directory;
    @Before public void setup()throws Exception {
        instrument=InstrumentationRegistry.getInstrumentation();context=instrument.getTargetContext();device=UiDevice.getInstance(instrument);
        String run=InstrumentationRegistry.getArguments().getString("flow_run");assertNotNull(run);UUID.fromString(run);
        directory=new File(context.getFilesDir(),"remote-flow/"+run);assertTrue(directory.isDirectory()||directory.mkdirs());
        feature=RemoteFeature.get(context);
        if(People.keyholder().state()==com.subhub.app.security.KeyholderProvider.State.NONE){assertTrue(ControllerPinManager.verify(context,"2468"));ControllerPinManager.enterDomMode();}
        else {assertEquals(com.subhub.app.security.KeyholderProvider.State.READY,People.keyholder().state());ControllerPinManager.enterSubMode();}
        assertTrue(feature.recoveryReady());
    }
    @Test @SdkSuppress(minSdkVersion=29)
    public void domPairsEditsAppliesEndsAndQueuesWhileSubIsOffline()throws Exception {
        assertTrue(feature.store.peers(false).isEmpty());Uri image=null;
        for(RemoteStore.Invitation unused:feature.store.invitations(System.currentTimeMillis()))feature.cancel(unused.id);
        Set<String> previousInvitations=new HashSet<>();for(RemoteStore.Invitation prior:feature.store.invitations(System.currentTimeMillis()))previousInvitations.add(prior.id);
        try(ActivityScenario<PairingActivity> pairing=ActivityScenario.launch(new Intent(context,PairingActivity.class).putExtra("asDom",true))) {
            tap(pairing,context.getString(R.string.remote_create_invite));
            await(()->{for(RemoteStore.Invitation value:feature.store.invitations(System.currentTimeMillis()))if(!previousInvitations.contains(value.id))return true;return false;},30_000);
            RemoteStore.Invitation invitation=null;for(RemoteStore.Invitation value:feature.store.invitations(System.currentTimeMillis()))if(!previousInvitations.contains(value.id)){invitation=value;break;}
            assertNotNull(invitation);final RemoteStore.Invitation selectedInvitation=invitation;
            String link=new RemoteSecrets(context).pendingLink(invitation.id);assertNotNull(link);
            await(()->{AtomicReference<Boolean> ready=new AtomicReference<>(false);pairing.onActivity(activity->{ImageView view=first(activity.findViewById(R.id.role_page_body),ImageView.class);ready.set(view!=null&&view.getDrawable() instanceof BitmapDrawable);});return ready.get();},10_000);
            pairing.onActivity(activity->{try{ImageView qr=first(activity.findViewById(R.id.role_page_body),ImageView.class);Bitmap bitmap=((BitmapDrawable)qr.getDrawable()).getBitmap();int[] pixels=new int[bitmap.getWidth()*bitmap.getHeight()];bitmap.getPixels(pixels,0,bitmap.getWidth(),0,0,bitmap.getWidth(),bitmap.getHeight());String decoded=new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(bitmap.getWidth(),bitmap.getHeight(),pixels)))).getText();JSONObject witness=new JSONObject().put("storedLength",link.length()).put("qrLength",decoded.length()).put("storedSha256",RemoteProtocol.sha256(link.getBytes(StandardCharsets.UTF_8))).put("qrSha256",RemoteProtocol.sha256(decoded.getBytes(StandardCharsets.UTF_8)));try{PairingInvite expected=PairingInvite.restore(link),actual=PairingInvite.restore(decoded);witness.put("sameInvitation",expected.id.equals(actual.id)).put("sameIdentity",expected.domIdentity.equals(actual.domIdentity)).put("sameRoute",expected.route.equals(actual.route)).put("sameExpiry",expected.expiresAt==actual.expiresAt).put("sameToken",expected.tokenHash().equals(actual.tokenHash()));}catch(RuntimeException invalidPayload){witness.put("parseable",false);}write("qr-witness",witness.toString());assertTrue("QR does not match the newly created invitation; private hash witness retained",link.equals(decoded));}catch(Exception invalid){throw new AssertionError(invalid);}});
            write("invite",link);write("dom-state","invite-ready");
            await(()->{for(RemoteStore.Invitation item:feature.store.invitations(System.currentTimeMillis()))if(item.id.equals(selectedInvitation.id)&&item.candidate!=null)return true;return false;},90_000);
            awaitText(pairing,context.getString(R.string.remote_approve));tap(pairing,context.getString(R.string.remote_approve));approveDialog();
            await(()->peer(RemoteProtocol.Role.DOM)!=null&&peer(RemoteProtocol.Role.DOM).state==RemoteStore.State.ACTIVE,90_000);
        }
        domWithConnection();
    }
    @Test @SdkSuppress(minSdkVersion=29)
    public void domResumesAfterPairingWithoutRepeatingApproval()throws Exception {
        assertNotNull(peer(RemoteProtocol.Role.DOM));assertEquals(PairingInvite.restore(read("invite")).id,peer(RemoteProtocol.Role.DOM).generation);domWithConnection();
    }
    private void domWithConnection()throws Exception {
        RemoteStore.Peer sub=peer(RemoteProtocol.Role.DOM);assertNotNull(sub);
        try(ActivityScenario<RemoteConnectionActivity> connection=ActivityScenario.launch(new Intent(context,RemoteConnectionActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID,RemotePersonRepository.id(sub).value()))) {
            feature.changed();domAfterPairing();
        }
    }
    private void domAfterPairing()throws Exception {
        if(Build.VERSION.SDK_INT<29)throw new AssertionError("MediaStore fixture requires API29");
        Uri image=null;
        RemoteStore.Peer sub=peer(RemoteProtocol.Role.DOM);RemotePersonRepository repository=new RemotePersonRepository(context,feature);PersonId id=RemotePersonRepository.id(sub);
        await(()->{try{PersonSnapshot value=repository.load(id);return value.sync.contract==PersonSnapshot.Freshness.CURRENT&&value.sync.stats==PersonSnapshot.Freshness.CURRENT;}catch(Exception waiting){return false;}},90_000);
        write("dom-state","baseline-synced");PersonSnapshot baseline=repository.load(id);assertEquals(2,baseline.allowances.size());assertNotEquals(baseline.allowances.get(0).dailyMinutes,baseline.allowances.get(1).dailyMinutes);
        assertEquals(42,feature.store.mirrored(sub.identity,sub.generation,RemoteProtocol.Stream.STATS,"summary").getLong("censors"));
        assertFalse(new AppModeManager(context).isArmed());
        int existingArtwork=0;JSONArray baselineAssets=feature.contracts.current(sub).getJSONObject("contract").getJSONArray("assets");
        for(int index=0;index<baselineAssets.length();index++)if(baselineAssets.getJSONObject(index).getString("path").startsWith("assets/censor/"))existingArtwork++;
        ContentValues values=new ContentValues();String name="subhub-flow-"+directory.getName()+".png";values.put(MediaStore.Images.Media.DISPLAY_NAME,name);values.put(MediaStore.Images.Media.MIME_TYPE,"image/png");values.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/");
        try(android.database.Cursor existing=context.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID},MediaStore.Images.Media.DISPLAY_NAME+"=?",new String[]{name},null)) {
            if(existing!=null&&existing.moveToFirst())image=ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,existing.getLong(0));
        }
        if(image==null){image=context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);assertNotNull(image);
            try(OutputStream output=context.getContentResolver().openOutputStream(image)){Bitmap bitmap=Bitmap.createBitmap(48,48,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xffd19bff);assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));bitmap.recycle();}}
        try(ActivityScenario<RemoteContractActivity> editor=ActivityScenario.launch(new Intent(context,RemoteContractActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID,id.value()).putExtra("section","censor"))) {
            awaitEditor(editor);write("dom-state","editor-open");name(editor,"Shared contract");
            tap(editor,context.getString(R.string.remote_add_censor_image));
            device.waitForIdle();
            androidx.test.uiautomator.UiObject2 roots=device.wait(Until.findObject(By.desc("Show roots")),5000);assertNotNull(roots);roots.click();device.waitForIdle();
            androidx.test.uiautomator.UiObject2 recent=device.wait(Until.findObject(By.res("android:id/title").text("Recent")),5000);assertNotNull(recent);recent.click();
            assertTrue(device.wait(Until.gone(By.text("Open from")),5000));device.waitForIdle();
            BySelector imageTile=By.descStartsWith(name+", ");
            if(!device.wait(Until.hasObject(imageTile),10_000)){device.dumpWindowHierarchy(new File(directory,"picker-failure.xml"));fail("Synthetic image tile not visible in document picker");}
            device.findObject(imageTile).click();assertTrue(device.wait(Until.gone(By.pkg("com.google.android.documentsui").depth(0)),5000));instrument.waitForIdleSync();
            try { awaitText(editor,context.getString(R.string.remote_artwork_count,existingArtwork+1)); }
            catch(AssertionError failure) { capture("picker-return-failure.png");device.dumpWindowHierarchy(new File(directory,"picker-return-failure.xml"));throw failure; }
            write("dom-state","image-selected");
            toggleFirst(editor,false);capture("remote-contract-editor.png");tap(editor,context.getString(R.string.remote_apply));write("dom-state","apply-requested");
        }
        await(()->{RemoteStore.Command command=feature.store.latestCommand(sub.identity,sub.generation);return command!=null&&command.state==RemoteStore.CommandState.APPLIED;},90_000);
        await(()->{try{return repository.load(id).can(PersonSnapshot.Capability.END_SESSION);}catch(Exception waiting){return false;}},90_000);
        new AppModeManager(context).setArmed(true);String expected=repository.load(id).sessionIdentity;assertFalse(expected.isEmpty());
        try(ActivityScenario<RemoteEndActivity> end=ActivityScenario.launch(new Intent(context,RemoteEndActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID,id.value()).putExtra("expectedSession",expected))) {
            await(()->{try{JSONObject result=feature.endCommands.latest(sub);return result!=null&&"ENDED".equals(result.getString("state"));}catch(Exception waiting){return false;}},90_000);
            assertTrue("Remote End changed the Dom's local session",new AppModeManager(context).isArmed());capture("remote-end-result.png");
        }
        new AppModeManager(context).setArmed(false);
        try(ActivityScenario<RemoteConnectionActivity> connection=ActivityScenario.launch(new Intent(context,RemoteConnectionActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID,id.value()))) {
            await(()->"awaiting-code".equals(read("sub-state")),30_000);tap(connection,context.getString(R.string.remote_keyholder_code));
            onView(withHint(R.string.controller_pin_label)).inRoot(isDialog()).perform(replaceText("2468"),closeSoftKeyboard());
            onView(withText(R.string.controller_pin_unlock)).inRoot(isDialog()).perform(click());instrument.waitForIdleSync();
            BySelector code=By.text(java.util.regex.Pattern.compile("[0-9]{6}"));if(!device.wait(Until.hasObject(code),10_000)){
                AtomicReference<String> witness=new AtomicReference<>();connection.onActivity(a->{try{java.lang.reflect.Field shown=RemoteConnectionActivity.class.getDeclaredField("codeDialog");shown.setAccessible(true);androidx.appcompat.app.AlertDialog dialog=(androidx.appcompat.app.AlertDialog)shown.get(a);witness.set("dialog="+(dialog!=null&&dialog.isShowing())+", dom="+ControllerPinManager.isDomModeActive()+", authority="+People.keyholder().state()+", credential="+ControllerPinManager.hasCredentials(context));}catch(Exception invalid){throw new AssertionError(invalid);}});fail("Protected code was not observable: "+witness.get());}
            connection.onActivity(a->{try{java.lang.reflect.Field shown=RemoteConnectionActivity.class.getDeclaredField("codeDialog");shown.setAccessible(true);androidx.appcompat.app.AlertDialog dialog=(androidx.appcompat.app.AlertDialog)shown.get(a);assertNotNull(dialog);assertTrue((dialog.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);}catch(Exception invalid){throw new AssertionError(invalid);}});
            write("keyholder-code",device.findObject(code).getText());write("dom-state","code-shared");
            await(()->"ready-offline".equals(read("sub-state")),30_000);
            if(device.hasObject(By.res("android:id/button1")))device.findObject(By.res("android:id/button1")).click();
            new File(directory,"keyholder-code").delete();
        }
        write("dom-state","ready-offline");
        await(()->"yes".equals(read("offline-confirmed")),60_000);
        try(ActivityScenario<RemoteContractActivity> editor=ActivityScenario.launch(new Intent(context,RemoteContractActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID,id.value()).putExtra("section","censor"))) {
            awaitEditor(editor);name(editor,"Queued offline");tap(editor,context.getString(R.string.remote_apply));
        }
        await(()->{RemoteStore.Command command=feature.store.latestCommand(sub.identity,sub.generation);return command!=null&&command.revision==2&&command.pending();},10_000);Thread.sleep(1500);
        assertTrue(feature.store.latestCommand(sub.identity,sub.generation).pending());write("dom-state","queued-offline");
        await(()->{RemoteStore.Command command=feature.store.latestCommand(sub.identity,sub.generation);return command!=null&&command.revision==2&&command.state==RemoteStore.CommandState.APPLIED;},100_000);
        await(()->"second-applied".equals(read("sub-state")),60_000);
        try(ActivityScenario<RemoteConnectionActivity> connection=ActivityScenario.launch(new Intent(context,RemoteConnectionActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID,id.value()))) {
            tap(connection,context.getString(R.string.remote_unpair));await(()->feature.store.peer(sub.identity,sub.generation).state==RemoteStore.State.REVOKED,10_000);
            await(()->"true".equals(feature.store.metadata(sub.identity,sub.generation,"revocation_delivered","")),90_000);
            assertNull(new RemoteSecrets(context).controllerSecret(RemoteProtocol.Role.DOM,sub.identity,sub.generation));
        }
        write("dom-state","complete");if(image!=null)context.getContentResolver().delete(image,null,null);
    }
    @Test public void subApprovesKeepsBaselineAppliesArtworkAndObservesTargetedEnd()throws Exception {
        assertTrue(feature.store.peers(false).isEmpty());List<com.subhub.app.settings.InstalledAppCatalog.Entry> installed=com.subhub.app.settings.InstalledAppCatalog.load(context);assertTrue(installed.size()>=2);
        SharedPreferences main=context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0);Set<String> selected=new LinkedHashSet<>(Arrays.asList(installed.get(0).packageName,installed.get(1).packageName));
        new AppModeManager(context).save(false,selected);main.edit().putBoolean("module_censor_enabled",true).putBoolean("app_timer_per_app_enabled",true).putInt("app_timer_allowance_minutes:"+installed.get(0).packageName,37).putInt("app_timer_allowance_minutes:"+installed.get(1).packageName,83).putString("paypal_secret_fixture","kept-local").commit();
        context.getSharedPreferences(StatsRepository.PREFS_NAME,0).edit().putLong("total_blocks_all_time",42).commit();
        try(ActivityScenario<PairingActivity> pairing=ActivityScenario.launch(new Intent(context,PairingActivity.class).putExtra("asDom",false))) {
            await(()->!read("invite").isEmpty(),60_000);String link=read("invite");PairingInvite.parse(link,System.currentTimeMillis());
            pairing.onActivity(activity -> ((ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE))
                    .setPrimaryClip(ClipData.newPlainText("Pairing fixture",PairingInvite.parse(link,System.currentTimeMillis()).code())));
            tap(pairing,context.getString(R.string.remote_paste_code));
            tap(pairing,context.getString(R.string.remote_approve));approveDialog();
            await(()->peer(RemoteProtocol.Role.SUB)!=null&&peer(RemoteProtocol.Role.SUB).state==RemoteStore.State.ACTIVE,90_000);
            assertTrue("Pairing changed enforcement",main.getBoolean("module_censor_enabled",false));assertFalse(new AppModeManager(context).isArmed());write("sub-state","paired");
            subWaitForFirstApply();
        }
    }
    @Test public void subResumesAfterPairingWithoutChangingBaseline()throws Exception {
        RemoteStore.Peer dom=peer(RemoteProtocol.Role.SUB);assertNotNull(dom);assertEquals(PairingInvite.restore(read("invite")).id,dom.generation);
        try(ActivityScenario<RemoteConnectionActivity> connection=ActivityScenario.launch(new Intent(context,RemoteConnectionActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID,RemotePersonRepository.id(dom).value()))) {
            feature.changed();subWaitForFirstApply();
        }
    }
    @Test public void domCompletesKeyholderVerificationAndRevocationOnExistingRelationship()throws Exception {
        RemoteStore.Peer sub=peer(RemoteProtocol.Role.DOM);assertNotNull(sub);assertEquals(RemoteStore.State.ACTIVE,sub.state);
        try(ActivityScenario<RemoteConnectionActivity> connection=ActivityScenario.launch(new Intent(context,RemoteConnectionActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID,RemotePersonRepository.id(sub).value()))) {
            feature.changed();await(()->"awaiting-code".equals(read("sub-state")),30_000);tap(connection,context.getString(R.string.remote_keyholder_code));
            onView(withHint(R.string.controller_pin_label)).inRoot(isDialog()).perform(replaceText("2468"),closeSoftKeyboard());
            onView(withText(R.string.controller_pin_unlock)).inRoot(isDialog()).perform(click());instrument.waitForIdleSync();
            BySelector code=By.text(java.util.regex.Pattern.compile("[0-9]{6}"));if(!device.wait(Until.hasObject(code),10_000)){
                AtomicReference<String> witness=new AtomicReference<>();connection.onActivity(a->{try{java.lang.reflect.Field shown=RemoteConnectionActivity.class.getDeclaredField("codeDialog");shown.setAccessible(true);androidx.appcompat.app.AlertDialog dialog=(androidx.appcompat.app.AlertDialog)shown.get(a);witness.set("dialog="+(dialog!=null&&dialog.isShowing())+", dom="+ControllerPinManager.isDomModeActive()+", authority="+People.keyholder().state()+", credential="+ControllerPinManager.hasCredentials(context));}catch(Exception invalid){throw new AssertionError(invalid);}});fail("Protected code was not observable: "+witness.get());}
            connection.onActivity(a->{try{java.lang.reflect.Field shown=RemoteConnectionActivity.class.getDeclaredField("codeDialog");shown.setAccessible(true);androidx.appcompat.app.AlertDialog dialog=(androidx.appcompat.app.AlertDialog)shown.get(a);assertNotNull(dialog);assertTrue((dialog.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);}catch(Exception invalid){throw new AssertionError(invalid);}});
            write("keyholder-code",device.findObject(code).getText());write("dom-state","code-shared");
            await(()->"code-accepted".equals(read("sub-state")),30_000);new File(directory,"keyholder-code").delete();
            if(device.hasObject(By.res("android:id/button1")))device.findObject(By.res("android:id/button1")).click();
            tap(connection,context.getString(R.string.remote_unpair));await(()->feature.store.peer(sub.identity,sub.generation).state==RemoteStore.State.REVOKED,10_000);
            await(()->"true".equals(feature.store.metadata(sub.identity,sub.generation,"revocation_delivered","")),90_000);
            assertNull(new RemoteSecrets(context).controllerSecret(RemoteProtocol.Role.DOM,sub.identity,sub.generation));write("dom-state","complete");
        }
    }
    @Test public void subVerifiesCodeAndRetainsConfigurationAfterRevocation()throws Exception {
        RemoteStore.Peer dom=peer(RemoteProtocol.Role.SUB);assertNotNull(dom);assertEquals(RemoteStore.State.ACTIVE,dom.state);
        SharedPreferences main=context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0);boolean censor=main.getBoolean("module_censor_enabled",false);
        Set<String> apps=new HashSet<>(new AppModeManager(context).getSelectedPackages());
        try(ActivityScenario<RemoteConnectionActivity> connection=ActivityScenario.launch(new Intent(context,RemoteConnectionActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID,RemotePersonRepository.id(dom).value()))) {
            assertEquals(com.subhub.app.security.KeyholderProvider.State.READY,People.keyholder().state());assertFalse(ControllerPinManager.verify(context,"2468"));
            ControllerPinManager.enterSubMode();feature.changed();write("sub-state","awaiting-code");await(()->read("keyholder-code").matches("[0-9]{6}"),30_000);
            String code=read("keyholder-code");com.subhub.app.security.ControllerAuthenticator auth=new com.subhub.app.security.ControllerAuthenticator(context);
            assertEquals(com.subhub.app.security.ControllerAuthenticator.Result.SUCCESS,auth.verify(code));assertTrue(ControllerPinManager.isDomModeActive());assertFalse(People.isLocalConfigurationAllowed());
            assertEquals(com.subhub.app.security.ControllerAuthenticator.Result.INVALID,auth.verify(code));new File(directory,"keyholder-code").delete();ControllerPinManager.enterSubMode();write("sub-state","code-accepted");
            await(()->feature.store.peer(dom.identity,dom.generation).state==RemoteStore.State.REVOKED,90_000);
            assertEquals(com.subhub.app.security.KeyholderProvider.State.NONE,People.keyholder().state());assertTrue(People.isLocalConfigurationAllowed());assertTrue(ControllerPinManager.verify(context,"2468"));
            assertNull(new RemoteSecrets(context).controllerSecret(RemoteProtocol.Role.SUB,dom.identity,dom.generation));
            assertEquals(censor,main.getBoolean("module_censor_enabled",false));assertEquals(apps,new AppModeManager(context).getSelectedPackages());write("sub-state","complete");
        }
    }
    @Test public void domReconcilesPersistedRevocationDelivery()throws Exception {
        RemoteStore.Peer sub=revokedPeer(RemoteProtocol.Role.DOM);write("dom-state","reconciling-revocation");
        try(ActivityScenario<RemoteConnectionActivity> connection=ActivityScenario.launch(new Intent(context,RemoteConnectionActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID,RemotePersonRepository.id(sub).value()))) {
            feature.changed();await(()->"true".equals(feature.store.metadata(sub.identity,sub.generation,"revocation_delivered","")),90_000);
            assertNull(new RemoteSecrets(context).controllerSecret(RemoteProtocol.Role.DOM,sub.identity,sub.generation));write("dom-state","complete");
        }
    }
    @Test public void subKeepsRevokedEndpointAliveUntilDomReceipt()throws Exception {
        RemoteStore.Peer dom=revokedPeer(RemoteProtocol.Role.SUB);assertNull(new RemoteSecrets(context).controllerSecret(RemoteProtocol.Role.SUB,dom.identity,dom.generation));
        try(ActivityScenario<RemoteConnectionActivity> connection=ActivityScenario.launch(new Intent(context,RemoteConnectionActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID,RemotePersonRepository.id(dom).value()))) {
            feature.changed();write("sub-state","awaiting-revocation-receipt");await(()->"complete".equals(read("dom-state")),100_000);write("sub-state","complete");
        }
    }
    private RemoteStore.Peer revokedPeer(RemoteProtocol.Role role) {
        String generation=PairingInvite.restore(read("invite")).id;
        for(RemoteStore.Peer candidate:feature.store.peers(true))if(candidate.localRole==role&&generation.equals(candidate.generation)){
            assertEquals(RemoteStore.State.REVOKED,candidate.state);return candidate;
        }
        throw new AssertionError("Original revoked relationship unavailable");
    }
    private void subWaitForFirstApply()throws Exception {
        SharedPreferences main=context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0);
            RemoteStore.Peer dom=peer(RemoteProtocol.Role.SUB);
            await(()->"1".equals(feature.store.metadata(dom.identity,dom.generation,"applied_revision","0")),90_000);
            assertFalse(main.getBoolean("module_censor_enabled",true));assertEquals("kept-local",main.getString("paypal_secret_fixture",""));
            String folder=main.getString(com.subhub.app.capture.CustomImageManager.PACK_DIR_KEY,"");File[] files=new File(folder).listFiles();assertNotNull(files);assertEquals(1,files.length);assertNotNull(android.graphics.BitmapFactory.decodeFile(files[0].getPath()));
            assertEquals("Shared contract",feature.contracts.current(dom).getJSONObject("contract").getString("name"));
            new AppModeManager(context).setArmed(true);String before=com.subhub.app.appmode.RuntimeSessionIdentity.current(context);assertFalse(before.isEmpty());feature.changed();write("sub-state","first-applied");
            await(()->!new AppModeManager(context).isArmed(),90_000);assertFalse(ControllerPinManager.verify(context,"2468"));assertEquals(com.subhub.app.security.KeyholderProvider.State.READY,People.keyholder().state());
            ControllerPinManager.enterSubMode();write("sub-state","awaiting-code");await(()->read("keyholder-code").matches("[0-9]{6}"),30_000);
            String code=read("keyholder-code");com.subhub.app.security.ControllerAuthenticator authenticator=new com.subhub.app.security.ControllerAuthenticator(context);
            assertEquals(com.subhub.app.security.ControllerAuthenticator.Result.SUCCESS,authenticator.verify(code));assertTrue(ControllerPinManager.isDomModeActive());assertFalse(People.isLocalConfigurationAllowed());
            assertEquals(com.subhub.app.security.ControllerAuthenticator.Result.INVALID,authenticator.verify(code));new File(directory,"keyholder-code").delete();ControllerPinManager.enterSubMode();
            assertEquals("kept-local",main.getString("paypal_secret_fixture",""));write("sub-state","ready-offline");
    }
    @Test public void subResumesQueuedConfigurationAndRetainsItAfterRevocation()throws Exception {
        RemoteStore.Peer dom=peer(RemoteProtocol.Role.SUB);assertNotNull(dom);assertEquals(RemoteStore.State.ACTIVE,dom.state);
        try(ActivityScenario<RemoteConnectionActivity> connection=ActivityScenario.launch(new Intent(context,RemoteConnectionActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID,RemotePersonRepository.id(dom).value()))) {
            feature.changed();await(()->"2".equals(feature.store.metadata(dom.identity,dom.generation,"applied_revision","0")),90_000);
            assertEquals("Queued offline",feature.contracts.current(dom).getJSONObject("contract").getString("name"));write("sub-state","second-applied");
            await(()->feature.store.peer(dom.identity,dom.generation).state==RemoteStore.State.REVOKED,90_000);
            assertEquals("Queued offline",feature.contracts.localApplied().getJSONObject("contract").getString("name"));assertTrue(People.isLocalConfigurationAllowed());
            assertEquals(com.subhub.app.security.KeyholderProvider.State.NONE,People.keyholder().state());assertNull(new RemoteSecrets(context).controllerSecret(RemoteProtocol.Role.SUB,dom.identity,dom.generation));assertTrue(ControllerPinManager.verify(context,"2468"));
            assertEquals("kept-local",context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).getString("paypal_secret_fixture",""));write("sub-state","complete");
        }
    }
    private void approveDialog()throws Exception {
        assertTrue(device.wait(Until.hasObject(By.text(context.getString(R.string.remote_pair_question))),5000));
        assertTrue(device.wait(Until.hasObject(By.res("android:id/button1")),5000));device.findObject(By.res("android:id/button1")).click();instrument.waitForIdleSync();
    }
    private void awaitEditor(ActivityScenario<RemoteContractActivity> scenario)throws Exception {
        await(()->{AtomicReference<Boolean> value=new AtomicReference<>(false);scenario.onActivity(activity->value.set(first(activity.findViewById(R.id.role_page_body),EditText.class)!=null));return value.get();},15_000);
    }
    private void name(ActivityScenario<RemoteContractActivity> scenario,String value){scenario.onActivity(activity->{EditText name=first(activity.findViewById(R.id.role_page_body),EditText.class);assertNotNull(name);name.setText(value);});instrument.waitForIdleSync();}
    private void toggleFirst(ActivityScenario<RemoteContractActivity> scenario,boolean desired)throws Exception {
        AtomicReference<View> target=new AtomicReference<>();scenario.onActivity(activity->{Switch toggle=first(activity.findViewById(R.id.role_page_body),Switch.class);assertNotNull(toggle);if(toggle.isChecked()!=desired)target.set(toggle);});if(target.get()!=null)tapView(target.get());
    }
    private <T extends Activity> void tap(ActivityScenario<T> scenario,String copy)throws Exception {
        AtomicReference<View> target=new AtomicReference<>();scenario.onActivity(activity->target.set(find(activity.findViewById(R.id.role_page_body),copy)));assertNotNull("Missing action: "+copy,target.get());tapView(target.get());
    }
    private void tapView(View target)throws Exception {
        instrument.runOnMainSync(()->target.requestRectangleOnScreen(new Rect(0,0,target.getWidth(),target.getHeight()),true));instrument.waitForIdleSync();
        AtomicReference<Rect> found=new AtomicReference<>();instrument.runOnMainSync(()->{Rect rectangle=new Rect();assertTrue(target.getGlobalVisibleRect(rectangle));found.set(rectangle);});Rect rectangle=found.get();assertTrue(device.click(rectangle.centerX(),rectangle.centerY()));instrument.waitForIdleSync();
    }
    private <T extends Activity> void awaitText(ActivityScenario<T> scenario,String copy)throws Exception {await(()->{AtomicReference<Boolean> value=new AtomicReference<>(false);scenario.onActivity(activity->value.set(find(activity.findViewById(R.id.role_page_body),copy)!=null));return value.get();},15_000);}
    private void capture(String name)throws Exception {device.waitForIdle();assertTrue(device.takeScreenshot(new File(directory,name)));}
    private RemoteStore.Peer peer(RemoteProtocol.Role role){for(RemoteStore.Peer peer:feature.store.peers(false))if(peer.localRole==role)return peer;return null;}
    private void write(String name,String value)throws IOException {try(OutputStream output=new FileOutputStream(new File(directory,name))){output.write(value.getBytes(StandardCharsets.UTF_8));}}
    private String read(String name){try{return new String(java.nio.file.Files.readAllBytes(new File(directory,name).toPath()),StandardCharsets.UTF_8);}catch(IOException absent){return "";}}
    private interface Check{boolean ready()throws Exception;}
    private void await(Check check,long milliseconds)throws Exception {long deadline=SystemClock.uptimeMillis()+milliseconds;while(!check.ready()&&SystemClock.uptimeMillis()<deadline)Thread.sleep(100);assertTrue("Flow deadline exceeded",check.ready());}
    private static View find(View root,String text){if(root instanceof TextView&&text.contentEquals(((TextView)root).getText())&&root.isShown())return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View value=find(((ViewGroup)root).getChildAt(i),text);if(value!=null)return value;}return null;}
    private static <T> T first(View root,Class<T> type){if(type.isInstance(root))return type.cast(root);if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){T value=first(((ViewGroup)root).getChildAt(i),type);if(value!=null)return value;}return null;}
}
