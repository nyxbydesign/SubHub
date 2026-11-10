package com.subhub.app.service;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import androidx.test.core.app.ApplicationProvider;
import java.io.File;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PreparedFrameRecorderAndroidTest {
    @Test public void boundedEncodingPreservesPixelsAndCloses() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File directory = new File(context.getCacheDir(), "row-recorder-test-" + System.nanoTime());
        PreparedFrameRecorder recorder = new PreparedFrameRecorder(directory, 2);
        int[] first = new int[16];
        Arrays.fill(first, 0xff123456);
        int[] second = new int[16];
        Arrays.fill(second, 0xff654321);
        assertTrue(recorder.offer(first, 4, 4, 10));
        assertTrue(recorder.offer(second, 4, 4, 20));
        assertFalse(recorder.offer(second, 4, 4, 30));
        assertTrue(recorder.awaitFinished(5000));
        recorder.close();
        recorder.close();
        assertEquals(2, directory.listFiles().length);
        Bitmap decoded = BitmapFactory.decodeFile(new File(directory, "frame-10.png").getPath());
        assertNotNull(decoded);
        assertEquals(0xff123456, decoded.getPixel(2, 2));
        decoded.recycle();
    }

    @Test public void invalidFramesDoNotConsumeCaptureBudget() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File directory = new File(context.getCacheDir(), "row-recorder-test-" + System.nanoTime());
        PreparedFrameRecorder recorder = new PreparedFrameRecorder(directory, 1);
        assertFalse(recorder.offer(new int[3], 4, 4, 10));
        assertFalse(recorder.offer(new int[16], 4, 4, -1));
        assertTrue(recorder.offer(new int[16], 4, 4, 10));
        assertTrue(recorder.awaitFinished(5000));
        assertEquals(1, directory.listFiles().length);
    }
}
