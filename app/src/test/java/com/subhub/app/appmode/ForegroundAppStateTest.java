package com.subhub.app.appmode;

import static org.junit.Assert.*;

import org.junit.Test;

public class ForegroundAppStateTest {
    @Test
    public void returningToTheSameAppCannotPublishAnOldFrame() {
        ForegroundAppState.clear();
        ForegroundAppState.update("chosen.app");
        ForegroundAppState.Snapshot old = ForegroundAppState.snapshot();
        ForegroundAppState.update("chosen.app");
        assertTrue(ForegroundAppState.isCurrent(old));
        ForegroundAppState.update("excluded.app");
        assertFalse(ForegroundAppState.isCurrent(old));
        ForegroundAppState.update("chosen.app");
        assertFalse(ForegroundAppState.isCurrent(old));
        ForegroundAppState.clear();
        assertEquals("", ForegroundAppState.snapshot().packageName);
    }
}
