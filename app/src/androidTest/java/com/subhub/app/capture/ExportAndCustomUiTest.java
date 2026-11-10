package com.subhub.app.capture;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.widget.LinearLayout;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.subhub.app.R;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class ExportAndCustomUiTest {
    @org.junit.Before public void enterControllerForEditableScreens() {
        com.subhub.app.security.ControllerPinManager.enterDomMode();
    }
    @After public void clearCustomImages() {
        Context context = ApplicationProvider.getApplicationContext();
        CustomImageManager manager = new CustomImageManager(context);
        for (CustomImageManager.Entry entry : manager.listEntries()) manager.delete(entry.getId());
    }

    @Test public void exportShowsCurrentStyleAndRequiresSecondDeleteConfirmation() {
        try (ActivityScenario<ExportActivity> scenario = ActivityScenario.launch(ExportActivity.class)) {
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(R.id.export_look_button));
                assertTrue(activity.findViewById(R.id.button_pick_images).isEnabled());
                assertFalse(((SwitchMaterial) activity.findViewById(
                        R.id.switch_delete_originals)).isChecked());
            });
            onView(withId(R.id.switch_delete_originals)).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click());
            onView(withText(R.string.export_delete_warning_title)).check(matches(isDisplayed()));
            onView(withText(R.string.export_delete_warning_enable)).perform(click());
            scenario.onActivity(activity -> assertTrue(((SwitchMaterial) activity.findViewById(
                    R.id.switch_delete_originals)).isChecked()));
        }
    }

    @Test public void syntheticCensoredBitmapSavesToAndCleansUpFromGallery() throws Exception {
        AtomicReference<Uri> saved = new AtomicReference<>();
        try (ActivityScenario<ExportActivity> scenario = ActivityScenario.launch(ExportActivity.class)) {
            scenario.onActivity(activity -> {
                Bitmap bitmap = Bitmap.createBitmap(96, 72, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.MAGENTA);
                try {
                    saved.set(activity.saveToGallery(bitmap, 1));
                } catch (Exception error) {
                    throw new AssertionError(error);
                } finally {
                    bitmap.recycle();
                }
            });
        }
        assertNotNull(saved.get());
        Context context = ApplicationProvider.getApplicationContext();
        try (InputStream input = context.getContentResolver().openInputStream(saved.get())) {
            Bitmap decoded = BitmapFactory.decodeStream(input);
            assertNotNull(decoded);
            assertTrue(decoded.getWidth() > 0 && decoded.getHeight() > 0);
            decoded.recycle();
        } finally {
            context.getContentResolver().delete(saved.get(), null, null);
        }
    }

    @Test public void customImageLibraryExposesPickerAndEmptyState() {
        clearCustomImages();
        try (ActivityScenario<CustomImagesActivity> scenario =
                     ActivityScenario.launch(CustomImagesActivity.class)) {
            scenario.onActivity(activity -> {
                assertTrue(activity.findViewById(R.id.button_add).isEnabled());
                LinearLayout list = activity.findViewById(R.id.image_list);
                assertTrue(list.getChildCount() > 0);
            });
            onView(withText(R.string.custom_images_empty)).check(matches(isDisplayed()));
        }
    }
}
