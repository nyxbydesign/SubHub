package com.subhub.app.remote;

import static org.junit.Assert.*;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.activity.*;
import com.subhub.app.people.PersonId;
import com.subhub.app.penance.*;
import com.subhub.app.security.*;
import com.subhub.app.settings.SettingsRepository;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RemoteActivityAndroidTest {
    private Context context;
    private RemoteStore sender,receiver;
    private RemoteActivityRepository sending,receiving;
    private RemoteStore.Peer sourcePeer,targetPeer;
    private final List<String> databases=new ArrayList<>();
    private Map<String,?> settings,penance;
    private final FakeNotifications notifications=new FakeNotifications();
    private String generation;
    private static final String DOM=String.join("",Collections.nCopies(64,"a")),SUB=String.join("",Collections.nCopies(64,"b"));
    @Before public void setup()throws Exception {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        settings=new HashMap<>(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).getAll());
        penance=new HashMap<>(context.getSharedPreferences(PenanceManager.PREFS_NAME,0).getAll());
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit().putString(DeviceRolePreferences.KEY_MODE,DeviceRolePreferences.Mode.BOTH.name()).commit();
        context.getSharedPreferences(PenanceManager.PREFS_NAME,0).edit().clear().commit();
        ControllerPinManager.initialize(context);ControllerPinManager.enterDomMode();
        sender=database();receiver=database();pair();
        sending=new RemoteActivityRepository(context,sender,()->{},notifications);
        receiving=new RemoteActivityRepository(context,receiver,()->{},notifications);
        receiver.onActivityReceived(receiving::received);
        receiver.onRevoked((identity,gen)->receiving.revoked(receiver.peer(identity,gen)));
        drain(sending);drain(receiving);
    }
    @After public void cleanup()throws Exception {
        if(sending!=null)sending.close();if(receiving!=null)receiving.close();
        if(sender!=null)sender.close();if(receiver!=null)receiver.close();for(String name:databases)context.deleteDatabase(name);
        restore(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0),settings);
        restore(context.getSharedPreferences(PenanceManager.PREFS_NAME,0),penance);ControllerPinManager.enterSubMode();
    }
    private RemoteStore database(){String name="activity-"+UUID.randomUUID()+".db";databases.add(name);return new RemoteStore(context,name);}
    private void pair() {
        PairingInvite invitation=PairingInvite.create(DOM,"fixture",System.currentTimeMillis());generation=invitation.id;
        receiver.saveInvite(invitation);receiver.bindInvite(generation,SUB,invitation.tokenHash(),System.currentTimeMillis());
        receiver.approveInvite(generation,SUB,"Fixture Sub",System.currentTimeMillis());receiver.activate(SUB,generation);
        sender.acceptInvite(invitation,"Fixture Dom");sender.activate(DOM,generation);
        sourcePeer=sender.peer(DOM,generation);targetPeer=receiver.peer(SUB,generation);
    }
    private static JSONObject event(String id,long time)throws Exception {
        return RemoteActivityWire.encode(new ActivityEvent(id,ActivityEvent.Kind.APP_OPENED,time,"com.example.selected",0,"","",0),"UTC","");
    }
    private RemoteProtocol.Batch publish(String id,long time)throws Exception {
        sender.publish(DOM,generation,RemoteProtocol.Stream.EVENTS,id,event(id,time),false,time);
        return sender.nextBatch(DOM,generation,RemoteProtocol.Stream.EVENTS);
    }
    @Test public void committedReceiptsDeduplicateReadStateAndNotificationsAcrossReopen()throws Exception {
        notifications.store=receiver;
        RemoteProtocol.Batch batch=publish("event_one",System.currentTimeMillis());receiver.receive(SUB,batch);drain(receiving);
        assertEquals(1,receiving.unreadCount());assertEquals(1,notifications.calls.get());
        ActivityEntry entry=receiving.load(null,20).entries.get(0);receiving.markRead(Collections.singletonList(entry.key));
        receiver.receive(SUB,batch);drain(receiving);assertEquals(0,receiving.unreadCount());assertEquals(1,notifications.calls.get());
        assertEquals(1,notifications.cancelled.size());
        String name=databases.get(1);receiving.close();receiver.close();receiver=new RemoteStore(context,name);
        notifications.store=receiver;receiving=new RemoteActivityRepository(context,receiver,()->{},notifications);receiver.onActivityReceived(receiving::received);
        drain(receiving);assertFalse(receiving.load(null,20).entries.get(0).unread);receiving.dispatchNotifications();drain(receiving);
        assertEquals(1,notifications.calls.get());assertFalse(notifications.insideTransaction);
    }
    @Test public void alteredImmutableFactsAndUnknownSchemasNeverAdvanceTheReceiptCursor()throws Exception {
        RemoteProtocol.Batch initial=publish("event_one",System.currentTimeMillis());receiver.receive(SUB,initial);
        assertThrows(SecurityException.class,()->sender.receive(DOM,initial));
        JSONObject altered=new JSONObject(initial.changes.get(0).body).put("packageName","com.example.other");
        RemoteProtocol.Change changed=new RemoteProtocol.Change(2,"event_one",RemoteProtocol.canonical(altered),false,initial.changes.get(0).observedAt);
        assertThrows(SecurityException.class,()->receiver.receive(SUB,new RemoteProtocol.Batch(generation,RemoteProtocol.Stream.EVENTS,1,2,Collections.singletonList(changed))));
        JSONObject unknown=event("event_two",System.currentTimeMillis()).put("schema",2);
        RemoteProtocol.Change bad=new RemoteProtocol.Change(2,"event_two",unknown.toString(),false,unknown.getLong("observedAt"));
        assertThrows(SecurityException.class,()->receiver.receive(SUB,new RemoteProtocol.Batch(generation,RemoteProtocol.Stream.EVENTS,1,2,Collections.singletonList(bad))));
        try(Cursor cursor=receiver.getReadableDatabase().rawQuery("SELECT through_seq FROM incoming WHERE stream='EVENTS'",null)){assertTrue(cursor.moveToFirst());assertEquals(1,cursor.getLong(0));}
        assertEquals(1,receiving.load(null,20).entries.size());
    }
    @Test public void exactLookupAndPerPersonRecentWorkBeyondTheFirstFeedPage()throws Exception {
        receiver.getWritableDatabase().delete("activity_source", null, null);
        long time=System.currentTimeMillis();for(int i=0;i<123;i++)sender.publish(DOM,generation,RemoteProtocol.Stream.EVENTS,"event_"+i,event("event_"+i,time+i),false,time+i);
        receiver.receive(SUB,sender.nextBatch(DOM,generation,RemoteProtocol.Stream.EVENTS));
        ActivityRepository.Page first=receiving.load(null,50);assertEquals(50,first.entries.size());assertNotNull(first.nextCursor);
        ActivityRepository.Page second=receiving.load(first.nextCursor,50);assertEquals(50,second.entries.size());
        ActivityRepository.Page last=receiving.load(second.nextCursor,50);assertEquals(23,last.entries.size());assertNull(last.nextCursor);
        String oldest=last.entries.get(22).key;ActivityRepository.Page located=receiving.locate(oldest,20);
        assertEquals(oldest,located.entries.get(0).key);assertEquals(oldest,receiving.load(located.cursor,20).entries.get(0).key);
        assertEquals(3,receiving.recent(RemotePersonRepository.id(targetPeer),3).size());
        RemoteProtocol.Batch replay=sender.nextBatch(DOM,generation,RemoteProtocol.Stream.EVENTS);
        receiver.revoke(SUB,generation);assertThrows(SecurityException.class,()->receiver.receive(SUB,replay));assertTrue(receiving.locate(oldest,20).entries.isEmpty());assertEquals(0,receiving.unreadCount());
        assertEquals(123,count(receiver,"activity_receipts"));
    }
    @Test public void unpairedServiceWindowCallbacksPersistLocalSelectedAppAndReopenOnActivity() throws Exception {
        RemoteStore local = database(); String name = databases.get(databases.size() - 1);
        RemoteActivityRepository repository = new RemoteActivityRepository(context, local, () -> {}, notifications);
        ActivityRepository priorUi = ActivitySources.repository();
        java.lang.reflect.Field installed = RemoteActivityRecorder.class.getDeclaredField("installed"); installed.setAccessible(true);
        RemoteActivityRecorder.Sink priorRecorder = (RemoteActivityRecorder.Sink) installed.get(null);
        com.subhub.app.appmode.AppModeManager mode = new com.subhub.app.appmode.AppModeManager(context);
        mode.saveIncludedPackages(Collections.singleton("com.example.selected"));
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                .putBoolean(com.subhub.app.appmode.AppModeManager.KEY_ALL_APPS, false).commit();
        try {
            assertTrue(local.peers(false).isEmpty()); RemoteActivityRecorder.install(repository);
            java.util.concurrent.atomic.AtomicReference<com.subhub.app.service.ScreenshotAccessibilityService> service = new java.util.concurrent.atomic.AtomicReference<>();
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                try {
                    com.subhub.app.service.ScreenshotAccessibilityService instance = new com.subhub.app.service.ScreenshotAccessibilityService();
                    java.lang.reflect.Method attach = android.content.ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
                    attach.setAccessible(true); attach.invoke(instance, context);
                    java.lang.reflect.Field penanceField = instance.getClass().getDeclaredField("penance"); penanceField.setAccessible(true);
                    penanceField.set(instance, new PenanceManager(context)); service.set(instance);
                } catch (Exception failure) { throw new AssertionError(failure); }
            });
            // Invoke the actual callback without granting/binding Accessibility or starting capture.
            // This proves the service-to-recorder edge, not Android's live event delivery.
            for (String app : Arrays.asList("com.example.unselected", "com.example.selected", "com.example.selected")) {
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    android.view.accessibility.AccessibilityEvent event = android.view.accessibility.AccessibilityEvent.obtain(
                            android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
                    try { event.setPackageName(app); event.setClassName("android.app.Activity"); service.get().onAccessibilityEvent(event); }
                    finally { event.recycle(); }
                });
            }
            drain(repository); assertEquals(1, repository.recent(PersonId.LOCAL, 20).size());
            ActivityEntry entry = repository.recent(PersonId.LOCAL, 1).get(0);
            assertEquals("com.example.selected", entry.event.packageName); assertFalse(entry.unread);
            repository.close(); local.close(); local = new RemoteStore(context, name);
            repository = new RemoteActivityRepository(context, local, () -> {}, notifications); drain(repository);
            assertEquals(entry.key, repository.recent(PersonId.LOCAL, 1).get(0).key);
            ActivitySources.install(repository); ControllerPinManager.enterDomMode();
            try (androidx.test.core.app.ActivityScenario<DomActivityActivity> page =
                    androidx.test.core.app.ActivityScenario.launch(DomActivityActivity.class)) {
                java.util.concurrent.atomic.AtomicBoolean shown = new java.util.concurrent.atomic.AtomicBoolean();
                long deadline = android.os.SystemClock.uptimeMillis() + 8000;
                do {
                    page.onActivity(activity -> shown.set(activity.findViewById(com.subhub.app.R.id.dom_activity_list)
                            .findViewWithTag("activity_entry:" + entry.key) != null));
                    if (!shown.get()) android.os.SystemClock.sleep(50);
                } while (!shown.get() && android.os.SystemClock.uptimeMillis() < deadline);
                assertTrue(shown.get()); page.recreate();
                page.onActivity(activity -> assertNotNull(activity.findViewById(com.subhub.app.R.id.dom_activity_list)));
            }
        } finally {
            ActivitySources.install(priorUi); RemoteActivityRecorder.install(priorRecorder);
            repository.close(); local.close();
        }
    }

    @Test public void combinedFeedPagesExistingLocalAndEligibleRemoteEvents() throws Exception {
        receiver.getWritableDatabase().delete("activity_source", null, null);
        long now = System.currentTimeMillis();
        receiving.foreground("com.example.launcher", "com.example.local", true, now + 10);
        drain(receiving);
        receiver.receive(SUB, publish("remote_event", now));
        ActivityRepository.Page first = receiving.load(null, 1);
        assertEquals(1, first.entries.size());
        assertNotNull(first.nextCursor);
        ActivityRepository.Page second = receiving.load(first.nextCursor, 1);
        assertEquals(1, second.entries.size());
        assertNull(second.nextCursor);
        Set<PersonId> people = new HashSet<>();
        people.add(first.entries.get(0).personId); people.add(second.entries.get(0).personId);
        assertEquals(new HashSet<>(Arrays.asList(PersonId.LOCAL, RemotePersonRepository.id(targetPeer))), people);
        assertEquals(2, receiving.recent(null, 10).size());
        ActivityEntry remoteEntry = first.entries.get(0).personId.isLocal() ? second.entries.get(0) : first.entries.get(0);
        ActivityEntry localEntry = first.entries.get(0).personId.isLocal() ? first.entries.get(0) : second.entries.get(0);
        ActivityRepository prior = ActivitySources.repository();
        ActivitySources.install(receiving);
        try (androidx.test.core.app.ActivityScenario<DomActivityActivity> screen =
                androidx.test.core.app.ActivityScenario.launch(DomActivityActivity.class)) {
            java.util.concurrent.atomic.AtomicBoolean shown = new java.util.concurrent.atomic.AtomicBoolean();
            long deadline = android.os.SystemClock.uptimeMillis() + 8000;
            do {
                screen.onActivity(activity -> shown.set(
                        activity.findViewById(com.subhub.app.R.id.dom_activity_list)
                                .findViewWithTag("activity_entry:" + localEntry.key) != null
                        && activity.findViewById(com.subhub.app.R.id.dom_activity_list)
                                .findViewWithTag("activity_entry:" + remoteEntry.key) != null));
                if (!shown.get()) android.os.SystemClock.sleep(50);
            } while (!shown.get() && android.os.SystemClock.uptimeMillis() < deadline);
            assertTrue("Both local and remote events appear on Activity", shown.get());
        } finally { ActivitySources.install(prior); }
        assertTrue(remoteEntry.unread);
        receiving.markRead(Collections.singletonList(remoteEntry.key));
        assertEquals(0, receiving.unreadCount());
        assertEquals(remoteEntry.key, receiving.locate(remoteEntry.key, 10).entries.get(0).key);
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                .putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.DOM_ONLY.name()).commit();
        assertEquals(1, receiving.load(null, 10).entries.size());
        assertFalse(receiving.load(null, 10).entries.get(0).personId.isLocal());
    }
    @Test public void reconnectAndLimitLoopsDoNotDuplicateGenuineSourceTransitions()throws Exception {
        long now=System.currentTimeMillis()+1;
        sending.foreground("com.example.launcher","com.example.selected",true,now);drain(sending);
        sending.foreground("com.example.selected","com.example.selected",true,now+1);drain(sending);
        assertEquals(1,sending.recent(PersonId.LOCAL,20).size());
        sending.close();sending=new RemoteActivityRepository(context,sender,()->{},notifications);drain(sending);
        sending.foreground("","com.example.selected",true,now+2);drain(sending);assertEquals(1,sending.recent(PersonId.LOCAL,20).size());
        sending.foreground("com.example.selected","com.example.other",false,now+3);
        sending.foreground("com.example.other","com.example.selected",true,now+4);
        sending.limit("com.example.selected",true,37,Collections.singleton("com.example.selected"),now+5);
        sending.limit("com.example.selected",true,37,Collections.singleton("com.example.selected"),now+6);
        sending.limit("com.example.selected",true,83,Collections.singleton("com.example.selected"),now+7);drain(sending);
        assertEquals(4,sending.recent(PersonId.LOCAL,20).size());assertEquals(4,sender.nextBatch(DOM,generation,RemoteProtocol.Stream.EVENTS).changes.size());
        ActivityEntry recent=sending.recent(PersonId.LOCAL,1).get(0);assertEquals(83,recent.event.limitMinutes);
    }
    @Test public void localHistoryNeverBecomesAFutureDomsBacklogAndSupportsExactLocalNavigation()throws Exception {
        sender.revoke(DOM,generation);sending.refreshSource();long now=System.currentTimeMillis()+1;
        sending.foreground("com.example.launcher","com.example.selected",true,now);drain(sending);
        String localKey=sending.recent(PersonId.LOCAL,1).get(0).key;ActivityRepository.Page local=sending.locate(localKey,20);
        assertTrue(local.cursor.startsWith("local:"));assertEquals(localKey,sending.load(local.cursor,20).entries.get(0).key);
        assertFalse(local.entries.get(0).unread);
        PairingInvite replacement=PairingInvite.create(DOM,"fixture",System.currentTimeMillis());sender.acceptInvite(replacement,"New approval");sender.activate(DOM,replacement.id);
        sending.refreshSource();sending.prepare(sender.peer(DOM,replacement.id));
        assertTrue(sender.nextBatch(DOM,replacement.id,RemoteProtocol.Stream.EVENTS).changes.isEmpty());
        sending.foreground("com.example.selected","com.example.other",false,now+1);sending.foreground("com.example.other","com.example.selected",true,System.currentTimeMillis()+2);drain(sending);
        assertEquals(1,sender.nextBatch(DOM,replacement.id,RemoteProtocol.Stream.EVENTS).changes.size());
        assertEquals(2,sending.load("local:MAX",20).entries.size());
    }
    @Test public void oldPeerHeadInventoriesExcludeEventsAndUnnegotiatedHeadsAreRejected()throws Exception {
        publish("event_one",System.currentTimeMillis());
        JSONObject legacy=sender.outgoingHeads(DOM,generation);assertFalse(legacy.has("EVENTS"));receiver.confirmHeads(SUB,generation,legacy);
        JSONObject current=sender.outgoingHeads(DOM,generation,true);assertTrue(current.has("EVENTS"));
        assertThrows(Exception.class,()->receiver.confirmHeads(SUB,generation,current));
        receiver.receive(SUB,sender.nextBatch(DOM,generation,RemoteProtocol.Stream.EVENTS));receiver.confirmHeads(SUB,generation,current,true);
        assertTrue(Long.parseLong(receiver.metadata(SUB,generation,"confirmed_EVENTS","0"))>0);
    }
    @Test public void libraryRetirementKeepsActiveConfigurationAndAcknowledgedSequenceMeaning()throws Exception {
        sender.publish(DOM,generation,RemoteProtocol.Stream.BASELINE,"active",new JSONObject().put("kept","active"),false,1);
        sender.publish(DOM,generation,RemoteProtocol.Stream.BASELINE,"saved:legacy",new JSONObject().put("privateLibrary","removed"),false,2);
        sender.retireLibraries();RemoteProtocol.Batch batch=sender.nextBatch(DOM,generation,RemoteProtocol.Stream.BASELINE);
        assertEquals(2,batch.changes.size());assertEquals("active",new JSONObject(batch.changes.get(0).body).getString("kept"));
        assertTrue(batch.changes.get(1).deleted);assertEquals("{}",batch.changes.get(1).body);assertEquals(3,batch.through);
        receiver.receive(SUB,batch);sender.acknowledge(DOM,new RemoteProtocol.Ack(batch));receiver.confirmHeads(SUB,generation,sender.outgoingHeads(DOM,generation));
        assertTrue(Long.parseLong(receiver.metadata(SUB,generation,"confirmed_BASELINE","0"))>0);
        sender.retireLibraries();assertTrue(sender.nextBatch(DOM,generation,RemoteProtocol.Stream.BASELINE).changes.isEmpty());
    }
    @Test public void sourceAndReceiptTablesUpgradeWithoutChangingExistingPeerRows()throws Exception {
        RemoteStore legacy=database();String name=databases.get(databases.size()-1);
        SQLiteDatabase db=legacy.getWritableDatabase();db.execSQL("INSERT INTO peers(peer,generation,name,local_role,state) VALUES(?,?,?,'DOM','ACTIVE')",new Object[]{SUB,generation,"Kept peer"});
        db.execSQL("DROP TABLE activity_receipts");db.execSQL("DROP TABLE activity_source");db.execSQL("DROP TABLE activity_state");db.execSQL("DROP TABLE payload_maintenance");db.setVersion(2);legacy.close();
        RemoteStore upgraded=new RemoteStore(context,name);
        try{assertEquals("Kept peer",upgraded.peer(SUB,generation).name);assertEquals(0,count(upgraded,"activity_receipts"));assertEquals(0,count(upgraded,"activity_source"));}
        finally{upgraded.close();}
    }
    @Test public void assessmentLedgerPublishesOneDurableRuleFactWithoutPaymentFields()throws Exception {
        java.lang.reflect.Field installed=RemoteActivityRecorder.class.getDeclaredField("installed");installed.setAccessible(true);
        RemoteActivityRecorder.Sink previous=(RemoteActivityRecorder.Sink)installed.get(null);RemoteActivityRecorder.install(sending);
        try {
            new com.subhub.app.settings.FeatureModuleManager(context).save(true,true,true);
            new com.subhub.app.appmode.AppModeManager(context).setArmed(true);
            PenanceManager manager=new PenanceManager(context);Map<PenanceInfraction,Integer> rules=new EnumMap<>(PenanceInfraction.class);rules.put(PenanceInfraction.NEW_DETECTION,123);
            manager.configure(true,rules,1000,5000,10,10,1);long now=System.currentTimeMillis();
            assertEquals(123,manager.recordInfraction(PenanceInfraction.NEW_DETECTION,1,now));drain(sending);
            List<PenanceEvent> ledger=manager.durableActivityEvents();assertEquals(1,ledger.size());
            RemoteActivityRecorder.tribute(ledger.get(0).getId());drain(sending);
            RemoteProtocol.Batch batch=sender.nextBatch(DOM,generation,RemoteProtocol.Stream.EVENTS);assertEquals(1,batch.changes.size());
            JSONObject body=new JSONObject(batch.changes.get(0).body);ActivityEvent fact=RemoteActivityWire.decode(body);
            assertEquals(ActivityEvent.Kind.TRIBUTE_TRIGGERED,fact.kind);assertEquals(123,fact.amountCents);assertEquals("new_detection",fact.reason);
            assertEquals("EUR",fact.currency);assertFalse(body.has("settlementId"));assertFalse(body.has("paid"));
        } finally {RemoteActivityRecorder.install(previous);new com.subhub.app.appmode.AppModeManager(context).setArmed(false);}
    }
    @Test public void deviceCapabilityAndCredentialChangesDenyStaleFeedAndConfigurationActions()throws Exception {
        RemoteProtocol.Batch batch=publish("event_one",System.currentTimeMillis());receiver.receive(SUB,batch);
        ActivityEntry entry=receiving.load(null,20).entries.get(0);ControllerPinManager.enterSubMode();
        assertThrows(SecurityException.class,()->receiving.load(null,20));assertThrows(SecurityException.class,()->receiving.markRead(Collections.singletonList(entry.key)));
        ControllerPinManager.enterDomMode();context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit().putString(DeviceRolePreferences.KEY_MODE,DeviceRolePreferences.Mode.SUB_ONLY.name()).commit();
        assertThrows(SecurityException.class,()->receiving.load(null,20));
        assertThrows(SecurityException.class,()->receiver.queueConfiguration(SUB,generation,0,0,new JSONObject()));
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit().putString(DeviceRolePreferences.KEY_MODE,DeviceRolePreferences.Mode.DOM_ONLY.name()).commit();
        int before=count(sender,"activity_source");sending.foreground("com.example.other","com.example.selected",true,System.currentTimeMillis());drain(sending);assertEquals(before,count(sender,"activity_source"));
    }
    private static int count(RemoteStore store,String table){try(Cursor rows=store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM "+table,null)){assertTrue(rows.moveToFirst());return rows.getInt(0);}}
    private static void drain(RemoteActivityRepository repository)throws Exception {
        java.lang.reflect.Field field=RemoteActivityRepository.class.getDeclaredField("worker");field.setAccessible(true);
        ((ExecutorService)field.get(repository)).submit(()->{}).get(15,TimeUnit.SECONDS);
    }
    private static void restore(SharedPreferences preferences,Map<String,?> values){SharedPreferences.Editor editor=preferences.edit().clear();for(Map.Entry<String,?> row:values.entrySet()){Object value=row.getValue();String key=row.getKey();if(value instanceof String)editor.putString(key,(String)value);else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);else if(value instanceof Integer)editor.putInt(key,(Integer)value);else if(value instanceof Long)editor.putLong(key,(Long)value);else if(value instanceof Float)editor.putFloat(key,(Float)value);else if(value instanceof Set)editor.putStringSet(key,new HashSet<>((Set<String>)value));else throw new AssertionError("Unsupported fixture field");}assertTrue(editor.commit());}
    private static final class FakeNotifications implements RemoteActivityRepository.Notifications {
        final Set<String> shown=Collections.synchronizedSet(new HashSet<>()),cancelled=Collections.synchronizedSet(new HashSet<>());final java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();RemoteStore store;boolean insideTransaction;
        public boolean enabled(Context context){return true;}
        public boolean show(Context context,ActivityEntry entry){if(store!=null)insideTransaction|=store.getWritableDatabase().inTransaction();shown.add(entry.key);calls.incrementAndGet();return true;}
        public void cancel(Context context,ActivityEntry entry){cancelled.add(entry.key);}
        public void cancelPeer(Context context,PersonId peer){}
    }
}
