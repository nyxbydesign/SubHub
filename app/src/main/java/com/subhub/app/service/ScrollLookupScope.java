package com.subhub.app.service;
import java.util.Objects;

/** True input changes fence motion; a provisional owner's first confirmation does not. */
final class ScrollLookupScope {
    final long captureEpoch, inputGeneration, documentEpoch;
    final int activeWindow, eventWindow;
    final String packageName;
    final boolean provisional;
    ScrollLookupScope(long captureEpoch, long inputGeneration, long documentEpoch,
            int activeWindow, int eventWindow, String packageName, boolean provisional) {
        this.captureEpoch = captureEpoch; this.inputGeneration = inputGeneration;
        this.documentEpoch = documentEpoch; this.activeWindow = activeWindow;
        this.eventWindow = eventWindow; this.packageName = packageName; this.provisional = provisional;
    }
    boolean externalMatches(long capture, long input, int window, String packageNow) {
        return captureEpoch == capture && inputGeneration == input && activeWindow >= 0
                && activeWindow == window && eventWindow == window && Objects.equals(packageName, packageNow);
    }
    boolean mayApply(long capture, long input, int window, String packageNow,
            long documentNow, boolean confirmedSameOwner) {
        return externalMatches(capture, input, window, packageNow)
                && (documentEpoch == documentNow
                || documentEpoch < documentNow && provisional && confirmedSameOwner);
    }
}
