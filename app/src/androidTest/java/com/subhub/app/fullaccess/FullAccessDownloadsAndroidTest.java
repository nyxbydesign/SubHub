package com.subhub.app.fullaccess;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

@SdkSuppress(minSdkVersion = 29)
public class FullAccessDownloadsAndroidTest {
    private static final byte[] DATA = {1, 2, 3, 4};

    @Test
    public void completedDownloadIsPublishedWithItsCompleteBytes() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ContentResolver resolver = context.getContentResolver();
        String name = "subhub-files-test-" + UUID.randomUUID() + ".bin";
        Uri item = FullAccessDownloads.save(context, name, "application/octet-stream", () -> true,
                output -> output.write(DATA));
        try {
            try (InputStream input = resolver.openInputStream(item)) {
                assertNotNull(input);
                byte[] actual = new byte[DATA.length];
                int count = 0;
                while (count < actual.length) {
                    int read = input.read(actual, count, actual.length - count);
                    if (read < 0) break;
                    count += read;
                }
                assertEquals(DATA.length, count);
                assertArrayEquals(DATA, actual);
                assertEquals(-1, input.read());
            }
            try (Cursor row = resolver.query(item, new String[]{MediaStore.MediaColumns.IS_PENDING},
                    null, null, null)) {
                assertNotNull(row);
                assertEquals(1, row.getCount());
                row.moveToFirst();
                assertEquals(0, row.getInt(0));
            }
        } finally {
            resolver.delete(item, null, null);
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    public void closingViewerBeforeCompletionDiscardsPendingDownload() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ContentResolver resolver = context.getContentResolver();
        String name = "subhub-files-test-" + UUID.randomUUID() + ".bin";
        AtomicBoolean active = new AtomicBoolean(true);
        Uri collection = MediaStore.setIncludePending(MediaStore.Downloads.EXTERNAL_CONTENT_URI);
        String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=?";
        String[] selectionArgs = {name};
        try {
            assertThrows(IOException.class, () -> FullAccessDownloads.save(context, name, "application/octet-stream",
                    active::get, output -> {
                        output.write(DATA);
                        active.set(false);
                    }));
            try (Cursor row = resolver.query(collection, new String[]{MediaStore.MediaColumns._ID},
                    selection, selectionArgs, null)) {
                assertNotNull(row);
                assertEquals(0, row.getCount());
            }
        } finally {
            try (Cursor leaked = resolver.query(collection, new String[]{MediaStore.MediaColumns._ID},
                    selection, selectionArgs, null)) {
                if (leaked != null) {
                    while (leaked.moveToNext()) {
                        resolver.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                leaked.getLong(0)), null, null);
                    }
                }
            }
        }
    }
}
