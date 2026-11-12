package com.subhub.app.fullaccess;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Base64;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class FullAccessDataPresentationAndroidTest {
    @Test public void contactPhotosAreReencodedAsSmallSquareJpegs() {
        Bitmap source = Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            source.eraseColor(Color.BLUE);
            assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, bytes));
            String photo = FullAccessContactsSource.encodePhoto(bytes.toByteArray());
            assertNotNull(photo);
            byte[] jpeg = Base64.getDecoder().decode(photo);
            assertTrue(jpeg.length <= FullAccessContactsSource.PHOTO_BYTES);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
            assertEquals(FullAccessContactsSource.PHOTO_EDGE, bounds.outWidth);
            assertEquals(FullAccessContactsSource.PHOTO_EDGE, bounds.outHeight);
            assertEquals("image/jpeg", bounds.outMimeType);
            assertNull(FullAccessContactsSource.encodePhoto(new byte[]{0, 1, 2}));
            assertNull(FullAccessContactsSource.encodePhoto(new byte[512 * 1024 + 1]));
        } finally {
            source.recycle();
        }
    }

    @Test public void groupsNotificationsByAppAndPaginatesExpandedRowsWithoutLosingRedaction() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File temporary = Files.createTempDirectory(target.getCacheDir().toPath(), "notification-history-").toFile();
        Context isolated = new ContextWrapper(target) {
            @Override public File getNoBackupFilesDir() { return temporary; }
        };
        long now = System.currentTimeMillis();
        long since = now - FullAccessNotificationHistory.RETENTION_MILLIS;
        FullAccessNotificationListener.Preview publicPreview =
                FullAccessNotificationListener.publicPreview("Calendar", "Lunch is ready");
        FullAccessNotificationListener.Preview hidden =
                FullAccessNotificationListener.publicPreview("Account", "Your code is 746194");
        try (FullAccessNotificationHistory history = new FullAccessNotificationHistory(isolated)) {
            history.record(7, "newest-a", "example.alpha", "message", now - 100, now, hidden);
            // Arrival order differs from post time, and ties can span both group and detail pages.
            history.record(7, "older-a", "example.alpha", "event", now - 300, now, publicPreview);
            history.record(7, "newest-b", "example.beta", "event", now - 100, now, publicPreview);
            history.record(6, "other-epoch", "example.elsewhere", "event", now - 50, now, publicPreview);
            history.record(7, "too-old", "example.expired", "event", since - 1, now, publicPreview);

            JSONObject first = history.groups(isolated, 7, "", since, now, 1);
            JSONObject alpha = first.getJSONArray("items").getJSONObject(0);
            assertEquals("example.alpha", alpha.getString("id"));
            assertEquals(2, alpha.getInt("count"));
            assertEquals(now - 100, alpha.getLong("time"));
            assertFalse(alpha.has("notificationText"));
            JSONObject second = history.groups(isolated, 7, first.getString("nextCursor"), since, now, 1);
            assertEquals("example.beta", second.getJSONArray("items").getJSONObject(0).getString("id"));
            assertFalse(second.has("nextCursor"));

            JSONObject thread = history.group(isolated, 7, "example.alpha", "", since, now, 1);
            assertFalse(thread.getJSONArray("items").getJSONObject(0).has("notificationTitle"));
            JSONObject older = history.group(isolated, 7, "example.alpha", thread.getString("nextCursor"),
                    since, now, 1);
            JSONObject olderRow = older.getJSONArray("items").getJSONObject(0);
            assertEquals("Lunch is ready", olderRow.getString("notificationText"));
            assertFalse(older.has("nextCursor"));
            assertEquals(3, history.page(isolated, 7, Long.MAX_VALUE, since, now, 12).getJSONArray("items").length());
        } finally {
            FullAccessNotificationHistory.clear(isolated);
            Files.delete(temporary.toPath());
        }
    }
}
