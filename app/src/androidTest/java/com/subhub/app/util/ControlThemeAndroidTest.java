package com.subhub.app.util;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.view.ContextThemeWrapper;
import android.view.View;
import androidx.core.graphics.ColorUtils;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Native drawable exports only; never captures the app or device screen. */
@RunWith(AndroidJUnit4.class)
public final class ControlThemeAndroidTest {
    private static Context themed() {
        return new ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_SubHub);
    }

    @Test public void enabledPaletteKeepsTextAndControlBoundariesReadable() {
        Context context = themed();
        int surface = context.getColor(R.color.control_checked_surface);
        assertTrue(ColorUtils.calculateContrast(context.getColor(R.color.control_checked_text), surface) >= 4.5);
        assertTrue(ColorUtils.calculateContrast(context.getColor(R.color.control_checked_outline), surface) >= 3);
        assertTrue(ColorUtils.calculateContrast(context.getColor(R.color.control_checked_indicator), surface) >= 3);
        assertNotEquals(context.getColor(R.color.accent), context.getColor(R.color.control_checked_indicator));
    }

    @Test public void disabledCheckedControlsDoNotUseTheirEnabledTint() {
        Context context = themed();
        int enabled = android.R.attr.state_enabled;
        int checked = android.R.attr.state_checked;
        for (int resource : new int[]{R.color.toggle_tint, R.color.switch_thumb_tint, R.color.switch_track_tint}) {
            ColorStateList colors = context.getColorStateList(resource);
            int active = colors.getColorForState(new int[]{enabled, checked}, 0);
            int disabled = colors.getColorForState(new int[]{checked}, 0);
            assertNotEquals(active, disabled);
            assertEquals(disabled, colors.getColorForState(new int[]{}, 0));
        }
        Drawable tile = context.getDrawable(R.drawable.bg_selection_tile);
        tile.setState(new int[]{checked});
        Bitmap bitmap = render(tile, 64, 48);
        assertEquals(context.getColor(R.color.surface_disabled), bitmap.getPixel(32, 24));
        bitmap.recycle();
    }

    @Test public void checkedStateToggleActuallyDrawsTheSemanticPlumFill() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = themed();
            StateToggle toggle = new StateToggle(context);
            int width = Math.round(220 * context.getResources().getDisplayMetrics().density);
            int height = Math.round(48 * context.getResources().getDisplayMetrics().density);
            toggle.setChecked(true);
            toggle.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            toggle.layout(0, 0, width, height);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            toggle.draw(new Canvas(bitmap));
            int inset = Math.round(8 * context.getResources().getDisplayMetrics().density);
            int x = width - toggle.getPaddingRight() - toggle.getSwitchMinWidth() + inset;
            assertEquals(context.getColor(R.color.control_checked_surface), bitmap.getPixel(x, height / 2));
            bitmap.recycle();
            toggle.performClick();
            assertFalse(toggle.isChecked());
        });
    }

    @Test public void portraitPhoneIconsRenderDistinctCoverageWithoutClippedEdges() throws IOException {
        Context context = themed();
        int[] resources = {R.drawable.ic_quality_low, R.drawable.ic_quality_medium, R.drawable.ic_quality_high};
        String[] labels = {"Low", "Medium", "High"};
        Bitmap sheet = Bitmap.createBitmap(800, 485, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(sheet);
        canvas.drawColor(context.getColor(R.color.background));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(context.getColor(R.color.control_checked_indicator));
        paint.setTextSize(16);
        canvas.drawText("NATIVE ANDROID ICONS - 4x above / 44x64 below", 40, 33, paint);
        Bitmap previous = null;
        File directory = new File(context.getExternalFilesDir(null), "icon-qa");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        for (int index = 0; index < resources.length; index++) {
            Drawable icon = context.getDrawable(resources[index]);
            float density = context.getResources().getDisplayMetrics().density;
            assertEquals(Math.round(44 * density), icon.getIntrinsicWidth());
            assertEquals(Math.round(64 * density), icon.getIntrinsicHeight());
            Bitmap bitmap = render(icon, 44, 64);
            for (int x = 0; x < 44; x++) {
                assertEquals(0, Color.alpha(bitmap.getPixel(x, 0)));
                assertEquals(0, Color.alpha(bitmap.getPixel(x, 63)));
            }
            for (int y = 0; y < 64; y++) {
                assertEquals(0, Color.alpha(bitmap.getPixel(0, y)));
                assertEquals(0, Color.alpha(bitmap.getPixel(43, y)));
            }
            if (previous != null) {
                int differences = 0;
                for (int y = 0; y < 64; y++) for (int x = 0; x < 44; x++)
                    if (previous.getPixel(x, y) != bitmap.getPixel(x, y)) differences++;
                assertTrue("Coverage differences must survive native icon-size rendering", differences >= 80);
                previous.recycle();
            }
            write(bitmap, new File(directory, "quality-" + labels[index].toLowerCase(java.util.Locale.ROOT) + ".png"));
            int left = 60 + index * 250;
            paint.setColor(context.getColor(R.color.surface_disabled));
            canvas.drawRoundRect(left - 20, 55, left + 200, 445, 20, 20, paint);
            int saved = canvas.save();
            canvas.translate(left, 75);
            canvas.scale(4, 4);
            icon.setBounds(0, 0, 44, 64);
            icon.draw(canvas);
            canvas.restoreToCount(saved);
            canvas.drawBitmap(bitmap, left + 66, 375, null);
            paint.setTextSize(19);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(context.getColor(R.color.control_checked_text));
            canvas.drawText(labels[index], left + 88, 361, paint);
            previous = bitmap;
        }
        if (previous != null) previous.recycle();
        write(sheet, new File(directory, "quality-contact-sheet.png"));
        sheet.recycle();
    }

    @Test public void coverageTiersAddIntimateFeetAndFaceMasksWithoutCoveringHair() {
        Context context = themed();
        Bitmap low = render(context.getDrawable(R.drawable.ic_quality_low), 44, 64);
        Bitmap medium = render(context.getDrawable(R.drawable.ic_quality_medium), 44, 64);
        Bitmap high = render(context.getDrawable(R.drawable.ic_quality_high), 44, 64);
        int mask = context.getColor(R.color.control_checked_indicator);
        try {
            for (int x : new int[]{13, 30}) {
                assertEquals("Low already covers intimate areas", mask, low.getPixel(x, 38));
                assertEquals(mask, medium.getPixel(x, 38));
                assertEquals(mask, high.getPixel(x, 38));
            }
            for (int x : new int[]{9, 16, 27, 32}) {
                assertNotEquals("Low leaves feet visible", mask, low.getPixel(x, 50));
                assertEquals("Medium adds feet", mask, medium.getPixel(x, 50));
                assertEquals(mask, high.getPixel(x, 50));
            }
            for (int x : new int[]{13, 30}) {
                assertNotEquals(mask, medium.getPixel(x, 14));
                assertEquals("High uses face-only masks", mask, high.getPixel(x, 14));
                assertEquals("Hair remains unchanged", low.getPixel(x, 10), high.getPixel(x, 10));
            }
            assertEquals("Long hair remains visible beside the face", low.getPixel(26, 15), high.getPixel(26, 15));
            assertNotEquals(mask, medium.getPixel(8, 29));
            assertEquals("High retains small armpit coverage", mask, high.getPixel(8, 29));
        } finally {
            low.recycle(); medium.recycle(); high.recycle();
        }
    }

    private static Bitmap render(Drawable drawable, int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(new Canvas(bitmap));
        return bitmap;
    }

    private static void write(Bitmap bitmap, File path) throws IOException {
        try (FileOutputStream stream = new FileOutputStream(path)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
    }
}
