package com.subhub.app.service;

/** Distinguishes API timing from a verified pixel-capture timestamp. Requests are not pixel bounds. */
final class CaptureTimeReference {
    enum Kind { WINDOW_CLIENT_RECEIPT, DISPLAY_SERVER_COMPLETION, VERIFIED_PIXEL_CAPTURE }

    final Kind kind;
    final long requestUptimeMillis;
    final long reportedUptimeMillis;
    final long callbackUptimeMillis;
    final boolean valid;

    private CaptureTimeReference(Kind kind, long request, long reported, long callback) {
        this.kind = kind;
        requestUptimeMillis = request;
        reportedUptimeMillis = reported;
        callbackUptimeMillis = callback;
        valid = request >= 0L && reported >= request && callback >= reported;
    }

    static CaptureTimeReference accessibility(boolean window, long request, long reported, long callback) {
        // Android15 stamps window results on client receipt; display results on server completion.
        // Neither is a timestamp supplied by the captured buffer's producer/compositor.
        return new CaptureTimeReference(window ? Kind.WINDOW_CLIENT_RECEIPT
                : Kind.DISPLAY_SERVER_COMPLETION, request, reported, callback);
    }

    /** Requires independent pixel-time evidence; never wrap an Accessibility API stamp with this. */
    static CaptureTimeReference verifiedPixelTime(long request, long captured, long callback) {
        return new CaptureTimeReference(Kind.VERIFIED_PIXEL_CAPTURE, request, captured, callback);
    }

    boolean pixelTimeKnown() { return valid && kind == Kind.VERIFIED_PIXEL_CAPTURE; }
}
