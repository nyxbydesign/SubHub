package com.subhub.app.service;

import static org.junit.Assert.*;
import org.junit.Test;

/** Numeric timelines from Lab109; no page content or pixels. Times are source-clock milliseconds. */
public final class QualityPresentationSlotTest {
    private static LateQualityPresentationGate.Stamp stamp(long sequence, int window, long motion) {
        return new LateQualityPresentationGate.Stamp(sequence, 1, 2, "", 0, motion,
                0, 0, 1344, 2992, 1344, 2992, window, true);
    }

    private static boolean acquire(QualityPresentationSlot<Object> slot, Object source,
            long sequence, long consumerSequence, long capturedAt, long now) {
        return LateQualityPresentationGate.decide(stamp(sequence, 3, 896),
                stamp(consumerSequence, 3, 896), true, capturedAt, now, 2500)
                == LateQualityPresentationGate.Decision.MATCH && slot.acquire(source, true);
    }

    @Test public void allThreeRecordedSecondFastTicksRetainCurrentQuality() {
        // source sequence/capture, first and second fast publication observed uptimes.
        long[][] timeline = {{1169, 348610244, 348611037, 348611354},
                {1177, 348613633, 348614438, 348614822},
                {1180, 348614998, 348615806, 348616157}};
        for (long[] row : timeline) {
            QualityPresentationSlot<Object> slot = new QualityPresentationSlot<>();
            Object quality = new Object();
            slot.getAndSet(quality);
            assertTrue(acquire(slot, quality, row[0], row[0] + 1, row[1], row[2]));
            assertTrue(acquire(slot, quality, row[0], row[0] + 2, row[1], row[3]));
            assertSame(quality, slot.get());
        }
    }

    @Test public void worldCacheHandoffIsStillSingleUse() {
        QualityPresentationSlot<Object> slot = new QualityPresentationSlot<>();
        Object quality = new Object();
        slot.getAndSet(quality);
        assertTrue(slot.acquire(quality, false));
        assertNull(slot.get());
        assertFalse(slot.acquire(quality, false));
    }

    @Test public void replacementIncludingEmptyEvidenceInvalidatesQueuedReaders() {
        QualityPresentationSlot<Object> slot = new QualityPresentationSlot<>();
        Object old = new Object(), empty = new Object();
        slot.getAndSet(old);
        assertTrue(slot.acquire(old, true));
        assertSame(old, slot.getAndSet(empty));
        assertFalse(slot.acquire(old, true));
        assertFalse(slot.compareAndSet(old, null)); // delayed old expiry cannot erase new evidence
        assertSame(empty, slot.get());
    }

    @Test public void clearInvalidatesAnAlreadyAcquiredReader() {
        QualityPresentationSlot<Object> slot = new QualityPresentationSlot<>();
        Object old = new Object();
        slot.getAndSet(old);
        assertTrue(slot.acquire(old, true));
        slot.getAndSet(null);
        assertFalse(slot.acquire(old, true));
    }

    @Test public void expiryRemovesOldDisplayEvenWhenNewPendingResultNeverPublishes() {
        QualityPresentationSlot<Object> slot = new QualityPresentationSlot<>();
        Object old = new Object(), newer = new Object();
        slot.getAndSet(old);
        slot.markDisplayed(old);
        slot.getAndSet(newer);
        assertFalse(slot.compareAndSet(old, null));
        assertTrue(slot.clearDisplayed(old));
        assertSame(newer, slot.get());
        assertFalse(slot.clearDisplayed(old));
    }

    @Test public void oldExpiryCannotEraseANewerDisplay() {
        QualityPresentationSlot<Object> slot = new QualityPresentationSlot<>();
        Object old = new Object(), newer = new Object();
        slot.markDisplayed(old);
        slot.markDisplayed(newer);
        assertFalse(slot.clearDisplayed(old));
        assertTrue(slot.clearDisplayed(newer));
    }

    @Test public void expiryStillRemovesDisplayAfterWorkerDiscardsExpiredPendingSource() {
        QualityPresentationSlot<Object> slot = new QualityPresentationSlot<>();
        Object old = new Object();
        slot.getAndSet(old);
        slot.markDisplayed(old);
        assertTrue(slot.compareAndSet(old, null));
        assertTrue(slot.clearDisplayed(old));
    }

    @Test public void repeatedReadsNeverRenewCaptureAge() {
        QualityPresentationSlot<Object> slot = new QualityPresentationSlot<>();
        Object quality = new Object();
        slot.getAndSet(quality);
        for (long now : new long[]{1200, 1500, 2000, 3000, 3500}) {
            assertTrue(acquire(slot, quality, 10, 11, 1000, now));
        }
        assertFalse(acquire(slot, quality, 10, 12, 1000, 3501));
    }

    @Test public void publicationFenceRejectsMotionWindowPhaseClockAndExpiryChanges() {
        LateQualityPresentationGate.Stamp source = stamp(10, 3, 896);
        for (LateQualityPresentationGate.Stamp consumer : new LateQualityPresentationGate.Stamp[]{
                stamp(11, 4, 896), stamp(11, 3, 897)}) {
            assertEquals(LateQualityPresentationGate.Decision.STALE,
                    LateQualityPresentationGate.decide(source, consumer, true, 1000, 1200, 2500));
        }
        assertEquals(LateQualityPresentationGate.Decision.STALE,
                LateQualityPresentationGate.decide(source, stamp(11, 3, 896), false, 1000, 1200, 2500));
        assertEquals(LateQualityPresentationGate.Decision.STALE,
                LateQualityPresentationGate.decide(source, stamp(11, 3, 896), true, 1000, 999, 2500));
        assertEquals(LateQualityPresentationGate.Decision.STALE,
                LateQualityPresentationGate.decide(source, stamp(11, 3, 896), true, 1000, 3501, 2500));
    }
}
