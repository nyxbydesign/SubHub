package com.subhub.app.remote;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.appmode.AppTimerManager;
import com.subhub.app.penance.PenanceInfraction;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.people.PersonId;
import com.subhub.app.security.SubFeaturePolicy;
import com.subhub.app.settings.SettingsRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class ExtraTimeRequestAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test public void retainedCapabilityCannotBypassConsentWorkspaceOrRevocation() throws Exception {
        for (String restriction : new String[]{"consent", "workspace", "revocation"}) {
            // Each restriction owns a fresh pair and ledger; never weaken a guard to reset a pair.
            try (RemoteExtraTimeUiFixture fixture = RemoteExtraTimeUiFixture.setupSub(context)) {
                ExtraTimeRequests repository = fixture.subRequests();
                RemoteStore store = RemoteFeature.get(context).store;
                FormalRelationshipCoordinator.requireOwnedFixture(store);
                RemoteStore.Peer peer = store.peers(false).stream()
                        .filter(value -> value.state == RemoteStore.State.ACTIVE
                                && value.localRole == RemoteProtocol.Role.SUB).findFirst().orElseThrow();
                String scope = peer.identity + "_" + peer.generation;
                assertEquals("1", store.metadata(peer.identity, peer.generation, "extra_time_version", "missing"));
                assertTrue(repository.snapshot(System.currentTimeMillis()).canRequest);
                assertTrue(new ExtraTimeStore(context).load(scope).isEmpty());
                assertTrue(new PenanceManager(context).snapshot(System.currentTimeMillis()).getEvents().isEmpty());

                if (restriction.equals("consent")) {
                    assertTrue(SubFeaturePolicy.setAllowed(context, "limits", false));
                    assertFalse(SubFeaturePolicy.allowed(context, "limits"));
                } else if (restriction.equals("workspace")) {
                    fixture.switchDom();
                    assertTrue(com.subhub.app.security.DeviceRolePreferences.isDomWorkspace(context));
                } else {
                    // Domain-only authenticated withdrawal. Encrypted transport authentication is
                    // covered separately by ExtraTimeWireAndroidTest; no network is opened here.
                    try (PeerTransport.Connection authenticated = new PeerTransport.Connection() {
                        public String authenticatedPeerIdentity() { return peer.identity; }
                        public java.io.InputStream input() { throw new AssertionError("No fixture network reads"); }
                        public java.io.OutputStream output() { throw new AssertionError("No fixture network writes"); }
                        public void setReadTimeoutMillis(long timeout) { throw new AssertionError("No fixture network deadlines"); }
                        public boolean isDirect() { return true; }
                        public void close() { }
                    }) {
                        store.revokeAuthenticatedPeer(authenticated, peer);
                    }
                    assertEquals(RemoteStore.State.REVOKED, store.peer(peer.identity, peer.generation).state);
                }

                assertEquals("Restriction must not rely on clearing cached compatibility: " + restriction,
                        "1", store.metadata(peer.identity, peer.generation, "extra_time_version", "missing"));
                assertThrows(SecurityException.class,
                        () -> repository.submit(fixture.firstPackage, 100, "EUR"));
                assertTrue("No request, including unpublished submissions: " + restriction,
                        new ExtraTimeStore(context).load(scope).isEmpty());
                assertTrue("No tribute assessed: " + restriction,
                        new PenanceManager(context).snapshot(System.currentTimeMillis()).getEvents().isEmpty());
                if (restriction.equals("workspace")) fixture.switchSub();
            }
        }
    }

    @Test public void submissionChargesOnceAndApprovalAppliesOnlyItsTemporaryAppCredit() throws Exception {
        try (RemoteExtraTimeUiFixture fixture = RemoteExtraTimeUiFixture.setupSub(context)) {
            ExtraTimeRequests sub = fixture.subRequests();
            ExtraTimeRequests.Snapshot offered = sub.snapshot(System.currentTimeMillis());
            assertFalse(offered.scope.isEmpty());
            assertThrows(IllegalArgumentException.class,()->sub.submit(fixture.firstPackage,100,"EUR",null));
            assertThrows(IllegalArgumentException.class,()->sub.submit(fixture.firstPackage,100,"EUR",""));
            fixture.setSubAppTimeSupport(false);
            ExtraTimeRequests.Snapshot unsupported = sub.snapshot(System.currentTimeMillis());
            assertFalse(unsupported.supported); assertFalse(unsupported.canRequest);
            assertEquals(offered.scope,unsupported.scope);
            assertThrows(SecurityException.class,()->sub.submit(fixture.firstPackage,100,"EUR"));
            assertThrows(SecurityException.class,()->sub.submit(fixture.firstPackage,100,"EUR",offered.scope));
            assertTrue(new ExtraTimeStore(context).load(offered.scope).isEmpty());
            assertTrue(new PenanceManager(context).snapshot(System.currentTimeMillis()).getEvents().isEmpty());
            fixture.setSubAppTimeSupport(true);
            ExtraTimeRequests.Snapshot empty = sub.snapshot(System.currentTimeMillis());
            assertEquals(2, empty.apps.size());
            assertTrue(empty.requests.isEmpty());
            for (ExtraTimeRequests.App app : empty.apps) {
                assertTrue(app.exhausted);
                assertNull(app.pending);
                assertNull(app.latest);
                assertEquals(0L, app.remainingMillis);
            }
            assertThrows(SecurityException.class,()->sub.submit(fixture.firstPackage,100,"EUR",offered.scope+"_stale"));
            assertTrue(new ExtraTimeStore(context).load(offered.scope).isEmpty());
            assertTrue(new PenanceManager(context).snapshot(System.currentTimeMillis()).getEvents().isEmpty());
            ExtraTimeRequests.Request request = sub.submit(fixture.firstPackage,100,"EUR",offered.scope);
            ExtraTimeRequests.App awaiting = sub.snapshot(System.currentTimeMillis()).apps.stream()
                    .filter(app -> app.packageName.equals(fixture.firstPackage)).findFirst().orElseThrow();
            assertEquals(request.id, awaiting.pending.id);
            assertSame(awaiting.pending, awaiting.latest);
            assertEquals(request.id,sub.submit(fixture.firstPackage,100,"EUR").id);
            assertEquals(request.id,sub.submit(fixture.firstPackage,100,"EUR",offered.scope).id);
            assertThrows(SecurityException.class,()->sub.submit(fixture.firstPackage,100,"EUR",offered.scope+"_stale"));
            assertEquals(1,new PenanceManager(context).snapshot(System.currentTimeMillis()).getEvents().size());
            assertEquals(AppTimerManager.LimitStatus.PER_APP,new AppTimerManager(context).limitStatus(
                    fixture.firstPackage,java.util.Set.of(fixture.firstPackage),System.currentTimeMillis()));
            fixture.transfer(); fixture.switchDom();
            ExtraTimeRequests dom = fixture.domRequests();
            assertThrows(IllegalArgumentException.class,()->dom.approve(request.id,0));
            assertThrows(IllegalArgumentException.class,()->dom.approve(request.id,1441));
            dom.approve(request.id,25);
            assertEquals(1,new PenanceManager(context).snapshot(System.currentTimeMillis()).getEvents().size());
            fixture.transfer(); fixture.transfer(); fixture.switchSub();
            long measuredAt = System.currentTimeMillis();
            ExtraTimeRequests.Snapshot after = fixture.subRequests().snapshot(measuredAt);
            ExtraTimeRequests.App first = after.apps.stream().filter(app->app.packageName.equals(fixture.firstPackage)).findFirst().orElseThrow();
            ExtraTimeRequests.App second = after.apps.stream().filter(app->app.packageName.equals(fixture.secondPackage)).findFirst().orElseThrow();
            assertFalse(first.exhausted); assertEquals(25*60_000L,first.remainingMillis); assertTrue(second.exhausted);
            assertEquals(request.id, first.latest.id);
            assertEquals(ExtraTimeRequests.Status.APPROVED, first.latest.status);
            assertNull(first.pending);
            assertEquals(25, first.bonusMinutes);
            AppTimerManager timers = new AppTimerManager(context);
            java.util.Set<String> selected = java.util.Set.of(fixture.firstPackage, fixture.secondPackage);
            for (ExtraTimeRequests.App app : after.apps) {
                assertEquals(timers.snapshot(app.packageName, measuredAt).appUsedMillis, app.usedMillis);
                assertEquals(timers.limitStatus(app.packageName, selected, measuredAt) != AppTimerManager.LimitStatus.NONE,
                        app.exhausted);
            }
            assertEquals(5,timers.allowanceMinutes(fixture.firstPackage));
            assertEquals(1,new PenanceManager(context).snapshot(System.currentTimeMillis()).getEvents().size());
            assertThrows(IllegalStateException.class,()->fixture.subRequests().submit(fixture.firstPackage,100,"EUR"));
            assertTrue(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit()
                    .putInt("app_timer_allowance_minutes:"+fixture.firstPackage,6).commit());
            ExtraTimeRequests.Snapshot changed = fixture.subRequests().snapshot(System.currentTimeMillis());
            ExtraTimeRequests.Request expired = changed.requests.stream()
                    .filter(value->value.id.equals(request.id)).findFirst().orElseThrow();
            assertEquals(ExtraTimeRequests.Status.EXPIRED,expired.status);
            assertEquals(100,expired.tributeCents);
            assertEquals(0,ExtraTimeRequests.allowance(context,fixture.firstPackage,System.currentTimeMillis()).bonusMillis);
            ExtraTimeRequests.App changedApp = changed.apps.stream()
                    .filter(app -> app.packageName.equals(fixture.firstPackage)).findFirst().orElseThrow();
            assertEquals(6, changedApp.dailyMinutes);
            assertEquals(0, changedApp.bonusMinutes);
            assertSame(expired, changedApp.latest);
            fixture.transfer(); fixture.switchDom();
            assertEquals(ExtraTimeRequests.Status.EXPIRED,fixture.domRequests().snapshot(System.currentTimeMillis()).requests.stream()
                    .filter(value->value.id.equals(request.id)).findFirst().orElseThrow().status);
            fixture.switchSub();
            assertTrue(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                    .putBoolean(AppTimerManager.KEY_PER_APP_ENABLED, false)
                    .putBoolean(AppTimerManager.KEY_TOTAL_ENABLED, true)
                    .putInt(AppTimerManager.KEY_TOTAL_MINUTES, 15).commit());
            ExtraTimeRequests.Snapshot shared = sub.snapshot(System.currentTimeMillis());
            assertTrue(shared.canRequest);
            for (ExtraTimeRequests.App app : shared.apps) {
                assertEquals(0, app.dailyMinutes);
                assertTrue(app.exhausted);
                assertTrue(app.combinedLimitReached);
                assertEquals(0L, app.remainingMillis);
            }
            ExtraTimeRequests.Request sharedRequest = sub.submit(fixture.firstPackage, 100, "EUR", shared.scope);
            fixture.transfer(); fixture.switchDom(); fixture.domRequests().approve(sharedRequest.id, 25);
            fixture.transfer(); fixture.switchSub();

            long sharedAt = System.currentTimeMillis();
            ExtraTimeRequests.Snapshot credited = sub.snapshot(sharedAt);
            ExtraTimeRequests.App creditedApp = credited.apps.stream()
                    .filter(app -> app.packageName.equals(fixture.firstPackage)).findFirst().orElseThrow();
            ExtraTimeRequests.App uncreditedApp = credited.apps.stream()
                    .filter(app -> app.packageName.equals(fixture.secondPackage)).findFirst().orElseThrow();
            assertEquals(15 * 60_000L, timers.snapshot("", sharedAt).totalUsedMillis);
            assertEquals(0, creditedApp.dailyMinutes);
            assertEquals(25, creditedApp.bonusMinutes);
            assertEquals(25 * 60_000L, creditedApp.remainingMillis);
            assertEquals(30 * 60_000L, creditedApp.budgetMillis);
            assertFalse(creditedApp.exhausted);
            assertTrue(creditedApp.combinedLimitReached);
            assertEquals(0, uncreditedApp.bonusMinutes);
            assertTrue(uncreditedApp.exhausted);
            assertTrue(uncreditedApp.combinedLimitReached);
            assertEquals(AppTimerManager.LimitStatus.NONE, timers.limitStatus(fixture.firstPackage, selected, sharedAt));
            assertEquals(AppTimerManager.LimitStatus.COMBINED, timers.limitStatus(fixture.secondPackage, selected, sharedAt));
            assertEquals(sharedRequest.id, creditedApp.latest.id);
            assertEquals(2, new PenanceManager(context).snapshot(sharedAt).getEvents().size());

            assertTrue(SubFeaturePolicy.setAllowed(context, "limits", false));
            ExtraTimeRequests.Snapshot withdrawn = sub.snapshot(System.currentTimeMillis());
            assertFalse(withdrawn.canRequest);
            ExtraTimeRequests.App withdrawnApp = withdrawn.apps.stream()
                    .filter(app -> app.packageName.equals(fixture.firstPackage)).findFirst().orElseThrow();
            assertEquals(0, withdrawnApp.bonusMinutes);
            assertEquals(0L, withdrawnApp.budgetMillis);
            assertFalse(withdrawnApp.exhausted);
            assertFalse(withdrawnApp.combinedLimitReached);
            assertEquals(ExtraTimeRequests.Status.EXPIRED, withdrawnApp.latest.status);
            assertThrows(SecurityException.class, () -> sub.submit(fixture.secondPackage, 100, "EUR", shared.scope));
            assertEquals(2, new PenanceManager(context).snapshot(System.currentTimeMillis()).getEvents().size());
            RemoteStore.Peer peer = RemoteFeature.get(context).store.peers(false).stream()
                    .filter(value->value.localRole==RemoteProtocol.Role.SUB).findFirst().orElseThrow();
            assertThrows(SecurityException.class,()->RemoteFeature.get(context).store.revoke(peer.identity,peer.generation));
            assertEquals(RemoteStore.State.ACTIVE,RemoteFeature.get(context).store.peer(peer.identity,peer.generation).state);
        }
    }
    @Test public void declineDoesNotChargeAndChangedQuoteOrConfigurationCannotBeApplied() throws Exception {
        try (RemoteExtraTimeUiFixture fixture = RemoteExtraTimeUiFixture.setupSub(context)) {
            ExtraTimeRequests sub = fixture.subRequests();
            assertThrows(IllegalArgumentException.class,()->sub.submit(fixture.firstPackage,99,"EUR"));
            assertTrue(new PenanceManager(context).snapshot(System.currentTimeMillis()).getEvents().isEmpty());
            ExtraTimeRequests.Request first = sub.submit(fixture.firstPackage,100,"EUR");
            RemoteStore authority = RemoteFeature.get(context).store;
            RemoteStore.Peer subPeer = authority.peers(false).stream().filter(value->value.localRole==RemoteProtocol.Role.SUB).findFirst().orElseThrow();
            ExtraTimeStore journal = new ExtraTimeStore(context);
            String scope = subPeer.identity+"_"+subPeer.generation;
            List<ExtraTimeStore.Row> history = journal.load(scope);
            ExtraTimeStore.Row original = ExtraTimeStore.find(history,first.id);
            // Simulate an unpublished submission that expired before its tribute was assessed.
            JSONObject expired = original.state("SUBMITTING",0,0).unsubmittedExpiry(0).json(true).put("id","unpublished_expiry");
            history.add(ExtraTimeStore.Row.parse(expired,true)); journal.save(scope,history);
            fixture.transfer(); fixture.switchDom(); fixture.domRequests().decline(first.id); fixture.transfer(); fixture.switchSub();
            assertFalse(fixture.subRequests().snapshot(System.currentTimeMillis()).requests.stream()
                    .filter(value->value.id.equals("unpublished_expiry")).findFirst().orElseThrow().tributeCents>0);
            assertEquals(1,new PenanceManager(context).snapshot(System.currentTimeMillis()).getEvents().size());
            ExtraTimeRequests.Request second = fixture.subRequests().submit(fixture.firstPackage,100,"EUR");
            assertNotEquals(first.id,second.id); assertEquals(2,new PenanceManager(context).snapshot(System.currentTimeMillis()).getEvents().size());
            fixture.transfer(); fixture.switchDom(); fixture.domRequests().approve(second.id,15);
            assertTrue(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit()
                    .putInt("app_timer_allowance_minutes:"+fixture.firstPackage,6).commit());
            fixture.transfer(); fixture.transfer(); fixture.switchSub();
            ExtraTimeRequests.Request outcome = fixture.subRequests().snapshot(System.currentTimeMillis()).requests.stream()
                    .filter(value->value.id.equals(second.id)).findFirst().orElseThrow();
            assertEquals(ExtraTimeRequests.Status.EXPIRED,outcome.status);
            assertEquals(0,ExtraTimeRequests.allowance(context,fixture.firstPackage,System.currentTimeMillis()).bonusMillis);
        }
    }
    @Test public void foreignDecisionAndReplayCannotManufactureTime() throws Exception {
        try (RemoteExtraTimeUiFixture fixture = RemoteExtraTimeUiFixture.setupSub(context)) {
            ExtraTimeRequests.Request request = fixture.subRequests().submit(fixture.firstPackage,100,"EUR");
            RemoteStore store = RemoteFeature.get(context).store;
            RemoteStore.Peer peer = store.peers(false).stream().filter(value->value.localRole==RemoteProtocol.Role.SUB).findFirst().orElseThrow();
            ExtraTimeStore journal = new ExtraTimeStore(context);
            ExtraTimeStore.Row row = ExtraTimeStore.find(journal.load(peer.identity+"_"+peer.generation),request.id);
            JSONObject foreign = row.state("APPROVED",15,0).json(false).put("id","unknown_request");
            ExtraTimePeer.Endpoint endpoint = ExtraTimeRequests.endpoint(context,store,"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",peer);
            assertThrows(SecurityException.class,()->endpoint.receive(new JSONObject().put("schema",1).put("rows",new JSONArray().put(foreign))));
            assertEquals(0,ExtraTimeRequests.allowance(context,fixture.firstPackage,System.currentTimeMillis()).bonusMillis);
            fixture.transfer(); fixture.switchDom(); fixture.domRequests().approve(request.id,15); fixture.transfer(); fixture.switchSub();
            long firstCeiling = ExtraTimeRequests.allowance(context,fixture.firstPackage,System.currentTimeMillis()).appCeilingMillis;
            assertTrue(context.getSharedPreferences("subhub_app_timer_usage",0).edit()
                    .putLong("package_used_ms:"+fixture.firstPackage,firstCeiling).commit());
            fixture.transfer(); fixture.transfer();
            assertEquals(firstCeiling,ExtraTimeRequests.allowance(context,fixture.firstPackage,System.currentTimeMillis()).appCeilingMillis);
            assertTrue(fixture.subRequests().snapshot(System.currentTimeMillis()).apps.stream()
                    .filter(value->value.packageName.equals(fixture.firstPackage)).findFirst().orElseThrow().exhausted);
        }
    }
}
