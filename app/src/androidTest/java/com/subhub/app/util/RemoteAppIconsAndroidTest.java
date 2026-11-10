package com.subhub.app.util;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.help.HelpActivity;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class RemoteAppIconsAndroidTest {
    private static final String PACKAGE = "com.subhub.iconfixture.absent.viewer";
    private static final int FALLBACK = R.drawable.ic_settings_apps;
    @Rule public final com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture();
    @Rule public final com.subhub.app.UiAutomationStateRule automation = new com.subhub.app.UiAutomationStateRule();

    @Test public void exactPeerGenerationAndPackageUseRemotePixelsAndAuthorizedOfflineCache() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertThrows(PackageManager.NameNotFoundException.class, () -> target.getPackageManager().getApplicationInfo(PACKAGE, 0));
        Source source = new Source(); Queued worker = new Queued();
        RemoteAppIcon red = source.icon("peer-a", "gen-a", PACKAGE, png(Color.RED, 8));
        RemoteAppIcon blue = source.icon("peer-b", "gen-a", PACKAGE, png(Color.BLUE, 8));
        RemoteAppIcon green = source.icon("peer-a", "gen-b", PACKAGE, png(Color.GREEN, 8));
        RemoteAppIcon yellow = source.icon("peer-a", "gen-a", PACKAGE + ".other", png(Color.YELLOW, 8));
        source.publish(Arrays.asList(red, blue, green, yellow), false);
        try (Screen screen = new Screen(source, worker)) {
            List<ImageView> views = new ArrayList<>();
            screen.page.onActivity(activity -> {
                for (RemoteAppIcon identity : Arrays.asList(red, blue, green, yellow, red)) {
                    ImageView view = image(screen.root, activity); views.add(view); screen.icons.bind(view, identity, FALLBACK);
                }
                RemoteAppIcons.Binding original = RemoteAppIcons.binding(views.get(0));
                for (int i = 0; i < 20; i++) screen.icons.bind(views.get(0), red, FALLBACK);
                assertSame(original, RemoteAppIcons.binding(views.get(0)));
            });
            assertEquals(4, worker.size()); worker.drain(); idle();
            screen.page.onActivity(activity -> {
                assertPixels(views.get(0), Color.RED); assertPixels(views.get(1), Color.BLUE);
                assertPixels(views.get(2), Color.GREEN); assertPixels(views.get(3), Color.YELLOW);
                assertPixels(views.get(4), Color.RED); assertNotSame(views.get(0).getDrawable(), views.get(4).getDrawable());
                source.bytes.clear(); // Offline is availability, not a change of authority.
                ImageView cached = image(screen.root, activity); screen.icons.bind(cached, red, FALLBACK);
                assertPixels(cached, Color.RED);
            });
            assertEquals(4, source.calls.get()); assertEquals(Arrays.asList(red, blue, green, yellow), source.requested);
        }
    }

    @Test public void missingDescriptorsStayRemoteAndNeverUseTheViewersInstalledPackageIcon() throws Exception {
        Source source = new Source(); Queued worker = new Queued();
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        RemoteAppIcon installedHere = new RemoteAppIcon("peer", "generation", target.getPackageName(), null);
        RemoteAppIcon absentHere = new RemoteAppIcon("peer", "generation", PACKAGE, null);
        source.publish(Arrays.asList(installedHere, absentHere), false);
        try (Screen screen = new Screen(source, worker)) {
            screen.page.onActivity(activity -> {
                ImageView view = image(screen.root, activity);
                AppIcons.Engine local = new AppIcons.Engine((context, name, pixels) -> { fail("Unexpected local query"); return null; }, worker);
                local.bind(view, target.getPackageName(), FALLBACK, new ColorDrawable(Color.MAGENTA));
                AppIcons.Binding previous = AppIcons.binding(view);
                screen.icons.bind(view, installedHere, FALLBACK);
                assertTrue(previous.cancelled); assertFallback(view); assertNotNull(RemoteAppIcons.binding(view));
                screen.icons.bind(view, absentHere, FALLBACK); assertFallback(view);
            });
            assertEquals(0, source.calls.get()); assertEquals(0, worker.size());
        }
    }

    @Test public void sameImageViewRebindsBothDirectionsWithoutLateLocalOrRemoteOverwrite() throws Exception {
        Source source = new Source(); Queued remote = new Queued(), localWork = new Queued();
        RemoteAppIcon blue = source.icon("peer", "generation", PACKAGE, png(Color.BLUE, 8));
        RemoteAppIcon green = source.icon("peer", "generation", PACKAGE + ".green", png(Color.GREEN, 8));
        source.publish(Arrays.asList(blue, green), false);
        try (Screen screen = new Screen(source, remote)) {
            AtomicReference<ImageView> view = new AtomicReference<>();
            AtomicReference<AppIcons.Engine> local = new AtomicReference<>();
            screen.page.onActivity(activity -> {
                view.set(image(screen.root, activity));
                local.set(new AppIcons.Engine((context, name, pixels) -> solid(Color.RED, 8), localWork));
                local.get().bind(view.get(), PACKAGE + ".local", FALLBACK, null);
                AppIcons.Binding first = AppIcons.binding(view.get());
                screen.icons.bind(view.get(), blue, FALLBACK); assertTrue(first.cancelled);
            });
            localWork.drain(); idle();
            screen.page.onActivity(activity -> assertFallback(view.get()));
            remote.drain(); idle();
            screen.page.onActivity(activity -> {
                assertPixels(view.get(), Color.BLUE);
                screen.icons.bind(view.get(), green, FALLBACK);
                RemoteAppIcons.Binding previous = RemoteAppIcons.binding(view.get());
                local.get().bind(view.get(), PACKAGE + ".known", FALLBACK, new ColorDrawable(Color.MAGENTA));
                assertTrue(previous.cancelled);
            });
            remote.drain(); idle();
            screen.page.onActivity(activity -> {
                assertEquals(Color.MAGENTA, ((ColorDrawable) view.get().getDrawable()).getColor());
                assertNotNull(AppIcons.binding(view.get())); assertNull(RemoteAppIcons.binding(view.get()));
                screen.icons.bind(view.get(), blue, FALLBACK); assertPixels(view.get(), Color.BLUE);
                local.get().bind(view.get(), PACKAGE + ".final", FALLBACK, null);
            });
            localWork.drain(); idle();
            screen.page.onActivity(activity -> {
                assertEquals(Color.RED, ((BitmapDrawable) view.get().getDrawable()).getBitmap().getPixel(2, 2));
                assertTrue(AppIcons.binding(view.get()).resolved); assertNull(RemoteAppIcons.binding(view.get()));
            });
        }
    }

    @Test public void revocationClearsDisplayedAndCachedIconsAndIdenticalHashDoesNotAuthorizeAnotherScope() throws Exception {
        Source source = new Source(); Queued worker = new Queued(); byte[] bytes = png(Color.RED, 8);
        RemoteAppIcon allowed = source.icon("peer-a", "generation-a", PACKAGE, bytes);
        RemoteAppIcon otherPeer = source.icon("peer-b", "generation-a", PACKAGE, bytes);
        RemoteAppIcon otherGeneration = source.icon("peer-a", "generation-b", PACKAGE, bytes);
        source.publish(Collections.singleton(allowed), false);
        try (Screen screen = new Screen(source, worker)) {
            AtomicReference<ImageView> shown = new AtomicReference<>();
            screen.page.onActivity(activity -> { shown.set(image(screen.root, activity)); screen.icons.bind(shown.get(), allowed, FALLBACK); });
            worker.drain(); idle();
            screen.page.onActivity(activity -> {
                assertPixels(shown.get(), Color.RED);
                for (RemoteAppIcon unauthorized : Arrays.asList(otherPeer, otherGeneration)) {
                    ImageView view = image(screen.root, activity); screen.icons.bind(view, unauthorized, FALLBACK); assertFallback(view);
                }
            });
            assertEquals(1, source.calls.get());
            source.publish(Collections.emptyList(), true); idle();
            screen.page.onActivity(activity -> {
                assertFallback(shown.get()); assertEquals(0, screen.icons.cachedBytes()); assertEquals(0, screen.icons.pendingCount());
            });
            source.publish(Collections.singleton(otherGeneration), true); idle(); worker.drain(); idle();
            screen.page.onActivity(activity -> assertFallback(shown.get()));
            assertEquals(Arrays.asList(allowed, otherGeneration), source.requested);
        }
    }

    @Test public void inFlightRemoteResultCannotOverwriteLocalOrAnotherRemoteBinding() throws Exception {
        Source source = new Source(); ExecutorService worker = Executors.newSingleThreadExecutor();
        RemoteAppIcon red = source.icon("peer", "generation-a", PACKAGE, png(Color.RED, 8));
        RemoteAppIcon blue = source.icon("peer", "generation-b", PACKAGE, png(Color.BLUE, 8));
        source.publish(Arrays.asList(red, blue), false);
        source.entered = new CountDownLatch(1); source.gate = new CountDownLatch(1);
        try (Screen screen = new Screen(source, worker)) {
            AtomicReference<ImageView> view = new AtomicReference<>();
            screen.page.onActivity(activity -> { view.set(image(screen.root, activity)); screen.icons.bind(view.get(), red, FALLBACK); });
            assertTrue(source.entered.await(5, TimeUnit.SECONDS));
            screen.page.onActivity(activity -> {
                RemoteAppIcons.Binding original = RemoteAppIcons.binding(view.get());
                screen.icons.bind(view.get(), blue, FALLBACK); assertTrue(original.cancelled);
                AppIcons.Engine local = new AppIcons.Engine((context, name, pixels) -> { fail("Unexpected local query"); return null; }, worker);
                local.bind(view.get(), PACKAGE, FALLBACK, new ColorDrawable(Color.MAGENTA));
            });
            source.gate.countDown(); worker.submit(() -> { }).get(5, TimeUnit.SECONDS); idle();
            screen.page.onActivity(activity -> {
                assertEquals(Color.MAGENTA, ((ColorDrawable) view.get().getDrawable()).getColor());
                assertNull(RemoteAppIcons.binding(view.get())); assertEquals(0, screen.icons.cachedBytes());
                screen.icons.bind(view.get(), blue, FALLBACK);
            });
            await(screen.page, () -> RemoteAppIcons.binding(view.get()).resolved);
            screen.page.onActivity(activity -> assertPixels(view.get(), Color.BLUE));
            assertEquals(Arrays.asList(red, blue), source.requested);
        } finally {
            source.gate.countDown(); worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void deliveryRechecksCurrentAuthorityEvenBeforeInvalidationNotificationIsProcessed() throws Exception {
        Source source = new Source(); Queued worker = new Queued();
        RemoteAppIcon icon = source.icon("peer", "generation", PACKAGE, png(Color.BLUE, 8));
        source.publish(Collections.singleton(icon), false);
        source.afterLoad = () -> source.authority = RemoteAppIcons.Authority.EMPTY;
        try (Screen screen = new Screen(source, worker)) {
            AtomicReference<ImageView> view = new AtomicReference<>();
            screen.page.onActivity(activity -> { view.set(image(screen.root, activity)); screen.icons.bind(view.get(), icon, FALLBACK); });
            worker.drain(); idle();
            screen.page.onActivity(activity -> { assertFallback(view.get()); assertEquals(0, screen.icons.cachedBytes()); });
            assertEquals(1, source.calls.get());
        }
    }

    @Test public void detachDuringLoadAndCloseMakeDelayedResultsInert() throws Exception {
        Source source = new Source(); ExecutorService worker = Executors.newSingleThreadExecutor();
        RemoteAppIcon icon = source.icon("peer", "generation", PACKAGE, png(Color.GREEN, 8));
        source.publish(Collections.singleton(icon), false);
        source.entered = new CountDownLatch(1); source.gate = new CountDownLatch(1);
        try (Screen screen = new Screen(source, worker)) {
            AtomicReference<ImageView> view = new AtomicReference<>();
            screen.page.onActivity(activity -> { view.set(image(screen.root, activity)); screen.icons.bind(view.get(), icon, FALLBACK); });
            assertTrue(source.entered.await(5, TimeUnit.SECONDS));
            screen.page.onActivity(activity -> {
                screen.root.removeView(view.get()); assertTrue(RemoteAppIcons.binding(view.get()).detached);
            });
            source.gate.countDown(); worker.submit(() -> { }).get(5, TimeUnit.SECONDS); idle();
            screen.page.onActivity(activity -> {
                assertFallback(view.get()); screen.root.addView(view.get());
            });
            await(screen.page, () -> RemoteAppIcons.binding(view.get()).resolved);
            screen.page.onActivity(activity -> assertPixels(view.get(), Color.GREEN));
            source.entered = new CountDownLatch(1); source.gate = new CountDownLatch(1);
            RemoteAppIcon late = source.icon("peer", "later-generation", PACKAGE, png(Color.RED, 8));
            source.publish(Arrays.asList(icon, late), false);
            screen.page.onActivity(activity -> screen.icons.bind(view.get(), late, FALLBACK));
            assertTrue(source.entered.await(5, TimeUnit.SECONDS));
            screen.page.onActivity(activity -> screen.icons.close());
            source.gate.countDown(); worker.submit(() -> { }).get(5, TimeUnit.SECONDS); idle();
            screen.page.onActivity(activity -> { assertNotNull(view.get().getImageTintList()); assertNull(RemoteAppIcons.binding(view.get())); });
            assertEquals(0, source.listeners.size());
        } finally {
            source.gate.countDown(); worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void malformedAndOversizedBytesNeverReachDecoderAndNegativeCacheIsScoped() throws Exception {
        Source source = new Source(); Queued worker = new Queued(); AtomicInteger decodes = new AtomicInteger();
        byte[] valid = png(Color.RED, 8), wrongHash = valid.clone(), badSignature = valid.clone(), badCrc = valid.clone();
        wrongHash[wrongHash.length - 1] ^= 1; badSignature[0] = 0; badCrc[29] ^= 1;
        List<RemoteAppIcon> invalid = new ArrayList<>();
        RemoteAppIcon recoverable = source.icon("peer", "hash-mismatch", PACKAGE, valid);
        source.bytes.put(recoverable, wrongHash); invalid.add(recoverable);
        invalid.add(source.icon("peer", "signature", PACKAGE, badSignature));
        invalid.add(source.icon("peer", "checksum", PACKAGE, badCrc));
        RemoteAppIcon dimensions = new RemoteAppIcon("peer", "dimensions", PACKAGE,
                new RemoteAppIcon.Descriptor(hash(valid), valid.length, 9, 8));
        source.bytes.put(dimensions, valid); invalid.add(dimensions);
        RemoteAppIcon oversized = source.icon("peer", "oversized", PACKAGE, valid);
        source.bytes.put(oversized, new byte[RemoteAppIcon.MAX_BYTES + 1]); invalid.add(oversized);
        RemoteAppIcon shortBytes = source.icon("peer", "short", PACKAGE, valid);
        source.bytes.put(shortBytes, Arrays.copyOf(valid, valid.length - 1)); invalid.add(shortBytes);
        source.publish(invalid, false);
        try (Screen screen = new Screen(source, worker, (identity, bytes, pixels) -> {
            decodes.incrementAndGet(); return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        })) {
            List<ImageView> views = new ArrayList<>();
            screen.page.onActivity(activity -> {
                for (RemoteAppIcon identity : invalid) {
                    ImageView view = image(screen.root, activity); views.add(view); screen.icons.bind(view, identity, FALLBACK);
                }
            });
            worker.drain(); idle();
            screen.page.onActivity(activity -> {
                for (int i = 0; i < invalid.size(); i++) {
                    assertFallback(views.get(i)); screen.icons.bind(views.get(i), invalid.get(i), FALLBACK);
                }
                assertEquals(invalid.size(), screen.icons.missingCount());
            });
            assertEquals(invalid.size(), source.calls.get()); assertEquals(0, worker.size()); assertEquals(0, decodes.get());
            source.bytes.put(recoverable, valid); source.publish(invalid, true); idle(); worker.drain(); idle();
            screen.page.onActivity(activity -> assertPixels(views.get(0), Color.RED));
            assertEquals(1, decodes.get());
        }
    }

    @Test public void requestedPixelSizeIsPartOfTheCompleteCacheAndPendingKey() throws Exception {
        Source source = new Source(); Queued worker = new Queued();
        RemoteAppIcon icon = source.icon("peer", "generation", PACKAGE, png(Color.CYAN, 128));
        source.publish(Collections.singleton(icon), false);
        try (Screen screen = new Screen(source, worker)) {
            AtomicReference<ImageView> small = new AtomicReference<>(), large = new AtomicReference<>();
            screen.page.onActivity(activity -> {
                small.set(image(screen.root, density(activity, 160))); large.set(image(screen.root, density(activity, 320)));
                screen.icons.bind(small.get(), icon, FALLBACK); screen.icons.bind(large.get(), icon, FALLBACK);
                assertEquals(2, screen.icons.pendingCount());
            });
            worker.drain(); idle();
            screen.page.onActivity(activity -> {
                assertPixels(small.get(), Color.CYAN); assertPixels(large.get(), Color.CYAN);
                assertEquals(48, ((BitmapDrawable) small.get().getDrawable()).getBitmap().getWidth());
                assertEquals(96, ((BitmapDrawable) large.get().getDrawable()).getBitmap().getWidth());
            });
            assertEquals(2, source.calls.get());
        }
    }

    @Test public void rejectedWorkerQueueKeepsFallbackAndAllowsAnExactRetry() throws Exception {
        Source source = new Source(); Queued worker = new Queued();
        RemoteAppIcon icon = source.icon("peer", "generation", PACKAGE, png(Color.BLUE, 8));
        source.publish(Collections.singleton(icon), false);
        for (int i = 0; i < RemoteAppIcons.MAX_PENDING; i++) worker.execute(() -> { });
        try (Screen screen = new Screen(source, worker)) {
            AtomicReference<ImageView> view = new AtomicReference<>();
            screen.page.onActivity(activity -> {
                view.set(image(screen.root, activity)); screen.icons.bind(view.get(), icon, FALLBACK);
                assertFallback(view.get()); assertEquals(0, screen.icons.pendingCount()); assertEquals(0, screen.icons.missingCount());
            });
            worker.drain(); idle();
            screen.page.onActivity(activity -> screen.icons.bind(view.get(), icon, FALLBACK));
            worker.drain(); idle();
            screen.page.onActivity(activity -> assertPixels(view.get(), Color.BLUE));
            assertEquals(1, source.calls.get());
        }
    }

    @Test public void decodedNegativePendingAndWeakViewRegistriesRemainBounded() throws Exception {
        Source source = new Source(); Queued worker = new Queued(); List<RemoteAppIcon> catalog = new ArrayList<>();
        byte[] bytes = png(Color.YELLOW, 128);
        for (int i = 0; i < 80; i++) catalog.add(source.icon("peer", "generation", PACKAGE + ".image" + i, bytes));
        for (int i = 0; i < 192; i++) catalog.add(new RemoteAppIcon("peer", "generation", PACKAGE + ".missing" + i, catalog.get(0).descriptor));
        RemoteAppIcon legacy = new RemoteAppIcon("peer", "generation", PACKAGE + ".legacy", null); catalog.add(legacy);
        source.publish(catalog, false);
        try (Screen screen = new Screen(source, worker)) {
            screen.page.onActivity(activity -> {
                Context context = density(activity, 480);
                for (int i = 0; i < 80; i++) screen.icons.bind(image(screen.root, context), catalog.get(i), FALLBACK);
                assertEquals(RemoteAppIcons.MAX_PENDING, screen.icons.pendingCount()); assertEquals(RemoteAppIcons.MAX_PENDING, worker.size());
            });
            worker.drain(); idle();
            screen.page.onActivity(activity -> {
                for (int i = 64; i < 80; i++) screen.icons.bind((ImageView) screen.root.getChildAt(i), catalog.get(i), FALLBACK);
            });
            worker.drain(); idle();
            screen.page.onActivity(activity -> assertEquals(RemoteAppIcons.CACHE_BYTES, screen.icons.cachedBytes()));
            for (int batch = 0; batch < 3; batch++) {
                int start = 80 + batch * 64;
                screen.page.onActivity(activity -> {
                    for (int i = start; i < start + 64; i++) screen.icons.bind(image(screen.root, activity), catalog.get(i), FALLBACK);
                });
                worker.drain(); idle();
            }
            screen.page.onActivity(activity -> {
                assertEquals(RemoteAppIcons.MAX_MISSING, screen.icons.missingCount());
                assertTrue(screen.icons.cachedBytes() <= RemoteAppIcons.CACHE_BYTES); assertEquals(0, screen.icons.pendingCount());
                for (int i = 0; i < 300; i++) screen.icons.bind(image(screen.root, activity), legacy, FALLBACK);
                assertEquals(RemoteAppIcons.MAX_BINDINGS, screen.icons.bindingCount());
                assertNull(RemoteAppIcons.binding((ImageView) screen.root.getChildAt(0)));
            });
        }
    }

    private static final class Source implements RemoteAppIcons.Provider {
        volatile RemoteAppIcons.Authority authority = RemoteAppIcons.Authority.EMPTY;
        final Map<RemoteAppIcon, byte[]> bytes = new ConcurrentHashMap<>();
        final List<RemoteAppIcon> requested = new CopyOnWriteArrayList<>();
        final List<Runnable> listeners = new CopyOnWriteArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        volatile CountDownLatch gate, entered;
        volatile Runnable afterLoad;
        RemoteAppIcon icon(String peer, String generation, String name, byte[] image) throws Exception {
            // Read the synthetic IHDR directly so malformed fixtures never invoke a decoder here.
            java.nio.ByteBuffer header = java.nio.ByteBuffer.wrap(image, 16, 8);
            int width = header.getInt(), height = header.getInt();
            RemoteAppIcon icon = new RemoteAppIcon(peer, generation, name,
                    new RemoteAppIcon.Descriptor(hash(image), image.length, width, height));
            bytes.put(icon, image); return icon;
        }
        void publish(java.util.Collection<RemoteAppIcon> identities, boolean notify) {
            authority = new RemoteAppIcons.Authority(identities);
            if (notify) for (Runnable listener : listeners) listener.run();
        }
        @Override public RemoteAppIcons.Authority currentAuthority() { return authority; }
        @Override public void addInvalidationListener(Runnable listener) { listeners.add(listener); }
        @Override public void removeInvalidationListener(Runnable listener) { listeners.remove(listener); }
        @Override public byte[] load(RemoteAppIcon identity) throws Exception {
            assertNotSame(Looper.getMainLooper(), Looper.myLooper()); assertFalse(Thread.holdsLock(this));
            calls.incrementAndGet(); requested.add(identity);
            CountDownLatch started = entered, blocked = gate;
            if (started != null) started.countDown(); if (blocked != null) assertTrue(blocked.await(8, TimeUnit.SECONDS));
            byte[] result = bytes.get(identity); Runnable hook = afterLoad; if (hook != null) hook.run(); return result;
        }
    }

    private static final class Queued implements Executor {
        final ArrayBlockingQueue<Runnable> tasks = new ArrayBlockingQueue<>(RemoteAppIcons.MAX_PENDING);
        @Override public void execute(Runnable task) { if (!tasks.offer(task)) throw new RejectedExecutionException(); }
        int size() { return tasks.size(); }
        void drain() { Runnable task; while ((task = tasks.poll()) != null) task.run(); }
    }

    private static final class Screen implements AutoCloseable {
        final ActivityScenario<HelpActivity> page = ActivityScenario.launch(HelpActivity.class);
        LinearLayout root; RemoteAppIcons icons;
        Screen(Source source, Executor worker) { this(source, worker, null); }
        Screen(Source source, Executor worker, RemoteAppIcons.Decoder decoder) {
            page.onActivity(activity -> {
                root = new LinearLayout(activity); root.setOrientation(LinearLayout.VERTICAL); activity.setContentView(root);
                icons = decoder == null ? new RemoteAppIcons(source, worker) : new RemoteAppIcons(source, worker, decoder);
            });
        }
        @Override public void close() { try { page.onActivity(activity -> icons.close()); } finally { page.close(); } }
    }

    private static Context density(Context context, int dpi) {
        Configuration configuration = new Configuration(context.getResources().getConfiguration()); configuration.densityDpi = dpi;
        return context.createConfigurationContext(configuration);
    }
    private static ImageView image(LinearLayout parent, Context context) {
        ImageView view = new ImageView(context); parent.addView(view, new LinearLayout.LayoutParams(80, 80));
        assertTrue(view.isAttachedToWindow()); return view;
    }
    private static void assertPixels(ImageView view, int color) {
        assertTrue(RemoteAppIcons.binding(view).resolved); assertNull(view.getImageTintList());
        assertEquals(color, ((BitmapDrawable) view.getDrawable()).getBitmap().getPixel(2, 2));
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, view.getImportantForAccessibility());
    }
    private static void assertFallback(ImageView view) {
        assertNotNull(view.getDrawable()); assertNotNull(view.getImageTintList());
        assertFalse(RemoteAppIcons.binding(view).resolved);
    }
    private static Bitmap solid(int color, int pixels) {
        Bitmap bitmap = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888); bitmap.eraseColor(color); return bitmap;
    }
    private static byte[] png(int color, int pixels) {
        Bitmap bitmap = solid(color, pixels); ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); bitmap.recycle(); return output.toByteArray();
    }
    private static String hash(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return hex.toString();
    }
    private static void idle() { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); }
    private interface Condition { boolean ready(); }
    private static void await(ActivityScenario<?> page, Condition condition) throws Exception {
        long end = SystemClock.uptimeMillis() + 5000;
        do {
            AtomicBoolean done = new AtomicBoolean(); page.onActivity(activity -> done.set(condition.ready()));
            if (done.get()) return; SystemClock.sleep(20);
        } while (SystemClock.uptimeMillis() < end);
        fail("Remote icon callback did not settle");
    }
}
