package com.subhub.app.remote;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class RemoteTransportAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test public void javaConsumerLoadsNativeLibraryAndPreservesIdentity() throws Exception {
        byte[] seed = seed();
        String identity;
        try (PeerTransport endpoint = IrohPeerTransport.open(context,seed,new String[0],10_000)) {
            identity = endpoint.localIdentity();
            assertEquals(64, identity.length());
        }
        try (PeerTransport endpoint = IrohPeerTransport.open(context,seed,new String[0],10_000)) {
            assertEquals(identity,endpoint.localIdentity());
        }
    }

    @Test public void directEncryptedJavaStreamRoundTrip() throws Exception {
        roundTrip(false);
    }

    @Test public void publicRelayEncryptedJavaStreamRoundTrip() throws Exception {
        org.junit.Assume.assumeTrue("Requires the owned emulator UDP-blocked relay gate",
                "true".equals(InstrumentationRegistry.getArguments().getString("require_relay")));
        roundTrip(true);
    }

    @Test public void cancellationReleasesAcceptWithoutClosingEndpoint() throws Exception {
        try (PeerTransport endpoint = IrohPeerTransport.open(context,seed(),new String[0],10_000)) {
            long start = android.os.SystemClock.elapsedRealtime();
            assertThrows(SocketTimeoutException.class, () -> endpoint.accept(200));
            assertTrue(android.os.SystemClock.elapsedRealtime()-start < 3000);
            assertEquals(64,endpoint.localIdentity().length());
            assertFalse(endpoint.inviteRoute().isEmpty());
        }
    }

    @Test public void invitationRouteCannotSubstituteAnotherIdentity() throws Exception {
        try (PeerTransport a = IrohPeerTransport.open(context,seed(),new String[0],10_000);
             PeerTransport b = IrohPeerTransport.open(context,seed(),new String[0],10_000)) {
            assertThrows(IOException.class, () -> b.connect(b.localIdentity(),a.inviteRoute(),1000));
        }
    }

    @Test public void idleStreamReadDeadlineClosesConnection() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (PeerTransport a = IrohPeerTransport.open(context,seed(),new String[0],10_000);
             PeerTransport b = IrohPeerTransport.open(context,seed(),new String[0],10_000)) {
            Future<PeerTransport.Connection> accepting = executor.submit(() -> a.accept(10_000));
            try (PeerTransport.Connection client = b.connect(a.localIdentity(),a.inviteRoute(),10_000);
                 PeerTransport.Connection server = accepting.get(10,TimeUnit.SECONDS)) {
                server.setReadTimeoutMillis(200);
                assertThrows(SocketTimeoutException.class, () -> server.input().read());
            }
        } finally { executor.shutdownNow(); }
    }

    private void roundTrip(boolean requireRelay) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        String[] relays = requireRelay ? null : new String[0];
        try (PeerTransport a = IrohPeerTransport.open(context,seed(),relays,15_000);
             PeerTransport b = IrohPeerTransport.open(context,seed(),relays,15_000)) {
            String route = a.inviteRoute();
            if (requireRelay) {
                try (computer.iroh.EndpointTicket ticket = computer.iroh.EndpointTicket.Companion.fromString(route);
                     computer.iroh.EndpointAddr address = ticket.endpointAddr()) {
                    assertNotNull("No public relay route available",address.relayUrl());
                }
            }
            Future<PeerTransport.Connection> accepting = executor.submit(() -> a.accept(45_000));
            try (PeerTransport.Connection client = b.connect(a.localIdentity(),route,45_000);
                 PeerTransport.Connection server = accepting.get(45,TimeUnit.SECONDS)) {
                assertEquals(a.localIdentity(),client.authenticatedPeerIdentity());
                assertEquals(b.localIdentity(),server.authenticatedPeerIdentity());
                RemoteProtocol.write(client.output(),new JSONObject().put("version",1).put("protectedSeconds",1234));
                JSONObject received = RemoteProtocol.read(server.input());
                assertEquals(1234,received.getLong("protectedSeconds"));
                RemoteProtocol.write(server.output(),received);
                assertEquals(1234,RemoteProtocol.read(client.input()).getLong("protectedSeconds"));
                assertEquals(!requireRelay,client.isDirect());
                assertEquals(!requireRelay,server.isDirect());
            }
        } finally { executor.shutdownNow(); }
    }

    private static byte[] seed() {
        byte[] value = new byte[32]; new SecureRandom().nextBytes(value); return value;
    }
}
