package com.subhub.app.remote;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Application;
import android.app.UiAutomation;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.work.NetworkType;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import com.subhub.app.UiAutomationStateRule;
import com.subhub.app.configuration.LegacyPackMigration;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.Totp;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.model.MultipleFailureException;

/** Real scheduled WorkManager background exchange; independent of UI, pairing and session-control setup.
 * OS job observations corroborate scheduling, without attributing Worker launch exclusively to the OS.
 */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 31)
public final class RemoteBackgroundPeerSyncAndroidTest {
    @Rule public final UiAutomationStateRule automation = new UiAutomationStateRule();

    @Test public void scheduledWorkerAuthenticatesNonemptyPeerAndAcknowledgesOnlyCurrentGeneration()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.prepare();
            fixture.runScheduledExchange();
        }
    }

    @Test public void observedNetworkAvailabilityAutomaticallySchedulesAuthenticatedNonemptyWorker() throws Exception {
        try (Fixture fixture = new Fixture()) { fixture.prepare(); fixture.runScheduledExchange(true); }
    }

    private static final class Fixture implements AutoCloseable {
        static final long PROOF_MILLIS = 75_000, CLEANUP_GRACE_MILLIS = 120_000;
        final Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final String prefix = "background-peer-" + UUID.randomUUID() + "-";
        final String generation = UUID.randomUUID().toString();
        final String retiredGeneration = UUID.randomUUID().toString();
        final File root = new File(target.getCacheDir(), prefix);
        final Set<String> preferences = ConcurrentHashMap.newKeySet();
        final Set<UUID> oldImmediate = new HashSet<>(), ownedWork = new HashSet<>();
        final ExecutorService server = Executors.newSingleThreadExecutor();
        final AtomicBoolean activityStarted = new AtomicBoolean();
        final AtomicReference<UUID> selectedWork = new AtomicReference<>();
        final CountDownLatch selected = new CountDownLatch(1);
        final SharedPreferences receipt = target.getSharedPreferences("subhub_peer_worker", 0);
        final Context context = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                preferences.add(name);
                return target.getSharedPreferences(prefix + name, mode);
            }
            @Override public File getFilesDir() { return directory("files"); }
            @Override public File getNoBackupFilesDir() { return directory("private"); }
            @Override public File getCacheDir() { return directory("cache"); }
            private File directory(String name) {
                File value = new File(root, name);
                if (!value.isDirectory() && !value.mkdirs()) throw new IllegalStateException("Fixture directory unavailable");
                return value;
            }
        };
        final Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity activity) { activityStarted.set(true); }
            @Override public void onActivityCreated(Activity activity, Bundle state) { }
            @Override public void onActivityResumed(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        };
        final String localAlias = "subhub.test." + prefix + "local";
        final String remoteAlias = "subhub.test." + prefix + "remote";
        RemoteFeature previous, feature;
        RemoteConnectivityWake networkWake;
        RemoteStore local, remote;
        RemoteSecrets remoteSecrets;
        PeerTransport peer;
        WorkManager manager;
        Field singleton;
        Future<WireProof> serving;
        Map<String, ?> priorReceipt;
        String localIdentity, peerIdentity, originalPeriodic;
        String priorImmediateDrain="[]";
        RemoteProtocol.Batch localBatch, remoteBatch;
        boolean installed, scheduled, observing, originalDom, namespaceCreated;
        long originalAuthRevision, safetyObservedWall, safetyObservedElapsed, protectedUntilWall;

        void prepare() throws Exception {
            manager = WorkManager.getInstance(target);
            previous = RemoteFeature.get(target);
            originalDom = ControllerPinManager.isDomModeActive();
            originalAuthRevision = ControllerPinManager.domWorkspaceRevision();
            assertBackground();
            drainPriorImmediate();
            preflight(true);
            assertFalse((Boolean) field("foreground").get(previous));
            assertEquals(0, ((AtomicInteger) field("leases").get(previous)).get());
            assertTrue(((Set<?>) field("busy").get(previous)).isEmpty());
            assertTrue(((Map<?, ?>) field("connections").get(previous)).isEmpty());
            assertBackground();
            assertTrue(root.mkdir());
            namespaceCreated = true;
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                ((Application) target.getApplicationContext()).registerActivityLifecycleCallbacks(lifecycle);
                observing = true;
            });
            assertTrue(LegacyPackMigration.recoverAndMigrate(context));
            // Named stores exercise sync inventory without pairing commands or application witness adoption.
            local = new RemoteStore(context, prefix + "local.db");
            remote = new RemoteStore(context, prefix + "remote.db");
            RemoteSecrets localSecrets = new RemoteSecrets(context, "local-secrets", localAlias);
            remoteSecrets = new RemoteSecrets(context, "remote-secrets", remoteAlias);
            assertTrue(context.getSharedPreferences("subhub_peer_transport", 0).edit()
                    .putStringSet("relay_urls", Collections.emptySet()).commit());
            byte[] localSeed = localSecrets.identitySeed();
            try (PeerTransport identity = IrohPeerTransport.open(context, localSeed, new String[0], 10_000)) {
                localIdentity = identity.localIdentity();
            } finally { Arrays.fill(localSeed, (byte) 0); }
            byte[] remoteSeed = remoteSecrets.identitySeed();
            try { peer = IrohPeerTransport.open(context, remoteSeed, new String[0], 10_000); }
            finally { Arrays.fill(remoteSeed, (byte) 0); }
            peerIdentity = peer.localIdentity();
            assertNotEquals(localIdentity, peerIdentity);
            seedPeer(local, peerIdentity, generation, RemoteProtocol.Role.DOM, "ACTIVE", peer.inviteRoute());
            seedPeer(remote, localIdentity, generation, RemoteProtocol.Role.SUB, "ACTIVE", "");
            seedPeer(local, peerIdentity, retiredGeneration, RemoteProtocol.Role.DOM, "REVOKED", "");
            seedPeer(remote, localIdentity, retiredGeneration, RemoteProtocol.Role.SUB, "REVOKED", "");
            local.putMetadata(peerIdentity, retiredGeneration, "revocation_delivered", "true");
            // Existing scoped credentials avoid provisionSub's first-provision global PIN lock.
            remoteSecrets.saveControllerSecret(RemoteProtocol.Role.SUB, peerIdentity, localIdentity,
                    generation, Totp.newSecret());
            feature = new RemoteFeature(context, local, localSecrets);
            assertTrue(feature.recoveryReady());
            assertEquals(1, local.peers(false).size());
            assertEquals(0, local.peer(peerIdentity, generation).lastSyncedAt);
            assertEquals(0, remote.peer(localIdentity, generation).lastSyncedAt);
            long now = System.currentTimeMillis();
            local.publish(peerIdentity, generation, RemoteProtocol.Stream.CONFIGURATION, "background-proof",
                    new JSONObject().put("proof", prefix).put("direction", "dom-to-sub"), false, now);
            remote.publish(localIdentity, generation, RemoteProtocol.Stream.STATS, "background-proof",
                    new JSONObject().put("proof", prefix).put("count", 42), false, now);
            localBatch = local.nextBatch(peerIdentity, generation, RemoteProtocol.Stream.CONFIGURATION);
            remoteBatch = remote.nextBatch(localIdentity, generation, RemoteProtocol.Stream.STATS);
            assertEquals(1, localBatch.changes.size());
            assertEquals(1, remoteBatch.changes.size());
            singleton = field("instance");
            // Endpoint preparation takes time: refresh the real scheduling horizon just before substitution.
            preflight(false);
            synchronized (RemoteFeature.class) {
                assertSame(previous, singleton.get(null));
                singleton.set(null, feature);
                installed = true;
            }
            assertConsumer();
            priorReceipt = receipt.getAll();
            serving = server.submit(this::serve);
        }

        void runScheduledExchange() throws Exception { runScheduledExchange(false); }
        void runScheduledExchange(boolean automaticNetwork) throws Exception {
            assertConsumer();
            long priorStart = receipt.getLong("last_started", 0);
            scheduled = true;
            if(automaticNetwork)networkWake=new RemoteConnectivityWake(target,feature::requestStableConnectionSync);
            else RemoteScheduler.kick(target);
            long deadline = SystemClock.elapsedRealtime() + PROOF_MILLIS;
            WorkInfo work = null;
            JobInfo job = null;
            boolean runRequested = false;
            long eligibleAt = 0, requestedAt = 0;
            String runResult = "not requested: normal OS start";
            while (SystemClock.elapsedRealtime() < deadline) {
                assertConsumer();
                if (serving.isDone()) serving.get(0, TimeUnit.SECONDS);
                for (WorkInfo info : manager.getWorkInfosForUniqueWork(RemoteScheduler.IMMEDIATE).get(5, TimeUnit.SECONDS)) {
                    if (oldImmediate.contains(info.getId())) continue;
                    ownedWork.add(info.getId());
                    assertNotEquals("Immediate worker failed", WorkInfo.State.FAILED, info.getState());
                    assertNotEquals("Immediate worker was cancelled", WorkInfo.State.CANCELLED, info.getState());
                    assertTrue(info.getTags().contains(RemoteSyncWorker.class.getName()));
                    work = info;
                }
                if (work != null) {
                    selectedWork.compareAndSet(null, work.getId());
                    assertEquals(selectedWork.get(), work.getId());
                    assertEquals(NetworkType.CONNECTED, work.getConstraints().getRequiredNetworkType());
                    for (JobInfo pending : scheduler().getAllPendingJobs()) {
                        if (work.getId().toString().equals(pending.getExtras().getString("EXTRA_WORK_SPEC_ID"))) job = pending;
                    }
                    if (job != null) selected.countDown();
                    long next = work.getNextScheduleTimeMillis(), wall = System.currentTimeMillis();
                    if (job != null && !runRequested && work.getState() == WorkInfo.State.ENQUEUED
                            && next <= wall && !work.getId().toString().equals(receipt.getString("last_work_id", ""))) {
                        runRequested = true;
                        eligibleAt = next;
                        requestedAt = wall;
                        // Installed cmd help verifies -s preserves constraints and -n selects the
                        // same namespace as WorkManager on API 34+. Never force an early/offline job.
                        runResult = shell("cmd jobscheduler run -s"
                                + (Build.VERSION.SDK_INT >= 34 ? " -n androidx.work.systemjobscheduler" : "")
                                + " " + target.getPackageName() + " " + job.getId());
                    }
                    if (work.getId().toString().equals(receipt.getString("last_work_id", ""))
                            && receipt.getLong("last_started", 0) > priorStart
                            && receipt.getLong("last_finished", 0) >= receipt.getLong("last_started", 0)
                            && serving.isDone() && work.getState() == WorkInfo.State.SUCCEEDED) break;
                }
                Thread.sleep(50);
            }
            assertNotNull("No actual WorkManager work", work);
            assertEquals("Immediate worker did not succeed", WorkInfo.State.SUCCEEDED, work.getState());
            assertEquals(1, ownedWork.size());
            assertNotNull("No matching OS JobInfo", job);
            assertEquals("androidx.work.impl.background.systemjob.SystemJobService", job.getService().getClassName());
            assertNotNull("OS job lost its network constraint", job.getRequiredNetwork());
            assertEquals(work.getId().toString(), receipt.getString("last_work_id", ""));
            long started = receipt.getLong("last_started", 0), finished = receipt.getLong("last_finished", 0);
            assertTrue("Actual worker never started", started > priorStart);
            assertTrue("Actual worker did not complete", finished >= started);
            assertTrue("Worker requested retry", receipt.getBoolean("last_complete", false));
            WireProof proof = serving.get(1, TimeUnit.SECONDS);
            assertEquals(localIdentity, proof.authenticatedIdentity);
            assertTrue("Owned endpoint should use direct authenticated Iroh", proof.direct);
            assertTrue(proof.credentialReceipt);
            assertEquals(localBatch.digest(), proof.configurationAckDigest);
            assertEquals(remoteBatch.digest(), proof.statsBatchDigest);
            assertTrue(proof.doneAck);
            assertEquals(prefix, remote.mirrored(localIdentity, generation,
                    RemoteProtocol.Stream.CONFIGURATION, "background-proof").getString("proof"));
            assertEquals(prefix, local.mirrored(peerIdentity, generation,
                    RemoteProtocol.Stream.STATS, "background-proof").getString("proof"));
            assertEquals(42, local.mirrored(peerIdentity, generation,
                    RemoteProtocol.Stream.STATS, "background-proof").getInt("count"));
            assertDrained(local, peerIdentity, RemoteProtocol.Stream.CONFIGURATION, localBatch.through);
            assertDrained(remote, localIdentity, RemoteProtocol.Stream.STATS, remoteBatch.through);
            long localSync = local.peer(peerIdentity, generation).lastSyncedAt;
            long remoteSync = remote.peer(localIdentity, generation).lastSyncedAt;
            assertTrue("Local generation was not synced in worker window", localSync >= started && localSync <= finished);
            // The terminal receipt lets the worker return before the peer's final local commit.
            assertTrue("Peer generation was not synced by session completion",
                    remoteSync >= started && remoteSync <= proof.completedAt);
            assertEquals(0, local.peer(peerIdentity, retiredGeneration).lastSyncedAt);
            assertEquals(0, remote.peer(localIdentity, retiredGeneration).lastSyncedAt);
            assertNull(local.mirrored(peerIdentity, retiredGeneration, RemoteProtocol.Stream.STATS, "background-proof"));
            assertNull(remote.mirrored(localIdentity, retiredGeneration, RemoteProtocol.Stream.CONFIGURATION, "background-proof"));
            assertConsumer();
            assertOldPeriodicUnchanged();
            String witness = null;
            for (String line : shell("logcat -d -v brief -s WM-SystemJobService").split("\n")) {
                if (line.contains("onStartJob") && line.contains(work.getId().toString())) witness = line;
            }
            assertNotNull("Missing actual SystemJobService start for selected work ID", witness);
            // A matching OS start can observe an already running GreedyScheduler worker. Keep this
            // corroboration distinct from the actual worker receipt and authenticated exchange.
            JSONObject evidence = new JSONObject().put("work", work.getId().toString()).put("uniqueWork", RemoteScheduler.IMMEDIATE)
                    .put("producer", automaticNetwork?"RemoteConnectivityWake.stableValidatedNetwork":"RemoteScheduler.kick")
                    .put("priorImmediateDrain",new org.json.JSONArray(priorImmediateDrain)).put("actualScheduledWorker", RemoteSyncWorker.class.getName())
                    .put("osJobCorroboration", true).put("exclusiveOsWorkerLaunchProven", false)
                    .put("inProcessGreedyContributionPossible", true)
                    .put("limitation", "OS registration/start corroborates the same work ID; GreedyScheduler may launch the worker. No process-death, Doze or cadence guarantee.")
                    .put("job", job.getId()).put("preexistingPeriodic", new JSONObject(originalPeriodic))
                    .put("safetyObservedWall", safetyObservedWall).put("safetyObservedElapsed", safetyObservedElapsed)
                    .put("protectedUntilWall", protectedUntilWall).put("cleanupGraceMillis", CLEANUP_GRACE_MILLIS)
                    .put("schedulerNamespace", Build.VERSION.SDK_INT >= 34 ? "androidx.work.systemjobscheduler" : "default")
                    .put("eligibleAt", eligibleAt).put("requestedAt", requestedAt).put("constraintSatisfiedRun", runResult)
                    .put("osStart", witness).put("forced", false).put("started", started).put("finished", finished)
                    .put("generation", generation).put("retiredGeneration", retiredGeneration)
                    .put("localIdentity", localIdentity).put("authenticatedPeerIdentity", proof.authenticatedIdentity)
                    .put("peerIdentity", peerIdentity).put("direct", proof.direct).put("credentialReceipt", true)
                    .put("configurationAckDigest", proof.configurationAckDigest).put("statsBatchDigest", proof.statsBatchDigest)
                    .put("configurationThrough", localBatch.through).put("statsThrough", remoteBatch.through)
                    .put("doneAck", true).put("peerCompletedAt", proof.completedAt)
                    .put("localLastSync", localSync).put("peerLastSync", remoteSync)
                    .put("retiredLastSync", 0).put("activityStarted", activityStarted.get()).put("actualSingleton", true);
            File directory = new File(target.getFilesDir(), "remote-job-evidence");
            assertTrue(directory.isDirectory() || directory.mkdirs());
            File record = new File(directory, work.getId() + ".json");
            assertTrue("Do not overwrite earlier evidence", record.createNewFile());
            try (OutputStream output = new FileOutputStream(record)) {
                output.write(evidence.toString().getBytes(StandardCharsets.UTF_8));
            }
        }

        WireProof serve() throws Exception {
            WireProof proof = new WireProof();
            try (PeerTransport.Connection connection = peer.accept(60_000)) {
                assertTrue("Work ID observation did not arrive", selected.await(5, TimeUnit.SECONDS));
                assertConsumer();
                assertEquals(selectedWork.get().toString(), receipt.getString("last_work_id", ""));
                proof.authenticatedIdentity = connection.authenticatedPeerIdentity();
                proof.direct = connection.isDirect();
                assertEquals(localIdentity, proof.authenticatedIdentity);
                connection.setReadTimeoutMillis(10_000);
                PeerTransport.Connection observed = observeWrites(connection, proof);
                JSONObject hello = RemoteProtocol.read(observed.input());
                assertEquals("hello", hello.getString("type"));
                assertEquals(generation, hello.getString("generation"));
                RemoteKeyholder keyholder = new RemoteKeyholder(context, remote, remoteSecrets);
                RemoteSession session = new RemoteSession(remote, peer, new RemoteSession.Hooks() {
                    @Override public void authenticate(PeerTransport.Connection wire, RemoteStore.Peer current, long deadline)
                            throws Exception {
                        assertConsumer();
                        RemoteProtocol.write(wire.output(), new JSONObject().put("version", 1)
                                .put("type", "keyholder_credential").put("generation", generation).put("authVersion", 1)
                                .put("authSecret", keyholder.provisionSub(current, peerIdentity)));
                        JSONObject ack = RemoteProtocol.read(wire.input());
                        RemoteProtocol.requireVersion(ack);
                        assertEquals("keyholder_receipt", ack.getString("type"));
                        assertEquals(generation, ack.getString("generation"));
                        assertEquals(1, RemoteProtocol.integer(ack, "authVersion"));
                        proof.credentialReceipt = true;
                    }
                    @Override public void prepare(RemoteStore.Peer current) throws Exception { assertConsumer(); }
                    @Override public void received(RemoteStore.Peer current, RemoteProtocol.Stream stream) throws Exception { assertConsumer(); }
                    @Override public List<RemoteSession.AssetNeed> missingAssets(RemoteStore.Peer current) { return Collections.emptyList(); }
                    @Override public RemoteAssets assets(RemoteStore.Peer current) { throw new AssertionError("No asset requests in this proof"); }
                    @Override public void complete(RemoteStore.Peer current) throws Exception { assertConsumer(); }
                });
                session.incoming(observed, hello, System.nanoTime() + TimeUnit.SECONDS.toNanos(50));
                proof.completedAt = System.currentTimeMillis();
                assertConsumer();
            }
            return proof;
        }

        void assertDrained(RemoteStore store, String identity, RemoteProtocol.Stream stream, long through) {
            RemoteProtocol.Batch next = store.nextBatch(identity, generation, stream);
            assertTrue("Acknowledged outbox was not drained", next.changes.isEmpty());
            assertEquals(through, next.after);
        }

        void assertConsumer() throws Exception {
            assertSame("Worker must consume the owned singleton", feature, RemoteFeature.get(target));
            assertSame(local, feature.store);
            assertFalse("No foreground tick may contribute", (Boolean) field("foreground").get(feature));
            assertFalse("Fixture must not install UI lifecycle", (Boolean) field("installed").get(feature));
            assertFalse("An Activity started during background proof", activityStarted.get());
            assertEquals(originalDom, ControllerPinManager.isDomModeActive());
            assertEquals(originalAuthRevision, ControllerPinManager.domWorkspaceRevision());
        }

        void assertBackground() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (Stage stage : new Stage[]{Stage.STARTED, Stage.RESUMED, Stage.PAUSED}) {
                    assertTrue("Background proof requires no started Activity",
                            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).isEmpty());
                }
            });
        }

        JobScheduler scheduler() {
            JobScheduler value = target.getSystemService(JobScheduler.class);
            if (Build.VERSION.SDK_INT >= 34) return value.forNamespace("androidx.work.systemjobscheduler");
            return value;
        }

        /** Let earlier automatic work finish with its original consumer, never cancel or replace its ownership. */
        void drainPriorImmediate() throws Exception {
            final long drainMillis=65_000;
            List<WorkInfo> initial=manager.getWorkInfosForUniqueWork(RemoteScheduler.IMMEDIATE).get(5,TimeUnit.SECONDS);
            boolean unfinished=false;Set<UUID> known=new HashSet<>();
            for(WorkInfo info:initial){known.add(info.getId());unfinished|=!info.getState().isFinished();}
            if(!unfinished)return;
            List<WorkInfo> periodic=manager.getWorkInfosForUniqueWork(RemoteScheduler.PERIODIC).get(5,TimeUnit.SECONDS);
            String preservedPeriodic=periodicSnapshot(periodic);
            long protectedHorizon=Math.addExact(System.currentTimeMillis(),drainMillis+PROOF_MILLIS+CLEANUP_GRACE_MILLIS);
            for(WorkInfo info:periodic)if(!info.getState().isFinished())
                assertTrue("Periodic work is too near for a safe prior-job drain",info.getState()==WorkInfo.State.ENQUEUED&&info.getNextScheduleTimeMillis()>protectedHorizon);
            Map<UUID,String> requested=new java.util.LinkedHashMap<>();org.json.JSONArray evidence=new org.json.JSONArray();
            long deadline=SystemClock.elapsedRealtime()+drainMillis;boolean complete=false;
            while(SystemClock.elapsedRealtime()<deadline){
                assertSame("Do not replace the existing worker's consumer while it drains",previous,RemoteFeature.get(target));
                assertBackground();
                List<WorkInfo> current=manager.getWorkInfosForUniqueWork(RemoteScheduler.IMMEDIATE).get(5,TimeUnit.SECONDS);boolean terminal=true;
                for(WorkInfo info:current){
                    assertTrue("Concurrent immediate producer changed ownership",known.contains(info.getId()));
                    if(info.getState().isFinished())continue;terminal=false;
                    assertTrue("Unknown previous immediate worker",info.getTags().contains(RemoteSyncWorker.class.getName()));
                    assertEquals(NetworkType.CONNECTED,info.getConstraints().getRequiredNetworkType());
                    if(info.getState()==WorkInfo.State.ENQUEUED&&info.getNextScheduleTimeMillis()<=System.currentTimeMillis()&&!requested.containsKey(info.getId())){
                        for(JobInfo job:scheduler().getAllPendingJobs())if(info.getId().toString().equals(job.getExtras().getString("EXTRA_WORK_SPEC_ID"))){
                            assertNotNull("Previous job lost its network constraint",job.getRequiredNetwork());
                            String result=shell("cmd jobscheduler run -s"+(Build.VERSION.SDK_INT>=34?" -n androidx.work.systemjobscheduler":"")+" "+target.getPackageName()+" "+job.getId());
                            requested.put(info.getId(),result);break;
                        }
                    }
                }
                if(terminal&&((AtomicInteger)field("leases").get(previous)).get()==0&&((Set<?>)field("busy").get(previous)).isEmpty()&&((Map<?,?>)field("connections").get(previous)).isEmpty()){
                    for(WorkInfo info:current)if(known.contains(info.getId()))evidence.put(new JSONObject().put("id",info.getId().toString()).put("state",info.getState().name()).put("normalEligibleRun",requested.get(info.getId())));
                    complete=true;break;
                }
                Thread.sleep(50);
            }
            assertEquals("Draining prior work changed periodic state or scheduling",preservedPeriodic,periodicSnapshot(manager.getWorkInfosForUniqueWork(RemoteScheduler.PERIODIC).get(5,TimeUnit.SECONDS)));
            assertEquals(originalDom,ControllerPinManager.isDomModeActive());assertEquals(originalAuthRevision,ControllerPinManager.domWorkspaceRevision());
            priorImmediateDrain=evidence.toString();
            assertTrue("Prior automatic work did not finish within the safe drain deadline; no work was cancelled or replaced: "+requested,complete);
        }

        void preflight(boolean capture) throws Exception {
            List<WorkInfo> periodic = manager.getWorkInfosForUniqueWork(RemoteScheduler.PERIODIC).get(5, TimeUnit.SECONDS);
            List<WorkInfo> immediate = manager.getWorkInfosForUniqueWork(RemoteScheduler.IMMEDIATE).get(5, TimeUnit.SECONDS);
            long wall = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
            assertTrue("Scheduling clock unavailable", wall > 0);
            long horizon = Math.addExact(wall, PROOF_MILLIS + CLEANUP_GRACE_MILLIS);
            for (WorkInfo info : periodic) {
                if (info.getState().isFinished()) continue;
                long next = info.getNextScheduleTimeMillis();
                String observation = "Existing periodic " + info.getId() + " state=" + info.getState()
                        + " next=" + next + " wall=" + wall + " protectedUntil=" + horizon;
                assertEquals(observation, WorkInfo.State.ENQUEUED, info.getState());
                assertTrue(observation, next > horizon);
            }
            Set<UUID> immediateIds = new HashSet<>();
            for (WorkInfo info : immediate) {
                assertTrue("Existing immediate " + info.getId() + " state=" + info.getState(), info.getState().isFinished());
                immediateIds.add(info.getId());
            }
            String snapshot = periodicSnapshot(periodic);
            if (capture) {
                oldImmediate.addAll(immediateIds);
                originalPeriodic = snapshot;
            } else {
                assertEquals("Preexisting periodic changed during preparation", originalPeriodic, snapshot);
                assertEquals("Immediate namespace changed before substitution", oldImmediate, immediateIds);
                safetyObservedWall = wall;
                safetyObservedElapsed = elapsed;
                protectedUntilWall = horizon;
            }
        }

        void assertOldPeriodicUnchanged() throws Exception {
            assertEquals("Preexisting periodic inventory/state/scheduling metadata changed", originalPeriodic,
                    periodicSnapshot(manager.getWorkInfosForUniqueWork(RemoteScheduler.PERIODIC).get(5, TimeUnit.SECONDS)));
        }

        String periodicSnapshot(List<WorkInfo> infos) throws Exception {
            JSONObject observed = new JSONObject(), rows = new JSONObject();
            for (WorkInfo info : infos) observed.put(info.getId().toString(), new JSONObject()
                    .put("state", info.getState().name()).put("nextScheduleTimeMillis", info.getNextScheduleTimeMillis())
                    .put("runAttemptCount", info.getRunAttemptCount()));
            // The public API omits schedule_requested_at and last_enqueue_time. Read the live SQLite
            // rows without a write transaction; compare all columns, including schedule/time metadata.
            File current = new File(target.getNoBackupFilesDir(), "androidx.work.workdb");
            File legacy = target.getDatabasePath("androidx.work.workdb");
            if (current.isFile() && legacy.isFile()) assertEquals(current.getCanonicalFile(), legacy.getCanonicalFile());
            File database = current.isFile() ? current : legacy;
            assertTrue("Live WorkManager database unavailable", database.isFile());
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(database.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
                 Cursor cursor = db.rawQuery("SELECT w.* FROM WorkSpec w JOIN WorkName n ON n.work_spec_id=w.id WHERE n.name=? ORDER BY w.id",
                         new String[]{RemoteScheduler.PERIODIC})) {
                while (cursor.moveToNext()) {
                    JSONObject row = new JSONObject();
                    for (int i = 0; i < cursor.getColumnCount(); i++) {
                        Object value;
                        switch (cursor.getType(i)) {
                            case Cursor.FIELD_TYPE_NULL: value = JSONObject.NULL; break;
                            case Cursor.FIELD_TYPE_INTEGER: value = cursor.getLong(i); break;
                            case Cursor.FIELD_TYPE_FLOAT: value = cursor.getDouble(i); break;
                            case Cursor.FIELD_TYPE_BLOB: value = RemoteProtocol.sha256(cursor.getBlob(i)); break;
                            default: value = cursor.getString(i);
                        }
                        row.put(cursor.getColumnName(i), value);
                    }
                    rows.put(cursor.getString(cursor.getColumnIndexOrThrow("id")), row);
                }
            }
            assertEquals("Periodic API/SQLite inventory disagreement", observed.length(), rows.length());
            for (java.util.Iterator<String> ids = observed.keys(); ids.hasNext();) assertTrue(rows.has(ids.next()));
            return RemoteProtocol.canonical(new JSONObject().put("workInfo", observed).put("workSpec", rows));
        }

        @Override public void close() throws Exception {
            List<Throwable> failures = new ArrayList<>();
            clean(failures, () -> { if(networkWake!=null){networkWake.close();networkWake=null;} });
            clean(failures, () -> {
                selected.countDown();
                if (!scheduled) return;
                if (ownedWork.isEmpty()) {
                    for (WorkInfo info : manager.getWorkInfosForUniqueWork(RemoteScheduler.IMMEDIATE).get(5, TimeUnit.SECONDS)) {
                        if (!oldImmediate.contains(info.getId())) ownedWork.add(info.getId());
                    }
                }
                assertTrue("Ambiguous immediate ownership; do not cancel unknown work", ownedWork.size() <= 1);
                for (UUID id : ownedWork) manager.cancelWorkById(id).getResult().get(5, TimeUnit.SECONDS);
            });
            clean(failures, () -> { if (peer != null) peer.close(); });
            clean(failures, () -> {
                server.shutdownNow();
                assertTrue("Peer server did not stop", server.awaitTermination(10, TimeUnit.SECONDS));
            });
            // Keep the owned consumer installed until cancellation, worker IO and session pools drain.
            clean(failures, () -> {
                if (!scheduled) return;
                long deadline = SystemClock.elapsedRealtime() + 10_000;
                boolean quiet;
                do {
                    quiet = true;
                    for (UUID id : ownedWork) {
                        WorkInfo info = manager.getWorkInfoById(id).get(5, TimeUnit.SECONDS);
                        if (info != null && !info.getState().isFinished()) quiet = false;
                    }
                    for (JobInfo job : scheduler().getAllPendingJobs()) {
                        String work = job.getExtras().getString("EXTRA_WORK_SPEC_ID");
                        for (UUID id : ownedWork) if (id.toString().equals(work)) quiet = false;
                    }
                    if (!quiet) Thread.sleep(50);
                } while (!quiet && SystemClock.elapsedRealtime() < deadline);
                assertTrue("Owned scheduled jobs did not quiesce before restore", quiet);
            });
            clean(failures, () -> {
                if (feature == null) return;
                long deadline = SystemClock.elapsedRealtime() + 10_000;
                AtomicInteger leases = (AtomicInteger) field("leases").get(feature);
                Set<?> busy = (Set<?>) field("busy").get(feature);
                Map<?, ?> connections = (Map<?, ?>) field("connections").get(feature);
                while ((leases.get() != 0 || !busy.isEmpty() || !connections.isEmpty())
                        && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
                assertEquals("Worker window still owns the feature", 0, leases.get());
                assertTrue("Peer task still owns the feature", busy.isEmpty());
                assertTrue("Peer IO still owns the feature", connections.isEmpty());
            });
            clean(failures, () -> { if (feature != null) feature.shutdownForTest(); else if (local != null) local.close(); });
            clean(failures, () -> {
                if (!installed) return;
                synchronized (RemoteFeature.class) {
                    assertSame(feature, singleton.get(null));
                    singleton.set(null, previous);
                    installed = false;
                }
                assertSame(previous, RemoteFeature.get(target));
                for (String name : new String[]{"operations", "incomingSessions", "sessions"})
                    assertFalse("Original runtime was shut down", ((ExecutorService) field(name).get(previous)).isShutdown());
            });
            clean(failures, () -> {
                if (originalPeriodic != null) assertOldPeriodicUnchanged();
                if (safetyObservedWall != 0) {
                    assertTrue("Proof/cleanup exceeded its protected scheduling horizon",
                            SystemClock.elapsedRealtime() - safetyObservedElapsed <= PROOF_MILLIS + CLEANUP_GRACE_MILLIS
                                    && System.currentTimeMillis() <= protectedUntilWall);
                }
            });
            clean(failures, () -> { if (remote != null) remote.close(); });
            clean(failures, () -> {
                if (priorReceipt == null) return;
                SharedPreferences.Editor edit = receipt.edit();
                for (String key : new String[]{"last_started", "last_finished", "last_work_id", "last_complete"}) {
                    Object value = priorReceipt.get(key);
                    if (value == null) edit.remove(key);
                    else if (value instanceof Long) edit.putLong(key, (Long) value);
                    else if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
                    else if (value instanceof String) edit.putString(key, (String) value);
                    else throw new AssertionError("Unexpected original worker receipt type");
                }
                assertTrue(edit.commit());
            });
            clean(failures, () -> {
                if (observing) InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                        ((Application) target.getApplicationContext()).unregisterActivityLifecycleCallbacks(lifecycle));
                if (previous != null) {
                    assertEquals(originalDom, ControllerPinManager.isDomModeActive());
                    assertEquals(originalAuthRevision, ControllerPinManager.domWorkspaceRevision());
                    assertFalse(activityStarted.get());
                    assertBackground();
                }
            });
            clean(failures, () -> {
                if (!namespaceCreated) return;
                if (local != null) assertTrue(target.deleteDatabase(prefix + "local.db"));
                if (remote != null) assertTrue(target.deleteDatabase(prefix + "remote.db"));
                for (String name : preferences) assertTrue(target.deleteSharedPreferences(prefix + name));
                KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
                keys.load(null);
                for (String alias : new String[]{localAlias, remoteAlias}) if (keys.containsAlias(alias)) keys.deleteEntry(alias);
                Field ready = LegacyPackMigration.class.getDeclaredField("READY");
                ready.setAccessible(true);
                synchronized (LegacyPackMigration.class) { ((Set<?>) ready.get(null)).remove(context.getFilesDir().getAbsoluteFile()); }
                Path owned = root.getCanonicalFile().toPath(), allowed = target.getCacheDir().getCanonicalFile().toPath();
                assertEquals(prefix, owned.getFileName().toString());
                assertTrue(owned.startsWith(allowed));
                if (Files.exists(owned)) try (java.util.stream.Stream<Path> paths = Files.walk(owned)) {
                    for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                        assertFalse(Files.isSymbolicLink(path));
                        assertTrue(path.toRealPath().startsWith(owned));
                        Files.delete(path);
                    }
                }
            });
            MultipleFailureException.assertEmpty(failures);
        }
    }

    private static void seedPeer(RemoteStore store, String identity, String generation,
            RemoteProtocol.Role role, String state, String route) {
        // Only inventory setup is synthetic. Worker/session code alone may write sync state and receipts.
        ContentValues row = new ContentValues();
        row.put("peer", identity); row.put("generation", generation); row.put("name", "Background fixture");
        row.put("local_role", role.name()); row.put("state", state); row.put("route", route);
        store.getWritableDatabase().insertOrThrow("peers", null, row);
    }

    private static final class WireProof {
        String authenticatedIdentity, configurationAckDigest, statsBatchDigest;
        boolean direct, credentialReceipt, doneAck;
        long completedAt;
    }

    /** Observes bytes written by the real RemoteSession; never manufactures a protocol reply. */
    private static PeerTransport.Connection observeWrites(PeerTransport.Connection actual, WireProof proof) {
        return new PeerTransport.Connection() {
            final OutputStream output = new OutputStream() {
                final ByteArrayOutputStream pending = new ByteArrayOutputStream();
                @Override public void write(int value) { pending.write(value); }
                @Override public void write(byte[] value, int offset, int length) { pending.write(value, offset, length); }
                @Override public void flush() throws IOException {
                    byte[] bytes = pending.toByteArray();
                    if (bytes.length == 0) return;
                    JSONObject frame = RemoteProtocol.read(new ByteArrayInputStream(bytes));
                    try {
                        if ("ack".equals(frame.optString("type")) && "CONFIGURATION".equals(frame.optString("stream")))
                            proof.configurationAckDigest = RemoteProtocol.Ack.parse(frame).digest;
                        if ("batch".equals(frame.optString("type")) && "STATS".equals(frame.optString("stream"))) {
                            RemoteProtocol.Batch batch = RemoteProtocol.Batch.parse(frame);
                            assertFalse(batch.changes.isEmpty());
                            proof.statsBatchDigest = batch.digest();
                        }
                        if ("done_ack".equals(frame.optString("type"))) proof.doneAck = true;
                    } catch (org.json.JSONException invalid) { throw new IOException(invalid); }
                    actual.output().write(bytes);
                    actual.output().flush();
                    pending.reset();
                }
            };
            @Override public String authenticatedPeerIdentity() { return actual.authenticatedPeerIdentity(); }
            @Override public InputStream input() throws IOException { return actual.input(); }
            @Override public OutputStream output() { return output; }
            @Override public void setReadTimeoutMillis(long timeout) { actual.setReadTimeoutMillis(timeout); }
            @Override public boolean isDirect() { return actual.isDirect(); }
            @Override public void close() throws IOException { actual.close(); }
        };
    }

    private static Field field(String name) throws Exception {
        Field value = RemoteFeature.class.getDeclaredField(name);
        value.setAccessible(true);
        return value;
    }

    private static String shell(String command) throws IOException {
        ParcelFileDescriptor result = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY).executeShellCommand(command);
        assertNotNull("Shell command unavailable", result);
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(result)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > 512 * 1024) throw new IOException("Shell observation exceeded bound");
                output.write(buffer, 0, count);
            }
            return output.toString("UTF-8");
        }
    }

    private interface Cleanup { void run() throws Exception; }
    private static void clean(List<Throwable> failures, Cleanup cleanup) {
        try { cleanup.run(); } catch (Throwable failure) { failures.add(failure); }
    }
}
