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
    @Rule public final com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture();
    private final Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private Context context;
    private String prefix;
    private File fixtureFiles;
    private final Set<String> preferenceNames = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> databaseNames = Collections.synchronizedSet(new HashSet<>());
    private TaskRuntime runtime;
    private RemoteStore store;
    private RemoteStore.Peer domPeer, subPeer;
    private String domId, subId, generation, domScope, subScope;
    private final Set<String> ownedScopes = new LinkedHashSet<>();
    private TaskDefinition task;
    private long start;
    private boolean cancelSupport = true;
    @Before public void setup() throws Exception {
        prefix = "task-peer-fixture-" + UUID.randomUUID() + "-";
        fixtureFiles = new File(target.getFilesDir(), prefix);
        assertTrue(fixtureFiles.mkdir());
        context = new android.content.ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return fixtureFiles; }
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                String owned = prefix + name; preferenceNames.add(owned);
                return target.getSharedPreferences(owned, mode);
            }
            private String databaseName(String name) { String owned = prefix + name; databaseNames.add(owned); return owned; }
            @Override public File getDatabasePath(String name) { return target.getDatabasePath(databaseName(name)); }
            @Override public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(String name, int mode,
                    android.database.sqlite.SQLiteDatabase.CursorFactory factory) {
                return target.openOrCreateDatabase(databaseName(name), mode, factory);
            }
            @Override public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(String name, int mode,
                    android.database.sqlite.SQLiteDatabase.CursorFactory factory, android.database.DatabaseErrorHandler handler) {
                return target.openOrCreateDatabase(databaseName(name), mode, factory, handler);
            }
        };
        assertTrue(com.subhub.app.security.ControllerPinManager.setPin(context, "2468"));
        store = new RemoteStore(context, "peer.db");
        java.lang.reflect.Constructor<TaskRuntime> constructor = TaskRuntime.class.getDeclaredConstructor(Context.class, RemoteStore.class);
        constructor.setAccessible(true); runtime = constructor.newInstance(context, store);
        domId = RemoteProtocol.sha256(("dom" + prefix).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        subId = RemoteProtocol.sha256(("sub" + prefix).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        pair();
        context.getSharedPreferences(SubFeaturePolicy.STORE, 0).edit().putBoolean("tasks", true).commit();
        LocalDateTime local = LocalDateTime.ofInstant(Instant.now().minusSeconds(120), ZoneOffset.UTC).withSecond(0).withNano(0);
        start = local.toInstant(ZoneOffset.UTC).toEpochMilli();
        task = new TaskDefinition(UUID.randomUUID().toString(), "Synthetic task", "Read", local,
                ZoneId.of("UTC"), 3_600_000, Set.of(), true, false, null);
    }
    @After public void cleanup() throws Throwable {
        Throwable failure = null;
        boolean[] drained = {runtime == null};
        failure = cleanupStep(failure, () -> {
            if (runtime == null) return;
            java.lang.reflect.Field executor = TaskRuntime.class.getDeclaredField("executor"); executor.setAccessible(true);
            ScheduledExecutorService worker = (ScheduledExecutorService) executor.get(runtime);
            worker.shutdown(); drained[0] = worker.awaitTermination(10, TimeUnit.SECONDS);
            assertTrue("Keep owned fixture files if its worker has not drained", drained[0]);
        });
        failure = cleanupStep(failure, () -> {
            if (!drained[0]) return;
            // Cancel admitted keys too: Android may not list a post immediately after accepting it.
            if (runtime != null) try (android.database.Cursor rows = runtime.store.getReadableDatabase().rawQuery(
                    "SELECT scope,id FROM effects WHERE kind='notification'", null)) {
                while (rows.moveToNext()) {
                    if (!ownedScopes.contains(rows.getString(0))) continue;
                    com.subhub.app.notifications.OptionalNotificationDispatch.cancelExact(context,
                            com.subhub.app.notifications.NotificationPreferences.Role.DOM,
                            com.subhub.app.notifications.NotificationPreferences.Event.TASK_REVIEW,
                            "tasks:" + rows.getString(0) + ":" + rows.getString(1), 1);
                }
            }
        });
        failure = cleanupStep(failure, () -> {
            if (!drained[0]) return;
            NotificationManager manager = target.getSystemService(NotificationManager.class);
            for (android.service.notification.StatusBarNotification notification : manager.getActiveNotifications()) {
                String tag = notification.getTag();
                if (tag != null) for (String scope : ownedScopes)
                    if (tag.startsWith("tasks:" + scope + ":")) manager.cancel(tag, notification.getId());
            }
        });
        failure = cleanupStep(failure, () -> { if (drained[0] && runtime != null) runtime.store.close(); });
        failure = cleanupStep(failure, () -> { if (drained[0] && store != null) store.close(); });
        if (drained[0]) {
            for (String name : new HashSet<>(databaseNames)) failure = cleanupStep(failure, () -> target.deleteDatabase(name));
            for (String name : new HashSet<>(preferenceNames)) failure = cleanupStep(failure, () -> target.deleteSharedPreferences(name));
        }
        failure = cleanupStep(failure, () -> {
            if (drained[0] && fixtureFiles != null && fixtureFiles.exists()) {
                assertEquals(target.getFilesDir().getCanonicalFile(), fixtureFiles.getCanonicalFile().getParentFile());
                try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(fixtureFiles.toPath())) {
                    for (java.nio.file.Path path : (Iterable<java.nio.file.Path>) files.sorted(Comparator.reverseOrder())::iterator)
                        java.nio.file.Files.delete(path);
                }
            }
        });
        com.subhub.app.security.ControllerPinManager.initialize(target);
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
    private static Object invoke(Object receiver, java.lang.reflect.Method method, Object... args) throws Exception {
        try { return method.invoke(receiver, args); }
        catch (java.lang.reflect.InvocationTargetException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Error) throw (Error) cause;
            if (cause instanceof Exception) throw (Exception) cause;
            throw new AssertionError(cause);
        }
    }
    private Object peerMethod(String name, Class<?>[] types, Object... args) throws Exception {
        java.lang.reflect.Method method = RemoteStore.class.getDeclaredMethod(name, types); method.setAccessible(true);
        return invoke(store, method, args);
    }
    private void pair() throws Exception {
        long now = System.currentTimeMillis(); PairingInvite invite = PairingInvite.create(domId, "fixture", now);
        generation = invite.id;
        peerMethod("saveInvite", new Class<?>[]{PairingInvite.class}, invite);
        peerMethod("bindInvite", new Class<?>[]{String.class, String.class, String.class, long.class}, generation, subId, invite.tokenHash(), now + 1);
        peerMethod("approveInvite", new Class<?>[]{String.class, String.class, String.class, long.class}, generation, subId, "Synthetic Sub", now + 1);
        peerMethod("acceptInvite", new Class<?>[]{PairingInvite.class, String.class}, invite, "Synthetic Dom");
        peerMethod("activate", new Class<?>[]{String.class, String.class}, subId, generation);
        peerMethod("activate", new Class<?>[]{String.class, String.class}, domId, generation);
        domPeer = store.peer(subId, generation); subPeer = store.peer(domId, generation);
        domScope = subId + "_" + generation; subScope = domId + "_" + generation;
        ownedScopes.add(domScope); ownedScopes.add(subScope);
    }
    private TaskRepository repository() throws Exception {
        java.lang.reflect.Constructor<TaskRepository> constructor = TaskRepository.class.getDeclaredConstructor(
                Context.class, com.subhub.app.people.PersonId.class, RemoteStore.class, TaskRuntime.class);
        constructor.setAccessible(true); return constructor.newInstance(context, RemotePersonRepository.id(domPeer), store, runtime);
    }
    private void exchangeTasks(PeerTransport.Connection connection, RemoteStore.Peer peer, long deadline) throws Exception {
        java.lang.reflect.Constructor<TaskPeer> constructor = TaskPeer.class.getDeclaredConstructor(
                TaskRuntime.class, PeerTransport.Connection.class, RemoteStore.Peer.class, long.class);
        constructor.setAccessible(true);
        TaskPeer selected = constructor.newInstance(runtime, connection, peer, deadline);
        java.lang.reflect.Method exchange = TaskPeer.class.getDeclaredMethod("exchange"); exchange.setAccessible(true);
        invoke(selected, exchange);
    }
    private void create() throws Exception {
        runtime.store.append(domScope, new TaskAction("create", task.id, TaskAction.Kind.CREATE, null, start - 1000,
                "", "", List.of(), task), true, false);
    }
    @Test public void newPeersNegotiateTasksOldPeersSkipThemAndReconnectKeepsOneRequestNotification() throws Exception {
        if (android.os.Build.VERSION.SDK_INT >= 33) assertEquals(
                "The coordinator must grant notification permission before this OS-delivery fixture",
                android.content.pm.PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS));
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
    @Test public void offlineCancellationWinsLateProofAndLegacyPeerNeverReceivesUnknownActions() throws Exception {
        create(); session(true, new AtomicInteger());
        assertEquals("1", store.metadata(domPeer.identity, generation, "tasks_cancel_version", "0"));
        TaskAction proof = new TaskAction("offline-proof", task.id, TaskAction.Kind.OUTSIDE,
                task.firstStart.toLocalDate(), System.currentTimeMillis(), "", "", List.of(), null);
        runtime.store.append(subScope, proof, false, false);
        TaskAction cancel = new TaskAction("cancel", task.id, TaskAction.Kind.CANCEL_ONCE,
                proof.date, System.currentTimeMillis(), "", "", List.of(), null);
        runtime.store.append(domScope, cancel, true, false);
        cancelSupport = false; session(true, new AtomicInteger());
        assertEquals("0", store.metadata(domPeer.identity, generation, "tasks_cancel_version", "unexpected"));
        assertEquals(TaskEngine.Status.PENDING, runtime.store.load(subScope).occurrence(task.id, proof.date, proof.at).status);
        assertEquals(1, runtime.store.outgoing(domScope, runtime.store.received(subScope)).length());
        cancelSupport = true; session(true, new AtomicInteger()); session(true, new AtomicInteger());
        for (String scope : List.of(domScope, subScope)) {
            TaskEngine engine = runtime.store.load(scope);
            assertEquals(TaskEngine.Status.CANCELLED, engine.occurrence(task.id, proof.date, proof.at).status);
            assertNull(engine.occurrence(task.id, proof.date, proof.at).submission);
        }
        assertTrue(runtime.store.pending(domScope).isEmpty()); assertTrue(runtime.store.pending(subScope).isEmpty());
        assertEquals(0, TaskEngine.activeCount(runtime.store.load(domScope).recorded(System.currentTimeMillis())));
    }

    @Test public void reviewedHistoryRetainsRequestAndEntryTimesAcrossPeerReconnect() throws Exception {
        create();
        TaskDefinition older = new TaskDefinition(UUID.randomUUID().toString(), "Older synthetic task", "Read",
                task.firstStart.minusMinutes(30), task.zone, task.durationMillis, Set.of(), true, false, null);
        long olderStart = older.start(older.firstStart.toLocalDate()).toEpochMilli();
        runtime.store.append(domScope, new TaskAction("create-older", older.id, TaskAction.Kind.CREATE, null,
                olderStart - 1000, "", "", List.of(), older), true, false);
        session(true, new AtomicInteger());
        TaskAction first = new TaskAction("outside", task.id, TaskAction.Kind.OUTSIDE, task.firstStart.toLocalDate(),
                start + 1000, "", "", List.of(), null);
        TaskAction second = new TaskAction("older-proof", older.id, TaskAction.Kind.SUBMIT, older.firstStart.toLocalDate(),
                olderStart + 1000, "Synthetic proof", "", List.of(), null);
        runtime.store.append(subScope, first, false, false);
        runtime.store.append(subScope, second, false, false);
        session(true, new AtomicInteger());
        TaskAction approved = new TaskAction("approve-older", older.id, TaskAction.Kind.ACCEPT, second.date,
                start + 40_000, "", second.id, List.of(), null);
        TaskAction declined = new TaskAction("decline-newer", task.id, TaskAction.Kind.DENY, first.date,
                start + 30_000, "", first.id, List.of(), null);
        runtime.store.append(domScope, approved, true, false);
        runtime.store.append(domScope, declined, true, false);
        session(true, new AtomicInteger());
        session(true, new AtomicInteger());
        try (TaskStore reopened = new TaskStore(context)) {
            for (String scope : List.of(domScope, subScope)) {
                List<TaskEngine.Occurrence> rows = reopened.load(scope).recorded(System.currentTimeMillis());
                rows.sort(TaskEngine.LIST_ORDER);
                assertEquals(2, rows.size());
                assertEquals(older.id, rows.get(0).task.id);
                assertEquals(TaskEngine.Status.ACCEPTED, rows.get(0).status);
                assertEquals(approved.at, rows.get(0).historyEnteredAt);
                assertEquals(second.at, rows.get(0).submission.at);
                assertEquals(task.id, rows.get(1).task.id);
                assertEquals(TaskEngine.Status.DENIED, rows.get(1).status);
                assertEquals(declined.at, rows.get(1).historyEnteredAt);
                assertEquals(first.at, rows.get(1).submission.at);
            }
        }
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
    @Test public void authenticatedSubTimezoneIsReceivedRefreshedAndWithdrawnOnOptout() throws Exception {
        java.util.TimeZone previous = java.util.TimeZone.getDefault();
        boolean wasDom = com.subhub.app.security.ControllerPinManager.isDomModeActive();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tokyo"));
            session(true, new AtomicInteger());
            assertEquals("Asia/Tokyo", store.metadata(subId, generation, "tasks_timezone", ""));
            long received = Long.parseLong(store.metadata(subId, generation, "tasks_timezone_received", "0"));
            assertEquals(ZoneId.of("Asia/Tokyo"), TaskTimezone.require("Asia/Tokyo", received, System.currentTimeMillis()));
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Los_Angeles"));
            com.subhub.app.security.ControllerPinManager.enterDomMode();
            TaskRepository repository = repository();
            assertEquals(ZoneId.of("Asia/Tokyo"), repository.schedulingZone());
            store.taskTimezoneReceived(subId, generation, "Asia/Tokyo", 1);
            assertThrows(IllegalStateException.class, repository::schedulingZone);
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"));
            session(true, new AtomicInteger());
            assertEquals("America/New_York", store.metadata(subId, generation, "tasks_timezone", ""));
            TaskDefinition created = new TaskDefinition(UUID.randomUUID().toString(), "Future synthetic task", "",
                    LocalDateTime.now(repository.schedulingZone()).plusHours(2).withSecond(0).withNano(0),
                    repository.schedulingZone(), 60_000, Set.of(), true, false, null);
            repository.create(created);
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tokyo"));
            session(true, new AtomicInteger());
            assertEquals(ZoneId.of("Asia/Tokyo"), repository.schedulingZone());
            TaskDefinition retained = repository.engine().definitions().get(0);
            assertEquals(ZoneId.of("America/New_York"), retained.zone);
            assertEquals(created.occurrenceId(created.firstStart.toLocalDate()), retained.occurrenceId(retained.firstStart.toLocalDate()));
            assertEquals(created.end(created.firstStart.toLocalDate()), retained.end(retained.firstStart.toLocalDate()));
            context.getSharedPreferences(SubFeaturePolicy.STORE, 0).edit().putBoolean("tasks", false).commit();
            session(true, new AtomicInteger());
            assertEquals("", store.metadata(subId, generation, "tasks_timezone", "unexpected"));
            assertEquals("0", store.metadata(subId, generation, "tasks_timezone_received", "unexpected"));
        } finally {
            java.util.TimeZone.setDefault(previous);
            if (wasDom) com.subhub.app.security.ControllerPinManager.enterDomMode();
            else com.subhub.app.security.ControllerPinManager.enterSubMode();
        }
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
        create();
        peerMethod("revoke", new Class<?>[]{String.class, String.class}, subId, generation);
        peerMethod("revoke", new Class<?>[]{String.class, String.class}, domId, generation);
        assertThrows(SecurityException.class, () -> runtime.sweep(new TaskRepository.Target(domScope, domPeer)));
        assertEquals(0, runtime.store.received(subScope));
    }
    @Test public void irohTasksProofMediaAndReviewNegotiateAlongsideActivity() throws Exception {
        byte[] domSeed = new byte[32], subSeed = new byte[32];
        new java.security.SecureRandom().nextBytes(domSeed); new java.security.SecureRandom().nextBytes(subSeed);
        ExecutorService serving = Executors.newSingleThreadExecutor();
        try (PeerTransport domTransport = IrohPeerTransport.open(context, domSeed, new String[0], 10_000);
                PeerTransport subTransport = IrohPeerTransport.open(context, subSeed, new String[0], 10_000)) {
            peerMethod("revoke", new Class<?>[]{String.class, String.class}, subId, generation);
            peerMethod("revoke", new Class<?>[]{String.class, String.class}, domId, generation);
            domId = domTransport.localIdentity(); subId = subTransport.localIdentity();
            pair();
            create(); AtomicInteger calls = new AtomicInteger();
            irohSession(domTransport, subTransport, serving, calls);
            byte[] image = android.util.Base64.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", android.util.Base64.DEFAULT);
            TaskAttachment proof = new TaskAttachment(RemoteProtocol.sha256(image), "image/gif", image.length);
            RemoteAssets source = runtime.assets(subScope); source.reserve(proof.hash, proof.length);
            source.append(proof.hash, 0, image); source.finish(proof.hash);
            TaskAction submitted = new TaskAction("iroh-proof", task.id, TaskAction.Kind.SUBMIT, task.firstStart.toLocalDate(),
                    System.currentTimeMillis(), "Completed", "", List.of(proof), null);
            runtime.store.append(subScope, submitted, false, false);
            irohSession(domTransport, subTransport, serving, calls);
            TaskEngine.Occurrence pending = runtime.store.load(domScope).occurrence(task.id, submitted.date, submitted.at);
            assertEquals(TaskEngine.Status.PENDING, pending.status); assertEquals(submitted.id, pending.submission.id);
            assertTrue(runtime.assets(domScope).isComplete(proof.hash));
            assertEquals(proof.hash, RemoteAssets.digest(runtime.assets(domScope).finish(proof.hash)));
            runtime.store.append(domScope, new TaskAction("iroh-accept", task.id, TaskAction.Kind.ACCEPT, submitted.date,
                    System.currentTimeMillis(), "", submitted.id, List.of(), null), true, false);
            irohSession(domTransport, subTransport, serving, calls); irohSession(domTransport, subTransport, serving, calls);
            assertEquals(TaskEngine.Status.ACCEPTED, runtime.store.load(subScope).occurrence(task.id, submitted.date, submitted.at).status);
            assertEquals(8, calls.get());
            RemoteProtocol.Stream[] monitoring = {RemoteProtocol.Stream.STATS, RemoteProtocol.Stream.DAILY,
                    RemoteProtocol.Stream.APP_USAGE, RemoteProtocol.Stream.RUNTIME, RemoteProtocol.Stream.EVENTS};
            long observed = System.currentTimeMillis();
            for (RemoteProtocol.Stream stream : monitoring) {
                JSONObject body = new JSONObject().put("schema", 1).put("synthetic", true);
                if (stream == RemoteProtocol.Stream.EVENTS) body = new JSONObject().put("schema", 1).put("id", "synthetic_event")
                        .put("kind", "APP_OPENED").put("observedAt", observed).put("packageName", "com.example.synthetic")
                        .put("amountCents", 0).put("currency", "").put("reason", "").put("limitMinutes", 0)
                        .put("zone", "UTC").put("day", Instant.ofEpochMilli(observed).atZone(ZoneId.of("UTC")).toLocalDate().toString())
                        .put("configuration", "");
                store.publish(domId, generation, stream, stream == RemoteProtocol.Stream.EVENTS ? "synthetic_event" : "synthetic",
                        body, false, observed);
            }
            irohSession(domTransport, subTransport, serving, calls);
            irohSession(domTransport, subTransport, serving, calls);
            for (RemoteProtocol.Stream stream : monitoring) {
                assertEquals(1, store.published(domId, generation, stream).size());
                assertEquals(1, store.mirror(subId, generation, stream).size());
                assertTrue(store.nextBatch(domId, generation, stream).changes.isEmpty());
                assertEquals(1, store.nextBatch(domId, generation, stream).after);
            }
            try (android.database.Cursor rows = store.getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM activity_receipts WHERE peer=? AND generation=?", new String[]{subId, generation})) {
                assertTrue(rows.moveToFirst()); assertEquals(1, rows.getInt(0));
            }
        } finally {
            Arrays.fill(domSeed, (byte) 0); Arrays.fill(subSeed, (byte) 0);
            serving.shutdownNow(); assertTrue(serving.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
    private void irohSession(PeerTransport domTransport, PeerTransport subTransport, ExecutorService server, AtomicInteger calls) throws Exception {
        Future<?> incoming = server.submit(() -> {
            try (PeerTransport.Connection connection = subTransport.accept(10_000)) {
                JSONObject hello = RemoteProtocol.read(connection.input());
                assertEquals(domId, connection.authenticatedPeerIdentity());
                assertEquals(2, hello.getInt("activityVersion")); assertEquals(1, hello.getInt("tasksVersion"));
                new RemoteSession(store, subTransport, hooks(true, calls)).incoming(connection, hello, System.nanoTime() + TimeUnit.SECONDS.toNanos(25));
            } catch (Exception failure) { throw new RuntimeException(failure); }
        });
        try (PeerTransport.Connection connection = domTransport.connect(subId, subTransport.inviteRoute(), 10_000)) {
            new RemoteSession(store, domTransport, hooks(true, calls)).outgoing(connection, domPeer, System.nanoTime() + TimeUnit.SECONDS.toNanos(25));
        }
        incoming.get(30, TimeUnit.SECONDS);
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
            @Override public int taskCancelVersion() { return support && cancelSupport ? 1 : 0; }
            @Override public void tasks(PeerTransport.Connection connection, RemoteStore.Peer peer, long deadline) throws Exception {
                calls.incrementAndGet(); exchangeTasks(connection, peer, deadline);
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
