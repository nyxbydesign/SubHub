package com.subhub.app.diagnostics;

import org.junit.Test;
import static org.junit.Assert.*;

public final class CensorLabLogTest {
    @Test public void personTelemetryIsMirroredForBothCaptureModes() {
        assertTrue(CensorLabLog.allowed("PersonInference", "PERSON_MODEL v=1 run=1 totalMs=87"));
        for (String tag : new String[]{"ScreenshotA11y", "ScreenCaptureService"}) {
            assertTrue(CensorLabLog.allowed(tag, "PERSON_PROVISIONAL v=1 source=100 captureAgeMs=121"));
            assertTrue(CensorLabLog.allowed(tag, "PERSON_PUBLISH v=1 run=1 source=100 applied=true"));
        }
    }

    @Test public void unrelatedTagsAndMessagesRemainExcluded() {
        assertFalse(CensorLabLog.allowed("Other", "PERSON_MODEL v=1 run=1"));
        assertFalse(CensorLabLog.allowed("PersonInference", "private diagnostic text"));
        assertFalse(CensorLabLog.allowed("ScreenCaptureService", "unrelated text"));
        assertFalse(CensorLabLog.allowed("PersonInference", null));
        assertTrue(CensorLabLog.allowed("ScreenshotA11y", "OVERLAY_PUBLISH pass=fast"));
    }

    @Test public void futureSchemaMustReachParserInsteadOfSilentlyDisappearing() {
        assertTrue(CensorLabLog.allowed("PersonInference", "PERSON_MODEL v=2 future=1"));
        assertTrue(CensorLabLog.allowed("ScreenshotA11y", "PERSON_PUBLISH v=2 future=1"));
    }
}
