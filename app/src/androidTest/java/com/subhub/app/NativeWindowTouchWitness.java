package com.subhub.app;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Opt-in native input evidence; screenshots remain in the owned app's private cache. */
public final class NativeWindowTouchWitness implements AutoCloseable {
    private final Instrumentation instrumentation;
    private final Activity activity;
    private final View target;
    private final File directory;
    private final JSONArray events = new JSONArray();
    private Window.Callback original, forwarding;
    private boolean installed, closed;

    private NativeWindowTouchWitness(Instrumentation instrumentation, Activity activity, int targetId, String label)
            throws Exception {
        this.instrumentation = instrumentation; this.activity = activity;
        target = activity.findViewById(targetId);
        String run = InstrumentationRegistry.getArguments().getString("evidence_run", "header-touch");
        assertTrue("Safe evidence run token", run.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,120}"));
        assertTrue(label.matches("[A-Za-z0-9_-]+"));
        File parent = new File(activity.getCacheDir(), "header-touch-witness");
        assertTrue(parent.isDirectory() || parent.mkdirs());
        directory = new File(parent, run + "-" + label);
        assertFalse("Never overwrite native input evidence", directory.exists());
        assertTrue(directory.mkdir());
    }

    public static NativeWindowTouchWitness begin(Instrumentation instrumentation, Activity activity, int targetId,
            String label) throws Exception {
        NativeWindowTouchWitness witness = new NativeWindowTouchWitness(instrumentation, activity, targetId, label);
        try {
            witness.prepare();
            return witness;
        } catch (Exception | Error failure) {
            try { witness.close(); } catch (Exception | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility") // Returns false; the real View still owns click dispatch.
    private void prepare() throws Exception {
        assertTrue("Fresh-frame witness requires API 29+", Build.VERSION.SDK_INT >= 29);
        CountDownLatch committed = new CountDownLatch(1);
        AtomicLong frameAt = new AtomicLong();
        AtomicReference<Exception> problem = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            View decor = activity.getWindow().getDecorView();
            if (!decor.isHardwareAccelerated()) { problem.set(new IllegalStateException("Hardware frame unavailable")); return; }
            if (Build.VERSION.SDK_INT >= 29) decor.getViewTreeObserver().registerFrameCommitCallback(() -> {
                frameAt.set(SystemClock.uptimeMillis()); committed.countDown();
            });
            decor.invalidate();
        });
        if (problem.get() != null) throw problem.get();
        assertTrue("A fresh hardware frame must commit", committed.await(5, TimeUnit.SECONDS));
        AtomicReference<JSONObject> before = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try {
                before.set(windowState().put("frameCommittedAtUptimeMillis", frameAt.get()));
                original = activity.getWindow().getCallback();
                forwarding = (Window.Callback) Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{Window.Callback.class}, (proxy, method, arguments) -> {
                            JSONObject event = "dispatchTouchEvent".equals(method.getName())
                                    ? touch("window", (MotionEvent) arguments[0]) : null;
                            if (event != null) record(new JSONObject(event.toString()).put("phase", "enter"));
                            Object result;
                            try { result = method.invoke(original, arguments); }
                            catch (InvocationTargetException failure) {
                                if (event != null) record(event.put("phase", "throw")
                                        .put("exceptionClass", failure.getCause().getClass().getName()));
                                throw failure.getCause();
                            }
                            if (event != null) record(event.put("phase", "return").put("returned", result));
                            return result;
                        });
                activity.getWindow().setCallback(forwarding);
                target.setOnTouchListener((view, event) -> {
                    try { record(touch("target", event).put("returned", false)); }
                    catch (org.json.JSONException failure) { throw new AssertionError(failure); }
                    return false;
                });
                installed = true;
            } catch (Exception failure) { problem.set(failure); }
        });
        if (problem.get() != null) throw problem.get();
        Bitmap screenshot = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY).takeScreenshot();
        assertNotNull("Actual pre-tap screenshot", screenshot);
        try (OutputStream file = Files.newOutputStream(new File(directory, "before.png").toPath(),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, file));
            before.get().put("screenshotWidth", screenshot.getWidth()).put("screenshotHeight", screenshot.getHeight());
        } finally { screenshot.recycle(); }
        write("before.json", before.get());
        System.out.println("Tasks header native touch evidence: " + directory.getAbsolutePath());
    }

    private JSONObject windowState() throws Exception {
        Window window = activity.getWindow();
        View decor = window.getDecorView();
        JSONObject state = new JSONObject().put("uptimeMillis", SystemClock.uptimeMillis())
                .put("windowTokenIdentity", identity(decor.getWindowToken()))
                .put("applicationWindowTokenIdentity", identity(decor.getApplicationWindowToken()))
                .put("windowFlags", window.getAttributes().flags).put("windowType", window.getAttributes().type)
                .put("windowAlpha", window.getAttributes().alpha).put("windowFocus", decor.hasWindowFocus())
                .put("originalCallbackClass", window.getCallback().getClass().getName());
        int[] origin = new int[2]; target.getLocationOnScreen(origin);
        state.put("targetCenterScreenX", origin[0] + target.getWidth() / 2f)
                .put("targetCenterScreenY", origin[1] + target.getHeight() / 2f);
        if (target instanceof TextView) {
            TextView text = (TextView) target;
            state.put("targetPressed", text.isPressed()).put("targetFocused", text.isFocused())
                    .put("targetFocusableInTouchMode", text.isFocusableInTouchMode())
                    .put("targetTextSelectable", text.isTextSelectable()).put("targetInputType", text.getInputType())
                    .put("targetMovementMethod", text.getMovementMethod() == null ? "none"
                            : text.getMovementMethod().getClass().getName());
        }
        JSONArray hitMap = new JSONArray();
        appendHitMap(decor, "root", hitMap);
        return state.put("viewHitMap", hitMap);
    }

    private void appendHitMap(View view, String path, JSONArray result) throws Exception {
        Rect visible = new Rect(); boolean onScreen = view.getGlobalVisibleRect(visible);
        int[] origin = new int[2]; view.getLocationOnScreen(origin);
        String id = view.getId() == View.NO_ID ? "none" : Integer.toString(view.getId());
        if (view.getId() != View.NO_ID) try { id = view.getResources().getResourceEntryName(view.getId()); }
        catch (android.content.res.Resources.NotFoundException ignored) { }
        Rect nativeBounds = new Rect();
        AccessibilityNodeInfo node = view.createAccessibilityNodeInfo();
        boolean nodeAvailable = node != null;
        if (node != null) { node.getBoundsInScreen(nativeBounds); node.recycle(); }
        result.put(new JSONObject().put("path", path).put("class", view.getClass().getSimpleName()).put("id", id)
                .put("originX", origin[0]).put("originY", origin[1]).put("width", view.getWidth()).put("height", view.getHeight())
                .put("visibleRect", visible.flattenToString()).put("viewAccessibilityNodeAvailable", nodeAvailable)
                .put("viewAccessibilityNodeBounds", nativeBounds.flattenToString())
                .put("onScreen", onScreen).put("visibility", view.getVisibility()).put("clickable", view.isClickable())
                .put("enabled", view.isEnabled()).put("alpha", view.getAlpha()).put("z", view.getZ())
                .put("windowTokenIdentity", identity(view.getWindowToken())));
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++)
            appendHitMap(((ViewGroup) view).getChildAt(index), path + "/" + index, result);
    }

    private JSONObject touch(String boundary, MotionEvent event) throws org.json.JSONException {
        return new JSONObject().put("boundary", boundary).put("action", MotionEvent.actionToString(event.getActionMasked()))
                .put("x", event.getX()).put("y", event.getY()).put("rawX", event.getRawX()).put("rawY", event.getRawY())
                .put("eventTime", event.getEventTime()).put("downTime", event.getDownTime()).put("source", event.getSource())
                .put("eventFlags", event.getFlags()).put("windowFocus", target.hasWindowFocus())
                .put("windowTokenIdentity", identity(activity.getWindow().getDecorView().getWindowToken()));
    }

    private static String identity(Object value) { return value == null ? "none" : Integer.toHexString(System.identityHashCode(value)); }
    private synchronized void record(JSONObject event) { events.put(event); }
    public synchronized String describe() { return "nativeEvidence=" + directory.getAbsolutePath() + "; dispatch=" + events; }
    private void write(String name, JSONObject value) throws Exception {
        Files.write(new File(directory, name).toPath(), value.toString(2).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    @Override public void close() throws Exception {
        if (closed) return;
        closed = true;
        AtomicReference<Exception> problem = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            if (installed) {
                target.setOnTouchListener(null);
                if (activity.getWindow().getCallback() == forwarding) activity.getWindow().setCallback(original);
                else problem.set(new IllegalStateException("Fixture no longer owns window callback"));
                installed = false;
            }
        });
        synchronized (this) { write("input.json", new JSONObject().put("events", events)
                .put("callbackRestored", problem.get() == null)); }
        if (problem.get() != null) throw problem.get();
    }
}
