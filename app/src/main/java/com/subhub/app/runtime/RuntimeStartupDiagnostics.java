package com.subhub.app.runtime;

import android.util.Log;
import com.subhub.app.BuildConfig;

/** Debug evidence for startup failures with no exception text or profile data. */
public final class RuntimeStartupDiagnostics {
    private RuntimeStartupDiagnostics() {}

    private static void mark(String stage) {
        if (!BuildConfig.DEBUG) return;
        try { Log.w("SubHubRuntimeStart", stage); }
        catch (RuntimeException unavailable) { /* Android logging may be unavailable to host tests. */ }
    }

    public static void demoWindowFramePrepared() {
        if (BuildConfig.DEBUG) Log.d("SubHubRuntimeStart", "demo_window_frame_prepared");
    }

    public static void report(String component, Throwable failure) {
        if (!BuildConfig.DEBUG) return;
        Throwable cause = failure;
        for (int depth = 0; depth < 4 && cause != null; depth++) {
            int count = 0;
            for (StackTraceElement frame : cause.getStackTrace()) {
                if (!frame.getClassName().startsWith("com.subhub.app.")) continue;
                mark(component + " " + cause.getClass().getSimpleName()
                        + " " + frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber());
                if (++count == 3) break;
            }
            cause = cause.getCause();
        }
    }
}
