package com.subhub.app.service;

import android.graphics.Bitmap;
import android.graphics.Color;
import androidx.test.filters.SdkSuppress;
import org.junit.Test;
import static org.junit.Assert.*;

public final class GpuBitmapPreparerAndroidTest {
    @SdkSuppress(minSdkVersion = 29)
    @Test public void alternatingShapesAndAlphaNeverReturnPreviousPixelsOrOwnTheSource() {
        try (GpuBitmapPreparer preparer = new GpuBitmapPreparer()) {
        // Several fresh sources per identical output size exercise retained renderer resources,
        // not just resize/recreation. Source colors change every request and are recycled below.
        for (int i = 0; i < 24; i++) {
            int width = (i / 6) % 2 == 0 ? 300 : 800;
            int height = (i / 6) % 2 == 0 ? 800 : 300;
            int color = i % 2 == 0 ? Color.RED : 0x800000ff;
            Bitmap software = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            software.eraseColor(color);
            Bitmap hardware = software.copy(Bitmap.Config.HARDWARE, false);
            assertNotNull(hardware);
            try {
                InferenceBitmapPreparer.Prepared value = preparer.prepare(hardware, 320, true);
                assertNotNull(value);
                try {
                    int[] size = InferenceBitmapPreparer.targetDimensions(width, height, 320);
                    assertEquals(size[0], value.bitmap.getWidth());
                    assertEquals(size[1], value.bitmap.getHeight());
                    assertEquals(color, value.bitmap.getPixel(size[0] / 2, size[1] / 2));
                    assertTrue(value.retainedSourceFrame);
                    assertFalse(hardware.isRecycled());
                    assertEquals(Bitmap.Config.ARGB_8888, value.bitmap.getConfig());
                } finally { value.bitmap.recycle(); }
            } finally { hardware.recycle(); software.recycle(); }
        }
        }
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test public void unsupportedSourcesReturnFallbackWithoutTakingOwnership() {
        try (GpuBitmapPreparer preparer = new GpuBitmapPreparer()) {
        assertNull(preparer.prepare(null, 320, false));
        Bitmap source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        assertNull(preparer.prepare(source, 320, false));
        assertFalse(source.isRecycled());
        source.recycle();
        assertNull(preparer.prepare(source, 320, false));
        }
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test public void closeIsTerminalAndCrossThreadAccessFailsBeforeTouchingResources() throws Exception {
        GpuBitmapPreparer preparer = new GpuBitmapPreparer();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread other = new Thread(() -> {
            try { preparer.prepare(null, 320, false); }
            catch (Throwable expected) { failure.set(expected); }
        });
        other.start(); other.join(1000);
        assertFalse(other.isAlive());
        assertTrue(failure.get() instanceof IllegalStateException);
        preparer.close(); preparer.close();
        assertNull(preparer.prepare(null, 320, false));
    }
}
