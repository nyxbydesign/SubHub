package com.subhub.app.remote;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class FullAccessLoadingTest {
    @Test public void staleRouteUsesOnlyTheSameAuthenticatedIdentityWithinTheDialBudget() throws Exception {
        List<String> attempts = new ArrayList<>();
        FakeConnection opened = new FakeConnection(new byte[0]);
        PeerTransport transport = new PeerTransport() {
            @Override public Connection connect(String identity, String route, long timeout) throws IOException {
                assertEquals("synthetic-peer", identity);
                assertTrue(timeout > 0 && timeout <= 8_000);
                attempts.add(route);
                if (!route.isEmpty()) {
                    assertEquals(3_000, timeout);
                    throw new IOException("stale route");
                }
                return opened;
            }
            @Override public String localIdentity() { return "local"; }
            @Override public String inviteRoute() { return ""; }
            @Override public Connection accept(long timeout) { throw new AssertionError(); }
            @Override public void close() { }
        };
        assertSame(opened, RemoteFeature.dialFullAccess(transport, "synthetic-peer", "old-route"));
        assertEquals(List.of("old-route", ""), attempts);

        attempts.clear();
        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, () -> RemoteFeature.dialFullAccess(transport, "synthetic-peer", "old-route"));
            assertEquals(List.of("old-route"), attempts);
        } finally {
            Thread.interrupted();
        }
    }

    @Test public void admissionReleasesOnceAfterRejectionOrActualExitAndTransfersToChannelOnce() throws Exception {
        AtomicInteger rejected = new AtomicInteger();
        var beforeStart = new RemoteFeature.AdmittedTask<>(() -> { throw new AssertionError(); }, rejected::incrementAndGet);
        beforeStart.cancel(false);
        beforeStart.run();
        assertEquals(1, rejected.get());

        AtomicInteger released = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch allowExit = new CountDownLatch(1);
        var running = new RemoteFeature.AdmittedTask<>(() -> {
            entered.countDown();
            while (true) {
                try {
                    allowExit.await();
                    return null;
                } catch (InterruptedException ignored) {
                    // An admitted native operation may need time to return after interruption.
                }
            }
        }, released::incrementAndGet);
        Thread worker = new Thread(running);
        worker.start();
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTrue(running.cancel(true));
            assertEquals(0, released.get());
        } finally {
            allowExit.countDown();
            worker.join(3_000);
        }
        assertFalse(worker.isAlive());
        assertEquals(1, released.get());

        AtomicBoolean handedOff = new AtomicBoolean();
        AtomicInteger transferred = new AtomicInteger();
        FullAccessChannel channel = new FullAccessChannel(null, "contacts", new JSONObject(), () -> true,
                transferred::incrementAndGet);
        var admitted = new RemoteFeature.AdmittedTask<>(() -> {
            handedOff.set(true);
            return null;
        }, () -> {
            if (!handedOff.get()) transferred.incrementAndGet();
        });
        admitted.run();
        assertEquals(0, transferred.get());
        channel.close();
        channel.close();
        assertEquals(1, transferred.get());
    }

    @Test public void firstRowsTailAndLazyPhotosUseOneChannelAndBoundedFrames() throws Exception {
        JSONArray head = new JSONArray();
        JSONArray oldHead = new JSONArray();
        JSONArray photos = new JSONArray();
        Random deterministic = new Random(148);
        for (int index = 1; index <= 8; index++) {
            String id = Integer.toString(index);
            byte[] image = new byte[8 * 1024];
            deterministic.nextBytes(image);
            String photo = Base64.getEncoder().encodeToString(image);
            head.put(new JSONObject().put("id", id).put("title", "Synthetic " + id).put("hasPhoto", true));
            oldHead.put(new JSONObject().put("id", id).put("title", "Synthetic " + id).put("photo", photo));
            if (index <= 6) photos.put(new JSONObject().put("id", id).put("photo", photo));
        }
        JSONObject first = response(head).put("nextCursor", "8");
        JSONObject tail = response(new JSONArray().put(new JSONObject().put("id", "9")));
        JSONObject detail = response(photos);
        byte[] firstFrame = encoded(first);
        byte[] eagerFrame = encoded(response(oldHead).put("nextCursor", "8"));
        assertTrue(firstFrame.length < 1_024);
        assertTrue(eagerFrame.length >= 64 * 1024);
        assertTrue(encoded(detail).length < 70 * 1024);
        ByteArrayOutputStream server = new ByteArrayOutputStream();
        server.write(firstFrame);
        server.write(encoded(tail));
        server.write(encoded(detail));

        FakeConnection connection = new FakeConnection(server.toByteArray());
        try (FullAccessChannel channel = new FullAccessChannel(connection, "contacts",
                new JSONObject().put("status", "ready").put("lazyContactPhotos", true), () -> true, () -> { })) {
            assertTrue(channel.supportsLazyContactPhotos());
            assertFalse(channel.supportsLazyAppUsage());
            JSONObject initial = channel.query(new JSONObject().put("op", "contact_rows"));
            assertEquals(8, initial.getJSONArray("items").length());
            assertFalse(initial.getJSONArray("items").getJSONObject(0).has("photo"));
            assertEquals("9", channel.query(new JSONObject().put("op", "contact_rows")
                    .put("cursor", initial.getString("nextCursor"))).getJSONArray("items").getJSONObject(0).getString("id"));
            assertEquals(6, channel.query(new JSONObject().put("op", "contact_photos")
                    .put("node", "1,2,3,4,5,6")).getJSONArray("items").length());
            assertFalse(FullAccessChannel.queryOp("contacts", "contact_photos", "1,2,3,4,5,6,7"));
            assertFalse(FullAccessChannel.queryOp("apps", "app_usage", "http://example"));
        }
        assertEquals(1, connection.closed);
        try (ByteArrayInputStream requests = new ByteArrayInputStream(connection.sent.toByteArray())) {
            assertEquals("contact_rows", RemoteProtocol.read(requests).getString("op"));
            assertEquals("8", RemoteProtocol.read(requests).getString("cursor"));
            assertEquals("1,2,3,4,5,6", RemoteProtocol.read(requests).getString("node"));
            assertEquals(-1, requests.read());
        }
        System.out.printf("Synthetic Blackmail contact frames: initial=%d eager=%d six-photo=%d bytes%n",
                firstFrame.length, eagerFrame.length, encoded(detail).length);
    }

    private static JSONObject response(JSONArray items) throws Exception {
        return FullAccessChannel.frame(FullAccessChannel.RESPONSE).put("status", "ok")
                .put("scopeRevision", "fixture").put("items", items);
    }

    private static byte[] encoded(JSONObject frame) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        RemoteProtocol.write(bytes, frame);
        return bytes.toByteArray();
    }

    private static final class FakeConnection implements PeerTransport.Connection {
        final ByteArrayInputStream received;
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        int closed;

        FakeConnection(byte[] bytes) { received = new ByteArrayInputStream(bytes); }
        @Override public String authenticatedPeerIdentity() { return "synthetic-peer"; }
        @Override public InputStream input() { return received; }
        @Override public OutputStream output() { return sent; }
        @Override public void setReadTimeoutMillis(long timeout) { assertTrue(timeout > 0); }
        @Override public boolean isDirect() { return true; }
        @Override public void close() { closed++; }
    }
}
