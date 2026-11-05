package com.subhub.app.update;

import static org.junit.Assert.*;

import android.content.Context;
import android.app.DownloadManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.content.SharedPreferences;
import android.widget.CompoundButton;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.subhub.app.BuildConfig;
import com.subhub.app.R;
import com.subhub.app.security.ControllerPinManager;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Disposable-emulator checks: no APK downloads, installs, personal data or payments. */
@RunWith(AndroidJUnit4.class)
public final class DevUpdatesAndroidTest {
    private Context context;
    private SharedPreferences preferences;
    private UpdateStateStore state;
    private java.util.Map<String, ?> originalPreferences;
    private boolean originalDom;
    private boolean quiesced;

    @Before public void setup() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        UpdaterTestQuiescence.requireColdStartBaseline(context, androidx.work.WorkManager.getInstance(context));
        preferences = context.getSharedPreferences("subhub_updates", Context.MODE_PRIVATE);
        originalPreferences = new java.util.LinkedHashMap<>(preferences.getAll());
        originalDom = ControllerPinManager.isDomModeActive();
        UpdaterTestQuiescence.stop(context, androidx.work.WorkManager.getInstance(context));
        quiesced = true;
        assertTrue(preferences.edit().clear().putBoolean("automatic_checks", false).commit());
        state = new UpdateStateStore(context);
    }

    @After public void cleanup() throws Exception {
        if (originalPreferences == null || !quiesced) return;
        UpdaterTestQuiescence.stop(context, androidx.work.WorkManager.getInstance(context));
        try {
            com.subhub.app.settings.SharedPreferenceTestRestore.restore(preferences, originalPreferences);
        } finally {
            if (ControllerPinManager.isDomModeActive() != originalDom) {
                if (originalDom) ControllerPinManager.enterDomMode(); else ControllerPinManager.enterSubMode();
            }
        }
        assertEquals(originalPreferences, preferences.getAll());
        assertEquals(originalDom, ControllerPinManager.isDomModeActive());
        // The owned guest's cancelled updater jobs are recreated according to the restored preferences.
        UpdateScheduler.synchronize(context);
    }

    @Test public void devUpdatesDefaultOffAndPersistAcrossStoreRecreation() {
        assertFalse(state.devUpdates());
        state.setDevUpdates(true);
        assertTrue(new UpdateStateStore(context).devUpdates());
        state.setDevUpdates(false);
        assertFalse(new UpdateStateStore(context).devUpdates());
        assertFalse(state.automaticChecks());
    }

    @Test public void channelSwitchInvalidatesCacheDownloadAndInflightPublication() throws Exception {
        state.setDevUpdates(true);
        UpdateCandidate candidate = candidate("0.6.5-dev.123", BuildConfig.VERSION_CODE + 2L);
        long revision = state.channelRevision();
        state.setCandidate(candidate);
        state.setEtag("stale-channel");
        state.setLastCheck(123);
        File file = new File(state.updateDirectory(), "synthetic-channel-test.apk");
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(1); }
        state.setVerifiedPath(file.getAbsolutePath());
        state.setInstallRequested(true);
        DownloadManager manager = context.getSystemService(DownloadManager.class);
        // Zero allowed network types keeps this owned fixture pending without downloading bytes.
        long pending = manager.enqueue(new DownloadManager.Request(Uri.parse("https://example.invalid/subhub-fixture.apk"))
                .setAllowedNetworkTypes(0).setDestinationInExternalFilesDir(context,
                        Environment.DIRECTORY_DOWNLOADS, "pending-channel-fixture.apk"));
        state.setDownloadId(pending);
        try (Cursor row = manager.query(new DownloadManager.Query().setFilterById(pending))) {
            assertEquals(1, row.getCount());
        }
        state.setDevUpdates(false);
        assertNull(state.candidate());
        assertEquals("", state.etag());
        assertEquals(0, state.lastCheck());
        assertFalse(file.exists());
        assertFalse(state.installRequested());
        assertEquals(-1, state.downloadId());
        try (Cursor row = manager.query(new DownloadManager.Query().setFilterById(pending))) {
            assertEquals("The actual pending DownloadManager row must be removed", 0, row.getCount());
        }
        assertFalse(state.saveCheck(revision, "old", java.util.Collections.emptyList(), candidate));
        assertNull(state.candidate());
        assertEquals("", state.etag());
    }

    @Test public void legacyCachedDevCandidateCannotBeOfferedOrDownloadedWithoutOptIn() throws Exception {
        UpdateCandidate dev = candidate("0.6.5-dev.123", BuildConfig.VERSION_CODE + 2L);
        preferences.edit().putString("candidate", dev.json()).commit();
        assertNull(state.candidate());
        try {
            new UpdateDownloadCoordinator(context).start(dev);
            fail("Disabled development channel must reject the download before enqueue");
        } catch (IllegalStateException expected) { }
        assertEquals(-1, state.downloadId());
    }

    @Test public void nativeToggleActuallyChangesPreferenceAndPersistsOnRecreate() {
        try (ActivityScenario<UpdatesActivity> scenario = ActivityScenario.launch(UpdatesActivity.class)) {
            scenario.onActivity(activity -> {
                CompoundButton toggle = activity.findViewById(R.id.dev_updates);
                assertEquals("Dev updates", toggle.getText().toString());
                assertFalse(toggle.isChecked());
                assertTrue(toggle.isEnabled());
                toggle.performClick();
                assertTrue(toggle.isChecked());
                assertTrue(state.devUpdates());
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                CompoundButton toggle = activity.findViewById(R.id.dev_updates);
                assertTrue(toggle.isChecked());
                assertTrue(toggle.getWidth() > 0);
                assertTrue(toggle.getHeight() >= Math.round(48 * activity.getResources().getDisplayMetrics().density));
            });
        }
    }

    @Test public void subModeCanChangeUpdaterPreferencesWithNativeTapsAndRecreation() throws Exception {
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<UpdatesActivity> scenario = ActivityScenario.launch(UpdatesActivity.class)) {
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.automatic_checks))
                    .perform(androidx.test.espresso.action.ViewActions.scrollTo(), androidx.test.espresso.action.ViewActions.click());
            assertTrue(state.automaticChecks());
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.dev_updates))
                    .perform(androidx.test.espresso.action.ViewActions.scrollTo(), androidx.test.espresso.action.ViewActions.click());
            assertTrue(state.devUpdates());
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertTrue(((CompoundButton) activity.findViewById(R.id.dev_updates)).isChecked());
                assertTrue(((CompoundButton) activity.findViewById(R.id.automatic_checks)).isChecked());
                assertEquals(activity.getString(R.string.update_dev_help),
                        ((android.widget.TextView) activity.findViewById(R.id.dev_updates_help)).getText().toString());
            });
            assertFalse(ControllerPinManager.isDomModeActive());
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.automatic_checks))
                    .perform(androidx.test.espresso.action.ViewActions.scrollTo(), androidx.test.espresso.action.ViewActions.click());
            assertFalse(state.automaticChecks());
        }
    }

    @Test public void realHttpCheckFindsStableOutsideDevFeedAndRechecksAfterChannelSwitch() throws Exception {
        try (FeedServer server = new FeedServer()) {
            GitHubReleaseRepository repository = new GitHubReleaseRepository(context,
                    server.root + "/feed", server.root + "/latest");
            GitHubReleaseRepository.Result stable = repository.check();
            assertTrue(stable.succeeded());
            assertEquals("v0.6.4", stable.candidate.manifest.tag);
            assertEquals(0, server.devManifestRequests.get());
            assertEquals("\"fixture-feed\"", state.etag());
            state.setDevUpdates(true);
            assertEquals("", state.etag());
            GitHubReleaseRepository.Result dev = repository.check();
            assertTrue(dev.succeeded());
            assertEquals("v0.6.4-dev.123", dev.candidate.manifest.tag);
            assertTrue(dev.candidate.prerelease);
            assertEquals(1, server.devManifestRequests.get());
            GitHubReleaseRepository.Result cached = repository.check();
            assertEquals(dev.candidate.manifest.tag, cached.candidate.manifest.tag);
            assertEquals(1, server.notModified.get());
            state.setDevUpdates(false);
            assertNull(state.candidate());
            assertEquals("v0.6.4", repository.check().candidate.manifest.tag);
            assertFalse(state.candidate().prerelease);
            assertEquals(1, server.devManifestRequests.get());
        }
    }

    @Test public void malformedDevelopmentManifestDoesNotMaskValidStableRelease() throws Exception {
        state.setDevUpdates(true);
        try (FeedServer server = new FeedServer()) {
            server.invalidDev = true;
            GitHubReleaseRepository.Result result = new GitHubReleaseRepository(context,
                    server.root + "/feed", server.root + "/latest").check();
            assertTrue(result.succeeded());
            assertEquals("v0.6.4", result.candidate.manifest.tag);
            assertEquals(1, server.devManifestRequests.get());
        }
    }

    @Test public void concurrentReleaseChecksShareOneFeedRequest() throws Exception {
        try (FeedServer server = new FeedServer()) {
            server.blockFirstFeed = true;
            GitHubReleaseRepository repository = new GitHubReleaseRepository(context,
                    server.root + "/feed", server.root + "/latest");
            java.util.concurrent.FutureTask<GitHubReleaseRepository.Result> first =
                    new java.util.concurrent.FutureTask<>(repository::check);
            java.util.concurrent.FutureTask<GitHubReleaseRepository.Result> second =
                    new java.util.concurrent.FutureTask<>(repository::check);
            Thread one = new Thread(first, "updater-first-check");
            Thread two = new Thread(second, "updater-second-check");
            try {
                one.start();
                assertTrue(server.firstFeedEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                two.start();
                awaitCheckMonitor(two);
                server.releaseFirstFeed.countDown();
                assertEquals("v0.6.4", first.get(5, java.util.concurrent.TimeUnit.SECONDS).candidate.manifest.tag);
                assertEquals("v0.6.4", second.get(5, java.util.concurrent.TimeUnit.SECONDS).candidate.manifest.tag);
                assertEquals(1, server.feedRequests.get());
            } finally {
                server.releaseFirstFeed.countDown();
                one.join(5000); two.join(5000);
                assertFalse(one.isAlive()); assertFalse(two.isAlive());
            }
        }
    }

    @Test public void channelChangeDuringInflightCheckStartsFreshLookup() throws Exception {
        try (FeedServer server = new FeedServer()) {
            server.blockFirstFeed = true;
            GitHubReleaseRepository repository = new GitHubReleaseRepository(context,
                    server.root + "/feed", server.root + "/latest");
            java.util.concurrent.FutureTask<GitHubReleaseRepository.Result> first =
                    new java.util.concurrent.FutureTask<>(repository::check);
            java.util.concurrent.FutureTask<GitHubReleaseRepository.Result> second =
                    new java.util.concurrent.FutureTask<>(repository::check);
            Thread one = new Thread(first, "updater-old-channel-check");
            Thread two = new Thread(second, "updater-new-channel-check");
            try {
                one.start();
                assertTrue(server.firstFeedEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                state.setDevUpdates(true);
                two.start();
                awaitCheckMonitor(two);
                server.releaseFirstFeed.countDown();
                assertNull(first.get(5, java.util.concurrent.TimeUnit.SECONDS).candidate);
                assertEquals("v0.6.4-dev.123", second.get(5, java.util.concurrent.TimeUnit.SECONDS).candidate.manifest.tag);
                assertEquals(2, server.feedRequests.get());
                assertEquals("v0.6.4-dev.123", state.candidate().manifest.tag);
            } finally {
                server.releaseFirstFeed.countDown();
                one.join(5000); two.join(5000);
                assertFalse(one.isAlive()); assertFalse(two.isAlive());
            }
        }
    }

    private static void awaitCheckMonitor(Thread thread) throws Exception {
        long deadline = android.os.SystemClock.uptimeMillis() + 5000L;
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (thread.getState() == Thread.State.BLOCKED) {
                for (StackTraceElement frame : thread.getStackTrace()) {
                    if (frame.getClassName().equals(GitHubReleaseRepository.class.getName())
                            && frame.getMethodName().equals("check")) return;
                }
            }
            Thread.sleep(10L);
        }
        fail("Second check must be waiting on the in-flight check monitor");
    }

    private static UpdateCandidate candidate(String version, long code) throws Exception {
        return new UpdateCandidate(UpdateManifest.parse(manifest(version, code)), "Fixture notes", "");
    }

    private static String manifest(String version, long code) throws Exception {
        JSONObject asset = new JSONObject().put("abi", "universal").put("name", "fixture.apk")
                .put("url", "https://github.com/confiteor48/SubHub/releases/download/v" + version + "/fixture.apk")
                .put("sha256", "a".repeat(64)).put("size", 42);
        return new JSONObject().put("schema", 1).put("packageName", "com.subhub.app")
                .put("versionName", version).put("versionCode", code).put("minSdk", 26)
                .put("tag", "v" + version).put("assets", new JSONArray().put(asset)).toString();
    }

    private static final class FeedServer implements AutoCloseable {
        final ServerSocket listener;
        final Thread thread;
        final String root;
        final AtomicInteger devManifestRequests = new AtomicInteger();
        final AtomicInteger notModified = new AtomicInteger();
        final AtomicInteger feedRequests = new AtomicInteger();
        final java.util.concurrent.CountDownLatch firstFeedEntered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch releaseFirstFeed = new java.util.concurrent.CountDownLatch(1);
        volatile boolean blockFirstFeed;
        volatile boolean invalidDev;
        volatile boolean closed;

        FeedServer() throws Exception {
            listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            root = "http://127.0.0.1:" + listener.getLocalPort();
            thread = new Thread(this::serve, "updater-fixture-http");
            thread.start();
        }

        JSONObject release(String version, boolean dev) throws Exception {
            return new JSONObject().put("draft", false).put("prerelease", dev)
                    .put("tag_name", "v" + version).put("body", "Fixture notes")
                    .put("assets", new JSONArray().put(new JSONObject()
                            .put("name", "SubHub-" + version + "-update.json")
                            .put("browser_download_url", root + (dev ? "/dev" : "/stable"))));
        }

        void serve() {
            while (!closed) {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.UTF_8));
                    String first = reader.readLine();
                    if (first == null) continue;
                    String path = first.split(" ")[1];
                    boolean cached = false;
                    for (String line; (line = reader.readLine()) != null && !line.isEmpty();) {
                        if (line.toLowerCase(java.util.Locale.ROOT).startsWith("if-none-match:")) cached = true;
                    }
                    if (path.equals("/feed")) {
                        int count = feedRequests.incrementAndGet();
                        if (blockFirstFeed && count == 1) {
                            firstFeedEntered.countDown();
                            if (!releaseFirstFeed.await(15, java.util.concurrent.TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Fixture feed gate timed out");
                            }
                        }
                    }
                    String body;
                    int code = 200;
                    if (path.equals("/feed") && cached) {
                        code = 304;
                        body = "";
                        notModified.incrementAndGet();
                    } else if (path.equals("/feed")) {
                        body = new JSONArray().put(release("0.6.4-dev.123", true)).toString();
                    } else if (path.equals("/latest")) {
                        body = release("0.6.4", false).toString();
                    } else if (path.equals("/dev")) {
                        devManifestRequests.incrementAndGet();
                        body = invalidDev ? "not-json" : manifest("0.6.4-dev.123", BuildConfig.VERSION_CODE + 2L);
                    } else {
                        body = manifest("0.6.4", BuildConfig.VERSION_CODE + 1L);
                    }
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    socket.getOutputStream().write(("HTTP/1.1 " + code + " Fixture\r\n"
                            + "Content-Type: application/json\r\nETag: \"fixture-feed\"\r\n"
                            + "Content-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().write(bytes);
                } catch (Exception exception) {
                    if (!closed) throw new AssertionError(exception);
                }
            }
        }

        @Override public void close() throws Exception {
            closed = true;
            releaseFirstFeed.countDown();
            listener.close();
            thread.join(5000);
            assertFalse("Fixture server must stop", thread.isAlive());
        }
    }
}
