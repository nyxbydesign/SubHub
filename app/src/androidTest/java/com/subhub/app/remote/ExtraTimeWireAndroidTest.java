package com.subhub.app.remote;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real encrypted duplex transport proves negotiation and phase ordering, not just JSON encoding. */
@RunWith(AndroidJUnit4.class)
public final class ExtraTimeWireAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private String domDatabase, subDatabase, generation;
    private RemoteStore dom, sub;
    private PeerTransport dt, st;
    private ExecutorService server;
    private Hooks dh, sh;
    private final List<String> frames = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean dropReceipt, revokeBeforeSubReceive;
    private volatile String dropHandshake;
    private volatile boolean malformedHello;

    @Before public void setup() throws Exception {
        domDatabase = "extra-time-wire-dom-" + UUID.randomUUID();
        subDatabase = "extra-time-wire-sub-" + UUID.randomUUID();
        dom = new RemoteStore(context, domDatabase); sub = new RemoteStore(context, subDatabase);
        dt = IrohPeerTransport.open(context, seed(), new String[0], 10_000);
        st = IrohPeerTransport.open(context, seed(), new String[0], 10_000);
        PairingInvite invite = PairingInvite.create(dt.localIdentity(), dt.inviteRoute(), 1000);
        generation = invite.id;
        dom.saveInvite(invite); dom.bindInvite(invite.id, st.localIdentity(), invite.tokenHash(), 1001);
        sub.acceptInvite(invite, "Synthetic Dom"); dom.approveInvite(invite.id, st.localIdentity(), "Synthetic Sub", 1001);
        dom.activate(st.localIdentity(), generation); sub.activate(dt.localIdentity(), generation);
        dh = new Hooks(dom, st.localIdentity(), RemoteProtocol.Role.DOM, "approved-request");
        sh = new Hooks(sub, dt.localIdentity(), RemoteProtocol.Role.SUB, "new-request");
        server = Executors.newSingleThreadExecutor();
    }

    @After public void cleanup() throws Exception {
        if (dt != null) dt.close();
        if (st != null) st.close();
        if (server != null) { server.shutdownNow(); assertTrue(server.awaitTermination(10, TimeUnit.SECONDS)); }
        if (dom != null) dom.close();
        if (sub != null) sub.close();
        if (domDatabase != null) context.deleteDatabase(domDatabase);
        if (subDatabase != null) context.deleteDatabase(subDatabase);
    }

    @Test public void negotiatedPhaseRunsBeforeDrainForBothInitiatorsAndPhasePlans() throws Exception {
        for (boolean priority : new boolean[]{false, true}) for (boolean domInitiates : new boolean[]{false, true}) {
            dh.priority = sh.priority = priority; frames.clear();
            exchange(domInitiates).success(); versions("1");
            assertEquals(Set.of("new-request"), dh.endpoint.applied);
            assertEquals(Set.of("approved-request"), sh.endpoint.applied);
            assertEquals(2, Collections.frequency(frames, "extra_time_state"));
            assertEquals(2, Collections.frequency(frames, "extra_time_receipt"));
            assertTrue(frames.indexOf("extra_time_complete_ack") < frames.indexOf("done"));
        }
    }

    @Test public void legacyPeerSkipsPhaseInBothDirectionsAndClearsOldCapability() throws Exception {
        for (boolean domSupports : new boolean[]{false, true}) for (boolean domInitiates : new boolean[]{false, true}) {
            dh.version = domSupports ? 1 : 0; sh.version = domSupports ? 0 : 1;
            dom.putMetadata(st.localIdentity(), generation, "extra_time_version", "1");
            sub.putMetadata(dt.localIdentity(), generation, "extra_time_version", "1");
            frames.clear(); exchange(domInitiates).success(); versions("0");
            assertEquals(0, dh.calls); assertEquals(0, sh.calls);
            for (String frame : frames) assertFalse(frame.startsWith("extra_time_"));
        }
    }

    @Test public void failedHandshakePreservesOnlyPreviouslyNegotiatedCapability() throws Exception {
        // A failed first contact must never invent support for an unknown peer.
        versions("missing");
        dropHandshake = "hello";
        for (boolean domInitiates : new boolean[]{false, true}) {
            frames.clear();
            Outcome failed = exchange(domInitiates);
            assertNotNull(failed.client); assertNotNull(failed.server);
            versions("missing");
            for (String frame : frames) assertFalse(frame.startsWith("extra_time_"));
        }
        dropHandshake = null;
        exchange(true).success(); versions("1");

        // Retry failures are connection failures, not evidence that either app lost support.
        for (String handshake : new String[]{"hello", "hello_ack"}) {
            dropHandshake = handshake;
            for (boolean domInitiates : new boolean[]{false, true}) {
                int previousDomCalls = dh.calls, previousSubCalls = sh.calls;
                frames.clear();
                Outcome failed = exchange(domInitiates);
                assertNotNull(failed.client); assertNotNull(failed.server);
                versions("1");
                assertEquals(previousDomCalls, dh.calls); assertEquals(previousSubCalls, sh.calls);
                for (String frame : frames) assertFalse(frame.startsWith("extra_time_"));
            }
        }
        dropHandshake = null;
        // A valid authenticated envelope with an invalid capability fails inside incoming()
        // before assignment. It must not erase compatibility from a prior valid negotiation.
        malformedHello = true;
        for (boolean domInitiates : new boolean[]{false, true}) {
            int previousDomCalls = dh.calls, previousSubCalls = sh.calls;
            frames.clear();
            Outcome failed = exchange(domInitiates);
            assertNotNull(failed.client); assertNotNull(failed.server);
            versions("1");
            assertEquals(previousDomCalls, dh.calls); assertEquals(previousSubCalls, sh.calls);
            for (String frame : frames) assertFalse(frame.startsWith("extra_time_"));
        }
        malformedHello = false;
        dh.version = 0;
        frames.clear(); exchange(false).success(); versions("0");
        for (String frame : frames) assertFalse(frame.startsWith("extra_time_"));
        dh.version = 1;
        frames.clear(); exchange(true).success(); versions("1");
        assertTrue(frames.contains("extra_time_state"));
    }

    @Test public void lostReceiptReconnectDoesNotRepeatCommittedApplication() throws Exception {
        dropReceipt = true;
        Outcome first = exchange(true);
        assertNotNull(first.client); assertNotNull(first.server);
        assertEquals(Set.of("approved-request"), sh.endpoint.applied);
        assertEquals(1, sh.endpoint.applications);
        dropReceipt = false; frames.clear();
        exchange(false).success();
        assertEquals(1, sh.endpoint.applications);
        assertEquals(1, dh.endpoint.applications);
    }

    @Test public void failedDurabilityIsNotAcknowledgedOrMarkedApplied() throws Exception {
        sh.endpoint.failCommit = true;
        Outcome result = exchange(true);
        assertNotNull(result.client); assertNotNull(result.server);
        assertTrue(sh.endpoint.applied.isEmpty());
        assertFalse(frames.contains("extra_time_receipt"));
        assertFalse(frames.contains("done"));
    }

    @Test public void revokedPairCannotApplyInFlightDecision() throws Exception {
        revokeBeforeSubReceive = true;
        Outcome result = exchange(true);
        assertNotNull(result.client); assertNotNull(result.server);
        assertEquals(RemoteStore.State.REVOKED, sub.peer(dt.localIdentity(), generation).state);
        assertTrue(sh.endpoint.applied.isEmpty());
        assertFalse(frames.contains("extra_time_receipt"));
    }

    @Test public void subDisabledAvailabilityExchangesNoPrivateRequestState() throws Exception {
        sh.endpoint.allowed = false;
        exchange(false).success();
        assertFalse(frames.contains("extra_time_state"));
        assertTrue(dh.endpoint.applied.isEmpty()); assertTrue(sh.endpoint.applied.isEmpty());
    }

    private final class Hooks implements RemoteSession.Hooks {
        final Endpoint endpoint;
        int version = 1, calls;
        boolean priority;
        Hooks(RemoteStore store, String remote, RemoteProtocol.Role role, String record) {
            endpoint = new Endpoint(store, remote, role, record);
        }
        public int extraTimeVersion() { return version; }
        public int sessionControlVersion() { return priority ? 1 : 0; }
        public void sessionControl(PeerTransport.Connection connection, RemoteStore.Peer peer, long deadline, boolean ready) { }
        public void sessionControl(PeerTransport.Connection connection, RemoteStore.Peer peer, long deadline) { }
        public void extraTime(PeerTransport.Connection connection, RemoteStore.Peer peer, long deadline) throws Exception {
            calls++; ExtraTimePeer.exchange(endpoint, connection, peer, deadline);
        }
        public void prepare(RemoteStore.Peer peer) { }
        public void received(RemoteStore.Peer peer, RemoteProtocol.Stream stream) { }
        public List<RemoteSession.AssetNeed> missingAssets(RemoteStore.Peer peer) { return Collections.emptyList(); }
        public RemoteAssets assets(RemoteStore.Peer peer) { return null; }
        public void complete(RemoteStore.Peer peer) { }
    }

    private final class Endpoint implements ExtraTimePeer.Endpoint {
        final RemoteStore authority;
        final String remote, record;
        final RemoteProtocol.Role role;
        final Set<String> applied = new HashSet<>();
        int applications;
        boolean allowed = true, failCommit;
        Endpoint(RemoteStore authority, String remote, RemoteProtocol.Role role, String record) {
            this.authority = authority; this.remote = remote; this.role = role; this.record = record;
        }
        public JSONObject snapshot() throws Exception { requireActive(); return new JSONObject().put("id", record); }
        public void receive(JSONObject value) throws Exception {
            requireActive();
            if (failCommit) throw new IOException("Synthetic failed durable write");
            if (applied.add(value.getString("id"))) applications++;
        }
        public boolean enabled() { return allowed; }
        public void requireActive() {
            RemoteStore.Peer current = authority.peer(remote, generation);
            if (current == null || current.state != RemoteStore.State.ACTIVE || current.localRole != role)
                throw new SecurityException("Synthetic pairing revoked");
        }
    }

    private static final class Outcome {
        Exception client, server;
        void success() { assertNull(String.valueOf(client), client); assertNull(String.valueOf(server), server); }
    }

    private Outcome exchange(boolean domInitiates) throws Exception {
        PeerTransport source = domInitiates ? dt : st, target = domInitiates ? st : dt;
        RemoteStore sourceStore = domInitiates ? dom : sub, targetStore = domInitiates ? sub : dom;
        Hooks sourceHooks = domInitiates ? dh : sh, targetHooks = domInitiates ? sh : dh;
        RemoteSession outgoing = new RemoteSession(sourceStore, source, sourceHooks);
        RemoteSession incoming = new RemoteSession(targetStore, target, targetHooks);
        Outcome outcome = new Outcome();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        Future<?> serving = server.submit(() -> {
            try (PeerTransport.Connection raw = target.accept(10_000)) {
                PeerTransport.Connection observed = new Observe(raw);
                incoming.incoming(observed, RemoteProtocol.read(observed.input()), deadline);
            } catch (Exception failure) { outcome.server = failure; }
        });
        try (PeerTransport.Connection raw = source.connect(target.localIdentity(), target.inviteRoute(), 10_000)) {
            outgoing.outgoing(new Observe(raw), sourceStore.peer(target.localIdentity(), generation), deadline);
        } catch (Exception failure) { outcome.client = failure; }
        serving.get(25, TimeUnit.SECONDS);
        return outcome;
    }

    private final class Observe implements PeerTransport.Connection {
        final PeerTransport.Connection wrapped;
        final OutputStream output;
        Observe(PeerTransport.Connection wrapped) {
            this.wrapped = wrapped;
            output = new OutputStream() {
                final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                public void write(int value) { buffer.write(value); }
                public void write(byte[] bytes, int offset, int length) { buffer.write(bytes, offset, length); }
                public void flush() throws IOException {
                    JSONObject value = RemoteProtocol.read(new ByteArrayInputStream(buffer.toByteArray()));
                    String type = value.optString("type"); frames.add(type);
                    if (type.equals(dropHandshake)) throw new IOException("Synthetic lost handshake");
                    if (malformedHello && "hello".equals(type)) {
                        try { value.put("extraTimeVersion", 0); }
                        catch (org.json.JSONException invalid) { throw new IOException(invalid); }
                    }
                    if (dropReceipt && "extra_time_receipt".equals(type)) throw new IOException("Synthetic lost receipt");
                    if (revokeBeforeSubReceive && "extra_time_state".equals(type)) {
                        revokeBeforeSubReceive = false; sub.revoke(dt.localIdentity(), generation);
                    }
                    RemoteProtocol.write(wrapped.output(), value); buffer.reset();
                }
            };
        }
        public String authenticatedPeerIdentity() { return wrapped.authenticatedPeerIdentity(); }
        public InputStream input() throws IOException { return wrapped.input(); }
        public OutputStream output() { return output; }
        public void setReadTimeoutMillis(long timeout) { wrapped.setReadTimeoutMillis(timeout); }
        public boolean isDirect() { return wrapped.isDirect(); }
        public void close() throws IOException { wrapped.close(); }
    }

    private void versions(String expected) {
        assertEquals(expected, dom.metadata(st.localIdentity(), generation, "extra_time_version", "missing"));
        assertEquals(expected, sub.metadata(dt.localIdentity(), generation, "extra_time_version", "missing"));
    }
    private static byte[] seed() { byte[] seed = new byte[32]; new SecureRandom().nextBytes(seed); return seed; }
}
