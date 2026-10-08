package com.subhub.app.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class DiagnosticsRepositoryTest {
    @Test
    public void liveMetricsRemainBoundedAndProcessLocal() {
        DiagnosticsRepository.begin("Test capture", 320);
        DiagnosticsRepository.ready("Test capture", "NNAPI", "models/320n_fp16.onnx", 320);
        DiagnosticsRepository.Snapshot snapshot = DiagnosticsRepository.recordFrame(
                "Test capture", 42, 3, 1080, 2400);

        assertTrue(snapshot.isRunning());
        assertTrue(snapshot.isReady());
        assertEquals("320n_fp16.onnx", snapshot.getModel());
        assertEquals(42, snapshot.getAverageInferenceMs());
        assertEquals(3, snapshot.getTotalDetections());
        assertTrue(DiagnosticsRepository.overlayText(snapshot).contains("NNAPI"));

        DiagnosticsRepository.recordPublishDelay("Test capture", 87L);
        snapshot = DiagnosticsRepository.snapshot();
        assertEquals(87L, snapshot.getLastPublishDelayMs());
        assertTrue(DiagnosticsRepository.overlayText(snapshot).contains("UI 87 ms"));

        DiagnosticsRepository.recordAccessibilityText("Test capture", 4, 2);
        DiagnosticsRepository.recordOcr("Test capture", 240L, false, 1);
        DiagnosticsRepository.recordOcr("Test capture", 360L, true, 0);
        snapshot = DiagnosticsRepository.snapshot();
        assertEquals(4, snapshot.getLastAccessibilityTextCandidates());
        assertEquals(2, snapshot.getLastAccessibilityTextStable());
        assertEquals(0, snapshot.getLastOcrTextStable());
        assertEquals(2L, snapshot.getOcrRuns());
        assertEquals(300L, snapshot.getAverageOcrMs());
        assertEquals(360L, snapshot.getPeakOcrMs());
        assertEquals(1L, snapshot.getStaleOcrResults());

        DiagnosticsRepository.fail("Test capture",
                new IllegalStateException("private path must not be retained"));
        snapshot = DiagnosticsRepository.snapshot();
        assertEquals("IllegalStateException", snapshot.getLastFailure());
        assertFalse(snapshot.getLastFailure().contains("private path"));
        assertTrue(snapshot.isReady());

        DiagnosticsRepository.stop("Test capture");
        assertFalse(DiagnosticsRepository.snapshot().isRunning());
    }
}
