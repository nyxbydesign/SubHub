package com.subhub.app.remote;

import static org.junit.Assert.*;
import static com.subhub.app.remote.RemoteSessionCommands.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.security.SubFeaturePolicy;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.stats.RemoteSessionStatsFixture;
import com.subhub.app.stats.StatsRepository;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Two real direct Iroh endpoints with explicitly pre-negotiated version fixtures; no permission grants. */
public final class RemoteSessionControlPeerAndroidTest {
    private Context target, subContext;
    private String prefix, domDatabase, subDatabase, generation;
    private RemoteStore dom, sub;
    private PeerTransport dt, st;
    private StatsRepository stats;
    private RemoteSessionNative nativeSession;
    private RemoteSessionControlPeer domWire, subWire;
    private ExecutorService server;
    private SharedPreferences crashFixture;
    private boolean crashRecovery, crashRecovered;
    private boolean startsReady=true;
    private final List<String> transcript = Collections.synchronizedList(new ArrayList<>());

    @Before public void setup() throws Exception {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String storageId = InstrumentationRegistry.getArguments().getString("wire_storage_id", "");
        crashRecovery = "recover".equals(InstrumentationRegistry.getArguments().getString("wire_phase", ""));
        if (!storageId.isEmpty()) {
            assertTrue(storageId.matches("wirecrash_[a-f0-9]{32}")); prefix = "control-wire-" + storageId + "-";
            crashFixture = target.getSharedPreferences(prefix + "fixture", 0);
        } else prefix = "control-wire-" + UUID.randomUUID() + "-";
        subContext = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return target.getSharedPreferences(prefix + name, mode); }
        };
        domDatabase = prefix + "dom.db"; subDatabase = prefix + "sub.db";
        dom = new RemoteStore(target, domDatabase); sub = new RemoteStore(subContext, subDatabase);
        byte[] domSeed = seed(), subSeed = seed();
        if (crashFixture != null) {
            if (crashRecovery) {
                domSeed = android.util.Base64.decode(crashFixture.getString("domSeed", ""), android.util.Base64.NO_WRAP);
                subSeed = android.util.Base64.decode(crashFixture.getString("subSeed", ""), android.util.Base64.NO_WRAP);
                assertEquals(32, domSeed.length); assertEquals(32, subSeed.length);
            } else {
                assertTrue(crashFixture.getAll().isEmpty());
                assertTrue(crashFixture.edit().putString("domSeed", android.util.Base64.encodeToString(domSeed, android.util.Base64.NO_WRAP))
                        .putString("subSeed", android.util.Base64.encodeToString(subSeed, android.util.Base64.NO_WRAP)).commit());
            }
        }
        dt = IrohPeerTransport.open(target, domSeed, new String[0], 10000);
        st = IrohPeerTransport.open(target, subSeed, new String[0], 10000);
        if (crashRecovery) {
            generation = crashFixture.getString("generation", "");
            assertNotNull(dom.peer(st.localIdentity(), generation)); assertNotNull(sub.peer(dt.localIdentity(), generation));
        } else {
            PairingInvite invite = PairingInvite.create(dt.localIdentity(), dt.inviteRoute(), 1000); generation = invite.id;
            dom.saveInvite(invite); dom.bindInvite(invite.id, st.localIdentity(), invite.tokenHash(), 1001);
            sub.acceptInvite(invite, "Synthetic Dom"); dom.approveInvite(invite.id, st.localIdentity(), "Synthetic Sub", 1001);
            dom.activate(st.localIdentity(), generation); sub.activate(dt.localIdentity(), generation);
            dom.putMetadata(st.localIdentity(), generation, "session_control_version", "1");
            sub.putMetadata(dt.localIdentity(), generation, "session_control_version", "1");
            if (crashFixture != null) assertTrue(crashFixture.edit().putString("generation", generation).commit());
        }
        stats = RemoteSessionStatsFixture.create(subContext); nativeSession = RemoteSessionNative.installFixture(subContext, () -> stats);
        RemoteSessionClock.observe(subContext);
        domWire = new RemoteSessionControlPeer(target, dom, dt.localIdentity(), null);
        subWire = new RemoteSessionControlPeer(subContext, sub, st.localIdentity(), this::state);
        server = Executors.newSingleThreadExecutor();
    }
    private static byte[] seed() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return bytes; }
    @After public void cleanup() throws Exception {
        try {
            if (server != null) server.shutdownNow();
            if (dt != null) dt.close(); if (st != null) st.close();
            if (server != null) assertTrue(server.awaitTermination(10, TimeUnit.SECONDS));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        } finally {
            if (nativeSession != null) nativeSession.close();
            if (dom != null) dom.close(); if (sub != null) sub.close();
            boolean remove = crashFixture == null || crashRecovered;
            if (remove && domDatabase != null) target.deleteDatabase(domDatabase); if (remove && subDatabase != null) target.deleteDatabase(subDatabase);
            if (remove && prefix != null) for (String name : new String[]{SettingsRepository.PREFERENCES_NAME, StatsRepository.PREFS_NAME,
                    SubFeaturePolicy.STORE, RemoteConfiguration.DURATION_PREFS, com.subhub.app.penance.PenanceManager.PREFS_NAME,
                    "subhub_hardcore_auto_pay"}) target.getSharedPreferences(prefix + name, 0).edit().clear().commit();
            if (stats != null) stats.sessionStamp();
            if (crashRecovered) assertTrue(crashFixture.edit().clear().commit());
        }
    }
    private RemoteSessionControlState state(RemoteStore.Peer peer) {
        RemoteSessionClock.Sample now = RemoteSessionClock.observe(subContext);
        RuntimeSessionIdentity.Observed runtime = RuntimeSessionIdentity.observe(subContext, now);
        return new RemoteSessionControlState(st.localIdentity(), peer.identity, peer.generation, now.wall, 0,
                new Duration(-1, 3600000, 86400000, false), runtime.active, runtime.active ? "" : runtime.idleFence,
                runtime.identity, RemoteSessionRuntime.Readiness.UNAVAILABLE, Reason.HARDCORE_REQUIRED,
                false, runtime.active && !runtime.identity.isEmpty());
    }
    private Request queueStop() throws Exception {
        return queueStopFor(RuntimeSessionIdentity.observe(subContext, RemoteSessionClock.observe(subContext)).identity);
    }
    private Request queueStopFor(String identity) throws Exception {
        RemoteSessionClock.Sample now = RemoteSessionClock.observe(subContext);
        Request request = Request.stop(UUID.randomUUID().toString(), dt.localIdentity(), st.localIdentity(), generation,
                dom.allocateSessionSequence(st.localIdentity(), generation), 0, now.wall, now.wall + 120000,
                identity);
        dom.rememberSessionControl(st.localIdentity(), generation, RemoteSessionRuntime.Direction.OUTGOING, request, now); return request;
    }
    private RemoteSessionRuntime.Record outgoing(Request request) throws Exception {
        return dom.sessionControl(st.localIdentity(), generation, RemoteSessionRuntime.Direction.OUTGOING, request.id);
    }
    private static final class Outcome {
        Exception client, server;
        boolean directClient, directServer;
        void success() {
            if (client != null || server != null) {
                String clientDetail = client == null ? "none" : android.util.Log.getStackTraceString(client);
                String serverDetail = server == null ? "none" : android.util.Log.getStackTraceString(server);
                fail("Client: " + clientDetail + "\nServer: " + serverDetail);
            }
            assertTrue(directClient); assertTrue(directServer);
        }
    }
    private Outcome exchange(UnaryOperator<JSONObject> clientFilter, UnaryOperator<JSONObject> serverFilter) throws Exception {
        Outcome outcome = new Outcome(); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        Future<?> incoming = server.submit(() -> {
            try (PeerTransport.Connection connection = st.accept(10000)) {
                try { subWire.exchange(new Observe(connection, serverFilter), sub.peer(dt.localIdentity(), generation), deadline,startsReady); }
                catch (Exception failure) { outcome.server = failure; }
                outcome.directServer = connection.isDirect();
            } catch (Exception failure) { outcome.server = failure; }
        });
        try (PeerTransport.Connection connection = dt.connect(st.localIdentity(), st.inviteRoute(), 10000)) {
            try { domWire.exchange(new Observe(connection, clientFilter), dom.peer(st.localIdentity(), generation), deadline); }
            catch (Exception failure) { outcome.client = failure; }
            outcome.directClient = connection.isDirect();
            // The production caller continues RemoteSession on this stream. Keep this isolated fixture's
            // successful client alive until the receiver consumes Complete; close aborts unread QUIC bytes.
            if (outcome.client == null) incoming.get(25, TimeUnit.SECONDS);
        }
        incoming.get(25, TimeUnit.SECONDS); return outcome;
    }
    private void unlocked() {
        assertFalse(Thread.holdsLock(dom)); assertFalse(Thread.holdsLock(sub));
        assertFalse(Thread.holdsLock(SubFeaturePolicy.LOCK)); assertFalse(Thread.holdsLock(RuntimeSessionIdentity.LOCK));
    }
    private final class Observe implements PeerTransport.Connection {
        final PeerTransport.Connection wrapped;
        final OutputStream output;
        Observe(PeerTransport.Connection wrapped, UnaryOperator<JSONObject> filter) {
            this.wrapped = wrapped;
            output = new OutputStream() {
                final ByteArrayOutputStream pending = new ByteArrayOutputStream();
                @Override public void write(int value) { unlocked(); pending.write(value); }
                @Override public void write(byte[] bytes, int offset, int length) { unlocked(); pending.write(bytes, offset, length); }
                @Override public void flush() throws IOException {
                    unlocked(); JSONObject frame = RemoteProtocol.read(new ByteArrayInputStream(pending.toByteArray()));
                    if (filter != null) frame = filter.apply(frame);
                    if (frame == null) throw new IOException("Synthetic lost response");
                    transcript.add(RemoteProtocol.canonical(frame)); RemoteProtocol.write(wrapped.output(), frame); pending.reset();
                }
            };
        }
        public String authenticatedPeerIdentity() { unlocked(); return wrapped.authenticatedPeerIdentity(); }
        public InputStream input() throws IOException { unlocked(); return wrapped.input(); }
        public OutputStream output() { unlocked(); return output; }
        public void setReadTimeoutMillis(long value) { unlocked(); wrapped.setReadTimeoutMillis(value); }
        public boolean isDirect() { unlocked(); return wrapped.isDirect(); }
        public void close() throws IOException { wrapped.close(); }
    }

    @Test public void incompleteStartAssetsDoNotBlockAuthenticatedStop() throws Exception {
        startsReady=false;
        new AppModeManager(subContext).setArmed(true);Request request=queueStop();
        exchange(null,null).success();
        assertEquals(State.APPLIED,outgoing(request).result.state);
        assertFalse(outgoing(request).result.proof.active);
        assertEquals("",stats.activeSessionIdentity());assertEquals(1,stats.getSessionHistory().size());
    }

    @Test public void incompleteStartAssetsPreserveAcknowledgedReceiptThenApplyOriginalRequest() throws Exception {
        java.lang.reflect.Field binding=com.subhub.app.service.ScreenshotAccessibilityService.class.getDeclaredField("running");
        binding.setAccessible(true);boolean original=binding.getBoolean(null);
        assertFalse("A pre-existing Accessibility binding is not owned by this fixture",original);
        try {
            binding.setBoolean(null,true);
            configureReadyStart();Request request=queueStart();startsReady=false;
            exchange(null,null).success();
            RemoteSessionRuntime.Record first=sub.sessionControl(dt.localIdentity(),generation,RemoteSessionRuntime.Direction.INCOMING,request.id);
            assertEquals(State.ACKNOWLEDGED,first.result.state);assertEquals(State.ACKNOWLEDGED,outgoing(request).result.state);
            assertNull(nativeSession.marker());assertNull(sub.nativeSessionPreparation(dt.localIdentity(),generation,request.id));
            assertFalse(new AppModeManager(subContext).isArmed());assertEquals("",stats.activeSessionIdentity());
            exchange(null,null).success();
            RemoteSessionRuntime.Record deferred=sub.sessionControl(dt.localIdentity(),generation,RemoteSessionRuntime.Direction.INCOMING,request.id);
            assertEquals(RemoteProtocol.canonical(first.result.json()),RemoteProtocol.canonical(deferred.result.json()));
            assertEquals(first.lifetime.receivedWall,deferred.lifetime.receivedWall);
            assertEquals(first.lifetime.deadlineElapsed,deferred.lifetime.deadlineElapsed);
            startsReady=true;exchange(null,null).success();
            assertEquals(State.APPLIED,outgoing(request).result.state);assertTrue(outgoing(request).result.proof.active);
            assertEquals(request.digest(),outgoing(request).request.digest());assertEquals(request.expiresAt,outgoing(request).request.expiresAt);
            assertNotNull(nativeSession.marker());assertFalse(stats.activeSessionIdentity().isEmpty());
        } finally { binding.setBoolean(null,original); }
    }

    private void configureReadyStart() throws Exception {
        sub.putMetadata(dt.localIdentity(),generation,"applied_revision","7");
        subContext.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit()
                .putBoolean(com.subhub.app.security.HardcoreModeManager.KEY_REQUESTED,true)
                .putString(com.subhub.app.security.DeviceRolePreferences.KEY_MODE,com.subhub.app.security.DeviceRolePreferences.Mode.SUB_ONLY.name()).commit();
        RemoteSessionClock.Sample now=RemoteSessionClock.observe(subContext);
        subContext.getSharedPreferences(SubFeaturePolicy.STORE,0).edit().putBoolean("hardcore_locally_consented",true)
                .putString("hardcore_start_accepted_review","synthetic_wire_review")
                .putString("hardcore_start_proof_v1",RemoteProtocol.canonical(new RemoteSessionRuntime.HardcoreConsent(dt.localIdentity(),st.localIdentity(),generation,now.wall).json())).commit();
        synchronized(SubFeaturePolicy.LOCK) {
            sub.getWritableDatabase().beginTransaction();
            try { sub.acknowledgeHardcoreStartReview(dt.localIdentity(),generation,"synthetic_wire_review");sub.getWritableDatabase().setTransactionSuccessful(); }
            finally { sub.getWritableDatabase().endTransaction(); }
        }
        RemoteSessionReadiness.Source source=new RemoteSessionReadiness.Source() {
            public RemoteSessionClock.Sample clock(){return RemoteSessionClock.observe(subContext);}
            public int sdk(){return 35;}public boolean adminActive(){return true;}
            public boolean accessibilityEnabled(){return true;}public boolean accessibilityBound(){return true;}
            public com.subhub.app.settings.CaptureMethod captureMethod(){return com.subhub.app.settings.CaptureMethod.APP_MODE;}
        };
        subWire=new RemoteSessionControlPeer(subContext,sub,st.localIdentity(),this::state,
                new RemoteSessionNativeController(subContext,sub,st.localIdentity(),source));
    }
    private Request queueStart() throws Exception {
        RemoteSessionClock.Sample now=RemoteSessionClock.observe(subContext);
        Request request=Request.start(UUID.randomUUID().toString(),dt.localIdentity(),st.localIdentity(),generation,
                dom.allocateSessionSequence(st.localIdentity(),generation),7,now.wall,now.wall+120000,
                RuntimeSessionIdentity.observe(subContext,now).idleFence,new Duration(-1,3600000,86400000,false));
        dom.rememberSessionControl(st.localIdentity(),generation,RemoteSessionRuntime.Direction.OUTGOING,request,now);return request;
    }

    @Test public void directAuthenticatedStopPersistsNativeProofAndSecretFreeState() throws Exception {
        new AppModeManager(subContext).setArmed(true); String nativeId = stats.activeSessionIdentity(); Request request = queueStop();
        exchange(null, null).success();
        assertEquals(State.APPLIED, outgoing(request).result.state); assertFalse(outgoing(request).result.proof.active);
        assertEquals("", stats.activeSessionIdentity()); assertEquals(1, stats.getSessionHistory().size());
        RemoteSessionControlState.Received state = RemoteSessionControlState.Received.parse(new JSONObject(
                dom.metadata(st.localIdentity(), generation, RemoteSessionControlState.METADATA, "")));
        assertFalse(state.state.active); assertTrue(state.fresh(RemoteSessionClock.observe(target)));
        String wire = transcript.toString(); assertFalse(wire.contains(nativeId)); assertFalse(wire.contains("hardcore_start_proof"));
        assertFalse(wire.contains("authSecret")); assertTrue(RemoteSessionRuntime.applicationEnabled());
    }
    @Test public void lostFinalResultReplaysExactlyWithoutStoppingAReplacement() throws Exception {
        new AppModeManager(subContext).setArmed(true); Request request = queueStop();
        Outcome lost = exchange(null, frame -> "session_control_results".equals(frame.optString("type")) ? null : frame);
        assertNotNull(lost.client); assertTrue(lost.server instanceof IOException); assertEquals(State.ACKNOWLEDGED, outgoing(request).result.state);
        assertEquals(State.APPLIED, sub.sessionControl(dt.localIdentity(), generation, RemoteSessionRuntime.Direction.INCOMING, request.id).result.state);
        new AppModeManager(subContext).setArmed(true); String replacement = stats.activeSessionIdentity();
        exchange(null, null).success(); assertEquals(State.APPLIED, outgoing(request).result.state);
        assertEquals(replacement, stats.activeSessionIdentity()); assertTrue(new AppModeManager(subContext).isArmed());
        assertEquals(1, stats.getSessionHistory().size());
        RemoteSessionControlState.Received state = RemoteSessionControlState.Received.parse(new JSONObject(
                dom.metadata(st.localIdentity(), generation, RemoteSessionControlState.METADATA, "")));
        assertTrue(state.state.active); assertTrue(state.state.canStop);
    }
    @Test public void malformedLaterCommandCannotPartiallyAcknowledgeOrApplyTheBatch() throws Exception {
        new AppModeManager(subContext).setArmed(true); String original = stats.activeSessionIdentity(); Request request = queueStop();
        Outcome malformed = exchange(frame -> {
            if (!"session_control_commands".equals(frame.optString("type"))) return frame;
            try {
                org.json.JSONArray commands = frame.getJSONArray("commands");
                commands.put(Request.stop(UUID.randomUUID().toString(), request.sender, String.join("", Collections.nCopies(64, "f")),
                        generation, request.sequence + 1, 0, request.issuedAt, request.expiresAt, request.sessionIdentity).json());
                commandDigest(frame); return frame;
            } catch (Exception failure) { throw new AssertionError(failure); }
        }, null);
        assertNotNull(malformed.client); assertTrue(malformed.server instanceof SecurityException);
        assertNull(sub.sessionControl(dt.localIdentity(), generation, RemoteSessionRuntime.Direction.INCOMING, request.id));
        assertEquals(original, stats.activeSessionIdentity()); assertNull(nativeSession.stopMarker());
    }
    @Test public void malformedLaterResultCannotPartiallyAdvanceTheOutgoingBatch() throws Exception {
        new AppModeManager(subContext).setArmed(true); Request first = queueStop(), second = queueStop();
        Outcome malformed = exchange(null, frame -> {
            if (!"session_control_results".equals(frame.optString("type"))) return frame;
            try { frame.getJSONArray("results").getJSONObject(1).put("requestDigest", String.join("", Collections.nCopies(64, "f"))); return frame; }
            catch (Exception failure) { throw new AssertionError(failure); }
        });
        assertNotNull(malformed.client); assertEquals(State.ACKNOWLEDGED, outgoing(first).result.state);
        assertEquals(State.ACKNOWLEDGED, outgoing(second).result.state); assertEquals(1, stats.getSessionHistory().size());
        exchange(null, null).success(); assertEquals(State.APPLIED, outgoing(first).result.state);
        assertEquals(State.REJECTED, outgoing(second).result.state); assertEquals(1, stats.getSessionHistory().size());
    }
    private static void commandDigest(JSONObject frame) throws Exception {
        frame.put("batchDigest", RemoteProtocol.sha256(RemoteProtocol.canonical(new JSONObject()
                .put("commands", frame.getJSONArray("commands")).put("acks", frame.getJSONArray("acks"))
                .put("reconcile", frame.getJSONArray("reconcile")))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    private void ageAnchors(RemoteStore store, Request request) {
        // Simulated receiver-local lifetime only: original wire request/digest/deadline stay byte-identical; no OS clock writes.
        store.getWritableDatabase().execSQL("UPDATE session_control_journal SET received_wall=received_wall-?,received_elapsed=received_elapsed-?,deadline_elapsed=deadline_elapsed-? WHERE id=?",
                new Object[]{LIFETIME_MILLIS + 5000, LIFETIME_MILLIS + 5000, LIFETIME_MILLIS + 5000, request.id});
    }
    private void reopen() {
        dom.close(); sub.close(); dom = new RemoteStore(target, domDatabase); sub = new RemoteStore(subContext, subDatabase);
        domWire = new RemoteSessionControlPeer(target, dom, dt.localIdentity(), null);
        subWire = new RemoteSessionControlPeer(subContext, sub, st.localIdentity(), this::state);
    }
    private int incomingCount() {
        try (android.database.Cursor rows = sub.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM session_control_journal", null)) {
            assertTrue(rows.moveToFirst()); return rows.getInt(0);
        }
    }
    private String incomingFloor() { return sub.metadata(dt.localIdentity(), generation, "session_control_INCOMING_floor", "0"); }
    private String acked(Request request) {
        return sub.metadata(dt.localIdentity(), generation, "session_control_wire:INCOMING:" + request.id + ":acked", "");
    }
    @Test public void lostFinalPastAnchoredLifetimeAndLaterSequenceRetainsReceiverProofAcrossReopen() throws Exception {
        new AppModeManager(subContext).setArmed(true); Request original = queueStop(); String digest = original.digest();
        Outcome lost = exchange(null, frame -> "session_control_results".equals(frame.optString("type")) ? null : frame);
        assertNotNull(lost.client); assertEquals(State.ACKNOWLEDGED, outgoing(original).result.state);
        ageAnchors(dom, original); ageAnchors(sub, original); reopen();
        dom.putMetadata(st.localIdentity(), generation, "session_control_version", "0");
        sub.putMetadata(dt.localIdentity(), generation, "session_control_version", "0");
        dom.checkSessionControlClock(st.localIdentity(), generation, RemoteSessionRuntime.Direction.OUTGOING, original.id,
                RemoteSessionClock.observe(target));
        assertEquals(State.ACKNOWLEDGED, outgoing(original).result.state);
        assertEquals("EXPIRED", dom.metadata(st.localIdentity(), generation, "session_control_wire:OUTGOING:" + original.id + ":timeout", ""));
        reopen();
        Probe probe = new Probe(); assertThrows(SecurityException.class, () -> domWire.exchange(probe,
                dom.peer(st.localIdentity(), generation), System.nanoTime() + TimeUnit.SECONDS.toNanos(1)));
        dom.putMetadata(st.localIdentity(), generation, "session_control_version", "1");
        sub.putMetadata(dt.localIdentity(), generation, "session_control_version", "1");
        new AppModeManager(subContext).setArmed(true); String replacement = stats.activeSessionIdentity();
        Request later = queueStopFor(original.sessionIdentity);
        exchange(null, null).success();
        assertEquals(State.APPLIED, outgoing(original).result.state); assertEquals(State.REJECTED, outgoing(later).result.state);
        assertEquals(digest, outgoing(original).request.digest()); assertEquals(original.expiresAt, outgoing(original).request.expiresAt);
        assertEquals(replacement, stats.activeSessionIdentity()); assertEquals(1, stats.getSessionHistory().size());
        assertEquals(Long.toString(later.sequence), incomingFloor());
    }
    @Test public void lostReceiptPastAnchoredLifetimeIsReceiverExpiryNotLocalGuess() throws Exception {
        new AppModeManager(subContext).setArmed(true); String identity = stats.activeSessionIdentity(); Request original = queueStop();
        Outcome lost = exchange(null, frame -> "session_control_receipts".equals(frame.optString("type")) ? null : frame);
        assertNotNull(lost.client); assertEquals(State.QUEUED, outgoing(original).result.state);
        assertEquals(identity, stats.activeSessionIdentity()); ageAnchors(dom, original); ageAnchors(sub, original); reopen();
        Request later = queueStopFor(String.join("", Collections.nCopies(64, "f")));
        assertEquals(State.QUEUED, outgoing(original).result.state);
        exchange(null, null).success(); assertEquals(State.EXPIRED, outgoing(original).result.state);
        assertEquals(State.REJECTED, outgoing(later).result.state); assertEquals(identity, stats.activeSessionIdentity());
        assertTrue(stats.getSessionHistory().isEmpty()); assertNull(nativeSession.stopMarker());
    }
    @Test public void lostCompleteAndAckEchoRetainDurableOutboxAfterReceiverPruning() throws Exception {
        new AppModeManager(subContext).setArmed(true); Request original = queueStop();
        Outcome lost = exchange(frame -> "session_control_complete".equals(frame.optString("type")) ? null : frame, null);
        assertNotNull(lost.client); assertEquals(State.APPLIED, outgoing(original).result.state); assertEquals("", acked(original));
        assertEquals(1, dom.pendingSessionControlAcks(st.localIdentity(), generation).size());
        ageAnchors(dom, original); ageAnchors(sub, original); reopen();
        Outcome echoLost = exchange(null, frame -> "session_control_receipts".equals(frame.optString("type")) ? null : frame);
        assertNotNull(echoLost.client); assertFalse(acked(original).isEmpty());
        assertEquals(1, dom.pendingSessionControlAcks(st.localIdentity(), generation).size());
        // An incoming later receipt triggers normal age pruning; retry of the separate ACK is then a receipt-only no-op.
        Request later = queueStopFor(String.join("", Collections.nCopies(64, "f")));
        sub.rememberSessionControl(dt.localIdentity(), generation, RemoteSessionRuntime.Direction.INCOMING, later, RemoteSessionClock.observe(subContext));
        assertNull(sub.sessionControl(dt.localIdentity(), generation, RemoteSessionRuntime.Direction.INCOMING, original.id));
        reopen(); exchange(null, null).success();
        assertEquals(State.APPLIED, outgoing(original).result.state); assertEquals(1, stats.getSessionHistory().size());
        assertEquals(1, dom.pendingSessionControlAcks(st.localIdentity(), generation).size()); // The new result, never the retired result.
        assertEquals(later.id, dom.pendingSessionControlAcks(st.localIdentity(), generation).get(0).id);
        exchange(null, null).success(); assertTrue(dom.pendingSessionControlAcks(st.localIdentity(), generation).isEmpty());
    }
    @Test public void secondStoredConflictRollsBackEarlierReceiptAndFloor() throws Exception {
        new AppModeManager(subContext).setArmed(true); String originalRuntime = stats.activeSessionIdentity();
        dom.allocateSessionSequence(st.localIdentity(), generation); dom.allocateSessionSequence(st.localIdentity(), generation);
        Request first = queueStop(), second = queueStop();
        Request conflicting = Request.stop(second.id, second.sender, second.recipient, generation, 2, 0,
                second.issuedAt, second.expiresAt, second.sessionIdentity);
        sub.rememberSessionControl(dt.localIdentity(), generation, RemoteSessionRuntime.Direction.INCOMING, conflicting, RemoteSessionClock.observe(subContext));
        Outcome outcome = exchange(null, null); assertTrue(outcome.server instanceof SecurityException); assertNotNull(outcome.client);
        assertNull(sub.sessionControl(dt.localIdentity(), generation, RemoteSessionRuntime.Direction.INCOMING, first.id));
        assertEquals(conflicting.digest(), sub.sessionControl(dt.localIdentity(), generation, RemoteSessionRuntime.Direction.INCOMING, second.id).request.digest());
        assertEquals("2", incomingFloor()); assertEquals(1, incomingCount());
        assertEquals("", sub.metadata(dt.localIdentity(), generation, "session_control_wire:INCOMING:" + first.id + ":protected", ""));
        assertEquals(originalRuntime, stats.activeSessionIdentity()); assertNull(nativeSession.stopMarker());
    }
    @Test public void secondCapacityFailureRollsBackSixteenthReceiptAndFloor() throws Exception {
        new AppModeManager(subContext).setArmed(true); String originalRuntime = stats.activeSessionIdentity();
        RemoteSessionClock.Sample now = RemoteSessionClock.observe(subContext);
        for (int index = 1; index <= 15; index++) {
            long sequence = dom.allocateSessionSequence(st.localIdentity(), generation); assertEquals(index, sequence);
            Request seed = Request.stop(UUID.randomUUID().toString(), dt.localIdentity(), st.localIdentity(), generation,
                    sequence, 0, now.wall, now.wall + 120000, RuntimeSessionIdentity.observe(subContext, now).identity);
            sub.rememberSessionControl(dt.localIdentity(), generation, RemoteSessionRuntime.Direction.INCOMING, seed, now);
        }
        Request first = queueStop(), second = queueStop(); Outcome outcome = exchange(null, null);
        assertTrue(outcome.server instanceof IllegalStateException); assertNotNull(outcome.client);
        for (Request request : new Request[]{first, second}) {
            assertNull(sub.sessionControl(dt.localIdentity(), generation, RemoteSessionRuntime.Direction.INCOMING, request.id));
            assertEquals("", sub.metadata(dt.localIdentity(), generation, "session_control_wire:INCOMING:" + request.id + ":protected", ""));
        }
        assertEquals("15", incomingFloor()); assertEquals(15, incomingCount());
        assertEquals(originalRuntime, stats.activeSessionIdentity()); assertNull(nativeSession.stopMarker());
    }
    @Test public void failedTerminalOutboxCommitCannotEmitCompleteOrAcknowledgeReceiver() throws Exception {
        new AppModeManager(subContext).setArmed(true); Request request = queueStop();
        dom.getWritableDatabase().execSQL("CREATE TRIGGER deny_result_ack BEFORE INSERT ON remote_metadata WHEN NEW.name LIKE '%:ackPending' BEGIN SELECT RAISE(ABORT,'Synthetic ACK durability failure'); END");
        try {
            Outcome failed = exchange(null, null); assertNotNull(failed.client);
            assertEquals(State.ACKNOWLEDGED, outgoing(request).result.state);
            assertTrue(dom.pendingSessionControlAcks(st.localIdentity(), generation).isEmpty()); assertEquals("", acked(request));
            for (String value : transcript) assertFalse(value.contains("session_control_complete"));
        } finally { dom.getWritableDatabase().execSQL("DROP TRIGGER deny_result_ack"); }
        exchange(null, null).success(); assertEquals(State.APPLIED, outgoing(request).result.state);
        assertEquals(1, stats.getSessionHistory().size());
    }
    @Test public void terminalAcknowledgmentUncertaintyStaysBoundedAcrossDowngradeAndReopen() throws Exception {
        new AppModeManager(subContext).setArmed(true); List<Request> requests = new ArrayList<>();
        for (int index = 0; index < 16; index++) requests.add(queueStop());
        Outcome lost = exchange(frame -> "session_control_complete".equals(frame.optString("type")) ? null : frame, null);
        assertNotNull(lost.client); assertEquals(16, dom.pendingSessionControlAcks(st.localIdentity(), generation).size());
        for (Request request : requests) { ageAnchors(dom, request); ageAnchors(sub, request); assertEquals("", acked(request)); }
        dom.putMetadata(st.localIdentity(), generation, "session_control_version", "0");
        sub.putMetadata(dt.localIdentity(), generation, "session_control_version", "0"); reopen();
        RemoteSessionClock.Sample now = RemoteSessionClock.observe(subContext);
        Request seventeenth = Request.stop(UUID.randomUUID().toString(), dt.localIdentity(), st.localIdentity(), generation,
                dom.allocateSessionSequence(st.localIdentity(), generation), 0, now.wall, now.wall + 120000,
                String.join("", Collections.nCopies(64, "f")));
        assertThrows(IllegalStateException.class, () -> dom.rememberSessionControl(st.localIdentity(), generation,
                RemoteSessionRuntime.Direction.OUTGOING, seventeenth, now));
        assertThrows(IllegalStateException.class, () -> sub.rememberSessionControl(dt.localIdentity(), generation,
                RemoteSessionRuntime.Direction.INCOMING, seventeenth, now));
        assertEquals(16, incomingCount()); assertEquals(16, dom.pendingSessionControlAcks(st.localIdentity(), generation).size());
        assertEquals("16", incomingFloor()); assertEquals(1, stats.getSessionHistory().size());
        dom.putMetadata(st.localIdentity(), generation, "session_control_version", "1");
        sub.putMetadata(dt.localIdentity(), generation, "session_control_version", "1");
        exchange(null, null).success(); assertTrue(dom.pendingSessionControlAcks(st.localIdentity(), generation).isEmpty());
        for (Request request : requests) assertFalse(acked(request).isEmpty());
    }
    @Test public void ackThenLaterCommandConflictRollsBackAckWithoutEmittingEcho() throws Exception {
        new AppModeManager(subContext).setArmed(true); Request terminal = queueStop();
        Outcome lost = exchange(frame -> "session_control_complete".equals(frame.optString("type")) ? null : frame, null);
        assertNotNull(lost.client); dom.allocateSessionSequence(st.localIdentity(), generation);
        Request first = queueStopFor(terminal.sessionIdentity), second = queueStopFor(terminal.sessionIdentity);
        Request conflicting = Request.stop(second.id, second.sender, second.recipient, generation, 2, 0,
                second.issuedAt, second.expiresAt, second.sessionIdentity);
        sub.rememberSessionControl(dt.localIdentity(), generation, RemoteSessionRuntime.Direction.INCOMING, conflicting, RemoteSessionClock.observe(subContext));
        transcript.clear(); Outcome failed = exchange(null, null); assertTrue(failed.server instanceof SecurityException);
        assertEquals("", acked(terminal)); assertEquals(1, dom.pendingSessionControlAcks(st.localIdentity(), generation).size());
        assertNull(sub.sessionControl(dt.localIdentity(), generation, RemoteSessionRuntime.Direction.INCOMING, first.id));
        assertEquals("2", incomingFloor()); assertEquals(2, incomingCount()); assertEquals(1, stats.getSessionHistory().size());
        for (String value : transcript) assertFalse(value.contains("session_control_receipts"));
    }
    @Test public void laterResultAckMismatchCannotPartiallyRetireTerminalRows() throws Exception {
        new AppModeManager(subContext).setArmed(true); Request first = queueStop(), second = queueStop();
        Outcome lost = exchange(frame -> "session_control_complete".equals(frame.optString("type")) ? null : frame, null);
        assertNotNull(lost.client); assertEquals(2, dom.pendingSessionControlAcks(st.localIdentity(), generation).size());
        transcript.clear(); Outcome failed = exchange(frame -> {
            if (!"session_control_commands".equals(frame.optString("type"))) return frame;
            try {
                frame.getJSONArray("acks").getJSONObject(1).put("resultDigest", String.join("", Collections.nCopies(64, "f")));
                commandDigest(frame); return frame;
            } catch (Exception failure) { throw new AssertionError(failure); }
        }, null);
        assertTrue(failed.server instanceof SecurityException); assertNotNull(failed.client);
        assertEquals("", acked(first)); assertEquals("", acked(second));
        assertEquals(2, dom.pendingSessionControlAcks(st.localIdentity(), generation).size());
        for (String value : transcript) assertFalse(value.contains("session_control_receipts"));
        exchange(null, null).success(); assertTrue(dom.pendingSessionControlAcks(st.localIdentity(), generation).isEmpty());
    }
    @Test public void timedOutUnknownDeliveryCannotExecuteOrReinterpretLegacyExpiry() throws Exception {
        new AppModeManager(subContext).setArmed(true); String active = stats.activeSessionIdentity();
        dom.putMetadata(st.localIdentity(), generation, "session_control_version", "0");
        RemoteSessionClock.Sample now = RemoteSessionClock.observe(subContext);
        Request legacy = Request.stop(UUID.randomUUID().toString(), dt.localIdentity(), st.localIdentity(), generation,
                dom.allocateSessionSequence(st.localIdentity(), generation), 0, now.wall - 200000, now.wall - 80000,
                RuntimeSessionIdentity.observe(subContext, now).identity);
        dom.rememberSessionControl(st.localIdentity(), generation, RemoteSessionRuntime.Direction.OUTGOING, legacy, now);
        String immutable = RemoteProtocol.canonical(outgoing(legacy).result.json()); assertEquals(State.EXPIRED, outgoing(legacy).result.state);
        Request uncertain = queueStop(); ageAnchors(dom, uncertain);
        assertEquals("", dom.metadata(st.localIdentity(), generation, "session_control_wire:OUTGOING:" + uncertain.id + ":protected", ""));
        dom.putMetadata(st.localIdentity(), generation, "session_control_version", "1");
        Outcome failed = exchange(null, null); assertTrue(failed.server instanceof SecurityException); assertNotNull(failed.client);
        assertEquals(State.QUEUED, outgoing(uncertain).result.state);
        assertEquals(uncertain.digest(), dom.metadata(st.localIdentity(), generation, "session_control_wire:OUTGOING:" + uncertain.id + ":protected", ""));
        assertEquals(immutable, RemoteProtocol.canonical(outgoing(legacy).result.json()));
        assertNull(sub.sessionControl(dt.localIdentity(), generation, RemoteSessionRuntime.Direction.INCOMING, uncertain.id));
        assertEquals("0", incomingFloor()); assertEquals(0, incomingCount());
        assertEquals(active, stats.activeSessionIdentity()); assertTrue(stats.getSessionHistory().isEmpty()); assertNull(nativeSession.stopMarker());
        assertTrue(new JSONObject(transcript.get(0)).getJSONArray("reconcile").toString().contains(uncertain.id));
    }
    @Test public void senderTimedOutKnownUnpreparedReceiptCannotExecuteWithFreshReceiverAnchor() throws Exception {
        new AppModeManager(subContext).setArmed(true); String active = stats.activeSessionIdentity(); Request request = queueStop();
        Outcome lost = exchange(null, frame -> "session_control_receipts".equals(frame.optString("type")) ? null : frame);
        assertNotNull(lost.client);
        RemoteSessionRuntime.Record receipt = sub.sessionControl(dt.localIdentity(), generation, RemoteSessionRuntime.Direction.INCOMING, request.id);
        assertEquals(Reason.NONE, receipt.lifetime.check(request, RemoteSessionClock.observe(subContext)));
        ageAnchors(dom, request); exchange(null, null).success();
        assertEquals(State.EXPIRED, outgoing(request).result.state); assertEquals(active, stats.activeSessionIdentity());
        assertTrue(stats.getSessionHistory().isEmpty()); assertNull(nativeSession.stopMarker());
    }
    private RemoteSessionNative.PreparedStop prepareIncomingStop(Request request) throws Exception {
        RemoteSessionClock.Sample now = RemoteSessionClock.observe(subContext);
        sub.rememberSessionControl(dt.localIdentity(), generation, RemoteSessionRuntime.Direction.INCOMING, request, now);
        RemoteSessionNative.PreparedStop prepared = nativeSession.prepareStop(request, now);
        assertEquals(State.APPLYING, sub.prepareNativeSessionControl(dt.localIdentity(), generation, request.id, prepared.json(), now).result.state);
        assertTrue(RemoteSessionNative.currentProcess(prepared.process)); assertNull(nativeSession.stopMarker());
        return prepared;
    }
    @Test public void reconcileOnlyPreparedStopCannotCommitFirstEffectWithFreshReceiverClock() throws Exception {
        new AppModeManager(subContext).setArmed(true); String active = stats.activeSessionIdentity(); Request request = queueStop();
        prepareIncomingStop(request);
        RemoteSessionRuntime.Record original = sub.sessionControl(dt.localIdentity(), generation, RemoteSessionRuntime.Direction.INCOMING, request.id);
        assertEquals(Reason.NONE, original.lifetime.check(request, RemoteSessionClock.observe(subContext)));
        // A sender-only changed clock: request and fresh receiver anchors remain untouched.
        dom.getWritableDatabase().execSQL("UPDATE session_control_journal SET received_wall=received_wall+5000 WHERE id=?", new Object[]{request.id});
        exchange(null, null).success();
        assertEquals(State.EXPIRED, outgoing(request).result.state); assertEquals(active, stats.activeSessionIdentity());
        assertNull(nativeSession.stopMarker()); assertTrue(stats.getSessionHistory().isEmpty());
        RemoteSessionRuntime.Record after = sub.sessionControl(dt.localIdentity(), generation, RemoteSessionRuntime.Direction.INCOMING, request.id);
        assertEquals(original.request.digest(), after.request.digest()); assertEquals(original.request.expiresAt, after.request.expiresAt);
        assertEquals(original.lifetime.receivedWall, after.lifetime.receivedWall); assertEquals(original.lifetime.deadlineElapsed, after.lifetime.deadlineElapsed);
    }
    @Test public void reconcileOnlyCommittedStopFinishesOriginalProofWithoutNewEffect() throws Exception {
        new AppModeManager(subContext).setArmed(true); Request request = queueStop();
        RemoteSessionNative.PreparedStop prepared = prepareIncomingStop(request);
        assertTrue(nativeSession.commitPreparedStop(prepared, RemoteSessionClock.observe(subContext)));
        assertNotNull(nativeSession.stopMarker()); assertEquals(1, stats.getSessionHistory().size());
        String marker = RemoteProtocol.canonical(nativeSession.stopMarker().prepared.json()); ageAnchors(dom, request);
        exchange(null, null).success(); assertEquals(State.APPLIED, outgoing(request).result.state);
        assertFalse(outgoing(request).result.proof.active); assertEquals(1, stats.getSessionHistory().size());
        assertEquals(marker, RemoteProtocol.canonical(nativeSession.stopMarker().prepared.json()));
    }
    @Test public void ordinaryPreparedStopStillCommitsItsFirstEffect() throws Exception {
        new AppModeManager(subContext).setArmed(true); Request request = queueStop(); prepareIncomingStop(request);
        exchange(null, null).success(); assertEquals(State.APPLIED, outgoing(request).result.state);
        assertNotNull(nativeSession.stopMarker()); assertEquals(1, stats.getSessionHistory().size());
    }
    @Test public void seedWireDurableUncertaintyAndAckPendingBeforeActualProcessDeath() throws Exception {
        org.junit.Assume.assumeTrue(crashFixture != null && !crashRecovery);
        new AppModeManager(subContext).setArmed(true); Request terminal = queueStop();
        Outcome lostComplete = exchange(frame -> "session_control_complete".equals(frame.optString("type")) ? null : frame, null);
        assertNotNull(lostComplete.client); assertEquals(State.APPLIED, outgoing(terminal).result.state);
        new AppModeManager(subContext).setArmed(true); String active = stats.activeSessionIdentity(); Request uncertain = queueStop();
        Outcome lostReceipt = exchange(null, frame -> "session_control_receipts".equals(frame.optString("type")) ? null : frame);
        assertNotNull(lostReceipt.client); assertEquals(State.QUEUED, outgoing(uncertain).result.state);
        assertEquals(State.ACKNOWLEDGED, sub.sessionControl(dt.localIdentity(), generation, RemoteSessionRuntime.Direction.INCOMING, uncertain.id).result.state);
        assertFalse(acked(terminal).isEmpty());
        assertEquals(1, dom.pendingSessionControlAcks(st.localIdentity(), generation).size());
        assertEquals(terminal.id, dom.pendingSessionControlAcks(st.localIdentity(), generation).get(0).id);
        assertEquals(active, stats.activeSessionIdentity()); assertEquals(1, stats.getSessionHistory().size());
        assertTrue(crashFixture.edit().putString("terminal", RemoteProtocol.canonical(terminal.json()))
                .putString("uncertain", RemoteProtocol.canonical(uncertain.json())).putString("active", active)
                .putInt("seedPid", android.os.Process.myPid()).commit());
        android.os.Bundle witness = new android.os.Bundle(); witness.putString("wire_boundary", "ready");
        witness.putInt("wire_pid", android.os.Process.myPid()); InstrumentationRegistry.getInstrumentation().sendStatus(1, witness);
        // Host intentionally force-stops this instrumented process after the durable boundary; this is not a passing test.
        new java.util.concurrent.CountDownLatch(1).await(90, TimeUnit.SECONDS);
        fail("Host did not interrupt the seeded fixture");
    }
    @Test public void recoverWireUncertaintyAndAckPendingAfterActualProcessDeath() throws Exception {
        org.junit.Assume.assumeTrue(crashFixture != null && crashRecovery);
        assertNotEquals(crashFixture.getInt("seedPid", -1), android.os.Process.myPid());
        Request terminal = Request.parse(new JSONObject(crashFixture.getString("terminal", "")));
        Request uncertain = Request.parse(new JSONObject(crashFixture.getString("uncertain", "")));
        assertEquals(State.APPLIED, outgoing(terminal).result.state); assertNotNull(outgoing(terminal).result.proof);
        assertEquals(State.QUEUED, outgoing(uncertain).result.state);
        assertEquals(uncertain.digest(), outgoing(uncertain).request.digest());
        assertEquals(uncertain.expiresAt, outgoing(uncertain).request.expiresAt);
        assertEquals(1, dom.pendingSessionControlAcks(st.localIdentity(), generation).size());
        assertEquals(terminal.id, dom.pendingSessionControlAcks(st.localIdentity(), generation).get(0).id);
        assertEquals(crashFixture.getString("active", ""), stats.activeSessionIdentity());
        assertEquals(1, stats.getSessionHistory().size());
        ageAnchors(dom, uncertain); ageAnchors(sub, uncertain);
        exchange(null, null).success(); assertEquals(State.EXPIRED, outgoing(uncertain).result.state);
        assertEquals(State.APPLIED, outgoing(terminal).result.state); assertEquals(1, stats.getSessionHistory().size());
        assertEquals(crashFixture.getString("active", ""), stats.activeSessionIdentity());
        exchange(null, null).success(); assertTrue(dom.pendingSessionControlAcks(st.localIdentity(), generation).isEmpty());
        android.os.Bundle witness = new android.os.Bundle(); witness.putInt("wire_recovered_pid", android.os.Process.myPid());
        InstrumentationRegistry.getInstrumentation().sendStatus(1, witness); crashRecovered = true;
    }
    private final class Probe implements PeerTransport.Connection {
        int calls;
        String identity = st.localIdentity();
        public String authenticatedPeerIdentity() { calls++; return identity; }
        public InputStream input() { calls++; return new ByteArrayInputStream(new byte[0]); }
        public OutputStream output() { calls++; return new ByteArrayOutputStream(); }
        public void setReadTimeoutMillis(long timeout) { calls++; }
        public boolean isDirect() { return true; }
        public void close() { }
    }
    @Test public void missingCapabilityAndForeignAuthenticatedPeerCannotStartExchange() throws Exception {
        Probe probe = new Probe(); RemoteStore.Peer peer = dom.peer(st.localIdentity(), generation);
        dom.putMetadata(peer.identity, generation, "session_control_version", "0");
        assertThrows(SecurityException.class, () -> domWire.exchange(probe, peer, System.nanoTime() + TimeUnit.SECONDS.toNanos(1),false));
        assertEquals(1, probe.calls); probe.calls = 0; probe.identity = dt.localIdentity();
        dom.putMetadata(peer.identity, generation, "session_control_version", "1");
        assertThrows(SecurityException.class, () -> domWire.exchange(probe, peer, System.nanoTime() + TimeUnit.SECONDS.toNanos(1),false));
        assertEquals(1, probe.calls);
        probe.calls=0;probe.identity=st.localIdentity();dom.revoke(peer.identity,generation);
        assertThrows(SecurityException.class,()->domWire.exchange(probe,peer,System.nanoTime()+TimeUnit.SECONDS.toNanos(1),false));
        assertEquals(1,probe.calls);
    }
    @Test public void noTransportAccessorRunsUnderAuthorityLocks() throws Exception {
        Probe probe = new Probe(); RemoteStore.Peer peer = dom.peer(st.localIdentity(), generation);
        synchronized (dom) { assertThrows(IllegalStateException.class, () -> domWire.exchange(probe, peer, Long.MAX_VALUE)); }
        synchronized (SubFeaturePolicy.LOCK) { assertThrows(IllegalStateException.class, () -> domWire.exchange(probe, peer, Long.MAX_VALUE)); }
        synchronized (RuntimeSessionIdentity.LOCK) { assertThrows(IllegalStateException.class, () -> domWire.exchange(probe, peer, Long.MAX_VALUE)); }
        assertEquals(0, probe.calls);
    }
}
