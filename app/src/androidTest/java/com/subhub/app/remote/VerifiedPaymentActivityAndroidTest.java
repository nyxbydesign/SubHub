package com.subhub.app.remote;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.Cursor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.activity.ActivityEvent;
import com.subhub.app.people.PersonId;
import com.subhub.app.penance.*;
import com.subhub.app.stats.DailyStatsStore;
import com.subhub.app.storage.StoredPayloads;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;

/** Real receipt parsing/ledger/outbox/source persistence with synthetic responses; never opens a payment client. */
@RunWith(AndroidJUnit4.class)
public final class VerifiedPaymentActivityAndroidTest {
    private static final String DOM = repeat("a"), SUB = repeat("b"), OTHER = repeat("c");
    private static final String OUTBOX = "payment_activity_outbox_v1", AUDIENCE = "payment_activity_audience_v1";
    private Context target, context, domContext;
    private String prefix, generation;
    private final Set<String> preferences = Collections.synchronizedSet(new HashSet<>());
    private RemoteStore sub, dom;
    private RemoteActivityRepository sending, receiving;
    private PenanceManager wallet;
    private RemoteSessionNative nativeSession;
    private Field dailyField, recorderField;
    private DailyStatsStore previousDaily, daily;
    private Object previousRecorder;
    private final AtomicBoolean rejectCompletion = new AtomicBoolean(), rejectBeforeCompletion = new AtomicBoolean(), rejectAcknowledgement = new AtomicBoolean(), rejectOutbox = new AtomicBoolean(), rejectAudience = new AtomicBoolean();
    @Before public void prepare() throws Exception {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertFalse("Owned guest must have no live capture", com.subhub.app.service.ScreenCaptureService.isRunning());
        assertFalse("Owned guest must have no live recognition", com.subhub.app.service.ScreenshotAccessibilityService.isRunning());
        assertFalse("Do not disturb a configured automatic payment owner", new HardcoreAutoPayManager(target).isEnabled());
        prefix = "verified-payment-fixture-" + UUID.randomUUID() + "-";
        context = scoped("sub", true); domContext = scoped("dom", false);
        DailyStatsStore.awaitWrites();
        dailyField = DailyStatsStore.class.getDeclaredField("instance"); dailyField.setAccessible(true);
        previousDaily = (DailyStatsStore) dailyField.get(null);
        Constructor<DailyStatsStore> constructor = DailyStatsStore.class.getDeclaredConstructor(Context.class, String.class);
        constructor.setAccessible(true); daily = constructor.newInstance(context, prefix + "daily.db"); dailyField.set(null, daily);
        sub = new RemoteStore(context, prefix + "sub.db"); dom = new RemoteStore(domContext, prefix + "dom.db");
        PairingInvite invite = PairingInvite.create(DOM, "ticket", 1000); generation = invite.id;
        dom.saveInvite(invite); dom.bindInvite(generation, SUB, invite.tokenHash(), 1001);
        dom.approveInvite(generation, SUB, "Synthetic Sub", 1001); dom.activate(SUB, generation);
        sub.acceptInvite(invite, "Original Dom"); sub.activate(DOM, generation);
        sending = new RemoteActivityRepository(context, sub, () -> {}, NO_NOTIFICATIONS);
        receiving = new RemoteActivityRepository(domContext, dom, () -> {}, NO_NOTIFICATIONS);
        recorderField = RemoteActivityRecorder.class.getDeclaredField("installed"); recorderField.setAccessible(true);
        previousRecorder = recorderField.get(null); RemoteActivityRecorder.install(sending);
        dom.onActivityReceived(receiving::received); drain(sending); drain(receiving);
        nativeSession = RemoteSessionNative.installFixture(context, () -> com.subhub.app.stats.RemoteSessionStatsFixture.create(context));
        wallet = new PenanceManager(context);
        domContext.getSharedPreferences(PenanceManager.PREFS_NAME, 0).edit().putLong("total_paid_cents_v1", 77).commit();
    }
    private Context scoped(String role, boolean intercept) {
        return new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public Object getSystemService(String name) {
                return Context.NOTIFICATION_SERVICE.equals(name) ? null : super.getSystemService(name);
            }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                String owned = prefix + role + "-" + name; preferences.add(owned);
                SharedPreferences saved = target.getSharedPreferences(owned, mode);
                if (!intercept || !PenanceManager.PREFS_NAME.equals(name)) return saved;
                return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                        (proxy, method, args) -> {
                    if (method.getName().equals("edit")) return editor(saved.edit());
                    try { return method.invoke(saved, args); } catch (InvocationTargetException failed) { throw failed.getCause(); }
                });
            }
        };
    }
    private SharedPreferences.Editor editor(SharedPreferences.Editor saved) {
        AtomicBoolean financial = new AtomicBoolean(), removingOutbox = new AtomicBoolean();
        return (SharedPreferences.Editor) Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
            if (method.getName().equals("putLong") && "total_paid_cents_v1".equals(args[0])) financial.set(true);
            if (method.getName().equals("putString") && OUTBOX.equals(args[0])) {
                if (rejectOutbox.get()) throw new IllegalStateException("Synthetic optional outbox refusal");
                removingOutbox.set("[]".equals(args[1]));
            }
            if (method.getName().equals("putString") && AUDIENCE.equals(args[0]) && rejectAudience.get())
                throw new IllegalStateException("Synthetic audience bind refusal");
            if (method.getName().equals("commit") && financial.get() && rejectBeforeCompletion.getAndSet(false)) return false;
            if (method.getName().equals("commit") && removingOutbox.get() && !financial.get() && rejectAcknowledgement.get()) return false;
            Object value;
            try { value = method.invoke(saved, args); } catch (InvocationTargetException failed) { throw failed.getCause(); }
            if (method.getName().equals("commit") && financial.get() && rejectCompletion.getAndSet(false)) return false;
            return value == saved ? proxy : value;
        });
    }
    @After public void clean() throws Throwable {
        rejectAcknowledgement.set(false); rejectCompletion.set(false); rejectBeforeCompletion.set(false); rejectOutbox.set(false); rejectAudience.set(false);
        Throwable failure = null;
        failure = cleanupStep(failure, () -> { if (sending != null) sending.close(); });
        failure = cleanupStep(failure, () -> { if (receiving != null) receiving.close(); });
        failure = cleanupStep(failure, () -> { if (context != null) com.subhub.app.commitment.CommitmentManager.emergencyRelease(context); });
        failure = cleanupStep(failure, () -> { if (context != null) new PaidPauseManager(context).finish(); });
        failure = cleanupStep(failure, () -> { if (context != null) new com.subhub.app.appmode.AppModeManager(context).setArmed(false); });
        failure = cleanupStep(failure, () -> com.subhub.app.appmode.SessionExpiryAlarms.whenIdle().get(5, TimeUnit.SECONDS));
        failure = cleanupStep(failure, () -> InstrumentationRegistry.getInstrumentation().waitForIdleSync());
        failure = cleanupStep(failure, () -> { if (nativeSession != null) nativeSession.close(); });
        failure = cleanupStep(failure, () -> { if (recorderField != null) recorderField.set(null, previousRecorder); });
        failure = cleanupStep(failure, DailyStatsStore::awaitWrites);
        failure = cleanupStep(failure, () -> { if (dailyField != null) dailyField.set(null, previousDaily); });
        failure = cleanupStep(failure, () -> { if (sub != null) sub.close(); });
        failure = cleanupStep(failure, () -> { if (dom != null) dom.close(); });
        failure = cleanupStep(failure, () -> { if (daily != null) daily.close(); });
        if (target != null && prefix != null) {
            for (String suffix : new String[]{"sub.db", "dom.db", "daily.db"})
                failure = cleanupStep(failure, () -> target.deleteDatabase(prefix + suffix));
            for (String name : preferences) failure = cleanupStep(failure, () -> target.deleteSharedPreferences(name));
        }
        if (failure != null) throw failure;
    }
    private interface CleanupStep { void run() throws Exception; }
    private static Throwable cleanupStep(Throwable first, CleanupStep step) {
        try { step.run(); } catch (Throwable failure) {
            if (first == null) return failure;
            if (first != failure) first.addSuppressed(failure);
        }
        return first;
    }
    private SharedPreferences penance() { return context.getSharedPreferences(PenanceManager.PREFS_NAME, 0); }
    private PenanceManager.Settlement checkout() {
        long now = System.currentTimeMillis();
        assertTrue(penance().edit().putString("events_v1", UUID.randomUUID() + "," + now + "," + now + ",100,1,OPEN,,NEW_DETECTION,EUR").commit());
        PenanceManager.Settlement selected = wallet.beginSettlement(now); assertNotNull(selected); return selected;
    }
    private static PayPalOrdersClient.Capture capture(String settlement, int amount, String currency, String id) throws Exception {
        JSONObject amountDoc = new JSONObject().put("currency_code", currency).put("value", java.math.BigDecimal.valueOf(amount, 2).toPlainString());
        JSONObject captured = new JSONObject().put("id", id).put("status", "COMPLETED").put("amount", amountDoc);
        JSONObject payments = new JSONObject().put("captures", new JSONArray().put(captured));
        JSONObject unit = new JSONObject().put("custom_id", settlement).put("payments", payments);
        JSONObject response = new JSONObject().put("status", "COMPLETED")
                .put("purchase_units", new JSONArray().put(unit));
        Method parse = PayPalOrdersClient.class.getDeclaredMethod("parseCapture", JSONObject.class, String.class, int.class, String.class);
        parse.setAccessible(true);
        try { return (PayPalOrdersClient.Capture) parse.invoke(null, response, settlement, amount, currency); }
        catch (InvocationTargetException failed) { if (failed.getCause() instanceof Exception) throw (Exception) failed.getCause(); throw failed; }
    }
    @Test public void onlyVerifiedCaptureCreatesPaidFactAndDomWalletRemainsUnchanged() throws Exception {
        PenanceManager.Settlement selected = checkout();
        RemoteActivityRecorder.tribute(selected.getEvents().get(0).getId()); drain(sending);
        assertEquals(1, facts(ActivityEvent.Kind.TRIBUTE_TRIGGERED).size()); assertTrue(facts(ActivityEvent.Kind.TRIBUTE_PAID).isEmpty());
        assertFalse(wallet.completeVerifiedSettlement(selected.getId(), 100, null));
        assertTrue(wallet.completeVerifiedSettlement(selected.getId(), 100, capture(selected.getId(), 100, "EUR", "CAPTURE-CONFIRMED")));
        drain(sending);
        List<JSONObject> paid = facts(ActivityEvent.Kind.TRIBUTE_PAID); assertEquals(1, paid.size());
        String wire = paid.get(0).toString();
        assertFalse(wire.contains("CAPTURE-CONFIRMED")); assertFalse(wire.contains("settlement")); assertFalse(wire.contains("payer"));
        assertEquals(100, wallet.getTotalPaidCents());
        RemoteProtocol.Batch batch = sub.nextBatch(DOM, generation, RemoteProtocol.Stream.EVENTS);
        assertFalse(batch.changes.isEmpty()); dom.receive(SUB, batch); sub.acknowledge(DOM, new RemoteProtocol.Ack(batch));
        assertEquals(77, new PenanceManager(domContext).getTotalPaidCents());
        assertEquals(0, new JSONArray(penance().getString(OUTBOX, "[]")).length());
    }
    @Test public void manualLinkPaidLedgerNeverBecomesProviderPaidActivity() throws Exception {
        PenanceManager.Settlement selected = checkout();
        assertTrue(wallet.completeSettlement(selected.getId(), 100)); drain(sending);
        assertEquals(100, wallet.getTotalPaidCents()); assertTrue(facts(ActivityEvent.Kind.TRIBUTE_PAID).isEmpty());
        assertFalse(wallet.hasPaymentActivity());
    }
    @Test public void captureCannotCreditWrongSettlementAmountCurrencyOrMissingIdentity() throws Exception {
        PenanceManager.Settlement selected = checkout();
        PayPalOrdersClient.Capture proof = capture(selected.getId(), 100, "EUR", "CAPTURE-CORRECT");
        assertFalse(wallet.completeVerifiedSettlement("other-settlement", 100, proof));
        assertFalse(wallet.completeVerifiedSettlement(selected.getId(), 99, proof));
        assertFalse(wallet.completeVerifiedSettlement(selected.getId(), 100, capture(selected.getId(), 100, "USD", "CAPTURE-USD")));
        assertThrows(IllegalStateException.class, () -> capture(selected.getId(), 100, "EUR", ""));
        assertEquals(0, wallet.getTotalPaidCents()); assertEquals(100, wallet.snapshot(System.currentTimeMillis()).getCheckoutCents());
        assertFalse(wallet.hasPaymentActivity());
    }
    @Test public void originalCheckoutAudienceSurvivesRetryAndNeverLeaksToReplacementDom() throws Exception {
        PenanceManager.Settlement selected = checkout();
        String original = penance().getString(AUDIENCE, "");
        sub.revoke(DOM, generation);
        PairingInvite replacement = PairingInvite.create(OTHER, "replacement", 2000);
        sub.acceptInvite(replacement, "Replacement Dom"); sub.activate(OTHER, replacement.id); sending.refreshSource();
        assertEquals(selected.getId(), wallet.beginSettlement(System.currentTimeMillis()).getId());
        assertEquals(original, penance().getString(AUDIENCE, ""));
        assertTrue(wallet.completeVerifiedSettlement(selected.getId(), 100, capture(selected.getId(), 100, "EUR", "CAPTURE-OLD-AUDIENCE")));
        drain(sending);
        assertTrue(sub.nextBatch(OTHER, replacement.id, RemoteProtocol.Stream.EVENTS).changes.isEmpty());
        try (Cursor row = sub.getReadableDatabase().rawQuery("SELECT peer,generation,published FROM activity_source WHERE source_key LIKE 'paid:%'", null)) {
            assertTrue(row.moveToFirst()); assertEquals(DOM, row.getString(0)); assertEquals(generation, row.getString(1)); assertEquals(1, row.getInt(2));
        }
    }
    @Test public void persistedOutboxReplaysAfterProjectionAckFailureWithoutGrowingFactsOrChangingTime() throws Exception {
        PenanceManager.Settlement selected = checkout();
        PayPalOrdersClient.Capture proof = capture(selected.getId(), 100, "EUR", "CAPTURE-REPLAY");
        rejectAcknowledgement.set(true);
        assertTrue(wallet.completeVerifiedSettlement(selected.getId(), 100, proof)); drain(sending);
        assertEquals(1, wallet.durablePaymentActivity().size());
        long observed = facts(ActivityEvent.Kind.TRIBUTE_PAID).get(0).getLong("observedAt");
        sending.close(); rejectAcknowledgement.set(false);
        sending = new RemoteActivityRepository(context, sub, () -> {}, NO_NOTIFICATIONS); RemoteActivityRecorder.install(sending); drain(sending);
        assertFalse(wallet.hasPaymentActivity()); assertEquals(1, facts(ActivityEvent.Kind.TRIBUTE_PAID).size());
        assertEquals(observed, facts(ActivityEvent.Kind.TRIBUTE_PAID).get(0).getLong("observedAt"));
        assertTrue(wallet.completeVerifiedSettlement(selected.getId(), 100, proof)); drain(sending);
        assertEquals(1, facts(ActivityEvent.Kind.TRIBUTE_PAID).size()); assertEquals(100, wallet.getTotalPaidCents());
    }
    @Test public void optionalCapacityAndOutboxFailureNeverBlockSuccessfulCredit() throws Exception {
        PenanceManager.Settlement selected = checkout(); RemoteActivityRecorder.install(null);
        JSONArray full = new JSONArray();
        for (int index = 0; index < 128; index++) full.put(new JSONObject().put("id", "paid_" + RemoteProtocol.sha256(("queued" + index).getBytes(StandardCharsets.UTF_8)))
                .put("settlement", "queued_" + index).put("time", System.currentTimeMillis()).put("amount", 1).put("currency", "EUR").put("peer", "").put("generation", ""));
        assertTrue(penance().edit().putString(OUTBOX, full.toString()).commit());
        assertTrue(wallet.completeVerifiedSettlement(selected.getId(), 100, capture(selected.getId(), 100, "EUR", "CAPTURE-CAPACITY")));
        assertEquals(100, wallet.getTotalPaidCents()); assertEquals(128, wallet.durablePaymentActivity().size()); assertTrue(wallet.paymentActivityGap());
        assertTrue(penance().edit().putString(OUTBOX, "[]").commit());
        PenanceManager.Settlement next = checkout(); rejectOutbox.set(true);
        assertTrue(wallet.completeVerifiedSettlement(next.getId(), 100, capture(next.getId(), 100, "EUR", "CAPTURE-OUTBOX-FAILURE")));
        rejectOutbox.set(false); assertEquals(200, wallet.getTotalPaidCents());
    }
    @Test public void refusedCompletionRequiresDurableRetryAndFinishesOrderCleanupWithoutRecredit() throws Exception {
        PenanceManager.Settlement selected = checkout(); RemoteActivityRecorder.install(null);
        wallet.bindOrder(selected.getId(), "synthetic-order", "https://example.invalid/approval", "boundary");
        PayPalOrdersClient.Capture proof = capture(selected.getId(), 100, "EUR", "CAPTURE-UNCERTAIN-COMMIT");
        rejectCompletion.set(true);
        assertFalse(wallet.completeVerifiedSettlement(selected.getId(), 100, proof));
        assertEquals("synthetic-order", wallet.getActiveOrderId());
        assertTrue(wallet.completeVerifiedSettlement(selected.getId(), 100, proof));
        assertEquals("", wallet.getActiveOrderId()); assertEquals(100, wallet.getTotalPaidCents());
        assertEquals(1, wallet.durablePaymentActivity().size());
    }
    @Test public void staleVerifiedRetryCannotClearNewCheckoutOrReuseItsAudience() throws Exception {
        PenanceManager.Settlement first = checkout(); RemoteActivityRecorder.install(null);
        PayPalOrdersClient.Capture proof = capture(first.getId(), 100, "EUR", "CAPTURE-FIRST");
        assertTrue(wallet.completeVerifiedSettlement(first.getId(), 100, proof));
        // Preserve paid history while adding the next open occurrence.
        long now = System.currentTimeMillis(); String events = penance().getString("events_v1", "");
        assertTrue(penance().edit().putString("events_v1", events + ";" + UUID.randomUUID() + "," + now + "," + now + ",100,1,OPEN,,NEW_DETECTION,EUR").commit());
        PenanceManager.Settlement second = wallet.beginSettlement(now); assertNotNull(second);
        wallet.bindOrder(second.getId(), "newer-order", "https://example.invalid/new", "boundary");
        assertTrue(wallet.completeVerifiedSettlement(first.getId(), 100, proof));
        assertEquals("newer-order", wallet.getActiveOrderId()); assertEquals(second.getId(), wallet.getActiveSettlementId());
        assertEquals(100, wallet.getTotalPaidCents());
    }
    @Test public void failedAudienceBindClearsOldScopeAndWakeFailureCannotUndoCompletion() throws Exception {
        assertTrue(penance().edit().putString(AUDIENCE, new JSONObject().put("settlement", "old").put("peer", OTHER).put("generation", "old-generation").toString()).commit());
        rejectAudience.set(true); PenanceManager.Settlement selected = checkout(); rejectAudience.set(false);
        assertFalse(penance().contains(AUDIENCE));
        RemoteActivityRecorder.install(new RemoteActivityRecorder.Sink() {
            public void foreground(String before, String app, boolean tracked, long at) { }
            public void tribute(String id) { }
            public void limit(String app, boolean perApp, int minutes, Set<String> selected, long at) { }
            public void payments() { throw new IllegalStateException("Synthetic optional wake failure"); }
        });
        assertTrue(wallet.completeVerifiedSettlement(selected.getId(), 100, capture(selected.getId(), 100, "EUR", "CAPTURE-WAKE-FAILURE")));
        assertEquals(100, wallet.getTotalPaidCents()); assertEquals("", wallet.durablePaymentActivity().get(0).peer);
    }
    @Test public void paidPauseAndCreditShareOneDiskCommitWhenPreparationIsNotAcknowledged() throws Exception {
        PenanceManager.Settlement selected = pauseCheckout(); RemoteActivityRecorder.install(null);
        PayPalOrdersClient.Capture proof = capture(selected.getId(), 500, "EUR", "CAPTURE-PAUSE-BEFORE-WRITE");
        rejectBeforeCompletion.set(true);
        assertFalse(wallet.completeVerifiedSettlement(selected.getId(), 500, proof));
        Map<String, Object> disk = diskPenance();
        assertTrue(((String) disk.get("events_v1")).contains(",CHECKOUT,"));
        assertFalse(disk.containsKey("paid_pause_session_identity"));
        assertFalse(disk.containsKey("paid_pause_active_until"));
        com.subhub.app.settings.SharedPreferenceTestRestore.restore(penance(), disk);
        wallet = new PenanceManager(context);
        assertTrue(wallet.completeVerifiedSettlement(selected.getId(), 500, proof));
        assertEquals(500, wallet.getTotalPaidCents());
        assertEquals(selected.getId(), penance().getString("paid_pause_settlement_v1", ""));
        assertFalse(new com.subhub.app.appmode.AppModeManager(context).isArmed());
        assertTrue(wallet.durablePaymentActivity().isEmpty());
    }
    @Test public void coldPaidPauseReplayKeepsOriginalGrantAndNeverRestartsAnExpiredPause() throws Exception {
        PenanceManager.Settlement selected = pauseCheckout(); RemoteActivityRecorder.install(null);
        PayPalOrdersClient.Capture proof = capture(selected.getId(), 500, "EUR", "CAPTURE-PAUSE-LOST-ACK");
        rejectCompletion.set(true);
        assertFalse(wallet.completeVerifiedSettlement(selected.getId(), 500, proof));
        Map<String, Object> disk = diskPenance();
        assertTrue(((String) disk.get("events_v1")).contains(",PAID,"));
        assertEquals(500L, disk.get("total_paid_cents_v1"));
        String identity = (String) disk.get("paid_pause_session_identity");
        long until = (Long) disk.get("paid_pause_active_until");
        assertNotNull(identity); assertEquals(selected.getId(), disk.get("paid_pause_settlement_v1"));
        com.subhub.app.settings.SharedPreferenceTestRestore.restore(penance(), disk);
        wallet = new PenanceManager(context);
        assertTrue(wallet.completeVerifiedSettlement(selected.getId(), 500, proof));
        assertEquals(identity, penance().getString("paid_pause_session_identity", ""));
        assertEquals(until, penance().getLong("paid_pause_active_until", 0));
        assertEquals(500, wallet.getTotalPaidCents());
        assertTrue(penance().edit().putLong("paid_pause_active_until", System.currentTimeMillis() - 1).commit());
        assertTrue(new PaidPauseManager(context).finishIfDue(identity));
        assertTrue(wallet.completeVerifiedSettlement(selected.getId(), 500, proof));
        assertEquals(0, penance().getLong("paid_pause_active_until", 0));
        assertEquals("", penance().getString("paid_pause_session_identity", ""));
        assertEquals(500, wallet.getTotalPaidCents());
    }
    private PenanceManager.Settlement pauseCheckout() throws Exception {
        new com.subhub.app.settings.FeatureModuleManager(context).save(true, true, true);
        boolean wasDom = com.subhub.app.security.ControllerPinManager.isDomModeActive();
        try {
            com.subhub.app.security.ControllerPinManager.enterDomMode();
            assertTrue(com.subhub.app.commitment.CommitmentManager.start(context, 3600000));
        } finally { if (!wasDom) com.subhub.app.security.ControllerPinManager.enterSubMode(); }
        PaidPauseManager pause = new PaidPauseManager(context); pause.configure(true, 500, 15);
        assertTrue(wallet.requestPaidPause(System.currentTimeMillis()));
        PenanceManager.Settlement selected = wallet.beginPaidPauseSettlement(System.currentTimeMillis());
        assertNotNull(selected); assertTrue(penance().edit().commit()); return selected;
    }
    /** Reads the actual fixture XML, then tests reopen from that disk snapshot rather than trusting preference memory. */
    private Map<String, Object> diskPenance() throws Exception {
        java.io.File file = new java.io.File(target.getDataDir(), "shared_prefs/" + prefix + "sub-" + PenanceManager.PREFS_NAME + ".xml");
        Map<String, Object> result = new HashMap<>();
        try (java.io.InputStream input = new java.io.FileInputStream(file)) {
            org.xmlpull.v1.XmlPullParser xml = android.util.Xml.newPullParser(); xml.setInput(input, "UTF-8");
            for (int event = xml.getEventType(); event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT; event = xml.next()) {
                if (event != org.xmlpull.v1.XmlPullParser.START_TAG || "map".equals(xml.getName())) continue;
                String type = xml.getName(), key = xml.getAttributeValue(null, "name"), value = xml.getAttributeValue(null, "value");
                switch (type) {
                    case "string": result.put(key, xml.nextText()); break;
                    case "long": result.put(key, Long.parseLong(value)); break;
                    case "int": result.put(key, Integer.parseInt(value)); break;
                    case "boolean": result.put(key, Boolean.parseBoolean(value)); break;
                    case "float": result.put(key, Float.parseFloat(value)); break;
                    default: throw new AssertionError("Unsupported fixture preference type");
                }
            }
        }
        return result;
    }
    private List<JSONObject> facts(ActivityEvent.Kind kind) throws Exception {
        List<JSONObject> result = new ArrayList<>();
        try (Cursor rows = sub.getReadableDatabase().rawQuery("SELECT body FROM activity_source", null)) {
            while (rows.moveToNext()) {
                JSONObject body = new JSONObject(StoredPayloads.read(rows, 0));
                if (RemoteActivityWire.decode(body).kind == kind) result.add(body);
            }
        }
        return result;
    }
    private static void drain(RemoteActivityRepository repository) throws Exception {
        Field field = RemoteActivityRepository.class.getDeclaredField("worker"); field.setAccessible(true);
        ((ExecutorService) field.get(repository)).submit(() -> {}).get(10, TimeUnit.SECONDS);
    }
    private static String repeat(String text) { return String.join("", Collections.nCopies(64, text)); }
    private static final RemoteActivityRepository.Notifications NO_NOTIFICATIONS = new RemoteActivityRepository.Notifications() {
        public boolean enabled(Context context) { return false; }
        public boolean show(Context context, com.subhub.app.activity.ActivityEntry entry) { throw new AssertionError("No OS notice expected"); }
        public void cancel(Context context, com.subhub.app.activity.ActivityEntry entry) { }
        public void cancelPeer(Context context, PersonId person) { }
    };
}
