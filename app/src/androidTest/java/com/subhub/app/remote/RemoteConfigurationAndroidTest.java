package com.subhub.app.remote;

import static org.junit.Assert.*;
import android.content.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.configuration.*;
import com.subhub.app.settings.SettingsRepository;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;

@RunWith(AndroidJUnit4.class)
public class RemoteConfigurationAndroidTest {
    private static final String DOM=repeat('a'),SUB=repeat('b');
    private Context context;private RemoteStore dom,sub;private String generation,prefix;
    private File root;private RemoteAssets assets;private final Set<String> preferences=new HashSet<>();
    @Before public void setup() throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();prefix="remote-config-test-"+UUID.randomUUID();
        root=new File(target.getCacheDir(),prefix);assertTrue(root.mkdirs());
        context=new ContextWrapper(target) {
            @Override public Context getApplicationContext(){return this;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode) {preferences.add(name);return super.getSharedPreferences(prefix+name,mode);}
            @Override public File getFilesDir(){return directory("files");}
            @Override public File getNoBackupFilesDir(){return directory("private");}
            private File directory(String name){File file=new File(root,name);file.mkdirs();return file;}
        };
        assertTrue(com.subhub.app.configuration.LegacyPackMigration.recoverAndMigrate(context));
        dom=new RemoteStore(context,prefix+"dom.db");sub=new RemoteStore(context,prefix+"sub.db");
        PairingInvite invite=PairingInvite.create(DOM,"ticket",1000);generation=invite.id;
        dom.saveInvite(invite);dom.bindInvite(generation,SUB,invite.tokenHash(),1001);dom.approveInvite(generation,SUB,"Sub",1001);dom.activate(SUB,generation);
        sub.acceptInvite(invite,"Dom");sub.activate(DOM,generation);assets=new RemoteAssets(new File(root,"blobs"));
    }
    @After public void cleanup() throws Exception {
        dom.close();sub.close();context.deleteDatabase(prefix+"dom.db");context.deleteDatabase(prefix+"sub.db");
        for(String name:preferences)context.getSharedPreferences(name,0).edit().clear().commit();
        try(java.util.stream.Stream<java.nio.file.Path> files=java.nio.file.Files.walk(root.toPath())) {
            for(java.nio.file.Path file:files.sorted(Comparator.reverseOrder()).toArray(java.nio.file.Path[]::new))java.nio.file.Files.delete(file);
        }
    }
    @Test public void appliedReceiptIsSeparateFromTransportAckAndReplay() throws Exception {
        seed();RemoteStore.Command command=deliver(configuration());
        assertEquals(RemoteStore.CommandState.SENT,dom.latestCommand(SUB,generation).state);
        assertEquals("0",dom.metadata(SUB,generation,"applied_revision","0"));
        assertTrue(writer().apply(sub.peer(DOM,generation),command,assets,Set.of()));
        assertFalse(main().getBoolean("module_censor_enabled",true));
        assertEquals("credential",main().getString("paypal_secret_fixture",""));assertTrue(main().getBoolean("app_mode_armed",false));
        assertEquals(77L,main().getLong("commitment_end",0));
        assertEquals(-1L,context.getSharedPreferences(RemoteConfiguration.DURATION_PREFS,0).getLong("duration",0));
        assertEquals(RemoteStore.CommandState.APPLIED,sub.command(DOM,generation,command.id).state);
        RemoteProtocol.Batch receipt=sub.nextBatch(DOM,generation,RemoteProtocol.Stream.COMMAND_RESULT);dom.receive(SUB,receipt);
        dom.acceptResult(SUB,generation,new JSONObject(receipt.changes.get(0).body));
        assertEquals("1",dom.metadata(SUB,generation,"applied_revision","0"));
        main().edit().putBoolean("module_censor_enabled",true).commit();
        writer().apply(sub.peer(DOM,generation),sub.stageCommand(DOM,generation,new JSONObject(command.body)),assets,Set.of());
        assertTrue(main().getBoolean("module_censor_enabled",false));
    }
    @Test public void failedSecondStoreRestoresTypedValuesAndRetainedSettings() throws Exception {
        seed();Map<String,?> before=new HashMap<>(main().getAll());RemoteStore.Command command=deliver(configuration());int[] commits={0};
        RemoteConfigurationWriter failed=new RemoteConfigurationWriter(context,sub,(name,editor)->++commits[0]!=2 && editor.commit(),new File(root,"writer"));
        failed.apply(sub.peer(DOM,generation),command,assets,Set.of());
        assertEquals(before,main().getAll());assertEquals(RemoteStore.CommandState.REJECTED,sub.command(DOM,generation,command.id).state);
        assertFalse(new File(root,"writer/journal.json").exists());
    }
    @Test public void processDeathRollsBackBeforeRetryAndRecoverySurvivesRevocation() throws Exception {
        seed();Map<String,?> before=new HashMap<>(main().getAll());RemoteStore.Command command=deliver(configuration());
        RemoteConfigurationWriter crash=new RemoteConfigurationWriter(context,sub,(name,editor)->{editor.commit();throw new SimulatedDeath();},new File(root,"writer"));
        assertThrows(SimulatedDeath.class,()->crash.apply(sub.peer(DOM,generation),command,assets,Set.of()));
        assertFalse(main().getBoolean("module_censor_enabled",true));sub.revoke(DOM,generation);writer().recover();
        assertEquals(before,main().getAll());assertFalse(new File(root,"writer/journal.json").exists());
    }
    @Test public void staleRevisionUnavailableAppAndRevocationDoNotApply() throws Exception {
        seed();RemoteStore.Command first=deliver(configuration());sub.putMetadata(DOM,generation,"applied_revision","3");
        writer().apply(sub.peer(DOM,generation),first,assets,Set.of());assertEquals(RemoteStore.CommandState.CONFLICT,sub.command(DOM,generation,first.id).state);
        JSONObject unknown=configuration();unknown.getJSONObject("apps").getJSONArray("packages").put("com.unknown.app");
        JSONObject body=new JSONObject(first.body).put("id",UUID.randomUUID().toString()).put("revision",4).put("base",3).put("configuration",unknown);
        RemoteStore.Command next=sub.stageCommand(DOM,generation,body);writer().apply(sub.peer(DOM,generation),next,assets,Set.of());
        assertEquals(RemoteStore.CommandState.REJECTED,sub.command(DOM,generation,next.id).state);assertTrue(main().getBoolean("module_censor_enabled",false));
        sub.revoke(DOM,generation);assertThrows(SecurityException.class,()->sub.stageCommand(DOM,generation,body));
    }
    @Test public void assetsMustFinishAndCommandIdentityCannotChange() throws Exception {
        JSONObject doc=configuration();File file=new File(root,"image");try(FileOutputStream output=new FileOutputStream(file)){output.write(new byte[]{1,2,3});}
        String hash=RemoteAssets.digest(file);doc.getJSONObject("contract").getJSONArray("assets").put(new JSONObject().put("path","assets/censor/image.png").put("hash",hash).put("length",3));
        RemoteStore.Command command=deliver(doc);assertFalse(writer().apply(sub.peer(DOM,generation),command,assets,Set.of()));
        assertThrows(SecurityException.class,()->sub.stageCommand(DOM,generation,new JSONObject(command.body).put("base",7).put("revision",8)));
        assets.append(hash,0,new byte[]{1,2,3});assets.finish(hash);assertTrue(writer().apply(sub.peer(DOM,generation),command,assets,Set.of()));
        String path=main().getString(com.subhub.app.capture.CustomImageManager.PACK_DIR_KEY,"");assertTrue(new File(path,"image.png").isFile());
    }
    @Test public void protocolRejectsSecretsCoercionsIncompleteActiveAndConcurrentDraft() throws Exception {
        JSONObject doc=configuration();doc.getJSONObject("apps").put("all","true");assertThrows(IllegalArgumentException.class,()->RemoteConfiguration.active(doc));
        doc.getJSONObject("apps").put("all",false);doc.getJSONObject("service").put("hidden","false");assertThrows(IllegalArgumentException.class,()->RemoteConfiguration.active(doc));
        doc.getJSONObject("service").put("hidden",false);doc.getJSONObject("contract").getJSONObject("sections").getJSONObject("wallet").put("paypal_secret","oops");
        assertThrows(IllegalArgumentException.class,()->RemoteConfiguration.active(doc));
        deliver(configuration());assertThrows(IllegalStateException.class,()->dom.queueConfiguration(SUB,generation,1,0,configuration()));
        assertThrows(IllegalStateException.class,()->dom.queueConfiguration(SUB,generation,0,0,configuration()));
    }
    @Test public void unchangedHeadsRefreshOnlyCaughtUpStreams() throws Exception {
        sub.publish(DOM,generation,RemoteProtocol.Stream.BASELINE,"active",new JSONObject().put("count",1),false,1);
        dom.confirmHeads(SUB,generation,sub.outgoingHeads(DOM,generation));assertEquals("missing",dom.metadata(SUB,generation,"confirmed_BASELINE","missing"));
        RemoteProtocol.Batch batch=sub.nextBatch(DOM,generation,RemoteProtocol.Stream.BASELINE);dom.receive(SUB,batch);
        dom.confirmHeads(SUB,generation,sub.outgoingHeads(DOM,generation));assertTrue(Long.parseLong(dom.metadata(SUB,generation,"confirmed_BASELINE","0"))>0);
        assertThrows(SecurityException.class,()->dom.confirmHeads(SUB,generation,sub.outgoingHeads(DOM,generation).put("BASELINE",0)));
        sub.putMetadata(DOM,generation,"unavailable_BASELINE","true");dom.confirmHeads(SUB,generation,sub.outgoingHeads(DOM,generation));
        assertEquals("0",dom.metadata(SUB,generation,"confirmed_BASELINE","missing"));
    }


    @Test public void terminalReceiptCannotRegressAndCommittedJournalKeepsAppliedSettings() throws Exception {
        seed();JSONObject doc=configuration();RemoteStore.Command command=deliver(doc);int[] commits={0};
        RemoteConfigurationWriter crash=new RemoteConfigurationWriter(context,sub,(name,editor)-> {
            boolean result=editor.commit();if(++commits[0]==3) {
                try{sub.commandResult(DOM,generation,command,RemoteStore.CommandState.APPLIED,doc,"");}catch(Exception failure){throw new AssertionError(failure);}
                throw new SimulatedDeath();
            }
            return result;
        },new File(root,"writer"));
        assertThrows(SimulatedDeath.class,()->crash.apply(sub.peer(DOM,generation),command,assets,Set.of()));writer().recover();
        assertFalse(main().getBoolean("module_censor_enabled",true));assertFalse(new File(root,"writer/journal.json").exists());
        assertThrows(SecurityException.class,()->sub.commandResult(DOM,generation,command,RemoteStore.CommandState.REJECTED,null,"later failure"));
    }
    @Test public void removedArtworkDoesNotReactivateLocalSelectionsAndMetadataSurvivesUnpair() throws Exception {
        seed();main().edit().putString(com.subhub.app.capture.CustomImageManager.PREFS_KEY,"[{\"id\":\"old\",\"enabled\":true}]")
            .putStringSet(com.subhub.app.popup.PopupStormSettings.K_FOLDERS,Set.of("content://local-only")).commit();
        JSONObject doc=configuration();doc.getJSONObject("contract").put("name","Retained contract");
        RemoteStore.Command command=deliver(doc);RemoteConfigurationWriter live=new RemoteConfigurationWriter(context,sub);
        live.apply(sub.peer(DOM,generation),command,assets,Set.of());
        assertEquals("[]",main().getString(com.subhub.app.capture.CustomImageManager.PREFS_KEY,""));
        assertTrue(main().getStringSet(com.subhub.app.popup.PopupStormSettings.K_FOLDERS,Set.of("fallback")).isEmpty());
        sub.revoke(DOM,generation);RemoteContracts contracts=new RemoteContracts(context,sub,live);
        assertEquals("Retained contract",contracts.localApplied().getJSONObject("contract").getString("name"));
        main().edit().putBoolean("module_censor_enabled",true).commit();assertNull(contracts.localApplied());
        assertEquals("credential",main().getString("paypal_secret_fixture",""));
    }
    @Test public void queuedFeatureChangeIsRejectedWhenSubOptsOutBeforeDeliveryAndReplayStaysRejected() throws Exception {
        seed();
        JSONObject doc=configuration();doc.getJSONObject("contract").getJSONObject("sections").getJSONObject("censor").put("censor_intensity", 73);
        RemoteStore.Command queued=deliver(doc);
        assertTrue(com.subhub.app.security.SubFeaturePolicy.setAllowed(context,"censor",false));
        Map<String,?> before=new HashMap<>(main().getAll());
        assertTrue(writer().apply(sub.peer(DOM,generation),queued,assets,Set.of()));
        assertEquals(RemoteStore.CommandState.REJECTED,sub.command(DOM,generation,queued.id).state);
        assertEquals(before,main().getAll());
        assertTrue(com.subhub.app.security.SubFeaturePolicy.setAllowed(context,"censor",true));
        assertTrue(writer().apply(sub.peer(DOM,generation),sub.stageCommand(DOM,generation,new JSONObject(queued.body)),assets,Set.of()));
        assertEquals(RemoteStore.CommandState.REJECTED,sub.command(DOM,generation,queued.id).state);
        assertFalse(main().contains("censor_intensity"));
    }
    @Test public void optedOutRuntimeFeaturesStayOffWithoutReplacingSavedRulesOrCredentials() throws Exception {
        main().edit().putBoolean("module_censor_enabled",true).putBoolean("module_subliminal_enabled",true)
                .putBoolean("popup_storm_enabled",true).putBoolean("app_timer_per_app_enabled",true)
                .putBoolean("app_timer_total_enabled",true).commit();
        SharedPreferences wallet=context.getSharedPreferences(com.subhub.app.penance.PenanceManager.PREFS_NAME,0);
        wallet.edit().putBoolean(com.subhub.app.penance.PenanceManager.KEY_RULE_PARTICIPATION_MIGRATED,true).commit();
        new com.subhub.app.penance.PenanceManager(context).configure(true,400,1000,5000,5);
        for(String key:com.subhub.app.security.SubFeaturePolicy.FEATURES)assertTrue(com.subhub.app.security.SubFeaturePolicy.setAllowed(context,key,false));
        com.subhub.app.settings.FeatureModuleManager modules=new com.subhub.app.settings.FeatureModuleManager(context);
        assertFalse(modules.isCensorEnabled());assertFalse(modules.isLimitsEnabled());assertFalse(modules.isWalletEnabled());assertFalse(modules.isSubliminalEnabled());
        assertFalse(new com.subhub.app.appmode.AppTimerManager(context).loadSettings().anyEnabled());
        assertFalse(new com.subhub.app.penance.PenanceManager(context).isEnabled());
        assertFalse(com.subhub.app.popup.PopupStormSettings.load(context).isEnabled());
        assertTrue(main().getBoolean("module_censor_enabled",false));assertTrue(main().getBoolean("popup_storm_enabled",false));
        assertFalse(com.subhub.app.security.SubFeaturePolicy.hardcoreOverride(context));
    }
    @Test public void remoteSnapshotsCannotContainLocalConsentOrHardcoreAuthority() throws Exception {
        JSONObject doc=configuration();
        doc.getJSONObject("contract").getJSONObject("sections").getJSONObject("modules").put("hardcore_locally_consented",true);
        assertThrows(IllegalArgumentException.class,()->RemoteConfiguration.active(doc));
        assertFalse(com.subhub.app.security.SubFeaturePolicy.hardcoreOverride(context));
    }

    @Test public void optingOutOfOneFeatureStillAllowsAnUnrelatedDurationChange() throws Exception {
        seed();JSONObject doc=configuration();doc.getJSONObject("contract").getJSONObject("sections").getJSONObject("modules").put("module_censor_enabled",true);
        doc.getJSONObject("service").put("hidden",true);
        assertTrue(com.subhub.app.security.SubFeaturePolicy.setAllowed(context,"censor",false));
        RemoteStore.Command command=deliver(doc);assertTrue(writer().apply(sub.peer(DOM,generation),command,assets,Set.of()));
        assertEquals(RemoteStore.CommandState.APPLIED,sub.command(DOM,generation,command.id).state);
        assertTrue(context.getSharedPreferences(RemoteConfiguration.DURATION_PREFS,0).getBoolean("hidden",false));
        assertTrue(main().getBoolean("module_censor_enabled",false));
        assertFalse(new com.subhub.app.settings.FeatureModuleManager(context).isCensorEnabled());
    }
    @Test public void disabledLimitsAlsoRefuseChangesToIndividualAllowances() throws Exception {
        JSONObject doc=configuration();doc.getJSONObject("apps").getJSONObject("allowances").put("com.android.settings",73);
        assertTrue(com.subhub.app.security.SubFeaturePolicy.setAllowed(context,"limits",false));
        RemoteStore.Command command=deliver(doc);assertTrue(writer().apply(sub.peer(DOM,generation),command,assets,Set.of()));
        assertEquals(RemoteStore.CommandState.REJECTED,sub.command(DOM,generation,command.id).state);
        assertFalse(main().contains("app_timer_allowance_minutes:com.android.settings"));
    }

    private RemoteConfigurationWriter writer(){return new RemoteConfigurationWriter(context,sub,(name,editor)->editor.commit(),new File(root,"writer"));}
    private RemoteStore.Command deliver(JSONObject doc) throws Exception {
        RemoteStore.Command queued=dom.queueConfiguration(SUB,generation,0,0,doc);RemoteProtocol.Batch batch=dom.nextBatch(SUB,generation,RemoteProtocol.Stream.CONFIGURATION);
        dom.acknowledge(SUB,sub.receive(DOM,batch));dom.markSent(SUB,batch);return sub.stageCommand(DOM,generation,new JSONObject(queued.body));
    }
    static JSONObject configuration() throws Exception {
        JSONObject sections=new JSONObject();
        for(String section:ConfigurationSchema.SECTIONS)sections.put(section,ConfigurationSettingCatalog.defaults(section));
        sections.getJSONObject("modules").put("module_censor_enabled",false);
        JSONObject metadata=RemoteConfiguration.currentMetadata(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"Contract",1,1);
        return RemoteConfiguration.fromSections(metadata,sections,new JSONArray(),new JSONObject().put("all",false).put("packages",new JSONArray()).put("allowances",new JSONObject()),
                new JSONObject().put("duration",-1).put("minimum",3600000).put("maximum",86400000).put("hidden",false));
    }
    private SharedPreferences main(){return context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0);}
    private void seed(){main().edit().putBoolean("module_censor_enabled",true).putString("paypal_secret_fixture","credential").putBoolean("app_mode_armed",true)
            .putLong("commitment_end",77).putFloat("test_retained_float",0.2f).putStringSet("test_retained_set",Set.of("x")).commit();}
    private static String repeat(char c){char[] result=new char[64];Arrays.fill(result,c);return new String(result);}
    private static final class SimulatedDeath extends Error { }
}
