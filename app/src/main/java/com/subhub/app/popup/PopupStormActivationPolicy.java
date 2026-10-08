package com.subhub.app.popup;

/** Keeps Popup Storm attached to an active protection runtime, not mere service presence. */
public final class PopupStormActivationPolicy {
    private PopupStormActivationPolicy() {}

    public static boolean shouldStart(
            boolean screenCaptureRunning,
            boolean accessibilityRecognitionActive) {
        return screenCaptureRunning || accessibilityRecognitionActive;
    }
}
