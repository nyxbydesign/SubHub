package com.subhub.app.capture.export;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.intent.Intents;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.capture.ExportActivity;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.SettingsRepository;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ExportWorkspaceAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    @Test public void mixedSelectionPreviewAndBackgroundExportUseIndependentSettings() throws Exception {
        ControllerPinManager.enterDomMode();
        SettingsRepository live = new SettingsRepository(context);
        String originalStyle = live.preferences().getString(SettingsRepository.KEY_CENSOR_TYPE, "box");
        ExportSettings.reset(context);
        ExportSettings.preferences(context).edit().putString(SettingsRepository.KEY_CENSOR_TYPE, "box")
                .putBoolean(SettingsRepository.KEY_REVERSE_MODE, true)
                .putFloat(SettingsRepository.KEY_REVERSE_STRENGTH, 1f)
                .putStringSet(SettingsRepository.KEY_ENABLED_CATEGORIES, Collections.emptySet()).commit();
        File sourceVideo = new File(context.getCacheDir(), "workspace-source.mp4");
        long[] times = new long[45]; for (int i = 0; i < times.length; i++) times[i] = i * 50_000L;
        ExportFoundationAndroidTest.generate(sourceVideo, times, 90);
        Uri video = seed(sourceVideo, true);
        File sourcePhoto = new File(context.getCacheDir(), "workspace-source.jpg");
        Bitmap image = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888); image.eraseColor(Color.GREEN);
        Canvas pattern = new Canvas(image); Paint paint = new Paint(); paint.setColor(Color.BLUE);
        for (int x = 0; x < 320; x += 16) pattern.drawRect(x, 0, x + 8, 240, paint);
        try (OutputStream output = new FileOutputStream(sourcePhoto)) { image.compress(Bitmap.CompressFormat.JPEG, 95, output); } finally { image.recycle(); }
        Uri photo = seed(sourcePhoto, false); List<Uri> copies = new ArrayList<>();
        Intents.init();
        try {
            Intent selected = new Intent(); ClipData clip = ClipData.newRawUri("Synthetic fixtures", photo); clip.addItem(new ClipData.Item(video)); selected.setClipData(clip);
            Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(new Instrumentation.ActivityResult(Activity.RESULT_OK, selected));
            try (ActivityScenario<ExportActivity> scenario = ActivityScenario.launch(ExportActivity.class)) {
                onView(withId(R.id.button_pick_images)).perform(scrollTo(), click());
                onView(withId(R.id.export_preview_button)).perform(scrollTo(), click());
                await(() -> {
                    AtomicBoolean visible = new AtomicBoolean();
                    scenario.onActivity(a -> visible.set(a.findViewById(R.id.export_preview_image).getVisibility() == android.view.View.VISIBLE));
                    return visible.get();
                }, 45_000);
                String job;
                try (ExportJobStore store = new ExportJobStore(context)) {
                    job = store.latest(); assertEquals(2, store.items(job).size());
                    assertTrue(store.items(job).stream().allMatch(item -> item.state == ExportJobStore.State.DRAFT));
                }
                assertEquals(originalStyle, live.preferences().getString(SettingsRepository.KEY_CENSOR_TYPE, "box"));
                onView(withId(R.id.export_start_batch_button)).perform(scrollTo(), click());
                await(ExportService::isRunning, 15_000);
                scenario.moveToState(Lifecycle.State.CREATED);
                await(() -> {
                    try (ExportJobStore store = new ExportJobStore(context)) {
                        return store.items(job).stream().allMatch(item -> item.state == ExportJobStore.State.SAVED
                                || item.state == ExportJobStore.State.FAILED || item.state == ExportJobStore.State.INTERRUPTED);
                    }
                }, 90_000);
                try (ExportJobStore store = new ExportJobStore(context)) {
                    for (ExportJobStore.Item item : store.items(job)) {
                        assertEquals(item.message, ExportJobStore.State.SAVED, item.state); copies.add(Uri.parse(item.output));
                        try (InputStream input = context.getContentResolver().openInputStream(Uri.parse(item.source))) { assertNotNull(input); }
                    }
                }
                scenario.moveToState(Lifecycle.State.RESUMED);
                onView(withId(R.id.export_results_list)).perform(scrollTo());
                Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
                try (OutputStream output = new FileOutputStream(new File(context.getFilesDir(), "gallery-results.png"))) { screenshot.compress(Bitmap.CompressFormat.PNG, 100, output); }
                screenshot.recycle();
                onView(withId(R.id.button_pick_images)).perform(scrollTo());
                scenario.onActivity(a -> {
                    android.view.View view = a.findViewById(R.id.button_pick_images);
                    android.view.ViewParent parent = view.getParent();
                    while (parent != null && !(parent instanceof android.widget.ScrollView)) parent = parent.getParent();
                    if (parent instanceof android.widget.ScrollView) ((android.widget.ScrollView) parent).scrollTo(0, 0);
                });
                InstrumentationRegistry.getInstrumentation().getUiAutomation().waitForIdle(150, 3000);
                screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
                try (OutputStream output = new FileOutputStream(new File(context.getFilesDir(), "gallery-controls.png"))) { screenshot.compress(Bitmap.CompressFormat.PNG, 100, output); }
                screenshot.recycle();
            }
        } finally {
            Intents.release();
            for (Uri uri : copies) context.getContentResolver().delete(uri, null, null);
            context.getContentResolver().delete(video, null, null); context.getContentResolver().delete(photo, null, null);
            ExportSettings.reset(context); ControllerPinManager.enterSubMode();
        }
    }
    private Uri seed(File file, boolean video) throws IOException {
        ContentValues values = new ContentValues(); values.put(MediaStore.MediaColumns.DISPLAY_NAME, "UX synthetic " + file.getName());
        values.put(MediaStore.MediaColumns.MIME_TYPE, video ? "video/mp4" : "image/jpeg");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, (video ? "Movies" : "Pictures") + "/SubHubTest");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri result = context.getContentResolver().insert(video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        try (InputStream input = new FileInputStream(file); OutputStream output = context.getContentResolver().openOutputStream(result)) {
            byte[] buffer = new byte[8192]; int length; while ((length = input.read(buffer)) >= 0) output.write(buffer, 0, length);
        }
        values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0); context.getContentResolver().update(result, values, null, null); return result;
    }
    private static void await(java.util.function.BooleanSupplier ready, long timeout) {
        long end = SystemClock.elapsedRealtime() + timeout;
        while (!ready.getAsBoolean() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100);
        assertTrue("Expected export state before timeout", ready.getAsBoolean());
    }
}
