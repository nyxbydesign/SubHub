package com.subhub.app.remote.icons;

import static com.subhub.app.remote.icons.IconFixtures.*;
import static org.junit.Assert.*;
import com.subhub.app.remote.RemoteProtocol;
import com.subhub.app.remote.RemoteStore;
import com.subhub.app.util.RemoteAppIcon;
import java.io.IOException;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AppIconExchangeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void actualDuplexTransfersPngAndSecondExchangeResumesCompleteWithoutChunks() throws Exception {
        AppIconCache domCache = domCache(), subCache = cache(temporary.newFolder());
        Authority dom = new Authority(), sub = new Authority(); byte[] png = png(128, 128, 1);
        RemoteStore.Peer domPeer = peer(RemoteProtocol.Role.DOM), subPeer = peer(RemoteProtocol.Role.SUB);
        AppIconExchange receiver = exchange(dom, (p, name) -> { throw new AssertionError("DOM never resolves packages"); }, domCache);
        AtomicInteger resolutions = new AtomicInteger();
        AppIconExchange sender = exchange(sub, (p, name) -> { assertEquals(PACKAGE, name); resolutions.incrementAndGet(); return png; }, subCache);
        for (int attempt = 0; attempt < 2; attempt++) {
            Outcome result = duplex((c, d) -> receiver.exchange(c, domPeer, d), (c, d) -> sender.exchange(c, subPeer, d));
            assertNull(String.valueOf(result.dom), result.dom); assertNull(String.valueOf(result.sub), result.sub);
            long chunks = result.fromSub.stream().filter(f -> "icon_chunk".equals(f.optString("type"))).count();
            if (attempt == 0) assertTrue(chunks > 0); else assertEquals(0, chunks);
        }
        RemoteAppIcon identity = domCache.snapshot().identity(SUB, GENERATION, PACKAGE);
        assertArrayEquals(png, domCache.load(identity));
        assertEquals(2, resolutions.get()); // Current source is re-rasterized; cache is never source authority.
        assertNull(subCache.snapshot().identity(SUB, GENERATION, PACKAGE));
    }

    @Test public void unknownPublicationReturnsUnavailableWithoutSourceDiscoveryOrLocalFallback() throws Exception {
        AppIconCache domCache = domCache(); Authority dom = new Authority(), sub = new Authority(); sub.published = false;
        AtomicInteger calls = new AtomicInteger();
        AppIconExchange receiver = exchange(dom, (p, name) -> { throw new AssertionError(); }, domCache);
        AppIconExchange sender = exchange(sub, (p, name) -> { calls.incrementAndGet(); throw new AssertionError(); }, cache(temporary.newFolder()));
        RemoteStore.Peer domPeer = peer(RemoteProtocol.Role.DOM), subPeer = peer(RemoteProtocol.Role.SUB);
        Outcome result = duplex((c, d) -> receiver.exchange(c, domPeer, d), (c, d) -> sender.exchange(c, subPeer, d));
        assertNull(result.dom); assertNull(result.sub); assertEquals(0, calls.get());
        assertEquals(bare(), domCache.snapshot().identity(SUB, GENERATION, PACKAGE)); assertNull(domCache.load(bare()));
    }

    @Test public void unauthenticatedOrRemovedMirrorCannotSendWants() throws Exception {
        Authority authority = new Authority(); AppIconExchange receiver = exchange(authority, (p, n) -> null, domCache());
        RemoteStore.Peer domPeer = peer(RemoteProtocol.Role.DOM);
        Connection[] pair = pair();
        try {
            assertThrows(SecurityException.class, () -> receiver.exchange(pair[1], domPeer, System.nanoTime() + TimeUnit.SECONDS.toNanos(1)));
            assertTrue(pair[1].outgoing.isEmpty());
            authority.mirrored = false;
            assertThrows(SecurityException.class, () -> receiver.exchange(pair[0], domPeer, System.nanoTime() + TimeUnit.SECONDS.toNanos(1)));
            assertTrue(pair[0].outgoing.isEmpty());
        } finally { pair[0].close(); pair[1].close(); }
    }

    @Test public void cachePresenceCannotAuthorizeAReferenceMissingFromTheMirrorProjection() throws Exception {
        Authority authority = new Authority(); AppIconCache cache = cache(temporary.newFolder());
        byte[] png = png(8, 8, 1); RemoteAppIcon icon = icon(SUB, GENERATION, PACKAGE, png, 8, 8);
        cache.publishReferences(Collections.singleton(icon)); cache.complete(cache.snapshot(), icon, png, () -> { });
        cache.publishReferences(Collections.emptyList());
        AppIconExchange receiver = exchange(authority, (p, n) -> null, cache); RemoteStore.Peer domPeer = peer(RemoteProtocol.Role.DOM);
        Connection[] pair = pair();
        try {
            assertThrows(SecurityException.class, () -> receiver.exchange(pair[0], domPeer, System.nanoTime() + TimeUnit.SECONDS.toNanos(1)));
            assertTrue(pair[0].outgoing.isEmpty());
        } finally { pair[0].close(); pair[1].close(); }
    }

    @Test public void receiverRejectsMalformedDescriptorsBeforeResume() throws Exception {
        byte[] png = png(8, 8, 1); RemoteAppIcon icon = icon(SUB, GENERATION, PACKAGE, png, 8, 8);
        List<JSONObject> offers = Arrays.asList(
                described("icon_offer", icon).put("hash", "../escape"),
                described("icon_offer", icon).put("length", RemoteAppIcon.MAX_BYTES + 1),
                described("icon_offer", icon).put("length", 57.5),
                described("icon_offer", icon).put("width", 129),
                described("icon_offer", icon).put("height", 0),
                described("icon_offer", icon).put("format", "svg"),
                described("icon_offer", icon).put("package", "other.app"),
                described("icon_offer", icon).put("generation", "other_generation"),
                described("icon_offer", icon).put("appIconsVersion", 2));
        for (JSONObject offer : offers) {
            Outcome outcome = scripted(offer, (c, d) -> { throw new AssertionError("Invalid offer admitted a resume"); });
            assertNotNull(offer.toString(), outcome.dom);
            assertFalse(outcome.dom instanceof AssertionError);
        }
    }

    @Test public void receiverRejectsBadHashMagicLengthAndDimensionsAtEnd() throws Exception {
        byte[] valid = png(8, 8, 1), badMagic = valid.clone(); badMagic[0] = 0;
        List<byte[]> payloads = Arrays.asList(valid, badMagic, valid, Arrays.copyOf(valid, valid.length - 1));
        List<RemoteAppIcon> descriptors = Arrays.asList(
                new RemoteAppIcon(SUB, GENERATION, PACKAGE, new RemoteAppIcon.Descriptor(
                        RemoteProtocol.sha256(png(8, 8, 2)), valid.length, 8, 8)),
                icon(SUB, GENERATION, PACKAGE, badMagic, 8, 8),
                icon(SUB, GENERATION, PACKAGE, valid, 7, 8),
                icon(SUB, GENERATION, PACKAGE, valid, 8, 8));
        for (int i = 0; i < payloads.size(); i++) {
            byte[] payload = payloads.get(i); RemoteAppIcon identity = descriptors.get(i);
            Outcome outcome = scripted(described("icon_offer", identity), (c, d) -> {
                sendChunk(c, identity, 0, payload); RemoteProtocol.read(c.input());
                RemoteProtocol.write(c.output(), described("icon_end", identity).put("offset", payload.length));
            });
            assertNotNull(outcome.dom); assertFalse(outcome.dom instanceof AssertionError);
        }
    }

    @Test public void oversizedGapEmptyBadEncodingAndConflictingDuplicateChunksFailClosed() throws Exception {
        byte[] png = png(128, 128, 1); RemoteAppIcon icon = icon(SUB, GENERATION, PACKAGE, png, 128, 128);
        List<JSONObject> chunks = Arrays.asList(
                chunk(icon, 0, new byte[AppIconExchange.MAX_CHUNK_BYTES + 1]),
                chunk(icon, 1, new byte[] {1}), chunk(icon, 0, new byte[0]),
                chunk(icon, 0, new byte[] {1}).put("data", "!invalid!"),
                chunk(icon, 0, new byte[] {1}).put("hash", String.format("%064x", 4)),
                chunk(icon, icon.descriptor.byteLength, new byte[] {1}));
        for (JSONObject frame : chunks) {
            Outcome outcome = scripted(described("icon_offer", icon), (c, d) -> RemoteProtocol.write(c.output(), frame));
            assertNotNull(outcome.dom); assertFalse(outcome.dom instanceof AssertionError);
        }
        Outcome conflict = scripted(described("icon_offer", icon), (c, d) -> {
            sendChunk(c, icon, 0, new byte[] {1}); RemoteProtocol.read(c.input());
            sendChunk(c, icon, 0, new byte[] {2});
        });
        assertTrue(String.valueOf(conflict.dom), conflict.dom instanceof ProtocolException);
    }

    @Test public void exactDuplicateChunksAreAcknowledgedAndCanFinish() throws Exception {
        byte[] png = png(8, 8, 1); RemoteAppIcon icon = icon(SUB, GENERATION, PACKAGE, png, 8, 8);
        AppIconCache cache = domCache(); AppIconExchange receiver = exchange(new Authority(), (p, n) -> null, cache);
        RemoteStore.Peer domPeer = peer(RemoteProtocol.Role.DOM);
        Outcome outcome = duplex((c, d) -> receiver.exchange(c, domPeer, d), (c, d) -> {
            RemoteProtocol.read(c.input()); RemoteProtocol.write(c.output(), described("icon_offer", icon));
            RemoteProtocol.read(c.input());
            for (int duplicate = 0; duplicate < 2; duplicate++) {
                sendChunk(c, icon, 0, png); JSONObject ack = RemoteProtocol.read(c.input());
                assertEquals(png.length, ack.getInt("offset"));
            }
            finish(c, icon, png.length);
        });
        assertNull(outcome.dom); assertNull(outcome.sub); assertArrayEquals(png, cache.load(icon));
    }

    @Test public void interruptedTransferResumesByExactDescriptorAndVerifiedPrefix() throws Exception {
        byte[] png = png(128, 128, 1); RemoteAppIcon icon = icon(SUB, GENERATION, PACKAGE, png, 128, 128);
        AppIconCache cache = domCache(); AppIconExchange receiver = exchange(new Authority(), (p, n) -> null, cache);
        RemoteStore.Peer domPeer = peer(RemoteProtocol.Role.DOM);
        Outcome interrupted = duplex((c, d) -> receiver.exchange(c, domPeer, d), (c, d) -> {
            RemoteProtocol.read(c.input()); RemoteProtocol.write(c.output(), described("icon_offer", icon));
            RemoteProtocol.read(c.input()); sendChunk(c, icon, 0, Arrays.copyOf(png, AppIconExchange.MAX_CHUNK_BYTES));
            RemoteProtocol.read(c.input()); // Close without end: only a verified prefix remains in memory.
        });
        assertTrue(String.valueOf(interrupted.dom), interrupted.dom instanceof IOException); assertNull(cache.load(icon));
        assertEquals(AppIconExchange.MAX_CHUNK_BYTES, cache.resume(cache.snapshot(), icon).length);
        Outcome resumed = duplex((c, d) -> receiver.exchange(c, domPeer, d), (c, d) -> {
            RemoteProtocol.read(c.input()); RemoteProtocol.write(c.output(), described("icon_offer", icon));
            JSONObject resume = RemoteProtocol.read(c.input());
            int offset = resume.getInt("offset"); assertEquals(AppIconExchange.MAX_CHUNK_BYTES, offset);
            assertEquals(RemoteProtocol.sha256(Arrays.copyOf(png, offset)), resume.getString("prefixHash"));
            while (offset < png.length) {
                byte[] bytes = Arrays.copyOfRange(png, offset, Math.min(png.length, offset + AppIconExchange.MAX_CHUNK_BYTES));
                sendChunk(c, icon, offset, bytes); offset += bytes.length; RemoteProtocol.read(c.input());
            }
            finish(c, icon, png.length);
        });
        assertNull(resumed.dom); assertNull(resumed.sub); assertArrayEquals(png, cache.load(icon));
    }

    @Test public void senderRefusesConflictingResumeHashDescriptorAndOffset() throws Exception {
        byte[] png = png(8, 8, 1); Authority sub = new Authority();
        AppIconExchange sender = exchange(sub, (p, n) -> png, cache(temporary.newFolder()));
        RemoteStore.Peer subPeer = peer(RemoteProtocol.Role.SUB);
        for (int mutation = 0; mutation < 3; mutation++) {
            int scenario = mutation;
            Outcome outcome = duplex((c, d) -> {
                RemoteProtocol.write(c.output(), frame("icon_wants").put("packages", new JSONArray().put(PACKAGE)));
                JSONObject offer = RemoteProtocol.read(c.input());
                JSONObject resume = new JSONObject(offer.toString()).put("type", "icon_resume").put("offset", 0)
                        .put("prefixHash", RemoteProtocol.sha256(new byte[0]));
                if (scenario == 0) resume.put("prefixHash", String.format("%064x", 4));
                if (scenario == 1) resume.put("width", 7);
                if (scenario == 2) resume.put("offset", png.length + 1);
                RemoteProtocol.write(c.output(), resume); RemoteProtocol.read(c.input());
            }, (c, d) -> sender.exchange(c, subPeer, d));
            assertTrue(String.valueOf(outcome.sub), outcome.sub instanceof ProtocolException);
        }
    }

    @Test public void conflictingPartialIsDiscardedAfterExplicitRefusalAndNextDuplexCanRecover() throws Exception {
        byte[] png = png(8, 8, 1); RemoteAppIcon icon = icon(SUB, GENERATION, PACKAGE, png, 8, 8);
        AppIconCache cache = domCache(); cache.offered(cache.snapshot(), icon); cache.partial(cache.snapshot(), icon, new byte[] {0});
        AppIconExchange receiver = exchange(new Authority(), (p, n) -> null, cache);
        AppIconExchange sender = exchange(new Authority(), (p, n) -> png, cache(temporary.newFolder()));
        RemoteStore.Peer domPeer = peer(RemoteProtocol.Role.DOM), subPeer = peer(RemoteProtocol.Role.SUB);
        Outcome refused = duplex((c, d) -> receiver.exchange(c, domPeer, d), (c, d) -> sender.exchange(c, subPeer, d));
        assertTrue(String.valueOf(refused.dom), refused.dom instanceof ProtocolException);
        assertTrue(String.valueOf(refused.sub), refused.sub instanceof ProtocolException);
        assertEquals(0, cache.resume(cache.snapshot(), icon).length); assertNull(cache.load(icon));
        Outcome recovered = duplex((c, d) -> receiver.exchange(c, domPeer, d), (c, d) -> sender.exchange(c, subPeer, d));
        assertNull(recovered.dom); assertNull(recovered.sub); assertArrayEquals(png, cache.load(icon));
    }

    @Test public void malformedAndOversizedSenderRastersNeverProduceAnOffer() throws Exception {
        Connection[] eof = pair();
        try {
            eof[0].close();
            assertEquals(-1, eof[1].input().read());
            // END is consumed once; later reads, even through a new stream wrapper, must remain EOF.
            eof[1].deadline = System.nanoTime() - 1;
            assertEquals(-1, eof[1].input().read());
            assertThrows(java.io.EOFException.class, () -> RemoteProtocol.read(eof[1].input()));
        } finally { eof[0].close(); eof[1].close(); }
        for (byte[] png : Arrays.asList(new byte[RemoteAppIcon.MAX_BYTES + 1], new byte[57], png(129, 128, 1))) {
            AppIconExchange sender = exchange(new Authority(), (p, n) -> png, cache(temporary.newFolder()));
            RemoteStore.Peer subPeer = peer(RemoteProtocol.Role.SUB);
            Outcome outcome = duplex((c, d) -> {
                RemoteProtocol.write(c.output(), frame("icon_wants").put("packages", new JSONArray().put(PACKAGE)));
                RemoteProtocol.read(c.input());
            }, (c, d) -> sender.exchange(c, subPeer, d));
            assertNotNull(outcome.sub); assertFalse(outcome.sub instanceof AssertionError);
            assertTrue(outcome.fromSub.isEmpty());
        }
    }

    @Test public void oversizedIconControlFramesAreRefusedWithinGeneralProtocolFrameLimit() throws Exception {
        byte[] png = png(8, 8, 1); RemoteAppIcon icon = icon(SUB, GENERATION, PACKAGE, png, 8, 8);
        char[] padding = new char[40_000]; Arrays.fill(padding, 'x');
        Outcome outcome = scripted(described("icon_offer", icon).put("extra", new String(padding)), (c, d) -> { });
        assertTrue(String.valueOf(outcome.dom), outcome.dom instanceof ProtocolException);
    }

    @Test public void duplicateAndExcessRequestsAreRejectedBeforeRasterization() throws Exception {
        Authority authority = new Authority(); AtomicInteger calls = new AtomicInteger();
        AppIconExchange sender = exchange(authority, (p, n) -> { calls.incrementAndGet(); return null; }, cache(temporary.newFolder()));
        RemoteStore.Peer subPeer = peer(RemoteProtocol.Role.SUB);
        JSONArray excess = new JSONArray(); for (int i = 0; i < 9; i++) excess.put("example.app" + i);
        for (JSONArray packages : Arrays.asList(new JSONArray().put(PACKAGE).put(PACKAGE), excess)) {
            Outcome outcome = duplex((c, d) -> {
                RemoteProtocol.write(c.output(), frame("icon_wants").put("packages", packages)); RemoteProtocol.read(c.input());
            }, (c, d) -> sender.exchange(c, subPeer, d));
            assertTrue(String.valueOf(outcome.sub), outcome.sub instanceof ProtocolException);
        }
        assertEquals(0, calls.get());
    }

    @Test public void eightRequestsSucceedButNinthLocalRequestIsRefused() throws Exception {
        Authority dom = new Authority(), sub = new Authority(); List<String> names = new ArrayList<>();
        List<RemoteAppIcon> refs = new ArrayList<>();
        for (int i = 0; i < 8; i++) { String name = "example.app" + i; names.add(name); refs.add(new RemoteAppIcon(SUB, GENERATION, name, null)); }
        dom.wants = names; sub.wants = names; AppIconCache cache = cache(temporary.newFolder()); cache.publishReferences(refs);
        byte[] png = png(8, 8, 1); AtomicInteger calls = new AtomicInteger();
        AppIconExchange receiver = exchange(dom, (p, n) -> null, cache);
        AppIconExchange sender = exchange(sub, (p, n) -> { calls.incrementAndGet(); return png; }, cache(temporary.newFolder()));
        RemoteStore.Peer domPeer = peer(RemoteProtocol.Role.DOM), subPeer = peer(RemoteProtocol.Role.SUB);
        Outcome outcome = duplex((c, d) -> receiver.exchange(c, domPeer, d), (c, d) -> sender.exchange(c, subPeer, d));
        assertNull(outcome.dom); assertNull(outcome.sub); assertEquals(8, calls.get());
        names.add("example.ninth"); Connection[] pair = pair();
        try {
            assertThrows(ProtocolException.class, () -> receiver.exchange(pair[0], domPeer, System.nanoTime() + TimeUnit.SECONDS.toNanos(1)));
            assertTrue(pair[0].outgoing.isEmpty());
        } finally { pair[0].close(); pair[1].close(); }
    }

    @Test public void duplicateTrafficConsumesByteAndChunkBudgets() throws Exception {
        byte[] png = png(128, 128, 1); RemoteAppIcon icon = icon(SUB, GENERATION, PACKAGE, png, 128, 128);
        Outcome bytes = scripted(described("icon_offer", icon), (c, d) -> {
            byte[] chunk = Arrays.copyOf(png, AppIconExchange.MAX_CHUNK_BYTES);
            for (int i = 0; i < 65; i++) { sendChunk(c, icon, 0, chunk); RemoteProtocol.read(c.input()); }
        });
        assertTrue(String.valueOf(bytes.dom), bytes.dom instanceof ProtocolException);
        Outcome frames = scripted(described("icon_offer", icon), (c, d) -> {
            for (int i = 0; i < 129; i++) { sendChunk(c, icon, 0, new byte[] {png[0]}); RemoteProtocol.read(c.input()); }
        });
        assertTrue(String.valueOf(frames.dom), frames.dom instanceof ProtocolException);
    }

    @Test public void expiredDeadlineWritesNothing() throws Exception {
        AppIconCache cache = domCache(); Authority authority = new Authority();
        AppIconExchange receiver = exchange(authority, (p, n) -> null, cache); RemoteStore.Peer domPeer = peer(RemoteProtocol.Role.DOM);
        Connection[] pair = pair();
        try {
            assertThrows(SocketTimeoutException.class, () -> receiver.exchange(pair[0], domPeer, System.nanoTime() - 1));
            assertTrue(pair[0].outgoing.isEmpty());
        } finally { pair[0].close(); pair[1].close(); }
    }

    @Test public void revokeBetweenChunkAndEndCancelsDeliveryAndClearsPartial() throws Exception {
        byte[] png = png(8, 8, 1); RemoteAppIcon icon = icon(SUB, GENERATION, PACKAGE, png, 8, 8);
        AppIconCache cache = domCache(); Authority authority = new Authority();
        AppIconExchange receiver = exchange(authority, (p, n) -> null, cache); RemoteStore.Peer domPeer = peer(RemoteProtocol.Role.DOM);
        Outcome outcome = duplex((c, d) -> receiver.exchange(c, domPeer, d), (c, d) -> {
            RemoteProtocol.read(c.input()); RemoteProtocol.write(c.output(), described("icon_offer", icon)); RemoteProtocol.read(c.input());
            sendChunk(c, icon, 0, png); RemoteProtocol.read(c.input());
            authority.mirrored = false; cache.publishReferences(Collections.emptyList());
            RemoteProtocol.write(c.output(), described("icon_end", icon).put("offset", png.length));
        });
        assertTrue(String.valueOf(outcome.dom), outcome.dom instanceof SecurityException);
        assertNull(cache.load(icon)); assertFalse(cache.currentAuthority().permits(icon));
        cache.publishReferences(Collections.singleton(icon)); assertEquals(0, cache.resume(cache.snapshot(), icon).length);
    }

    @Test public void senderPublicationRemovalDuringRasterizationCannotOfferPixels() throws Exception {
        Authority authority = new Authority(); byte[] png = png(8, 8, 1);
        AppIconExchange sender = exchange(authority, (p, n) -> { authority.published = false; return png; }, cache(temporary.newFolder()));
        RemoteStore.Peer subPeer = peer(RemoteProtocol.Role.SUB);
        Outcome outcome = duplex((c, d) -> {
            RemoteProtocol.write(c.output(), frame("icon_wants").put("packages", new JSONArray().put(PACKAGE)));
            RemoteProtocol.read(c.input());
        }, (c, d) -> sender.exchange(c, subPeer, d));
        assertTrue(String.valueOf(outcome.sub), outcome.sub instanceof SecurityException);
        assertTrue(String.valueOf(outcome.dom), outcome.dom instanceof IOException);
    }

    @Test public void removeThenRegrantSameReferenceCannotCompleteAnOldInflightTransfer() throws Exception {
        byte[] png = png(8, 8, 1); RemoteAppIcon icon = icon(SUB, GENERATION, PACKAGE, png, 8, 8);
        AppIconCache cache = domCache(); AppIconExchange receiver = exchange(new Authority(), (p, n) -> null, cache);
        RemoteStore.Peer domPeer = peer(RemoteProtocol.Role.DOM);
        Outcome outcome = duplex((c, d) -> receiver.exchange(c, domPeer, d), (c, d) -> {
            RemoteProtocol.read(c.input()); RemoteProtocol.write(c.output(), described("icon_offer", icon)); RemoteProtocol.read(c.input());
            sendChunk(c, icon, 0, png); RemoteProtocol.read(c.input());
            cache.publishReferences(Collections.emptyList()); cache.publishReferences(Collections.singleton(icon));
            RemoteProtocol.write(c.output(), described("icon_end", icon).put("offset", png.length));
        });
        assertTrue(String.valueOf(outcome.dom), outcome.dom instanceof SecurityException); assertNull(cache.load(icon));
        assertEquals(0, cache.resume(cache.snapshot(), icon).length);
    }

    @Test public void explicitRevokeCancellationUnblocksPendingReadWithoutWaitingForDeadline() throws Exception {
        AppIconCache cache = domCache(); Authority authority = new Authority();
        AppIconExchange receiver = exchange(authority, (p, n) -> null, cache); RemoteStore.Peer domPeer = peer(RemoteProtocol.Role.DOM);
        Outcome outcome = duplex((c, d) -> receiver.exchange(c, domPeer, d), (c, d) -> {
            RemoteProtocol.read(c.input());
            authority.active = false; cache.publishReferences(Collections.emptyList()); receiver.cancel(SUB, GENERATION);
        });
        assertNotNull(outcome.dom); assertFalse(outcome.dom instanceof SocketTimeoutException);
        assertNull(cache.snapshot().identity(SUB, GENERATION, PACKAGE));
    }

    private AppIconCache domCache() throws Exception {
        AppIconCache cache = cache(temporary.newFolder()); cache.publishReferences(Collections.singleton(bare())); return cache;
    }
    private Outcome scripted(JSONObject offer, Action afterResume) throws Exception {
        AppIconCache cache = domCache(); AppIconExchange receiver = exchange(new Authority(), (p, n) -> null, cache);
        RemoteStore.Peer domPeer = peer(RemoteProtocol.Role.DOM);
        return duplex((c, d) -> receiver.exchange(c, domPeer, d), (c, d) -> {
            RemoteProtocol.read(c.input()); RemoteProtocol.write(c.output(), offer);
            RemoteProtocol.read(c.input()); afterResume.run(c, d);
        });
    }
    private static JSONObject chunk(RemoteAppIcon icon, int offset, byte[] bytes) throws Exception {
        return described("icon_chunk", icon).put("offset", offset).put("data", Base64.getEncoder().encodeToString(bytes));
    }
    private static void sendChunk(Connection connection, RemoteAppIcon icon, int offset, byte[] bytes) throws Exception {
        RemoteProtocol.write(connection.output(), chunk(icon, offset, bytes));
    }
    private static void finish(Connection connection, RemoteAppIcon icon, int length) throws Exception {
        RemoteProtocol.write(connection.output(), described("icon_end", icon).put("offset", length));
        assertEquals("icon_ack", RemoteProtocol.read(connection.input()).getString("type"));
        RemoteProtocol.write(connection.output(), frame("icons_complete"));
        assertEquals("icons_ack", RemoteProtocol.read(connection.input()).getString("type"));
    }
}
