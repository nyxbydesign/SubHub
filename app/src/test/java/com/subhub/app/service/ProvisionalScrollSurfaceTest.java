package com.subhub.app.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ProvisionalScrollSurfaceTest {
    @Test public void sameWindowProducesStablePrivacySafeIdentity() {
        ProvisionalScrollSurface.Identity first =
                ProvisionalScrollSurface.forWindow("org.chromium.chrome", 7);
        ProvisionalScrollSurface.Identity second =
                ProvisionalScrollSurface.forWindow("org.chromium.chrome", 7);

        assertTrue(first.valid());
        assertEquals(first.key(), second.key());
        assertEquals(first.telemetryToken(), second.telemetryToken());
        assertFalse(first.key().contains("chromium"));
    }

    @Test public void packageAndWindowChangesCreateDifferentDocuments() {
        ProvisionalScrollSurface.Identity baseline =
                ProvisionalScrollSurface.forWindow("org.chromium.chrome", 7);

        assertNotEquals(baseline.key(),
                ProvisionalScrollSurface.forWindow("org.chromium.chrome", 8).key());
        assertNotEquals(baseline.key(),
                ProvisionalScrollSurface.forWindow("com.twitter.android", 7).key());
    }

    @Test public void incompleteWindowCannotBecomeCacheAuthority() {
        assertFalse(ProvisionalScrollSurface.forWindow("", 7).valid());
        assertFalse(ProvisionalScrollSurface.forWindow("org.chromium.chrome", -1).valid());
    }
}
