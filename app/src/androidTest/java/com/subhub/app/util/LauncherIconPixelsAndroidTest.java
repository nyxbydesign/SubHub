package com.subhub.app.util;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class LauncherIconPixelsAndroidTest {
    private static final int[] SIZES = {48, 96, 192};

    @Test
    public void recordsLauncherPixelsAndMask() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Resources resources = target.getResources();
        String packageName = target.getPackageName();
        int foreground = resources.getIdentifier("ic_launcher_foreground", "drawable", packageName);
        assertTrue("Launcher foreground resource is missing", foreground != 0);

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded = BitmapFactory.decodeResource(resources, foreground, options);
        assertNotNull(decoded);
        try {
            pixels("foreground.decoded", decoded, "");
        } finally {
            decoded.recycle();
        }

        render("foreground.resourceIcon", Icon.createWithResource(target, foreground).loadDrawable(target));

        PackageManager manager = target.getPackageManager();
        Drawable applicationIcon = manager.getApplicationIcon(packageName);
        render("application", applicationIcon);
        assertTrue("Application icon is not adaptive", applicationIcon instanceof AdaptiveIconDrawable);

        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName);
        for (String alias : new String[]{"DefaultLauncher", "DiscreetLauncher"}) {
            ComponentName component = new ComponentName(packageName, packageName + "." + alias);
            ActivityInfo info = manager.getActivityInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS);
            record(alias + ".state", "manifestEnabled=" + info.enabled
                    + "|enabledSetting=" + manager.getComponentEnabledSetting(component));
            ResolveInfo resolved = null;
            for (ResolveInfo item : manager.queryIntentActivities(launcher, PackageManager.MATCH_DISABLED_COMPONENTS)) {
                if (component.getClassName().equals(item.activityInfo.name)) {
                    resolved = item;
                    break;
                }
            }
            assertNotNull("Missing MAIN/LAUNCHER resolver for " + alias, resolved);
            Drawable icon = resolved.loadIcon(manager);
            render(alias, icon);
            if ("DefaultLauncher".equals(alias)) {
                assertTrue("Default launcher icon is not adaptive", icon instanceof AdaptiveIconDrawable);
            }
        }
    }

    private static void render(String surface, Drawable icon) throws NoSuchAlgorithmException {
        assertNotNull(surface, icon);
        record(surface + ".type", icon.getClass().getSimpleName()
                + "|intrinsic=" + icon.getIntrinsicWidth() + "x" + icon.getIntrinsicHeight());
        Rect original = icon.copyBounds();
        try {
            for (int size : SIZES) {
                icon.setBounds(0, 0, size, size);
                Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                try {
                    icon.draw(new Canvas(output));
                    pixels(surface, output, "|bounds=" + rect(icon.getBounds()));
                } finally {
                    output.recycle();
                }
                if (icon instanceof AdaptiveIconDrawable) {
                    adaptive(surface, (AdaptiveIconDrawable) icon, size);
                }
            }
        } finally {
            icon.setBounds(original);
        }
    }

    private static void adaptive(String surface, AdaptiveIconDrawable icon, int size)
            throws NoSuchAlgorithmException {
        Path mask = icon.getIconMask();
        RectF extent = new RectF();
        mask.computeBounds(extent, true);
        Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        try {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.WHITE);
            new Canvas(output).drawPath(mask, paint);
            pixels(surface + ".mask", output, "|pathBounds="
                    + extent.left + "," + extent.top + "," + extent.right + "," + extent.bottom
                    + "|foregroundBounds=" + rect(icon.getForeground().getBounds())
                    + "|backgroundBounds=" + rect(icon.getBackground().getBounds()));
        } finally {
            output.recycle();
        }

        if (Build.VERSION.SDK_INT >= 33) {
            Drawable monochrome = icon.getMonochrome();
            if (monochrome == null) {
                record(surface + ".monochrome", "size=" + size + "x" + size + "|present=false");
            } else {
                Bitmap themed = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                try {
                    Canvas canvas = new Canvas(themed);
                    canvas.clipPath(mask);
                    monochrome.draw(canvas);
                    pixels(surface + ".monochrome", themed,
                            "|present=true|bounds=" + rect(monochrome.getBounds()));
                } finally {
                    themed.recycle();
                }
            }
        }
    }

    private static String rect(Rect bounds) {
        return bounds.left + "," + bounds.top + "," + bounds.right + "," + bounds.bottom;
    }

    private static void pixels(String surface, Bitmap bitmap, String extra) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] row = new int[width];
        ByteBuffer bytes = ByteBuffer.allocate(width * Integer.BYTES);
        for (int y = 0; y < height; y++) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1);
            bytes.clear();
            for (int pixel : row) {
                bytes.putInt(pixel);
            }
            digest.update(bytes.array());
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        record(surface, "size=" + width + "x" + height + "|density=" + bitmap.getDensity()
                + "|argbSha256=" + hex + extra);
    }

    private static void record(String surface, String value) {
        Bundle line = new Bundle();
        line.putString("launcher_icon_pixels", surface + "|" + value);
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.sendStatus(42, line);
    }
}
