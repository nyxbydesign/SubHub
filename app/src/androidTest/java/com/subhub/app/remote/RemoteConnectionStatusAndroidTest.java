package com.subhub.app.remote;

import static org.junit.Assert.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.people.*;
import com.subhub.app.security.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.*;

/** Current-generation directional observations, without pretending a Dom publishes Sub telemetry. */
public final class RemoteConnectionStatusAndroidTest {
    @Rule public final com.subhub.app.UiAutomationStateRule automation=new com.subhub.app.UiAutomationStateRule();
    private Context context;private RemoteFeature feature,previous;private RemoteStore store;private Field singleton;
    private boolean originalDom;private KeyholderProvider originalProvider;private final Map<String,Map<String,?>> preferences=new LinkedHashMap<>();
    @Before public void setup() throws Exception {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        for(String n:new String[]{com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME,"subhub_controller_auth"})preferences.put(n,new HashMap<>(context.getSharedPreferences(n,0).getAll()));
        originalDom=ControllerPinManager.isDomModeActive();originalProvider=People.keyholder();People.installKeyholder(new KeyholderProvider(){public State state(){return State.NONE;}public String assignedDomName(){return "";}public ControllerAuthenticator.Result verifyCode(String value){return ControllerAuthenticator.Result.UNAVAILABLE;}});
        context.getSharedPreferences(com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME,0).edit().putString(DeviceRolePreferences.KEY_MODE,"BOTH").commit();
        assertTrue(ControllerPinManager.setPin(context,"2468"));assertTrue(ControllerPinManager.verify(context,"2468"));ControllerPinManager.enterDomMode();
        store=FormalRelationshipCoordinator.createOwnedFixture(context);feature=new RemoteFeature(context,store);
        Field foreground=RemoteFeature.class.getDeclaredField("foreground");foreground.setAccessible(true);foreground.set(feature,true);
        Field endpoint=RemoteFeature.class.getDeclaredField("endpoint");endpoint.setAccessible(true);endpoint.set(feature,new PeerTransport(){
            public String localIdentity(){return repeat('b');}public String inviteRoute(){return "";}
            public Connection connect(String peer,String route,long timeout){throw new AssertionError("Observation fixture must not connect");}
            public Connection accept(long timeout)throws java.io.IOException{throw new java.net.SocketTimeoutException();}public void close(){}
        });
        singleton=RemoteFeature.class.getDeclaredField("instance");singleton.setAccessible(true);synchronized(RemoteFeature.class){previous=RemoteFeature.get(context);singleton.set(null,feature);}
    }
    @After public void cleanup() throws Exception {
        try{if(feature!=null)feature.shutdownForTest();}finally{
            if(singleton!=null)synchronized(RemoteFeature.class){assertSame(feature,singleton.get(null));singleton.set(null,previous);}if(store!=null){FormalRelationshipCoordinator.releaseOwnedFixture(store);context.deleteDatabase(store.getDatabaseName());}
            for(Map.Entry<String,Map<String,?>> pref:preferences.entrySet()){SharedPreferences.Editor e=context.getSharedPreferences(pref.getKey(),0).edit().clear();for(Map.Entry<String,?> v:pref.getValue().entrySet()){Object x=v.getValue();if(x instanceof String)e.putString(v.getKey(),(String)x);else if(x instanceof Boolean)e.putBoolean(v.getKey(),(Boolean)x);else if(x instanceof Integer)e.putInt(v.getKey(),(Integer)x);else if(x instanceof Long)e.putLong(v.getKey(),(Long)x);else if(x instanceof Float)e.putFloat(v.getKey(),(Float)x);else if(x instanceof Set)e.putStringSet(v.getKey(),new HashSet<>((Set<String>)x));}assertTrue(e.commit());}
            ControllerPinManager.enterSubMode();if(originalProvider!=null)People.installKeyholder(originalProvider);if(originalDom)ControllerPinManager.enterDomMode();
        }
    }
    @Test public void legacySubConnectionShowsNewerContractContactWithoutClaimingCompletedSync() throws Exception {
        String identity=repeat('a'),generation=UUID.randomUUID().toString();
        android.database.sqlite.SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();try{
            db.execSQL("INSERT INTO peers(peer,generation,name,local_role,state) VALUES(?,?,?,'SUB','ACTIVE')",new Object[]{identity,generation,"Synthetic Dom"});
            FormalRelationshipStore.mapPeer(db,identity,generation,true);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        Field busy=RemoteFeature.class.getDeclaredField("busy");busy.setAccessible(true);((Set<String>)busy.get(feature)).add(identity+":"+generation);
        long completed=System.currentTimeMillis()-12*60*60_000L,contact=System.currentTimeMillis()-180_000;
        store.synced(identity,generation,completed);
        db.delete("remote_metadata","peer=? AND generation=? AND name=?",new String[]{identity,generation,"last_contact"});
        store.putMetadata(identity,generation,"confirmed_CONFIGURATION",Long.toString(contact));
        RemoteStore.Peer peer=store.peer(identity,generation);
        try(ActivityScenario<RemoteConnectionActivity> page=ActivityScenario.launch(new Intent(context,RemoteConnectionActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID,RemotePersonRepository.id(peer).value()))){
            await(()->row(page,RemoteProtocol.Stream.CONFIGURATION)!=null);
            page.onActivity(a->{
                assertNotNull(find(a.body,a.getString(R.string.role_connection_offline)+" · "+a.getString(R.string.role_last_online,ProfileViews.formatLastSeen(contact))));
                assertTrue(text(a.body.findViewWithTag("connection_stream_CONFIGURATION")).contains(java.text.DateFormat.getDateTimeInstance().format(new Date(contact))));
            });
            assertEquals(completed,store.peer(identity,generation).lastSyncedAt);
            assertEquals(contact,store.lastContactAt(peer));
        }
    }
    @Test public void subViewingDomShowsConfigurationOnlyAndUpdatesWithoutAButtonOrNewContact() throws Exception {check(RemoteProtocol.Role.SUB);}
    @Test public void domViewingSubShowsCurrentBaselineStatsAndRuntime() throws Exception {check(RemoteProtocol.Role.DOM);}
    @SuppressWarnings("unchecked") private void check(RemoteProtocol.Role role) throws Exception {
        String identity=repeat('a'),generation=UUID.randomUUID().toString();
        android.database.sqlite.SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();try{
            db.execSQL("INSERT INTO peers(peer,generation,name,local_role,state) VALUES(?,?,?,?,'ACTIVE')",new Object[]{identity,generation,"Synthetic peer",role.name()});FormalRelationshipStore.mapPeer(db,identity,generation,true);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        Field busy=RemoteFeature.class.getDeclaredField("busy");busy.setAccessible(true);((Set<String>)busy.get(feature)).add(identity+":"+generation);
        Field retry=RemoteFeature.class.getDeclaredField("nextAttempt");retry.setAccessible(true);Map<String,Long> retryMap=(Map<String,Long>)retry.get(feature);retryMap.put(identity+":"+generation,Long.MAX_VALUE);
        RemoteStore.Peer peer=store.peer(identity,generation);long contact=System.currentTimeMillis()-120_000;store.synced(identity,generation,contact);store.putMetadata(identity,generation,"connection_error","Unable to sync");
        try(ActivityScenario<RemoteConnectionActivity> page=ActivityScenario.launch(new Intent(context,RemoteConnectionActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID,RemotePersonRepository.id(peer).value()))){
            await(()->retryMap.isEmpty());
            await(()->row(page,role==RemoteProtocol.Role.SUB?RemoteProtocol.Stream.CONFIGURATION:RemoteProtocol.Stream.BASELINE)!=null);
            page.onActivity(a->{
                assertEquals(1,a.body.getChildCount());
                assertNull(find(a.body,a.getString(R.string.remote_sync_now)));
                assertNull(find(a.body,a.getString(R.string.remote_sync_details)));
                assertNull(find(a.body,a.getString(R.string.remote_failure)));
                assertNull(find(a.body,identity));
                String discriminator=ProfileViews.targetDiscriminator(a,RemotePersonRepository.id(peer),"");
                assertEquals(1,count(a.body,discriminator));
                String status=a.getString(R.string.role_connection_offline)+" · "+a.getString(R.string.role_last_online,ProfileViews.formatLastSeen(contact));
                assertNotNull(find(a.body,status));
                assertEquals(role==RemoteProtocol.Role.DOM,a.body.findViewWithTag("connection_device_model")!=null);
                assertNotNull(find(a.body,a.getString(R.string.remote_unpair)));
                assertEquals(role==RemoteProtocol.Role.DOM,find(a.body,a.getString(R.string.remote_keyholder_code))!=null);
                for(RemoteProtocol.Stream stream:RemoteProtocol.Stream.values()){
                    boolean expected=role==RemoteProtocol.Role.SUB?stream==RemoteProtocol.Stream.CONFIGURATION:stream==RemoteProtocol.Stream.BASELINE||stream==RemoteProtocol.Stream.STATS||stream==RemoteProtocol.Stream.RUNTIME;
                    assertEquals(stream.name(),expected,a.body.findViewWithTag("connection_stream_"+stream.name())!=null);
                }
            });
            long observed=System.currentTimeMillis()-30_000;for(RemoteProtocol.Stream stream:RemoteConnectionActivity.observedStreams(peer))store.putMetadata(identity,generation,"confirmed_"+stream.name(),Long.toString(observed));
            String expected=java.text.DateFormat.getDateTimeInstance().format(new Date(observed));
            await(()->{View view=row(page,role==RemoteProtocol.Role.SUB?RemoteProtocol.Stream.CONFIGURATION:RemoteProtocol.Stream.BASELINE);return view!=null&&text(view).contains(expected);});
            assertEquals(contact,store.peer(identity,generation).lastSyncedAt);
            assertEquals(contact,store.lastContactAt(peer));
            await(()->{java.util.concurrent.atomic.AtomicBoolean ready=new java.util.concurrent.atomic.AtomicBoolean();page.onActivity(a->ready.set(a.body.getChildCount()==1&&a.body.getChildAt(0).getWidth()>0&&a.body.getChildAt(0).getHeight()>0));return ready.get();});
            page.onActivity(a->capture(a.body.getChildAt(0),role.name()));
        }
    }
    private View row(ActivityScenario<RemoteConnectionActivity> page,RemoteProtocol.Stream stream){java.util.concurrent.atomic.AtomicReference<View> value=new java.util.concurrent.atomic.AtomicReference<>();page.onActivity(a->value.set(a.body.findViewWithTag("connection_stream_"+stream.name())));return value.get();}
    private static String text(View view){if(view instanceof TextView)return ((TextView)view).getText().toString();StringBuilder s=new StringBuilder();if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)s.append(text(((ViewGroup)view).getChildAt(i)));return s.toString();}
    private static View find(View root,String wanted){if(root instanceof TextView&&wanted.contentEquals(((TextView)root).getText()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=find(((ViewGroup)root).getChildAt(i),wanted);if(found!=null)return found;}return null;}
    private void capture(View card,String role) {
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(card.getWidth(),card.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
        try {
            card.draw(new android.graphics.Canvas(bitmap));
            java.io.File directory=new java.io.File(context.getFilesDir(),"ui-v3/connection-single-card");
            assertTrue(directory.isDirectory()||directory.mkdirs());
            try(java.io.FileOutputStream file=new java.io.FileOutputStream(new java.io.File(directory,role+".png"))){assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,file));}
        } catch(java.io.IOException unavailable){throw new AssertionError("Could not retain Connection card render",unavailable);}
        finally {bitmap.recycle();}
    }
    private static int count(View root,String wanted){int total=root instanceof TextView&&wanted.contentEquals(((TextView)root).getText())?1:0;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)total+=count(((ViewGroup)root).getChildAt(i),wanted);return total;}
    private static String repeat(char value){return new String(new char[64]).replace('\0',value);}
    private static void await(java.util.concurrent.Callable<Boolean> condition)throws Exception{long end=android.os.SystemClock.elapsedRealtime()+10_000;while(android.os.SystemClock.elapsedRealtime()<end){if(condition.call())return;Thread.sleep(30);}fail("Native connection observation did not settle");}
}
