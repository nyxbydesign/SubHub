package com.subhub.app.tasks;

import android.content.*;
import android.os.SystemClock;
import android.widget.VideoView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.people.*;
import com.subhub.app.remote.*;
import com.subhub.app.security.*;
import com.subhub.app.settings.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TaskPlaybackAndroidTest {
    @Rule public final KeyholderPresentationAndroidTest.IntegrationFixture integration = new KeyholderPresentationAndroidTest.IntegrationFixture();
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private TaskRuntime runtime;
    private TaskAttachment attachment;
    private TaskDefinition definition;
    private boolean ownsFixture;
    @Rule public final com.subhub.app.UiAutomationStateRule automation = new com.subhub.app.UiAutomationStateRule();
    private Map<String, ?> consent;
    @Before public void setup() throws Exception {
        assertTrue(RemoteFeature.get(context).store.peers(false).isEmpty());
        runtime = TaskRuntime.get(context); assertTrue(runtime.store.load("local").definitions().isEmpty());
        ownsFixture = true;
        consent = new HashMap<>(context.getSharedPreferences(SubFeaturePolicy.STORE, 0).getAll());
        context.getSharedPreferences(SubFeaturePolicy.STORE, 0).edit().putBoolean("tasks", true).putBoolean("hardcore_locally_consented", false).commit();
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit().putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.BOTH.name()).commit();
        ControllerPinManager.initialize(context); assertTrue(ControllerPinManager.setPin(context, "2468")); ControllerPinManager.enterDomMode();
    }
    @After public void cleanup() throws Exception {
        if (!ownsFixture) return;
        for (String table : new String[]{"projections", "journal", "receipts", "cursors", "effects"})
            runtime.store.getWritableDatabase().execSQL("DELETE FROM " + table + " WHERE scope='local'");
        if (attachment != null) {
            File root = new File(context.getFilesDir(), "tasks-media/local");
            for (String suffix : List.of("blob", "size", "part")) {
                File file = new File(root, attachment.hash + "." + suffix);
                assertFalse(java.nio.file.Files.isSymbolicLink(file.toPath()));
                assertTrue(!file.exists() || file.delete());
            }
        }
        SharedPreferenceTestRestore.restore(context.getSharedPreferences(SubFeaturePolicy.STORE, 0), consent);
    }
    private void seed(byte[] bytes, String mime) throws Exception {
        attachment = new TaskAttachment(RemoteProtocol.sha256(bytes), mime, bytes.length);
        RemoteAssets assets = runtime.assets("local"); assets.reserve(attachment.hash, bytes.length);
        long offset = 0;
        while (offset < bytes.length) {
            int end = Math.min(bytes.length, (int) offset + RemoteAssets.CHUNK_BYTES);
            offset = assets.append(attachment.hash, offset, Arrays.copyOfRange(bytes, (int) offset, end));
        }
        TaskMedia.requireHeader(assets.finish(attachment.hash), mime);
        LocalDateTime start = LocalDateTime.ofInstant(Instant.now().minusSeconds(120), ZoneOffset.UTC).withSecond(0).withNano(0);
        TaskDefinition task = new TaskDefinition(UUID.randomUUID().toString(), "Synthetic playback", "", start,
                ZoneId.of("UTC"), 3_600_000, Set.of(), false, false, attachment);
        definition = task;
        runtime.store.append("local", new TaskAction(UUID.randomUUID().toString(), task.id, TaskAction.Kind.CREATE, null,
                System.currentTimeMillis(), "", "", List.of(), task), true, false);
    }
    private ActivityScenario<TaskMediaActivity> launch() throws Exception {
        return ActivityScenario.launch(new Intent(context, TaskMediaActivity.class).setAction(Intent.ACTION_MAIN)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, "local").putExtra(TaskMediaActivity.EXTRA_SCOPE, "local")
                .putExtra(TaskMediaActivity.EXTRA_DOM, true).putExtra(TaskMediaActivity.EXTRA_ATTACHMENT, attachment.json().toString()));
    }
    private void play(ActivityScenario<TaskMediaActivity> page) throws Exception {
        await(() -> {
            AtomicBoolean ready = new AtomicBoolean();
            page.onActivity(activity -> { VideoView player = activity.findViewById(android.R.id.content).findViewWithTag("task_media_player");
                ready.set(player != null && player.getDuration() > 500); });
            return ready.get();
        });
        page.onActivity(activity -> ((VideoView) activity.findViewById(android.R.id.content).findViewWithTag("task_media_player")).start());
        await(() -> {
            AtomicBoolean playing = new AtomicBoolean();
            page.onActivity(activity -> { VideoView player = activity.findViewById(android.R.id.content).findViewWithTag("task_media_player");
                playing.set(player != null && player.isPlaying() && player.getCurrentPosition() > 0); });
            return playing.get();
        });
    }
    @Test public void privateWavPlaysAndLocalOptoutRevokesTheOpenPlayer() throws Exception {
        int samples = 16_000; ByteBuffer wav = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(36 + samples * 2)
                .put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1)
                .putShort((short) 1).putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(samples * 2);
        for (int i = 0; i < samples; i++) wav.putShort((short) (Math.sin(i * 2 * Math.PI * 440 / 8000) * 300));
        seed(wav.array(), "audio/wav");
        try (ActivityScenario<TaskMediaActivity> page = launch()) {
            play(page);
            context.getSharedPreferences(SubFeaturePolicy.STORE, 0).edit().putBoolean("tasks", false).commit();
            await(() -> page.getState() == androidx.lifecycle.Lifecycle.State.DESTROYED);
        }
    }
    @Test public void privateMp4PlaysAndScreenPauseStopsItsPlayer() throws Exception {
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("tasks/sample.mp4")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
            while ((n = input.read(buffer)) != -1) bytes.write(buffer, 0, n);
            seed(bytes.toByteArray(), "video/mp4");
        }
        try (ActivityScenario<TaskMediaActivity> page = launch()) {
            play(page); page.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
            page.onActivity(activity -> assertNull(activity.findViewById(android.R.id.content).findViewWithTag("task_media_player")));
        }
    }
    @Test public void inlineImageRendersAtTheBottomAndOptoutRemovesItsPixels() throws Exception {
        seed(android.util.Base64.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", android.util.Base64.DEFAULT), "image/gif");
        try (ActivityScenario<TasksActivity> page = inline()) {
            awaitInline(page, "task_inline_image");
            page.onActivity(activity -> {
                android.view.ViewGroup body = activity.findViewById(com.subhub.app.R.id.role_page_body);
                android.view.View image = body.findViewWithTag("task_inline_image");
                assertTrue(image.getHeight() <= Math.round(activity.getResources().getDisplayMetrics().heightPixels * .4f));
                assertNotNull(((android.view.ViewGroup) body.getChildAt(body.getChildCount() - 1)).findViewWithTag("task_inline_image"));
            });
            context.getSharedPreferences(SubFeaturePolicy.STORE, 0).edit().putBoolean("tasks", false).commit();
            await(() -> { AtomicBoolean gone = new AtomicBoolean(); page.onActivity(activity -> gone.set(
                    activity.findViewById(android.R.id.content).findViewWithTag("task_inline_image") == null)); return gone.get(); });
        }
    }

    @Test public void inlineMissingMediaRetriesAndInvalidImageShowsAnError() throws Exception {
        byte[] bytes = android.util.Base64.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", android.util.Base64.DEFAULT);
        seed(bytes, "image/gif");
        File file = runtime.assets("local").finish(attachment.hash); assertTrue(file.delete());
        try (ActivityScenario<TasksActivity> page = inline()) {
            awaitInline(page, "task_inline_error");
            RemoteAssets assets = runtime.assets("local"); assets.reserve(attachment.hash, bytes.length);
            assets.append(attachment.hash, 0, bytes); assets.finish(attachment.hash);
            tapInline("task_inline_retry"); awaitInline(page, "task_inline_image");
        }
        try (FileOutputStream stream = new FileOutputStream(file)) { stream.write(new byte[]{0, 1, 2, 3}); }
        try (ActivityScenario<TasksActivity> page = inline()) { awaitInline(page, "task_inline_error"); }
    }

    @Test public void inlineMp4NativeControlsSeekPauseAndRetainPlaybackAcrossRefresh() throws Exception {
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("tasks/sample.mp4")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
            while ((n = input.read(buffer)) != -1) bytes.write(buffer, 0, n);
            seed(bytes.toByteArray(), "video/mp4");
        }
        try (ActivityScenario<TasksActivity> page = inline()) { checkInlinePlayback(page); }
    }

    @Test public void inlineWavNativeControlsPlayAndRoleChangeRevokesPlayback() throws Exception {
        int samples = 32000; ByteBuffer wav = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(36 + samples * 2)
                .put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1)
                .putShort((short) 1).putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(samples * 2);
        for (int i = 0; i < samples; i++) wav.putShort((short) (Math.sin(i * 2 * Math.PI * 440 / 8000) * 300));
        seed(wav.array(), "audio/wav");
        try (ActivityScenario<TasksActivity> page = inline()) {
            awaitInline(page, "task_inline_play"); tapInline("task_inline_play"); awaitPlaying(page);
            ControllerPinManager.enterSubMode();
            await(() -> { AtomicBoolean gone = new AtomicBoolean(); page.onActivity(activity -> gone.set(
                    activity.findViewById(android.R.id.content).findViewWithTag("task_inline_player") == null)); return gone.get(); });
        }
    }

    private ActivityScenario<TasksActivity> inline() throws Exception {
        ActivityScenario<TasksActivity> page = ActivityScenario.launch(new Intent(context, TasksActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, "local"));
        String row = "task_open:" + definition.occurrenceId(definition.firstStart.toLocalDate());
        awaitInline(page, row); tapInline(row); return page;
    }
    private void checkInlinePlayback(ActivityScenario<TasksActivity> page) throws Exception {
        awaitInline(page, "task_inline_play"); tapInline("task_inline_play"); awaitPlaying(page);
        java.util.concurrent.atomic.AtomicReference<VideoView> original = new java.util.concurrent.atomic.AtomicReference<>();
        page.onActivity(activity -> original.set(activity.findViewById(android.R.id.content).findViewWithTag("task_inline_player")));
        page.onActivity(activity -> { try {
            java.lang.reflect.Method refresh = TasksActivity.class.getDeclaredMethod("refresh"); refresh.setAccessible(true); refresh.invoke(activity);
        } catch (Exception failure) { throw new AssertionError(failure); } });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        page.onActivity(activity -> assertSame(original.get(), activity.findViewById(android.R.id.content).findViewWithTag("task_inline_player")));
        tapInline("task_inline_play");
        page.onActivity(activity -> assertFalse(original.get().isPlaying()));
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withTagValue(org.hamcrest.Matchers.is("task_inline_seek")))
                .perform(com.subhub.app.NativeUiActions.revealAboveNavigation(),
                    new androidx.test.espresso.action.GeneralClickAction(androidx.test.espresso.action.Tap.SINGLE, view -> {
                        int[] location = new int[2]; view.getLocationOnScreen(location);
                        return new float[]{location[0] + view.getWidth() * .7f, location[1] + view.getHeight() * .5f};
                    }, androidx.test.espresso.action.Press.FINGER));
        await(() -> { AtomicBoolean moved = new AtomicBoolean(); page.onActivity(activity -> moved.set(
                original.get().getCurrentPosition() >= original.get().getDuration() / 2)); return moved.get(); });
        page.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
        assertFalse(original.get().isPlaying());
        page.onActivity(activity -> assertNull(activity.findViewById(android.R.id.content).findViewWithTag("task_inline_player")));
    }
    private void awaitPlaying(ActivityScenario<TasksActivity> page) throws Exception {
        await(() -> { AtomicBoolean playing = new AtomicBoolean(); page.onActivity(activity -> {
            VideoView player = activity.findViewById(android.R.id.content).findViewWithTag("task_inline_player");
            playing.set(player != null && player.isPlaying() && player.getCurrentPosition() > 0);
        }); return playing.get(); });
    }
    private void awaitInline(ActivityScenario<TasksActivity> page, String tag) throws Exception {
        await(() -> { AtomicBoolean ready = new AtomicBoolean(); page.onActivity(activity -> {
            android.view.View view = activity.findViewById(android.R.id.content).findViewWithTag(tag);
            ready.set(view != null && view.isEnabled());
        }); return ready.get(); });
    }
    private void tapInline(String tag) {
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withTagValue(org.hamcrest.Matchers.is(tag)))
                .perform(com.subhub.app.NativeUiActions.revealAboveNavigation(), androidx.test.espresso.action.ViewActions.click());
    }

    private interface Condition { boolean ready() throws Exception; }
    private static void await(Condition condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 12_000;
        do { if (condition.ready()) return; SystemClock.sleep(50); } while (SystemClock.elapsedRealtime() < deadline);
        fail("Native task media state did not arrive");
    }
}
