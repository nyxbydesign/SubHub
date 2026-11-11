package com.subhub.app.runtime;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.activity.result.ActivityResultRegistry;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.core.app.ActivityOptionsCompat;
import androidx.lifecycle.ViewModelStore;
import androidx.lifecycle.ViewModelStoreOwner;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.appmode.DeviceStartResultRegistry;
import com.subhub.app.appmode.DeviceStartViewModel;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.settings.CaptureMethod;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Real AndroidX registry and ViewModelStore, with owned in-memory runtime and synthetic onLaunch.
 * Never installs the application facade, opens an OS prompt or changes physical app preferences.
 * Save/restore here tests registry routing; it is not an ActivityScenario or system-dialog test.
 */
@RunWith(AndroidJUnit4.class)
public final class DeviceStartResultRegistryAndroidTest {
    @Test public void oldRawResultCannotConsumeNewPromptAfterRegistryRestore() throws Exception {
        try (Fixture f = new Fixture()) {
            f.start(); Attempt first = f.last(); DeviceStartViewModel.PromptRoute old = f.route();
            int firstCode = f.code(old);
            f.result(firstCode); f.await(() -> !f.model.busy());
            assertEquals(1, first.results);
            f.start(); Attempt second = f.last(); DeviceStartViewModel.PromptRoute current = f.route();
            int secondCode = f.code(current);
            assertNotEquals(firstCode, secondCode); assertNotEquals(old.registrationKey, current.registrationKey);
            f.result(firstCode); f.recreateRegistry();
            f.onMain(() -> {
                old.returned(() -> { throw new AssertionError("Retired callback read a new grant"); }, 0, null);
                assertEquals(0, second.results); assertTrue(f.model.busy());
            });
            f.result(secondCode); f.await(() -> !f.model.busy());
            assertEquals(1, second.results); assertEquals(1, first.results);
        }
    }
    @Test public void pendingResultSurvivesUnregisterAndReregistersSameExactRoute() throws Exception {
        try (Fixture f = new Fixture()) {
            f.start(); Attempt owner = f.last(); DeviceStartViewModel.PromptRoute route = f.route();
            int code = f.code(route); int launches = f.registry.launches;
            f.onMain(() -> {
                f.model.detach(f.ui); f.routing.close();
                assertTrue(f.registry.dispatchResult(code, Activity.RESULT_CANCELED, null));
                assertEquals(0, owner.results); // No callback is registered, so AndroidX retains it.
                f.routing = new DeviceStartResultRegistry(f.registry, ignored -> false);
                f.routing.restore(route);
                assertEquals(0, owner.results); // Real callback returned, but model processing is posted.
                f.model.attach(f.ui);
            });
            f.await(() -> !f.model.busy());
            assertEquals(1, owner.results); assertEquals(launches, f.registry.launches);
        }
    }
    @Test public void registrySaveRestoreKeepsPendingRequestCodeWithoutRelaunch() throws Exception {
        try (Fixture f = new Fixture()) {
            f.start(); Attempt owner = f.last(); DeviceStartViewModel.PromptRoute route = f.route();
            int code = f.code(route); f.recreateRegistry();
            assertSame(route, f.route()); assertEquals(0, f.registry.launches);
            f.result(code); f.await(() -> !f.model.busy());
            assertEquals(1, owner.results); assertEquals(RuntimeOwnerStore.Phase.IDLE, owner.phase());
        }
    }
    @Test public void launchThrowAndLateRawResultDrainInCurrentRegistryWithoutRelaunch() throws Exception {
        try (Fixture f = new Fixture()) {
            f.onMain(() -> f.registry.throwAfterLaunch = true);
            f.start(); Attempt owner = f.last(); DeviceStartViewModel.PromptRoute route = f.route();
            Registry original = f.registry; int code = f.code(route), launches = original.launches;
            assertEquals(RuntimeOwnerStore.Phase.STOPPING, owner.phase());
            f.onMain(() -> {
                f.routing.onFrameworkResultReturned(); // No result yet: this proves nothing returned.
                assertEquals(0, owner.results); assertTrue(f.model.busy());
                assertTrue(original.dispatchResult(code, Activity.RESULT_CANCELED, null));
                assertEquals(0, owner.results); // AndroidX buffers because launch threw.
                f.routing.onFrameworkResultReturned();
                assertEquals(0, owner.results); // Delivery is posted beyond this outer turn.
                assertTrue(original.dispatchResult(code, Activity.RESULT_CANCELED, null));
                f.routing.onFrameworkResultReturned(); // Duplicate cannot submit a second receipt.
            });
            f.await(() -> !f.model.busy());
            assertSame(original, f.registry); assertEquals(launches, original.launches);
            assertEquals(1, owner.results); assertEquals(RuntimeOwnerStore.Phase.IDLE, owner.phase());
            f.onMain(() -> original.throwAfterLaunch = false);
            f.start(); Attempt next = f.last(); int nextCode = f.code(f.route());
            assertNotEquals(code, nextCode);
            f.result(code); assertEquals(0, next.results); assertTrue(f.model.busy());
            f.result(nextCode); f.await(() -> !f.model.busy());
            assertEquals(1, next.results); assertEquals(1, owner.results);
        }
    }
    @Test public void notificationLaunchThrowAlsoDrainsOnlyItsActualLateResult() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.onMain(() -> f.registry.throwAfterLaunch = true);
            f.start(); Attempt owner = f.last(); DeviceStartViewModel.PromptRoute route = f.route();
            assertEquals(RuntimeStartAttempt.Permission.NOTIFICATIONS, route.permission);
            f.onMain(() -> f.routing.onFrameworkResultReturned());
            assertTrue(f.model.busy()); assertEquals(0, owner.results);
            f.result(f.code(route)); f.await(() -> !f.model.busy());
            assertEquals(1, owner.results); assertEquals(1, f.registry.launches);
            assertEquals(RuntimeOwnerStore.Phase.IDLE, owner.phase());
        }
    }
    @Test public void registryKeysAloneCannotReconstructAnAttemptAfterProcessLoss() throws Exception {
        try (Fixture f = new Fixture()) {
            f.start(); Attempt owner = f.last(); DeviceStartViewModel.PromptRoute route = f.route();
            int code = f.code(route); Bundle state = new Bundle(); Bundle[] metadata = new Bundle[1];
            f.onMain(() -> {
                metadata[0] = f.routing.saveMetadata(); f.registry.onSaveInstanceState(state); f.model.detach(f.ui); f.routing.close();
                DeviceStartViewModel empty = new DeviceStartViewModel();
                Registry restored = new Registry(f); restored.onRestoreInstanceState(state);
                try (DeviceStartResultRegistry routing = new DeviceStartResultRegistry(restored, ignored -> false)) {
                    routing.restore(metadata[0], empty.pendingRoute());
                    assertTrue(restored.dispatchResult(code, Activity.RESULT_CANCELED, null));
                    routing.discardRetiredResults();
                    assertEquals(0, owner.results); assertNull(empty.pendingRoute());
                }
                f.routing = new DeviceStartResultRegistry(f.registry, ignored -> false);
                f.routing.restore(route); f.model.attach(f.ui);
            });
            assertTrue(owner.coordinator.anyOccupied());
        }
    }
    @Test public void retiredRoutesReleaseCapabilitiesAndCapacityFailsBeforeOsLaunch() throws Exception {
        try (Fixture f = new Fixture()) {
            Field delivery = DeviceStartViewModel.PromptRoute.class.getDeclaredField("delivery");
            delivery.setAccessible(true);
            // Production cap is intentionally finite and does not recycle a key/request code.
            for (int index = 0; index < 128; index++) {
                f.start(); DeviceStartViewModel.PromptRoute route = f.route();
                f.onMain(() -> assertFalse(f.routing.needsFreshHome()));
                f.result(f.code(route)); f.await(() -> !f.model.busy());
                assertNull(delivery.get(route));
            }
            int before = f.registry.launches;
            f.begin(); f.await(() -> !f.model.busy());
            assertEquals(before, f.registry.launches);
            assertEquals(RuntimeOwnerStore.Phase.IDLE, f.last().phase());
            assertNull(f.route());
            f.onMain(() -> assertTrue(f.routing.needsFreshHome()));
            f.recreateRegistry(); // Configuration recreation must not recycle request codes.
            f.onMain(() -> assertTrue(f.routing.needsFreshHome()));
            f.begin(); f.await(() -> !f.model.busy()); assertEquals(0, f.registry.launches);
            Registry oldRegistry = f.registry; DeviceStartViewModel oldModel = f.model;
            f.freshRegistryAndModel(); // Models a distinct Activity target, NOT Activity.recreate().
            assertNotSame(oldModel, f.model); assertNotSame(oldRegistry, f.registry);
            f.onMain(() -> assertFalse(f.routing.needsFreshHome()));
            f.start(); Attempt fresh = f.last(); int freshCode = f.code(f.route());
            f.onMain(() -> {
                // A late callback targeted at the old Activity registry cannot reach the new one.
                oldRegistry.dispatchResult(freshCode, Activity.RESULT_CANCELED, null);
                assertEquals(0, fresh.results); assertTrue(f.model.busy());
            });
            f.result(freshCode); f.await(() -> !f.model.busy()); assertEquals(1, fresh.results);
        }
    }

    private static final class Registry extends ActivityResultRegistry {
        final Fixture fixture; int launches; boolean throwAfterLaunch;
        Registry(Fixture fixture) { this.fixture = fixture; }
        @Override public <I, O> void onLaunch(int requestCode, ActivityResultContract<I, O> contract,
                I input, ActivityOptionsCompat options) {
            launches++; fixture.codes.put(fixture.model.pendingRoute(), requestCode);
            if (throwAfterLaunch) throw new IllegalStateException("Synthetic accepted launch then throw");
        }
    }
    private static final class MemoryOwner implements RuntimeOwnerStore.Backend {
        RuntimeOwnerStore.Record record = RuntimeOwnerStore.Record.idle(0);
        @Override public RuntimeOwnerStore.Record read() { return record; }
        @Override public RuntimeOwnerStore.Outcome compareAndSet(RuntimeOwnerStore.Record expected, RuntimeOwnerStore.Record next) {
            if (!record.equals(expected)) return RuntimeOwnerStore.Outcome.CONFLICT;
            record = next; return RuntimeOwnerStore.Outcome.COMMITTED;
        }
    }
    private static final class Attempt implements AppRuntimeGateway.DeviceStart {
        final MemoryOwner memory = new MemoryOwner();
        final RuntimeMutex mutex = new RuntimeMutex(new Object());
        final RuntimeAdmissionAuthority authority = new RuntimeAdmissionAuthority(mutex);
        final ActiveRuntimeCoordinator coordinator = new ActiveRuntimeCoordinator(new RuntimeOwnerStore(mutex, memory),
                authority, () -> ActiveRuntimeCoordinator.Occupancy.CLEAR, UUID.randomUUID().toString());
        final RuntimeStartGateway gateway = new RuntimeStartGateway(coordinator, Runnable::run, () -> { });
        final RuntimeStartAttempt attempt;
        final AppRuntimeGateway.DeviceStartSpec specification;
        final boolean notifications;
        int results;
        Attempt(AppRuntimeGateway.DeviceStartSpec specification, boolean notifications) {
            this.specification = specification; this.notifications = notifications;
            assertTrue(authority.completeAvailability(authority.beginAvailability(RuntimeTarget.DEVICE), "fixture", 1, true));
            assertTrue(authority.completeAuthorization(authority.beginAuthorization(RuntimeTarget.DEVICE, RuntimeOrigin.LOCAL_UI), 1, true));
            assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.reconcileStartup());
            attempt = gateway.reserve(authority.capture(RuntimeTarget.DEVICE, RuntimeOrigin.LOCAL_UI), Set.of("capture")).join().attempt;
        }
        @Override public RuntimeLease.Identity identity() { return attempt.identity(); }
        @Override public AppRuntimeGateway.DeviceStartSpec specification() { return specification; }
        @Override public AppRuntimeGateway.DevicePlan plan() { return new AppRuntimeGateway.DevicePlan(CaptureMethod.SCREEN_RECORDING, false, false, notifications); }
        @Override public CompletableFuture<RuntimeStartAttempt.Prompt> permission(RuntimeStartAttempt.Permission kind) { return gateway.permission(attempt, kind); }
        @Override public CompletableFuture<Boolean> permissionResult(RuntimeStartAttempt.Prompt prompt, boolean granted, int code, Intent data) {
            results++; return gateway.permissionResult(attempt, prompt, granted);
        }
        @Override public CompletableFuture<AppRuntimeGateway.StartResult> start() { throw new AssertionError("Fixture always denies projection"); }
        @Override public boolean cancel() { return gateway.cancel(attempt); }
        RuntimeOwnerStore.Phase phase() { return memory.record.phase; }
    }
    private static final class Fixture implements AutoCloseable {
        final Application application = (Application) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        final AppRuntimeGateway original = AppRuntimeGateway.current(application);
        final List<Attempt> attempts = new ArrayList<>();
        final Map<DeviceStartViewModel.PromptRoute, Integer> codes = new IdentityHashMap<>();
        ViewModelStore retained = new ViewModelStore();
        final ViewModelStoreOwner viewModels = () -> retained;
        DeviceStartViewModel model; Registry registry; DeviceStartResultRegistry routing;
        final AppRuntimeGateway gateway;
        final DeviceStartViewModel.Ui ui = new DeviceStartViewModel.Ui() {
            @Override public boolean available() { return true; }
            @Override public boolean granted(RuntimeStartAttempt.Permission permission) { return false; }
            @Override public Runnable prepare(DeviceStartViewModel.PromptRoute route) {
                DeviceStartResultRegistry.Prepared prepared = routing.prepare(route);
                return () -> prepared.launch(new Intent("fixture.permission"));
            }
            @Override public void changed() { }
            @Override public void completed(AppRuntimeGateway.StartState state) { }
        };
        Fixture() throws Exception { this(false); }
        Fixture(boolean notifications) throws Exception {
            // Construct an isolated facade without altering its process-wide installed field.
            Constructor<AppRuntimeGateway> constructor = AppRuntimeGateway.class.getDeclaredConstructor(Application.class, AppRuntimeGateway.Backend.class);
            constructor.setAccessible(true);
            gateway = constructor.newInstance(application, new AppRuntimeGateway.Backend() {
                @Override public CompletableFuture<AppRuntimeGateway.DeviceStart> deviceStart(AppRuntimeGateway.DeviceStartSpec specification) {
                    Attempt attempt = new Attempt(specification, notifications); attempts.add(attempt); return CompletableFuture.completedFuture(attempt);
                }
                @Override public CompletableFuture<AppRuntimeGateway.StartResult> rearmDevice(AppRuntimeGateway.RearmReason reason,
                        RuntimeSessionIdentity.ComponentObservation expected, Runnable action) { throw new AssertionError("No physical rearm"); }
                @Override public AppRuntimeGateway.DeviceMutationPermit admitDeviceMutation(AppRuntimeGateway.Mutation mutation) {
                    throw new AssertionError("No physical mutations");
                }
            });
            onMain(() -> {
                model = new ViewModelProvider(viewModels).get(DeviceStartViewModel.class);
                registry = new Registry(this); routing = new DeviceStartResultRegistry(registry, ignored -> false); model.attach(ui);
            });
        }
        void onMain(Runnable work) { InstrumentationRegistry.getInstrumentation().runOnMainSync(work); }
        void begin() { onMain(() -> assertTrue(model.begin(gateway, new AppRuntimeGateway.DeviceStartSpec(CaptureMethod.SCREEN_RECORDING, 0, 0, false)))); }
        void start() { begin(); await(() -> model.pendingRoute() != null && codes.containsKey(model.pendingRoute())); }
        Attempt last() { return attempts.get(attempts.size() - 1); }
        DeviceStartViewModel.PromptRoute route() {
            DeviceStartViewModel.PromptRoute[] value = new DeviceStartViewModel.PromptRoute[1]; onMain(() -> value[0] = model.pendingRoute()); return value[0];
        }
        int code(DeviceStartViewModel.PromptRoute route) { return codes.get(route); }
        void result(int code) { onMain(() -> {
            assertTrue(registry.dispatchResult(code, Activity.RESULT_CANCELED, null)); routing.onFrameworkResultReturned();
        }); }
        void freshRegistryAndModel() {
            onMain(() -> {
                assertFalse(model.busy()); assertNull(model.pendingRoute()); assertTrue(routing.needsFreshHome());
                model.detach(ui); routing.close(); retained.clear(); retained = new ViewModelStore();
                model = new ViewModelProvider(viewModels).get(DeviceStartViewModel.class);
                registry = new Registry(this); routing = new DeviceStartResultRegistry(registry, ignored -> false);
                model.attach(ui); // No saved keys, grants or retained capabilities cross Activity targets.
            });
        }
        void recreateRegistry() {
            onMain(() -> {
                Bundle metadata = routing.saveMetadata(); Bundle state = new Bundle();
                registry.onSaveInstanceState(state); model.detach(ui); routing.close();
                registry = new Registry(this); registry.onRestoreInstanceState(state);
                DeviceStartViewModel recreated = new ViewModelProvider(viewModels).get(DeviceStartViewModel.class);
                assertSame(model, recreated); model = recreated;
                routing = new DeviceStartResultRegistry(registry, ignored -> false); routing.restore(metadata, model.pendingRoute()); model.attach(ui);
            });
        }
        void await(BooleanSupplier observation) {
            long deadline = SystemClock.uptimeMillis() + 5000;
            AtomicBoolean ready = new AtomicBoolean();
            do {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                onMain(() -> ready.set(observation.getAsBoolean()));
                if (ready.get()) return;
            } while (SystemClock.uptimeMillis() < deadline);
            fail("Exact registry/attempt transition did not complete");
        }
        @Override public void close() {
            onMain(() -> {
                model.cancel(); DeviceStartViewModel.PromptRoute pending = model.pendingRoute();
                if (pending != null && codes.containsKey(pending)) {
                    // These are our synthetic recorded launches, not fabricated real OS returns.
                    registry.dispatchResult(codes.get(pending), Activity.RESULT_CANCELED, null);
                    routing.close(); routing = new DeviceStartResultRegistry(registry, ignored -> false); routing.restore(pending);
                }
            });
            try { await(() -> model.pendingRoute() == null); }
            finally { onMain(() -> { routing.close(); retained.clear(); }); }
            for (Attempt attempt : attempts) assertEquals("Owned fixture must fully drain", RuntimeOwnerStore.Phase.IDLE, attempt.phase());
            assertSame(original, AppRuntimeGateway.current(application));
        }
    }
}
