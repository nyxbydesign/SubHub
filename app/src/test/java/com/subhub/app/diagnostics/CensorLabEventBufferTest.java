package com.subhub.app.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CensorLabEventBufferTest {
    @Test public void bufferNeverBlocksOrExceedsItsBound() {
        CensorLabEventBuffer buffer = new CensorLabEventBuffer(2);
        assertTrue(buffer.offer(event(1L, "one")));
        assertTrue(buffer.offer(event(2L, "two")));
        assertFalse(buffer.offer(event(3L, "three")));

        assertEquals(2, buffer.size());
        assertEquals(2, buffer.snapshot().size());
        assertEquals(1L, buffer.dropped());
    }

    @Test public void labLogAllowlistRejectsContentAndPackageMessages() {
        assertTrue(CensorLabLog.allowed("CensorMotion", "DRAW seq=4"));
        assertTrue(CensorLabLog.allowed("ScreenshotA11y", "SCROLL_EVENT id=2 dy=80"));
        assertTrue(CensorLabLog.allowed("ScreenshotA11y", "SCENE_COMMIT id=4:8:2:10"));
        assertTrue(CensorLabLog.allowed("ScreenshotA11y", "WORLD_CACHE_QUERY entries=8"));
        assertTrue(CensorLabLog.allowed("ScreenshotA11y", "CALIBRATION_SCENE id=4:8"));
        assertTrue(CensorLabLog.allowed("ScreenshotA11y", "CALIBRATION_TOUCH id=2 phase=start"));
        assertFalse(CensorLabLog.allowed("ScreenshotA11y", "CALIBRATION_PRIVATE arbitrary"));
        assertTrue(CensorLabLog.allowed("ScreenshotA11y", "TEXT_SCAN accepted candidates=4"));
        assertFalse(CensorLabLog.allowed("ScreenshotA11y",
                "Recognition activated for foreground package com.example.private"));
        assertFalse(CensorLabLog.allowed("LocalSmutModel", "recognized secret text"));
    }

    @Test public void byteBudgetDropsLargeEventsBeforeMemoryCanGrowUnbounded() {
        CensorLabEventBuffer buffer = new CensorLabEventBuffer(100, 80L);
        assertTrue(buffer.offer(event(1L, "small")));
        assertFalse(buffer.offer(event(2L, "x".repeat(100))));

        assertEquals(1, buffer.size());
        assertEquals(1L, buffer.dropped());
    }

    private static CensorLabEventBuffer.Event event(long sequence, String message) {
        return new CensorLabEventBuffer.Event(sequence, sequence, sequence, sequence,
                "test", "CensorMotion", message);
    }
}
