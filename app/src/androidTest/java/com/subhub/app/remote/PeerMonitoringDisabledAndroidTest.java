package com.subhub.app.remote;

import static org.junit.Assert.*;
import android.content.Context;
import android.database.Cursor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.activity.ActivityEntry;
import com.subhub.app.people.PersonId;
import com.subhub.app.security.*;
import com.subhub.app.settings.SettingsRepository;
import java.util.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PeerMonitoringDisabledAndroidTest {
    private static final String DOM=String.join("",Collections.nCopies(64,"a"));
    private static final String SUB=String.join("",Collections.nCopies(64,"b"));
    private Context context;private RemoteStore sub,dom;private String generation,subName,domName;
    private String originalMode;
    @Before public void setup() {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalMode=context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).getString(DeviceRolePreferences.KEY_MODE,null);
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit().putString(DeviceRolePreferences.KEY_MODE,DeviceRolePreferences.Mode.BOTH.name()).commit();
        ControllerPinManager.initialize(context);ControllerPinManager.enterDomMode();
        subName="disabled-sub-"+UUID.randomUUID()+".db";domName="disabled-dom-"+UUID.randomUUID()+".db";
        sub=new RemoteStore(context,subName);dom=new RemoteStore(context,domName);
        PairingInvite invite=PairingInvite.create(DOM,"fixture",System.currentTimeMillis());generation=invite.id;
        dom.saveInvite(invite);dom.bindInvite(generation,SUB,invite.tokenHash(),System.currentTimeMillis());
        dom.approveInvite(generation,SUB,"Sub",System.currentTimeMillis());dom.activate(SUB,generation);
        sub.acceptInvite(invite,"Dom");sub.activate(DOM,generation);
    }
    @After public void cleanup() {
        if(sub!=null)sub.close();if(dom!=null)dom.close();
        context.deleteDatabase(subName);context.deleteDatabase(domName);
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit().putString(DeviceRolePreferences.KEY_MODE,originalMode).commit();
        ControllerPinManager.enterSubMode();
    }
    @Test public void activityHooksDoNotCapturePersistNotifyOrSynchronize() throws Exception {
        int[] calls={0};
        RemoteActivityRepository.Notifications notifications=new RemoteActivityRepository.Notifications() {
            public boolean enabled(Context c){calls[0]++;return true;}
            public boolean show(Context c,ActivityEntry e){calls[0]++;return true;}
            public void cancel(Context c,ActivityEntry e){calls[0]++;}
            public void cancelPeer(Context c,PersonId p){calls[0]++;}
        };
        try(RemoteActivityRepository activity=new RemoteActivityRepository(context,sub,()->calls[0]++,notifications)) {
            activity.install();activity.refreshSource();activity.prepare(sub.peer(DOM,generation));
            activity.foreground("old","com.example.selected",true,1);activity.tribute("ledger");
            activity.limit("com.example.selected",true,10,Collections.singleton("com.example.selected"),2);
            RemoteActivityRecorder.foreground("old","com.example.selected",true,3);
            RemoteActivityRecorder.tribute("ledger");RemoteActivityRecorder.limit("com.example.selected",true,20,Collections.emptySet(),4);
            activity.received(sub.peer(DOM,generation));activity.dispatchNotifications();
            assertEquals(0,activity.unreadCount());assertThrows(UnsupportedOperationException.class,()->activity.load(null,10));
            assertEquals(0,calls[0]);
            for(String table:new String[]{"activity_source","activity_receipts","activity_state","outbox"})assertEquals(table,0,count(sub,table));
        }
    }
    @Test public void monitoringPublicationReceiptAndAcknowledgementFailBeforeCommit() throws Exception {
        for(RemoteProtocol.Stream stream:blocked()) {
            assertThrows(SecurityException.class,()->sub.publish(DOM,generation,stream,"sample",new JSONObject(),false,1));
            RemoteProtocol.Batch batch=new RemoteProtocol.Batch(generation,stream,0,1,Collections.singletonList(new RemoteProtocol.Change(1,"sample","{}",false,1)));
            assertThrows(SecurityException.class,()->dom.receive(SUB,batch));
            assertThrows(SecurityException.class,()->sub.acknowledge(DOM,new RemoteProtocol.Ack(batch)));
        }
        assertEquals(0,count(sub,"outbox"));assertEquals(0,count(sub,"outgoing"));
        assertEquals(0,count(dom,"incoming"));assertEquals(0,count(dom,"remote_objects"));assertEquals(0,count(dom,"activity_receipts"));
    }
    @Test public void legacyMonitoringOutboxesStayPrivateWithoutFakeAcknowledgements() throws Exception {
        for(RemoteProtocol.Stream stream:blocked()) {
            sub.getWritableDatabase().execSQL("INSERT INTO outbox VALUES(?,?,?,?,?,?,?,?)",new Object[]{DOM,generation,stream.name(),"legacy",7,"{\"private\":true}",0,1});
            sub.getWritableDatabase().execSQL("INSERT INTO outgoing(peer,generation,stream,next_seq,acked,offered,digest) VALUES(?,?,?,?,?,?,?)",new Object[]{DOM,generation,stream.name(),7,3,6,"previous"});
            RemoteProtocol.Batch batch=sub.nextBatch(DOM,generation,stream);assertTrue(batch.changes.isEmpty());assertEquals(3,batch.after);assertEquals(3,batch.through);
            assertEquals(-1,sub.outgoingHeads(DOM,generation,true).getLong(stream.name()));
            try(Cursor row=sub.getReadableDatabase().rawQuery("SELECT next_seq,acked,offered,digest FROM outgoing WHERE stream=?",new String[]{stream.name()})) {
                assertTrue(row.moveToFirst());assertEquals(7,row.getLong(0));assertEquals(3,row.getLong(1));assertEquals(6,row.getLong(2));assertEquals("previous",row.getString(3));
            }
        }
        assertEquals(blocked().length,count(sub,"outbox"));
    }
    @Test public void configurationCatalogAndReceiptsStillTransferAndAcknowledge() throws Exception {
        for(RemoteProtocol.Stream stream:new RemoteProtocol.Stream[]{RemoteProtocol.Stream.BASELINE,RemoteProtocol.Stream.APP_CATALOG,RemoteProtocol.Stream.COMMAND_RESULT,RemoteProtocol.Stream.CONFIGURATION}) {
            boolean fromSub=stream.writer==RemoteProtocol.Role.SUB;RemoteStore source=fromSub?sub:dom,target=fromSub?dom:sub;
            String peer=fromSub?DOM:SUB,identity=fromSub?SUB:DOM;
            source.publish(peer,generation,stream,"active",new JSONObject().put("retained",true),false,1);
            RemoteProtocol.Batch batch=source.nextBatch(peer,generation,stream);assertEquals(1,batch.changes.size());
            source.acknowledge(peer,target.receive(identity,batch));assertTrue(source.nextBatch(peer,generation,stream).changes.isEmpty());
            assertTrue(target.mirrored(identity,generation,stream,"active").getBoolean("retained"));
        }
        new RemoteTelemetry(context,sub).prepare(sub.peer(DOM,generation));
        for(RemoteProtocol.Stream stream:blocked())assertTrue(sub.published(DOM,generation,stream).isEmpty());
    }
    private static RemoteProtocol.Stream[] blocked(){return new RemoteProtocol.Stream[]{RemoteProtocol.Stream.STATS,RemoteProtocol.Stream.DAILY,RemoteProtocol.Stream.APP_USAGE,RemoteProtocol.Stream.RUNTIME,RemoteProtocol.Stream.EVENTS};}
    private static int count(RemoteStore store,String table){try(Cursor rows=store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM "+table,null)){assertTrue(rows.moveToFirst());return rows.getInt(0);}}
}
