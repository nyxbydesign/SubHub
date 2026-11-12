package com.subhub.app.fullaccess;

import static org.junit.Assert.*;

import android.app.Notification;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import androidx.core.content.FileProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.security.ControllerPinManager;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class FullAccessDataPresentationAndroidTest {
    public static AutoCloseable seedDemoTranscript(Context target, String generation, String[] lines) throws Exception {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("blackmail_owned_guest"));
        assertFalse("A synthetic fixture must not replace a prior transcript", FullAccessSmsExport.demoAvailable(target));
        File directory = new File(target.getFilesDir(), "full-access-downloads");
        assertTrue(directory.isDirectory() || directory.mkdir());
        File transcript = File.createTempFile("demo-synthetic-transcript-", ".txt", directory);
        try {
            try (FileOutputStream stream = new FileOutputStream(transcript)) {
                stream.write(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
            }
            DemoFullAccess source = new DemoFullAccess(target, generation, ControllerPinManager.domWorkspaceRevision());
            assertTrue(source.importSms(FileProvider.getUriForFile(target,
                    target.getPackageName() + ".fullaccess.files", transcript)));
        } finally {
            Files.delete(transcript.toPath());
        }
        return () -> FullAccessSmsExport.clearDemo(target);
    }

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

    @Test public void extractsExpandedAndCurrentMessagesWithinTheConsentWindow() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        long now = System.currentTimeMillis();
        long since = now - 500;
        Notification.Builder builder = new Notification.Builder(context, "notification-fixture");
        Notification big = new Notification.BigTextStyle(builder)
                .setBigContentTitle("Weekend plans").bigText("Tea downstairs\nMeet by the window").build();
        assertEquals(new FullAccessNotificationListener.Preview("Weekend plans", "Tea downstairs\nMeet by the window"),
                FullAccessNotificationListener.preview(big, since, now));
        Notification inbox = new Notification.InboxStyle(new Notification.Builder(context, "notification-fixture"))
                .setBigContentTitle("Reminders").addLine("Take a coat").addLine("Water the plants").build();
        assertEquals("Take a coat\nWater the plants", FullAccessNotificationListener.preview(inbox, since, now).text());

        Notification.MessagingStyle chat = new Notification.MessagingStyle("Me").setConversationTitle("Weekend group");
        chat.addHistoricMessage(new Notification.MessagingStyle.Message("Historical row", now - 100, "Kit"));
        chat.addMessage("Before consent", since - 1, "Kit");
        chat.addMessage("First eligible", since, "Kit");
        chat.addMessage("Most recent", now, "Lee");
        Notification message = new Notification.Builder(context, "notification-fixture").setStyle(chat).build();
        assertEquals("Kit: First eligible\nLee: Most recent",
                FullAccessNotificationListener.preview(message, since, now).text());

        Notification.MessagingStyle crowded = new Notification.MessagingStyle("Me").setConversationTitle("Weekend group");
        crowded.addMessage(String.join("", java.util.Collections.nCopies(480, "A")), since, "Kit");
        crowded.addMessage("Keep the latest", now, "Lee");
        Notification latest = new Notification.Builder(context, "notification-fixture").setStyle(crowded).build();
        assertEquals("Lee: Keep the latest", FullAccessNotificationListener.preview(latest, since, now).text());
        assertTrue(FullAccessNotificationListener.publicPreview("Security", "code has arrived").empty());
        message.visibility = Notification.VISIBILITY_SECRET;
        assertTrue(FullAccessNotificationListener.preview(message, since, now).empty());
        message.visibility = Notification.VISIBILITY_PUBLIC;
        message.flags |= Notification.FLAG_LOCAL_ONLY;
        assertTrue(FullAccessNotificationListener.preview(message, since, now).empty());
    }

    @Test public void notificationScopePreservesLegacyApprovalsAndFencesProspectiveText() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File temporary = Files.createTempDirectory(target.getCacheDir().toPath(), "notification-consent-").toFile();
        String fixturePrefs = temporary.getName();
        SharedPreferences preferences = target.getSharedPreferences(fixturePrefs, Context.MODE_PRIVATE);
        Context isolated = new ContextWrapper(target) {
            @Override public File getNoBackupFilesDir() { return temporary; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return preferences; }
        };
        long now = System.currentTimeMillis();
        long since = now - 500;
        long textSince = now - 200;
        var publicOnly = new FullAccessNotificationListener.Choice(7, "", 2, since, 0);
        var renewed = new FullAccessNotificationListener.Choice(7, "", 2, since, textSince);
        var replacement = new FullAccessNotificationListener.Choice(8, "", 2, since, textSince);
        assertEquals(0, renewed.acceptedTextSince(renewed, textSince - 1));
        assertEquals(textSince, renewed.acceptedTextSince(renewed, textSince));
        assertEquals(0, renewed.acceptedTextSince(publicOnly, now));
        assertFalse(replacement.sameScope(renewed));

        try {
            for (int version : new int[]{1, 2, 3}) {
                assertTrue(preferences.edit().putString("notifications_details_token", "legacy-approved")
                        .putLong(FullAccessNotificationListener.TEXT_SINCE, textSince).commit());
                File path = new File(temporary, "full-access-notifications.db");
                try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(path, null)) {
                    String v2 = version >= 2
                            ? ", public_title TEXT NOT NULL DEFAULT '', public_text TEXT NOT NULL DEFAULT ''" : "";
                    String v3 = version == 3
                            ? ", details_token TEXT NOT NULL DEFAULT '', detail_title TEXT NOT NULL DEFAULT '',"
                            + " detail_text TEXT NOT NULL DEFAULT '', small_icon TEXT NOT NULL DEFAULT ''" : "";
                    db.execSQL("CREATE TABLE posted_notifications (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                            + " epoch INTEGER NOT NULL, event_key TEXT NOT NULL, pkg TEXT NOT NULL, category TEXT NOT NULL,"
                            + " posted_at INTEGER NOT NULL" + v2 + v3 + ", UNIQUE(epoch,event_key,posted_at))");
                    db.execSQL("INSERT INTO posted_notifications (epoch,event_key,pkg,category,posted_at)"
                            + " VALUES(7,'retained','example.alpha','message',?)", new Object[]{now - 100});
                    if (version == 3) {
                        db.execSQL("UPDATE posted_notifications SET details_token='legacy-approved',"
                                + " detail_text='Previously approved', public_text='A new message' WHERE event_key='retained'");
                        db.execSQL("INSERT INTO posted_notifications (epoch,event_key,pkg,category,posted_at,details_token,"
                                + "detail_text,public_text) VALUES(7,'stale','example.stale','message',?,"
                                + "'superseded','Stale private text','Public text')", new Object[]{now - 100});
                    }
                    db.setVersion(version);
                }
                String id;
                try (FullAccessNotificationHistory history = new FullAccessNotificationHistory(isolated, false, textSince)) {
                    id = notificationRow(history, isolated, since, now).getString("id");
                    var publicPreview = FullAccessNotificationListener.publicPreview("Chat", "A new message");
                    var details = FullAccessNotificationListener.publicPreview("Kit", "Meet by the window");
                    history.record(7, "retained", "example.alpha", "message", now - 100, now, publicPreview, details, "raster");
                    history.record(7, "retained", "example.alpha", "message", now - 100, now,
                            FullAccessNotificationListener.EMPTY, FullAccessNotificationListener.EMPTY, "");
                    JSONObject row = notificationRow(history, isolated, since, now);
                    assertEquals(id, row.getString("id"));
                    assertEquals(version == 3 ? "Previously approved" : "Meet by the window", row.getString("notificationText"));
                    assertEquals("raster", row.getString("notificationIcon"));
                    if (version == 3) {
                        JSONObject stale = history.group(isolated, 7, "example.stale", "", since, now, 12)
                                .getJSONArray("items").getJSONObject(0);
                        assertEquals("Public text", stale.getString("notificationText"));
                    }
                }
                try (FullAccessNotificationHistory history = new FullAccessNotificationHistory(isolated)) {
                    assertEquals("A new message", notificationRow(history, isolated, since, now).getString("notificationText"));
                }
                try (FullAccessNotificationHistory history = new FullAccessNotificationHistory(isolated, false, now - 50)) {
                    JSONObject row = notificationRow(history, isolated, since, now);
                    assertEquals(id, row.getString("id"));
                    assertEquals("A new message", row.getString("notificationText"));
                }
                FullAccessNotificationHistory.clear(isolated);
            }
        } finally {
            target.deleteSharedPreferences(fixturePrefs);
            FullAccessNotificationHistory.clear(isolated);
            Files.delete(temporary.toPath());
        }
    }

    private static JSONObject notificationRow(FullAccessNotificationHistory history, Context context, long since, long now)
            throws Exception {
        var items = history.group(context, 7, "example.alpha", "", since, now, 12).getJSONArray("items");
        assertEquals(1, items.length());
        return items.getJSONObject(0);
    }
}
