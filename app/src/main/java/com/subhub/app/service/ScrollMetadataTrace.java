package com.subhub.app.service;

/** Primitive-only evidence; no node acquisition, raw class/ID, or page content. */
final class ScrollMetadataTrace {
    private ScrollMetadataTrace() { }

    static int classKind(CharSequence name) {
        if (name == null) return 0;
        String value = name.toString();
        if (value.equals("android.webkit.WebView")) return 1;
        if (value.equals("android.widget.ScrollView") || value.equals("androidx.core.widget.NestedScrollView")) return 2;
        if (value.equals("android.widget.HorizontalScrollView")) return 3;
        if (value.equals("androidx.recyclerview.widget.RecyclerView") || value.equals("android.support.v7.widget.RecyclerView")) return 4;
        if (value.equals("android.widget.ListView") || value.equals("android.widget.AbsListView")) return 5;
        if (value.equals("android.widget.GridView")) return 6;
        return 0;
    }

    static String encode(long sourceTime, long producer, int kind, int offsetX, int offsetY,
            int maxX, int maxY, int from, int to, int count, int deltaX, int deltaY,
            AccessibilitySurfaceIdentityResolver.Identity identity) {
        return "SCROLL_METADATA schema=1 sourceUptimeMs=" + sourceTime
                + " motionToken=" + Long.toUnsignedString(producer, 16) + " classKind=" + kind
                + " offset=" + offsetX + ',' + offsetY + " max=" + maxX + ',' + maxY
                + " indices=" + from + ',' + to + ',' + count + " explicit=" + deltaX + ',' + deltaY
                + " sourcePresent=" + identity.sourcePresent + " nodes=" + identity.nodeCount
                + " ownerDepth=" + identity.ownerDepth + " ownerKind=" + identity.ownerKind
                + " traversalFailed=" + identity.traversalFailed;
    }
}
