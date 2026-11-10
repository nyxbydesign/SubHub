package com.subhub.app.remote;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.people.People;
import com.subhub.app.people.PersonId;
import com.subhub.app.people.LocalSessionControls;
import com.subhub.app.relationships.RelationshipUi;
import com.subhub.app.security.ControllerAuthenticator;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.KeyholderProvider;
import com.subhub.app.security.Totp;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.storage.StoredPayloads;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;

/** Real schema-4 SQLite and Keystore cipher upgrade; isolated identities/preferences, no network or OS grants. */
public final class SwitchDomAuthorityAndroidTest {
    private static final String SELF=repeat('1'), INCOMING=repeat('a'), OUTGOING=repeat('b');
    private static final String PIN="624871", INCOMING_SEED=repeatSeed('A'), OUTGOING_SEED=repeatSeed('C');
    private Context target,context;
    private String prefix,alias,incomingGeneration,outgoingGeneration;
    private KeyholderProvider originalProvider;
    private boolean originalFullSession,ownsSession;
    private Field application;
    private Object originalApplication;
    private RemoteStore store;
    private RemoteSecrets secrets;
    private RemoteKeyholder keyholder;
    private FormalRelationshipProvider provider;
    private Map<String,?> legacyCipher;

    @Before public void setup() throws Exception {
        target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefix="switch-dom-"+UUID.randomUUID()+"-";alias="subhub.test.switch."+prefix;
        incomingGeneration=UUID.randomUUID().toString();outgoingGeneration=UUID.randomUUID().toString();
        assertFalse("Fixture requires an unowned workspace session",ControllerPinManager.isLocalDomWorkspaceUnlocked());
        originalProvider=People.keyholder();originalFullSession=ControllerPinManager.isDomModeActive();ownsSession=true;
        application=ControllerPinManager.class.getDeclaredField("application");application.setAccessible(true);
        originalApplication=application.get(null);
        context=new ContextWrapper(target){
            @Override public Context getApplicationContext(){return this;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode){return target.getSharedPreferences(prefix+name,mode);}
        };
        People.installKeyholder(new KeyholderProvider(){
            public State state(){return State.NONE;} public String assignedDomName(){return "";}
            public ControllerAuthenticator.Result verifyCode(String value){return ControllerAuthenticator.Result.UNAVAILABLE;}
        });
        assertTrue(main().edit().putString(DeviceRolePreferences.KEY_MODE,"BOTH").commit());
        assertTrue(ControllerPinManager.setPin(context,PIN));
        store=FormalRelationshipCoordinator.createOwnedFixture(context);
        try(SQLiteDatabase legacy=SQLiteDatabase.openDatabase(store.resolvedDatabaseFile().getPath(),null,SQLiteDatabase.OPEN_READWRITE)){
            createLegacySchema(legacy);
            legacy.execSQL("INSERT INTO peers(peer,generation,name,local_role,state) VALUES(?,?,'Remote Dom','SUB','ACTIVE')",new Object[]{INCOMING,incomingGeneration});
            legacy.execSQL("INSERT INTO peers(peer,generation,name,local_role,state) VALUES(?,?,'Owned Sub','DOM','ACTIVE')",new Object[]{OUTGOING,outgoingGeneration});
            legacy.execSQL("INSERT INTO remote_metadata(peer,generation,name,value) VALUES(?,?,'controller_auth_v1_provisioned','1')",new Object[]{INCOMING,incomingGeneration});
            legacy.setVersion(4);
        }
        // The unchanged legacy slot/AAD format uses an isolated wrapping alias, never the user's key.
        secrets=new RemoteSecrets(context,"subhub_remote_secrets_v1",alias);
        secrets.saveControllerSecret(RemoteProtocol.Role.SUB,SELF,INCOMING,incomingGeneration,INCOMING_SEED);
        secrets.saveControllerSecret(RemoteProtocol.Role.DOM,SELF,OUTGOING,outgoingGeneration,OUTGOING_SEED);
        legacyCipher=new HashMap<>(context.getSharedPreferences("subhub_remote_secrets_v1",0).getAll());
        assertEquals(8,store.getWritableDatabase().getVersion());
        keyholder=new RemoteKeyholder(context,store,secrets);People.installKeyholder(keyholder);
        ControllerPinManager.enterSubMode();
        provider=new FormalRelationshipProvider(context,store);provider.snapshot().get(15,TimeUnit.SECONDS);
        assertEquals(KeyholderProvider.State.READY,keyholder.state());
        assertEquals(legacyCipher,context.getSharedPreferences("subhub_remote_secrets_v1",0).getAll());
        assertEquals(INCOMING_SEED,secrets.controllerSecret(RemoteProtocol.Role.SUB,INCOMING,incomingGeneration));
    }
    private SharedPreferences main(){return context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0);}
    private RemoteStore.Peer outgoing(){return store.peer(OUTGOING,outgoingGeneration);}
    private RemoteStore.Peer incoming(){return store.peer(INCOMING,incomingGeneration);}
    private void localWorkspace(){
        People.installKeyholder(unavailable());ControllerPinManager.enterSubMode();
        assertFalse(ControllerPinManager.verify(context,PIN));
        assertTrue(ControllerPinManager.verifyLocalDomWorkspace(context,PIN));
        assertTrue(ControllerPinManager.isLocalDomWorkspaceUnlocked());assertFalse(ControllerPinManager.isSessionUnlocked());
    }
    private static KeyholderProvider unavailable(){return new KeyholderProvider(){
        public State state(){return State.UNAVAILABLE;} public String assignedDomName(){return "Remote Dom";}
        public ControllerAuthenticator.Result verifyCode(String value){return ControllerAuthenticator.Result.UNAVAILABLE;}
    };}
    private RemoteSessionControlCoordinator advice() throws Exception {
        RemoteSessionClock.Sample now=RemoteSessionClock.observe(context);
        RemoteSessionControlState state=new RemoteSessionControlState(OUTGOING,SELF,outgoingGeneration,now.wall,1,null,true,"",repeat('c'),
                RemoteSessionRuntime.Readiness.NEEDS_SUB_ACTION,RemoteSessionCommands.Reason.READINESS_REQUIRED,false,true);
        store.putMetadata(OUTGOING,outgoingGeneration,"session_control_version","1");
        store.putMetadata(OUTGOING,outgoingGeneration,RemoteSessionControlState.METADATA,
                RemoteProtocol.canonical(new RemoteSessionControlState.Received(state,now).json()));
        return new RemoteSessionControlCoordinator(context,store,SELF);
    }
    private long journalCount(){try(Cursor row=store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM session_control_journal",null)){assertTrue(row.moveToFirst());return row.getLong(0);}}
    private byte[] marker() throws Exception {
        return Files.readAllBytes(new java.io.File(new java.io.File(context.getNoBackupFilesDir(),"linking-"+LocalRelationshipAuthority.digest(store.resolvedDatabaseFile().getPath())),"current.json").toPath());
    }

    @Test public void legacyFourCipherAndOutgoingDomStayIndependentOfUnavailableIncomingSub() throws Exception {
        assertEquals(RemoteStore.State.ACTIVE,incoming().state);assertEquals(RemoteProtocol.Role.SUB,incoming().localRole);
        localWorkspace();
        RelationshipUi.Snapshot view=provider.snapshot().get(15,TimeUnit.SECONDS);
        assertTrue(view.localParticipants.stream().anyMatch(p->p.role==RelationshipUi.Role.DOM));
        byte[] before=marker();RemoteKeyholder.Code code=keyholder.code(outgoing());
        assertEquals(Totp.code(OUTGOING_SEED,code.expiresAt/Totp.STEP_MILLIS-1),code.value);
        assertArrayEquals(before,marker());assertEquals(legacyCipher,context.getSharedPreferences("subhub_remote_secrets_v1",0).getAll());
        assertThrows(SecurityException.class,()->keyholder.code(incoming()));
        assertFalse(ControllerPinManager.canEditConfiguration());
        assertTrue(main().edit().putBoolean("app_mode_armed",true).commit());
        assertFalse(LocalSessionControls.end(context,PersonId.LOCAL));assertTrue(main().getBoolean("app_mode_armed",false));
        LocalRelationshipAuthority authority=new LocalRelationshipAuthority(context,store,FormalRelationshipClock.source(context));
        assertThrows(SecurityException.class,()->authority.observeCommand(RemoteProtocol.Role.SUB,LocalRelationshipAuthority.NewCommand.ENROLLMENT));
        assertThrows(SecurityException.class,()->store.revoke(INCOMING,incomingGeneration));
        assertEquals(RemoteStore.State.ACTIVE,incoming().state);
        RemoteSessionControlCoordinator controls=advice();
        RemoteSessionRuntime.Review review=store.authorityAccess(()->controls.review(outgoing(),RemoteSessionCommands.Action.STOP));
        assertEquals(review.request.id,store.authorityAccess(()->controls.confirm(review)));assertEquals(1,journalCount());
        assertEquals(RemoteSessionCommands.State.QUEUED,store.sessionControl(OUTGOING,outgoingGeneration,RemoteSessionRuntime.Direction.OUTGOING,review.request.id).result.state);
        assertThrows(SecurityException.class,()->store.authorityAccess(()->controls.review(incoming(),RemoteSessionCommands.Action.STOP)));
    }
    @Test public void workspaceReviewsRejectReauthenticationAndRetainedAuthorityRollback() throws Exception {
        localWorkspace();RemoteSessionControlCoordinator controls=advice();
        RemoteSessionRuntime.Review review=store.authorityAccess(()->controls.review(outgoing(),RemoteSessionCommands.Action.STOP));
        long revision=ControllerPinManager.domWorkspaceRevision();
        ControllerPinManager.enterSubMode();assertTrue(ControllerPinManager.verifyLocalDomWorkspace(context,PIN));
        assertTrue(ControllerPinManager.domWorkspaceRevision()>revision);
        assertEquals(review.sourceWitness,store.authorityObservation(()->controls.snapshot(outgoing())).sourceWitness);
        assertThrows(SecurityException.class,()->store.authorityAccess(()->controls.confirm(review)));assertEquals(0,journalCount());
        // UI role changes deliver preference invalidation on main before a later PIN entry.
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->assertTrue(
                main().edit().putString(DeviceRolePreferences.KEY_MODE,"SUB_ONLY").commit()));
        assertFalse(ControllerPinManager.isLocalDomWorkspaceUnlocked());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->assertTrue(
                main().edit().putString(DeviceRolePreferences.KEY_MODE,"BOTH").commit()));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertTrue(ControllerPinManager.verifyLocalDomWorkspace(context,PIN));
        People.installKeyholder(unavailable());assertFalse(ControllerPinManager.isLocalDomWorkspaceUnlocked());
        assertTrue(ControllerPinManager.verifyLocalDomWorkspace(context,PIN));
        String epoch=new JSONObject(new String(marker(),StandardCharsets.UTF_8)).getString("epoch");
        store.getWritableDatabase().execSQL("UPDATE linking_state SET revision=revision-1 WHERE id=1");
        assertThrows(RuntimeException.class,()->keyholder.code(outgoing()));
        assertThrows(RuntimeException.class,()->store.authorityObservation(()->controls.snapshot(outgoing())));
        assertEquals(epoch,new JSONObject(new String(marker(),StandardCharsets.UTF_8)).getString("epoch"));assertEquals(0,journalCount());
    }
    @After public void cleanup() throws Exception {
        if(!ownsSession)return;
        try {
            if(provider!=null){provider.close();provider.cleanupOwnedFixture();}
        } finally {
            try {
                ControllerPinManager.enterSubMode();
                if(originalProvider!=null)People.installKeyholder(originalProvider);
                if(originalFullSession)ControllerPinManager.enterDomMode();
                if(application!=null)application.set(null,originalApplication);
            } finally {
                if(store!=null){store.close();FormalRelationshipCoordinator.releaseOwnedFixture(store);assertTrue(target.deleteDatabase(store.getDatabaseName()));}
                if(context!=null)for(String name:new String[]{SettingsRepository.PREFERENCES_NAME,"subhub_remote_secrets_v1","subhub_controller_auth","subhub_local_feature_consent"})context.getSharedPreferences(name,0).edit().clear().commit();
                if(alias!=null){assertTrue(alias.startsWith("subhub.test.switch."));java.security.KeyStore keys=java.security.KeyStore.getInstance("AndroidKeyStore");keys.load(null);if(keys.containsAlias(alias))keys.deleteEntry(alias);}
            }
        }
    }
    private static String repeat(char c){return new String(new char[64]).replace('\0',c);}
    private static String repeatSeed(char c){return new String(new char[32]).replace('\0',c);}

    // Exact schema-4 onCreate/metadata DDL from dev.224 blob 0347879d. Its two helper classes are unchanged.
    private static void createLegacySchema(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE peers(peer TEXT NOT NULL,generation TEXT NOT NULL,name TEXT NOT NULL,"
                + "local_role TEXT NOT NULL,state TEXT NOT NULL,last_sync INTEGER NOT NULL DEFAULT 0,"
                + "route TEXT NOT NULL DEFAULT '',PRIMARY KEY(peer,generation))");
        db.execSQL("CREATE UNIQUE INDEX active_remote_identity ON peers(peer) WHERE state IN ('PENDING','ACTIVE')");
        db.execSQL("CREATE UNIQUE INDEX one_dom_per_sub ON peers(local_role) WHERE local_role='SUB' AND state IN ('PENDING','ACTIVE')");
        db.execSQL("CREATE TABLE invites(id TEXT PRIMARY KEY,token_hash TEXT NOT NULL,expires INTEGER NOT NULL,"
                + "candidate TEXT,candidate_name TEXT NOT NULL DEFAULT '',approved INTEGER NOT NULL DEFAULT 0,consumed INTEGER NOT NULL DEFAULT 0)");
        for (String table : new String[] {"local_objects", "outbox", "remote_objects"}) {
            db.execSQL("CREATE TABLE " + table + "(peer TEXT NOT NULL,generation TEXT NOT NULL,"
                    + "stream TEXT NOT NULL,item TEXT NOT NULL,seq INTEGER NOT NULL,body TEXT NOT NULL,"
                    + "deleted INTEGER NOT NULL,observed INTEGER NOT NULL,PRIMARY KEY(peer,generation,stream,item))");
        }
        db.execSQL("CREATE TABLE outgoing(peer TEXT NOT NULL,generation TEXT NOT NULL,stream TEXT NOT NULL,"
                + "next_seq INTEGER NOT NULL DEFAULT 0,acked INTEGER NOT NULL DEFAULT 0,"
                + "offered INTEGER NOT NULL DEFAULT 0,digest TEXT,PRIMARY KEY(peer,generation,stream))");
        db.execSQL("CREATE TABLE incoming(peer TEXT NOT NULL,generation TEXT NOT NULL,stream TEXT NOT NULL,"
                + "through_seq INTEGER NOT NULL,PRIMARY KEY(peer,generation,stream))");
        db.execSQL("CREATE TABLE commands(peer TEXT NOT NULL,generation TEXT NOT NULL,id TEXT NOT NULL,"
                + "revision INTEGER NOT NULL,status TEXT NOT NULL,body TEXT NOT NULL,"
                + "PRIMARY KEY(peer,generation,id))");
        createLegacyMetadata(db);
        RemoteActivityStore.create(db);
        StoredPayloads.createProgress(db);

    }
    private static void createLegacyMetadata(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE remote_metadata(peer TEXT NOT NULL,generation TEXT NOT NULL,name TEXT NOT NULL,value TEXT NOT NULL,PRIMARY KEY(peer,generation,name))");

    }
}
