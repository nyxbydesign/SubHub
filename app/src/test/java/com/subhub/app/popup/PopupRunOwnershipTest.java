package com.subhub.app.popup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PopupRunOwnershipTest {
    @Test public void previewExpiresAtItsOriginalDeadlineIncludingPreparationTime() {
        PopupRunOwnership ownership = new PopupRunOwnership();
        long preview = ownership.beginPreview(1_000, 10_000);
        assertTrue(preview > 0);
        assertFalse(ownership.expirePreview(preview, 10_999));
        assertEquals(1, ownership.remainingPreviewMillis(10_999));
        assertTrue(ownership.expirePreview(preview, 11_000));
        assertFalse(ownership.hasOwner());
        assertFalse(ownership.expirePreview(preview, 12_000));
    }

    @Test public void repeatedPreviewDoesNotExtendTheDeadline() {
        PopupRunOwnership ownership = new PopupRunOwnership();
        long preview = ownership.beginPreview(1_000, 10_000);
        assertEquals(0, ownership.beginPreview(9_000, 10_000));
        assertEquals(2_000, ownership.remainingPreviewMillis(9_000));
        assertTrue(ownership.expirePreview(preview, 11_000));
    }

    @Test public void previewCannotTakeOverAnExistingServiceEffect() {
        PopupRunOwnership ownership = new PopupRunOwnership();
        long service = ownership.adoptService();
        assertEquals(0, ownership.beginPreview(1_000, 10_000));
        assertTrue(ownership.isService(service));
        assertFalse(ownership.isPreview());
    }

    @Test public void serviceAdoptionInvalidatesThePreviewTimerWithoutStoppingTheRun() {
        PopupRunOwnership ownership = new PopupRunOwnership();
        long preview = ownership.beginPreview(1_000, 10_000);
        long service = ownership.adoptService();
        assertFalse(ownership.expirePreview(preview, 20_000));
        assertTrue(ownership.isService(service));
        assertTrue(ownership.hasOwner());
        assertEquals(0, ownership.remainingPreviewMillis(20_000));
        assertEquals(service, ownership.adoptService());
    }

    @Test public void oldPreviewAndQueuedStopCannotAffectANewerPreview() {
        PopupRunOwnership ownership = new PopupRunOwnership();
        long first = ownership.beginPreview(1_000, 10_000);
        long stop = ownership.stop();
        long second = ownership.beginPreview(2_000, 10_000);
        assertFalse(ownership.isStopped(stop));
        assertFalse(ownership.expirePreview(first, 12_000));
        assertTrue(ownership.isPreview());
        assertEquals(1_000, ownership.remainingPreviewMillis(11_000));
        assertTrue(ownership.expirePreview(second, 12_000));
    }

    @Test public void anOldQueuedServiceStartIsInvalidAfterStopOrNewPreview() {
        PopupRunOwnership ownership = new PopupRunOwnership();
        long service = ownership.adoptService();
        long stop = ownership.stop();
        assertFalse(ownership.isService(service));
        assertTrue(ownership.isStopped(stop));
        ownership.beginPreview(1_000, 10_000);
        assertFalse(ownership.isService(service));
        assertFalse(ownership.isStopped(stop));
    }
}
