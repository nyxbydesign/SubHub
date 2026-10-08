package com.subhub.app.diagnostics;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.subhub.app.R;
import com.subhub.app.security.ControllerPinManager;

import org.junit.Before;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class DiagnosticsCensorLabActivityTest {
    @Before public void resetSession() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        if (!ControllerPinManager.isConfigured(context)) {
            ControllerPinManager.setPin(context, "2468");
        }
        ControllerPinManager.enterDomMode();
        if (CensorLabRecorder.isActive()) CensorLabRecorder.stop(context);
    }

    @Test public void telemetryFallbackStopExposesMarkerAndShareState() {
        try (ActivityScenario<DiagnosticsActivity> scenario =
                     ActivityScenario.launch(DiagnosticsActivity.class)) {
            scenario.onActivity(activity -> {
                try {
                    CensorLabRecorder.start(activity);
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
                activity.findViewById(R.id.button_refresh).performClick();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertTrue(CensorLabRecorder.isActive());
                assertTrue(activity.findViewById(R.id.button_censor_lab_stop).isEnabled());
                assertFalse(activity.findViewById(R.id.button_censor_lab_save).isEnabled());
            });

            scenario.onActivity(activity ->
                    activity.findViewById(R.id.button_censor_lab_stop).performClick());
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> assertTrue(
                    activity.findViewById(R.id.censor_lab_sync_marker).getVisibility()
                            == View.VISIBLE));
            SystemClock.sleep(1_500L);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertFalse(CensorLabRecorder.isActive());
                assertTrue(activity.findViewById(R.id.button_censor_lab_start).isEnabled());
                assertTrue(activity.findViewById(R.id.button_censor_lab_attach).isEnabled());
                assertTrue(activity.findViewById(R.id.button_censor_lab_share_trace).isEnabled());
                assertTrue(activity.findViewById(R.id.button_censor_lab_save).isEnabled());
            });
        }
    }

    @Test public void completedSessionSavesToDownloadsFromTheVisibleButton() throws Exception {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q);
        Context context = ApplicationProvider.getApplicationContext();
        String session = CensorLabRecorder.start(context).id;
        CensorLabRecorder.stop(context);
        Uri saved = null;
        try (ActivityScenario<DiagnosticsActivity> scenario = ActivityScenario.launch(DiagnosticsActivity.class)) {
            androidx.test.espresso.Espresso.onView(
                    androidx.test.espresso.matcher.ViewMatchers.withId(R.id.button_censor_lab_save))
                    .perform(androidx.test.espresso.action.ViewActions.scrollTo(),
                            androidx.test.espresso.action.ViewActions.click());
            long deadline = SystemClock.uptimeMillis() + 10_000L;
            while (saved == null && SystemClock.uptimeMillis() < deadline) {
                try (Cursor rows = context.getContentResolver().query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        new String[]{MediaStore.Downloads._ID, MediaStore.Downloads.IS_PENDING},
                        MediaStore.Downloads.DISPLAY_NAME + " LIKE ?",
                        new String[]{"subhub-censor-lab-" + session + "-%.zip"}, null)) {
                    if (rows != null && rows.moveToFirst() && rows.getInt(1) == 0) {
                        saved = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                rows.getLong(0));
                    }
                }
                if (saved == null) SystemClock.sleep(100L);
            }
            org.junit.Assert.assertNotNull("The local save must finish without a share recipient", saved);
            try (java.io.InputStream input = context.getContentResolver().openInputStream(saved);
                    java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(input)) {
                java.util.zip.ZipEntry first = zip.getNextEntry();
                org.junit.Assert.assertNotNull(first);
                org.junit.Assert.assertEquals("manifest.json", first.getName());
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> activity.findViewById(R.id.diagnostics_scroll).scrollTo(0, 0));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            android.graphics.Bitmap screenshot = InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation().takeScreenshot();
            org.junit.Assert.assertNotNull(screenshot);
            java.io.File preview = new java.io.File(context.getExternalFilesDir(null),
                    "lab-downloads-ui.png");
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(preview)) {
                org.junit.Assert.assertTrue(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,
                        100, output));
            } finally { screenshot.recycle(); }
        } finally {
            if (saved != null) org.junit.Assert.assertEquals(1,
                    context.getContentResolver().delete(saved, null, null));
        }
    }
}
