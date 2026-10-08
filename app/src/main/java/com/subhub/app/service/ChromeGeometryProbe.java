package com.subhub.app.service;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import com.subhub.app.BuildConfig;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** One-shot emulator diagnostic of native Chrome resource geometry; never reads node text. */
@SuppressWarnings("deprecation")
final class ChromeGeometryProbe implements AutoCloseable {
    private volatile boolean closed;

    static ChromeGeometryProbe startIfArmed(AccessibilityService service) {
        if (!BuildConfig.DEBUG || !(Build.HARDWARE.contains("ranchu")
                || Build.HARDWARE.contains("goldfish"))) return null;
        android.content.SharedPreferences prefs = service.getSharedPreferences(
                "chrome_geometry_probe", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("enabled", false)) return null;
        prefs.edit().remove("enabled").apply();
        ChromeGeometryProbe probe = new ChromeGeometryProbe();
        Thread thread = new Thread(() -> probe.run(service), "SubHub-chrome-geometry");
        thread.setDaemon(true);
        thread.start();
        return probe;
    }

    static boolean nativeId(String id) {
        return id != null && id.matches("com\\.android\\.chrome:id/[a-zA-Z0-9_]+");
    }

    static boolean relevantId(String id) {
        if (!nativeId(id)) return false;
        return id.contains("toolbar") || id.contains("location_bar") || id.contains("url_bar")
                || id.contains("control_container") || id.contains("compositor") || id.endsWith("/content");
    }

    private void run(AccessibilityService service) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        long deadline = SystemClock.uptimeMillis() + 30000L;
        Set<String> ids = new LinkedHashSet<>();
        int sample = 0;
        Log.i("ScreenshotA11y", "CHROME_GEOMETRY_BEGIN uptimeMs=" + SystemClock.uptimeMillis());
        try {
            while (!closed && SystemClock.uptimeMillis() < deadline && sample < 200) {
                AccessibilityNodeInfo root = service.getRootInActiveWindow();
                if (root == null) { SystemClock.sleep(100); continue; }
                try {
                    CharSequence pkg = root.getPackageName();
                    if (pkg == null || !"com.android.chrome".contentEquals(pkg)) {
                        SystemClock.sleep(100);
                        continue;
                    }
                    if (ids.isEmpty()) {
                        // Discovery consumes its own node copy, leaving this root owned here.
                        ids.addAll(discover(AccessibilityNodeInfo.obtain(root), deadline));
                        if (ids.isEmpty()) break;
                    }
                    for (String id : ids) {
                        if (closed || SystemClock.uptimeMillis() >= deadline) break;
                        long start = SystemClock.uptimeMillis();
                        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
                        Rect bounds = new Rect();
                        boolean visible = false;
                        String status = nodes == null || nodes.isEmpty() ? "missing"
                                : nodes.size() == 1 ? "ok" : "ambiguous";
                        try {
                            if (nodes != null && nodes.size() == 1 && nodes.get(0) != null) {
                                AccessibilityNodeInfo node = nodes.get(0);
                                node.getBoundsInScreen(bounds);
                                visible = node.isVisibleToUser();
                            }
                        } finally {
                            if (nodes != null) for (AccessibilityNodeInfo node : nodes) {
                                if (node != null) node.recycle();
                            }
                        }
                        Log.i("ScreenshotA11y", "CHROME_GEOMETRY sample=" + sample
                                + " id=" + id.substring(id.indexOf('/') + 1)
                                + " startMs=" + start + " endMs=" + SystemClock.uptimeMillis()
                                + " status=" + status + " visible=" + visible
                                + " rect=" + bounds.left + ',' + bounds.top + ',' + bounds.right + ',' + bounds.bottom);
                    }
                    sample++;
                } finally { root.recycle(); }
                SystemClock.sleep(100);
            }
        } catch (RuntimeException error) {
            Log.i("ScreenshotA11y", "CHROME_GEOMETRY_FAILURE type=" + error.getClass().getSimpleName());
        } finally {
            Log.i("ScreenshotA11y", "CHROME_GEOMETRY_END samples=" + sample + " ids=" + ids.size());
        }
    }

    private Set<String> discover(AccessibilityNodeInfo ownedRoot, long deadline) {
        Set<String> ids = new LinkedHashSet<>();
        ArrayDeque<AccessibilityNodeInfo> nodes = new ArrayDeque<>();
        nodes.add(ownedRoot);
        int fetched = 1;
        try {
            while (!nodes.isEmpty() && !closed && SystemClock.uptimeMillis() < deadline) {
                AccessibilityNodeInfo node = nodes.removeFirst();
                try {
                    String id = node.getViewIdResourceName();
                    if (nativeId(id)) {
                        Rect bounds = new Rect();
                        node.getBoundsInScreen(bounds);
                        Log.i("ScreenshotA11y", "CHROME_NODE id=" + id.substring(id.indexOf('/') + 1)
                                + " rect=" + bounds.left + ',' + bounds.top + ',' + bounds.right + ',' + bounds.bottom);
                        if (relevantId(id) && ids.size() < 8) ids.add(id);
                    }
                    for (int i = 0; i < node.getChildCount() && fetched < 96; i++) {
                        if (closed || SystemClock.uptimeMillis() >= deadline) break;
                        fetched++;
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) nodes.addLast(child);
                    }
                } finally { node.recycle(); }
            }
        } finally {
            while (!nodes.isEmpty()) nodes.removeFirst().recycle();
        }
        return ids;
    }

    @Override public void close() { closed = true; }
}
