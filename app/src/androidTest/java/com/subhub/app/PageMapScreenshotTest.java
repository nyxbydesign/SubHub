package com.subhub.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import com.subhub.app.appmode.AppModeActivity;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.AppTimerManager;
import com.subhub.app.atmosphere.AtmosphereActivity;
import com.subhub.app.capture.CustomImagesActivity;
import com.subhub.app.capture.ExportActivity;
import com.subhub.app.commitment.CommitmentActivity;
import com.subhub.app.diagnostics.DiagnosticsActivity;
import com.subhub.app.help.HelpActivity;
import com.subhub.app.penance.PenanceActivity;
import com.subhub.app.popup.PopupStormActivity;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.FeatureModuleManager;
import com.subhub.app.settings.GlobalSettingsActivity;
import com.subhub.app.settings.SettingsActivity;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.stats.AchievementsActivity;
import com.subhub.app.stats.StatsActivity;
import com.subhub.app.studio.StudioActivity;
import com.subhub.app.subliminal.SubliminalSettingsActivity;
import com.subhub.app.update.UpdatesActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;

/** Captures the real running screens used by the design inventory. */
@RunWith(AndroidJUnit4.class)
public final class PageMapScreenshotTest {
    @Test public void capturePrimaryFoundationPages() throws Exception {
        prepareFixture(false);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        new AppModeManager(context).setArmed(false);
        captureMain("foundation-01-dom-home", false);
        capture("foundation-02-censor", SettingsActivity.class);
        capture("foundation-03-limits", AppModeActivity.class);
        capture("foundation-04-wallet", PenanceActivity.class);
        capture("foundation-05-atmosphere", AtmosphereActivity.class);
        capture("foundation-06-settings", GlobalSettingsActivity.class);
        captureMain("foundation-07-sub-home", true);
        captureInSpace("foundation-08-sub-settings", GlobalSettingsActivity.class, true);
    }

    @Test public void captureCorePages() throws Exception {
        prepareFixture(true);
        captureMain("00-sub-home", true);
        captureInSpace("00b-sub-settings", GlobalSettingsActivity.class, true);
        captureInSpace("00c-sub-arrangements", StudioActivity.class, true);

        ControllerPinManager.enterDomMode();
        captureMain("01-dom-home", false);
        capture("02-limits", AppModeActivity.class);
        capture("03-wallet", PenanceActivity.class);
        captureScrolled("03b-wallet-rules-and-safety", PenanceActivity.class, 2);
        captureScrolled("03c-wallet-checkout-and-history", PenanceActivity.class, 5);
        capture("04-global-settings", GlobalSettingsActivity.class);
    }

    @Test public void captureSupportingPages() throws Exception {
        prepareFixture(false);
        capture("05-censor-settings", SettingsActivity.class);
        captureTarget("05a-settings-appearance", SettingsActivity.class,
                R.id.censor_appearance, R.id.button_appearance_details);
        captureTarget("05b-settings-detection-categories", SettingsActivity.class,
                R.id.censor_text_section, 0);
        captureTarget("05c-settings-phrases-and-tools", SettingsActivity.class,
                R.id.censor_phrases_section, R.id.button_appearance_details);
        captureTarget("04b-app-assignments", GlobalSettingsActivity.class,
                R.id.app_list, R.id.button_toggle_apps);
        capture("07-censor-photos", ExportActivity.class);
        capture("08-help-safety", HelpActivity.class);
        capture("09-statistics", StatsActivity.class);
        capture("10-achievements", AchievementsActivity.class);
        capture("11-custom-images", CustomImagesActivity.class);
        capture("14-atmosphere", AtmosphereActivity.class);
        capture("15-whispers", SubliminalSettingsActivity.class);
        capture("16-popup-storm", PopupStormActivity.class);
        capture("17-studio-library", StudioActivity.class);
        captureAfterClick("17b-studio-drafts", StudioActivity.class, R.id.tab_drafts);
        captureAfterClick("17c-studio-create", StudioActivity.class, R.id.tab_create);
        capture("18-updates", UpdatesActivity.class);
        capture("19-diagnostics", DiagnosticsActivity.class);
        captureActiveServiceLock("20-service-lock");
    }

    @Test public void captureSharedColorWheel() throws Exception {
        prepareFixture(false);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getExternalFilesDir(null), "page-map");
        if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("No screenshot directory");
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(SettingsActivity.class)) {
            scenario.onActivity(activity -> com.subhub.app.util.ColorPickerDialog.show(activity,
                    activity.getString(R.string.gradient_start), android.graphics.Color.MAGENTA, color -> { }));
            UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            device.waitForIdle(750L);
            settleDisplay();
            if (!device.takeScreenshot(new File(directory, "21-color-wheel.png"))) {
                throw new IllegalStateException("Could not capture color picker");
            }
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(android.R.id.button2))
                    .perform(androidx.test.espresso.action.ViewActions.click());
        }
    }

    @Test public void captureFirstClassPackWizard() throws Exception {
        prepareFixture(false);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getExternalFilesDir(null), "page-map");
        if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("No screenshot directory");
        try (ActivityScenario<StudioActivity> scenario = ActivityScenario.launch(StudioActivity.class)) {
            scenario.onActivity(activity -> activity.findViewById(R.id.button_blank).performClick());
            String[] names = {"22-wizard-details", "23-wizard-features", "24-wizard-images", "25-wizard-review"};
            for (int step = 0; step < names.length; step++) {
                final int selected = step;
                scenario.onActivity(activity -> activity.getWindow().getDecorView()
                        .findViewWithTag("pack_step:" + selected).performClick());
                int[] panels = {R.id.details_step, R.id.features_step, R.id.images_step, R.id.review_step};
                scenario.onActivity(activity -> {
                    if (!activity.findViewById(panels[selected]).isShown()) {
                        throw new AssertionError("Wrong wizard panel: " + selected);
                    }
                });
                UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
                device.waitForIdle(750L);
                settleDisplay();
                if (!device.takeScreenshot(new File(directory, names[step] + ".png"))) {
                    throw new IllegalStateException("Could not capture wizard step " + step);
                }
            }
        }
    }

    @Test public void captureNativePackSectionEditor() throws Exception {
        prepareFixture(false);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getExternalFilesDir(null), "page-map");
        if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("No screenshot directory");
        try (ActivityScenario<StudioActivity> scenario = ActivityScenario.launch(StudioActivity.class)) {
            scenario.onActivity(activity -> {
                try {
                    java.lang.reflect.Method show = Class.forName("com.subhub.app.studio.PackSectionEditor")
                            .getDeclaredMethod("show", androidx.appcompat.app.AppCompatActivity.class,
                                    String.class, String.class, org.json.JSONObject.class, java.util.function.Consumer.class);
                    show.setAccessible(true);
                    show.invoke(null, activity, "censor", "Censor",
                            com.subhub.app.pack.PackSettingCatalog.defaults("censor"),
                            (java.util.function.Consumer<org.json.JSONObject>) values -> { });
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
            UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            device.waitForIdle(750L);
            androidx.test.espresso.Espresso.onView(
                    androidx.test.espresso.matcher.ViewMatchers.withText(
                            context.getString(R.string.pack_editor_title, "Censor")))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(
                            androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
            settleDisplay();
            if (!device.takeScreenshot(new File(directory, "26-pack-section-editor.png"))) {
                throw new IllegalStateException("Could not capture section editor");
            }
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(android.R.id.button2))
                    .perform(androidx.test.espresso.action.ViewActions.click());
        }
    }

    private static void prepareFixture(boolean subSpace) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean("has_seen_onboarding", true)
                .remove("commitment_started_at").remove("commitment_ends_at")
                .remove("commitment_duration").commit();
        new FeatureModuleManager(context).save(true, true, true);
        prepareLimitsFixture(context);
        if (!ControllerPinManager.isConfigured(context)) {
            ControllerPinManager.setPin(context, "2468");
        }
        String service = context.getPackageName()
                + "/com.subhub.app.service.ScreenshotAccessibilityService";
        runShellCommand("settings put secure enabled_accessibility_services " + service);
        runShellCommand("settings put secure accessibility_enabled 1");
        runShellCommand("pm grant " + context.getPackageName()
                + " android.permission.POST_NOTIFICATIONS");
        runShellCommand("appops set " + context.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
        Thread.sleep(500L);
        if (subSpace) ControllerPinManager.enterSubMode();
        else ControllerPinManager.enterDomMode();
    }

    /** UI idleness alone does not guarantee a newly selected panel reached the compositor. */
    private static void settleDisplay() throws InterruptedException {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Thread.sleep(450L);
    }

    private static void captureTarget(String name, Class<? extends Activity> activityClass,
            int targetId, int expandId) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getExternalFilesDir(null), "page-map");
        if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("No screenshot directory");
        try (ActivityScenario<? extends Activity> scenario = ActivityScenario.launch(activityClass)) {
            scenario.onActivity(activity -> {
                if (activityClass == GlobalSettingsActivity.class) {}
                if (expandId != 0) activity.findViewById(expandId).performClick();
            });
            if (targetId == R.id.app_list) {
                java.util.concurrent.atomic.AtomicBoolean loaded = new java.util.concurrent.atomic.AtomicBoolean();
                long deadline = android.os.SystemClock.uptimeMillis() + 5000L;
                do {
                    scenario.onActivity(activity -> loaded.set(
                            ((ViewGroup) activity.findViewById(R.id.app_list)).getChildCount() > 1));
                    if (loaded.get()) break;
                    Thread.sleep(50L);
                } while (android.os.SystemClock.uptimeMillis() < deadline);
                if (!loaded.get()) throw new AssertionError("App assignments did not finish loading");
            }
            settleDisplay();
            scenario.onActivity(activity -> {
                View target = activity.findViewById(targetId);
                if (target == null || !target.isShown()) throw new AssertionError("Capture target is hidden: " + name);
                android.view.ViewParent parent = target.getParent();
                while (parent != null && !(parent instanceof ScrollView)) parent = parent.getParent();
                if (!(parent instanceof ScrollView)) throw new AssertionError("No scroll owner: " + name);
                ScrollView scroll = (ScrollView) parent;
                Rect bounds = new Rect(0, 0, target.getWidth(), target.getHeight());
                ((ViewGroup) scroll.getChildAt(0)).offsetDescendantRectToMyCoords(target, bounds);
                scroll.scrollTo(0, Math.max(0, bounds.top - 24));
            });
            settleDisplay();
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(targetId))
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(
                            androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
            if (!UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                    .takeScreenshot(new File(directory, name + ".png"))) {
                throw new IllegalStateException("Could not capture " + name);
            }
        }
    }

    private static void runShellCommand(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand(command);
             FileInputStream output = new FileInputStream(descriptor.getFileDescriptor())) {
            // Drain to EOF: closing the descriptor alone can race the command on an emulator.
            byte[] buffer = new byte[256];
            while (output.read(buffer) != -1) {
                // Shell setup commands normally have no output.
            }
        }
    }

    /** Documentation must demonstrate real per-app settings, never an empty Limits list. */
    private static void prepareLimitsFixture(Context context) {
        java.util.Map<String, Integer> examples = new java.util.LinkedHashMap<>();
        String[] candidates = {"com.android.chrome", "com.instagram.android",
                "com.twitter.android", "org.chromium.chrome.stable", "com.google.android.youtube"};
        for (String packageName : candidates) {
            if (context.getPackageManager().getLaunchIntentForPackage(packageName) == null) continue;
            examples.put(packageName, examples.isEmpty() ? 20 : 10);
            if (examples.size() == 2) break;
        }
        if (examples.isEmpty()) throw new AssertionError("Install one or two demo apps before documentation capture");
        AppModeManager mode = new AppModeManager(context);
        mode.saveIncludedPackages(
                com.subhub.app.appmode.LegacyAppSelection.merge(
                        false,
                        mode.getIncludedPackages(), examples.keySet(), mode.getIncludedPackages(),
                        java.util.Set.of()));
        mode.save(false, mode.getIncludedPackages());
        AppTimerManager timers = new AppTimerManager(context);
        timers.saveSettings(true, 30, true, 45);
        timers.saveAllowances(examples.keySet(), examples);
    }

    private static void captureAfterClick(String name, Class<? extends Activity> activityClass,
            int viewId) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getExternalFilesDir(null), "page-map");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Could not create screenshot directory");
        }
        try (ActivityScenario<? extends Activity> scenario = ActivityScenario.launch(activityClass)) {
            scenario.onActivity(activity -> activity.findViewById(viewId).performClick());
            UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            device.waitForIdle(750L);
            Thread.sleep(350L);
            if (!device.takeScreenshot(new File(directory, name + ".png"))) {
                throw new IllegalStateException("Could not capture " + name);
            }
        }
    }

    private static void capture(String name, Class<? extends Activity> activityClass)
            throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getExternalFilesDir(null), "page-map");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Could not create screenshot directory");
        }

        try (ActivityScenario<? extends Activity> scenario = ActivityScenario.launch(activityClass)) {
            UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            device.waitForIdle(750L);
            Thread.sleep(350L);
            if (activityClass == AppModeActivity.class) {
                scenario.onActivity(activity -> {
                    ViewGroup rows = activity.findViewById(R.id.per_app_allowances_list);
                    int expected = new AppModeManager(activity).getIncludedPackages().size();
                    if (expected < 1 || expected > 2) {
                        throw new AssertionError(
                                        "Limits screenshot must show one or two selected demo"
                                            + " apps");
                    }
                    java.util.Set<String> values = new java.util.LinkedHashSet<>();
                    for (String packageName :
                                    new AppModeManager(activity).getIncludedPackages()) {
                                android.widget.EditText allowance =
                                        rows.findViewWithTag("limit:" + packageName);
                        if (allowance == null || !allowance.isShown()) throw new AssertionError("Custom allowance is not visible");
                        values.add(allowance.getText().toString());
                    }
                    if (!values.contains("20") || (expected == 2 && !values.contains("10"))) {
                        throw new AssertionError("Custom demo allowances were not bound");
                    }
                });
            }
            if (!device.takeScreenshot(new File(directory, name + ".png"))) {
                throw new IllegalStateException("Could not capture " + name);
            }
        }
    }

    private static void captureActiveServiceLock(String name) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        long now = System.currentTimeMillis();
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong("commitment_started_at", now)
                .putLong("commitment_ends_at", now + 3_600_000L)
                .putLong("commitment_duration", 3_600_000L)
                .commit();
        capture(name, CommitmentActivity.class);
    }

    private static void captureMain(String name, boolean subSpace) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getExternalFilesDir(null), "page-map");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Could not create screenshot directory");
        }
        Intent intent = new Intent(context, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .putExtra(MainActivity.EXTRA_SUPPRESS_PERMISSION_READINESS, true);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> applyControllerSpace(activity, subSpace));
            UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            device.waitForIdle(750L);
            Thread.sleep(350L);
            scenario.onActivity(activity -> scrollToTop(activity.findViewById(android.R.id.content)));
            device.waitForIdle(500L);
            if (!device.takeScreenshot(new File(directory, name + ".png"))) {
                throw new IllegalStateException("Could not capture " + name);
            }
        }
    }

    private static void scrollToTop(View view) {
        if (view instanceof ScrollView) {
            ((ScrollView) view).scrollTo(0, 0);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                scrollToTop(group.getChildAt(index));
            }
        }
    }

    private static void captureInSpace(String name, Class<? extends Activity> activityClass,
            boolean subSpace) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getExternalFilesDir(null), "page-map");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Could not create screenshot directory");
        }
        try (ActivityScenario<? extends Activity> scenario = ActivityScenario.launch(activityClass)) {
            scenario.onActivity(activity -> applyControllerSpace(activity, subSpace));
            UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            device.waitForIdle(750L);
            Thread.sleep(350L);
            if (!device.takeScreenshot(new File(directory, name + ".png"))) {
                throw new IllegalStateException("Could not capture " + name);
            }
        }
    }

    private static void applyControllerSpace(Activity activity, boolean subSpace) {
        if (!subSpace) {
            ControllerPinManager.enterDomMode();
            return;
        }
        View editLock = activity.findViewById(R.id.button_edit_lock);
        if (editLock instanceof TextView
                && "Lock".contentEquals(((TextView) editLock).getText())) {
            editLock.performClick();
        } else {
            ControllerPinManager.enterSubMode();
        }
    }

    private static void captureScrolled(
            String name, Class<? extends Activity> activityClass, int swipes) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getExternalFilesDir(null), "page-map");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Could not create screenshot directory");
        }
        try (ActivityScenario<? extends Activity> scenario = ActivityScenario.launch(activityClass)) {
            UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            device.waitForIdle(750L);
            int x = device.getDisplayWidth() / 2;
            int fromY = Math.round(device.getDisplayHeight() * 0.78f);
            int toY = Math.round(device.getDisplayHeight() * 0.24f);
            for (int index = 0; index < swipes; index++) {
                device.swipe(x, fromY, x, toY, 24);
                device.waitForIdle(500L);
            }
            Thread.sleep(250L);
            if (!device.takeScreenshot(new File(directory, name + ".png"))) {
                throw new IllegalStateException("Could not capture " + name);
            }
        }
    }
}
