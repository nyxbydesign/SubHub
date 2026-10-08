package com.subhub.app.settings;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.AppModePolicy;
import com.subhub.app.security.ControllerPinManager;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Native geometry and assignment contracts; fixture preferences are restored, no screenshots. */
@RunWith(AndroidJUnit4.class)
public final class AppAssignmentRowAndroidTest {
    @Test public void nativeCheckboxGlyphIsCenteredInHighlightForLtrAndRtl() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (int direction : new int[]{View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
                AppAssignmentRow row = row(1);
                row.setLayoutDirection(direction);
                int width = dp(row.getContext(), 320);
                row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                row.layout(0, 0, width, row.getMeasuredHeight());
                for (int index = 0; index < 3; index++) {
                    CheckBox check = row.choice(index);
                    check.setChecked(true);
                    check.jumpDrawablesToCurrentState();
                    Bitmap pixels = Bitmap.createBitmap(check.getWidth(), check.getHeight(), Bitmap.Config.ARGB_8888);
                    check.draw(new Canvas(pixels));
                    int tint = check.getContext().getColor(R.color.control_checked_indicator);
                    int minX = pixels.getWidth(), maxX = -1, minY = pixels.getHeight(), maxY = -1;
                    for (int y = 0; y < pixels.getHeight(); y++) for (int x = 0; x < pixels.getWidth(); x++) {
                        int color = pixels.getPixel(x, y);
                        if (Color.alpha(color) > 240 && Math.abs(Color.red(color) - Color.red(tint)) < 4
                                && Math.abs(Color.green(color) - Color.green(tint)) < 4
                                && Math.abs(Color.blue(color) - Color.blue(tint)) < 4) {
                            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                        }
                    }
                    assertTrue("Native checked glyph must render", maxX >= minX);
                    assertEquals("Glyph and highlight horizontal centers", (pixels.getWidth() - 1) / 2f,
                            (minX + maxX) / 2f, 1f);
                    assertEquals("Glyph and highlight vertical centers", (pixels.getHeight() - 1) / 2f,
                            (minY + maxY) / 2f, 1f);
                    pixels.recycle();
                }
            }
        });
    }
    @Test public void appAssignmentsPersistIndependentlyOnReopenAndDenySubModeEditing() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        AppModeManager manager = new AppModeManager(context);
        Set<String> censorBefore = manager.getSelectedPackages();
        Set<String> limitBefore = manager.getTimerPackages();
        Set<String> subliminalBefore = manager.getSubliminalPackages();
        boolean armed = manager.isArmed();
        boolean wasDom = ControllerPinManager.isDomModeActive();
        AppModePolicy.Mode mode = manager.getMode();
        ControllerPinManager.enterDomMode();
        AtomicReference<String> selectedPackage = new AtomicReference<>();
        try {
            try (ActivityScenario<GlobalSettingsActivity> scenario = ActivityScenario.launch(GlobalSettingsActivity.class)) {
                scenario.onActivity(activity -> activity.findViewById(R.id.button_toggle_apps).performClick());
                awaitRows(scenario);
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scenario.onActivity(activity -> {
                    LinearLayout list = activity.findViewById(R.id.app_list);
                    AppAssignmentRow row = (AppAssignmentRow) list.getChildAt(1);
                    assertTrue("Header alignment requires measured visible rows", row.getWidth() > 0);
                    LinearLayout header = (LinearLayout) list.getChildAt(0);
                    for (int index = 0; index < 3; index++) {
                        Rect bounds = new Rect(0, 0, row.choice(index).getWidth(), row.choice(index).getHeight());
                        list.offsetDescendantRectToMyCoords(row.choice(index), bounds);
                        View title = header.getChildAt(index + 1);
                        float titleCenter = header.getLeft() + title.getLeft() + title.getWidth() / 2f;
                        assertEquals("Column label must align with the centered checkbox/highlight",
                                titleCenter, bounds.exactCenterX(), 1f);
                    }
                    String tag = row.choice(0).getTag().toString();
                    String packageName = tag.substring("assignment:".length(), tag.lastIndexOf(':'));
                    selectedPackage.set(packageName);
                    row.choice(0).setChecked(true);
                    row.choice(1).setChecked(false);
                    row.choice(2).setChecked(true);
                });
                assertTrue(manager.getSelectedPackages().contains(selectedPackage.get()));
                assertFalse(manager.getTimerPackages().contains(selectedPackage.get()));
                assertTrue(manager.getSubliminalPackages().contains(selectedPackage.get()));
                assertEquals(armed, manager.isArmed());
                assertEquals(mode, manager.getMode());
                scenario.recreate();
                awaitRows(scenario);
                scenario.onActivity(activity -> {
                    CheckBox censor = activity.findViewById(R.id.app_list)
                            .findViewWithTag("assignment:" + selectedPackage.get() + ":0");
                    CheckBox limit = activity.findViewById(R.id.app_list)
                            .findViewWithTag("assignment:" + selectedPackage.get() + ":1");
                    CheckBox subliminal = activity.findViewById(R.id.app_list)
                            .findViewWithTag("assignment:" + selectedPackage.get() + ":2");
                    assertTrue(censor.isChecked());
                    assertFalse(limit.isChecked());
                    assertTrue(subliminal.isChecked());
                });
                ControllerPinManager.enterSubMode();
                scenario.recreate();
                awaitRows(scenario);
                scenario.onActivity(activity -> {
                    CheckBox censor = activity.findViewById(R.id.app_list)
                            .findViewWithTag("assignment:" + selectedPackage.get() + ":0");
                    assertFalse(censor.isEnabled());
                });
                assertTrue(manager.getSelectedPackages().contains(selectedPackage.get()));
            }
        } finally {
            manager.saveAppSelections(censorBefore, limitBefore, subliminalBefore);
            manager.save(armed, mode, censorBefore);
            if (wasDom) ControllerPinManager.enterDomMode();
            else ControllerPinManager.enterSubMode();
        }
    }

    private static void awaitRows(ActivityScenario<GlobalSettingsActivity> scenario) throws Exception {
        AtomicBoolean loaded = new AtomicBoolean();
        long deadline = android.os.SystemClock.uptimeMillis() + 5000;
        do {
            scenario.onActivity(activity -> loaded.set(
                    ((LinearLayout) activity.findViewById(R.id.app_list)).getChildCount() > 1));
            if (loaded.get()) return;
            Thread.sleep(50);
        } while (android.os.SystemClock.uptimeMillis() < deadline);
        fail("Launcher assignments did not load within the bounded fixture deadline");
    }

    @Test public void narrowLargeTextAndRtlKeepIndependentTargetsInsideRow() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (int screenWidth : new int[]{320, 360, 411}) {
                for (float font : new float[]{1f, 1.3f, 2f}) {
                    for (int direction : new int[]{View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
                        AppAssignmentRow row = row(font);
                        row.setLayoutDirection(direction);
                        int width = dp(row.getContext(), screenWidth - 40);
                        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                        row.layout(0, 0, width, row.getMeasuredHeight());
                        Rect previous = null;
                        for (int index = 0; index < 3; index++) {
                            CheckBox check = row.choice(index);
                            assertTrue(check.getWidth() >= dp(row.getContext(), 48));
                            assertTrue(check.getHeight() >= dp(row.getContext(), 48));
                            assertTrue(check.isFocusable());
                            Rect bounds = new Rect(0, 0, check.getWidth(), check.getHeight());
                            row.offsetDescendantRectToMyCoords(check, bounds);
                            assertTrue(bounds.left >= 0 && bounds.right <= width);
                            assertTrue(bounds.top >= 0 && bounds.bottom <= row.getHeight());
                            if (previous != null) assertFalse(Rect.intersects(previous, bounds));
                            previous = bounds;
                            assertTrue(check.getContentDescription().toString().startsWith("A very long"));
                        }
                        assertEquals(font > 1.4f, row.isStacked());
                        if (font == 1f) {
                            // Android rounds each padding inset separately at fractional densities.
                            assertEquals("Compact rows contain a 48dp target plus their actual padding",
                                    dp(row.getContext(), 48) + row.getPaddingTop() + row.getPaddingBottom(),
                                    row.getHeight());
                        }
                        android.util.Log.i("AppAssignmentGeometry", "screen=" + screenWidth
                                + " font=" + font + " direction=" + direction
                                + " rowHeightDp=" + row.getHeight()
                                / row.getResources().getDisplayMetrics().density);
                    }
                }
            }
        });
    }

    @Test public void independentChecksExposeAppAndModuleAndDoNotChangeOtherChoices() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            AppAssignmentRow row = row(1);
            assertTrue(row.choice(0).isChecked());
            assertFalse(row.choice(1).isChecked());
            assertTrue(row.choice(2).isChecked());
            row.choice(1).performClick();
            assertTrue(row.choice(0).isChecked());
            assertTrue(row.choice(1).isChecked());
            assertTrue(row.choice(2).isChecked());
            assertTrue(row.choice(0).getContentDescription().toString().endsWith("Censor"));
            assertTrue(row.choice(1).getContentDescription().toString().endsWith("Limit"));
            assertTrue(row.choice(2).getContentDescription().toString().endsWith("Subliminal"));
            assertEquals("assignment:example.app:1", row.choice(1).getTag());
            assertNotSame(row.choice(0).getBackground(), row.choice(1).getBackground());
        });
    }

    private static AppAssignmentRow row(float font) {
        Context base = ApplicationProvider.getApplicationContext();
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.fontScale = font;
        Context themed = new ContextThemeWrapper(base.createConfigurationContext(config), R.style.Theme_SubHub);
        return new AppAssignmentRow(themed, "A very long application name", "example.app",
                themed.getDrawable(R.drawable.ic_launcher_foreground), new boolean[]{true, false, true});
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
