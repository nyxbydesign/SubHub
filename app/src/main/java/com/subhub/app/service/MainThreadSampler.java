package com.subhub.app.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import com.subhub.app.BuildConfig;

/** One-shot emulator debug diagnostic, with no node, screen, or render authority. */
final class MainThreadSampler {
    private MainThreadSampler() {}

    static void startIfArmed(Context context) {
        if (!BuildConfig.DEBUG || !(Build.HARDWARE.contains("ranchu")
                || Build.HARDWARE.contains("goldfish"))) return;
        SharedPreferences preferences = context.getSharedPreferences(
                "main_thread_probe", Context.MODE_PRIVATE);
        if (!preferences.getBoolean("armed", false)) return;
        preferences.edit().remove("armed").apply();
        Thread main = Looper.getMainLooper().getThread();
        Thread sampler = new Thread(() -> sample(main), "SubHub-main-probe");
        sampler.setDaemon(true);
        sampler.start();
    }

    private static void sample(Thread main) {
        long deadline = SystemClock.uptimeMillis() + 45000L;
        Log.i("MainThreadProbe", "MAIN_SAMPLE_START uptimeMs=" + SystemClock.uptimeMillis());
        int count = 0;
        while (SystemClock.uptimeMillis() < deadline) {
            long started = SystemClock.uptimeMillis();
            StackTraceElement[] stack = main.getStackTrace();
            StringBuilder locations = new StringBuilder();
            for (int index = 0; index < Math.min(stack.length, 24); index++) {
                StackTraceElement frame = stack[index];
                if (index > 0) locations.append(';');
                locations.append(frame.getClassName()).append('#')
                        .append(frame.getMethodName()).append(':').append(frame.getLineNumber());
            }
            Log.i("MainThreadProbe", "MAIN_SAMPLE uptimeMs=" + started
                    + " sampleMs=" + (SystemClock.uptimeMillis() - started)
                    + " state=" + main.getState().name() + " stack=" + locations);
            count++;
            SystemClock.sleep(100L);
        }
        Log.i("MainThreadProbe", "MAIN_SAMPLE_END count=" + count);
    }
}
