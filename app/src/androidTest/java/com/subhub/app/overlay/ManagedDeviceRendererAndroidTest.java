package com.subhub.app.overlay;

import static org.junit.Assert.*;

import android.app.Service;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.IBinder;
import android.view.WindowManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.capture.CustomImageManager;
import com.subhub.app.penance.CensorTapTracker;
import com.subhub.app.runtime.*;
import com.subhub.app.service.RuntimeCaptureHost;
import com.subhub.app.settings.CensorAppearance;
import com.subhub.app.settings.SettingsRepository;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Exact DEVICE capability and Android renderer with fixture-owned preferences/files only. No bound
 * service, overlay window, capture grant, hardware draw, physical session or native drain evidence.
 */
@RunWith(AndroidJUnit4.class)
public final class ManagedDeviceRendererAndroidTest {
    @Test public void initialDeviceAppearanceAndArtworkUseExplicitInputs() throws Exception {
        try (Fixture f = Fixture.create()) {
            f.construct();
            main(() -> {
                CensorOverlayView view = (CensorOverlayView) field(f.controller, "view");
                CensorAppearance appearance = (CensorAppearance) field(view, "appearance");
                assertEquals(CensorAppearance.Type.CUSTOM, appearance.getType());
                assertTrue(appearance.isShowBorder());
                assertEquals(CensorAppearance.BorderEffect.RAINBOW, appearance.getBorderEffect());
                assertEquals(300f, (Float) field(view, "maxExtrapolationMs"), 0f);
                assertSame(f.taps, f.dependencies.taps);
                assertTrue(f.loader.empty()); assertTrue(f.dependencies.images.isEmpty());
                assertFalse(f.dependencies.tryNormal(() -> fail("Not ACTIVE")));
            });
            f.activate();
            main(() -> assertFalse(f.controller.reconcileArtwork())); f.loader.drain();
            main(() -> { assertTrue(f.controller.reconcileArtwork()); assertFalse(f.dependencies.images.isEmpty()); });
        }
    }

    @Test public void queuedDeviceArtworkSurvivesStopUntilActualWorkerReturn() throws Exception {
        try (Fixture f = Fixture.create()) {
            f.construct(); f.activate();
            main(() -> assertFalse(f.controller.reconcileArtwork())); assertFalse(f.loader.empty());
            f.runtime.requestStop(); f.awaitCleanup();
            main(() -> f.cleanup.get().run(f.controller::close));
            assertFalse(f.dependencies.managedResourcesDrained());
            assertTrue(f.call(f.coordinator::anyOccupied));
            f.loader.drain(); // Not cancellation or shutdown receipt.
            f.awaitRenderer();
            assertTrue(f.dependencies.images.isEmpty());
        }
    }

    @Test public void disconnectedDeviceArtworkCannotDecodeOrRender() throws Exception {
        try (Fixture f = Fixture.create()) {
            f.construct(); f.activate();
            main(() -> assertFalse(f.controller.reconcileArtwork()));
            Method disconnect = RuntimeCaptureHost.Connection.class.getDeclaredMethod("disconnected");
            disconnect.setAccessible(true); disconnect.invoke(f.request.connection);
            f.loader.drain();
            assertTrue(f.dependencies.images.isEmpty()); assertFalse(f.dependencies.open());
            assertFalse(f.component.normal(() -> fail("Disconnected owner must be stopped")));
        }
    }

    @Test public void supersededDeviceDecodeCannotReplacePublishedArtwork() throws Exception {
        try (Fixture f = Fixture.create()) {
            f.construct(); f.activate();
            main(() -> assertFalse(f.controller.reconcileArtwork())); f.loader.drain();
            AtomicReference<Bitmap> published = new AtomicReference<>(), superseded = new AtomicReference<>();
            main(() -> assertTrue(f.dependencies.tryNormal(() -> f.dependencies.images.draw(drawing ->
                    published.set(drawing.imageFor(1).bitmap())))));
            f.dependencies.images.loadOwnedAsync(obsolete -> {
                Bitmap image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888); superseded.set(image);
                main(() -> f.dependencies.appearanceChanged(null)); assertTrue(obsolete.getAsBoolean());
                return Collections.singletonList(image);
            }, () -> fail("Superseded ready"));
            f.loader.drain(); assertTrue(superseded.get().isRecycled()); assertFalse(published.get().isRecycled());
            main(() -> assertTrue(f.dependencies.tryNormal(() -> f.dependencies.images.draw(drawing ->
                    assertSame(published.get(), drawing.imageFor(1).bitmap())))));
            main(() -> assertFalse(f.controller.reconcileArtwork())); f.loader.drain();
            main(() -> assertTrue(f.controller.reconcileArtwork())); assertTrue(published.get().isRecycled());
        }
    }

    private static void main(Runnable work) { InstrumentationRegistry.getInstrumentation().runOnMainSync(work); }
    private static Object field(Object target, String name) {
        try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static final class Fixture implements AutoCloseable {
        final Context physical = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final Path root = Files.createTempDirectory(physical.getCacheDir().toPath(), "managed-device-render-");
        final SharedPreferences preferences = physical.getSharedPreferences("managed_device_render_" + UUID.randomUUID(), 0);
        final ExecutorService owner = Executors.newSingleThreadExecutor();
        final Queue loader = new Queue();
        final CensorTapTracker taps = new CensorTapTracker();
        final AtomicReference<DeviceRuntimeEffects.DeviceComponent.Cleanup> cleanup = new AtomicReference<>();
        final Context context = new ContextWrapper(physical) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return root.toFile(); }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                if (!SettingsRepository.PREFERENCES_NAME.equals(name)) throw new AssertionError("Unexpected fixture store: " + name);
                return preferences;
            }
        };
        ActiveRuntimeCoordinator coordinator; RuntimeLease lease; RuntimeExecution<?> runtime;
        DeviceRuntimeEffects effects; DeviceRuntimeEffects.DeviceComponent component;
        RuntimeCaptureHost.DeviceRequest request; FixtureService service;
        LegacyRenderDependencies dependencies; OverlayController controller;
        Fixture() throws Exception { }
        static Fixture create() throws Exception {
            Fixture f = new Fixture();
            try { f.initialize(); return f; }
            catch (Exception | Error failure) {
                try { f.close(); } catch (Exception | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        void initialize() throws Exception {
            Path pack = Files.createDirectory(root.resolve("pack")); Bitmap image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
            try (FileOutputStream output = new FileOutputStream(pack.resolve("owned.png").toFile())) {
                image.eraseColor(Color.MAGENTA); assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
            } finally { image.recycle(); }
            assertTrue(preferences.edit().putString(SettingsRepository.KEY_CENSOR_TYPE, "custom")
                    .putString(SettingsRepository.KEY_DETECTION_PRESET, "high")
                    .putBoolean(SettingsRepository.KEY_SHOW_BORDER, true)
                    .putString(SettingsRepository.KEY_BORDER_EFFECT, "rainbow")
                    .putString(CustomImageManager.PACK_DIR_KEY, pack.toString()).commit());
            call(() -> {
                RuntimeMutex mutex = new RuntimeMutex(new Object()); RuntimeAdmissionAuthority authority = new RuntimeAdmissionAuthority(mutex);
                coordinator = new ActiveRuntimeCoordinator(new RuntimeOwnerStore(mutex, new RuntimeOwnerStore.Backend() {
                    RuntimeOwnerStore.Record row = RuntimeOwnerStore.Record.idle(0);
                    public RuntimeOwnerStore.Record read() { return row; }
                    public RuntimeOwnerStore.Outcome compareAndSet(RuntimeOwnerStore.Record before, RuntimeOwnerStore.Record next) {
                        if (!row.equals(before)) return RuntimeOwnerStore.Outcome.CONFLICT;
                        row = next; return RuntimeOwnerStore.Outcome.COMMITTED;
                    }
                }), authority, () -> ActiveRuntimeCoordinator.Occupancy.CLEAR, UUID.randomUUID().toString());
                assertTrue(authority.completeAvailability(authority.beginAvailability(RuntimeTarget.DEVICE), "device-fixture", 1, true));
                assertTrue(authority.completeAuthorization(authority.beginAuthorization(RuntimeTarget.DEVICE, RuntimeOrigin.LOCAL_UI), 1, true));
                assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.reconcileStartup());
                lease = coordinator.reserve(authority.capture(RuntimeTarget.DEVICE, RuntimeOrigin.LOCAL_UI), Set.of("renderer")).lease;
                assertNotNull(lease); assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.beginStarting(lease, ignored -> true));
                Constructor<?> execution = RuntimeExecution.class.getDeclaredConstructor(ActiveRuntimeCoordinator.class, RuntimeLease.class, Object.class, Executor.class);
                execution.setAccessible(true); runtime = (RuntimeExecution<?>) execution.newInstance(coordinator, lease, "fixture", owner);
                Constructor<DeviceRuntimeEffects> capability = DeviceRuntimeEffects.class.getDeclaredConstructor(ActiveRuntimeCoordinator.class, RuntimeExecution.class, RuntimeLease.class);
                capability.setAccessible(true); effects = capability.newInstance(coordinator, runtime, lease);
                component = effects.component("renderer"); component.installShutdown(cleanup::set); component.usesNoConfiguration();
                return null;
            });
            main(() -> service = new FixtureService(context));
            Method connect = RuntimeCaptureHost.Connection.class.getDeclaredMethod("projection", RuntimeCaptureHost.ProjectionHost.class); connect.setAccessible(true);
            RuntimeCaptureHost.Connection connection = (RuntimeCaptureHost.Connection) connect.invoke(null, new RuntimeCaptureHost.ProjectionHost() {
                public Service service() { return service; }
                public void showNeutralForegroundNotification() { fail("No bound service"); }
                public void stopAfterDrain() { fail("No OS lifecycle"); }
            });
            request = new RuntimeCaptureHost.DeviceRequest(connection, new RuntimeCaptureHost.CapturePlan(RuntimeCaptureHost.Mode.PROJECTION, 26),
                    runtime, effects, ignored -> { throw new AssertionError("No service attachment"); });
        }
        void construct() {
            main(() -> assertTrue(component.startup(() -> {
                dependencies = LegacyRenderDependencies.managedDevice(request, component, new SettingsRepository(context),
                        new CustomImageManager(context), taps, loader);
                controller = OverlayController.managedDevice(service, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, dependencies);
            })));
        }
        void activate() throws Exception {
            CompletableFuture<ActiveRuntimeCoordinator.Result> ready = new CompletableFuture<>();
            call(() -> { component.readyAsync(ready::complete); return null; });
            assertEquals(ActiveRuntimeCoordinator.Result.OK, ready.get(10, TimeUnit.SECONDS));
            assertEquals(ActiveRuntimeCoordinator.Result.OK, call(() -> coordinator.activate(lease)));
        }
        <T> T call(Callable<T> work) throws Exception { return owner.submit(work).get(10, TimeUnit.SECONDS); }
        void awaitCleanup() throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (cleanup.get() == null) {
                call(() -> null); if (System.nanoTime() >= deadline) throw new AssertionError("Actual core cleanup missing");
            }
        }
        void awaitRenderer() throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (dependencies != null && !dependencies.managedResourcesDrained()) {
                main(() -> { });
                if (System.nanoTime() >= deadline) throw new AssertionError("Actual queued renderer callbacks remain; timeout is not drain");
                Thread.sleep(10);
            }
        }
        @Override public void close() throws Exception {
            if (runtime != null) runtime.requestStop();
            if (component != null) {
                awaitCleanup();
                main(() -> cleanup.get().run(() -> {
                    if (controller != null) controller.close(); else if (dependencies != null) dependencies.close(); else loader.shutdown();
                }));
            } else loader.shutdown();
            loader.drain(); awaitRenderer(); assertTrue(loader.isTerminated());
            if (component != null) {
                cleanup.get().drained();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (call(coordinator::anyOccupied)) {
                    if (System.nanoTime() >= deadline) throw new AssertionError("Owner cleanup not acknowledged");
                }
            }
            owner.shutdown(); assertTrue(owner.awaitTermination(10, TimeUnit.SECONDS));
            // Only this fixture's files/preferences. Failed real drain deliberately retains them.
            assertTrue(preferences.edit().clear().commit());
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
    }
    private static final class FixtureService extends Service {
        FixtureService(Context context) { attachBaseContext(context); }
        @Override public IBinder onBind(Intent intent) { return null; }
    }
    private static final class Queue extends AbstractExecutorService {
        final ArrayDeque<Runnable> pending = new ArrayDeque<>(); boolean closed, running;
        @Override public synchronized void execute(Runnable work) { if (closed) throw new IllegalStateException("Closed fixture queue"); pending.add(work); }
        synchronized boolean empty() { return pending.isEmpty(); }
        void drain() {
            assertNotSame(android.os.Looper.getMainLooper(), android.os.Looper.myLooper());
            while (true) {
                Runnable work; synchronized (this) { work = pending.poll(); if (work == null) return; running = true; }
                try { work.run(); } finally { synchronized (this) { running = false; } }
            }
        }
        @Override public synchronized void shutdown() { closed = true; }
        @Override public List<Runnable> shutdownNow() { throw new AssertionError("Cancellation is not drain"); }
        @Override public synchronized boolean isShutdown() { return closed; }
        @Override public synchronized boolean isTerminated() { return closed && !running && pending.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { throw new AssertionError("No blocking loader teardown"); }
    }
}
