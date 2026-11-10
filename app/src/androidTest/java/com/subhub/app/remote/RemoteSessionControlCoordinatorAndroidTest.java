package com.subhub.app.remote;

import static org.junit.Assert.*;
import static com.subhub.app.remote.RemoteSessionCommands.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.Cursor;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.people.People;
import com.subhub.app.security.ControllerAuthenticator;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.KeyholderProvider;
import com.subhub.app.security.SubFeaturePolicy;
import com.subhub.app.settings.SettingsRepository;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Private PIN/preferences and real SQLite; global provider/mode/context are restored exactly. No OS grants. */
public final class RemoteSessionControlCoordinatorAndroidTest {
    private static final String DOM = String.join("", Collections.nCopies(64, "a"));
    private static final String SUB = String.join("", Collections.nCopies(64, "b"));
    private static final String SESSION = String.join("", Collections.nCopies(64, "c")), IDLE = String.join("", Collections.nCopies(64, "d"));
    private Context target, context;
    private String prefix, database, generation;
    private RemoteStore store, independent;
    private RemoteKeyholder keyholder;
    private String wrappingAlias;
    private boolean wrappingCreated;
    private RemoteSessionControlCoordinator coordinator;
    private KeyholderProvider originalProvider, ownedProvider;
    private boolean originalMode;
    private Field applicationField;
    private Object originalApplication;
    private Runnable afterObservation;
    private RemoteFeature feature;
    private java.io.File facadeDirectory;
    private Context facadeContext;
    private int observations;
    @Before public void setup() throws Exception {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext(); prefix = "session-coordinator-" + UUID.randomUUID() + "-";
        database = prefix + "db"; originalProvider = People.keyholder(); originalMode = ControllerPinManager.isDomModeActive();
        applicationField = ControllerPinManager.class.getDeclaredField("application"); applicationField.setAccessible(true);
        originalApplication = applicationField.get(null);
        context = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return target.getSharedPreferences(prefix + name, mode); }
        };
        store = new RemoteStore(context, database); independent = new RemoteStore(context, database);
        wrappingAlias = "subhub.test.coordinator." + UUID.randomUUID();
        keyholder = new RemoteKeyholder(context, store, new RemoteSecrets(context, "secrets", wrappingAlias));
        ownedProvider = provider(); People.installKeyholder(ownedProvider);
        main().edit().putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.DOM_ONLY.name()).commit();
        assertTrue(ControllerPinManager.setPin(context, "7351"));
        long now = System.currentTimeMillis();
        PairingInvite invite = PairingInvite.create(DOM, "fixture", now);
        generation = invite.id;
        store.saveInvite(invite); store.bindInvite(generation, SUB, invite.tokenHash(), now);
        store.approveInvite(generation, SUB, "Synthetic Sub", now); store.activate(SUB, generation);
        store.putMetadata(SUB, generation, "session_control_version", "1"); independent.getReadableDatabase();
        coordinator = new RemoteSessionControlCoordinator(context, store, DOM); state(true, 7, needsAction(), Reason.READINESS_REQUIRED);
    }
    private static RemoteSessionRuntime.Readiness needsAction() { return RemoteSessionRuntime.Readiness.NEEDS_SUB_ACTION; }
    private KeyholderProvider provider() {
        return new KeyholderProvider() {
            @Override public State state() {
                assertFalse(Thread.holdsLock(store)); assertFalse(Thread.holdsLock(SubFeaturePolicy.LOCK)); assertFalse(Thread.holdsLock(RuntimeSessionIdentity.LOCK));
                assertFalse(store.getWritableDatabase().inTransaction()); observations++;
                State value = keyholder.state(); Runnable action = afterObservation; afterObservation = null;
                if (action != null) action.run(); return value;
            }
            @Override public String assignedDomName() { return keyholder.assignedDomName(); }
            @Override public ControllerAuthenticator.Result verifyCode(String code) { return keyholder.verifyCode(code); }
            @Override public boolean withLocalAuthority(java.util.function.BooleanSupplier action) { return keyholder.withLocalAuthority(action); }
        };
    }
    @After public void cleanup() throws Exception {
        afterObservation = null;
        try {
            if (originalProvider != null) People.installKeyholder(originalProvider);
            if (originalMode) ControllerPinManager.enterDomMode(); else ControllerPinManager.enterSubMode();
            if (applicationField != null) applicationField.set(null, originalApplication);
            if (feature != null) feature.shutdownForTest();
        } finally {
            try {
            if (store != null) store.close(); if (independent != null) independent.close();
            if (database != null) assertTrue(target.deleteDatabase(database));
            if (prefix != null) for (String name : new String[]{SettingsRepository.PREFERENCES_NAME, "subhub_controller_auth", "secrets",
                    SubFeaturePolicy.STORE, RemoteConfiguration.DURATION_PREFS, "subhub_remote_secrets_v1", "subhub_peer_transport"})
                target.getSharedPreferences(prefix + name, 0).edit().clear().commit();
            if (wrappingCreated) {
                assertTrue(wrappingAlias.startsWith("subhub.test.coordinator."));
                java.security.KeyStore keys = java.security.KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
                if (keys.containsAlias(wrappingAlias)) keys.deleteEntry(wrappingAlias); assertFalse(keys.containsAlias(wrappingAlias));
            }
            } finally { cleanupFacadeStorage(); }
        }
    }
    private void cleanupFacadeStorage() throws Exception {
        if (facadeContext != null) {
            Field ready = com.subhub.app.configuration.LegacyPackMigration.class.getDeclaredField("READY"); ready.setAccessible(true);
            synchronized (com.subhub.app.configuration.LegacyPackMigration.class) {
                ((java.util.Set<?>) ready.get(null)).remove(facadeContext.getFilesDir().getAbsoluteFile());
            }
        }
        if (facadeDirectory == null || !facadeDirectory.exists()) return;
        java.nio.file.Path root = facadeDirectory.getCanonicalFile().toPath();
        assertTrue(root.startsWith(target.getCacheDir().getCanonicalFile().toPath()));
        assertEquals(prefix + "facade", root.getFileName().toString());
        try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path path : (Iterable<java.nio.file.Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator) {
                assertFalse(java.nio.file.Files.isSymbolicLink(path)); assertTrue(path.toRealPath().startsWith(root)); java.nio.file.Files.delete(path);
            }
        }
    }
    private SharedPreferences main() { return context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0); }
    private RemoteStore.Peer peer() { return store.peer(SUB, generation); }
    private RemoteSessionControlState state(boolean active, long revision, RemoteSessionRuntime.Readiness readiness, Reason reason) throws Exception {
        RemoteSessionClock.Sample now = RemoteSessionClock.observe(context);
        RemoteSessionControlState state = new RemoteSessionControlState(SUB, DOM, generation, now.wall, revision,
                active ? null : new Duration(-1, 3600000, 86400000, false), active, active ? "" : IDLE, active ? SESSION : "",
                readiness, reason, !active && readiness == RemoteSessionRuntime.Readiness.APP_MODE_READY && reason == Reason.NONE, active);
        store.putMetadata(SUB, generation, RemoteSessionControlState.METADATA, RemoteProtocol.canonical(new RemoteSessionControlState.Received(state, now).json()));
        return state;
    }
    private RemoteSessionRuntime.Review review() throws Exception { return coordinator.review(peer(), Action.STOP); }
    private int count() {
        try (Cursor rows = store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM session_control_journal", null)) { assertTrue(rows.moveToFirst()); return rows.getInt(0); }
    }
    private String floor() { return store.metadata(SUB, generation, "session_control_OUTGOING_floor", "0"); }
    private RemoteSessionRuntime.Record recorded(RemoteSessionRuntime.Review review) throws Exception {
        return store.sessionControl(SUB, generation, RemoteSessionRuntime.Direction.OUTGOING, review.request.id);
    }
    @Test public void mintedReviewQueuesOnceAndAlreadyJournaledReplayRequiresCurrentAuthentication() throws Exception {
        RemoteSessionRuntime.Review review = review(); assertEquals(0, count()); String digest = review.requestDigest;
        assertEquals(review.request.id, coordinator.confirm(review)); assertEquals(1, count());
        assertEquals(State.QUEUED, recorded(review).result.state); assertEquals(digest, recorded(review).request.digest());
        state(false, 9, RemoteSessionRuntime.Readiness.APP_MODE_READY, Reason.NONE);
        RemoteSessionRuntime.Review reconstructed = new RemoteSessionRuntime.Review(review.request, review.sourceWitness, review.preparedAt);
        assertEquals(review.request.id, new RemoteSessionControlCoordinator(context, store, DOM).confirm(reconstructed));
        assertTrue(ControllerPinManager.setPin(context, "8642")); assertEquals(review.request.id, coordinator.confirm(reconstructed));
        assertEquals(1, count()); assertEquals("1", floor()); assertEquals(review.request.expiresAt, recorded(review).request.expiresAt);
        ControllerPinManager.lockNow(); assertThrows(SecurityException.class, () -> coordinator.confirm(reconstructed)); assertEquals(1, count());
    }
    @Test public void reconstructedUnconfirmedReviewCannotCreateIntentAndOriginalStillWorks() throws Exception {
        RemoteSessionRuntime.Review original = review();
        RemoteSessionRuntime.Review forged = new RemoteSessionRuntime.Review(original.request, original.sourceWitness, original.preparedAt);
        assertThrows(SecurityException.class, () -> coordinator.confirm(forged));
        assertThrows(SecurityException.class, () -> new RemoteSessionControlCoordinator(context, store, DOM).confirm(original));
        assertEquals(0, count()); assertEquals("0", floor()); assertEquals(original.request.id, coordinator.confirm(original)); assertEquals(1, count());
    }
    @Test public void changedCredentialsRequireNewReviewEvenWhileDomSessionRemainsUnlocked() throws Exception {
        RemoteSessionRuntime.Review old = review(); assertTrue(ControllerPinManager.setPin(context, "8642"));
        assertTrue(ControllerPinManager.isSessionUnlocked()); assertThrows(SecurityException.class, () -> coordinator.confirm(old));
        assertEquals(0, count()); assertEquals("0", floor());
        RemoteSessionRuntime.Review fresh = review(); assertEquals(fresh.request.id, coordinator.confirm(fresh)); assertEquals(1, count());
    }
    @Test public void unlockedModeWithoutActualCredentialsCannotMintOrQueueAnIntent() throws Exception {
        main().edit().remove("controller_pin_salt").remove("controller_pin_hash").commit();
        assertTrue(ControllerPinManager.isDomModeActive());
        assertThrows(SecurityException.class, () -> coordinator.review(peer(), Action.STOP));
        assertThrows(SecurityException.class, () -> coordinator.snapshot(peer()));
        assertEquals(0, count()); assertEquals("0", store.metadata(SUB, generation, "session_control_allocated", "0"));
    }
    @Test public void realPrivateRemoteCredentialAdmitsReviewButGoverningScopeChangeRefusesItsConfirmation() throws Exception {
        main().edit().putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.BOTH.name()).commit();
        String parent = String.join("", Collections.nCopies(64, "e"));
        PairingInvite governing = PairingInvite.create(parent, "fixture", System.currentTimeMillis());
        store.acceptInvite(governing, "Synthetic Dom"); store.activate(parent, governing.id);
        java.security.KeyStore keys = java.security.KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
        assertFalse(keys.containsAlias(wrappingAlias)); wrappingCreated = true;
        String secret = keyholder.provisionSub(store.peer(parent, governing.id), DOM);
        assertFalse(ControllerPinManager.isSessionUnlocked());
        String code = com.subhub.app.security.Totp.code(secret, System.currentTimeMillis() / com.subhub.app.security.Totp.STEP_MILLIS);
        assertEquals(ControllerAuthenticator.Result.SUCCESS, keyholder.verifyCode(code)); assertTrue(ControllerPinManager.isSessionUnlocked());
        assertEquals(KeyholderProvider.State.READY, ownedProvider.state());
        RemoteSessionRuntime.Review review = review(); assertEquals(0, count());
        independent.revoke(parent, governing.id);
        assertThrows(SecurityException.class, () -> coordinator.confirm(review)); assertEquals(0, count()); assertEquals("0", floor());
    }
    @Test public void changedProviderOrRoleCannotConfirmAnEarlierUnjournaledReview() throws Exception {
        RemoteSessionRuntime.Review old = review(); People.installKeyholder(provider());
        assertThrows(SecurityException.class, () -> coordinator.confirm(old)); assertEquals(0, count());
        RemoteSessionRuntime.Review current = review();
        main().edit().putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.BOTH.name()).commit();
        assertThrows(SecurityException.class, () -> coordinator.confirm(current)); assertEquals(0, count());
        RemoteSessionRuntime.Review both = review(); main().edit().putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.SUB_ONLY.name()).commit();
        assertThrows(SecurityException.class, () -> coordinator.confirm(both)); assertEquals(0, count());
    }
    @Test public void targetRevocationAndCapabilityDowngradeRefuseNewIntentAcrossIndependentHandle() throws Exception {
        RemoteSessionRuntime.Review old = review(); independent.putMetadata(SUB, generation, "session_control_version", "0");
        assertThrows(UnsupportedOperationException.class, () -> coordinator.confirm(old)); assertEquals(0, count());
        independent.putMetadata(SUB, generation, "session_control_version", "1");
        RemoteSessionRuntime.Review current = review(); independent.revoke(SUB, generation);
        assertThrows(SecurityException.class, () -> coordinator.confirm(current)); assertEquals(0, count()); assertEquals("0", floor());
    }
    @Test public void authorityMutationDuringObservationCannotAllocateOrQueueAReview() throws Exception {
        afterObservation = () -> assertTrue(ControllerPinManager.setPin(context, "8642"));
        assertThrows(SecurityException.class, () -> coordinator.review(peer(), Action.STOP));
        assertEquals("0", store.metadata(SUB, generation, "session_control_allocated", "0")); assertEquals(0, count());
        afterObservation = () -> People.installKeyholder(provider());
        assertThrows(SecurityException.class, () -> coordinator.review(peer(), Action.STOP)); assertEquals(0, count());
    }
    @Test public void newGoverningDomDuringObservationCannotUseTheOldLocalAuthorityScope() throws Exception {
        assertTrue(main().edit().putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.BOTH.name()).commit());
        PairingInvite governing = PairingInvite.create(String.join("", Collections.nCopies(64, "e")), "fixture", System.currentTimeMillis());
        afterObservation = () -> {
            independent.acceptInvite(governing, "Synthetic Dom"); independent.activate(governing.domIdentity, governing.id);
        };
        assertThrows(SecurityException.class, () -> coordinator.review(peer(), Action.STOP)); assertEquals(0, count());
        RemoteStore.Peer installed = independent.peer(governing.domIdentity, governing.id);
        assertNotNull(installed); assertEquals(RemoteProtocol.Role.SUB, installed.localRole); assertEquals(RemoteStore.State.ACTIVE, installed.state);
        assertEquals(DeviceRolePreferences.Mode.BOTH, DeviceRolePreferences.get(context)); assertTrue(ControllerPinManager.isSessionUnlocked());
        assertEquals("0", store.metadata(SUB, generation, "session_control_allocated", "0"));
    }
    @Test public void failedQueueCommitRetainsSameReviewIdentityAndOriginalDeadlineForRetry() throws Exception {
        RemoteSessionRuntime.Review review = review(); String digest = review.requestDigest;
        store.getWritableDatabase().execSQL("CREATE TRIGGER deny_session_command BEFORE INSERT ON session_control_journal BEGIN SELECT RAISE(ABORT,'Synthetic journal commit failure'); END");
        try { assertThrows(android.database.sqlite.SQLiteException.class, () -> coordinator.confirm(review)); assertEquals(0, count()); assertEquals("0", floor()); }
        finally { store.getWritableDatabase().execSQL("DROP TRIGGER deny_session_command"); }
        assertEquals(review.request.id, coordinator.confirm(review)); assertEquals(digest, recorded(review).request.digest());
        assertEquals(review.request.expiresAt, recorded(review).request.expiresAt); assertEquals(1, count()); assertTrue(observations > 0);
    }
    private Object minted(RemoteSessionRuntime.Review review) throws Exception {
        Field field = RemoteSessionControlCoordinator.class.getDeclaredField("reviews"); field.setAccessible(true);
        return ((Map<?, ?>) field.get(coordinator)).get(review.request.id);
    }
    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    @Test public void freshRepeatedAdviceCannotRenewOriginalReviewFreshnessOrRequestLifetime() throws Exception {
        RemoteSessionRuntime.Review old = review(); Object minted = minted(old);
        RemoteSessionControlState.Received original = (RemoteSessionControlState.Received) field(minted, "source");
        // Fixture-owned clock anchors only. The request, digest and original expiry remain untouched.
        set(minted, "source", new RemoteSessionControlState.Received(original.state, new RemoteSessionClock.Sample(
                original.clock.wall - 95000, original.clock.elapsed - 95000, original.clock.boot)));
        state(true, 7, needsAction(), Reason.READINESS_REQUIRED);
        assertThrows(SecurityException.class, () -> coordinator.confirm(old)); assertEquals(0, count());
        RemoteSessionRuntime.Review timed = review(); Object timedMinted = minted(timed);
        RemoteSessionClock.Bounds lifetime = (RemoteSessionClock.Bounds) field(timedMinted, "lifetime");
        RemoteSessionClock.Bounds aged = new RemoteSessionClock.Bounds(lifetime.receivedWall - 125000, lifetime.receivedElapsed - 125000,
                lifetime.boot, lifetime.deadlineElapsed - 125000);
        assertEquals(Reason.EXPIRED, aged.check(timed.request, RemoteSessionClock.observe(context))); set(timedMinted, "lifetime", aged);
        assertThrows(SecurityException.class, () -> coordinator.confirm(timed)); assertEquals(0, count());
        assertEquals(timed.requestDigest, timed.request.digest()); assertEquals(timed.request.issuedAt + LIFETIME_MILLIS, timed.request.expiresAt);
    }
    @Test public void stopReviewIgnoresStartProviderChangesButStillBindsExactActiveSession() throws Exception {
        RemoteSessionRuntime.Snapshot clicked = coordinator.snapshot(peer()); RemoteSessionRuntime.Review stop = review();
        assertEquals(clicked.sourceWitness, stop.sourceWitness);
        state(true, 99, RemoteSessionRuntime.Readiness.UNAVAILABLE, Reason.HARDCORE_REQUIRED);
        RemoteSessionRuntime.Snapshot current = coordinator.snapshot(peer());
        // This is the opaque equality contract used by Root's existing Controls/Gateway, not a View acceptance claim.
        assertTrue(current.canStop); assertEquals(clicked.sourceWitness, current.sourceWitness); assertEquals(current.sourceWitness, stop.sourceWitness);
        assertEquals(stop.request.id, coordinator.confirm(stop)); assertEquals(1, count());
        RemoteSessionRuntime.Review changed = review();
        RemoteSessionClock.Sample now = RemoteSessionClock.observe(context);
        RemoteSessionControlState replacement = new RemoteSessionControlState(SUB, DOM, generation, now.wall, 99, null, true, "",
                String.join("", Collections.nCopies(64, "f")), RemoteSessionRuntime.Readiness.UNAVAILABLE, Reason.HARDCORE_REQUIRED, false, true);
        store.putMetadata(SUB, generation, RemoteSessionControlState.METADATA, RemoteProtocol.canonical(new RemoteSessionControlState.Received(replacement, now).json()));
        assertThrows(SecurityException.class, () -> coordinator.confirm(changed)); assertEquals(1, count());
    }
    @Test public void startReviewBindsFullSourceWhileUnchangedFactsCanRefreshTheirObservation() throws Exception {
        state(false, 7, RemoteSessionRuntime.Readiness.APP_MODE_READY, Reason.NONE);
        RemoteSessionRuntime.Review start = coordinator.review(peer(), Action.START);
        assertEquals(coordinator.snapshot(peer()).sourceWitness, start.sourceWitness);
        state(false, 7, RemoteSessionRuntime.Readiness.APP_MODE_READY, Reason.NONE); assertEquals(start.request.id, coordinator.confirm(start));
        RemoteSessionRuntime.Review changed = coordinator.review(peer(), Action.START);
        state(false, 8, RemoteSessionRuntime.Readiness.APP_MODE_READY, Reason.NONE);
        assertThrows(SecurityException.class, () -> coordinator.confirm(changed)); assertEquals(1, count());
    }
    @Test public void latestStatusNeverRegressesToOlderResultAfterNewerRowRetires() throws Exception {
        RemoteSessionRuntime.Review first = review(), second = review(); coordinator.confirm(first); coordinator.confirm(second);
        store.advanceSessionControl(SUB, generation, RemoteSessionRuntime.Direction.OUTGOING, first.request.id,
                new Result(first.request, State.REJECTED, Reason.SESSION_CHANGED, System.currentTimeMillis() + 5000, null).json());
        assertEquals(second.request.id, coordinator.snapshot(peer()).latestRecord.request.id);
        store.getWritableDatabase().delete("session_control_journal", "id=?", new String[]{second.request.id});
        assertNull(coordinator.snapshot(peer()).latestRecord); assertEquals(Long.toString(second.request.sequence), floor());
    }
    @Test public void unconfirmedReviewRegistryIsBoundedAndConfirmationReleasesOnlyItsSlot() throws Exception {
        RemoteSessionRuntime.Review first = null;
        for (int index = 0; index < 32; index++) { RemoteSessionRuntime.Review review = review(); if (first == null) first = review; }
        assertThrows(IllegalStateException.class, () -> coordinator.review(peer(), Action.STOP));
        assertEquals("32", store.metadata(SUB, generation, "session_control_allocated", "0")); assertEquals(0, count());
        coordinator.confirm(first); RemoteSessionRuntime.Review next = review(); assertEquals(33, next.request.sequence); assertEquals(1, count());
    }
    private static void futureFailure(Class<? extends Throwable> type, java.util.concurrent.CompletableFuture<?> future) throws Exception {
        try { future.get(5, java.util.concurrent.TimeUnit.SECONDS); fail("Expected " + type.getSimpleName()); }
        catch (java.util.concurrent.ExecutionException failure) { assertTrue(String.valueOf(failure.getCause()), type.isInstance(failure.getCause())); }
    }
    @Test public void enabledFacadeAndHooksRefuseUnsupportedOrUnauthenticatedCommandsWithoutEffects() throws Exception {
        assertTrue(RemoteSessionRuntime.applicationEnabled());
        facadeDirectory = new java.io.File(target.getCacheDir(), prefix + "facade");
        facadeContext = new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public java.io.File getFilesDir() { return owned("files"); }
            @Override public java.io.File getNoBackupFilesDir() { return owned("no-backup"); }
            @Override public java.io.File getCacheDir() { return owned("cache"); }
            private java.io.File owned(String name) {
                java.io.File child = new java.io.File(facadeDirectory, name);
                if (!child.isDirectory() && !child.mkdirs()) throw new IllegalStateException("Cannot create owned facade storage");
                return child;
            }
        };
        assertTrue(com.subhub.app.configuration.LegacyPackMigration.recoverAndMigrate(facadeContext));
        feature = new RemoteFeature(facadeContext, store);
        assertTrue(feature.recoveryReady());
        PeerTransport noNetwork = new PeerTransport() {
            public String localIdentity() { return DOM; }
            public String inviteRoute() { throw new AssertionError("Unsupported facade resolved route"); }
            public Connection connect(String identity, String route, long timeout) { throw new AssertionError("Unsupported facade connected"); }
            public Connection accept(long timeout) { throw new AssertionError("Unsupported facade accepted"); }
            public void close() { }
        };
        Field endpoint = RemoteFeature.class.getDeclaredField("endpoint"); endpoint.setAccessible(true);
        java.util.concurrent.atomic.AtomicInteger leases = (java.util.concurrent.atomic.AtomicInteger) field(feature, "leases");
        // A fixture-owned lease keeps asynchronous facade reads on this exact endpoint until cleanup.
        leases.incrementAndGet(); synchronized (field(feature, "endpointLock")) { endpoint.set(feature, noNetwork); }
        try {
            String source = store.metadata(SUB, generation, RemoteSessionControlState.METADATA, "");
            store.putMetadata(SUB, generation, "session_control_version", "0");
            RemoteSessionRuntime.Snapshot unsupported = feature.sessionSnapshot(peer()).get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(unsupported.supported); assertFalse(unsupported.canStart); assertFalse(unsupported.canStop);
            futureFailure(UnsupportedOperationException.class, feature.reviewSession(peer(), Action.STOP));
            RemoteSessionClock.Sample now = RemoteSessionClock.observe(context);
            Request request = Request.stop(UUID.randomUUID().toString(), DOM, SUB, generation, 1, 7, now.wall, now.wall + LIFETIME_MILLIS, SESSION);
            RemoteSessionRuntime.Review forged = new RemoteSessionRuntime.Review(request, "not-a-minted-review", now.wall);
            futureFailure(UnsupportedOperationException.class, feature.confirmSession(forged));
            store.putMetadata(SUB, generation, "session_control_version", "1");
            RemoteSessionRuntime.Snapshot enabled = feature.sessionSnapshot(peer()).get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(enabled.supported); assertFalse(enabled.canStart); assertTrue(enabled.canStop);
            futureFailure(SecurityException.class, feature.confirmSession(forged));
            java.lang.reflect.Method sessionMethod = RemoteFeature.class.getDeclaredMethod("session", PeerTransport.class); sessionMethod.setAccessible(true);
            RemoteSession session = (RemoteSession) sessionMethod.invoke(feature, noNetwork);
            RemoteSession.Hooks hooks = (RemoteSession.Hooks) field(session, "hooks"); assertEquals(VERSION, hooks.sessionControlVersion());
            PeerTransport.Connection wrongPeer = new PeerTransport.Connection() {
                public String authenticatedPeerIdentity() { return DOM; }
                public java.io.InputStream input() { throw new AssertionError("Unauthenticated hook read application data"); }
                public java.io.OutputStream output() { throw new AssertionError("Unauthenticated hook wrote application data"); }
                public void setReadTimeoutMillis(long timeout) { throw new AssertionError("Unauthenticated hook began I/O"); }
                public boolean isDirect() { return true; }
                public void close() { }
            };
            assertThrows(SecurityException.class, () -> hooks.sessionControl(wrongPeer, peer(), System.nanoTime() + 1000000000));
            assertNotNull(field(feature, "sessionControlCoordinator")); assertSame(noNetwork, field(feature, "endpoint"));
            awaitOwnedLease(leases); assertTrue(((Map<?, ?>) field(feature, "connections")).isEmpty());
            assertTrue(((Map<?, ?>) field(feature, "nextAttempt")).isEmpty());
            assertEquals(0, count()); assertEquals("0", floor()); assertEquals("0", store.metadata(SUB, generation, "session_control_allocated", "0"));
            assertEquals(source, store.metadata(SUB, generation, RemoteSessionControlState.METADATA, ""));
            assertFalse(main().getBoolean(com.subhub.app.appmode.AppModeManager.KEY_ARMED, false));
            ControllerPinManager.lockNow(); futureFailure(SecurityException.class, feature.sessionSnapshot(peer()));
            awaitOwnedLease(leases);
            assertEquals(0, count()); assertEquals("0", floor());
        } finally { assertTrue(leases.decrementAndGet() >= 0); }
    }
    private static void awaitOwnedLease(java.util.concurrent.atomic.AtomicInteger leases) throws InterruptedException {
        long deadline = android.os.SystemClock.uptimeMillis() + 5000;
        while (leases.get() != 1 && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(10);
        assertEquals("Facade work must release its query leases before the fixture lease", 1, leases.get());
    }
}
