package com.subhub.app.stats;

import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Debug;
import android.os.SystemClock;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.subhub.app.MainActivity;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.GlobalSettingsActivity;

import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Opt-in, synthetic emulator profiling. Results are diagnostics, not device-independent deadlines.
 */
@RunWith(AndroidJUnit4.class)
public class UiPerformanceAndroidTest {
    @Test
    public void measureNavigationIdleWorkAndReleasedActivities() throws Exception {
        assumeTrue(
                Boolean.parseBoolean(
                        InstrumentationRegistry.getArguments().getString("measureUi", "false")));
        assumeTrue(android.os.Build.MODEL.contains("sdk_gphone"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        OnboardingState.complete(context);
        ControllerPinManager.setPin(context, "2468");
        List<WeakReference<Activity>> activities = new ArrayList<>();
        JSONArray reports = new JSONArray();
        String phase = InstrumentationRegistry.getArguments().getString("profilePhase", "baseline");
        boolean trace =
                Boolean.parseBoolean(
                        InstrumentationRegistry.getArguments().getString("sampleTrace", "false"));
        // Synthetic local encryption exercises the credential checks without contacting PayPal.
        new com.subhub.app.penance.PayPalCredentialStore(context)
                .save(
                        com.subhub.app.penance.PayPalEnvironment.SANDBOX,
                        "synthetic-ui-client",
                        "synthetic-ui-secret");
        if (trace)
            Debug.startMethodTracingSampling(
                    new File(context.getFilesDir(), "ui-" + phase).getAbsolutePath(),
                    16 * 1024 * 1024,
                    1000);
        try {
            for (Class<? extends Activity> type :
                    Arrays.asList(
                            MainActivity.class,
                            GlobalSettingsActivity.class,
                            StatsActivity.class)) {
                long before = SystemClock.elapsedRealtimeNanos();
                try (ActivityScenario<? extends Activity> scenario =
                        ActivityScenario.launch(
                                new Intent(context, type).setAction(Intent.ACTION_MAIN))) {
                    JSONObject report =
                            new JSONObject()
                                    .put("screen", type.getSimpleName())
                                    .put(
                                            "launch_ms",
                                            (SystemClock.elapsedRealtimeNanos() - before) / 1e6);
                    AtomicInteger layouts = new AtomicInteger();
                    scenario.onActivity(
                            activity -> {
                                activities.add(new WeakReference<>(activity));
                                activity.getWindow()
                                        .getDecorView()
                                        .getViewTreeObserver()
                                        .addOnGlobalLayoutListener(layouts::incrementAndGet);
                            });
                    SystemClock.sleep(2200);
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                    report.put("idle_layouts", layouts.get());
                    if (type == MainActivity.class || type == GlobalSettingsActivity.class) {
                        JSONArray timings = new JSONArray();
                        scenario.onActivity(
                                activity -> {
                                    try {
                                        Method render =
                                                type.getDeclaredMethod(
                                                        type == MainActivity.class
                                                                ? "renderRuntimeState"
                                                                : "displayGroup");
                                        render.setAccessible(true);
                                        for (int i = 0; i < 8; i++) {
                                            long start = SystemClock.elapsedRealtimeNanos();
                                            render.invoke(activity);
                                            timings.put(
                                                    (SystemClock.elapsedRealtimeNanos() - start)
                                                            / 1e6);
                                        }
                                    } catch (Exception error) {
                                        throw new AssertionError(error);
                                    }
                                });
                        report.put("refresh_ms", timings);
                    }
                    reports.put(report);
                }
            }
        } finally {
            if (trace) Debug.stopMethodTracing();
        }
        for (int i = 0; i < 3; i++) {
            Runtime.getRuntime().gc();
            System.runFinalization();
            SystemClock.sleep(300);
        }
        JSONArray retained = new JSONArray();
        for (WeakReference<Activity> reference : activities)
            if (reference.get() != null) retained.put(reference.get().getClass().getSimpleName());
        JSONArray threads = new JSONArray();
        for (Thread thread : Thread.getAllStackTraces().keySet())
            if (thread.isAlive()
                    && (thread.getName().startsWith("subhub-ui-")
                            || thread.getName().equals("subhub-home-achievements")))
                threads.put(thread.getName());
        JSONObject result =
                new JSONObject()
                        .put("owned_threads_after_close", threads)
                        .put("phase", phase)
                        .put("reports", reports)
                        .put("retained_after_close", retained)
                        .put("runtime", new JSONObject(Debug.getRuntimeStats()));
        try (FileOutputStream out =
                new FileOutputStream(new File(context.getFilesDir(), "ui-" + phase + ".json"))) {
            out.write(result.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        android.util.Log.i("SubHubUiProfile", result.toString());
    }
}
