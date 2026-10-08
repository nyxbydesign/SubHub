package com.subhub.app.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public final class MediaProjectionLeaseRegistryTest {
    @After public void releaseLease() {
        MediaProjectionLeaseRegistry.release(MediaProjectionLeaseRegistry.Owner.PROTECTION);
        MediaProjectionLeaseRegistry.release(MediaProjectionLeaseRegistry.Owner.CENSOR_LAB);
    }

    @Test public void ownersAreMutuallyExclusiveAndReleaseIsOwnerChecked() {
        assertTrue(MediaProjectionLeaseRegistry.acquire(
                MediaProjectionLeaseRegistry.Owner.CENSOR_LAB));
        assertFalse(MediaProjectionLeaseRegistry.acquire(
                MediaProjectionLeaseRegistry.Owner.PROTECTION));
        MediaProjectionLeaseRegistry.release(MediaProjectionLeaseRegistry.Owner.PROTECTION);
        assertEquals(MediaProjectionLeaseRegistry.Owner.CENSOR_LAB,
                MediaProjectionLeaseRegistry.owner());
        MediaProjectionLeaseRegistry.release(MediaProjectionLeaseRegistry.Owner.CENSOR_LAB);
        assertNull(MediaProjectionLeaseRegistry.owner());
    }
}
