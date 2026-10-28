package com.subhub.app.tasks;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.remote.*;
import com.subhub.app.security.SubFeaturePolicy;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Exercises framed sessions over connected sockets; the pre-authenticated identity is explicit. */
@RunWith(AndroidJUnit4.class)
public class TaskPeerAndroidTest {
    @Rule public final com.subhub.app.UiAutomationStateRule automation = new com.subhub.app.UiAutomationStateRule();
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private TaskRuntime runtime;
    private RemoteStore store;
    private RemoteStore.Peer domPeer, subPeer;
    private String domId, subId, generation, domScope, subScope;
    private TaskDefinition task;
    private long start;
    private boolean oldTasks;
    @Before public void setup() throws Exception {
        runtime = TaskRuntime.get(context); store = RemoteFeature.get(context).store;
        generation = "tasks_" + UUID.randomUUID();
        domId = RemoteProtocol.sha256(("dom" + generation).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        subId = RemoteProtocol.sha256(("sub" + generation).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        store.getWritableDatabase().execSQL("INSERT INTO peers(peer,generation,name,local_role,state,route) VALUES(?,?,?,'DOM','ACTIVE','')", new Object[]{subId, generation, "Synthetic Sub"});
        store.getWritableDatabase().execSQL("INSERT INTO peers(peer,generation,name,local_role,state,route) VALUES(?,?,?,'SUB','ACTIVE','')", new Object[]{domId, generation, "Synthetic Dom"});
        domPeer = store.peer(subId, generation); subPeer = store.peer(domId, generation);
        domScope = subId + "_" + generation; subScope = domId + "_" + generation;
        oldTasks = context.getSharedPreferences(SubFeaturePolicy.STORE, 0).getBoolean("tasks", true);
        context.getSharedPreferences(SubFeaturePolicy.STORE, 0).edit().putBoolean("tasks", true).commit();
        LocalDateTime local = LocalDateTime.ofInstant(Instant.now().minusSeconds(120), ZoneOffset.UTC).withSecond(0).withNano(0);
        start = local.toInstant(ZoneOffset.UTC).toEpochMilli();
        task = new TaskDefinition(UUID.randomUUID().toString(), "Synthetic task", "Read", local,
                ZoneId.of("UTC"), 3_600_000, Set.of(), true, false, null);
    }
    @After public void cleanup() throws Exception {
        context.getSharedPreferences(SubFeaturePolicy.STORE, 0).edit().putBoolean("tasks", oldTasks).commit();
        for (String scope : List.of(domScope, subScope)) {
            for (String table : new String[]{"projections", "journal", "receipts", "cursors", "effects"})
                runtime.store.getWritableDatabase().execSQL("DELETE FROM " + table + " WHERE scope=?", new Object[]{scope});
            File directory = new File(context.getFilesDir(), "tasks-media/" + scope);
            if (directory.exists()) try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(directory.toPath())) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { java.nio.file.Files.delete(path); } catch (IOException failure) { throw new IllegalStateException(failure); } });
            }
        }
        for (String table : new String[]{"peers", "remote_metadata", "local_objects", "outbox", "remote_objects", "outgoing", "incoming", "commands"})
            store.getWritableDatabase().execSQL("DELETE FROM " + table + " WHERE generation=?", new Object[]{generation});
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        for (android.service.notification.StatusBarNotification notification : manager.getActiveNotifications())
            if (notification.getTag() != null && notification.getTag().startsWith("tasks:" + domScope + ":")) manager.cancel(notification.getTag(), notification.getId());
    }
    private void create() throws Exception {
        runtime.store.append(domScope, new TaskAction("create", task.id, TaskAction.Kind.CREATE, null, start - 1000,
                "", "", List.of(), task), true, false);
    }
    @Test public void newPeersNegotiateTasksOldPeersSkipThemAndReconnectKeepsOneRequestNotification() throws Exception {
        if (android.os.Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .grantRuntimePermission(context.getPackageName(), Manifest.permission.POST_NOTIFICATIONS);
        create(); AtomicInteger calls = new AtomicInteger();
        session(false, calls); assertEquals(0, calls.get()); assertTrue(runtime.store.load(subScope).definitions().isEmpty());
        session(true, calls); assertEquals(2, calls.get()); assertEquals(1, runtime.store.load(subScope).definitions().size());
        TaskAction outside = new TaskAction("outside", task.id, TaskAction.Kind.OUTSIDE, task.firstStart.toLocalDate(),
                System.currentTimeMillis(), "", "", List.of(), null);
        runtime.store.append(subScope, outside, false, false);
        session(true, calls); session(true, calls);
        assertEquals(TaskEngine.Status.PENDING, runtime.store.load(domScope).occurrence(task.id, outside.date, outside.at).status);
        assertTrue(runtime.store.pending(domScope).isEmpty());
        long notifications = Arrays.stream(context.getSystemService(NotificationManager.class).getActiveNotifications())
                .filter(n -> ("tasks:" + domScope + ":outside").equals(n.getTag())).count();
        assertEquals(1, notifications);
    }
    @Test public void localOptoutStopsActionAndAssetTrafficWithoutAdvancingReceiptThenResumes() throws Exception {
        create(); context.getSharedPreferences(SubFeaturePolicy.STORE, 0).edit().putBoolean("tasks", false).commit();
        session(true, new AtomicInteger());
        assertEquals(0, runtime.store.received(subScope)); assertTrue(runtime.store.load(subScope).definitions().isEmpty());
        assertEquals("false", store.metadata(domPeer.identity, generation, "tasks_allowed", ""));
        context.getSharedPreferences(SubFeaturePolicy.STORE, 0).edit().putBoolean("tasks", true).commit();
        session(true, new AtomicInteger());
        assertEquals(1, runtime.store.received(subScope)); assertEquals(1, runtime.store.load(subScope).definitions().size());
        assertEquals("true", store.metadata(domPeer.identity, generation, "tasks_allowed", ""));
    }
    @Test public void attachmentBytesAreHashVerifiedAndStayWithinTheSelectedRelationship() throws Exception {
        byte[] bytes = new byte[RemoteAssets.CHUNK_BYTES + 13]; new Random(77).nextBytes(bytes);
        byte[] header = new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10}; System.arraycopy(header, 0, bytes, 0, header.length);
        String hash = RemoteProtocol.sha256(bytes); RemoteAssets source = runtime.assets(domScope);
        source.reserve(hash, bytes.length);
        source.append(hash, 0, Arrays.copyOf(bytes, RemoteAssets.CHUNK_BYTES));
        source.append(hash, RemoteAssets.CHUNK_BYTES, Arrays.copyOfRange(bytes, RemoteAssets.CHUNK_BYTES, bytes.length)); source.finish(hash);
        task = new TaskDefinition(task.id, task.title, task.text, task.firstStart, task.zone, task.durationMillis,
                task.weekdays, task.allowRefusal, task.failureTribute, new TaskAttachment(hash, "image/png", bytes.length));
        create(); session(true, new AtomicInteger());
        assertTrue(runtime.assets(subScope).isComplete(hash));
        assertEquals(hash, RemoteAssets.digest(runtime.assets(subScope).finish(hash)));
        try (InputStream input = runtime.assets(subScope).open(hash)) {
            ByteArrayOutputStream received = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) received.write(buffer, 0, count);
            assertArrayEquals(bytes, received.toByteArray());
        }
        assertThrows(IOException.class, () -> runtime.assets("local").open(hash));
    }
    @Test public void revocationBlocksPreviouslyQueuedTaskActions() throws Exception {
        create(); store.getWritableDatabase().execSQL("UPDATE peers SET state='REVOKED' WHERE generation=?", new Object[]{generation});
        assertThrows(SecurityException.class, () -> runtime.sweep(new TaskRepository.Target(domScope, domPeer)));
        assertEquals(0, runtime.store.received(subScope));
    }
    private void session(boolean bothSupportTasks, AtomicInteger calls) throws Exception {
        ExecutorService server = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                Socket client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort()); Socket accepted = listener.accept()) {
            PeerTransport.Connection outgoing = connection(client, subId), incoming = connection(accepted, domId);
            Future<?> receiving = server.submit(() -> {
                try {
                    JSONObject hello = RemoteProtocol.read(incoming.input());
                    new RemoteSession(store, transport(subId), hooks(bothSupportTasks, calls)).incoming(incoming, hello, System.nanoTime() + TimeUnit.SECONDS.toNanos(20));
                } catch (Exception failure) { throw new RuntimeException(failure); }
            });
            new RemoteSession(store, transport(domId), hooks(true, calls)).outgoing(outgoing, domPeer, System.nanoTime() + TimeUnit.SECONDS.toNanos(20));
            receiving.get(25, TimeUnit.SECONDS);
        } finally { server.shutdownNow(); assertTrue(server.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    private RemoteSession.Hooks hooks(boolean support, AtomicInteger calls) {
        return new RemoteSession.Hooks() {
            @Override public int tasksVersion() { return support ? 1 : 0; }
            @Override public void tasks(PeerTransport.Connection connection, RemoteStore.Peer peer, long deadline) throws Exception {
                calls.incrementAndGet(); TaskPeer.exchange(context, connection, peer, deadline);
            }
            @Override public void prepare(RemoteStore.Peer peer) { }
            @Override public void received(RemoteStore.Peer peer, RemoteProtocol.Stream stream) { }
            @Override public List<RemoteSession.AssetNeed> missingAssets(RemoteStore.Peer peer) { return List.of(); }
            @Override public RemoteAssets assets(RemoteStore.Peer peer) { return null; }
            @Override public void complete(RemoteStore.Peer peer) { }
        };
    }
    private static PeerTransport transport(String identity) {
        return new PeerTransport() {
            @Override public String localIdentity() { return identity; }
            @Override public String inviteRoute() { return ""; }
            @Override public Connection connect(String peer, String route, long timeout) throws IOException { throw new IOException("Fixture only"); }
            @Override public Connection accept(long timeout) throws IOException { throw new IOException("Fixture only"); }
            @Override public void close() { }
        };
    }
    private static PeerTransport.Connection connection(Socket socket, String remote) {
        return new PeerTransport.Connection() {
            @Override public String authenticatedPeerIdentity() { return remote; }
            @Override public InputStream input() throws IOException { return socket.getInputStream(); }
            @Override public OutputStream output() throws IOException { return socket.getOutputStream(); }
            @Override public boolean isDirect() { return true; }
            @Override public void setReadTimeoutMillis(long timeout) { try { socket.setSoTimeout((int) timeout); } catch (Exception failure) { throw new IllegalStateException(failure); } }
            @Override public void close() throws IOException { socket.close(); }
        };
    }
}
