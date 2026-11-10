package com.subhub.app.service;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ScrollMetadataTraceTest {
    @Test public void numericTracePreservesMissingSentinelsWithoutPageIdentifiers() {
        String record = ScrollMetadataTrace.encode(100, 0xabc, 4, 0, 1500, 0, 30000,
                4, 8, 200, -1, -1, AccessibilitySurfaceIdentityResolver.Identity.empty());
        assertEquals("SCROLL_METADATA schema=1 sourceUptimeMs=100 motionToken=abc classKind=4"
                + " offset=0,1500 max=0,30000 indices=4,8,200 explicit=-1,-1"
                + " sourcePresent=false nodes=0 ownerDepth=-1 ownerKind=0 traversalFailed=false", record);
    }

    @Test public void coarseClassMappingDoesNotEmitArbitraryNames() {
        assertEquals(0, ScrollMetadataTrace.classKind(null));
        assertEquals(0, ScrollMetadataTrace.classKind("private.application.CustomContent"));
        assertEquals(1, ScrollMetadataTrace.classKind("android.webkit.WebView"));
        assertEquals(2, ScrollMetadataTrace.classKind("android.widget.ScrollView"));
        assertEquals(4, ScrollMetadataTrace.classKind("androidx.recyclerview.widget.RecyclerView"));
    }

}
