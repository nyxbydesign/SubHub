package com.subhub.app.journal;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.people.PersonId;
import com.subhub.app.remote.IrohPeerTransport;
import com.subhub.app.remote.PairingInvite;
import com.subhub.app.remote.PeerTransport;
import com.subhub.app.remote.RemoteAssets;
import com.subhub.app.remote.RemotePersonRepository;
import com.subhub.app.remote.RemoteProtocol;
import com.subhub.app.remote.RemoteSession;
import com.subhub.app.remote.RemoteStore;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class JournalPeerAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private PeerTransport dt, st;
    private RemoteStore dom, sub;
    private JournalRuntime dr, sr;
    private RemoteStore.Peer dp, sp;
    private File root;
    private String dn, sn, dj, sj, generation;
    private ExecutorService serving;
    private final AtomicInteger calls = new AtomicInteger();
    @Before public void setup() throws Exception {
        String suffix = UUID.randomUUID().toString();
        dn = "journal-dom-peer-" + suffix; sn = "journal-sub-peer-" + suffix;
        dj = "journal-dom-data-" + suffix; sj = "journal-sub-data-" + suffix;
        root = new File(context.getCacheDir(), "journal-peer-" + suffix); assertTrue(root.mkdir());
        dom = new RemoteStore(context, dn); sub = new RemoteStore(context, sn);
        dt = IrohPeerTransport.open(context, seed(), new String[0], 10_000);
        st = IrohPeerTransport.open(context, seed(), new String[0], 10_000);
        dr = new JournalRuntime(context, dom, dj, new File(root, "dom"));
        sr = new JournalRuntime(context, sub, sj, new File(root, "sub"));
        pair(); serving = Executors.newSingleThreadExecutor();
    }
    private static byte[] seed() { byte[] seed = new byte[32]; new SecureRandom().nextBytes(seed); return seed; }
    private void pair() throws Exception {
        PairingInvite invite = PairingInvite.create(dt.localIdentity(), dt.inviteRoute(), System.currentTimeMillis());
        generation = invite.id;
        dom.getWritableDatabase().execSQL("INSERT INTO peers(peer,generation,name,local_role,state,route) VALUES(?,?,?,'DOM','ACTIVE',?)",
                new Object[]{st.localIdentity(), generation, "Synthetic Sub", st.inviteRoute()});
        sub.getWritableDatabase().execSQL("INSERT INTO peers(peer,generation,name,local_role,state,route) VALUES(?,?,?,'SUB','ACTIVE',?)",
                new Object[]{dt.localIdentity(), generation, "Synthetic Dom", dt.inviteRoute()});
        dp = dom.peer(st.localIdentity(), generation); sp = sub.peer(dt.localIdentity(), generation);
    }
    private static JournalEntry entry(String text, JournalAttachment attachment) {
        return new JournalEntry(UUID.randomUUID().toString().replace("-", ""), 100, 101, text, null,
                attachment == null ? List.of() : List.of(attachment));
    }
    private JournalAttachment media(long length) throws Exception {
        File source = new File(root, "source.bin"); Random random = new Random(42);
        try (FileOutputStream output = new FileOutputStream(source)) {
            byte[] buffer = new byte[8192]; long left = length;
            while (left > 0) { random.nextBytes(buffer); int count = (int) Math.min(left, buffer.length); output.write(buffer, 0, count); left -= count; }
        }
        String hash = RemoteAssets.digest(source); RemoteAssets assets = dr.ownAssets(dp); assets.reserve(hash, length);
        try (RandomAccessFile input = new RandomAccessFile(source, "r")) {
            long offset = 0;
            while (offset < length) { byte[] buffer = new byte[(int) Math.min(RemoteAssets.CHUNK_BYTES, length - offset)];
                input.readFully(buffer); offset = assets.append(hash, offset, buffer); }
        }
        assets.finish(hash); return new JournalAttachment(hash, "Synthetic.bin", "application/octet-stream", length);
    }
    private RemoteSession.Hooks hooks(JournalRuntime runtime, boolean supported) throws Exception {
        RemoteAssets empty = new RemoteAssets(new File(root, runtime == dr ? "empty-dom" : "empty-sub"));
        return new RemoteSession.Hooks() {
            public int journalVersion() { return supported ? 1 : 0; }
            public void journal(PeerTransport.Connection connection, RemoteStore.Peer peer, long deadline) throws Exception {
                calls.incrementAndGet(); JournalPeer.exchange(runtime, connection, peer, deadline);
            }
            public void prepare(RemoteStore.Peer peer) { }
            public void received(RemoteStore.Peer peer, RemoteProtocol.Stream stream) { }
            public List<RemoteSession.AssetNeed> missingAssets(RemoteStore.Peer peer) { return List.of(); }
            public RemoteAssets assets(RemoteStore.Peer peer) { return empty; }
            public void complete(RemoteStore.Peer peer) { }
        };
    }
    private void session(boolean supported) throws Exception {
        RemoteSession.Hooks dh = hooks(dr, true), sh = hooks(sr, supported);
        Future<?> receiver = serving.submit(() -> {
            try (PeerTransport.Connection connection = st.accept(10_000)) {
                JSONObject hello = RemoteProtocol.read(connection.input());
                assertEquals(dt.localIdentity(), connection.authenticatedPeerIdentity());
                new RemoteSession(sub, st, sh).incoming(connection, hello, System.nanoTime() + TimeUnit.SECONDS.toNanos(30));
            } catch (Exception failure) { throw new RuntimeException(failure); }
        });
        try (PeerTransport.Connection connection = dt.connect(st.localIdentity(), st.inviteRoute(), 10_000)) {
            new RemoteSession(dom, dt, dh).outgoing(connection, dp, System.nanoTime() + TimeUnit.SECONDS.toNanos(30));
        }
        receiver.get(35, TimeUnit.SECONDS);
    }
    @Test public void realIrohNegotiationSkipsLegacyAndCarriesOnlyExplicitlySharedEntriesBidirectionally() throws Exception {
        JournalEntry privateEntry = entry("Private Dom entry", null), published = entry("Shared Dom entry", null), fromSub = entry("Shared Sub entry", null);
        dr.entries.save(true, privateEntry, Set.of()); dr.entries.save(true, published, Set.of(JournalRuntime.scope(dp)));
        sr.entries.save(false, fromSub, Set.of(JournalRuntime.scope(sp)));
        session(false); assertEquals(0, calls.get()); assertTrue(sr.entries.shared(JournalRuntime.scope(sp), 0).isEmpty());
        session(true); session(true);
        assertEquals(1, sr.entries.shared(JournalRuntime.scope(sp), 0).size());
        assertEquals(published.id, sr.entries.shared(JournalRuntime.scope(sp), 0).get(0).id);
        assertEquals(fromSub.id, dr.entries.shared(JournalRuntime.scope(dp), 0).get(0).id);
        assertNull(sr.entries.entry(false, JournalRuntime.scope(sp), privateEntry.id));
        assertEquals("1", dom.metadata(dp.identity, generation, "journal_version", ""));
        assertEquals(0, sub.mirror(sp.identity, generation, RemoteProtocol.Stream.EVENTS).size());
    }
    @Test public void mediaResumesAcrossBoundedSessionsAndWithdrawalRemovesRemoteReferenceAndBytes() throws Exception {
        JournalAttachment file = media(64L * RemoteAssets.CHUNK_BYTES + 123);
        JournalEntry entry = entry("Shared file", file); dr.entries.save(true, entry, Set.of(JournalRuntime.scope(dp)));
        session(true); assertFalse(sr.assets(sp).isComplete(file.hash)); assertEquals(64L * RemoteAssets.CHUNK_BYTES, sr.assets(sp).offset(file.hash));
        session(true); assertTrue(sr.assets(sp).isComplete(file.hash)); assertEquals(file.hash, RemoteAssets.digest(sr.assets(sp).finish(file.hash)));
        dr.entries.save(true, entry, Set.of()); session(true);
        assertTrue(sr.entries.shared(JournalRuntime.scope(sp), 0).isEmpty());
        assertFalse(new File(root, "sub/" + JournalRuntime.scope(sp) + "/" + file.hash + ".blob").exists());
    }
    @Test public void completePartialFileIsVerifiedBeforeConstructingAResumeRequest() throws Exception {
        JournalAttachment file = media(1234); JournalEntry entry = entry("Shared file", file);
        dr.entries.save(true, entry, Set.of(JournalRuntime.scope(dp)));
        RemoteAssets source = dr.ownAssets(dp), destination = sr.assets(sp);
        destination.reserve(file.hash, file.length); destination.append(file.hash, 0, source.chunk(file.hash, 0));
        assertFalse(destination.isComplete(file.hash)); session(true); assertTrue(destination.isComplete(file.hash));
    }
    @Test public void revocationAndRepairCannotInheritPreviousJournalAudienceOrCachedView() throws Exception {
        JournalEntry entry = entry("Old generation only", null); dr.entries.save(true, entry, Set.of(JournalRuntime.scope(dp)));
        session(true); PersonId oldTarget = RemotePersonRepository.id(sp);
        dom.getWritableDatabase().execSQL("UPDATE peers SET state='REVOKED' WHERE generation=?", new Object[]{generation});
        sub.getWritableDatabase().execSQL("UPDATE peers SET state='REVOKED' WHERE generation=?", new Object[]{generation});
        assertThrows(SecurityException.class, () -> sr.peer(oldTarget, false));
        pair(); session(true);
        assertTrue(sr.entries.shared(JournalRuntime.scope(sp), 0).isEmpty());
        assertEquals(0, dr.entries.outgoing(JournalRuntime.scope(dp), 0).length());
    }
    @After public void cleanup() throws Exception {
        if (serving != null) serving.shutdownNow();
        if (dt != null) dt.close(); if (st != null) st.close();
        if (serving != null) serving.awaitTermination(5, TimeUnit.SECONDS);
        if (dr != null) dr.entries.close(); if (sr != null) sr.entries.close();
        if (dom != null) dom.close(); if (sub != null) sub.close();
        for (String database : new String[]{dn, sn, dj, sj}) if (database != null) context.deleteDatabase(database);
        if (root != null && root.exists()) {
            assertTrue(root.getCanonicalPath().startsWith(context.getCacheDir().getCanonicalPath() + File.separator));
            try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(root.toPath())) {
                for (java.nio.file.Path path : paths.sorted(Comparator.reverseOrder()).toArray(java.nio.file.Path[]::new)) {
                    assertFalse(Files.isSymbolicLink(path)); Files.delete(path);
                }
            }
        }
    }
}
