package com.subhub.app.subliminal;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Authored native View/Handler adapter contracts. The synthetic WindowManager never requests OS
 * overlay permission or attaches a real system window. Real window-token/pixel qualification is
 * separate; these tests must not be presented as having run without an Android host.
 */
@RunWith(AndroidJUnit4.class)
public final class WhisperOverlayControllerAndroidTest {
    @Test public void partialAddWithActualViewParentCannotEscapeTeardown() {
        onMain(() -> {
            Fixture f = new Fixture();
            try {
                f.partialAddFailure = true;
                assertThrows(IllegalStateException.class, () -> f.controller.setEligible(true));
                assertNotNull(f.root.getParent());
                f.controller.close();
                assertNull(f.root.getParent()); assertTrue(f.controller.managedResourcesDrained());
            } finally { f.dispose(); }
        });
    }

    @Test public void silentDetachFailureWithActualParentDoesNotProduceDrainReceipt() {
        onMain(() -> {
            Fixture f = new Fixture();
            try {
                f.controller.setEligible(true); f.silentRemoveFailure = true;
                CountDownLatch receipt = new CountDownLatch(1);
                assertThrows(IllegalStateException.class, () -> f.controller.close(receipt::countDown));
                assertNotNull(f.root.getParent()); assertEquals(1L, receipt.getCount());
                assertFalse(f.controller.managedResourcesDrained());
                f.silentRemoveFailure = false; f.controller.close(); f.controller.close();
                assertEquals(0L, receipt.getCount()); assertTrue(f.controller.managedResourcesDrained());
            } finally { f.dispose(); }
        });
    }

    @Test public void oneForegroundEventDuringBusyGateEventuallyAttaches() throws Exception {
        AtomicReference<Fixture> fixture = new AtomicReference<>();
        try {
            onMain(() -> {
                Fixture f = new Fixture(); fixture.set(f); f.busy = true;
                f.controller.setEligible(true); assertEquals(1L, f.added.getCount());
                f.busy = false; // No second eligibility event.
            });
            assertTrue("Retained foreground request did not apply", fixture.get().added.await(2, TimeUnit.SECONDS));
            onMain(() -> assertNotNull(fixture.get().root.getParent()));
        } finally { onMain(() -> { if (fixture.get() != null) fixture.get().dispose(); }); }
    }

    @Test public void busyUpdateIsNotAcknowledgedAndAppliesLatestTextSizeOnRetry() throws Exception {
        AtomicReference<Fixture> fixture = new AtomicReference<>(); CountDownLatch applied = new CountDownLatch(1);
        try {
            onMain(() -> {
                Fixture f = new Fixture(); fixture.set(f); f.controller.setEligible(true); f.busy = true;
                assertFalse(f.controller.updateSettings(repository(Map.of(
                        SubliminalSettingsRepository.KEY_ADVANCED, true,
                        SubliminalSettingsRepository.KEY_TEXT_SIZE, 26))));
                f.afterEffect = applied::countDown; f.busy = false;
            });
            assertTrue("Retained settings did not apply", applied.await(2, TimeUnit.SECONDS));
            onMain(() -> {
                Fixture f = fixture.get(); TextView message = (TextView) f.root.getChildAt(0);
                float expected = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,
                        26f, f.context.getResources().getDisplayMetrics());
                assertEquals(expected, message.getTextSize(), 0.1f);
            });
        } finally { onMain(() -> { if (fixture.get() != null) fixture.get().dispose(); }); }
    }

    @Test public void noLongerEligibleWhileBusyCannotBeReopenedByRetainedRequest() throws Exception {
        AtomicReference<Fixture> fixture = new AtomicReference<>(); CountDownLatch applied = new CountDownLatch(1);
        try {
            onMain(() -> {
                Fixture f = new Fixture(); fixture.set(f); f.busy = true;
                f.controller.setEligible(true); f.controller.setEligible(false);
                f.afterEffect = applied::countDown; f.busy = false;
            });
            assertTrue(applied.await(2, TimeUnit.SECONDS));
            onMain(() -> assertNull(fixture.get().root));
        } finally { onMain(() -> { if (fixture.get() != null) fixture.get().dispose(); }); }
    }

    private static void onMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }
    private static SubliminalSettingsRepository repository(Map<String, Object> values) {
        SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getAll")) return values;
                    if (method.getName().equals("contains")) return values.containsKey(args[0]);
                    if (method.getName().startsWith("get") && args.length == 2)
                        return values.getOrDefault(args[0], args[1]);
                    throw new AssertionError("Unexpected settings access: " + method.getName());
                });
        return new SubliminalSettingsRepository(preferences);
    }
    private static final class Fixture {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final FrameLayout parent = new FrameLayout(context);
        final CountDownLatch added = new CountDownLatch(1);
        FrameLayout root;
        boolean busy, partialAddFailure, silentRemoveFailure;
        Runnable afterEffect;
        final SubliminalOverlayController controller;
        Fixture() {
            WindowManager windows = (WindowManager) Proxy.newProxyInstance(WindowManager.class.getClassLoader(),
                    new Class<?>[]{WindowManager.class}, (proxy, method, args) -> {
                        if (method.getName().equals("addView")) {
                            root = (FrameLayout) args[0]; parent.addView(root); added.countDown();
                            if (partialAddFailure) throw new IllegalStateException("Synthetic partial attachment");
                            return null;
                        }
                        if (method.getName().equals("removeViewImmediate")) {
                            if (!silentRemoveFailure) parent.removeView((View) args[0]); return null;
                        }
                        throw new AssertionError("Unexpected window access: " + method.getName());
                    });
            Context host = new ContextWrapper(context) {
                @Override public Object getSystemService(String name) {
                    return Context.WINDOW_SERVICE.equals(name) ? windows : super.getSystemService(name);
                }
            };
            controller = new SubliminalOverlayController(host, repository(Map.of()), new SubliminalOverlayController.Effects() {
                @Override public boolean run(Runnable effect) {
                    if (busy) return false;
                    effect.run(); if (afterEffect != null) afterEffect.run(); return true;
                }
                @Override public void recordImpression() { }
            });
        }
        void dispose() {
            silentRemoveFailure = false;
            controller.close();
            assertTrue(controller.managedResourcesDrained());
            assertEquals(0, parent.getChildCount());
        }
    }
}
