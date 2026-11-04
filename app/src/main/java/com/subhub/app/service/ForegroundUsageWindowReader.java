package com.subhub.app.service;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.List;

/** Shared conversion boundary for live AccessibilityService windows and native window fixtures. */
final class ForegroundUsageWindowReader {
    private ForegroundUsageWindowReader() {}

    static String read(List<AccessibilityWindowInfo> windows, String inputMethodPackage,
            boolean interactive, boolean locked) {
        if (!interactive || locked) return "";
        List<ForegroundUsageWindowResolver.Window> candidates = new ArrayList<>();
        if (windows != null) for (AccessibilityWindowInfo window : windows) {
            if (window == null || !window.isFocused()) continue;
            AccessibilityNodeInfo root = window.getRoot();
            try {
                Rect bounds = new Rect();
                window.getBoundsInScreen(bounds);
                String name = root != null && root.getPackageName() != null
                        ? root.getPackageName().toString() : "";
                candidates.add(new ForegroundUsageWindowResolver.Window(name,
                        window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION,
                        window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD,
                        window.isFocused(), root != null && root.isVisibleToUser()
                                && !bounds.isEmpty(), window.getLayer()));
            } finally {
                if (root != null) root.recycle();
            }
        }
        // Missing focus is unknown usage; event packages and active-root fallback are excluded.
        return ForegroundUsageWindowResolver.select(candidates, inputMethodPackage, interactive, locked);
    }
}
