package com.subhub.app.popup;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Arrays;
import java.util.HashSet;
import org.junit.Test;

public final class PopupInputBoundsAndroidTest {
    @Test public void folderDecoderRejectsOversizedProviderBytesAndCleansItsStagingFile() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String[] before = context.getCacheDir().list((directory, name) -> name.startsWith("popup-input-"));
        assertNotNull(before);
        PopupBitmapCache images = new PopupBitmapCache(context);
        try {
            Bitmap small = images.get("content://com.subhub.app.test.input-fixture/pipephoto");
            assertNotNull(small);
            assertEquals(64, small.getWidth());
            assertNull(images.get("content://com.subhub.app.test.input-fixture/pipeoversize"));
            String[] after = context.getCacheDir().list((directory, name) -> name.startsWith("popup-input-"));
            assertNotNull(after);
            assertEquals(new HashSet<>(Arrays.asList(before)), new HashSet<>(Arrays.asList(after)));
        } finally { images.clear(); }
    }
}
