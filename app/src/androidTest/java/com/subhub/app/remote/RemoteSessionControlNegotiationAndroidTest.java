package com.subhub.app.remote;

import static org.junit.Assert.*;

import android.content.Context;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.security.SubFeaturePolicy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Reuses the qualified direct-Iroh fixture; these tests exercise the actual RemoteSession handshake. */
public final class RemoteSessionControlNegotiationAndroidTest {
    private final RemoteSessionControlPeerAndroidTest fixture = new RemoteSessionControlPeerAndroidTest();
    private RemoteStore dom, sub;
    private PeerTransport dt, st;
    private Context subContext;
    private String generation;
    private ExecutorService server;
    private Hooks dh, sh;
    private final List<JSONObject> frames = Collections.synchronizedList(new ArrayList<>());
    private boolean legacyEnd;
    @Before public void setup() throws Exception {
        fixture.setup(); dom = (RemoteStore) value("dom"); sub = (RemoteStore) value("sub");
        dt = (PeerTransport) value("dt"); st = (PeerTransport) value("st"); subContext = (Context) value("subContext");
        generation = (String) value("generation"); server = (ExecutorService) value("server");
        dh = new Hooks((RemoteSessionControlPeer) value("domWire")); sh = new Hooks((RemoteSessionControlPeer) value("subWire"));
    }
    @After public void cleanup() throws Exception { fixture.cleanup(); }
    private Object value(String name) throws Exception {
        Field field = RemoteSessionControlPeerAndroidTest.class.getDeclaredField(name); field.setAccessible(true); return field.get(fixture);
    }
    private RemoteSessionCommands.Request queueStop() throws Exception {
        Method method = RemoteSessionControlPeerAndroidTest.class.getDeclaredMethod("queueStop"); method.setAccessible(true);
        return (RemoteSessionCommands.Request) method.invoke(fixture);
    }
    private final class Hooks implements RemoteSession.Hooks {
        int version, calls;
        final RemoteSessionControlPeer wire;
        Hooks(RemoteSessionControlPeer wire) { this.wire = wire; }
        public int sessionControlVersion() { return version; }
        public void sessionControl(PeerTransport.Connection connection, RemoteStore.Peer peer, long deadline) throws Exception {
            calls++; wire.exchange(connection, peer, deadline);
        }
        public void prepare(RemoteStore.Peer peer) throws Exception { end(peer); }
        public void received(RemoteStore.Peer peer, RemoteProtocol.Stream stream) throws Exception { end(peer); }
        public List<RemoteSession.AssetNeed> missingAssets(RemoteStore.Peer peer) { return Collections.emptyList(); }
        public RemoteAssets assets(RemoteStore.Peer peer) { return null; }
        public void complete(RemoteStore.Peer peer) throws Exception { end(peer); }
        private void end(RemoteStore.Peer peer) throws Exception {
            if (legacyEnd && peer.localRole == RemoteProtocol.Role.SUB)
                new RemoteEndCommands(subContext, sub).process(peer, System.currentTimeMillis());
        }
    }
    private static final class Outcome {
        Exception client, server;
        void success() { assertNull(String.valueOf(client), client); assertNull(String.valueOf(server), server); }
    }
    private Outcome exchange(boolean domInitiates, UnaryOperator<JSONObject> clientFilter, UnaryOperator<JSONObject> serverFilter) throws Exception {
        PeerTransport source = domInitiates ? dt : st, target = domInitiates ? st : dt;
        RemoteStore sourceStore = domInitiates ? dom : sub, targetStore = domInitiates ? sub : dom;
        Hooks sourceHooks = domInitiates ? dh : sh, targetHooks = domInitiates ? sh : dh;
        RemoteSession outgoing = new RemoteSession(sourceStore, source, sourceHooks), incoming = new RemoteSession(targetStore, target, targetHooks);
        Outcome outcome = new Outcome(); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        Future<?> serving = server.submit(() -> {
            try (PeerTransport.Connection raw = target.accept(10000)) {
                PeerTransport.Connection observed = new Observe(raw, serverFilter);
                JSONObject hello = RemoteProtocol.read(observed.input()); incoming.incoming(observed, hello, deadline);
            } catch (Exception failure) { outcome.server = failure; }
        });
        try (PeerTransport.Connection raw = source.connect(target.localIdentity(), target.inviteRoute(), 10000)) {
            outgoing.outgoing(new Observe(raw, clientFilter), sourceStore.peer(target.localIdentity(), generation), deadline);
        } catch (Exception failure) { outcome.client = failure; }
        serving.get(25, TimeUnit.SECONDS); return outcome;
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
                public void write(int value) { unlocked(); pending.write(value); }
                public void write(byte[] bytes, int offset, int length) { unlocked(); pending.write(bytes, offset, length); }
                public void flush() throws IOException {
                    unlocked(); JSONObject frame = RemoteProtocol.read(new ByteArrayInputStream(pending.toByteArray()));
                    if (filter != null) frame = filter.apply(frame);
                    frames.add(frame); RemoteProtocol.write(wrapped.output(), frame); pending.reset();
                }
            };
        }
        public String authenticatedPeerIdentity() { unlocked(); return wrapped.authenticatedPeerIdentity(); }
        public InputStream input() throws IOException { unlocked(); return wrapped.input(); }
        public OutputStream output() { unlocked(); return output; }
        public void setReadTimeoutMillis(long value) { unlocked(); wrapped.setReadTimeoutMillis(value); }
        public boolean isDirect() { return wrapped.isDirect(); }
        public void close() throws IOException { wrapped.close(); }
    }
    private void versions(String expected) {
        assertEquals(expected, dom.metadata(st.localIdentity(), generation, "session_control_version", "missing"));
        assertEquals(expected, sub.metadata(dt.localIdentity(), generation, "session_control_version", "missing"));
    }
    private void noControlFrames() {
        for (JSONObject frame : frames) assertFalse(frame.optString("type").startsWith("session_control_"));
        assertEquals(0, dh.calls); assertEquals(0, sh.calls);
    }
    private static Set<String> keys(JSONObject value) {
        Set<String> fields = new HashSet<>(); java.util.Iterator<String> keys = value.keys(); while (keys.hasNext()) fields.add(keys.next()); return fields;
    }
    private JSONObject oldRuntime() throws Exception {
        JSONObject value = new JSONObject().put("schema", 1).put("device", "Synthetic").put("observedAt", System.currentTimeMillis())
                .put("sessionStart", 0).put("sessionEnd", 0).put("appliedRevision", 0)
                .put("endSessionSupported", true).put("sessionIdentity", RuntimeSessionIdentity.observe(subContext, RemoteSessionClock.observe(subContext)).identity);
        for (String key : new String[]{"serviceActive", "screenCapture", "recognitionActive", "accessibilityBound", "accessibilityEnabled",
                "overlayPermission", "notifications", "armed", "paused"}) value.put(key, false);
        assertTrue(RemoteTelemetry.validRuntime(value)); return value;
    }

    @Test public void defaultHandshakeAndRuntimeRemainByteCompatibleAndLegacyEndStillWorks() throws Exception {
        JSONObject runtime = oldRuntime(); sub.publish(dt.localIdentity(), generation, RemoteProtocol.Stream.RUNTIME, "current", runtime, false, System.currentTimeMillis());
        exchange(true, null, null).success(); versions("0"); noControlFrames();
        assertEquals(RemoteProtocol.canonical(runtime), RemoteProtocol.canonical(dom.mirrored(st.localIdentity(), generation, RemoteProtocol.Stream.RUNTIME, "current")));
        for (JSONObject frame : frames) {
            if ("hello".equals(frame.optString("type"))) assertEquals(new HashSet<>(Arrays.asList("version", "type", "generation", "route", "authVersion", "activityVersion", "displayName")), keys(frame));
            if ("hello_ack".equals(frame.optString("type"))) assertEquals(new HashSet<>(Arrays.asList("version", "type", "generation", "authVersion", "activityVersion", "displayName")), keys(frame));
        }
        new AppModeManager(subContext).setArmed(true); RemoteSessionClock.Sample now = RemoteSessionClock.observe(subContext);
        String id = "legacy_" + UUID.randomUUID(); JSONObject request = new JSONObject().put("schema", 1).put("type", "end_session").put("id", id)
                .put("sessionIdentity", RuntimeSessionIdentity.observe(subContext, now).identity).put("issuedAt", now.wall).put("expiresAt", now.wall + 120000);
        dom.publish(st.localIdentity(), generation, RemoteProtocol.Stream.CONFIGURATION, "end:" + id, request, false, now.wall); legacyEnd = true;
        exchange(false, null, null).success(); versions("0"); noControlFrames();
        assertEquals(RemoteProtocol.canonical(request), RemoteProtocol.canonical(sub.mirrored(dt.localIdentity(), generation, RemoteProtocol.Stream.CONFIGURATION, "end:" + id)));
        assertFalse(new AppModeManager(subContext).isArmed()); assertEquals("ENDED", new JSONObject(sub.metadata(dt.localIdentity(), generation, "end:" + id, "")).getString("state"));
    }
    private void negotiatedStop(boolean domInitiates) throws Exception {
        dh.version = 1; sh.version = 1; new AppModeManager(subContext).setArmed(true); RemoteSessionCommands.Request request = queueStop();
        exchange(domInitiates, null, null).success(); versions("1"); assertEquals(1, dh.calls); assertEquals(1, sh.calls);
        assertEquals(RemoteSessionCommands.State.APPLIED, dom.sessionControl(st.localIdentity(), generation, RemoteSessionRuntime.Direction.OUTGOING, request.id).result.state);
        assertFalse(new AppModeManager(subContext).isArmed());
        assertEquals("0", dom.metadata(st.localIdentity(), generation, "tasks_version", "missing"));
        assertEquals("0", dom.metadata(st.localIdentity(), generation, "journal_version", "missing"));
        assertFalse(RemoteSessionRuntime.applicationEnabled());
    }
    @Test public void independentlyNegotiatedControlWorksWhenDomInitiates() throws Exception { negotiatedStop(true); }
    @Test public void independentlyNegotiatedControlWorksWhenSubInitiates() throws Exception { negotiatedStop(false); }
    @Test public void oneSidedCapabilityFallsBackInBothDirectionsAndClearsOldNegotiation() throws Exception {
        for (int version : new int[]{0, 1}) for (boolean initiator : new boolean[]{false, true}) {
            dh.version = version; sh.version = 1 - version;
            dom.putMetadata(st.localIdentity(), generation, "session_control_version", "1"); sub.putMetadata(dt.localIdentity(), generation, "session_control_version", "1");
            exchange(initiator, null, null).success(); versions("0"); noControlFrames();
        }
    }
    @Test public void unsupportedPositiveCapabilityFallsBackWithoutCallingControlHooks() throws Exception {
        dh.version = 1; sh.version = 1;
        exchange(true, frame -> {
            if ("hello".equals(frame.optString("type"))) try { frame.put("sessionControlVersion", 2); } catch (Exception failure) { throw new AssertionError(failure); }
            return frame;
        }, null).success(); versions("0"); noControlFrames();
    }
    @Test public void malformedHelloCapabilityFailsClosedAndClearsOldReceiverCapability() throws Exception {
        dh.version = 1; sh.version = 1;
        for (Object invalid : new Object[]{0, "1", true}) {
            sub.putMetadata(dt.localIdentity(), generation, "session_control_version", "1");
            Outcome outcome = exchange(true, frame -> {
                if ("hello".equals(frame.optString("type"))) try { frame.put("sessionControlVersion", invalid); } catch (Exception failure) { throw new AssertionError(failure); }
                return frame;
            }, null);
            assertNotNull(outcome.client); assertNotNull(outcome.server);
            assertEquals("0", sub.metadata(dt.localIdentity(), generation, "session_control_version", "missing")); noControlFrames();
        }
    }
    @Test public void malformedAckCapabilityCannotRestoreOldSenderCapability() throws Exception {
        dh.version = 1; sh.version = 1;
        Outcome outcome = exchange(true, null, frame -> {
            if ("hello_ack".equals(frame.optString("type"))) try { frame.put("sessionControlVersion", "1"); } catch (Exception failure) { throw new AssertionError(failure); }
            return frame;
        });
        assertNotNull(outcome.client); assertNotNull(outcome.server);
        assertEquals("0", dom.metadata(st.localIdentity(), generation, "session_control_version", "missing")); noControlFrames();
    }
}
