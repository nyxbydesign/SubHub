package com.subhub.app.settings;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.StrictMode;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import com.subhub.app.R;
import com.subhub.app.capture.CensorImageEditor;
import com.subhub.app.capture.CustomImageManager;
import com.subhub.app.security.ControllerPinManager;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class CensorImageLoadingAndroidTest {
    @Test
    public void hiddenLibraryDefersDecodingAndLockRefreshReusesAndReleasesItsThumbnail()
            throws Exception {
        assumeTrue(android.os.Build.MODEL.contains("sdk_gphone"));
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.SharedPreferences prefs = new SettingsRepository(app).preferences();
        Map<String, ?> original = new LinkedHashMap<>(prefs.getAll());
        CustomImageManager manager = new CustomImageManager(app);
        Set<String> existing = new HashSet<>();
        for (CustomImageManager.Entry entry : manager.listEntries()) existing.add(entry.getId());
        File fixture = new File(app.getCacheDir(), "ui-image-fixture.png");
        Bitmap pixels = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        pixels.eraseColor(0xff6946a2);
        try (FileOutputStream out = new FileOutputStream(fixture)) {
            assertTrue(pixels.compress(Bitmap.CompressFormat.PNG, 100, out));
        } finally {
            pixels.recycle();
        }
        AtomicReference<Bitmap> decoded = new AtomicReference<>();
        boolean wasDom = ControllerPinManager.isDomModeActive();
        try {
            assertEquals(1, manager.addImages(Collections.singletonList(Uri.fromFile(fixture))));
            prefs.edit()
                    .putString(SettingsRepository.KEY_CENSOR_TYPE, "custom")
                    .putBoolean(SettingsRepository.KEY_REVERSE_MODE, false)
                    .putBoolean(FeatureModuleManager.KEY_CENSOR_ENABLED, true)
                    .commit();
            ControllerPinManager.enterDomMode();
            try (ActivityScenario<SettingsActivity> scenario =
                    ActivityScenario.launch(SettingsActivity.class)) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scenario.onActivity(
                        activity -> {
                            LinearLayout list = activity.findViewById(R.id.censor_images_list);
                            assertFalse(list.isShown());
                            assertEquals(
                                    "Collapsed library has not built or decoded image rows",
                                    0,
                                    list.getChildCount());
                            activity.findViewById(R.id.button_appearance_details).performClick();
                        });
                AtomicReference<View> row = new AtomicReference<>();
                long deadline = android.os.SystemClock.uptimeMillis() + 5000;
                while (row.get() == null && android.os.SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity(
                            activity -> {
                                LinearLayout list = activity.findViewById(R.id.censor_images_list);
                                if (list.getChildCount() > 0
                                        && list.getChildAt(0) instanceof LinearLayout)
                                    row.set(list.getChildAt(0));
                            });
                    android.os.SystemClock.sleep(25);
                }
                assertNotNull("Visible library publishes its decoded row", row.get());
                scenario.onActivity(
                        activity -> {
                            LinearLayout list = activity.findViewById(R.id.censor_images_list);
                            ImageView image = (ImageView) ((LinearLayout) row.get()).getChildAt(0);
                            assertTrue(image.getDrawable() instanceof BitmapDrawable);
                            decoded.set(((BitmapDrawable) image.getDrawable()).getBitmap());
                            AtomicInteger reads = new AtomicInteger();
                            StrictMode.ThreadPolicy previous = StrictMode.getThreadPolicy();
                            try {
                                StrictMode.setThreadPolicy(
                                        new StrictMode.ThreadPolicy.Builder()
                                                .detectDiskReads()
                                                .penaltyListener(
                                                        Runnable::run,
                                                        violation -> reads.incrementAndGet())
                                                .build());
                                Field field = SettingsActivity.class.getDeclaredField("images");
                                field.setAccessible(true);
                                CensorImageEditor editor = (CensorImageEditor) field.get(activity);
                                editor.refresh();
                                ControllerPinManager.enterSubMode();
                                editor.refresh();
                                assertSame(row.get(), list.getChildAt(0));
                                assertFalse(((LinearLayout) row.get()).getChildAt(1).isEnabled());
                                assertEquals(
                                        "Lock refresh does not read or decode files",
                                        0,
                                        reads.get());
                            } catch (ReflectiveOperationException error) {
                                throw new AssertionError(error);
                            } finally {
                                StrictMode.setThreadPolicy(previous);
                            }
                        });
            }
            assertTrue("Closing the editor releases its bitmap", decoded.get().isRecycled());
        } finally {
            for (CustomImageManager.Entry entry : manager.listEntries())
                if (!existing.contains(entry.getId())) manager.delete(entry.getId());
            SharedPreferenceTestRestore.restore(prefs, original);
            fixture.delete();
            if (wasDom) ControllerPinManager.enterDomMode();
            else ControllerPinManager.enterSubMode();
        }
    }
}
