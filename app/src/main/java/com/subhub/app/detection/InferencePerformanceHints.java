package com.subhub.app.detection;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.os.PerformanceHintManager;
import android.os.Process;
import android.util.Log;

/** Android Dynamic Performance Framework session for the single inference worker. */
@SuppressLint("NewApi") // Every platform call is disabled unless API 31 created the manager.
final class InferencePerformanceHints implements AutoCloseable {
    private static final String TAG = "InferenceHints";
    private final PerformanceHintManager manager;
    private PerformanceHintManager.Session session;
    private int sessionThreadId = -1;
    private long targetNanos = 33_000_000L;

    InferencePerformanceHints(Context context) {
        manager = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? context.getSystemService(PerformanceHintManager.class) : null;
    }

    void begin(long requestedIntervalMillis) {
        if (manager == null) return;
        int threadId = Process.myTid();
        long requestedTarget = Math.max(16L,
                requestedIntervalMillis > 0L ? requestedIntervalMillis : 33L) * 1_000_000L;
        try {
            if (session == null || sessionThreadId != threadId) {
                closeSession();
                targetNanos = requestedTarget;
                session = manager.createHintSession(new int[]{threadId}, targetNanos);
                sessionThreadId = threadId;
            } else if (targetNanos != requestedTarget) {
                targetNanos = requestedTarget;
                session.updateTargetWorkDuration(targetNanos);
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Performance hint session unavailable", error);
            closeSession();
        }
    }

    void report(long actualNanos) {
        if (session == null || actualNanos <= 0L) return;
        try {
            session.reportActualWorkDuration(actualNanos);
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not report inference duration", error);
            closeSession();
        }
    }

    private void closeSession() {
        if (session != null) session.close();
        session = null;
        sessionThreadId = -1;
    }

    @Override public void close() { closeSession(); }
}
