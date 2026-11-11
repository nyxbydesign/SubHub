package com.subhub.app.security;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.hardware.HardwareBuffer;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import com.subhub.app.R;
import com.subhub.app.penance.TamperTributeReporter;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Touch-blocking concealment over SubHub's destructive Android Settings actions. */
public final class HardcoreSettingsGuard {
    private static final String TAG = "HardcoreSettingsGuard";
    static final String SETTINGS_PACKAGE = "com.android.settings";
    private static final int MAX_NODES = 500;
    private static final int MAX_BADGES = 4;
    private static final int MAX_CLICKABLE_ANCESTOR_DEPTH = 8;

    private com.subhub.app.runtime.DeviceRuntimeEffects.DeviceComponent deviceComponent;
    private final android.os.Handler managedMain = new android.os.Handler(android.os.Looper.getMainLooper());
    private final com.subhub.app.overlay.OwnedWindowRetirement managedWindows =
            new com.subhub.app.overlay.OwnedWindowRetirement();
    private final List<View> retiringBadges = new ArrayList<>();
    private final List<Rect> pendingBadgeBounds = new ArrayList<>();
    private int pendingSampleWindowId;
    private volatile boolean managedSealed, managedClosed, managedFailed, managedWindowsClosed;
    private final java.util.concurrent.atomic.AtomicInteger managedCallbacks = new java.util.concurrent.atomic.AtomicInteger();
    private final AccessibilityService service;
    private final Context overlayContext;
    private final WindowManager windows;
    private final List<View> badges = new ArrayList<>();
    private final List<Rect> guardedBounds = new ArrayList<>();
    private final List<Rect> sampledBounds = new ArrayList<>();
    private final AtomicBoolean backgroundSampleRunning = new AtomicBoolean();

    public HardcoreSettingsGuard(AccessibilityService service) {
        this.service = service;
        overlayContext = createOverlayContext(service);
        windows = overlayContext.getSystemService(WindowManager.class);
    }

    /** Construct under the exact DEVICE component's STARTUP permission. */
    public static HardcoreSettingsGuard managedDevice(AccessibilityService host,
            com.subhub.app.service.RuntimeCaptureHost.DeviceRequest request,
            com.subhub.app.runtime.DeviceRuntimeEffects.DeviceComponent component) {
        requireManagedMain();
        if (!request.owner().equals(component.owner())) throw new SecurityException("Hardcore owner mismatch");
        HardcoreSettingsGuard guard = new HardcoreSettingsGuard(host);
        guard.deviceComponent = component;
        return guard;
    }

    /** Memory-only fence. Screenshot completions still release their own resources/counts. */
    public void seal() { managedSealed = true; }

    /** Call inside real Cleanup, from a fresh main Handler dispatch outside traversal. */
    public void closeFromFreshMainQueue() {
        requireManagedMain();
        if (deviceComponent == null) throw new IllegalStateException("Not a managed Hardcore guard");
        seal();
        if (managedClosed) return;
        managedClosed = true;
        retiringBadges.addAll(badges);
        badges.clear(); guardedBounds.clear(); sampledBounds.clear(); pendingBadgeBounds.clear();
        try {
            for (View badge : new ArrayList<>(retiringBadges)) retireBadge(badge);
            managedWindowsClosed = managedWindows.isEmpty();
        } catch (RuntimeException | Error uncertain) { markManagedFailure(); throw uncertain; }
    }

    /** Actual cached receipts; no main-thread View inspection or generic sentinel. */
    public boolean managedResourcesDrained() {
        return deviceComponent != null && managedClosed && managedWindowsClosed && !managedFailed
                && managedCallbacks.get() == 0 && !backgroundSampleRunning.get();
    }

    private void markManagedFailure() { managedFailed = true; managedSealed = true; }

    private void retireBadge(View badge) {
        managedWindows.removeFromFreshMainQueue(windows, badge);
        retiringBadges.remove(badge);
    }

    private static void requireManagedMain() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
            throw new IllegalStateException("Hardcore windows require main thread");
    }

    private static Context createOverlayContext(AccessibilityService service) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return service;
        }
        DisplayManager displays = service.getSystemService(DisplayManager.class);
        Display primary = displays == null ? null : displays.getDisplay(Display.DEFAULT_DISPLAY);
        if (primary == null) return service;
        // A single display-bound window context gives TYPE_ACCESSIBILITY_OVERLAY its own valid
        // token. Reuse it for the service lifetime: creating one per Settings event is costly.
        return service.createDisplayContext(primary).createWindowContext(
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null);
    }

    public void refresh(String foregroundPackage, AccessibilityNodeInfo root) {
        if (deviceComponent != null) {
            requireManagedMain();
            if (!managedSealed) deviceComponent.normal(() -> {
                if (!managedSealed) refreshNow(foregroundPackage, root);
            });
        } else refreshNow(foregroundPackage, root);
    }
    private void refreshNow(String foregroundPackage, AccessibilityNodeInfo root) {
        boolean hardcore = new HardcoreModeManager(service).isEnabled();
        boolean domMode = ControllerPinManager.isDomModeActive();
        // The accessibility tree can be very large in scrolling feeds. Reject every non-Settings
        // event before walking it; the guard must be effectively free during normal recognition.
        if (!shouldGuard(hardcore, domMode, foregroundPackage)) {
            clear();
            return;
        }
        // A null tree is a transient Settings handoff, so retain the last verified bounds for one
        // refresh. A real non-SubHub page must clear immediately or stale badges remain stranded.
        if (root == null) {
            return;
        }
        if (!isSubHubPage(root)) {
            clear();
            return;
        }
        List<Rect> controls = new ArrayList<>();
        collectControls(root, controls, new int[]{0});
        if (deviceComponent != null) pendingSampleWindowId = root.getWindowId();
        updateBadges(controls);
        requestBackgroundSample(root.getWindowId());
    }

    public void clear() {
        if (deviceComponent != null) {
            requireManagedMain();
            if (managedSealed) return;
            pendingBadgeBounds.clear();
            List<View> removed = new ArrayList<>(badges);
            retiringBadges.addAll(removed);
            badges.clear(); guardedBounds.clear(); sampledBounds.clear();
            if (removed.isEmpty()) return;
            managedCallbacks.incrementAndGet();
            Runnable removal = new Runnable() {
                @Override public void run() {
                    boolean finished = true;
                    try {
                        if (managedSealed) return;
                        if (!deviceComponent.normal(() -> {
                            if (!managedSealed) {
                                for (View badge : removed)
                                    if (retiringBadges.contains(badge)) retireBadge(badge);
                                if (retiringBadges.isEmpty()) {
                                    List<Rect> latest = new ArrayList<>(pendingBadgeBounds);
                                    pendingBadgeBounds.clear();
                                    for (Rect bounds : latest) if (showBadge(bounds)) guardedBounds.add(new Rect(bounds));
                                    requestBackgroundSample(pendingSampleWindowId);
                                }
                            }
                        })) {
                            if (managedMain.postDelayed(this, 16L)) finished = false;
                            else markManagedFailure();
                        }
                    } catch (RuntimeException | Error uncertain) { markManagedFailure(); }
                    finally { if (finished) managedCallbacks.decrementAndGet(); }
                }
            };
            try {
                if (!managedMain.post(removal)) { markManagedFailure(); managedCallbacks.decrementAndGet(); }
            } catch (RuntimeException | Error uncertain) {
                markManagedFailure(); managedCallbacks.decrementAndGet(); throw uncertain;
            }
            return;
        }
        for (View badge : badges) {
            try { windows.removeViewImmediate(badge); } catch (RuntimeException ignored) {}
        }
        badges.clear();
        guardedBounds.clear();
        sampledBounds.clear();
    }

    private static boolean sameBounds(List<Rect> left, List<Rect> right) {
        int size = Math.min(MAX_BADGES, left.size());
        if (size != right.size()) return false;
        for (int index = 0; index < size; index++) {
            if (!left.get(index).equals(right.get(index))) return false;
        }
        return true;
    }

    private void updateBadges(List<Rect> controls) {
        List<Rect> clipped = new ArrayList<>();
        for (int index = 0; index < Math.min(MAX_BADGES, controls.size()); index++) {
            Rect bounds = clipBounds(controls.get(index));
            if (bounds != null) clipped.add(bounds);
        }
        if (sameBounds(clipped, guardedBounds)) return;
        sampledBounds.clear();
        if (clipped.size() == badges.size()) {
            try {
                for (int index = 0; index < clipped.size(); index++) {
                    windows.updateViewLayout(badges.get(index), layoutParams(clipped.get(index)));
                }
                guardedBounds.clear();
                for (Rect bounds : clipped) guardedBounds.add(new Rect(bounds));
                return;
            } catch (RuntimeException ignored) {
                // Rebuild below if Settings replaced the underlying window during navigation.
            }
        }
        clear();
        if (deviceComponent != null && !retiringBadges.isEmpty()) {
            // Keep only the latest desired layout; do not accumulate native windows while queued.
            for (Rect bounds : clipped) pendingBadgeBounds.add(new Rect(bounds));
            return;
        }
        for (Rect bounds : clipped) {
            if (showBadge(bounds)) guardedBounds.add(new Rect(bounds));
        }
    }

    static boolean shouldGuard(boolean hardcore, boolean domMode, String foregroundPackage) {
        return hardcore && !domMode && isSystemSettingsPackage(foregroundPackage);
    }

    public static boolean isSettingsPackage(String packageName) {
        return isSystemSettingsPackage(packageName);
    }

    private static boolean isSystemSettingsPackage(String packageName) {
        String value = packageName == null ? "" : packageName.toLowerCase(Locale.ROOT);
        if (SETTINGS_PACKAGE.equals(value)) return true;
        boolean trustedAndroidNamespace = value.startsWith("com.android.")
                || value.startsWith("com.google.android.");
        return trustedAndroidNamespace && (value.contains("settings")
                || value.endsWith("permissioncontroller"));
    }

    static int overlayFlags() {
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
    }

    static boolean isTargetControl(String viewId, CharSequence text, CharSequence description) {
        String id = normalize(viewId);
        if (id.contains("uninstall") || id.contains("clear_data")
                || id.contains("clear_storage") || id.contains("delete_data")) return true;
        String label = normalize(join(text, description));
        String[] targets = {
                "uninstall", "deinstallieren", "desinstaller", "desinstalar",
                "disinstalla", "アンインストール", "제거", "удалить", "卸载", "解除安裝",
                "clear data", "clear storage", "delete data", "daten loschen",
                "speicherinhalt loschen", "effacer les donnees", "borrar datos",
                "limpar armazenamento", "limpar dados", "cancella dati",
                "データを消去", "데이터 삭제", "стереть данные", "清除数据", "清除資料"
        };
        for (String target : targets) if (label.contains(target)) return true;
        return false;
    }

    private boolean isSubHubPage(AccessibilityNodeInfo root) {
        return containsAppIdentity(root, new int[]{0});
    }

    private boolean containsAppIdentity(AccessibilityNodeInfo node, int[] count) {
        if (node == null || count[0]++ >= MAX_NODES) return false;
        String value = normalize(join(node.getText(), node.getContentDescription()));
        String viewId = normalize(node.getViewIdResourceName());
        if (value.contains("subhub") || value.contains(service.getPackageName())
                || viewId.contains(service.getPackageName())) return true;
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) continue;
            try {
                if (containsAppIdentity(child, count)) return true;
            } finally {
                child.recycle();
            }
        }
        return false;
    }

    private void collectControls(AccessibilityNodeInfo node, List<Rect> controls, int[] count) {
        if (node == null || count[0]++ >= MAX_NODES || controls.size() >= MAX_BADGES) return;
        if (node.isVisibleToUser() && isTargetControl(node.getViewIdResourceName(),
                node.getText(), node.getContentDescription())) {
            Rect bounds = clickableBounds(node);
            if (bounds.width() >= dp(48) && bounds.height() >= dp(32)) addUnique(controls, bounds);
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) continue;
            try { collectControls(child, controls, count); }
            finally { child.recycle(); }
        }
    }

    private Rect clickableBounds(AccessibilityNodeInfo node) {
        Rect best = new Rect();
        node.getBoundsInScreen(best);
        if (node.isClickable() && isReasonableControlBounds(best)) return best;
        AccessibilityNodeInfo cursor = null;
        try {
            cursor = node.getParent();
            for (int depth = 0; cursor != null && depth < MAX_CLICKABLE_ANCESTOR_DEPTH; depth++) {
                Rect candidate = new Rect();
                cursor.getBoundsInScreen(candidate);
                if (cursor.isClickable() && isReasonableControlBounds(candidate)) {
                    best.set(candidate);
                    break;
                }
                AccessibilityNodeInfo parent = cursor.getParent();
                cursor.recycle();
                cursor = parent;
            }
        } finally {
            if (cursor != null) cursor.recycle();
        }
        expandToMinimumTouchTarget(best);
        return best;
    }

    private boolean isReasonableControlBounds(Rect bounds) {
        android.util.DisplayMetrics display = service.getResources().getDisplayMetrics();
        return bounds.width() > 0 && bounds.height() > 0
                && bounds.width() <= display.widthPixels
                && bounds.height() <= dp(240);
    }

    private void expandToMinimumTouchTarget(Rect bounds) {
        int minimum = dp(48);
        int horizontal = Math.max(0, minimum - bounds.width());
        int vertical = Math.max(0, minimum - bounds.height());
        bounds.inset(-horizontal / 2, -vertical / 2);
        bounds.right += horizontal % 2;
        bounds.bottom += vertical % 2;
    }

    private void addUnique(List<Rect> controls, Rect candidate) {
        for (Rect existing : controls) {
            Rect overlap = new Rect();
            if (overlap.setIntersect(existing, candidate)
                    && overlap.width() * overlap.height() >= candidate.width() * candidate.height() / 2) {
                return;
            }
        }
        controls.add(new Rect(candidate));
    }

    private boolean showBadge(Rect rawBounds) {
        Rect bounds = clipBounds(rawBounds);
        if (bounds == null) return false;

        View badge = new View(overlayContext);
        // Neutral fallback is visible only until the Settings-window sample arrives.
        badge.setBackground(new ColorDrawable(0xFF211E24));
        badge.setOnTouchListener((view, event) -> {
            if (deviceComponent != null && managedSealed) return true;
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                Runnable blocked = () -> {
                Log.i(TAG, "Blocked protected App Info action at " + bounds);
                TamperTributeReporter.record(service);
                Toast.makeText(service, R.string.hardcore_settings_blocked,
                        Toast.LENGTH_SHORT).show();
                };
                if (deviceComponent == null) blocked.run(); else deviceComponent.normal(blocked);
            }
            return true;
        });

        try {
            if (deviceComponent != null) {
                managedWindows.adding(badge);
                badges.add(badge); // Keep even a failed/partial add until verified cleanup.
            }
            windows.addView(badge, layoutParams(bounds));
            if (deviceComponent == null) badges.add(badge);
            return true;
        } catch (RuntimeException error) {
            if (deviceComponent != null) markManagedFailure();
            Log.w(TAG, "Could not attach Hardcore Settings guard", error);
            return false;
        }
    }

    private void requestBackgroundSample(int windowId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                || badges.isEmpty() || sameBounds(guardedBounds, sampledBounds)
                || !backgroundSampleRunning.compareAndSet(false, true)) return;
        if (deviceComponent != null && managedSealed) { backgroundSampleRunning.set(false); return; }
        try {
        service.takeScreenshotOfWindow(windowId, service.getMainExecutor(),
                new AccessibilityService.TakeScreenshotCallback() {
                    @Override public void onSuccess(AccessibilityService.ScreenshotResult result) {
                        Bitmap wrapped = null;
                        Bitmap readable = null;
                        HardwareBuffer buffer = result.getHardwareBuffer();
                        try {
                            wrapped = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                            if (wrapped != null) readable = wrapped.copy(Bitmap.Config.ARGB_8888, false);
                            if (readable != null && !managedSealed) {
                                if (deviceComponent == null) applySampledBackgrounds(readable);
                                else {
                                    Bitmap sample = readable;
                                    deviceComponent.normal(() -> applySampledBackgrounds(sample));
                                }
                            }
                        } finally {
                            try {
                                if (readable != null) readable.recycle();
                                if (wrapped != null) wrapped.recycle();
                                buffer.close();
                            } catch (RuntimeException | Error uncertain) {
                                if (deviceComponent != null) markManagedFailure();
                                throw uncertain;
                            } finally { backgroundSampleRunning.set(false); }
                        }
                    }

                    @Override public void onFailure(int errorCode) {
                        backgroundSampleRunning.set(false);
                        Log.d(TAG, "Settings background sample unavailable: " + errorCode);
                    }
                });
        } catch (RuntimeException | Error uncertain) {
            if (deviceComponent != null) markManagedFailure();
            backgroundSampleRunning.set(false);
            throw uncertain;
        }
    }

    private void applySampledBackgrounds(Bitmap screenshot) {
        int size = Math.min(badges.size(), guardedBounds.size());
        for (int index = 0; index < size; index++) {
            int color = sampleSurroundingColor(screenshot, guardedBounds.get(index));
            badges.get(index).setBackground(new ColorDrawable(color));
        }
        sampledBounds.clear();
        for (Rect bounds : guardedBounds) sampledBounds.add(new Rect(bounds));
    }

    private int sampleSurroundingColor(Bitmap screenshot, Rect screenBounds) {
        android.util.DisplayMetrics display = service.getResources().getDisplayMetrics();
        float scaleX = screenshot.getWidth() / (float) Math.max(1, display.widthPixels);
        float scaleY = screenshot.getHeight() / (float) Math.max(1, display.heightPixels);
        int left = Math.max(0, Math.round(screenBounds.left * scaleX));
        int right = Math.min(screenshot.getWidth() - 1, Math.round(screenBounds.right * scaleX));
        int top = Math.max(0, Math.round(screenBounds.top * scaleY));
        int bottom = Math.min(screenshot.getHeight() - 1, Math.round(screenBounds.bottom * scaleY));
        int marginX = Math.max(2, Math.round(dp(6) * scaleX));
        int marginY = Math.max(2, Math.round(dp(6) * scaleY));
        long red = 0L;
        long green = 0L;
        long blue = 0L;
        int samples = 0;
        int step = Math.max(1, Math.round(dp(8) * Math.max(scaleX, scaleY)));
        for (int x = left; x <= right; x += step) {
            int[] ys = {Math.max(0, top - marginY), Math.min(screenshot.getHeight() - 1, bottom + marginY)};
            for (int y : ys) {
                int color = screenshot.getPixel(x, y);
                red += Color.red(color);
                green += Color.green(color);
                blue += Color.blue(color);
                samples++;
            }
        }
        for (int y = top; y <= bottom; y += step) {
            int[] xs = {Math.max(0, left - marginX), Math.min(screenshot.getWidth() - 1, right + marginX)};
            for (int x : xs) {
                int color = screenshot.getPixel(x, y);
                red += Color.red(color);
                green += Color.green(color);
                blue += Color.blue(color);
                samples++;
            }
        }
        return samples == 0 ? 0xFF211E24 : Color.rgb(
                (int) (red / samples), (int) (green / samples), (int) (blue / samples));
    }

    private Rect clipBounds(Rect rawBounds) {
        android.util.DisplayMetrics display = service.getResources().getDisplayMetrics();
        Rect bounds = new Rect(
                Math.max(0, rawBounds.left), Math.max(0, rawBounds.top),
                Math.min(display.widthPixels, rawBounds.right),
                Math.min(display.heightPixels, rawBounds.bottom));
        return bounds.width() > 0 && bounds.height() > 0 ? bounds : null;
    }

    private WindowManager.LayoutParams layoutParams(Rect bounds) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                bounds.width(), bounds.height(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                overlayFlags(),
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = bounds.left;
        params.y = bounds.top;
        params.setTitle(service.getString(R.string.hardcore_guard_window_title));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * service.getResources().getDisplayMetrics().density);
    }

    private static String join(CharSequence first, CharSequence second) {
        return String.valueOf(first == null ? "" : first) + " "
                + String.valueOf(second == null ? "" : second);
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .trim();
    }
}
