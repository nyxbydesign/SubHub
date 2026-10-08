package com.subhub.app.capture.export;

import org.junit.Test;
import static org.junit.Assert.*;

public class ExportOptionsTest {
    @Test public void defaultsKeepAudioOriginalsAndEveryFrame() {
        ExportOptions options = ExportOptions.defaults();
        assertEquals(1, options.detectEvery); assertFalse(options.mute); assertFalse(options.deleteOriginals);
        assertEquals(ExportOptions.Quality.BALANCED, options.quality);
    }
    @Test public void dimensionsNeverUpscaleAndRemainEncoderCompatible() {
        assertArrayEquals(new int[] {608, 1080}, ExportOptions.defaults().dimensions(1080, 1920));
        assertArrayEquals(new int[] {320, 240}, ExportOptions.defaults().dimensions(321, 241));
        assertArrayEquals(new int[] {1080, 608}, ExportOptions.defaults().dimensions(1920, 1080));
    }
    @Test public void bitrateRemainsBoundedForLargeInputs() {
        assertEquals(40_000_000, ExportOptions.Quality.BEST.bitrate(16384, 16384, 120));
        assertEquals(1_000_000, ExportOptions.Quality.DRAFT.bitrate(16, 16, 1));
    }
    @Test(expected = IllegalArgumentException.class) public void arbitraryDetectionGapsRejected() {
        new ExportOptions(ExportOptions.Quality.BEST, 20, false, false);
    }
}
