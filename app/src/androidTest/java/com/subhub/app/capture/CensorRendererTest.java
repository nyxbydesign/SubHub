package com.subhub.app.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.settings.CensorAppearance;
import com.subhub.app.settings.EffectPalette;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public final class CensorRendererTest {
    @Test public void gradientBorderUsesBothConfiguredEndpoints() {
        Context context = ApplicationProvider.getApplicationContext();
        Bitmap source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.WHITE);
        Bitmap blue = source.copy(Bitmap.Config.ARGB_8888, true);
        Bitmap green = source.copy(Bitmap.Config.ARGB_8888, true);
        try (CensorRenderer renderer = new CensorRenderer(context)) {
            for (int end : new int[]{Color.BLUE, Color.GREEN}) {
                CensorAppearance appearance = new CensorAppearance(CensorAppearance.Type.BOX,
                        100, 0f, true, false, CensorAppearance.BorderEffect.GRADIENT,
                        false, Color.MAGENTA, new EffectPalette(Color.BLACK, Color.WHITE, Color.WHITE),
                        Collections.emptyList(), false, 100, "rectangle", "SubHub", "Blocked", Color.RED, end);
                renderer.draw(end == Color.BLUE ? blue : green, source,
                        Collections.singletonList(detection(20, 20, 60, 60)), appearance);
            }
            int changed = 0;
            for (int y = 18; y <= 82; y++) for (int x = 18; x <= 82; x++) {
                if (blue.getPixel(x, y) != green.getPixel(x, y)) changed++;
            }
            assertTrue("Changing only the endpoint must recolor the rendered border", changed > 100);
            assertEquals(Color.BLACK, blue.getPixel(50, 50));
            assertEquals(Color.BLACK, green.getPixel(50, 50));
        } finally { source.recycle(); blue.recycle(); green.recycle(); }
    }

    @Test public void solidBoxUsesItsSelectedOpaqueColor() {
        Context context = ApplicationProvider.getApplicationContext();
        Bitmap source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.WHITE);
        Bitmap target = source.copy(Bitmap.Config.ARGB_8888, true);
        int purple = Color.rgb(96, 24, 160);
        CensorAppearance appearance = new CensorAppearance(
                CensorAppearance.Type.BOX, 1, 0f, false, false,
                CensorAppearance.BorderEffect.CLASSIC, false, Color.MAGENTA,
                new EffectPalette(purple, Color.WHITE, Color.WHITE), Collections.emptyList(),
                false, 100, "rectangle", "SubHub", "Blocked");
        try (CensorRenderer renderer = new CensorRenderer(context)) {
            renderer.draw(target, source, Collections.singletonList(
                    detection(20, 20, 60, 60)), appearance);
            assertEquals(purple, target.getPixel(50, 50));
            assertEquals(255, Color.alpha(target.getPixel(50, 50)));
        } finally {
            source.recycle();
            target.recycle();
        }
    }

    @Test
    public void solidCensorChangesOnlyThePaddedDetectionRegion() {
        Context context = ApplicationProvider.getApplicationContext();
        Bitmap source = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.WHITE);
        Bitmap target = source.copy(Bitmap.Config.ARGB_8888, true);
        Detection detection = new Detection(
                "FEMALE_BREAST_EXPOSED", "breasts", .95f,
                new BBox(40, 40, 40, 40), true, true);
        CensorAppearance appearance = new CensorAppearance(
                CensorAppearance.Type.BOX, 100, false, false, Color.MAGENTA);

        try (CensorRenderer renderer = new CensorRenderer(context)) {
            renderer.draw(target, source, Collections.singletonList(detection), appearance);
            assertNotEquals(Color.WHITE, target.getPixel(60, 60));
            assertEquals(Color.WHITE, target.getPixel(2, 2));
        } finally {
            source.recycle();
            target.recycle();
        }
    }

    @Test public void everyRecoveredEffectAndBorderRendersSyntheticCorpus() {
        Context context = ApplicationProvider.getApplicationContext();
        Bitmap source = gradient(240, 180);
        Detection detection = detection(70, 45, 100, 90);
        try (CensorRenderer renderer = new CensorRenderer(context)) {
            for (CensorAppearance.Type type : CensorAppearance.Type.values()) {
                Bitmap target = source.copy(Bitmap.Config.ARGB_8888, true);
                CensorAppearance appearance = appearance(type,
                        CensorAppearance.BorderEffect.CLASSIC, false, "rectangle");
                renderer.draw(target, source, Collections.singletonList(detection), appearance);
                assertTrue(type + " should alter the detection region",
                        changedPixels(source, target, 50, 25, 190, 155) > 100);
                assertEquals(type + " should leave a distant corner unchanged",
                        source.getPixel(3, 3), target.getPixel(3, 3));
                target.recycle();
            }

            for (CensorAppearance.BorderEffect effect : CensorAppearance.BorderEffect.values()) {
                Bitmap target = source.copy(Bitmap.Config.ARGB_8888, true);
                renderer.draw(target, source, Collections.singletonList(detection),
                        appearance(CensorAppearance.Type.BOX, effect, false, "rectangle"));
                assertTrue(effect + " border should render",
                        changedPixels(source, target, 50, 25, 190, 155) > 100);
                target.recycle();
            }
        } finally {
            source.recycle();
        }
    }

    @Test public void reverseModePreservesDetectedCutoutForEveryShape() {
        Context context = ApplicationProvider.getApplicationContext();
        Bitmap source = gradient(240, 180);
        Detection detection = detection(70, 45, 100, 90);
        try (CensorRenderer renderer = new CensorRenderer(context)) {
            for (String shape : Arrays.asList("rectangle", "rounded", "ellipse")) {
                Bitmap target = source.copy(Bitmap.Config.ARGB_8888, true);
                renderer.draw(target, source, Collections.singletonList(detection),
                        appearance(CensorAppearance.Type.PIXELATE,
                                CensorAppearance.BorderEffect.RAINBOW, true, shape));
                assertEquals(shape + " cutout should preserve its center",
                        source.getPixel(120, 90), target.getPixel(120, 90));
                assertNotEquals(shape + " reverse field should censor outside",
                        source.getPixel(20, 20), target.getPixel(20, 20));
                target.recycle();
            }
        } finally {
            source.recycle();
        }
    }

    @Test public void rainbowExportBorderIsConfinedToTheDetectionRectangle() {
        Context context = ApplicationProvider.getApplicationContext();
        Bitmap source = gradient(240, 180);
        Bitmap target = Bitmap.createBitmap(240, 180, Bitmap.Config.ARGB_8888);
        target.eraseColor(Color.TRANSPARENT);
        CensorAppearance appearance = new CensorAppearance(
                CensorAppearance.Type.BOX, 1, 0f, true, true,
                CensorAppearance.BorderEffect.RAINBOW, false, Color.MAGENTA,
                Collections.emptyList(), false, 100, "rectangle", "SubHub", "Blocked");
        try (CensorRenderer renderer = new CensorRenderer(context)) {
            renderer.draw(target, source, Collections.singletonList(
                    detection(20, 70, 200, 20)), appearance);
            assertEquals(Color.BLACK, target.getPixel(120, 80));
            assertEquals(Color.TRANSPARENT, target.getPixel(120, 30));
            assertEquals(Color.TRANSPARENT, target.getPixel(120, 130));
            assertEquals(Color.TRANSPARENT, target.getPixel(5, 80));
            assertEquals(Color.TRANSPARENT, target.getPixel(235, 80));
        } finally {
            source.recycle();
            target.recycle();
        }
    }

    @Test public void customImageStoreImportsTogglesRendersAndDeletes() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        CustomImageManager manager = new CustomImageManager(context);
        for (CustomImageManager.Entry entry : manager.listEntries()) manager.delete(entry.getId());
        File input = new File(context.getCacheDir(), "synthetic-custom-censor.png");
        Bitmap custom = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        custom.eraseColor(Color.rgb(125, 0, 220));
        try (FileOutputStream output = new FileOutputStream(input)) {
            assertTrue(custom.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        custom.recycle();

        try {
            assertEquals(1, manager.addImages(Collections.singletonList(Uri.fromFile(input))));
            List<CustomImageManager.Entry> entries = manager.listEntries();
            assertEquals(1, entries.size());
            String id = entries.get(0).getId();
            Bitmap thumbnail = manager.thumbnail(id, 128);
            assertNotNull(thumbnail);
            thumbnail.recycle();

            CountDownLatch prepared = new CountDownLatch(1);
            try (CustomImagePool pool = new CustomImagePool(context)) {
                pool.reloadAsync(prepared::countDown);
                assertTrue("custom censor preparation should finish off-thread",
                        prepared.await(5, TimeUnit.SECONDS));
                CustomImagePool.PreparedImage image = pool.imageFor(17);
                assertNotNull(image);
                Rect firstCrop = image.cropFor(16f / 9f);
                assertSame("the hot path should reuse precomputed crop geometry",
                        firstCrop, image.cropFor(16f / 9f));
                assertTrue(firstCrop.width() > 0 && firstCrop.height() > 0);
                assertSame("a live track keeps its image assignment", image, pool.imageFor(17));
            }

            Bitmap source = gradient(240, 180);
            Bitmap target = source.copy(Bitmap.Config.ARGB_8888, true);
            try (CensorRenderer renderer = new CensorRenderer(context)) {
                renderer.draw(target, source, Collections.singletonList(
                        detection(70, 45, 100, 90)), appearance(CensorAppearance.Type.CUSTOM,
                        CensorAppearance.BorderEffect.CLASSIC, false, "rectangle"));
                assertTrue(changedPixels(source, target, 70, 45, 170, 135) > 100);
            }
            source.recycle();
            target.recycle();

            manager.setEnabled(id, false);
            assertFalse(manager.listEntries().get(0).isEnabled());
            assertTrue(manager.loadEnabledBitmaps(128).isEmpty());
            manager.delete(id);
            assertTrue(manager.listEntries().isEmpty());
        } finally {
            for (CustomImageManager.Entry entry : manager.listEntries()) manager.delete(entry.getId());
            if (input.exists()) assertTrue(input.delete());
        }
    }

    private static Detection detection(int x, int y, int width, int height) {
        return new Detection("FEMALE_BREAST_EXPOSED", "breasts", .95f,
                new BBox(x, y, width, height), true, true);
    }

    private static CensorAppearance appearance(
            CensorAppearance.Type type,
            CensorAppearance.BorderEffect border,
            boolean reverse,
            String shape) {
        return new CensorAppearance(type, 82, .15f, true, true, border, true,
                Color.MAGENTA, Arrays.asList("BLOCKED", "DENIED"), reverse, 88, shape,
                "SubHub", "Access denied");
    }

    private static Bitmap gradient(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        for (int y = 0; y < height; y++) {
            paint.setColor(Color.rgb(y * 255 / height, 40, 255 - y * 255 / height));
            canvas.drawRect(0, y, width, y + 1, paint);
        }
        for (int x = 0; x < width; x += 12) {
            paint.setColor((x / 12 & 1) == 0 ? Color.WHITE : Color.BLACK);
            paint.setAlpha(90);
            canvas.drawRect(x, 0, x + 6, height, paint);
        }
        return bitmap;
    }

    private static int changedPixels(
            Bitmap source, Bitmap target, int left, int top, int right, int bottom) {
        int changed = 0;
        for (int y = top; y < bottom; y += 2) {
            for (int x = left; x < right; x += 2) {
                if (source.getPixel(x, y) != target.getPixel(x, y)) changed++;
            }
        }
        return changed;
    }
}
