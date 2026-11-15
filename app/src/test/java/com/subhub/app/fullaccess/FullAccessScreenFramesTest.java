package com.subhub.app.fullaccess;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FullAccessScreenFramesTest {
    @Test public void samplesNoMoreThanEveryHalfSecond() {
        FullAccessScreenFrames frames = new FullAccessScreenFrames();

        assertTrue(frames.beginSample(0));
        assertFalse(frames.beginSample(499));
        assertTrue(frames.beginSample(500));
        assertFalse(frames.beginSample(500));
        assertTrue(frames.beginSample(1_000));
    }

    @Test public void slowViewerGetsOnlyTheLatestFrameAndConsumesItOnce() {
        FullAccessScreenFrames frames = new FullAccessScreenFrames();
        byte[] newer = {2};
        frames.publish(new byte[]{1}, 500);
        frames.publish(newer, 1_000);

        assertArrayEquals(newer, frames.takeLatest(1_200));
        assertNull(frames.takeLatest(1_201));
    }

    @Test public void staleOrOverBudgetFramesAreDiscarded() {
        FullAccessScreenFrames frames = new FullAccessScreenFrames();
        frames.publish(new byte[]{1}, 0);
        assertNull(frames.takeLatest(1_000));

        frames.publish(new byte[FullAccessScreenFrames.MAX_BYTES + 1], 1_000);
        assertNull(frames.takeLatest(1_001));
    }

    @Test public void hiddenContentAndClosedViewerCannotLeaveQueuedFrames() {
        FullAccessScreenFrames frames = new FullAccessScreenFrames();
        frames.publish(new byte[]{1}, 500);
        frames.clear();
        assertNull(frames.takeLatest(501));

        frames.publish(new byte[]{2}, 1_000);
        frames.close();
        frames.publish(new byte[]{3}, 1_500);
        assertFalse(frames.beginSample(1_500));
        assertNull(frames.takeLatest(1_500));
    }
}
