package com.subhub.app.service;

/** Restrict the source-derived native strategy; a web page on x.com is not native X. */
final class NativeScrollCurvePolicy {
    static boolean supports(String packageName, CharSequence className) {
        return "com.twitter.android".equals(packageName)
                && "androidx.recyclerview.widget.RecyclerView".contentEquals(className == null ? "" : className);
    }
    private NativeScrollCurvePolicy() {}
}
