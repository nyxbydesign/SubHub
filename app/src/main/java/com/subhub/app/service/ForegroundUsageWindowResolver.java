package com.subhub.app.service;

import com.subhub.app.appmode.AppModePolicy;
import java.util.List;

/** Usage needs current input focus, not the Censor's retained visible-window preference. */
final class ForegroundUsageWindowResolver {
    private ForegroundUsageWindowResolver() {}

    static String select(List<Window> windows, String inputMethodPackage,
            boolean interactive, boolean locked) {
        if (!interactive || locked || windows == null) return "";
        Window focused = null;
        for (Window window : windows) {
            // The keyboard can be active while its owning Activity still has input focus.
            if (window == null || window.inputMethod || !window.focused) continue;
            if (focused == null || window.layer > focused.layer) focused = window;
        }
        if (focused == null || !focused.application || !focused.visible
                || !AppModePolicy.shouldAcceptLiveForegroundPackage(
                        focused.packageName, inputMethodPackage)) return "";
        return focused.packageName;
    }

    static final class Window {
        final String packageName;
        final boolean application, inputMethod, focused, visible;
        final int layer;

        Window(String packageName, boolean application, boolean inputMethod,
                boolean focused, boolean visible, int layer) {
            this.packageName = packageName == null ? "" : packageName;
            this.application = application;
            this.inputMethod = inputMethod;
            this.focused = focused;
            this.visible = visible;
            this.layer = layer;
        }
    }
}
