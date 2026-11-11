package com.subhub.app.remote;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.people.*;
import com.subhub.app.security.*;
import com.subhub.app.settings.SettingsRepository;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Isolated cached observations with real SQLite continuity; never a transport or phone recovery claim. */
public final class CachedPeerReadModelAndroidTest {
    private static final String DOM=repeat('a'), SUB=repeat('b');
    private Context target,context;
    private File root;
    private String prefix,alias,generation,incoming;
    private final Set<String> preferences=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private RemoteStore store;
    private RemoteFeature feature;
    private FormalRelationshipProvider provider;
    private KeyholderProvider originalProvider;
    private boolean originalFull,ownsSession;
    private Field application;
    private Object originalApplication;

    @Before public void setup() throws Exception {
        target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertFalse(ControllerPinManager.isLocalDomWorkspaceUnlocked());
        prefix="cached-peer-"+UUID.randomUUID()+"-";alias="subhub.test.cached."+prefix;
        root=new File(target.getCacheDir(),prefix);assertTrue(root.mkdir());
        context=new ContextWrapper(target){
            @Override public Context getApplicationContext(){return this;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode){preferences.add(name);return target.getSharedPreferences(prefix+name,mode);}
            @Override public File getFilesDir(){return directory("files");}
            @Override public File getNoBackupFilesDir(){return directory("private");}
            @Override public File getCacheDir(){return directory("cache");}
            private File directory(String name){File value=new File(root,name);assertTrue(value.isDirectory()||value.mkdir()||value.isDirectory());return value;}
        };
        originalProvider=People.keyholder();originalFull=ControllerPinManager.isDomModeActive();ownsSession=true;
        application=ControllerPinManager.class.getDeclaredField("application");application.setAccessible(true);originalApplication=application.get(null);
        People.installKeyholder(new KeyholderProvider(){
            public State state(){return State.NONE;}public String assignedDomName(){return "";}
            public ControllerAuthenticator.Result verifyCode(String code){return ControllerAuthenticator.Result.UNAVAILABLE;}
        });
        assertTrue(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit()
                .putString(DeviceRolePreferences.KEY_MODE,"BOTH").commit());
        assertTrue(ControllerPinManager.setPin(context,"624871"));
        assertTrue(com.subhub.app.configuration.LegacyPackMigration.recoverAndMigrate(context));
        store=FormalRelationshipCoordinator.createOwnedFixture(context);
        generation=UUID.randomUUID().toString();incoming=UUID.randomUUID().toString();
        android.database.sqlite.SQLiteDatabase database=store.getWritableDatabase();
        database.beginTransaction();
        try {
            for(Object[] peer:new Object[][]{{SUB,generation,"Saved Sub","DOM"},{DOM,incoming,"Saved Dom","SUB"}}) {
                database.execSQL("INSERT INTO peers(peer,generation,name,local_role,state) VALUES(?,?,?,?,'ACTIVE')",peer);
                FormalRelationshipStore.mapPeer(database,(String)peer[0],(String)peer[1],true);
            }
            database.setTransactionSuccessful();
        } finally {database.endTransaction();}
        RemoteSecrets secrets=new RemoteSecrets(context,"cached-secrets",alias);
        secrets.saveControllerSecret(RemoteProtocol.Role.SUB,repeat('1'),DOM,incoming,"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        feature=new RemoteFeature(context,store,secrets);assertTrue(feature.recoveryReady());
        People.installKeyholder(feature.keyholder);ControllerPinManager.enterSubMode();
        assertTrue(ControllerPinManager.verifyLocalDomWorkspace(context,"624871"));
        provider=new FormalRelationshipProvider(context,store);provider.snapshot().get(20,TimeUnit.SECONDS);
    }

    @Test public void offlineMirrorsStayIndependentAndContactCannotCrossGenerationsOrBypassContinuity() throws Exception {
        long now=System.currentTimeMillis(),old=now-40*60_000L,domContact=now-5*60_000L;
        store.synced(SUB,generation,old);store.synced(DOM,incoming,domContact);
        store.updateProfile(SUB,generation,RemoteProfiles.encode(ProfileData.empty("Saved Sub"),RemoteProtocol.Role.SUB),"Saved Sub");
        store.updateProfile(DOM,incoming,RemoteProfiles.encode(ProfileData.empty("Saved Dom"),RemoteProtocol.Role.DOM),"Saved Dom");
        JSONObject stats=summary();assertTrue(RemoteTelemetry.validSummary(stats));
        JSONObject runtime=runtime(old);assertTrue(RemoteTelemetry.validRuntime(runtime));
        mirror(RemoteProtocol.Stream.BASELINE,"active",new JSONObject().put("bad","configuration"));
        mirror(RemoteProtocol.Stream.STATS,"summary",stats);mirror(RemoteProtocol.Stream.RUNTIME,"current",runtime);
        for(RemoteProtocol.Stream stream:new RemoteProtocol.Stream[]{RemoteProtocol.Stream.STATS,RemoteProtocol.Stream.RUNTIME})
            store.putMetadata(SUB,generation,"confirmed_"+stream.name(),Long.toString(old));
        RemotePersonRepository repository=new RemotePersonRepository(context,feature);PersonId selected=RemotePersonRepository.id(store.peer(SUB,generation));
        PersonSnapshot saved=repository.load(selected);
        assertEquals(PersonSnapshot.Connection.OFFLINE,saved.connection);assertEquals(old,saved.lastSyncedAt);
        assertEquals(7200,saved.protectedSeconds);assertEquals(3,saved.sessions);assertEquals("Saved Sub",saved.profile.name);
        assertEquals(PersonSnapshot.Freshness.STALE,saved.sync.stats);assertEquals(PersonSnapshot.Freshness.STALE,saved.sync.runtime);
        assertEquals(PersonSnapshot.Freshness.UNKNOWN,saved.sync.contract);assertFalse(saved.can(PersonSnapshot.Capability.EDIT_CONTRACT));
        assertFalse(saved.serviceActive);assertFalse(saved.can(PersonSnapshot.Capability.END_SESSION));
        store.putMetadata(SUB,generation,"confirmed_STATS","not-a-timestamp");
        saved=repository.load(selected);assertEquals(PersonSnapshot.Freshness.UNKNOWN,saved.sync.stats);
        assertEquals(PersonSnapshot.Freshness.STALE,saved.sync.runtime);assertEquals("Saved Sub",saved.profile.name);
        store.putMetadata(SUB,generation,"confirmed_STATS",Long.toString(old));
        mirror(RemoteProtocol.Stream.RUNTIME,"current",new JSONObject().put("schema",1));
        saved=repository.load(selected);assertEquals(7200,saved.protectedSeconds);
        assertEquals(PersonSnapshot.Freshness.UNKNOWN,saved.sync.runtime);assertFalse(saved.can(PersonSnapshot.Capability.END_SESSION));

        JSONObject configuration=RemoteConfigurationAndroidTest.configuration();
        configuration.getJSONObject("contract").getJSONObject("sections").getJSONObject("limits").put("app_timer_per_app_enabled",true);
        configuration.getJSONObject("apps").getJSONArray("packages").put("com.android.settings");
        mirror(RemoteProtocol.Stream.BASELINE,"active",configuration);
        mirror(RemoteProtocol.Stream.APP_USAGE,"today",new JSONObject().put("schema",1).put("zone","UTC")
                .put("day",java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()).put("millis","invalid"));
        store.putMetadata(SUB,generation,"confirmed_APP_USAGE",Long.toString(now));
        saved=repository.load(selected);assertFalse(saved.sections.isEmpty());assertEquals(1,saved.allowances.size());
        assertEquals(-1,saved.allowances.get(0).usedMillis);assertEquals(7200,saved.protectedSeconds);
        PersonSnapshot summary=repository.loadSummary(selected);
        assertEquals(saved.id,summary.id);assertEquals(saved.profile,summary.profile);
        assertEquals(saved.connection,summary.connection);assertEquals(saved.lastOnlineAt,summary.lastOnlineAt);
        assertEquals(saved.protectedSeconds,summary.protectedSeconds);assertEquals(saved.activityEvents,summary.activityEvents);
        assertEquals(saved.sync.stats,summary.sync.stats);assertEquals(saved.sync.runtime,summary.sync.runtime);
        assertEquals(saved.serviceLabel,summary.serviceLabel);assertEquals(saved.serviceActive,summary.serviceActive);
        assertEquals(PersonSnapshot.Freshness.UNKNOWN,summary.sync.contract);
        assertTrue(summary.sections.isEmpty());assertTrue(summary.allowances.isEmpty());assertTrue(summary.capabilities.isEmpty());
        assertFalse("Summary reads must not replace the full stored contract",repository.load(selected).sections.isEmpty());

        PersonSnapshot local=new PersonSnapshot(PersonId.LOCAL,"Local Sub","","","",PersonSnapshot.Connection.LOCAL,
                PersonSnapshot.Freshness.CURRENT,0,EnumSet.noneOf(PersonSnapshot.Capability.class),Collections.emptyList(),
                Collections.emptyList(),"EUR",0,0,0,0,false);
        PersonSnapshot projected=store.authorityObservation(()->repository.localProjection(local));
        assertEquals(RemotePersonRepository.id(store.peer(DOM,incoming)),projected.contractDomContact.id);
        assertEquals(domContact,projected.contractDomContact.lastOnlineAt);assertEquals(0,projected.lastSyncedAt);
        assertEquals(projected.contractDomContact,projected.withProfile(local.profile,Collections.emptyMap()).contractDomContact);
        assertEquals(projected.contractDomContact,projected.withContractDom(projected.contractDom,projected.contractDomId).contractDomContact);
        assertNull(projected.withContractDom(projected.contractDom).contractDomContact);
        PersonId replacement=new PersonId("remote_"+DOM+"_"+UUID.randomUUID());
        assertThrows(IllegalArgumentException.class,()->projected.withContractDom(projected.contractDom,replacement,projected.contractDomContact));
        assertNull(projected.withContractDom(projected.contractDom,replacement).contractDomContact);
        SharedPreferences main=context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0);
        assertTrue(main.edit().putString("remote_active_configuration",configuration.toString())
                .putString("remote_active_command",UUID.randomUUID().toString()).commit());
        PersonSnapshot unmatchedAuthor=store.authorityObservation(()->repository.localProjection(local));
        assertNull(unmatchedAuthor.contractDomId);assertNull(unmatchedAuthor.contractDomContact);
        assertTrue(main.edit().remove("remote_active_configuration").remove("remote_active_command").commit());
        store.revoke(SUB,generation);saved=repository.load(selected);
        assertEquals(PersonSnapshot.Connection.REVOKED,saved.connection);assertFalse(saved.can(PersonSnapshot.Capability.EDIT_CONTRACT));
        assertFalse(saved.can(PersonSnapshot.Capability.END_SESSION));
        store.getWritableDatabase().execSQL("UPDATE linking_state SET revision=revision-1 WHERE id=1");
        assertThrows(IllegalStateException.class,()->repository.load(selected));
    }

    @Test public void atmosphereSummaryUsesOnlyThePeerBaselineAndKeepsConsentFreshnessSeparate() throws Exception {
        long now = System.currentTimeMillis();
        JSONObject configuration = RemoteConfigurationAndroidTest.configuration();
        JSONObject sections = configuration.getJSONObject("contract").getJSONObject("sections");
        sections.getJSONObject("modules").put("module_subliminal_enabled", true);
        sections.getJSONObject("subliminal").put("subliminal_enabled_packs", new org.json.JSONArray(java.util.List.of("findom")))
                .put("subliminal_advanced", true).put("subliminal_custom_phrases", "Never expose this fixture phrase");
        sections.getJSONObject("popup").put("popup_storm_enabled", true).put("popup_storm_position_mode", "center");
        mirror(RemoteProtocol.Stream.BASELINE, "active", configuration);
        store.putMetadata(SUB, generation, "confirmed_BASELINE", Long.toString(now));
        RemotePersonRepository repository = new RemotePersonRepository(context, feature);
        PersonId selected = RemotePersonRepository.id(store.peer(SUB, generation));
        PersonSnapshot saved = repository.load(selected);
        PersonSnapshot.Section atmosphere = atmosphere(saved);
        assertEquals(PersonSnapshot.Freshness.CURRENT, saved.sync.contract);
        assertEquals("Whispers Configured on\nPopup Storm Configured on", atmosphere.summary);
        assertTrue(atmosphere.details.contains("Categories: Findom\nIntensity: Custom"));
        assertTrue(atmosphere.details.contains("Consent: Unknown"));
        assertFalse(atmosphere.details.contains("Never expose"));
        assertFalse(atmosphere.details.matches("(?s).*\\d.*"));
        JSONObject consent = new JSONObject();
        for (String key : SubFeaturePolicy.FEATURES) consent.put(key, !"subliminal".equals(key));
        JSONObject runtime = runtime(now).put("featureConsent", consent);
        assertTrue(RemoteTelemetry.validRuntime(runtime));
        mirror(RemoteProtocol.Stream.RUNTIME, "current", runtime);
        store.putMetadata(SUB, generation, "confirmed_RUNTIME", Long.toString(now));
        assertEquals("Whispers Disabled by Sub\nPopup Storm On", atmosphere(repository.load(selected)).summary);
        store.putMetadata(SUB, generation, "confirmed_RUNTIME", Long.toString(now - 120_000));
        atmosphere = atmosphere(repository.load(selected));
        assertEquals("Whispers Configured on\nPopup Storm Configured on", atmosphere.summary);
        assertTrue(atmosphere.details.contains("Consent: Last observed disabled by Sub"));
        store.putMetadata(SUB, generation, "confirmed_BASELINE", Long.toString(now - 40 * 60_000L));
        saved = repository.load(selected);
        assertEquals(PersonSnapshot.Freshness.STALE, saved.sync.contract);
        assertEquals(atmosphere.details, atmosphere(saved).details);
        mirror(RemoteProtocol.Stream.BASELINE, "active", new JSONObject().put("bad", "configuration"));
        saved = repository.load(selected);
        assertEquals(PersonSnapshot.Freshness.UNKNOWN, saved.sync.contract);
        assertTrue(saved.sections.isEmpty());
    }

    private static PersonSnapshot.Section atmosphere(PersonSnapshot person) {
        for (PersonSnapshot.Section section : person.sections) if ("atmosphere".equals(section.key)) return section;
        throw new AssertionError("Missing atmosphere section");
    }

    private void mirror(RemoteProtocol.Stream stream,String item,JSONObject body) {
        store.getWritableDatabase().execSQL("INSERT OR REPLACE INTO remote_objects(peer,generation,stream,item,seq,body,deleted,observed) VALUES(?,?,?,?,1,?,0,?)",
                new Object[]{SUB,generation,stream.name(),item,com.subhub.app.storage.StoredPayloads.bind(body.toString()),System.currentTimeMillis()});
    }
    private static JSONObject summary() throws Exception {
        JSONObject value=new JSONObject().put("schema",1).put("currency","EUR");
        for(String key:new String[]{"protectedSeconds","serviceSeconds","sessions","activityEvents","censors","limitMillis","limitStops",
                "whispers","popups","tamperEvents","tributes","assessedEUR","assessedUSD","paidEUR","paidUSD","currentSeconds",
                "currentEvents","currentCensors","currentLimitMillis","currentLimitStops","currentWhispers","currentPopups","currentTributes","currentAssessedEUR","currentAssessedUSD"})value.put(key,0);
        return value.put("protectedSeconds",7200).put("sessions",3).put("activityEvents",7);
    }
    private static JSONObject runtime(long observed) throws Exception {
        JSONObject value=new JSONObject().put("schema",1).put("device","Saved device").put("observedAt",observed)
                .put("sessionStart",0).put("sessionEnd",0).put("appliedRevision",0);
        for(String key:new String[]{"serviceActive","screenCapture","recognitionActive","accessibilityBound","accessibilityEnabled","overlayPermission","notifications","armed","paused"})value.put(key,false);
        return value.put("serviceActive",true).put("endSessionSupported",true).put("sessionIdentity",repeat('c'));
    }
    @After public void cleanup() throws Exception {
        try {
            if(provider!=null)provider.close();
            if(feature!=null)feature.shutdownForTest();else if(store!=null)store.close();
            if(provider!=null)provider.cleanupOwnedFixture();
        } finally {
            try {
                if(ownsSession){ControllerPinManager.enterSubMode();People.installKeyholder(originalProvider);
                    if(originalFull)ControllerPinManager.enterDomMode();if(application!=null)application.set(null,originalApplication);}
            } finally {
                if(store!=null){FormalRelationshipCoordinator.releaseOwnedFixture(store);assertTrue(target.deleteDatabase(store.getDatabaseName()));}
                if(context!=null)for(String name:preferences)assertTrue(context.getSharedPreferences(name,0).edit().clear().commit());
                if(alias!=null){java.security.KeyStore keys=java.security.KeyStore.getInstance("AndroidKeyStore");keys.load(null);if(keys.containsAlias(alias))keys.deleteEntry(alias);}
                if(root!=null){assertTrue(root.getCanonicalPath().startsWith(target.getCacheDir().getCanonicalPath()+File.separator));
                    try(java.util.stream.Stream<java.nio.file.Path> paths=java.nio.file.Files.walk(root.toPath())){
                        for(java.nio.file.Path path:paths.sorted(java.util.Comparator.reverseOrder()).toArray(java.nio.file.Path[]::new))java.nio.file.Files.delete(path);
                    }}
            }
        }
    }
    private static String repeat(char c){return new String(new char[64]).replace('\0',c);}
}
