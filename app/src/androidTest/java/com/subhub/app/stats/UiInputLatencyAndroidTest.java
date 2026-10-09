package com.subhub.app.stats;

import static org.junit.Assert.*;
import static org.junit.Assume.*;

import android.app.*;
import android.content.*;
import android.graphics.Rect;
import android.os.*;
import android.view.*;
import android.widget.*;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.*;

import com.subhub.app.MainActivity;
import com.subhub.app.R;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.*;

import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Opt-in real touch-to-draw diagnostics; never contacts providers or enables capture. */
@RunWith(AndroidJUnit4.class)
public class UiInputLatencyAndroidTest {
    private final Instrumentation instrument = InstrumentationRegistry.getInstrumentation();
    private final AtomicReference<Class<?>> destination = new AtomicReference<>();
    private final AtomicLong upAt = new AtomicLong(), drawAt = new AtomicLong();
    private volatile CountDownLatch drawn;
    private final List<View> watched = new ArrayList<>();
    private final List<ViewTreeObserver.OnDrawListener> watchers = new ArrayList<>();

    @Test
    public void measureFeatureToggleAndColdAndWarmNavigation() throws Exception {
        assumeTrue(
                Boolean.parseBoolean(
                        InstrumentationRegistry.getArguments().getString("measureUi", "false")));
        assumeTrue(android.os.Build.MODEL.contains("sdk_gphone"));
        Context context = instrument.getTargetContext();
        Application app = (Application) context.getApplicationContext();
        OnboardingState.complete(context);
        ControllerPinManager.setPin(context, "2468");
        new FeatureModuleManager(context).save(true, true, true, false);
        Application.ActivityLifecycleCallbacks observer =
                new Application.ActivityLifecycleCallbacks() {
                    @Override
                    public void onActivityResumed(Activity activity) {
                        watch(
                                activity.getWindow().getDecorView(),
                                () -> destination.get() == activity.getClass());
                    }

                    @Override
                    public void onActivityCreated(Activity a, Bundle b) {}

                    @Override
                    public void onActivityStarted(Activity a) {}

                    @Override
                    public void onActivityPaused(Activity a) {}

                    @Override
                    public void onActivityStopped(Activity a) {}

                    @Override
                    public void onActivitySaveInstanceState(Activity a, Bundle b) {}

                    @Override
                    public void onActivityDestroyed(Activity a) {}
                };
        app.registerActivityLifecycleCallbacks(observer);
        JSONObject report = new JSONObject();
        try (ActivityScenario<MainActivity> home =
                ActivityScenario.launch(
                        new Intent(context, MainActivity.class).setAction(Intent.ACTION_MAIN))) {
            instrument.waitForIdleSync();
            SystemClock.sleep(300);
            begin(GlobalSettingsActivity.class);
            tap(view(MainActivity.class, R.id.nav_settings));
            report.put("home_to_settings_first_draw_ms", awaitDraw());
            Activity settings = activity(GlobalSettingsActivity.class);
            instrument.runOnMainSync(
                    () ->
                            settings.findViewById(android.R.id.content)
                                    .findViewWithTag("settings:features")
                                    .performClick());
            instrument.waitForIdleSync();
            CompoundButton toggle =
                    (CompoundButton) view(GlobalSettingsActivity.class, R.id.switch_module_limits);
            instrument.runOnMainSync(() -> reveal(toggle));
            instrument.waitForIdleSync();
            boolean before = toggle.isChecked();
            AtomicInteger diskWrites = new AtomicInteger();
            AtomicReference<StrictMode.ThreadPolicy> previous = new AtomicReference<>();
            instrument.runOnMainSync(
                    () -> {
                        previous.set(StrictMode.getThreadPolicy());
                        StrictMode.setThreadPolicy(
                                new StrictMode.ThreadPolicy.Builder()
                                        .detectDiskWrites()
                                        .penaltyListener(
                                                Runnable::run, v -> diskWrites.incrementAndGet())
                                        .build());
                        watch(
                                toggle,
                                () -> destination.get() == null && toggle.isChecked() != before);
                    });
            begin(null);
            tap(toggle);
            report.put("feature_toggle_first_draw_ms", awaitDraw());
            instrument.runOnMainSync(
                    () -> {
                        StrictMode.setThreadPolicy(previous.get());
                        assertEquals(!before, toggle.isChecked());
                        assertEquals(!before, new FeatureModuleManager(context).isLimitsEnabled());
                    });
            report.put("toggle_ui_disk_writes", diskWrites.get());
            begin(SettingsActivity.class);
            tap(view(GlobalSettingsActivity.class, R.id.nav_censor));
            report.put("settings_to_censor_first_draw_ms", awaitDraw());
            begin(MainActivity.class);
            tap(view(SettingsActivity.class, R.id.nav_home));
            report.put("censor_to_home_first_draw_ms", awaitDraw());
            begin(GlobalSettingsActivity.class);
            tap(view(MainActivity.class, R.id.nav_settings));
            report.put("warm_home_to_settings_first_draw_ms", awaitDraw());
        } finally {
            app.unregisterActivityLifecycleCallbacks(observer);
            instrument.runOnMainSync(
                    () -> {
                        for (int i = 0; i < watched.size(); i++)
                            if (watched.get(i).getViewTreeObserver().isAlive())
                                watched.get(i)
                                        .getViewTreeObserver()
                                        .removeOnDrawListener(watchers.get(i));
                        for (Activity a :
                                new ArrayList<>(
                                        ActivityLifecycleMonitorRegistry.getInstance()
                                                .getActivitiesInStage(Stage.RESUMED))) a.finish();
                    });
        }
        String phase = InstrumentationRegistry.getArguments().getString("profilePhase", "baseline");
        try (FileOutputStream out =
                new FileOutputStream(new File(context.getFilesDir(), "input-" + phase + ".json"))) {
            out.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        android.util.Log.i("SubHubUiInput", report.toString());
    }

    private void begin(Class<?> target) {
        destination.set(target);
        drawn = new CountDownLatch(1);
        upAt.set(0);
        drawAt.set(0);
    }

    private double awaitDraw() throws Exception {
        assertTrue("Expected state reaches drawing", drawn.await(8, TimeUnit.SECONDS));
        instrument.waitForIdleSync();
        return (drawAt.get() - upAt.get()) / 1e6;
    }

    private void watch(View root, java.util.function.BooleanSupplier ready) {
        ViewTreeObserver.OnDrawListener listener =
                () -> {
                    if (upAt.get() > 0
                            && ready.getAsBoolean()
                            && drawAt.compareAndSet(0, SystemClock.elapsedRealtimeNanos()))
                        drawn.countDown();
                };
        root.getViewTreeObserver().addOnDrawListener(listener);
        watched.add(root);
        watchers.add(listener);
    }

    private Activity activity(Class<?> type) {
        AtomicReference<Activity> value = new AtomicReference<>();
        instrument.runOnMainSync(
                () -> {
                    for (Activity a :
                            ActivityLifecycleMonitorRegistry.getInstance()
                                    .getActivitiesInStage(Stage.RESUMED))
                        if (type.isInstance(a)) value.set(a);
                });
        assertNotNull("Resumed " + type.getSimpleName(), value.get());
        return value.get();
    }

    private View view(Class<?> type, int id) {
        AtomicReference<View> value = new AtomicReference<>();
        Activity activity = activity(type);
        instrument.runOnMainSync(() -> value.set(activity.findViewById(id)));
        assertNotNull(value.get());
        return value.get();
    }

    private void tap(View view) {
        Rect bounds = new Rect();
        instrument.runOnMainSync(
                () -> assertTrue("Visible native touch target", view.getGlobalVisibleRect(bounds)));
        long down = SystemClock.uptimeMillis();
        MotionEvent press =
                MotionEvent.obtain(
                        down, down, MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY(), 0);
        instrument.sendPointerSync(press);
        press.recycle();
        SystemClock.sleep(30);
        upAt.set(SystemClock.elapsedRealtimeNanos());
        MotionEvent release =
                MotionEvent.obtain(
                        down,
                        SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_UP,
                        bounds.centerX(),
                        bounds.centerY(),
                        0);
        instrument.sendPointerSync(release);
        release.recycle();
    }

    private void reveal(View view) {
        ViewParent parent = view.getParent();
        while (parent != null && !(parent instanceof ScrollView)) parent = parent.getParent();
        if (parent instanceof ScrollView) {
            ScrollView scroll = (ScrollView) parent;
            Rect rect = new Rect();
            view.getDrawingRect(rect);
            scroll.offsetDescendantRectToMyCoords(view, rect);
            scroll.scrollTo(0, Math.max(0, rect.top - scroll.getHeight() / 3));
        }
    }
}
