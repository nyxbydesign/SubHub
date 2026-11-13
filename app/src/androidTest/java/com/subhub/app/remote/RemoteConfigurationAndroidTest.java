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

    @Test public void waitingConfigurationWriterLeavesAuthorityAvailableForActualTaskSweep() throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        RemoteStore shared=RemoteFeature.get(target).store;
        assertFalse("Synthetic guest must not have an assigned Dom",shared.isRemotelyControlled());
        shared.getWritableDatabase().execSQL("INSERT INTO peers(peer,generation,name,local_role,state) VALUES(?,?,'Synthetic lock fixture','SUB','ACTIVE')",
                new Object[]{DOM,generation});
        java.util.concurrent.ExecutorService workers=java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.atomic.AtomicReference<Thread> writerThread=new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.Future<Boolean> applying=null;
        try {
            RemoteStore.Command command=shared.stageCommand(DOM,generation,new JSONObject(deliver(configuration()).body));
            RemoteConfigurationWriter writer=new RemoteConfigurationWriter(context,shared);
            synchronized(com.subhub.app.security.SubFeaturePolicy.LOCK) {
                applying=workers.submit(()->{writerThread.set(Thread.currentThread());
                    return writer.apply(shared.peer(DOM,generation),command,assets,Set.of());});
                long deadline=android.os.SystemClock.elapsedRealtime()+5000;
                boolean waiting=false;
                while(android.os.SystemClock.elapsedRealtime()<deadline) {
                    Thread thread=writerThread.get();
                    if(thread!=null && thread.getState()==Thread.State.BLOCKED) {
                        for(StackTraceElement frame:thread.getStackTrace())
                            if(frame.getClassName().equals(RemoteConfigurationWriter.class.getName())
                                    && frame.getMethodName().equals("apply"))waiting=true;
                    }
                    if(waiting)break;
                    Thread.yield();
                }
                assertTrue("Writer did not reach the held consent lock",waiting);
                // This bounded observer fails without deadlocking cleanup if apply holds store first.
                assertNotNull(workers.submit(()->shared.peer(DOM,generation)).get(2,java.util.concurrent.TimeUnit.SECONDS));
                com.subhub.app.tasks.TaskRepository repository=new com.subhub.app.tasks.TaskRepository(context,
                        com.subhub.app.people.PersonId.LOCAL);
                assertEquals(generation,repository.target().peer.generation);
                assertTrue(repository.list(System.currentTimeMillis()).isEmpty());
            }
            assertTrue(applying.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(RemoteStore.CommandState.APPLIED,shared.command(DOM,generation,command.id).state);
        } finally {
            workers.shutdown();assertTrue(workers.awaitTermination(15,java.util.concurrent.TimeUnit.SECONDS));
            android.database.sqlite.SQLiteDatabase db=shared.getWritableDatabase();
            for(String table:new String[]{"session_controls","local_objects","outbox","remote_objects","outgoing","incoming","commands","remote_metadata","peers"})
                db.delete(table,"peer=? AND generation=?",new String[]{DOM,generation});
        }
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
    @Test public void validatedCheckpointRecordsContactWithoutCompletingSyncAndRejectsInvalidOrRevokedHeads() throws Exception {
        long completed=System.currentTimeMillis()-12*60*60_000L;
        sub.synced(DOM,generation,completed);
        long started=System.currentTimeMillis();JSONObject heads=dom.outgoingHeads(SUB,generation);
        sub.confirmHeads(DOM,generation,heads);
        assertEquals(completed,sub.peer(DOM,generation).lastSyncedAt);
        assertTrue(sub.lastContactAt(sub.peer(DOM,generation))>=started);
        String observed=sub.metadata(DOM,generation,"last_contact","");
        sub.putMetadata(DOM,generation,"confirmed_CONFIGURATION","1");
        assertThrows(JSONException.class,()->sub.confirmHeads(DOM,generation,new JSONObject(heads.toString()).put("UNKNOWN",0)));
        assertEquals(observed,sub.metadata(DOM,generation,"last_contact",""));
        assertEquals("1",sub.metadata(DOM,generation,"confirmed_CONFIGURATION",""));
        String other=UUID.randomUUID().toString();
        assertThrows(SecurityException.class,()->sub.confirmHeads(DOM,other,heads));
        assertEquals("missing",sub.metadata(DOM,other,"last_contact","missing"));
        sub.revoke(DOM,generation);
        assertThrows(SecurityException.class,()->sub.confirmHeads(DOM,generation,heads));
        assertEquals(observed,sub.metadata(DOM,generation,"last_contact",""));
        assertEquals(completed,sub.peer(DOM,generation).lastSyncedAt);
    }

    @Test public void legacyContactUsesOnlyLocalRoleCorrectPastCheckpointsUntilExplicitObservationExists() throws Exception {
        long now=System.currentTimeMillis(),completed=now-12*60*60_000L,contact=now-180_000;
        sub.synced(DOM,generation,completed);
        sub.getWritableDatabase().delete("remote_metadata","peer=? AND generation=? AND name=?",new String[]{DOM,generation,"last_contact"});
        sub.putMetadata(DOM,generation,"confirmed_CONFIGURATION",Long.toString(contact));
        sub.putMetadata(DOM,generation,"confirmed_STATS",Long.toString(now-1));
        RemoteStore.Peer peer=sub.peer(DOM,generation);
        assertEquals(contact,sub.lastContactAt(peer,now));
        assertFalse(PeerPresence.online(false,contact,now));
        for(String invalid:new String[]{"invalid","0","-1",Long.toString(now+1)}){
            sub.putMetadata(DOM,generation,"confirmed_CONFIGURATION",invalid);
            assertEquals(completed,sub.lastContactAt(peer,now));
        }
        sub.putMetadata(DOM,generation,"confirmed_CONFIGURATION",Long.toString(contact));
        for(String invalid:new String[]{"invalid","0","-1",Long.toString(now+1)}){
            sub.putMetadata(DOM,generation,"last_contact",invalid);
            assertEquals(completed,sub.lastContactAt(peer,now));
        }
        sub.putMetadata(DOM,generation,"last_contact",Long.toString(contact));
        sub.putMetadata(DOM,generation,"confirmed_CONFIGURATION",Long.toString(now-1));
        assertEquals(contact,sub.lastContactAt(peer,now));
        assertEquals(completed,sub.peer(DOM,generation).lastSyncedAt);
    }

    @Test public void unchangedHeadsRefreshOnlyCaughtUpStreams() throws Exception {
        sub.publish(DOM,generation,RemoteProtocol.Stream.STATS,"summary",new JSONObject().put("count",1),false,1);
        dom.confirmHeads(SUB,generation,sub.outgoingHeads(DOM,generation));assertEquals("missing",dom.metadata(SUB,generation,"confirmed_STATS","missing"));
        RemoteProtocol.Batch batch=sub.nextBatch(DOM,generation,RemoteProtocol.Stream.STATS);dom.receive(SUB,batch);
        dom.confirmHeads(SUB,generation,sub.outgoingHeads(DOM,generation));assertTrue(Long.parseLong(dom.metadata(SUB,generation,"confirmed_STATS","0"))>0);
        assertThrows(SecurityException.class,()->dom.confirmHeads(SUB,generation,sub.outgoingHeads(DOM,generation).put("STATS",0)));
        sub.putMetadata(DOM,generation,"unavailable_STATS","true");dom.confirmHeads(SUB,generation,sub.outgoingHeads(DOM,generation));
        assertEquals("0",dom.metadata(SUB,generation,"confirmed_STATS","missing"));
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
    /** Persisted Hardcore state must not even be consulted to decide feature participation. */
    @Test public void hardcoreStateCannotOverrideAnySavedFeatureChoice() throws Exception {
        Context policy=hardcoreFeaturePolicyContext();
        List<String> features=new ArrayList<>(com.subhub.app.security.SubFeaturePolicy.FEATURES);features.add("tasks");
        for(String feature:features)assertTrue(com.subhub.app.security.SubFeaturePolicy.setAllowed(context,feature,false));
        Map<String,?> saved=new HashMap<>(context.getSharedPreferences(com.subhub.app.security.SubFeaturePolicy.STORE,0).getAll());
        for(String feature:features){
            assertFalse(feature,com.subhub.app.security.SubFeaturePolicy.allowed(policy,feature));
            assertFalse(feature,com.subhub.app.security.SubFeaturePolicy.chosen(policy,feature));
        }
        assertFalse(com.subhub.app.security.SubFeaturePolicy.hardcoreOverride(policy));
        assertEquals(saved,context.getSharedPreferences(com.subhub.app.security.SubFeaturePolicy.STORE,0).getAll());
        assertTrue(com.subhub.app.security.SubFeaturePolicy.setAllowed(context,"wallet",true));
        assertTrue(com.subhub.app.security.SubFeaturePolicy.setAllowed(context,"tasks",true));
        assertTrue(com.subhub.app.security.SubFeaturePolicy.allowed(policy,"wallet"));
        assertTrue(com.subhub.app.security.SubFeaturePolicy.allowed(policy,"tasks"));
        assertFalse(com.subhub.app.security.SubFeaturePolicy.allowed(policy,"censor"));
        assertTrue(main().getBoolean(com.subhub.app.security.HardcoreModeManager.KEY_REQUESTED,false));
        assertTrue(context.getSharedPreferences(com.subhub.app.security.SubFeaturePolicy.STORE,0).getBoolean("hardcore_locally_consented",false));
    }
    @Test public void hardcoreStateCannotBypassQueuedFeatureRefusal() throws Exception {
        seed();Context policy=hardcoreFeaturePolicyContext();
        JSONObject doc=configuration();doc.getJSONObject("contract").getJSONObject("sections").getJSONObject("censor").put("censor_intensity",73);
        RemoteStore.Command queued=deliver(doc);
        assertTrue(com.subhub.app.security.SubFeaturePolicy.setAllowed(context,"censor",false));
        Map<String,?> before=new HashMap<>(main().getAll());
        Map<String,?> choices=new HashMap<>(context.getSharedPreferences(com.subhub.app.security.SubFeaturePolicy.STORE,0).getAll());
        RemoteConfigurationWriter guarded=new RemoteConfigurationWriter(policy,sub,(name,editor)->editor.commit(),new File(root,"hardcore-writer"));
        assertTrue(guarded.apply(sub.peer(DOM,generation),queued,assets,Set.of()));
        assertEquals(RemoteStore.CommandState.REJECTED,sub.command(DOM,generation,queued.id).state);
        assertEquals(before,main().getAll());assertEquals(choices,context.getSharedPreferences(com.subhub.app.security.SubFeaturePolicy.STORE,0).getAll());
        assertTrue(guarded.apply(sub.peer(DOM,generation),sub.stageCommand(DOM,generation,new JSONObject(queued.body)),assets,Set.of()));
        assertEquals(RemoteStore.CommandState.REJECTED,sub.command(DOM,generation,queued.id).state);
        assertFalse(main().contains("censor_intensity"));
    }
    private Context hardcoreFeaturePolicyContext() {
        assertTrue(main().edit().putBoolean(com.subhub.app.security.HardcoreModeManager.KEY_REQUESTED,true).commit());
        assertTrue(context.getSharedPreferences(com.subhub.app.security.SubFeaturePolicy.STORE,0).edit().putBoolean("hardcore_locally_consented",true).commit());
        // This controlled policy fixture grants no Device Admin capability and changes no real authority.
        return new ContextWrapper(context) {
            @Override public Context getApplicationContext(){return this;}
            @Override public Object getSystemService(String name){
                if(Context.DEVICE_POLICY_SERVICE.equals(name))throw new AssertionError("Feature choices must not consult Hardcore admin state");
                return super.getSystemService(name);
            }
        };
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

    @Test public void gagRulesRequireNegotiationAndAnAppliedCommandFromTheCurrentPairing() throws Exception {
        JSONObject doc=GagContractFields.forPeer(configuration(),true,false);
        GagContractFields.section(doc).put("enabled",true).put("mode","MATCH").put("words","fixture");
        assertThrows(SecurityException.class,()->dom.queueConfiguration(SUB,generation,0,0,doc));
        assertThrows(SecurityException.class,()->sub.queueConfiguration(DOM,generation,0,0,doc));
        dom.putMetadata(SUB,generation,GagContractFields.VERSION,"1");
        sub.putMetadata(DOM,generation,GagContractFields.VERSION,"1");
        RemoteStore.Peer owner=sub.peer(DOM,generation);
        RemoteStore.Command command=deliver(doc);
        assertNull(GagContractFields.applied(sub,owner));
        assertTrue(writer().apply(owner,command,assets,Set.of()));
        assertTrue(GagContractFields.applied(sub,owner).getBoolean("enabled"));
        assertNull(GagContractFields.applied(dom,dom.peer(SUB,generation)));
        sub.revoke(DOM,generation);
        assertNull(GagContractFields.applied(sub,owner));
        PairingInvite next=PairingInvite.create(DOM,"next",2000);
        sub.acceptInvite(next,"Same Dom, new pairing"); sub.activate(DOM,next.id);
        sub.putMetadata(DOM,next.id,GagContractFields.VERSION,"1");
        assertNull(GagContractFields.applied(sub,sub.peer(DOM,next.id)));
    }

    private RemoteConfigurationWriter writer(){return new RemoteConfigurationWriter(context,sub,(name,editor)->editor.commit(),new File(root,"writer"));}
    private RemoteStore.Command deliver(JSONObject doc) throws Exception {
        RemoteStore.Command queued=dom.queueConfiguration(SUB,generation,0,0,doc);RemoteProtocol.Batch batch=dom.nextBatch(SUB,generation,RemoteProtocol.Stream.CONFIGURATION);
        dom.acknowledge(SUB,sub.receive(DOM,batch));dom.markSent(SUB,batch);return sub.stageCommand(DOM,generation,new JSONObject(queued.body));
    }
    @Test public void contractDomProfileFollowsAppliedOwnerDespiteOtherRelationshipsAndUpdates() throws Exception {
        RemoteContracts contracts = new RemoteContracts(context, sub, writer());
        RemoteStore.Peer owner = sub.peer(DOM, generation);
        assertEquals("Dom", RemoteProfiles.contractDom(context, sub, contracts).name);
        assertEquals("", RemoteProfiles.contractDom(context, sub, contracts).avatar);
        com.subhub.app.people.ProfileData first = new com.subhub.app.people.ProfileData("Contract owner", "Owner bio", "");
        RemoteProfiles.receive(sub, owner, RemoteProfiles.encode(first, RemoteProtocol.Role.DOM));
        PairingInvite other = PairingInvite.create(SUB, "other", 1000);
        sub.saveInvite(other); sub.bindInvite(other.id, repeat('c'), other.tokenHash(), 1001);
        sub.approveInvite(other.id, repeat('c'), "Unrelated Sub", 1001); sub.activate(repeat('c'), other.id);
        RemoteProfiles.receive(sub, sub.peer(repeat('c'), other.id), RemoteProfiles.encode(
                new com.subhub.app.people.ProfileData("Unrelated private profile", "Do not show", ""), RemoteProtocol.Role.SUB));
        RemoteStore.Command command = deliver(configuration());
        assertTrue(writer().apply(owner, command, assets, Set.of())); assertNotNull(contracts.localApplied());
        assertEquals(first, RemoteProfiles.contractDom(context, sub, contracts));
        android.graphics.Bitmap image = android.graphics.Bitmap.createBitmap(192, 192, android.graphics.Bitmap.Config.ARGB_8888);
        image.eraseColor(android.graphics.Color.BLUE); String avatar;
        try { avatar = com.subhub.app.people.ProfileImages.encode(image); } finally { image.recycle(); }
        com.subhub.app.people.ProfileData updated = new com.subhub.app.people.ProfileData("Updated owner", "Changed after reconnect", avatar);
        RemoteProfiles.receive(sub, owner, RemoteProfiles.encode(updated, RemoteProtocol.Role.DOM));
        assertEquals(updated, RemoteProfiles.contractDom(context, sub, contracts));
        RemoteProfiles.receive(sub, owner, RemoteProfiles.encode(new com.subhub.app.people.ProfileData("Updated owner", "", ""), RemoteProtocol.Role.DOM));
        assertEquals("", RemoteProfiles.contractDom(context, sub, contracts).about);
        assertEquals("", RemoteProfiles.contractDom(context, sub, contracts).avatar);
        assertEquals(0, sub.peer(DOM, generation).lastSyncedAt);
        main().edit().putBoolean("module_censor_enabled", true).commit(); assertNull(contracts.localApplied());
        assertEquals("Dom", RemoteProfiles.contractDom(context, sub, contracts).name);
    }

    @Test public void rePairingCannotAttributeRetainedContractToTheNextDomOrGeneration() throws Exception {
        RemoteContracts contracts = new RemoteContracts(context, sub, writer());
        RemoteProfiles.receive(sub, sub.peer(DOM, generation), RemoteProfiles.encode(
                new com.subhub.app.people.ProfileData("Previous Dom", "Previous private bio", ""), RemoteProtocol.Role.DOM));
        RemoteStore.Command old = deliver(configuration()); assertTrue(writer().apply(sub.peer(DOM, generation), old, assets, Set.of()));
        assertEquals("Previous Dom", RemoteProfiles.contractDom(context, sub, contracts).name);
        sub.revoke(DOM, generation);
        PairingInvite next = PairingInvite.create(repeat('d'), "next", 2000);
        sub.acceptInvite(next, "Next Dom"); sub.activate(repeat('d'), next.id);
        RemoteStore.Peer nextOwner = sub.peer(repeat('d'), next.id);
        RemoteProfiles.receive(sub, nextOwner, RemoteProfiles.encode(
                new com.subhub.app.people.ProfileData("Next Dom", "Next private bio", ""), RemoteProtocol.Role.DOM));
        assertEquals("Dom", RemoteProfiles.contractDom(context, sub, contracts).name);
        assertEquals("", RemoteProfiles.contractDom(context, sub, contracts).about);
        RemoteStore.Command queued = sub.stageCommand(repeat('d'), next.id, new JSONObject(old.body));
        assertEquals(RemoteStore.CommandState.QUEUED, queued.state);
        assertEquals("Dom", RemoteProfiles.contractDom(context, sub, contracts).name);
        assertTrue(writer().apply(nextOwner, queued, assets, Set.of()));
        assertEquals("Next Dom", RemoteProfiles.contractDom(context, sub, contracts).name);
        assertEquals("Next private bio", RemoteProfiles.contractDom(context, sub, contracts).about);
        sub.revoke(repeat('d'), next.id);
        PairingInvite renewed = PairingInvite.create(repeat('d'), "renewed", 3000);
        sub.acceptInvite(renewed, "Renewed Dom"); sub.activate(repeat('d'), renewed.id);
        RemoteProfiles.receive(sub, sub.peer(repeat('d'), renewed.id), RemoteProfiles.encode(
                new com.subhub.app.people.ProfileData("Renewed Dom", "Fresh generation bio", ""), RemoteProtocol.Role.DOM));
        assertEquals("Dom", RemoteProfiles.contractDom(context, sub, contracts).name);
        assertEquals("", RemoteProfiles.contractDom(context, sub, contracts).about);
    }

    @Test public void optionalNotificationChoicesDoNotChangeAppliedWriterReceiptsOrReplay() throws Exception {
        seed();
        JSONObject previous = configuration(), changed = new JSONObject(previous.toString());
        changed.getJSONObject("service").put("hidden", true);
        sub.putMetadata(DOM, generation, "initial_configuration", previous.toString());
        assertTrue(com.subhub.app.notifications.NotificationPreferences.set(context,
                com.subhub.app.notifications.NotificationPreferences.Role.SUB,
                com.subhub.app.notifications.NotificationPreferences.Event.CONTRACT_CHANGED, false));
        RemoteStore.Command command = deliver(changed);
        assertTrue(writer().apply(sub.peer(DOM, generation), command, assets, Set.of()));
        assertEquals(RemoteStore.CommandState.APPLIED, sub.command(DOM, generation, command.id).state);
        String key = "SUB:CONTRACT_CHANGED:" + DOM + ":" + generation;
        SharedPreferences receipts = context.getSharedPreferences("local_notification_receipts", 0);
        assertEquals(command.revision, receipts.getLong(key, 0));
        Map<String, ?> consumed = new HashMap<>(receipts.getAll());
        assertTrue(com.subhub.app.notifications.NotificationPreferences.set(context,
                com.subhub.app.notifications.NotificationPreferences.Role.SUB,
                com.subhub.app.notifications.NotificationPreferences.Event.CONTRACT_CHANGED, true));
        assertTrue(writer().apply(sub.peer(DOM, generation), command, assets, Set.of()));
        assertEquals(consumed, receipts.getAll());
        assertEquals(RemoteStore.CommandState.APPLIED, sub.command(DOM, generation, command.id).state);
    }

    @Test public void incompletePriorNotificationDocumentCannotFailCommittedConfiguration() throws Exception {
        seed();
        main().edit().putString("remote_active_configuration", "{}").commit();
        RemoteStore.Command command = deliver(configuration());
        assertTrue(writer().apply(sub.peer(DOM, generation), command, assets, Set.of()));
        assertEquals(RemoteStore.CommandState.APPLIED, sub.command(DOM, generation, command.id).state);
        assertFalse(new File(root, "writer/journal.json").exists());
    }

    @Test public void autoEditsCoalesceAcrossRestartAndWaitForAppliedReceipt() throws Exception {
        JSONObject first=configuration();RemoteStore.Command command=dom.queueConfiguration(SUB,generation,0,0,first);
        JSONObject second=new JSONObject(first.toString());second.getJSONObject("service").put("hidden",true);
        dom.saveConfigurationEdit(SUB,generation,RemoteProtocol.canonical(second));
        JSONObject last=new JSONObject(second.toString());last.getJSONObject("service").put("duration",86400000);
        dom.saveConfigurationEdit(SUB,generation,RemoteProtocol.canonical(last));
        dom.close();dom=new RemoteStore(context,prefix+"dom.db");
        RemoteConfigurationEdits edits=new RemoteConfigurationEdits(context,dom);RemoteStore.Peer peer=dom.peer(SUB,generation);
        assertTrue(RemoteConfigurationEdits.same(last,edits.editing(peer)));
        edits.drain(peer);assertEquals(command.id,dom.latestCommand(SUB,generation).id);
        dom.acceptResult(SUB,generation,new JSONObject().put("schema",1).put("id",command.id).put("revision",1)
                .put("state","APPLIED").put("appliedRevision",1).put("reason",""));
        edits.drain(peer);RemoteStore.Command next=dom.latestCommand(SUB,generation);
        assertEquals(2,next.revision);assertEquals(1,new JSONObject(next.body).getLong("base"));
        assertTrue(RemoteConfigurationEdits.same(last,RemoteConfiguration.active(new JSONObject(next.body).getJSONObject("configuration"))));
        assertEquals("",dom.metadata(SUB,generation,"configuration_edit",""));
        edits.drain(peer);assertEquals(next.id,dom.latestCommand(SUB,generation).id);
    }

    @Test public void reconnectDrainsPreserveSentCommandAndPublishLatestEditOnlyAfterAppliedReceipt() throws Exception {
        JSONObject first=configuration();RemoteStore.Command original=dom.queueConfiguration(SUB,generation,0,0,first);
        RemoteProtocol.Batch sent=dom.nextBatch(SUB,generation,RemoteProtocol.Stream.CONFIGURATION);
        dom.acknowledge(SUB,sub.receive(DOM,sent));dom.markSent(SUB,sent);
        JSONObject latest=new JSONObject(first.toString());latest.getJSONObject("service").put("hidden",true);
        dom.saveConfigurationEdit(SUB,generation,RemoteProtocol.canonical(latest));
        dom.close();dom=new RemoteStore(context,prefix+"dom.db");
        RemoteContracts contracts=new RemoteContracts(context,dom,new RemoteConfigurationWriter(context,dom));
        RemoteStore.Peer peer=dom.peer(SUB,generation);
        for(int i=0;i<3;i++)contracts.process(peer);
        RemoteStore.Command retained=dom.latestCommand(SUB,generation);
        assertEquals(original.id,retained.id);assertEquals(original.body,retained.body);
        assertEquals(RemoteStore.CommandState.SENT,retained.state);
        assertEquals("0",dom.metadata(SUB,generation,"applied_revision","0"));
        assertEquals(RemoteProtocol.canonical(latest),dom.metadata(SUB,generation,"configuration_edit",""));
        JSONObject receipt=new JSONObject().put("schema",1).put("id",original.id).put("revision",1)
                .put("state","APPLIED").put("appliedRevision",1).put("reason","");
        dom.acceptResult(SUB,generation,receipt);contracts.process(peer);
        RemoteStore.Command next=dom.latestCommand(SUB,generation);
        assertEquals(2,next.revision);assertEquals(1,new JSONObject(next.body).getLong("base"));
        assertTrue(RemoteConfigurationEdits.same(latest,RemoteConfiguration.active(new JSONObject(next.body).getJSONObject("configuration"))));
        dom.acceptResult(SUB,generation,receipt);
        for(int i=0;i<3;i++)contracts.process(peer);
        assertEquals(next.id,dom.latestCommand(SUB,generation).id);
        assertEquals("2",dom.metadata(SUB,generation,"desired_revision","0"));
    }

    @Test public void reconnectDrainsNeverRetryRejectedConfiguration() throws Exception {
        assertTerminalSurvivesReconnect(RemoteStore.CommandState.REJECTED);
    }

    @Test public void reconnectDrainsNeverRetryConfigurationConflict() throws Exception {
        assertTerminalSurvivesReconnect(RemoteStore.CommandState.CONFLICT);
    }

    private void assertTerminalSurvivesReconnect(RemoteStore.CommandState state) throws Exception {
        JSONObject first=configuration();RemoteStore.Command original=dom.queueConfiguration(SUB,generation,0,0,first);
        JSONObject latest=new JSONObject(first.toString());latest.getJSONObject("service").put("hidden",true);
        dom.saveConfigurationEdit(SUB,generation,RemoteProtocol.canonical(latest));
        dom.acceptResult(SUB,generation,new JSONObject().put("schema",1).put("id",original.id).put("revision",1)
                .put("state",state.name()).put("appliedRevision",0).put("reason","Stopped"));
        dom.close();dom=new RemoteStore(context,prefix+"dom.db");
        RemoteContracts contracts=new RemoteContracts(context,dom,new RemoteConfigurationWriter(context,dom));
        RemoteStore.Peer peer=dom.peer(SUB,generation);
        for(int i=0;i<3;i++)contracts.process(peer);
        assertEquals(original.id,dom.latestCommand(SUB,generation).id);
        assertEquals(state,dom.latestCommand(SUB,generation).state);
        assertEquals("1",dom.metadata(SUB,generation,"desired_revision","0"));
        assertEquals(RemoteProtocol.canonical(latest),dom.metadata(SUB,generation,"configuration_edit",""));
        assertFalse(contracts.editFailure(peer).isEmpty());
    }

    @Test public void reconnectDrainRechecksNewConsentBeforePublishingQueuedEdit() throws Exception {
        JSONObject first=configuration();RemoteStore.Command original=dom.queueConfiguration(SUB,generation,0,0,first);
        JSONObject latest=new JSONObject(first.toString());
        latest.getJSONObject("contract").getJSONObject("sections").getJSONObject("modules").put("module_censor_enabled",true);
        dom.saveConfigurationEdit(SUB,generation,RemoteProtocol.canonical(latest));
        dom.acceptResult(SUB,generation,new JSONObject().put("schema",1).put("id",original.id).put("revision",1)
                .put("state","APPLIED").put("appliedRevision",1).put("reason",""));
        JSONObject consent=new JSONObject();
        for(String feature:com.subhub.app.security.SubFeaturePolicy.FEATURES)consent.put(feature,!"censor".equals(feature));
        sub.publish(DOM,generation,RemoteProtocol.Stream.RUNTIME,"current",new JSONObject().put("featureConsent",consent),false,1000);
        dom.receive(SUB,sub.nextBatch(DOM,generation,RemoteProtocol.Stream.RUNTIME));
        RemoteContracts contracts=new RemoteContracts(context,dom,new RemoteConfigurationWriter(context,dom));
        RemoteStore.Peer peer=dom.peer(SUB,generation);
        for(int i=0;i<3;i++)contracts.process(peer);
        assertEquals(original.id,dom.latestCommand(SUB,generation).id);
        assertEquals(RemoteProtocol.canonical(latest),dom.metadata(SUB,generation,"configuration_edit",""));
        assertFalse(contracts.editFailure(peer).isEmpty());
    }

    @Test public void rejectedAutoEditStopsUntilExplicitDiscardAndNeverErasesPendingCommand() throws Exception {
        JSONObject first=configuration();RemoteStore.Command command=dom.queueConfiguration(SUB,generation,0,0,first);
        JSONObject second=new JSONObject(first.toString());second.getJSONObject("service").put("hidden",true);
        dom.saveConfigurationEdit(SUB,generation,RemoteProtocol.canonical(second));
        assertThrows(IllegalStateException.class,()->dom.resetConfigurationEdits(SUB,generation));
        RemoteConfigurationEdits edits=new RemoteConfigurationEdits(context,dom);RemoteStore.Peer peer=dom.peer(SUB,generation);
        dom.acceptResult(SUB,generation,new JSONObject().put("schema",1).put("id",command.id).put("revision",1)
                .put("state","REJECTED").put("appliedRevision",0).put("reason","Invalid"));
        edits.drain(peer);assertEquals(command.id,dom.latestCommand(SUB,generation).id);
        assertFalse(edits.failure(peer).isEmpty());assertTrue(RemoteConfigurationEdits.same(second,edits.editing(peer)));
        dom.resetConfigurationEdits(SUB,generation);
        assertEquals("",dom.metadata(SUB,generation,"configuration_edit",""));
        assertEquals(RemoteStore.CommandState.REJECTED,dom.command(SUB,generation,command.id).state);
        assertEquals("",edits.failure(peer));
    }

    @Test public void identicalPendingSnapshotDoesNotAllocateAnotherRevisionAfterAck() throws Exception {
        JSONObject snapshot=configuration();RemoteStore.Command first=dom.queueConfiguration(SUB,generation,0,0,snapshot);
        dom.saveConfigurationEdit(SUB,generation,RemoteProtocol.canonical(snapshot));
        dom.acceptResult(SUB,generation,new JSONObject().put("schema",1).put("id",first.id).put("revision",1)
                .put("state","APPLIED").put("appliedRevision",1).put("reason",""));
        RemoteConfigurationEdits edits=new RemoteConfigurationEdits(context,dom);
        edits.drain(dom.peer(SUB,generation));
        assertEquals(first.id,dom.latestCommand(SUB,generation).id);
        assertEquals("1",dom.metadata(SUB,generation,"desired_revision","0"));
        assertEquals("",dom.metadata(SUB,generation,"configuration_edit",""));
        assertTrue(RemoteConfigurationEdits.same(snapshot,edits.editing(dom.peer(SUB,generation))));
    }

    @Test public void explicitConflictReloadKeepsNextRevisionAboveTheReceiver() throws Exception {
        JSONObject baseline=configuration();
        sub.publish(DOM,generation,RemoteProtocol.Stream.BASELINE,"active",baseline,false,1000);
        dom.receive(SUB,sub.nextBatch(DOM,generation,RemoteProtocol.Stream.BASELINE));
        RemoteStore.Command first=dom.queueConfiguration(SUB,generation,0,0,baseline);
        dom.acceptResult(SUB,generation,new JSONObject().put("schema",1).put("id",first.id).put("revision",1)
                .put("state","CONFLICT").put("appliedRevision",5).put("reason","Stale revision"));
        dom.resetConfigurationEdits(SUB,generation);
        assertEquals("5",dom.metadata(SUB,generation,"desired_revision","0"));
        JSONObject next=new JSONObject(baseline.toString());next.getJSONObject("service").put("hidden",true);
        dom.saveConfigurationEdit(SUB,generation,RemoteProtocol.canonical(next));
        new RemoteConfigurationEdits(context,dom).drain(dom.peer(SUB,generation));
        RemoteStore.Command command=dom.latestCommand(SUB,generation);
        assertEquals(6,command.revision);assertEquals(5,new JSONObject(command.body).getLong("base"));
        assertEquals(RemoteStore.CommandState.CONFLICT,dom.command(SUB,generation,first.id).state);
    }

    @Test public void staleWorkspaceRevisionCannotSaveOrDiscardConfigurationEdits() throws Exception {
        JSONObject baseline=configuration();RemoteConfigurationEdits edits=new RemoteConfigurationEdits(context,dom);
        JSONObject staleGag=GagContractFields.forPeer(baseline,true,false);
        GagContractFields.section(staleGag).put("enabled",true);
        long stale=com.subhub.app.security.ControllerPinManager.domWorkspaceRevision()+1;
        RemoteStore.Peer peer=dom.peer(SUB,generation);
        SecurityException save=assertThrows(SecurityException.class,()->edits.save(peer,baseline,staleGag,stale));
        assertEquals("Dom workspace changed",save.getMessage());
        SecurityException reload=assertThrows(SecurityException.class,()->edits.reload(peer,stale));
        assertEquals("Dom workspace changed",reload.getMessage());
        assertEquals("",dom.metadata(SUB,generation,"configuration_edit",""));
        assertNull(dom.latestCommand(SUB,generation));
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
