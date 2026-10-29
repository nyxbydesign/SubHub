package com.subhub.app.util;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.subhub.app.R;
import com.subhub.app.help.HelpActivity;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class AppIconsAndroidTest {
    @Rule public final com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture();
    @Rule public final com.subhub.app.UiAutomationStateRule automation = new com.subhub.app.UiAutomationStateRule();

    @Test public void offMainLoadsCoalesceAndRecycledRowsKeepTheirCurrentPackage() throws Exception {
        CountDownLatch alpha = new CountDownLatch(1), beta = new CountDownLatch(1), enteredAlpha = new CountDownLatch(1), enteredBeta = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger(); ExecutorService worker = Executors.newSingleThreadExecutor();
        AppIcons.Engine icons = new AppIcons.Engine((context, name, pixels) -> {
            assertNotSame(Looper.getMainLooper(), Looper.myLooper()); calls.incrementAndGet();
            boolean first = name.endsWith("alpha"); (first ? enteredAlpha : enteredBeta).countDown();
            assertTrue((first ? alpha : beta).await(8, TimeUnit.SECONDS));
            Bitmap bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888); bitmap.eraseColor(first ? Color.RED : Color.BLUE); return bitmap;
        }, worker);
        AtomicReference<ImageView> reused = new AtomicReference<>(), second = new AtomicReference<>(), cached = new AtomicReference<>();
        try (ActivityScenario<HelpActivity> page = ActivityScenario.launch(HelpActivity.class)) {
            page.onActivity(activity -> {
                LinearLayout root = new LinearLayout(activity); root.setOrientation(LinearLayout.VERTICAL); activity.setContentView(root);
                reused.set(image(root)); second.set(image(root)); cached.set(image(root));
                icons.bind(reused.get(), "com.example.alpha", R.drawable.ic_settings_apps, null);
                icons.bind(second.get(), "com.example.alpha", R.drawable.ic_settings_apps, null);
                icons.bind(reused.get(), "com.example.beta", R.drawable.ic_settings_apps, null);
            });
            assertTrue(enteredAlpha.await(5, TimeUnit.SECONDS)); alpha.countDown();
            assertTrue(enteredBeta.await(5, TimeUnit.SECONDS));
            await(page, () -> AppIcons.binding(second.get()).resolved);
            page.onActivity(activity -> {
                assertEquals(Color.RED, pixel(second.get())); assertFalse(AppIcons.binding(reused.get()).resolved);
                assertEquals("com.example.beta", AppIcons.binding(reused.get()).packageName);
            });
            beta.countDown(); await(page, () -> AppIcons.binding(reused.get()).resolved);
            page.onActivity(activity -> {
                assertEquals(Color.BLUE, pixel(reused.get())); assertNull(reused.get().getImageTintList());
                icons.bind(cached.get(), "com.example.beta", R.drawable.ic_settings_apps, null);
                assertTrue(AppIcons.binding(cached.get()).resolved); assertEquals(Color.BLUE, pixel(cached.get()));
                assertNotSame(reused.get().getDrawable(), cached.get().getDrawable());
            });
            assertEquals(2, calls.get());
        } finally { alpha.countDown(); beta.countDown(); worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test public void missingAndDetachedAppsKeepFallbackWithoutLateViewMutation() throws Exception {
        CountDownLatch gate = new CountDownLatch(1), entered = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger(); ExecutorService worker = Executors.newSingleThreadExecutor();
        AppIcons.Engine icons = new AppIcons.Engine((context, name, pixels) -> {
            assertNotSame(Looper.getMainLooper(), Looper.myLooper()); calls.incrementAndGet();
            if (name.endsWith("missing")) throw new android.content.pm.PackageManager.NameNotFoundException(name);
            entered.countDown(); assertTrue(gate.await(8, TimeUnit.SECONDS));
            Bitmap bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888); bitmap.eraseColor(Color.GREEN); return bitmap;
        }, worker);
        AtomicReference<ImageView> missing = new AtomicReference<>(), detached = new AtomicReference<>();
        AtomicReference<LinearLayout> root = new AtomicReference<>();
        try (ActivityScenario<HelpActivity> page = ActivityScenario.launch(HelpActivity.class)) {
            page.onActivity(activity -> {
                root.set(new LinearLayout(activity)); activity.setContentView(root.get());
                missing.set(image(root.get())); detached.set(image(root.get()));
                icons.bind(missing.get(), "com.example.missing", R.drawable.ic_settings_apps, null);
                icons.bind(detached.get(), "com.example.late", R.drawable.ic_settings_apps, null);
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            page.onActivity(activity -> {
                icons.bind(missing.get(), "com.example.missing", R.drawable.ic_settings_apps, null);
                assertFalse(AppIcons.binding(missing.get()).resolved); assertNotNull(missing.get().getDrawable());
                assertNotNull(missing.get().getImageTintList());
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, missing.get().getImportantForAccessibility());
                root.get().removeView(detached.get()); assertTrue(AppIcons.binding(detached.get()).detached);
            });
            gate.countDown(); worker.submit(() -> { }).get(5, TimeUnit.SECONDS);
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            page.onActivity(activity -> {
                assertFalse(AppIcons.binding(detached.get()).resolved);
                root.get().addView(detached.get());
                assertTrue(AppIcons.binding(detached.get()).resolved); assertEquals(Color.GREEN, pixel(detached.get()));
            });
            assertEquals(2, calls.get());
        } finally { gate.countDown(); worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    private static ImageView image(LinearLayout parent) {
        ImageView image = new ImageView(parent.getContext()); parent.addView(image, new LinearLayout.LayoutParams(80, 80)); return image;
    }

    @Test public void preloadedCatalogIconsUseIndependentDrawablesWithoutAnotherPackageRead() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AppIcons.Engine icons = new AppIcons.Engine((context, name, pixels) -> { calls.incrementAndGet(); return null; }, Runnable::run);
        try (ActivityScenario<HelpActivity> page = ActivityScenario.launch(HelpActivity.class)) {
            page.onActivity(activity -> {
                LinearLayout root = new LinearLayout(activity); activity.setContentView(root);
                ImageView first = image(root), second = image(root);
                android.graphics.drawable.ColorDrawable source = new android.graphics.drawable.ColorDrawable(Color.RED);
                icons.bind(first, "com.example.catalog", R.drawable.ic_settings_apps, source);
                icons.bind(second, "com.example.catalog", R.drawable.ic_settings_apps, source);
                assertNotSame(first.getDrawable(), second.getDrawable()); assertNull(first.getImageTintList());
                ((android.graphics.drawable.ColorDrawable) first.getDrawable()).setColor(Color.BLUE);
                assertEquals(Color.RED, ((android.graphics.drawable.ColorDrawable) second.getDrawable()).getColor());
                assertEquals(Color.RED, source.getColor()); assertEquals(0, calls.get());
            });
        }
    }
    private static int pixel(ImageView image) { return ((BitmapDrawable) image.getDrawable()).getBitmap().getPixel(2, 2); }
    private interface Condition { boolean ready(); }
    private static void await(ActivityScenario<?> page, Condition condition) throws Exception {
        long end = SystemClock.uptimeMillis() + 5000;
        do { AtomicBoolean done = new AtomicBoolean(); page.onActivity(activity -> done.set(condition.ready()));
            if (done.get()) return; SystemClock.sleep(20);
        } while (SystemClock.uptimeMillis() < end);
        fail("Icon callback did not settle");
    }
}
