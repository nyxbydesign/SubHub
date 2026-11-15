package com.subhub.app.update;

import static org.junit.Assert.*;
import android.Manifest;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.service.notification.StatusBarNotification;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.BuildConfig;
import com.subhub.app.R;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;

/** Disposable emulator notices and a network-disabled DownloadManager item; no APK transfer/install. */
@androidx.test.filters.SdkSuppress(minSdkVersion = 33)
public final class UpdateNotificationAndroidTest {
    private Context context;
    private NotificationManager notifications;
    private UpdateStateStore state;
    private SharedPreferences updates, privacy;
    private Map<String, ?> updatesBefore, privacyBefore;
    private long id = -1;
    private boolean quiesced;
    @Before public void prepare() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UpdaterTestQuiescence.requireColdStartBaseline(context, androidx.work.WorkManager.getInstance(context));
        notifications = context.getSystemService(NotificationManager.class);
        updates = context.getSharedPreferences("subhub_updates", Context.MODE_PRIVATE);
        privacy = context.getSharedPreferences("subhub_privacy", Context.MODE_PRIVATE);
        updatesBefore = new java.util.LinkedHashMap<>(updates.getAll());
        privacyBefore = new java.util.LinkedHashMap<>(privacy.getAll());
        Assume.assumeTrue("Owned guest must already grant updater notification permission",
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
        UpdaterTestQuiescence.stop(context, androidx.work.WorkManager.getInstance(context));
        quiesced = true;
        assertTrue(updates.edit().clear().putBoolean("automatic_checks", false).commit());
        assertTrue(privacy.edit().putBoolean("discreet", false).commit());
        state = new UpdateStateStore(context);
        UpdateNotifications.cancelReady(context);
        if (id >= 0) UpdateNotifications.cancelDownload(context, id);
    }
    @After public void restore() throws Exception {
        if (!quiesced) return;
        UpdaterTestQuiescence.stop(context, androidx.work.WorkManager.getInstance(context));
        if (id >= 0) context.getSystemService(DownloadManager.class).remove(id);
        UpdateNotifications.cancelReady(context);
        if (id >= 0) UpdateNotifications.cancelDownload(context, id);
        try { SharedPreferenceTestRestore.restore(updates, updatesBefore); }
        finally { SharedPreferenceTestRestore.restore(privacy, privacyBefore); }
        assertEquals(updatesBefore, updates.getAll());
        assertEquals(privacyBefore, privacy.getAll());
        UpdateScheduler.synchronize(context);
    }

    @Test public void pendingDownloadIsSilentAndRepeatedPreparationKeepsOneTransfer() throws Exception {
        UpdateCandidate candidate = candidate();
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(
                "android.permission.DOWNLOAD_WITHOUT_NOTIFICATION"));
        assertEquals(DownloadManager.Request.VISIBILITY_HIDDEN, UpdateNotifications.downloadVisibility(context));
        UpdateDownloadCoordinator coordinator = new UpdateDownloadCoordinator(context);
        id = context.getSystemService(DownloadManager.class).enqueue(
                coordinator.request(candidate, candidate.manifest.assets.get(0)).setAllowedNetworkTypes(0));
        state.setCandidate(candidate); state.setDownloadId(id);
        assertEquals(id, coordinator.start(candidate));
        coordinator.prepare(candidate, false);
        assertEquals(id, state.downloadId());
        assertFalse(state.installRequested());
        assertNull(find(UpdateNotifications.downloadTag(id)));
        assertNull(find(null));
        coordinator.cancel();
        try (android.database.Cursor rows = context.getSystemService(DownloadManager.class)
                .query(new DownloadManager.Query().setFilterById(id))) { assertEquals(0, rows.getCount()); }
        assertEquals(-1L, state.downloadId());
    }

    @Test public void onlyCurrentVerifiedFileNotifiesAndRepeatedReadyDoesNotRepost() throws Exception {
        UpdateCandidate candidate = candidate();
        state.setCandidate(candidate);
        UpdateNotifications.ready(context, candidate);
        assertNull(find(null));
        File file = new File(state.updateDirectory(), "synthetic-ready-fixture.apk");
        try {
            try (FileOutputStream output = new FileOutputStream(file)) { output.write(1); }
            // This is synthetic durable state, never passed to an installer.
            state.setVerifiedPath(file.getAbsolutePath());
            UpdateNotifications.ready(context, candidate);
            Notification ready = await(null).getNotification();
            assertEquals(context.getString(R.string.update_ready_title, candidate.manifest.versionName),
                    ready.extras.getCharSequence(Notification.EXTRA_TITLE));
            assertEquals(0, ready.flags & Notification.FLAG_ONGOING_EVENT);
            assertEquals(R.drawable.ic_notification_subhub, ready.getSmallIcon().getResId());
            notifications.cancel(9071);
            awaitAbsent(null);
            UpdateNotifications.ready(context, candidate);
            assertNull(find(null));
            assertEquals(-1L, new UpdateDownloadCoordinator(context).start(candidate));
            assertTrue(file.isFile());
            assertFalse(state.installRequested());
        } finally { state.clearDownload(); file.delete(); }
    }

    @Test public void notificationDeniedKeepsTransferSilentAndReadyUsesPrivacyPresentation() throws Exception {
        Context denied = new ContextWrapper(context) {
            @Override public int checkPermission(String permission, int pid, int uid) {
                return Manifest.permission.POST_NOTIFICATIONS.equals(permission)
                        ? PackageManager.PERMISSION_DENIED : super.checkPermission(permission, pid, uid);
            }
        };
        assertEquals(DownloadManager.Request.VISIBILITY_HIDDEN, UpdateNotifications.downloadVisibility(denied));
        UpdateCandidate candidate = candidate();
        state.setCandidate(candidate);
        File file = new File(state.updateDirectory(), "synthetic-private-ready.apk");
        try {
            try (FileOutputStream output = new FileOutputStream(file)) { output.write(1); }
            state.setVerifiedPath(file.getAbsolutePath());
            UpdateNotifications.ready(denied, candidate);
            assertNull(find(null));
            privacy.edit().putBoolean("discreet", true).commit();
            UpdateNotifications.ready(context, candidate);
            Notification notice = await(null).getNotification();
            assertEquals(R.drawable.ic_discreet_filter, notice.getSmallIcon().getResId());
            assertEquals(context.getString(R.string.privacy_discreet_name),
                    notice.extras.getCharSequence(Notification.EXTRA_TITLE));
        } finally { state.clearDownload(); file.delete(); }
    }

    @Test public void staleCompletionCannotClaimAnotherVerifiedFileOrCurrentGeneration() throws Exception {
        UpdateCandidate candidate = candidate();
        state.setCandidate(candidate);
        File file = new File(state.updateDirectory(), "synthetic-stale-fixture.apk");
        try {
            try (FileOutputStream output = new FileOutputStream(file)) { output.write(1); }
            state.setVerifiedPath(file.getAbsolutePath());
            state.setDownloadId(201);
            long revision = state.channelRevision();
            assertEquals(UpdateDownloadFinalizer.Result.FAILED, UpdateDownloadFinalizer.finish(context, 200));
            assertEquals(file.getAbsolutePath(), state.verifiedPath());
            assertTrue(UpdateDownloadFinalizer.isCurrent(state, 201, revision, candidate));
            assertFalse(UpdateDownloadFinalizer.isCurrent(state, 200, revision, candidate));
            assertFalse(UpdateDownloadFinalizer.isCurrent(state, 201, revision + 1, candidate));
            state.setDownloadId(-1);
            state.setDevUpdates(true);
            state.setCandidate(candidate);
            state.setDownloadId(201);
            assertFalse(UpdateDownloadFinalizer.isCurrent(state, 201, revision, candidate));
            assertNull(find(null));
        } finally { state.setDownloadId(-1); state.clearDownload(); file.delete(); }
    }

    @Test public void sameVersionManifestReplacementCancelsOldTransferAndBytes() throws Exception {
        UpdateCandidate candidate = candidate();
        state.setCandidate(candidate);
        File file = new File(state.updateDirectory(), "synthetic-replaced-fixture.apk");
        try {
            try (FileOutputStream output = new FileOutputStream(file)) { output.write(1); }
            state.setVerifiedPath(file.getAbsolutePath());
            JSONObject replacement = new JSONObject(candidate.manifest.json());
            replacement.getJSONArray("assets").getJSONObject(0).put("sha256", "b".repeat(64));
            UpdateCandidate changed = new UpdateCandidate(UpdateManifest.parse(replacement.toString()), "", "");
            assertFalse(UpdateStateStore.sameCandidate(candidate, changed));
            state.setCandidate(changed);
            assertFalse(file.exists());
            assertEquals("", state.verifiedPath());
            try { new UpdateDownloadCoordinator(context).start(candidate); fail("Stale candidate"); }
            catch (IllegalStateException expected) { }
            assertNull(find(null));
        } finally { state.clearDownload(); file.delete(); }
    }

    @SuppressWarnings("deprecation")
    @Test public void completedUntrustedBytesFailVerificationWithoutNotification() throws Exception {
        UpdateCandidate candidate = candidate();
        state.setCandidate(candidate);
        File source = new File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS),
                "synthetic-rejected-update.apk");
        try {
            try (FileOutputStream output = new FileOutputStream(source)) { output.write(new byte[42]); }
            // Register local synthetic bytes; no network transfer or install is performed.
            id = context.getSystemService(DownloadManager.class).addCompletedDownload("fixture", "fixture",
                    false, "application/vnd.android.package-archive", source.getAbsolutePath(), source.length(), false);
            state.setDownloadId(id);
            assertEquals(UpdateDownloadFinalizer.Result.FAILED, UpdateDownloadFinalizer.finish(context, id));
            assertEquals("", state.verifiedPath());
            assertEquals(-1L, state.downloadId());
            assertFalse(new File(state.updateDirectory(), "download-" + id + ".apk").exists());
            assertNull(find(null));
        } finally { source.delete(); }
    }

    @Test public void automaticOptOutPreventsPreparationAndClearsExistingBytes() throws Exception {
        UpdateCandidate candidate = candidate();
        state.setCandidate(candidate);
        File file = new File(state.updateDirectory(), "synthetic-optout-fixture.apk");
        try {
            try (FileOutputStream output = new FileOutputStream(file)) { output.write(1); }
            state.setVerifiedPath(file.getAbsolutePath());
            state.setAutomaticChecks(false);
            assertFalse(file.exists());
            new UpdateDownloadCoordinator(context).prepare(candidate, true);
            assertEquals(-1L, state.downloadId());
            assertEquals("", state.verifiedPath());
            assertNull(find(null));
        } finally { state.clearDownload(); file.delete(); }
    }

    @Test public void refusedReadyCommitInvalidatesMemoryBeforeConcurrentPreparation() throws Exception {
        UpdateCandidate candidate = candidate();
        state.setCandidate(candidate);
        state.setAutomaticChecks(true);
        UpdateDownloadCoordinator coordinator = new UpdateDownloadCoordinator(context);
        id = context.getSystemService(DownloadManager.class).enqueue(
                coordinator.request(candidate, candidate.manifest.assets.get(0)).setAllowedNetworkTypes(0));
        state.setDownloadId(id);
        File file = new File(state.updateDirectory(), "synthetic-refused-publication.apk");
        java.util.concurrent.CountDownLatch memoryPublished = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch refuseCommit = new java.util.concurrent.CountDownLatch(1);
        SharedPreferences refusing = (SharedPreferences) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, arguments) -> {
                    if (!method.getName().equals("edit")) return invoke(updates, method, arguments);
                    SharedPreferences.Editor editor = updates.edit();
                    return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[]{SharedPreferences.Editor.class}, (editorProxy, editMethod, editArguments) -> {
                                if (editMethod.getName().equals("commit")) {
                                    // Model the failure boundary: memory changed, persistence refused.
                                    editor.apply();
                                    memoryPublished.countDown();
                                    if (!refuseCommit.await(15, java.util.concurrent.TimeUnit.SECONDS)) {
                                        throw new AssertionError("Publication refusal gate timed out");
                                    }
                                    return false;
                                }
                                Object value = invoke(editor, editMethod, editArguments);
                                return value == editor ? editorProxy : value;
                            });
                });
        Context refusingContext = new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return name.equals("subhub_updates") ? refusing : super.getSharedPreferences(name, mode);
            }
        };
        UpdateStateStore refusingState = new UpdateStateStore(refusingContext);
        java.util.concurrent.FutureTask<Boolean> publication = new java.util.concurrent.FutureTask<>(() -> {
            synchronized (UpdateStateStore.class) {
                return refusingState.publishVerified(file.getAbsolutePath());
            }
        });
        java.util.concurrent.FutureTask<Void> preparation = new java.util.concurrent.FutureTask<>(() -> {
            coordinator.prepare(candidate, true);
            return null;
        });
        Thread publisher = new Thread(publication, "updater-refused-publication");
        Thread preparer = new Thread(preparation, "updater-concurrent-preparation");
        try {
            try (FileOutputStream output = new FileOutputStream(file)) { output.write(1); }
            publisher.start();
            assertTrue(memoryPublished.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("The fixture exposes the unacknowledged raw preference value", file.getAbsolutePath(),
                    updates.getString("verified_path", ""));
            preparer.start();
            long deadline = android.os.SystemClock.uptimeMillis() + 5000L;
            while (preparer.getState() != Thread.State.BLOCKED
                    && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(10L);
            assertEquals("Preparation must wait for the publication decision", Thread.State.BLOCKED, preparer.getState());
            assertFalse(preparation.isDone());
            refuseCommit.countDown();
            assertFalse(publication.get(5, java.util.concurrent.TimeUnit.SECONDS));
            preparation.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("", state.verifiedPath());
            assertFalse(file.exists());
            assertEquals("The network-disabled transfer was reused without starting another", id, state.downloadId());
            assertFalse(state.wasNotified(candidate.manifest.tag));
            assertNull(find(null));
        } finally {
            refuseCommit.countDown();
            publisher.join(5000); preparer.join(5000);
            coordinator.cancel(); file.delete();
            assertFalse(publisher.isAlive()); assertFalse(preparer.isAlive());
        }
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] arguments) throws Throwable {
        try { return method.invoke(target, arguments); }
        catch (java.lang.reflect.InvocationTargetException exception) { throw exception.getCause(); }
    }

    @Test public void monochromeBrandHasTransparentCornersAndRecognizableOpenings() {
        Drawable glyph = context.getDrawable(R.drawable.ic_notification_subhub);
        Bitmap image = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        glyph.setBounds(0, 0, 96, 96); glyph.draw(new Canvas(image));
        int visible = 0;
        for (int y = 0; y < 96; y++) for (int x = 0; x < 96; x++) {
            int pixel = image.getPixel(x, y);
            if (android.graphics.Color.alpha(pixel) > 0) {
                visible++; assertEquals(0xFFFFFF, pixel & 0xFFFFFF);
            }
        }
        assertTrue(visible > 1500 && visible < 7000);
        assertEquals(0, image.getPixel(0, 0));
        assertEquals(0, image.getPixel(48, 49));
        assertTrue(android.graphics.Color.alpha(image.getPixel(48, 72)) > 0);
        image.recycle();
    }

    private StatusBarNotification find(String tag) {
        for (StatusBarNotification notice : notifications.getActiveNotifications())
            if (java.util.Objects.equals(tag, notice.getTag())
                    && (tag == null ? notice.getId() == 9070 || notice.getId() == 9071
                            : notice.getId() == 9072)) return notice;
        return null;
    }
    private void awaitAbsent(String tag) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (find(tag) == null) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Expected update notice cancellation");
    }
    private StatusBarNotification await(String tag) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            StatusBarNotification notice = find(tag); if (notice != null) return notice;
            Thread.sleep(20);
        }
        throw new AssertionError("Expected posted update notice");
    }
    private UpdateCandidate candidate() throws Exception {
        JSONObject asset = new JSONObject().put("abi", "universal").put("name", "notification-fixture.apk")
                .put("url", "https://github.com/confiteor48/SubHub/releases/download/v9.9.9/notification-fixture.apk")
                .put("sha256", "a".repeat(64)).put("size", 42);
        JSONObject manifest = new JSONObject().put("schema", 1).put("packageName", "com.subhub.app")
                .put("versionName", "9.9.9").put("versionCode", BuildConfig.VERSION_CODE + 1L)
                .put("minSdk", 26).put("tag", "v9.9.9").put("assets", new JSONArray().put(asset));
        return new UpdateCandidate(UpdateManifest.parse(manifest.toString()), "", "");
    }
}
