package com.subhub.app.overlay;

import static org.junit.Assert.*;

import android.app.Service;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.IBinder;
import android.view.View;
import android.view.WindowManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.capture.CustomImagePool;
import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.ObjectTracker;
import com.subhub.app.internal.*;
import com.subhub.app.people.InternalSubData;
import com.subhub.app.remote.RemoteAssets;
import com.subhub.app.runtime.*;
import com.subhub.app.service.RuntimeCaptureHost;
import com.subhub.app.settings.CensorAppearance;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.stats.OwnerObservation;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Real Android View/settings/software pixels and isolated owner accounting. No OS connection,
 * visible window, capture permission, hardware Canvas, RenderThread or native drain qualification.
 */
@RunWith(AndroidJUnit4.class)
public final class LegacyRenderDependenciesAndroidTest {
    @Test public void initialCustomArtworkAndFirstFrameNeedNoLaterConfigurationEdit() throws Exception {
        try (Fixture f = new Fixture(false, true)) {
            f.construct();
            main(() -> {
                assertEquals(CensorAppearance.Type.CUSTOM, f.appearance().getType());
                assertEquals(300f, (Float) field(f.view(), "maxExtrapolationMs"), 0f);
                assertSame(f.initial, field(f.dependencies, "acknowledged"));
                assertFalse(f.dependencies.images.pending());
                assertTrue(f.loader.empty()); // CUSTOM construction records demand, never decoding.
                assertThrows(LegacyRenderDependencies.Unavailable.class, () -> f.controller.update(Collections.emptyList(), 100, 100, null));
            });
            f.activate();
            main(() -> assertFalse(f.controller.reconcileArtwork()));
            f.loader.drain(); // Actual accepted worker bodies return, off main.
            main(() -> {
                assertTrue(f.controller.reconcileArtwork());
                assertFalse(f.dependencies.images.isEmpty());
                ObjectTracker tracker = new ObjectTracker(SettingsRepository.forPreferences(f.initial.main).loadDetectorConfig());
                f.controller.update(tracker.update(Collections.singletonList(new Detection(
                        "EXPOSED_TEST", "EXPOSED", 1f, new BBox(20, 20, 60, 60), true, true))), 100, 100, null);
                CensorOverlayView view = f.view();
                view.measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY));
                view.layout(0, 0, 100, 100);
                Bitmap result = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
                try { view.draw(new Canvas(result)); assertEquals(Color.MAGENTA, result.getPixel(50, 50)); }
                finally { result.recycle(); }
                assertSame(f.initial, f.runtime.configuration());
            });
        }
    }

    @Test public void initialReverseTextBorderAndMotionSettingsExistBeforeActive() throws Exception {
        try (Fixture f = new Fixture(true, true)) {
            f.construct();
            main(() -> {
                CensorAppearance actual = f.appearance();
                assertEquals(CensorAppearance.Type.ERROR_POPUP, actual.getType());
                assertTrue(actual.isReverseMode()); assertEquals(37, actual.getReverseStrength());
                assertEquals("ellipse", actual.getReverseCutoutShape());
                assertTrue(actual.isShowText()); assertTrue(actual.isShowBorder());
                assertEquals(CensorAppearance.BorderEffect.RAINBOW, actual.getBorderEffect());
                assertEquals("Owned title", actual.getErrorTitle());
                assertEquals("Owned body", actual.getErrorMessage());
                assertEquals(300f, (Float) field(f.view(), "maxExtrapolationMs"), 0f);
                assertSame(f.initial, field(f.dependencies, "acknowledged"));
                assertTrue(f.loader.empty());
            });
        }
    }

    @Test public void disconnectAfterQueueBeforeWorkerPreventsDecodeAndCompletion() throws Exception {
        // The manifest deliberately names an unstaged blob. Accidental decoding would fail.
        try (Fixture f = new Fixture(false, false)) {
            f.construct(); f.activate(); AtomicInteger ready = new AtomicInteger();
            main(() -> assertFalse(f.dependencies.reconcileArtwork(ready::incrementAndGet)));
            assertFalse(f.loader.empty());
            Method disconnect = RuntimeCaptureHost.Connection.class.getDeclaredMethod("disconnected");
            disconnect.setAccessible(true); disconnect.invoke(f.connection);
            f.loader.drain();
            assertEquals(0, ready.get()); assertNull(field(f.dependencies, "loaded"));
            assertFalse(f.dependencies.open()); assertTrue(f.dependencies.images.isEmpty());
            assertFalse(f.dependencies.cleanupFailed()); // Missing asset was never opened.
            assertFalse(f.runtime.runEffect(() -> fail("Disconnected owner must be stopped")));
        }
    }

    private static Object field(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void main(Runnable work) { InstrumentationRegistry.getInstrumentation().runOnMainSync(work); }

    private static final class Fixture implements AutoCloseable {
        final Context physical = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final Path root;
        final GuardedContext context;
        final InternalSubData data;
        final InternalOwnerDataBroker broker;
        final ExecutorService lifecycle = Executors.newSingleThreadExecutor();
        final AtomicReference<Thread> ownerThread = new AtomicReference<>();
        final Queue loader = new Queue();
        final ActiveRuntimeCoordinator coordinator;
        final RuntimeLease lease;
        final RuntimeEnvironment runtime;
        final RuntimeConfiguration initial;
        final RuntimeCaptureHost.Connection connection;
        final RuntimeCaptureHost.InternalRequest request;
        final RuntimeExecution<RuntimeConfiguration>.Component component;
        final AtomicReference<Runnable> afterRenderer = new AtomicReference<>();
        final FixtureService service;
        LegacyRenderDependencies dependencies;
        OverlayController controller;

        Fixture(boolean reverse, boolean stageAsset) throws Exception {
            root = Files.createTempDirectory(physical.getCacheDir().toPath(), "owned-legacy-render-");
            context = new GuardedContext(physical);
            Bitmap input = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            try { input.eraseColor(Color.MAGENTA); assertTrue(input.compress(Bitmap.CompressFormat.PNG, 100, encoded)); }
            finally { input.recycle(); }
            byte[] payload = encoded.toByteArray(); Path source = root.resolve("synthetic.png"); Files.write(source, payload);
            String hash = RemoteAssets.digest(source.toFile());
            Map<String, Map<String, Object>> settings = new LinkedHashMap<>();
            InternalConfigurationStore.defaults().forEach((name, values) -> settings.put(name, new LinkedHashMap<>(values)));
            Map<String, Object> main = settings.get(SettingsRepository.PREFERENCES_NAME);
            main.put(SettingsRepository.KEY_CENSOR_TYPE, reverse ? "error_popup" : "custom");
            main.put(SettingsRepository.KEY_DETECTION_PRESET, "high");
            main.put(SettingsRepository.KEY_CENSOR_SIZE_PADDING, 0f);
            main.put(SettingsRepository.KEY_SHOW_BORDER, reverse); main.put(SettingsRepository.KEY_SHOW_TEXT, reverse);
            main.put(SettingsRepository.KEY_BORDER_EFFECT, "rainbow");
            main.put(SettingsRepository.KEY_REVERSE_MODE, reverse); main.put(SettingsRepository.KEY_REVERSE_STRENGTH, 0.37f);
            main.put(SettingsRepository.KEY_REVERSE_CUTOUT_SHAPE, "ellipse");
            main.put(SettingsRepository.KEY_ERROR_TITLE, "Owned title"); main.put(SettingsRepository.KEY_ERROR_TEXT, "Owned body");
            settings.get(InternalConfigurationStore.ARTWORK).put("censor_assets", Collections.singleton(hash));
            FileInternalSubBackend backend = FileInternalSubBackend.createNew(root.toFile(),
                    new InternalProfile("Renderer fixture", "Synthetic data only", "", "", 0), settings);
            if (stageAsset) {
                RemoteAssets assets = new RemoteAssets(backend.scope().directory(InternalStorageScope.Area.ASSETS));
                assets.reserve(hash, payload.length); assets.append(hash, 0, payload); assets.finish(hash);
            }
            Constructor<InternalSubData> dataConstructor = InternalSubData.class.getDeclaredConstructor(FileInternalSubBackend.class);
            dataConstructor.setAccessible(true); data = dataConstructor.newInstance(backend);
            CompletableFuture<Void> visible = new CompletableFuture<>();
            data.execute(() -> { try { data.store.setVisible(data.store.read(), true, System.currentTimeMillis()); visible.complete(null); }
                catch (Throwable failed) { visible.completeExceptionally(failed); } });
            visible.get(10, TimeUnit.SECONDS);
            broker = InternalOwnerDataBroker.attach(context, data, OwnerDataProvisioning.Decision.FIRST_INTERNAL_CREATE, record -> record.visible);
            broker.initialize().get(10, TimeUnit.SECONDS);
            RuntimeMutex mutex = new RuntimeMutex(new Object()); RuntimeAdmissionAuthority authority = new RuntimeAdmissionAuthority(mutex);
            coordinator = new ActiveRuntimeCoordinator(new RuntimeOwnerStore(mutex, new RuntimeOwnerStore.Backend() {
                RuntimeOwnerStore.Record row = RuntimeOwnerStore.Record.idle(0);
                public RuntimeOwnerStore.Record read() { return row; }
                public RuntimeOwnerStore.Outcome compareAndSet(RuntimeOwnerStore.Record before, RuntimeOwnerStore.Record next) {
                    if (!row.equals(before)) return RuntimeOwnerStore.Outcome.CONFLICT;
                    row = next; return RuntimeOwnerStore.Outcome.COMMITTED;
                }
            }), authority, () -> ActiveRuntimeCoordinator.Occupancy.CLEAR, UUID.randomUUID().toString());
            lease = call(() -> {
                InternalSubRecord record = data.acknowledged(); RuntimeTarget target = RuntimeTarget.internal(record.targetId);
                authority.completeAvailability(authority.beginAvailability(target), record.generation, record.configurationRevision, true);
                authority.completeAuthorization(authority.beginAuthorization(target, RuntimeOrigin.LOCAL_UI), 1, true);
                assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.reconcileStartup());
                RuntimeLease admitted = coordinator.reserve(authority.capture(target, RuntimeOrigin.LOCAL_UI), Set.of("internal_accounting", "renderer")).lease;
                assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.beginStarting(admitted, ignored -> true)); return admitted;
            });
            Class<?> ownerType = Class.forName("com.subhub.app.runtime.InternalRuntimeOwner");
            Constructor<?> ownerConstructor = ownerType.getDeclaredConstructor(ActiveRuntimeCoordinator.class, RuntimeLease.class,
                    InternalConfigurationStore.class, InternalSubRecord.class, InternalOwnerDataBroker.class,
                    Executor.class, Runnable.class, OwnerObservation.class);
            ownerConstructor.setAccessible(true);
            Object owner = call(() -> ownerConstructor.newInstance(coordinator, lease, data.configuration, data.acknowledged(), broker,
                    lifecycle, (Runnable) () -> { if (Thread.currentThread() != ownerThread.get()) throw new AssertionError("Wrong fixture owner worker"); },
                    OwnerObservation.capture(System.currentTimeMillis())));
            runtime = (RuntimeEnvironment) field(owner, "runtime"); initial = runtime.configuration();
            Method prepared = ownerType.getDeclaredMethod("prepared"); prepared.setAccessible(true);
            ((CompletableFuture<?>) prepared.invoke(owner)).get(10, TimeUnit.SECONDS);
            Method session = ownerType.getDeclaredMethod("session"); session.setAccessible(true);
            InternalOwnerDataBroker.Session bound = call(() -> (InternalOwnerDataBroker.Session) session.invoke(owner));
            component = call(() -> {
                RuntimeExecution<RuntimeConfiguration>.Component value = runtime.registerComponent("renderer");
                value.installShutdown(afterRenderer::set); value.usesNoConfiguration(); return value;
            });
            AtomicReference<FixtureService> host = new AtomicReference<>(); main(() -> host.set(new FixtureService(context))); service = host.get();
            Method connect = RuntimeCaptureHost.Connection.class.getDeclaredMethod("projection", RuntimeCaptureHost.ProjectionHost.class);
            connect.setAccessible(true);
            connection = (RuntimeCaptureHost.Connection) connect.invoke(null, new RuntimeCaptureHost.ProjectionHost() {
                public Service service() { return service; }
                public void showNeutralForegroundNotification() { fail("No real foreground service in this fixture"); }
                public void stopAfterDrain() { fail("No OS service lifecycle in this fixture"); }
            });
            request = new RuntimeCaptureHost.InternalRequest(connection, new RuntimeCaptureHost.CapturePlan(RuntimeCaptureHost.Mode.PROJECTION, 26),
                    runtime, bound, data.storage, new RuntimeCaptureHost.InternalResources(runtime.identity()), ignored -> { throw new AssertionError("No attachment activation"); });
        }
        void construct() {
            main(() -> assertTrue(runtime.runStartupEffect(() -> {
                dependencies = LegacyRenderDependencies.owned(request, loader);
                controller = OverlayController.owned(service, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, dependencies);
            })));
        }
        void activate() throws Exception {
            assertEquals(ActiveRuntimeCoordinator.Result.OK, call(component::ready));
            assertEquals(ActiveRuntimeCoordinator.Result.OK, call(() -> coordinator.activate(lease)));
        }
        CensorOverlayView view() { return (CensorOverlayView) field(controller, "view"); }
        CensorAppearance appearance() { return (CensorAppearance) field(view(), "appearance"); }
        <T> T call(Callable<T> work) throws Exception {
            return lifecycle.submit(() -> { ownerThread.set(Thread.currentThread()); return work.call(); }).get(10, TimeUnit.SECONDS);
        }
        @Override public void close() throws Exception {
            runtime.requestStop();
            main(() -> { if (controller != null) controller.close(); else if (dependencies != null) dependencies.close(); else loader.shutdown(); });
            loader.drain();
            assertTrue(loader.isTerminated());
            if (controller != null) assertTrue(controller.managedResourcesDrained()); // No window/native frame was installed.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (afterRenderer.get() == null) {
                call(() -> null); if (System.nanoTime() >= deadline) throw new AssertionError("Renderer shutdown was not delivered");
            }
            request.resources.providers.close(); // Fixture never initialized a native detector.
            afterRenderer.getAndSet(null).run();
            while (call(coordinator::anyOccupied)) {
                if (System.nanoTime() >= deadline) throw new AssertionError("Actual owner accounting cleanup did not drain");
                CompletableFuture<Void> returned = new CompletableFuture<>(); data.execute(() -> returned.complete(null)); returned.get(10, TimeUnit.SECONDS);
            }
            broker.closeAfterDrain().get(10, TimeUnit.SECONDS);
            lifecycle.shutdown(); assertTrue(lifecycle.awaitTermination(10, TimeUnit.SECONDS));
            ExecutorService writer = (ExecutorService) field(data, "writes"); writer.shutdown(); assertTrue(writer.awaitTermination(10, TimeUnit.SECONDS));
            assertEquals(0, context.unrelatedPreferences.get());
            Field open = InternalOwnerDataBroker.class.getDeclaredField("OPEN"); open.setAccessible(true);
            Map<?, ?> map = (Map<?, ?>) open.get(null); synchronized (map) { map.remove(data); }
            assertTrue(context.registry.edit().clear().commit());
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
    }
    private static final class FixtureService extends Service {
        FixtureService(Context base) { attachBaseContext(base); }
        @Override public IBinder onBind(Intent intent) { return null; }
    }
    private static final class GuardedContext extends ContextWrapper {
        final SharedPreferences registry;
        final AtomicInteger unrelatedPreferences = new AtomicInteger();
        GuardedContext(Context base) { super(base); registry = base.getSharedPreferences("legacy_render_fixture_" + UUID.randomUUID(), 0); }
        @Override public Context getApplicationContext() { return this; }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            if ("subhub_internal_owner_data_registry".equals(name)) return registry;
            unrelatedPreferences.incrementAndGet(); throw new AssertionError("Unexpected physical preferences: " + name);
        }
    }
    private static final class Queue extends AbstractExecutorService {
        final ArrayDeque<Runnable> pending = new ArrayDeque<>();
        boolean shutdown, running;
        @Override public synchronized void execute(Runnable work) { if (shutdown) throw new IllegalStateException("Closed fixture queue"); pending.add(work); }
        synchronized boolean empty() { return pending.isEmpty(); }
        void drain() {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) throw new AssertionError("Decode must be off main");
            while (true) {
                Runnable work; synchronized (this) { work = pending.poll(); if (work == null) return; running = true; }
                try { work.run(); } finally { synchronized (this) { running = false; } }
            }
        }
        @Override public synchronized void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { throw new AssertionError("Cancellation is not drain"); }
        @Override public synchronized boolean isShutdown() { return shutdown; }
        @Override public synchronized boolean isTerminated() { return shutdown && !running && pending.isEmpty(); }
        @Override public boolean awaitTermination(long time, TimeUnit unit) { throw new AssertionError("No blocking loader cleanup"); }
    }
}
