package com.subhub.app.service;

import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeScrollCurvePolicyTest {
    @Test public void onlyNativeXRecyclerViewIsAdmitted() {
        String recycler = "androidx.recyclerview.widget.RecyclerView";
        assertTrue(NativeScrollCurvePolicy.supports("com.twitter.android", recycler));
        assertFalse(NativeScrollCurvePolicy.supports("org.chromium.chrome.stable", recycler));
        assertFalse(NativeScrollCurvePolicy.supports("com.twitter.android", "android.webkit.WebView"));
        assertFalse(NativeScrollCurvePolicy.supports("com.twitter.android", null));
        assertFalse(NativeScrollCurvePolicy.supports(null, recycler));
    }
}
