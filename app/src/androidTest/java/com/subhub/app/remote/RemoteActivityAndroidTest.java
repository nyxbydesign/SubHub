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
import com.subhub.app.relationships.DomScopeObserverFixture;
import com.subhub.app.security.*;
import com.subhub.app.settings.SettingsRepository;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RemoteActivityAndroidTest {
    @Rule public final com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture();
    private Context context;
    private DomScopeObserverFixture scope;
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
        assertFalse(com.subhub.app.service.ScreenCaptureService.isRunning());
        assertFalse(com.subhub.app.service.ScreenshotAccessibilityService.isRunning());
        settings=new HashMap<>(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).getAll());
        penance=new HashMap<>(context.getSharedPreferences(PenanceManager.PREFS_NAME,0).getAll());
        scope=new DomScopeObserverFixture(context);
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit().putString(DeviceRolePreferences.KEY_MODE,DeviceRolePreferences.Mode.BOTH.name()).commit();
        context.getSharedPreferences(PenanceManager.PREFS_NAME,0).edit().clear().commit();
        authenticateDom();
        sender=database();receiver=database();pair();
        // These named databases own the model projection; the normal provider queries another store.
        scope.outgoing(DomScopeObserverFixture.peer(targetPeer, 1));
        sending=new RemoteActivityRepository(context,sender,()->{},notifications);
        receiving=new RemoteActivityRepository(context,receiver,()->{},notifications);
        receiver.onActivityReceived(receiving::received);
        receiver.onRevoked((identity,gen)->receiving.revoked(receiver.peer(identity,gen)));
        drain(sending);drain(receiving);
    }
    @After public void cleanup()throws Exception {
        try {
            try { if(sending!=null)sending.close(); }
            finally { if(receiving!=null)receiving.close(); }
        } finally {
            try {
                try { if(sender!=null)sender.close(); }
                finally { if(receiver!=null)receiver.close(); }
            } finally {
                try { if(scope!=null)scope.close(); }
                finally {
                    try {
                        for(String name:databases)
                            if(context.getDatabasePath(name).isFile())assertTrue(context.deleteDatabase(name));
                    }
                    finally {
                        try { if(settings!=null)restore(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0),settings); }
                        finally {
                            try { if(penance!=null)restore(context.getSharedPreferences(PenanceManager.PREFS_NAME,0),penance); }
                            finally { ControllerPinManager.enterSubMode(); }
                        }
                    }
                }
            }
        }
    }
    private void authenticateDom() {
        ControllerPinManager.initialize(context);
        String pin = InstrumentationRegistry.getArguments().getString("controller_pin", "2468");
        if (!ControllerPinManager.isConfigured(context)) {
            assertFalse("Do not replace another credential owner", ControllerPinManager.hasCredentials(context));
            // IntegrationFixture restores the original settings, including an initially unkeyed guest.
            assertTrue("Prepare only the missing ordinary local PIN", ControllerPinManager.setPin(context, pin));
        }
        assertTrue("Authenticate the configured PIN without replacing it", ControllerPinManager.verify(context, pin));
        assertTrue(ControllerPinManager.isDomWorkspaceUnlocked());
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
    @Test public void markAllAcknowledgesAllPagesButNotFutureReceiptsAndRequiresDomAuthority() throws Exception {
        long time = System.currentTimeMillis();
        for (int index = 0; index < 123; index++) sender.publish(DOM, generation, RemoteProtocol.Stream.EVENTS,
                "bulk_" + index, event("bulk_" + index, time + index), false, time + index);
        receiver.receive(SUB, sender.nextBatch(DOM, generation, RemoteProtocol.Stream.EVENTS));
        assertEquals(123, receiving.unreadCount());
        receiving.markAllRead();
        assertEquals(0, receiving.unreadCount());
        assertEquals(123, count(receiver, "activity_receipts"));
        assertEquals(123, notifications.cancelled.size());
        receiver.receive(SUB, publish("bulk_later", time + 1000));
        assertEquals(1, receiving.unreadCount());
        ControllerPinManager.enterSubMode();
        assertThrows(SecurityException.class, receiving::markAllRead);
        authenticateDom();
        assertEquals(1, receiving.unreadCount());
        receiver.revoke(SUB, generation);
        receiving.markAllRead();
        try (Cursor rows = receiver.getReadableDatabase().rawQuery(
                "SELECT is_read FROM activity_receipts WHERE event_id='bulk_later'", null)) {
            assertTrue(rows.moveToFirst()); assertEquals(0, rows.getInt(0));
        }
    }

    @Test public void markAllDoesNotAcknowledgePeersOutsideTheCurrentDomScope() throws Exception {
        receiver.receive(SUB, publish("bulk_outside_scope", System.currentTimeMillis()));
        assertEquals(1, receiving.unreadCount());
        scope.outgoing();
        receiving.markAllRead();
        assertEquals(0, receiving.unreadCount());
        scope.outgoing(DomScopeObserverFixture.peer(targetPeer, 1));
        assertEquals(1, receiving.unreadCount());
    }

    @Test public void perKindSuppressionIsConsumedBeforeReenableOrTransportReplay() throws Exception {
        assertTrue("The coordinator must provision POST_NOTIFICATIONS and an enabled Activity channel before this OS-delivery fixture",
                ActivityNotifications.enabled(context));
        SharedPreferences choices = context.getSharedPreferences(com.subhub.app.notifications.NotificationPreferences.STORE, 0);
        Map<String, ?> original = new HashMap<>(choices.getAll());
        String key = com.subhub.app.notifications.NotificationPreferences.key(
                com.subhub.app.notifications.NotificationPreferences.Role.DOM,
                com.subhub.app.notifications.NotificationPreferences.Event.TRACKED_APP_OPENED);
        String id = "suppressed_" + UUID.randomUUID();
        try {
            assertTrue(choices.edit().putBoolean(key, false).commit());
            receiving.close(); receiving = new RemoteActivityRepository(context, receiver, () -> {});
            receiver.onActivityReceived(receiving::received); drain(receiving);
            RemoteProtocol.Batch batch = publish(id, System.currentTimeMillis());
            receiver.receive(SUB, batch); drain(receiving);
            try (Cursor row = receiver.getReadableDatabase().rawQuery("SELECT notified FROM activity_receipts WHERE event_id=?", new String[]{id})) {
                assertTrue(row.moveToFirst()); assertEquals(1, row.getInt(0));
            }
            assertTrue(choices.edit().putBoolean(key, true).commit());
            receiver.receive(SUB, batch); receiving.dispatchNotifications(); drain(receiving);
            android.app.NotificationManager manager = context.getSystemService(android.app.NotificationManager.class);
            String entry = RemoteActivityStore.entryKey(targetPeer, id);
            for (android.service.notification.StatusBarNotification active : manager.getActiveNotifications())
                assertFalse(active.getTag() != null && active.getTag().endsWith(entry));
        } finally { restore(choices, original); }
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
        scope.outgoing();
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
            assertTrue(repository.load(null, 20).entries.isEmpty());
            assertTrue(repository.recent(null, 20).isEmpty()); assertEquals(0, repository.unreadCount());
            assertEquals(entry.key, repository.load("local:MAX", 20).entries.get(0).key);
            repository.close(); local.close(); local = new RemoteStore(context, name);
            repository = new RemoteActivityRepository(context, local, () -> {}, notifications); drain(repository);
            assertEquals(entry.key, repository.recent(PersonId.LOCAL, 1).get(0).key);
            assertTrue(repository.load(null, 20).entries.isEmpty());
            ActivitySources.install(repository); authenticateDom();
            Intent localHistory = new Intent(context, DomActivityActivity.class)
                    .putExtra(DomActivityActivity.EXTRA_LOCAL_HISTORY, true)
                    .putExtra(DomActivityActivity.EXTRA_ENTRY_KEY, entry.key);
            try (androidx.test.core.app.ActivityScenario<DomActivityActivity> page =
                    androidx.test.core.app.ActivityScenario.launch(localHistory)) {
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

    @Test public void localActivityUsesSavedSubNameAcrossQueriesAndRenames() throws Exception {
        SharedPreferences names = context.getSharedPreferences("subhub_profile_names", 0);
        Map<String, ?> original = new HashMap<>(names.getAll());
        try {
            assertTrue(com.subhub.app.people.ProfileNames.set(context, true, "Synthetic Dom"));
            assertTrue(com.subhub.app.people.ProfileNames.set(context, false, "Synthetic Sub"));
            receiving.foreground("com.example.launcher", "com.example.local", true, System.currentTimeMillis());
            drain(receiving);
            receiver.receive(SUB, publish("remote_named", System.currentTimeMillis() - 1));
            ActivityEntry local = receiving.recent(PersonId.LOCAL, 1).get(0);
            String key = local.key;
            assertEquals("Synthetic Sub", local.personName);
            assertEquals("Synthetic Sub", receiving.locate(key, 10).entries.stream()
                    .filter(row -> row.key.equals(key)).findFirst().get().personName);
            assertEquals("Synthetic Sub", receiving.load("local:MAX", 10).entries.get(0).personName);
            assertFalse(receiving.recent(null, 10).stream().anyMatch(row -> row.key.equals(key)));
            assertEquals(1, receiving.load(null, 10).entries.size());
            assertEquals("Fixture Sub", receiving.recent(RemotePersonRepository.id(targetPeer), 1).get(0).personName);
            assertTrue(com.subhub.app.people.ProfileNames.set(context, false, "Renamed Sub"));
            assertEquals(key, receiving.recent(PersonId.LOCAL, 1).get(0).key);
            assertEquals("Renamed Sub", receiving.recent(PersonId.LOCAL, 1).get(0).personName);
            assertEquals("Renamed Sub", receiving.locate(key, 10).entries.stream()
                    .filter(row -> row.key.equals(key)).findFirst().get().personName);
            assertEquals("Renamed Sub", receiving.load("local:MAX", 10).entries.get(0).personName);
            assertFalse(receiving.recent(null, 10).stream().anyMatch(row -> row.key.equals(key)));
            assertTrue(com.subhub.app.people.ProfileNames.set(context, false, "\u202e\u2066"));
            assertEquals(context.getString(com.subhub.app.R.string.header_subtitle_sub),
                    receiving.recent(PersonId.LOCAL, 1).get(0).personName);
            assertEquals(PersonId.LOCAL, receiving.recent(PersonId.LOCAL, 1).get(0).personId);
        } finally { restore(names, original); }
    }

    @Test public void combinedFeedPagesExistingLocalAndEligibleRemoteEvents() throws Exception {
        receiver.getWritableDatabase().delete("activity_source", null, null);
        long now = System.currentTimeMillis();
        receiving.foreground("com.example.launcher", "com.example.local", true, now + 10);
        drain(receiving);
        receiver.receive(SUB, publish("remote_event", now));
        assertEquals(1, receiving.load(null, 10).entries.size());
        assertFalse(receiving.load(null, 10).entries.get(0).personId.isLocal());
        assertEquals(1, receiving.load("local:MAX", 10).entries.size());
        String remoteKey = receiving.load(null, 10).entries.get(0).key;
        assertEquals(1, receiving.unreadCount());
        scope.outgoing(DomScopeObserverFixture.peer(SUB, "unapproved-generation", 1));
        assertTrue(receiving.load(null, 10).entries.isEmpty());
        assertTrue(receiving.recent(RemotePersonRepository.id(targetPeer), 10).isEmpty());
        assertTrue(receiving.locate(remoteKey, 10).entries.isEmpty());
        assertEquals(0, receiving.unreadCount());
        scope.outgoing();
        assertTrue(receiving.recent(null, 10).isEmpty());
        scope.outgoing(DomScopeObserverFixture.peer(targetPeer, 1));
        assertEquals(remoteKey, receiving.load(null, 10).entries.get(0).key);
        assertEquals(1, receiving.unreadCount());
        // Only this receiver has an outgoing LOCAL approval; the sender's incoming Dom is separate.
        scope.outgoing(DomScopeObserverFixture.local("approved-local", 1),
                DomScopeObserverFixture.peer(targetPeer, 2));
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
        assertEquals(localEntry.key, receiving.load("local:MAX", 10).entries.get(0).key);
        scope.outgoing(DomScopeObserverFixture.peer(targetPeer, 1));
        assertEquals(remoteEntry.key, receiving.load(null, 10).entries.get(0).key);
        assertEquals(1, receiving.load(null, 10).entries.size());
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
        String name="activity-v2-"+UUID.randomUUID()+".db";databases.add(name);
        assertFalse(context.getDatabasePath(name).exists());
        try(SQLiteDatabase db=context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null)) {
            createVersion2Schema(db);
            db.execSQL("INSERT INTO peers(peer,generation,name,local_role,state) VALUES(?,?,?,'DOM','ACTIVE')",new Object[]{SUB,generation,"Kept peer"});
            db.setVersion(2);assertEquals(2,db.getVersion());
            Set<String> tables=new HashSet<>();
            try(Cursor rows=db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name!='android_metadata'",null)) {
                while(rows.moveToNext())tables.add(rows.getString(0));
            }
            assertEquals(new HashSet<>(Arrays.asList("peers","invites","local_objects","outbox","remote_objects","outgoing","incoming","commands","remote_metadata")),tables);
        }
        RemoteStore upgraded=new RemoteStore(context,name);
        try{assertEquals("Kept peer",upgraded.peer(SUB,generation).name);assertEquals(0,count(upgraded,"activity_receipts"));assertEquals(0,count(upgraded,"activity_source"));
            RemoteStore.Peer peer=upgraded.peer(SUB,generation);
            assertEquals(SUB,peer.identity);assertEquals(generation,peer.generation);
            assertEquals(RemoteProtocol.Role.DOM,peer.localRole);assertEquals(RemoteStore.State.ACTIVE,peer.state);
            assertEquals(0,count(upgraded,"activity_state"));assertEquals(RemoteStore.DATABASE_VERSION,upgraded.getReadableDatabase().getVersion());
        }
        finally{upgraded.close();}
    }
    // RemoteStore v2 onCreate/createMetadata from db22ddd689d5095f28cd52f552a126dcd4b52c85.
    private static void createVersion2Schema(SQLiteDatabase db) {
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
        db.execSQL("CREATE TABLE remote_metadata(peer TEXT NOT NULL,generation TEXT NOT NULL,name TEXT NOT NULL,value TEXT NOT NULL,PRIMARY KEY(peer,generation,name))");
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
        authenticateDom();context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit().putString(DeviceRolePreferences.KEY_MODE,DeviceRolePreferences.Mode.SUB_ONLY.name()).commit();
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
