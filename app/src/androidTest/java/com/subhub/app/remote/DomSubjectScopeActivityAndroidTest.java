package com.subhub.app.remote;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static com.subhub.app.NativeUiActions.revealAboveNavigation;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import com.subhub.app.R;
import com.subhub.app.activity.*;
import androidx.test.filters.SdkSuppress;
import com.subhub.app.people.PeopleActivity;
import com.subhub.app.people.PersonId;
import com.subhub.app.relationships.DomSubjectScope;
import com.subhub.app.relationships.RelationshipUi;
import com.subhub.app.relationships.RelationshipPreparationKey;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.settings.SettingsRepository;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/** Uses an owned formal store; no transport, service, or source-history deletion. */
public final class DomSubjectScopeActivityAndroidTest extends LinkingFixture {
    @Rule public final com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture();
    @Rule public final com.subhub.app.UiAutomationStateRule uiAutomation = new com.subhub.app.UiAutomationStateRule();
    private RelationshipUi.Provider previousProvider;
    private ActivityRepository previousActivity;
    private FormalRelationshipProvider formal;
    private RemoteActivityRepository activity;
    private boolean allowAcknowledgement;
    private final java.util.List<String> acknowledged = new java.util.ArrayList<>();

    @Before public void prepareScope() throws Exception {
        Context original = context;
        context = new ContextWrapper(original) {
            @Override public Context getApplicationContext() { return this; }
            @Override public ApplicationInfo getApplicationInfo() {
                ApplicationInfo info = new ApplicationInfo(super.getApplicationInfo());
                info.dataDir += "/" + prefix; return info;
            }
        };
        previousProvider = RelationshipUi.provider(); previousActivity = ActivitySources.repository();
        ControllerPinManager.enterDomMode();
        formal = provider(); RelationshipUi.install(formal);
        activity = new RemoteActivityRepository(context, store, () -> fail("Unexpected synchronization"),
                new RemoteActivityRepository.Notifications() {
                    public boolean enabled(Context c) { return false; }
                    public boolean show(Context c, ActivityEntry e) { fail("Unexpected notification"); return false; }
                    public void cancel(Context c, ActivityEntry e) { assertTrue("Unexpected acknowledgement", allowAcknowledgement); acknowledged.add(e.key); }
                    public void cancelPeer(Context c, PersonId p) { fail("Unexpected revocation notification"); }
                });
        java.lang.reflect.Field worker = RemoteActivityRepository.class.getDeclaredField("worker");
        worker.setAccessible(true);
        ((java.util.concurrent.ExecutorService) worker.get(activity)).submit(() -> {}).get(5, TimeUnit.SECONDS);
        ActivityEvent event = new ActivityEvent("scope_local", ActivityEvent.Kind.APP_OPENED,
                System.currentTimeMillis(), "com.android.settings", 0, "", "", 0);
        db().execSQL("INSERT INTO activity_source(event_id,source_key,body,observed) VALUES(?,?,?,?)",
                new Object[]{event.id, event.id, RemoteActivityWire.encode(event, "UTC", "").toString(), event.observedAt});
    }

    @After public void restoreScope() throws Exception {
        try {
            if (previousProvider != null) RelationshipUi.install(previousProvider);
            if (previousActivity != null) ActivitySources.install(previousActivity);
        } finally {
            try { if (activity != null) activity.close(); }
            finally { assertTrue(context.getSharedPreferences("subhub_relationship_presentation", 0).edit().clear().commit()); }
        }
    }

    @Test public void unlinkedLocalIsAbsentFromDefaultDomActivityButExplicitHistorySurvives() throws Exception {
        String before = history();
        assertTrue(activity.load(null, 20).entries.isEmpty());
        assertTrue(activity.recent(null, 20).isEmpty());
        assertEquals(0, activity.unreadCount());
        assertEquals(1, activity.load("local:MAX", 20).entries.size());
        assertEquals(1, activity.recent(PersonId.LOCAL, 20).size());
        ActivitySources.install(activity);
        try (ActivityScenario<DomActivityActivity> page = ActivityScenario.launch(DomActivityActivity.class)) {
            AtomicBoolean ready = new AtomicBoolean();
            long deadline = android.os.SystemClock.uptimeMillis() + 8000;
            do {
                page.onActivity(host -> ready.set(contains(host.findViewById(android.R.id.content),
                        host.getString(R.string.dom_activity_empty))));
                if (!ready.get()) Thread.sleep(25);
            } while (!ready.get() && android.os.SystemClock.uptimeMillis() < deadline);
            assertTrue("Default page must finish loading with an empty feed", ready.get());
            page.onActivity(host -> assertNull(host.findViewById(android.R.id.content)
                    .findViewWithTag("activity_entry:local:scope_local")));
        }
        assertEquals(before, history());
    }

    @Test public void approvedLocalIsIncludedAndSameProviderUnlinkInvalidatesCapturedGeneration() throws Exception {
        RelationshipUi.Snapshot snapshot = await(formal.snapshot());
        RelationshipUi.LocalReview review = await(formal.prepareLocal(RelationshipPreparationKey.create(snapshot.revision), ref(snapshot, RelationshipUi.Role.DOM),
                ref(snapshot, RelationshipUi.Role.SUB), snapshot.revision));
        RelationshipUi.Operation linked = await(formal.confirmLocal(review.operationId, review.requestDigest));
        assertEquals(RelationshipUi.OperationState.ACTIVE, linked.state);
        String before = history();
        DomSubjectScope scope = DomSubjectScope.read(context);
        assertEquals(linked.generation, scope.localEdge().generation);
        assertEquals(1, activity.load(null, 20).entries.size());
        await(formal.unlink(linked.relationshipId, linked.generation));
        assertThrows(IllegalStateException.class, scope::revalidate);
        assertTrue(activity.load(null, 20).entries.isEmpty());
        assertEquals(1, activity.load("local:MAX", 20).entries.size());
        assertEquals(before, history());
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void peopleConfirmsAndUnlinksWithCurrentLocalPinAndProductionProvider() throws Exception {
        assertTrue(target.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                .putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.BOTH.name()).commit());
        // The activity uses the app preferences; the real provider uses the owned fixture's store.
        assertTrue(ControllerPinManager.setPin(target, "624871"));
        try (ActivityScenario<PeopleActivity> page = ActivityScenario.launch(PeopleActivity.class)) {
            awaitAction(page, "relationship_prepare");
            tapAction("relationship_prepare");
            awaitPreparedReview(page);
            // Bind confirmation to the prepared journal entry recovered in the normal People UI.
            page.recreate();
            awaitAction(page, "relationship_resume");
            tapAction("relationship_resume");
            awaitAction(page, "relationship_confirm");

            long reviewed = ControllerPinManager.domWorkspaceRevision();
            tapAction("relationship_confirm");
            submitLocalPin();
            awaitAction(page, "relationship_unlink");
            assertEquals(reviewed, ControllerPinManager.domWorkspaceRevision());
            RelationshipUi.Edge edge = await(formal.snapshot()).governingDom;
            assertNotNull("The real provider must commit the selected local relationship", edge);
            assertEquals(RelationshipUi.Delivery.LOCAL, edge.delivery);
            assertEquals("ACTIVE", edge.state);

            tapAction("relationship_unlink");
            submitLocalPin();
            awaitAction(page, "relationship_prepare");
            assertEquals(reviewed, ControllerPinManager.domWorkspaceRevision());
            assertNull(await(formal.snapshot()).governingDom);
        }
    }

    private static void submitLocalPin() {
        onView(withHint(R.string.controller_pin_label)).perform(replaceText("624871"), closeSoftKeyboard());
        onView(withText(R.string.controller_pin_unlock)).perform(click());
    }

    private static void tapAction(String tag) {
        onView(withTagValue(is(tag))).perform(revealAboveNavigation(), click());
    }

    private static void awaitPreparedReview(ActivityScenario<PeopleActivity> page) throws InterruptedException {
        awaitAnyAction(page, "relationship_confirm", "relationship_resume");
    }

    private static void awaitAction(ActivityScenario<PeopleActivity> page, String tag) throws InterruptedException {
        awaitAnyAction(page, tag);
    }

    private static void awaitAnyAction(ActivityScenario<PeopleActivity> page, String... tags) throws InterruptedException {
        AtomicBoolean available = new AtomicBoolean();
        long deadline = android.os.SystemClock.uptimeMillis() + 8000;
        do {
            page.onActivity(host -> {
                for (String tag : tags) {
                    View action = host.findViewById(android.R.id.content).findViewWithTag(tag);
                    if (action != null && action.isEnabled()) available.set(true);
                }
            });
            if (!available.get()) Thread.sleep(25);
        } while (!available.get() && android.os.SystemClock.uptimeMillis() < deadline);
        assertTrue("Expected an enabled People action " + java.util.Arrays.toString(tags), available.get());
    }

    @Test public void providerReadsAreOutsideStoreAndAuthorityLocksAndFailureNeverFallsBack() throws Exception {
        RelationshipUi.Provider checked = new RelationshipUi.Provider() {
            public CompletableFuture<RelationshipUi.Snapshot> snapshot() {
                assertFalse(Thread.holdsLock(store)); assertFalse(db().inTransaction());
                assertFalse(Thread.holdsLock(com.subhub.app.security.SubFeaturePolicy.LOCK));
                assertFalse(Thread.holdsLock(com.subhub.app.appmode.RuntimeSessionIdentity.LOCK));
                return formal.snapshot();
            }
            public CompletableFuture<RelationshipUi.LocalReview> prepareLocal(String k, String d, String s, String r) { throw new AssertionError(); }
            public CompletableFuture<RelationshipUi.Operation> confirmLocal(String i, String d) { throw new AssertionError(); }
            public CompletableFuture<RelationshipUi.Operation> resume(String i) { throw new AssertionError(); }
            public CompletableFuture<RelationshipUi.Operation> cancel(String i) { throw new AssertionError(); }
        };
        RelationshipUi.install(checked); assertTrue(activity.load(null, 20).entries.isEmpty());
        RelationshipUi.install(RelationshipUi.unavailable());
        assertThrows(Exception.class, () -> activity.load(null, 20));
        assertEquals(1, activity.load("local:MAX", 20).entries.size());
    }

    @Test public void offlineApprovedPeerAndUnreadReceiptSurviveReadWhileLocalStaysExcluded() throws Exception {
        PairingInvite invite = PairingInvite.create(DOM, "Synthetic Dom", System.currentTimeMillis());
        store.saveInvite(invite); store.bindInvite(invite.id, SUB, invite.tokenHash(), System.currentTimeMillis());
        store.approveInvite(invite.id, SUB, "Offline synthetic Sub", System.currentTimeMillis()); store.activate(SUB, invite.id);
        RemoteStore.Peer peer = store.peer(SUB, invite.id);
        ActivityEvent event = new ActivityEvent("scope_peer", ActivityEvent.Kind.APP_OPENED,
                System.currentTimeMillis() - 3600000, "com.android.settings", 0, "", "", 0);
        String key = RemoteActivityStore.entryKey(peer, event.id);
        db().execSQL("INSERT INTO activity_receipts(entry_key,peer,generation,event_id,body,received) VALUES(?,?,?,?,?,?)",
                new Object[]{key, SUB, invite.id, event.id, RemoteActivityWire.encode(event, "UTC", "").toString(), event.observedAt});
        String before = history();
        assertEquals(1, activity.load(null, 20).entries.size());
        assertEquals(key, activity.load(null, 20).entries.get(0).key);
        assertEquals(1, activity.unreadCount());
        assertEquals(before, history());
        store.revoke(SUB, invite.id);
        assertTrue(activity.load(null, 20).entries.isEmpty());
        assertEquals(0, activity.unreadCount());
        assertEquals(1, activity.load("local:MAX", 20).entries.size());
    }

    @Test public void markAllUsesTheVerifiedContinuityGateWithoutNestingItInsideSql() throws Exception {
        PairingInvite invite = PairingInvite.create(DOM, "Synthetic Dom", System.currentTimeMillis());
        store.saveInvite(invite); store.bindInvite(invite.id, SUB, invite.tokenHash(), System.currentTimeMillis());
        store.approveInvite(invite.id, SUB, "Synthetic Sub", System.currentTimeMillis()); store.activate(SUB, invite.id);
        RemoteStore.Peer peer = store.peer(SUB, invite.id);
        ActivityEvent event = new ActivityEvent("gate_bulk", ActivityEvent.Kind.APP_OPENED,
                System.currentTimeMillis(), "com.android.settings", 0, "", "", 0);
        String key = RemoteActivityStore.entryKey(peer, event.id);
        db().execSQL("INSERT INTO activity_receipts(entry_key,peer,generation,event_id,body,received) VALUES(?,?,?,?,?,?)",
                new Object[]{key, SUB, invite.id, event.id, RemoteActivityWire.encode(event, "UTC", "").toString(), event.observedAt});
        assertNotNull("Exercise a registered continuity witness, not an unbound fake", LinkingWitness.attachedWithoutIo(store));
        assertEquals(1, activity.unreadCount());
        allowAcknowledgement = true;
        activity.markAllRead();
        assertEquals(0, activity.unreadCount());
        assertEquals(java.util.List.of(key), acknowledged);
        assertFalse("Repository leaves no transaction open", db().inTransaction());
        assertEquals(1, activity.load("local:MAX", 20).entries.size());
    }

    private String history() {
        StringBuilder value = new StringBuilder();
        for (String table : new String[]{"activity_source", "activity_receipts", "activity_state"})
            try (Cursor rows = db().rawQuery("SELECT * FROM " + table + " ORDER BY rowid", null)) {
                while (rows.moveToNext()) for (int i = 0; i < rows.getColumnCount(); i++)
                    value.append(rows.getString(i)).append('|');
            }
        return value.toString();
    }

    private static boolean contains(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (contains(((ViewGroup) view).getChildAt(i), text)) return true;
        return false;
    }
}
