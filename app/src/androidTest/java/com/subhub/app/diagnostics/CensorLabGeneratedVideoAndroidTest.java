package com.subhub.app.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Device-only validation for a real capture generated immediately before this test. */
@RunWith(AndroidJUnit4.class)
public final class CensorLabGeneratedVideoAndroidTest {
    @Test public void latestGeneratedRecordingIsPlayableVideoOnlyMp4() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        CensorLabRecorder.CompletedSession session = CensorLabRecorder.latest(context);
        Assume.assumeTrue(session != null && session.video != null && session.video.isFile());

        int videoTracks = 0;
        int audioTracks = 0;
        long longestDurationUs = 0L;
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(session.video.getAbsolutePath());
            for (int index = 0; index < extractor.getTrackCount(); index++) {
                MediaFormat format = extractor.getTrackFormat(index);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTracks++;
                    assertTrue(format.getInteger(MediaFormat.KEY_WIDTH) > 0);
                    assertTrue(format.getInteger(MediaFormat.KEY_HEIGHT) > 0);
                } else if (mime != null && mime.startsWith("audio/")) {
                    audioTracks++;
                }
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    longestDurationUs = Math.max(longestDurationUs,
                            format.getLong(MediaFormat.KEY_DURATION));
                }
            }
        } finally {
            extractor.release();
        }
        assertEquals(1, videoTracks);
        assertEquals(0, audioTracks);
        assertTrue(longestDurationUs > 0L);
    }
}
